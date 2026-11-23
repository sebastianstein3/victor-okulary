package pl.victor.app.features.meeting

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Notatki ze spotkania: nagrywa mikrofonem telefonu, przepisuje na bieżąco,
 * na koniec robi podsumowanie z zadaniami i zapisuje je w notatkach.
 *
 * ## Czemu mikrofon telefonu, a nie okularów
 * Okulary nadają dźwięk tylko w trybie pytania (po przycisku albo "Hey
 * Lens") - przez godzinę spotkania nie nadają. Telefon leżący na stole
 * słyszy też rozmówców, a o nich na spotkaniu chodzi.
 *
 * ## Jak przepisuje
 * [SpeechSegmenter] tnie nagranie na wypowiedzi po pauzach, a każda idzie
 * do rozpoznawania mowy na telefonie ([przepisz]) - w trakcie spotkania, w
 * tle. Po zatrzymaniu zostaje tylko podsumowanie, a nie godzina
 * przepisywania.
 *
 * ## Mikrofon na czas pytania
 * Dopóki spotkanie trzyma mikrofon, rozpoznawanie mowy pytania dostaje ciszę -
 * w biegu 160 po "nagrywaj spotkanie" żadne pytanie, także "zakończ
 * spotkanie", nie doszło. Dlatego tura asystenta [wstrzymaj]-uje nagrywanie
 * (mikrofon jest zwalniany, przepisywanie czeka), a po turze [wznów]-uje.
 * Zakończenie działa też samo z siebie: gdy przepisana wypowiedź jest
 * poleceniem końca, [polecenie] dostaje ją od razu.
 *
 * ## Mówcy (płatne, z przełącznikiem)
 * Gdy [bezMówców] zwraca `null` przy starcie, wypowiedzi idą też do plików
 * fragmentów ([ZapisFragmentów]), a każdy pełny fragment (7 min MOWY) jest
 * rozpoznawany z podziałem na mówców W TRAKCIE spotkania, w tle. Dlaczego nie
 * dopiero po końcu: godzinne spotkanie to kilka zapytań po kilkadziesiąt
 * sekund, a po "zakończ spotkanie" czekałoby się na nie wszystkie przed
 * podsumowaniem. W trakcie zostaje tylko ostatni, niepełny fragment. Plik
 * fragmentu znika zaraz po udanym rozpoznaniu. Transkrypcja na żywo działa
 * jak dotąd (ekran, "zakończ spotkanie") i zostaje tam, gdzie fragment się
 * nie udał - patrz [SpeakerTranscript.scal].
 *
 * @param przepisz PCM 16 kHz mono -> tekst albo `null`
 * @param podsumuj polecenie -> notatka modelu albo `null`
 * @param polecenie każda przepisana wypowiedź - do wykrycia "zakończ spotkanie"
 */
class MeetingRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val przepisz: suspend (ByteArray) -> String?,
    private val podsumuj: suspend (String) -> String?,
    private val dziennik: (String, Map<String, Any?>) -> Unit,
    private val polecenie: (String) -> Unit = {},
    /** Czy wolno zdjąć pauzę z bezpiecznika - nie, gdy mikrofon należy do tłumaczenia albo tury. */
    private val wolnoWznowić: () -> Boolean = { true },
    /** `null` = rozpoznawać mówców; inaczej powód, czemu nie (do dziennika). Pytane przy starcie. */
    private val bezMówców: () -> String? = { "niedostępne" },
    /** Plik PCM fragmentu -> odpowiedź usługi z mówcami. */
    private val rozpoznajMówców: suspend (File) -> MeetingDiarizer.Wynik? = { null }
) {
    sealed class Stan {
        object Bezczynny : Stan()
        data class Nagrywa(val startMs: Long) : Stan()
        object Podsumowuje : Stan()
    }

    private val _stan = MutableStateFlow<Stan>(Stan.Bezczynny)
    val stan: StateFlow<Stan> = _stan.asStateFlow()

    private val _linie = MutableStateFlow<List<MeetingLine>>(emptyList())

    /** Przepisane wypowiedzi bieżącego spotkania - na żywo do ekranu. */
    val linie: StateFlow<List<MeetingLine>> = _linie.asStateFlow()

    val store = MeetingStore(context)

    init {
        // Spotkanie przerwane w poprzednim uruchomieniu (crash, zabity proces).
        runCatching { store.odzyskaj() }.getOrDefault(0).takeIf { it > 0 }?.let {
            dziennik("spotkanie: odzyskano przerwane nagrania", mapOf("ile" to it))
        }
    }

    private var ostatniZapisMs = 0L

    private var nagrywanie: Job? = null
    private var przepisywanie: Job? = null
    private var kolejka: Channel<Pair<Long, ShortArray>>? = null
    private var startMs = 0L
    @Volatile private var nieprzepisanych = 0

    /** Fragmenty do rozpoznania mówców - `null`, gdy spotkanie idzie bez nich. */
    private var mówcyKolejka: Channel<ZapisFragmentów.Fragment>? = null
    private var mówcy: Job? = null
    private val wynikiMówców: MutableList<SpeakerTranscript.WynikFragmentu> =
        java.util.Collections.synchronizedList(ArrayList<SpeakerTranscript.WynikFragmentu>())

    val nagrywa: Boolean get() = _stan.value is Stan.Nagrywa

    @Volatile
    private var wstrzymane = false

    @Volatile
    private var wstrzymaneOd = 0L

    /** Oddaje mikrofon (np. na czas pytania do asystenta). */
    fun wstrzymaj() {
        if (nagrywa && !wstrzymane) {
            wstrzymaneOd = System.currentTimeMillis()
            wstrzymane = true
            dziennik("spotkanie: mikrofon oddany na czas tury", emptyMap())
        }
    }

    /** Bierze mikrofon z powrotem po [wstrzymaj]. */
    fun wznów() {
        if (wstrzymane) {
            wstrzymane = false
            if (nagrywa) dziennik("spotkanie: nagrywam dalej", emptyMap())
        }
    }

    @SuppressLint("MissingPermission")
    private fun nowyMikrofon(): AudioRecord? {
        val bufor = AudioRecord.getMinBufferSize(HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bufor, HZ / 5 * 2)
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            return null
        }
        return rec
    }

    /** @return `false`, gdy mikrofon jest niedostępny (brak zgody, zajęty). */
    fun start(): Boolean {
        if (_stan.value != Stan.Bezczynny) return false
        val pierwszy = nowyMikrofon()
        if (pierwszy == null) {
            dziennik("spotkanie: mikrofon niedostępny", emptyMap())
            return false
        }
        wstrzymane = false
        startMs = System.currentTimeMillis()
        nieprzepisanych = 0
        _linie.value = emptyList()
        _stan.value = Stan.Nagrywa(startMs)
        val k = Channel<Pair<Long, ShortArray>>(Channel.UNLIMITED)
        kolejka = k
        val id = startMs.toString()
        val powód = runCatching { bezMówców() }.getOrElse { it.javaClass.simpleName }
        wynikiMówców.clear()
        val zapis = if (powód == null) uruchomMówców(id) else null
        dziennik("spotkanie: start", mapOf("mówcy" to (powód == null), "bezMówców" to powód))

        przepisywanie = scope.launch {
            for ((odStartu, próbki) in k) {
                // W trakcie tury rozpoznawanie mowy należy do pytania.
                while (wstrzymane && nagrywa) kotlinx.coroutines.delay(200)
                val tekst = runCatching { przepisz(SpeechSegmenter.doBajtów(próbki)) }.getOrNull()
                    ?.trim()?.takeIf { it.isNotEmpty() }
                if (tekst == null) {
                    nieprzepisanych++
                } else {
                    _linie.value = _linie.value + MeetingLine(odStartu, tekst.replaceFirstChar { it.uppercase() })
                    val teraz = System.currentTimeMillis()
                    if (teraz - ostatniZapisMs > ZAPIS_CO_MS) {
                        ostatniZapisMs = teraz
                        store.zapiszCzęściowo(
                            Meeting(startMs.toString(), startMs, teraz, _linie.value, null, nieprzepisanych)
                        )
                    }
                    runCatching { polecenie(tekst) }
                }
            }
        }

        nagrywanie = scope.launch(Dispatchers.IO) {
            var plikMówców = zapis
            // Każda wypowiedź: do przepisania na bieżąco i - przy mówcach - do pliku fragmentu.
            fun wypowiedź(odStartu: Long, próbki: ShortArray) {
                k.trySend(odStartu to próbki)
                val z = plikMówców ?: return
                try {
                    z.dopisz(odStartu, SpeechSegmenter.doBajtów(próbki))
                } catch (e: Exception) {
                    // Pełny dysk nie może zatrzymać nagrywania - spotkanie idzie dalej bez mówców.
                    plikMówców = null
                    dziennik("spotkanie: mówcy - zapis nagrania nie działa", mapOf("błąd" to e.javaClass.simpleName))
                }
            }
            val segmenter = SpeechSegmenter(HZ)
            val ramka = ShortArray(HZ / 50) // 20 ms
            var przeczytane = 0L
            var rec: AudioRecord? = pierwszy
            try {
                pierwszy.startRecording()
                while (isActive && _stan.value is Stan.Nagrywa) {
                    // Bezpiecznik: tura, która nie oddała mikrofonu (wyjątek,
                    // przerwanie), nie może po cichu zatrzymać spotkania na zawsze.
                    if (wstrzymane && System.currentTimeMillis() - wstrzymaneOd > MAKS_PAUZA_MS && wolnoWznowić()) {
                        wstrzymane = false
                        dziennik("spotkanie: wracam do nagrywania po zbyt długiej pauzie", emptyMap())
                    }
                    if (wstrzymane) {
                        // Zwalniamy mikrofon naprawdę - samo nieczytanie go
                        // nie oddaje rozpoznawaniu mowy.
                        rec?.let { r ->
                            runCatching { r.stop() }
                            r.release()
                            rec = null
                            segmenter.domknij()?.let { wypowiedź(przeczytane * 1000 / HZ, it) }
                        }
                        kotlinx.coroutines.delay(100)
                        continue
                    }
                    val r = rec ?: nowyMikrofon()?.also {
                        it.startRecording()
                        rec = it
                    }
                    if (r == null) {
                        kotlinx.coroutines.delay(500)
                        continue
                    }
                    val n = r.read(ramka, 0, ramka.size)
                    if (n <= 0) continue
                    przeczytane += n
                    val kawałek = if (n == ramka.size) ramka.copyOf() else ramka.copyOf(n)
                    segmenter.podaj(kawałek)?.let { kawałekMowy ->
                        // Zegar ścienny, nie liczba próbek: pauzy (tura asystenta)
                        // nie dodają próbek i znaczniki czasu się rozjeżdżały.
                        val koniecMs = System.currentTimeMillis() - startMs
                        wypowiedź(koniecMs - kawałekMowy.size * 1000L / HZ, kawałekMowy)
                    }
                }
                segmenter.domknij()?.let { wypowiedź(System.currentTimeMillis() - startMs, it) }
            } finally {
                rec?.let { r ->
                    runCatching { r.stop() }
                    r.release()
                }
                k.close()
                // Ostatni, niepełny fragment też idzie do rozpoznania.
                runCatching { plikMówców?.domknij() }
                mówcyKolejka?.close()
            }
        }
        return true
    }

    /**
     * Kolejka i pętla rozpoznawania mówców - fragment po fragmencie, w tle.
     *
     * @return zapis fragmentów dla pętli nagrywania
     */
    private fun uruchomMówców(id: String): ZapisFragmentów {
        val q = Channel<ZapisFragmentów.Fragment>(Channel.UNLIMITED)
        mówcyKolejka = q
        mówcy = scope.launch {
            for (f in q) {
                val sekund = f.plik.length() / SpeakerTranscript.BAJTÓW_NA_SEKUNDĘ
                val w = runCatching { rozpoznajMówców(f.plik) }.getOrNull()
                val o = w?.odpowiedź?.takeIf { it.maMówców }
                val linie = o?.let { SpeakerTranscript.linie(it.słowa, f.ułożenie, f.nr) }?.takeIf { it.isNotEmpty() }
                val (odMs, doMs) = SpeakerTranscript.zakres(f.ułożenie)
                wynikiMówców.add(SpeakerTranscript.WynikFragmentu(f.nr, odMs, doMs, linie))
                // Udany - nagranie nie jest już potrzebne. Nieudany zostaje do
                // końca spotkania (patrz [stop]), żeby dało się go obejrzeć w razie błędu.
                if (linie != null) f.plik.delete()
                dziennik(
                    "spotkanie: mówcy - fragment",
                    mapOf(
                        "nr" to f.nr, "s" to sekund, "wypowiedzi" to f.ułożenie.size,
                        "ms" to w?.ms, "prób" to w?.prób, "osób" to (o?.mówców ?: 0),
                        "słów" to (w?.odpowiedź?.słowa?.size ?: 0), "linii" to (linie?.size ?: 0),
                        "błąd" to (w?.błąd ?: if (w == null) "brak wyniku" else null)
                    )
                )
            }
        }
        return ZapisFragmentów(
            plikDla = { nr -> store.plikFragmentu(id, nr) },
            gotowy = { q.trySend(it) }
        )
    }

    /**
     * Zatrzymuje, czeka na przepisanie reszty, robi podsumowanie i zapisuje.
     *
     * @return zapisane spotkanie albo `null`, gdy nic nie nagrywało
     */
    suspend fun stop(): Meeting? {
        if (_stan.value !is Stan.Nagrywa) return null
        val koniec = System.currentTimeMillis()
        wstrzymane = false
        _stan.value = Stan.Podsumowuje
        nagrywanie?.join()
        przepisywanie?.join()
        // Ostatni fragment mówców - zwykle jedno zapytanie, reszta poszła w trakcie.
        // Z limitem: zawieszona usługa nie może trzymać podsumowania w nieskończoność -
        // fragmenty bez wyniku biorą wtedy linie przepisane na bieżąco.
        mówcy?.let { job ->
            if (kotlinx.coroutines.withTimeoutOrNull(MAKS_CZEKANIE_NA_MÓWCÓW_MS) { job.join() } == null) {
                job.cancel()
                dziennik("spotkanie: mówcy - koniec czekania", mapOf("s" to MAKS_CZEKANIE_NA_MÓWCÓW_MS / 1000))
            }
        }
        mówcy = null
        mówcyKolejka = null
        val żywe = _linie.value
        // Jedna pętla rozpoznawania, fragment po fragmencie - kolejność jest już po numerach.
        val wyniki: List<SpeakerTranscript.WynikFragmentu> = synchronized(wynikiMówców) { wynikiMówców.toList() }
        val linie = SpeakerTranscript.scal(żywe, wyniki)
        // Nieudane fragmenty też: nie ma drogi, żeby je ponowić, a to nagranie
        // głosu ludzi przy stole - nie zostaje w telefonie bez potrzeby.
        val usunięte = withContext(Dispatchers.IO) { runCatching { store.usuńNagrania(startMs.toString()) }.getOrDefault(0) }
        if (wyniki.isNotEmpty()) {
            dziennik(
                "spotkanie: mówcy - wynik",
                mapOf(
                    "fragmentów" to wyniki.size,
                    "udanych" to wyniki.count { it.linie != null },
                    "linii" to linie.count { it.mówca != null },
                    "zastąpionych" to (żywe.size - linie.count { it.mówca == null }),
                    "usuniętychNagrań" to usunięte
                )
            )
        }
        dziennik(
            "spotkanie: koniec nagrywania",
            mapOf("min" to (koniec - startMs) / 60_000, "wypowiedzi" to linie.size, "nieprzepisanych" to nieprzepisanych)
        )
        val podsumowanie = if (linie.isEmpty()) {
            null
        } else {
            runCatching { podsumuj(MeetingNotes.polecenie(linie, koniec - startMs)) }.getOrNull()
        }
        val m = Meeting(
            id = startMs.toString(),
            startMs = startMs,
            koniecMs = koniec,
            linie = linie,
            podsumowanie = podsumowanie,
            nieprzepisanych = nieprzepisanych
        )
        withContext(Dispatchers.IO) {
            // Kopię roboczą kasujemy TYLKO po udanym zapisie - przy pełnym
            // dysku znikała razem z całą transkrypcją (przegląd 22.11).
            if (store.zapisz(m)) store.usuńCzęściowe(m.id)
        }
        _stan.value = Stan.Bezczynny
        return m
    }

    companion object {
        const val HZ = 16_000
        private const val MAKS_PAUZA_MS = 120_000L

        /** Najdłużej czekamy po końcu spotkania na rozpoznanie mówców (ostatni fragment, może i przedostatni). */
        private const val MAKS_CZEKANIE_NA_MÓWCÓW_MS = 240_000L

        /** Jak często zapisujemy stan spotkania w trakcie. */
        private const val ZAPIS_CO_MS = 15_000L
        private const val TAG = "MeetingRecorder"
    }
}
