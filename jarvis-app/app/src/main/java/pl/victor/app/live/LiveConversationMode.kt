package pl.victor.app.live

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.conversation.MetaCommands
import pl.victor.app.data.SettingsRepository
import pl.victor.app.translation.LiveTranslationProtocol
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tryb "rozmowa na żywo" - płynna rozmowa głosowa z asystentem przez Gemini
 * Live, z wchodzeniem w słowo.
 *
 * ## Czym to się różni od zwykłej tury
 * Zwykła tura to "nasłuch -> transkrypcja -> model -> synteza", każdy krok
 * czeka na poprzedni i asystentowi nie da się przerwać. Tu dźwięk płynie do
 * serwera bez przerwy, odpowiedź wraca głosem od razu, a gdy człowiek zaczyna
 * mówić, serwer zgłasza `interrupted` i odtwarzacz jest natychmiast czyszczony.
 *
 * ## Mikrofon
 * Tryb SAM trzyma mikrofon: przejmuje go od nasłuchu frazy i wybudzenia z
 * okularów ([przejmijMikrofon]) i oddaje dopiero na końcu. Orkiestrator na
 * czas trybu nie wpuszcza żadnej tury (patrz `handleUserTrigger` i
 * `startVoiceTurn`) - inaczej "Hej Wiktor" albo tryb konwersacyjny biłyby
 * się z sesją o jeden AudioRecord.
 *
 * ## Dźwięk
 * Najpierw łącze rozmowy (SCO) okularów - ich mikrofon i głośnik z
 * systemowym tłumieniem echa. Gdy nie wstanie w [LiveConversationLogic.SCO_LIMIT_MS]:
 * mikrofon telefonu i [BramkaEcha], jak w tłumaczeniu na żywo.
 *
 * Wszystkie decyzje są w [LiveConversationLogic] / [WykrywaczKońca] /
 * [BramkaEcha] - tu jest sama pętla.
 */
class LiveConversationMode(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val audio: pl.victor.app.audio.AudioManager,
    /** Wpis w dzienniku diagnostycznym (etap ustala orkiestrator). */
    private val dziennik: (String, Map<String, Any?>) -> Unit,
    /** Zabiera mikrofon nasłuchowi frazy, wybudzeniu z okularów i trybowi konwersacyjnemu. */
    private val przejmijMikrofon: () -> Unit,
    /** Odwrotność [przejmijMikrofon]. */
    private val oddajMikrofon: () -> Unit,
    /** Instrukcja systemowa sesji (persona + czas + miejsce). */
    private val instrukcja: suspend () -> String,
    /** Wymiana do kontekstu zwykłych tur - żeby po rozmowie asystent pamiętał, o czym była. */
    private val zapiszWymianę: (String, String) -> Unit,
    private val zgodaNaMikrofon: () -> Boolean
) {
    private val tag = "LiveConversation"

    private val _aktywna = MutableStateFlow(false)

    /** Czy trwa rozmowa na żywo - dla interfejsu i strażników w orkiestratorze. */
    val aktywna: StateFlow<Boolean> = _aktywna.asStateFlow()

    private var job: Job? = null

    /** Powód podany przez [stop] - trafia do wpisu "koniec". */
    @Volatile
    private var powódStopu: String? = null

    /** Czy wolno teraz zacząć (przełącznik, "tylko darmowe", klucz, zgoda). */
    fun dostępność(): LiveConversationLogic.Dostępność = LiveConversationLogic.dostępność(
        tylkoDarmowe = settings.isFreeOnly(),
        włączona = settings.isLiveConversationEnabled(),
        kluczGemini = settings.getApiKey("gemini"),
        zgodaNaMikrofon = zgodaNaMikrofon()
    )

    /**
     * Zaczyna rozmowę.
     *
     * @param turaWToku tura, z której wnętrza przyszło polecenie głosowe -
     *   pętla czeka, aż skończy sprzątać (łącze audio, mikrofon frazy), jak w
     *   tłumaczeniu ze słuchu (bieg 152)
     * @return `false`, gdy rozmowy nie da się zacząć - komunikat został już
     *   powiedziany, zostaje zwykły tryb pytań
     */
    fun start(turaWToku: Job? = null, źródło: String): Boolean {
        if (_aktywna.value) return true
        val d = dostępność()
        if (d != LiveConversationLogic.Dostępność.MOŻNA) {
            dziennik("rozmowa na żywo: niedostępna", mapOf("powód" to d.name, "źródło" to źródło))
            d.komunikat?.let { audio.speak(it, language = settings.getResponseLanguage()) }
            return false
        }
        val klucz = settings.getApiKey("gemini").orEmpty()
        powódStopu = null
        _aktywna.value = true
        // LAZY i start() po przypisaniu: pętla porównuje się z [job].
        job = scope.launch(start = CoroutineStart.LAZY) { pętla(klucz, turaWToku, źródło) }
        job?.start()
        return true
    }

    /** Kończy rozmowę (przycisk, dotyk skroni, ekran). Można wołać wiele razy. */
    fun stop(powód: String) {
        if (!_aktywna.value && job == null) return
        if (powódStopu == null) powódStopu = powód
        Log.i(tag, "Rozmowa na żywo - stop ($powód)")
        _aktywna.value = false
        job?.cancel()
        job = null
    }

    private suspend fun pętla(klucz: String, turaWToku: Job?, źródło: String) {
        val mójJob = currentCoroutineContext()[Job]
        val od = System.currentTimeMillis()
        val język = settings.getResponseLanguage()
        var sesja: LiveConversationSession? = null
        var wybór: LiveConversationLogic.WybórDźwięku? = null
        var łączeWzięte = false
        var mikrofonWzięty = false
        var powódKońca: String? = null
        var pożegnanie: String? = "Kończę rozmowę."
        val wysłane = AtomicInteger()
        val wyciszone = AtomicInteger()
        val odebrane = AtomicInteger()
        val przerwania = AtomicInteger()
        var wymian = 0
        val bramka = BramkaEcha()
        try {
            // Polecenie głosowe przychodzi z wnętrza tury - niech najpierw
            // odda łącze i mikrofon frazy (patrz bieg 152 w tłumaczeniu).
            if (turaWToku != null && turaWToku !== mójJob) {
                withTimeoutOrNull(TURA_MS) { turaWToku.join() }
            }
            przejmijMikrofon()
            mikrofonWzięty = true

            coroutineScope {
                val instrukcjaGotowa = async { instrukcja() }

                // 1. DROGA DŹWIĘKU - najpierw łącze rozmowy okularów.
                val scoOd = System.currentTimeMillis()
                val maZestaw = audio.hasBluetoothAudioDevice()
                val przyjęte: Boolean? = if (!maZestaw) {
                    false
                } else {
                    withTimeoutOrNull(LiveConversationLogic.SCO_LIMIT_MS) {
                        audio.beginConversationRouting(waitForLink = true)
                    }
                }
                if (przyjęte == true) łączeWzięte = true
                // `true` z prośby znaczy "przyjęte", nie "stoi" - dopytujemy,
                // aż wstanie albo minie limit.
                var wstało = false
                if (przyjęte == true) {
                    while (true) {
                        if (audio.isConversationLinkUp()) {
                            wstało = true
                            break
                        }
                        if (System.currentTimeMillis() - scoOd >= LiveConversationLogic.SCO_LIMIT_MS) break
                        delay(SCO_POLL_MS)
                    }
                }
                val w = LiveConversationLogic.wybierzDźwięk(maZestaw, przyjęte, wstało)
                wybór = w
                if (łączeWzięte && w.tryb != LiveConversationLogic.TrybDźwięku.SCO) {
                    // Zamówione, a nie stoi: telefon zostałby w trybie rozmowy
                    // i odpowiedź z telefonu szłaby w próżnię.
                    audio.resetConversationRouting()
                    łączeWzięte = false
                }
                dziennik(
                    "rozmowa na żywo: start",
                    mapOf(
                        "tryb" to w.tryb.opis, "powód" to w.powód, "źródło" to źródło,
                        "scoMs" to (System.currentTimeMillis() - scoOd),
                        "model" to LiveConversationProtocol.MODEL, "głos" to LiveConversationProtocol.GŁOS
                    )
                )

                // 2. SESJA - łączymy się w trakcie zapowiedzi.
                val s = LiveConversationSession(context, klucz, instrukcjaGotowa.await(), w.tryb)
                sesja = s
                val połączenieOd = System.currentTimeMillis()
                val połączenie = async { s.połącz(CONNECT_MS) }
                audio.speakAndAwait(ZAPOWIEDŹ, language = język)
                val połączona = połączenie.await()
                dziennik(
                    "rozmowa na żywo: połączenie",
                    mapOf(
                        "ok" to połączona, "ms" to (System.currentTimeMillis() - połączenieOd),
                        "powód" to s.powódZamknięcia
                    )
                )
                if (!połączona) {
                    powódKońca = "brak połączenia: ${s.powódZamknięcia}"
                    pożegnanie = "Nie udało się połączyć z Gemini. Pytaj mnie jak zwykle."
                    return@coroutineScope
                }
                if (!withContext(Dispatchers.IO) { s.otwórzMikrofon() }) {
                    powódKońca = "mikrofon zajęty"
                    pożegnanie = "Mikrofon jest zajęty - nie mogę rozmawiać na żywo."
                    return@coroutineScope
                }
                dziennik(
                    "rozmowa na żywo: mikrofon otwarty",
                    mapOf(
                        "źródło" to s.źródłoMikrofonu(), "tłumikEcha" to s.maTłumikEcha(),
                        "głośnikTelefonu" to s.graNaGłośnikuTelefonu()
                    )
                )

                // 3. PĘTLA
                val doZagrania = Channel<ByteArray>(Channel.UNLIMITED)
                val telefon = w.tryb == LiveConversationLogic.TrybDźwięku.TELEFON
                val odtwarzanie = launch(Dispatchers.IO) {
                    for (pcm in doZagrania) s.graj(pcm)
                }
                val nadawanie = launch(Dispatchers.IO) {
                    val kawałek = ByteArray(s.bajtówKawałka)
                    val cisza = ByteArray(s.bajtówKawałka)
                    var odPodsumowania = System.currentTimeMillis()
                    var oknoWysłane = 0
                    var oknoWyciszone = 0
                    var maksPoziom = 0
                    var sumaPoziomów = 0L
                    var kawałkówOkna = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val pcm = s.czytaj(kawałek) ?: break
                        val teraz = System.currentTimeMillis()
                        val poziom = s.poziom(pcm)
                        maksPoziom = maxOf(maksPoziom, poziom)
                        sumaPoziomów += poziom
                        kawałkówOkna++
                        // Przez SCO echo tłumi system; przez telefon - bramka.
                        val przepuść = !telefon ||
                            bramka.przepuść(teraz, s.graTeraz(teraz - ECHO_OGON_MS), poziom)
                        if (!s.wyślij(if (przepuść) pcm else cisza)) break
                        if (przepuść) {
                            wysłane.incrementAndGet()
                            oknoWysłane++
                        } else {
                            wyciszone.incrementAndGet()
                            oknoWyciszone++
                        }
                        if (teraz - odPodsumowania >= PODSUMOWANIE_MS) {
                            dziennik(
                                "rozmowa na żywo: mikrofon",
                                mapOf(
                                    "maksPoziom" to maksPoziom,
                                    "średniPoziom" to (sumaPoziomów / maxOf(1, kawałkówOkna)),
                                    "wysłane" to oknoWysłane, "wyciszone" to oknoWyciszone,
                                    "odebrane" to odebrane.get(), "przerwania" to przerwania.get(),
                                    "przebicia" to bramka.przebicia, "gra" to s.graTeraz(teraz)
                                )
                            )
                            odPodsumowania = teraz
                            oknoWysłane = 0
                            oknoWyciszone = 0
                            maksPoziom = 0
                            sumaPoziomów = 0L
                            kawałkówOkna = 0
                        }
                    }
                }

                val wymiana = LiveExchangeBuffer()
                val koniec = WykrywaczKońca(MetaCommands::endsLiveConversation)

                fun zapisz(para: Pair<String, String>?) {
                    val (ty, asystent) = para ?: return
                    wymian++
                    runCatching { zapiszWymianę(ty, asystent) }
                    dziennik(
                        "rozmowa na żywo: wymiana",
                        mapOf("ty" to ty.take(80), "asystent" to asystent.take(80))
                    )
                }
                var ostatniaAktywność = System.currentTimeMillis()
                while (powódKońca == null) {
                    if (!_aktywna.value || job !== mójJob) {
                        powódKońca = powódStopu ?: "zatrzymana"
                        break
                    }
                    val wiadomość = withTimeoutOrNull(POLL_MS) { s.zdarzenia.receiveCatching() }
                    val teraz = System.currentTimeMillis()
                    when (val z = wiadomość?.let { it.getOrNull() ?: Zdarzenie.Zamknięte }) {
                        is Zdarzenie.Dźwięk -> {
                            doZagrania.trySend(z.pcm)
                            odebrane.incrementAndGet()
                            ostatniaAktywność = teraz
                        }
                        is Zdarzenie.Oryginał -> {
                            zapisz(wymiana.dodajUżytkownika(z.tekst))
                            koniec.usłyszane(z.tekst, teraz)
                            ostatniaAktywność = teraz
                        }
                        is Zdarzenie.Przekład -> {
                            wymiana.dodajAsystenta(z.tekst)
                            ostatniaAktywność = teraz
                        }
                        Zdarzenie.Przerwane -> {
                            // WEJŚCIE W SŁOWO: wycinamy to, co czeka w kolejce
                            // i w odtwarzaczu - natychmiast, zanim przyjdzie
                            // cokolwiek nowego.
                            var wycięte = 0
                            while (doZagrania.tryReceive().isSuccess) wycięte++
                            s.wyczyśćOdtwarzanie()
                            przerwania.incrementAndGet()
                            dziennik(
                                "rozmowa na żywo: wejście w słowo",
                                mapOf("wycięteKawałki" to wycięte, "asystent" to wymiana.asystentTeraz.takeLast(60))
                            )
                        }
                        Zdarzenie.KoniecTury -> {
                            koniec.koniecTury()
                            zapisz(wymiana.zamknij())
                        }
                        Zdarzenie.Zamknięte -> {
                            powódKońca = "serwer zamknął: ${s.powódZamknięcia ?: "bez powodu"}"
                            pożegnanie = "Gemini zakończył rozmowę. Pytaj mnie jak zwykle."
                        }
                        is Zdarzenie.Błąd -> dziennik("rozmowa na żywo: błąd serwera", mapOf("opis" to z.opis.take(160)))
                        else -> Unit
                    }
                    if (powódKońca != null) break
                    if (koniec.czasKończyć(teraz)) {
                        powódKońca = "polecenie głosowe"
                        dziennik("rozmowa na żywo: polecenie końca", mapOf("usłyszane" to koniec.ogonTeraz.takeLast(60)))
                    } else if (LiveConversationLogic.czasKończyćPoCiszy(teraz, ostatniaAktywność, s.graTeraz(teraz))) {
                        powódKońca = "${LiveConversationLogic.CISZA_KOŃCZY_MS / 60_000} min ciszy"
                        pożegnanie = "Dwie minuty ciszy - kończę rozmowę."
                    } else if (!nadawanie.isActive) {
                        powódKońca = "mikrofon przestał działać"
                        pożegnanie = "Mikrofon przestał działać - kończę rozmowę."
                    }
                }
                // Koniec - najpierw cisza w odtwarzaczu, żeby pożegnanie nie
                // nałożyło się na resztę odpowiedzi.
                zapisz(wymiana.zamknij())
                nadawanie.cancel()
                doZagrania.close()
                odtwarzanie.cancel()
                s.wyczyśćOdtwarzanie()
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { withContext(Dispatchers.IO) { sesja?.zamknij() } }
                if (łączeWzięte) runCatching { audio.endConversationRouting() }
                val nasza = job === mójJob || job == null
                if (mikrofonWzięty && nasza) runCatching { oddajMikrofon() }
                if (nasza) {
                    _aktywna.value = false
                    job = null
                }
                val s = sesja
                runCatching {
                    dziennik(
                        "rozmowa na żywo: koniec",
                        mapOf(
                            "powód" to (powódKońca ?: powódStopu ?: "przerwana"),
                            "tryb" to wybór?.tryb?.opis,
                            "sekund" to ((System.currentTimeMillis() - od) / 1000),
                            "minutWejścia" to LiveConversationLogic.minutyZKawałków(
                                wysłane.get() + wyciszone.get(), LiveTranslationProtocol.KAWAŁEK_MS
                            ),
                            "minutWyjścia" to LiveConversationLogic.minutyZBajtów(
                                s?.wyjścieBajtów ?: 0L, LiveTranslationProtocol.PRÓBKOWANIE_WYJŚCIA
                            ),
                            "wymian" to wymian,
                            "przerwania" to przerwania.get(),
                            "przebicia" to bramka.przebicia
                        )
                    )
                }
                // Tylko nasza sesja się żegna: po szybkim stop/start "Kończę
                // rozmowę." starej sesji nakładało się na zapowiedź nowej.
                if (nasza) pożegnanie?.let { tekst -> runCatching { audio.speak(tekst, language = język) } }
            }
        }
    }

    companion object {
        /** Ile czekamy, aż tura, która włączyła tryb, skończy sprzątać. */
        private const val TURA_MS = 5_000L

        /** Co ile sprawdzamy, czy łącze SCO już stoi. */
        private const val SCO_POLL_MS = 100L

        /** Limit na połączenie z Gemini Live i jego `setupComplete`. */
        private const val CONNECT_MS = 8_000L

        /** Co ile pętla zdarzeń sprawdza ciszę i polecenie końca. */
        private const val POLL_MS = 250L

        /** Podsumowanie mikrofonu w dzienniku. */
        private const val PODSUMOWANIE_MS = 10_000L

        /** Ile po słyszalnej odpowiedzi mikrofon telefonu może jeszcze słyszeć jej echo. */
        private const val ECHO_OGON_MS = 500L

        const val ZAPOWIEDŹ = "Rozmawiamy na żywo. Żeby skończyć, powiedz: koniec rozmowy."
    }
}
