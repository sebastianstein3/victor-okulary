package pl.victor.app.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.FloatBuffer

/**
 * Wykrywanie frazy „Hej Wiktor" na telefonie - openWakeWord przez ONNX Runtime.
 *
 * ## Po co
 * Wcześniej były tu dwa zewnętrzne silniki: jeden wymagał klucza dostępu,
 * którego darmowe wersje przestały działać 30.06.2026, drugi był pełnym
 * rozpoznawaniem mowy zawężonym do jednej frazy - ciężkim dla baterii i z
 * modelem pobieranym osobno (historia przejścia: SettingsRepository.getWakeEngine).
 * openWakeWord to wyspecjalizowany detektor: trzy małe sieci, całość
 * na urządzeniu, offline, bez kont, kluczy i opłat.
 *
 * ## Modele (w APK, katalog assets `wakeword/`)
 * - `melspectrogram.onnx` i `embedding_model.onnx` - modele bazowe openWakeWord
 *   (github.com/dscripka/openWakeWord, wydanie v0.5.1). Kod openWakeWord jest
 *   na Apache-2.0, ale jego README obejmuje dołączone modele licencją
 *   CC BY-NC-SA 4.0 (użytek niekomercyjny, na tych samych warunkach).
 * - `hej_wiktor.onnx` - nasz klasyfikator frazy (skrypt i pomiary:
 *   tools/wakeword/). Uczony na cechach openwakeword_features (CC BY-NC-SA 4.0)
 *   i głosach Piper - traktujemy go jak niekomercyjny. Aplikacja komercyjna
 *   musiałaby wytrenować go na danych o swobodnej licencji.
 *   Gdy pliku nie ma, [start] odmawia z komunikatem "brak modelu", aplikacja
 *   działa dalej (fraza okularów i przycisk), a Ustawienia pokazują ten stan.
 *
 * ## Wątki
 * Mikrofon czyta osobny wątek - jeden na każde [start]. Każdy start ma własną
 * [Sesja] z własną flagą, bo [stop] w trakcie negocjowania łącza Bluetooth (do
 * kilku sekund) nie może dopuścić, żeby stary wątek ruszył nagrywanie po
 * starcie nowego. Wykrycie idzie na wątek główny: reakcja na nie zwykle
 * zatrzymuje nasłuch, a zatrzymanie czeka na wątek mikrofonu - z jego wnętrza
 * czekałby sam na siebie.
 *
 * ## Mikrofon okularów
 * Gdy włączone jest [pl.victor.app.data.SettingsRepository.isWakeWordOverGlassesMic],
 * wątek najpierw prosi [pl.victor.app.audio.BluetoothAudioRouter] o łącze
 * rozmowy i nagrywa przez nie; bez zestawu wraca na mikrofon telefonu. Koszt
 * (okulary w trybie rozmowy przez cały czas) jest opisany przy tym ustawieniu.
 * Nauka głosu ([nagraj]) nagrywa TYM SAMYM mikrofonem i źródłem, co nasłuch -
 * weryfikator ma znać dźwięk dokładnie taki, jaki potem dostaje.
 *
 * ## Weryfikator głosu właściciela
 * Gdy użytkownik nagrał swoje „Hej Wiktor” (Ustawienia, "Naucz mój głos" -
 * [NaukaGlosu]), w [MagazynGlosu] leży [WeryfikatorGlosu], a o wybudzeniu
 * decyduje [DecyzjaWybudzenia]: niższy próg dla potwierdzonego głosu
 * właściciela, weto dla dźwięków, które weryfikator mocno odrzuca. Bez
 * nagrań wszystko działa jak dawniej.
 */
class OpenWakeWord(private val context: Context) {

    private val tag = "OpenWakeWord"
    private val settings = pl.victor.app.data.SettingsRepository.getInstance(context)
    private val bluetoothRouter = pl.victor.app.audio.BluetoothAudioRouter.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Wczytane sieci - wspólne dla kolejnych startów; zwalnia je [release]. */
    private class Modele(
        val env: OrtEnvironment,
        val mel: OrtSession,
        val embedding: OrtSession,
        val klasyfikator: OrtSession,
        val oknoKlasyfikatora: Int
    ) {
        fun close() {
            runCatching { mel.close() }
            runCatching { embedding.close() }
            runCatching { klasyfikator.close() }
        }
    }

    @Volatile
    private var modele: Modele? = null

    /** Opis ostatniego nieudanego wczytania modeli - żeby nie próbować co chwilę. */
    @Volatile
    private var bladWczytania: String? = null

    /** Jedno uruchomienie nasłuchu - patrz opis klasy, akapit "Wątki". */
    private class Sesja {
        @Volatile
        var aktywna = true

        @Volatile
        var nagrywa = false
        var watek: Thread? = null
    }

    @Volatile
    private var sesja: Sesja? = null

    /** Ostatni błąd - do ustawień i diagnostyki; `null`, gdy wszystko w porządku. */
    @Volatile
    var lastError: String? = null
        private set

    /** Czy wątek nasłuchu w tej chwili czyta mikrofon. */
    val isListening: Boolean get() = sesja?.nagrywa == true

    /**
     * Podsumowanie wyników do dziennika diagnostycznego - najwyżej co
     * [DIAG_INTERVAL_MS]. Wołane z wątku nasłuchu.
     */
    @Volatile
    var onDiag: ((String, Map<String, Any?>) -> Unit)? = null

    /** Wołane z wątku nasłuchu, gdy ten skończył się sam (z opisem błędu). */
    @Volatile
    var onZakonczonySam: ((String?) -> Unit)? = null

    /** Pliki nauczonego głosu - patrz [NaukaGlosu]. */
    val magazynGlosu = MagazynGlosu(File(context.filesDir, MagazynGlosu.KATALOG))

    /**
     * Weryfikator z [magazynGlosu] albo `null`. Czytany przy każdej porcji, więc
     * [przeladujWeryfikator] działa od razu, także w trakcie nasłuchu.
     */
    @Volatile
    private var weryfikator: WeryfikatorGlosu? = null

    @Volatile
    private var weryfikatorWczytany = false

    /** Weryfikator, którego używa nasłuch, albo `null` (brak nagrań albo jeszcze nie wczytany). */
    val aktualnyWeryfikator: WeryfikatorGlosu? get() = weryfikator

    /**
     * Ostatnie wykrycie - do przycisku "To było fałszywe wybudzenie": wynik,
     * decyzja i okna cech, które je dały (kopie). `null` po oznaczeniu.
     */
    class OstatnieWybudzenie(
        val czasMs: Long,
        val wynik: Float,
        val weryfikator: Float?,
        val decyzja: DecyzjaWybudzenia.Decyzja,
        val okna: List<FloatArray>
    )

    @Volatile
    var ostatnieWybudzenie: OstatnieWybudzenie? = null

    /**
     * Wczytuje weryfikator od nowa - po nauce, korekcie albo usunięciu nagrań.
     * Wolno wołać z dowolnego wątku (plik ma kilkanaście kB).
     */
    fun przeladujWeryfikator(): WeryfikatorGlosu? {
        val w = magazynGlosu.wczytajWeryfikator()
        weryfikator = w
        weryfikatorWczytany = true
        onDiag?.invoke(
            "weryfikator głosu " + if (w != null) "wczytany" else "nieaktywny (brak nagrań)",
            mapOf(
                "tylkoMójGłos" to (settings.isWakeOnlyMyVoice() && w != null),
                "wymiar" to w?.wymiar
            )
        )
        return w
    }

    /** Czy wszystkie trzy pliki modeli są w APK (bez wczytywania). */
    fun hasModelFiles(): Boolean {
        val pliki = runCatching { context.assets.list(ASSET_DIR)?.toSet() }.getOrNull() ?: return false
        return MEL_ASSET in pliki && EMBEDDING_ASSET in pliki && CLASSIFIER_ASSET in pliki
    }

    /** Czy brakuje samego klasyfikatora frazy - najczęstszy stan, dopóki trwa trening. */
    fun isClassifierMissing(): Boolean {
        val pliki = runCatching { context.assets.list(ASSET_DIR)?.toSet() }.getOrNull() ?: return true
        return CLASSIFIER_ASSET !in pliki
    }

    /**
     * Czy modele są na miejscu i dają się wczytać. Pierwsze wywołanie wczytuje
     * sieci (ułamek sekundy), kolejne korzystają z wczytanych - lepiej wołać
     * poza wątkiem głównym.
     */
    fun isModelReady(): Boolean = modelProblem() == null

    /**
     * Co przeszkadza modelom - do pokazania w ustawieniach - albo `null`, gdy
     * są gotowe. Te same zastrzeżenia co przy [isModelReady].
     */
    fun modelProblem(): String? = ensureLoaded()

    /**
     * Wczytuje modele, jeśli jeszcze nie są.
     *
     * @return opis błędu albo `null`, gdy gotowe
     */
    @Synchronized
    private fun ensureLoaded(): String? {
        if (modele != null) return null
        bladWczytania?.let { return it }
        if (!hasModelFiles()) {
            return if (isClassifierMissing()) BRAK_MODELU else "Brak modeli bazowych openWakeWord w APK."
        }
        val otwarte = mutableListOf<OrtSession>()
        return try {
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { opcje ->
                // Jeden wątek: sieci są małe, a nasłuch trwa godzinami - więcej
                // wątków to więcej baterii, nie szybsza reakcja.
                opcje.setIntraOpNumThreads(1)
                opcje.setInterOpNumThreads(1)
                val mel = env.createSession(readAsset(MEL_ASSET), opcje).also { otwarte += it }
                val embedding = env.createSession(readAsset(EMBEDDING_ASSET), opcje).also { otwarte += it }
                val klasyfikator = env.createSession(readAsset(CLASSIFIER_ASSET), opcje).also { otwarte += it }
                // Okno klasyfikatora czytamy z jego wejścia [1, N, 96] zamiast
                // zakładać 16 - model trenowany z innym oknem też zadziała.
                val okno = runCatching {
                    val info = klasyfikator.inputInfo.values.first().info as TensorInfo
                    info.shape.getOrNull(1)?.toInt()?.takeIf { it > 0 }
                }.getOrNull() ?: OwwPipeline.FEATURE_WINDOW
                modele = Modele(env, mel, embedding, klasyfikator, okno)
                Log.i(
                    tag,
                    "Modele wczytane: wejścia mel=${mel.inputNames} osadzenia=${embedding.inputNames} " +
                        "klasyfikator=${klasyfikator.inputNames}, okno=$okno"
                )
            }
            null
        } catch (e: Throwable) {
            // Sesje otwarte przed błędem nie mogą zostać w pamięci natywnej.
            otwarte.forEach { runCatching { it.close() } }
            Log.e(tag, "Nie udało się wczytać modeli", e)
            "Model nie daje się wczytać: ${e.message ?: e::class.java.simpleName}".also {
                bladWczytania = it
            }
        }
    }

    private fun readAsset(name: String): ByteArray =
        context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }

    /**
     * Zaczyna nasłuch frazy w tle.
     *
     * Szybkie warunki (zgoda na mikrofon, pliki modeli) sprawdza od razu; sieci
     * wczytuje i mikrofon otwiera już wątek nasłuchu. Błąd z tej drugiej części
     * trafia do [lastError] i do dziennika.
     *
     * @param onDetected wołane na wątku głównym po wykryciu frazy
     * @return opis błędu albo `null`, gdy nasłuch ruszył
     */
    fun start(onDetected: () -> Unit): String? {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Brak zgody na mikrofon.".also { lastError = it }
        }
        if (!hasModelFiles()) {
            return (if (isClassifierMissing()) BRAK_MODELU else "Brak modeli bazowych openWakeWord w APK.")
                .also { lastError = it }
        }
        bladWczytania?.let { lastError = it; return it }

        val nowa = Sesja()
        val watek = Thread({
            nasluch(nowa, onDetected)
            // Wątek skończył się SAM (błąd mikrofonu, restart serwera audio) -
            // a nie przez stop(). Bez zgłoszenia aplikacja uważała nasłuch za
            // działający i "Hej Wiktor" milkł do restartu (przegląd 22.11).
            if (nowa.aktywna && sesja === nowa) onZakonczonySam?.invoke(lastError)
        }, "OpenWakeWord")
        nowa.watek = watek
        sesja = nowa
        lastError = null
        watek.start()
        return null
    }

    /** Pętla wątku nasłuchu - jedna na [Sesja]. */
    private fun nasluch(s: Sesja, onDetected: () -> Unit) {
        val blad = ensureLoaded()
        if (blad != null) {
            lastError = blad
            Log.w(tag, "Nasłuch nie ruszył: $blad")
            return
        }
        val m = modele ?: return
        if (!weryfikatorWczytany) przeladujWeryfikator()

        val bluetooth = settings.isWakeWordOverGlassesMic() &&
            runCatching { runBlocking { bluetoothRouter.acquire() } }.getOrDefault(false)
        var recorder: AudioRecord? = null
        try {
            // stop() w trakcie negocjowania łącza - nie zaczynamy nagrywać.
            if (!s.aktywna) return

            val rec = try {
                utworzRecorder(bluetooth)
            } catch (e: SecurityException) {
                lastError = "Brak zgody na mikrofon."
                return
            }
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "Mikrofon niedostępny (zajęty przez inną aplikację?)."
                Log.w(tag, lastError!!)
                return
            }
            rec.startRecording()
            s.nagrywa = true
            Log.i(tag, "Nasłuch „Hej Wiktor” ruszył" + if (bluetooth) " (mikrofon okularów)" else "")

            // Statystyki weryfikatora do podsumowania co DIAG_INTERVAL_MS.
            var weryfikacji = 0
            var maksWer = 0f
            lateinit var potok: OwwPipeline
            potok = OwwPipeline(
                melspec = { probki -> uruchomMel(m, probki) },
                embed = { okno -> uruchomOsadzenie(m, okno) },
                classify = { cechy -> uruchomKlasyfikator(m, cechy) },
                featureWindow = m.oknoKlasyfikatora,
                // Pomiar z treningu (tools/wakeword/README.md): próg 0,5 i DWIE
                // kolejne ramki (160 ms) to ~83% wykryć przy ~0,65 fałszywego
                // wybudzenia na godzinę; jedna ramka - 88% i 1,3/h.
                threshold = OwwPipeline.DEFAULT_THRESHOLD,
                wymaganeRamki = WYMAGANE_RAMKI,
                verify = verify@{ cechy ->
                    val w = weryfikator ?: return@verify null
                    // Weryfikator z innym oknem (inny klasyfikator w APK) - pomijamy.
                    if (w.wymiar != cechy.size * (cechy.firstOrNull()?.size ?: 0)) return@verify null
                    w.ocen(cechy).also {
                        weryfikacji++
                        if (it > maksWer) maksWer = it
                    }
                },
                tylkoMojGlos = { settings.isWakeOnlyMyVoice() },
                onDecision = { decyzja, wynik, wer ->
                    val pola = mapOf(
                        "wynik" to "%.2f".format(wynik),
                        "weryfikator" to wer?.let { "%.2f".format(it) },
                        "decyzja" to decyzja.opis
                    )
                    if (decyzja.budzi) {
                        Log.i(tag, "Wykryto frazę (wynik=$wynik, weryfikator=$wer, ${decyzja.opis})")
                        ostatnieWybudzenie = OstatnieWybudzenie(
                            czasMs = System.currentTimeMillis(),
                            wynik = wynik,
                            weryfikator = wer,
                            decyzja = decyzja,
                            okna = potok.recentWindows(OKNA_KOREKTY).map { WeryfikatorGlosu.splaszcz(it) }
                        )
                        onDiag?.invoke("wykryto „Hej Wiktor”", pola)
                    } else {
                        Log.i(tag, "Weryfikator zawetował wykrycie (wynik=$wynik, weryfikator=$wer)")
                        onDiag?.invoke("odrzucono „Hej Wiktor” - weryfikator głosu", pola)
                    }
                },
                onDetected = { mainHandler.post { if (s.aktywna) onDetected() } }
            )

            val bufor = ShortArray(OwwPipeline.CHUNK)
            var maks = 0f
            var porcje = 0
            var odDiag = System.currentTimeMillis()
            while (s.aktywna) {
                val n = rec.read(bufor, 0, bufor.size)
                if (n < 0) {
                    lastError = "Odczyt z mikrofonu nie powiódł się (kod $n)."
                    Log.w(tag, lastError!!)
                    break
                }
                if (n == 0) continue
                val teraz = System.currentTimeMillis()
                val wynik = potok.feed(if (n == bufor.size) bufor else bufor.copyOf(n), teraz)
                if (wynik != null) {
                    porcje++
                    if (wynik > maks) maks = wynik
                }
                if (teraz - odDiag >= DIAG_INTERVAL_MS) {
                    onDiag?.invoke(
                        "nasłuch „Hej Wiktor” - podsumowanie",
                        mapOf(
                            "maksWynik" to "%.2f".format(maks),
                            "porcji" to porcje,
                            "wykryć" to potok.detections,
                            // Pola weryfikatora tylko, gdy jest nauczony głos.
                            "weryfikacji" to weryfikacji.takeIf { weryfikator != null },
                            "maksWeryfikator" to "%.2f".format(maksWer).takeIf { weryfikacji > 0 },
                            "odrzuceń" to potok.vetoes.takeIf { weryfikator != null }
                        )
                    )
                    maks = 0f
                    porcje = 0
                    weryfikacji = 0
                    maksWer = 0f
                    odDiag = teraz
                }
            }
        } catch (e: Throwable) {
            lastError = "Nasłuch przerwany: ${e.message ?: e::class.java.simpleName}"
            Log.e(tag, "Nasłuch przerwany", e)
        } finally {
            s.nagrywa = false
            recorder?.let { rec ->
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
            // Router jest wspólny (wykrywanie frazy, rozpoznawanie mowy,
            // synteza) - zwalniamy dokładnie to jedno odwołanie, które wzięliśmy.
            if (bluetooth) runCatching { runBlocking { bluetoothRouter.release() } }
        }
    }

    /**
     * Mikrofon nasłuchu: przez łącze rozmowy okularów albo mikrofon telefonu
     * w trybie rozpoznawania mowy (bez systemowej obróbki rozmowy).
     *
     * @throws SecurityException bez zgody na mikrofon
     */
    private fun utworzRecorder(bluetooth: Boolean): AudioRecord {
        val zrodlo = if (bluetooth) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBufor = AudioRecord.getMinBufferSize(
            OwwPipeline.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord(
            zrodlo,
            OwwPipeline.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufor, OwwPipeline.CHUNK * 2 * 4)
        )
    }

    /** Wynik [nagraj]: próbki i opis mikrofonu albo błąd. */
    class Nagranie(val probki: ShortArray, val mikrofon: String, val blad: String? = null)

    /**
     * Nagrywa próbkę do nauki głosu tym samym mikrofonem i źródłem co nasłuch.
     * Blokuje - wołać poza wątkiem głównym, przy zatrzymanym nasłuchu
     * (mikrofon jest wyłączny; [NaukaGlosu] zatrzymuje go na czas nauki).
     *
     * @param maksMs najdłuższy czas nagrania
     * @param czyPrzerwac sprawdzane co porcję; `true` kończy nagranie wcześniej
     *   (to, co nagrano, wraca normalnie)
     * @param naStart wołane z wątku nagrywania, gdy mikrofon już nagrywa - stąd
     *   UI liczy czas do napisu "Mów teraz"
     * @param postep wołane co porcję z liczbą nagranych ms
     */
    fun nagraj(
        maksMs: Long,
        czyPrzerwac: () -> Boolean = { false },
        naStart: () -> Unit = {},
        postep: (Long) -> Unit = {}
    ): Nagranie {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Nagranie(ShortArray(0), "", "Brak zgody na mikrofon.")
        }
        val bluetooth = settings.isWakeWordOverGlassesMic() &&
            runCatching { runBlocking { bluetoothRouter.acquire() } }.getOrDefault(false)
        val mikrofon = if (bluetooth) MIKROFON_OKULARY else MIKROFON_TELEFON
        var recorder: AudioRecord? = null
        try {
            val rec = utworzRecorder(bluetooth)
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                return Nagranie(ShortArray(0), mikrofon, "Mikrofon niedostępny (zajęty przez inną aplikację?).")
            }
            val maksProbek = (maksMs * OwwPipeline.SAMPLE_RATE / 1000).toInt()
            val wynik = ShortArray(maksProbek)
            var n = 0
            rec.startRecording()
            naStart()
            while (n < maksProbek && !czyPrzerwac()) {
                val r = rec.read(wynik, n, minOf(OwwPipeline.CHUNK, maksProbek - n))
                if (r < 0) return Nagranie(wynik.copyOf(n), mikrofon, "Odczyt z mikrofonu nie powiódł się (kod $r).")
                n += r
                postep(n * 1000L / OwwPipeline.SAMPLE_RATE)
            }
            return Nagranie(wynik.copyOf(n), mikrofon)
        } catch (e: SecurityException) {
            return Nagranie(ShortArray(0), mikrofon, "Brak zgody na mikrofon.")
        } catch (e: Throwable) {
            Log.e(tag, "Nagranie do nauki głosu przerwane", e)
            return Nagranie(ShortArray(0), mikrofon, "Nagranie przerwane: ${e.message ?: e::class.java.simpleName}")
        } finally {
            recorder?.let { rec ->
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
            if (bluetooth) runCatching { runBlocking { bluetoothRouter.release() } }
        }
    }

    /**
     * Przepuszcza nagranie przez te same sieci co nasłuch i zwraca wszystkie
     * okna cech z wynikiem klasyfikatora - surowce dla [NaukaGlosuDane].
     * Blokuje (ok. 10 ms na porcję); `null`, gdy modele się nie wczytały.
     */
    fun analizuj(probki: ShortArray): List<OknoCech>? {
        if (ensureLoaded() != null) return null
        val m = modele ?: return null
        val okna = mutableListOf<OknoCech>()
        val potok = OwwPipeline(
            melspec = { p -> uruchomMel(m, p) },
            embed = { okno -> uruchomOsadzenie(m, okno) },
            // Wynik surowy, bez zerowania rozgrzewki - pierwsze okna i tak
            // pomija NaukaGlosuDane.POMIN_POCZATKOWE_OKNA.
            classify = { cechy ->
                uruchomKlasyfikator(m, cechy).also { okna += OknoCech(WeryfikatorGlosu.splaszcz(cechy), it) }
            },
            featureWindow = m.oknoKlasyfikatora
        )
        potok.feed(probki, 0L)
        return okna
    }

    /** Model mel: wejście [1, N] (wartości int16 jako float), wyjście [1, 1, ramki, 32]. */
    private fun uruchomMel(m: Modele, probki: FloatArray): Array<FloatArray> {
        val plaskie = uruchom(m.env, m.mel, probki, longArrayOf(1, probki.size.toLong()))
        val ramki = plaskie.size / OwwPipeline.MEL_BINS
        return Array(ramki) { r ->
            plaskie.copyOfRange(r * OwwPipeline.MEL_BINS, (r + 1) * OwwPipeline.MEL_BINS)
        }
    }

    /** Model osadzeń: wejście [1, 76, 32, 1], wyjście [1, 1, 1, 96]. */
    private fun uruchomOsadzenie(m: Modele, okno: Array<FloatArray>): FloatArray {
        val pasma = OwwPipeline.MEL_BINS
        val dane = FloatArray(okno.size * pasma)
        okno.forEachIndexed { i, ramka -> System.arraycopy(ramka, 0, dane, i * pasma, pasma) }
        return uruchom(m.env, m.embedding, dane, longArrayOf(1, okno.size.toLong(), pasma.toLong(), 1))
    }

    /** Klasyfikator: wejście [1, okno, 96], wyjście [1, 1]. */
    private fun uruchomKlasyfikator(m: Modele, cechy: Array<FloatArray>): Float {
        val dl = cechy.firstOrNull()?.size ?: OwwPipeline.EMBEDDING_SIZE
        val dane = FloatArray(cechy.size * dl)
        cechy.forEachIndexed { i, c -> System.arraycopy(c, 0, dane, i * dl, dl) }
        return uruchom(m.env, m.klasyfikator, dane, longArrayOf(1, cechy.size.toLong(), dl.toLong()))[0]
    }

    /**
     * Jedno wywołanie sieci z jednym wejściem.
     *
     * Nazwę wejścia bierzemy z modelu, a nie z kodu - klasyfikator trenowany
     * osobno może ją mieć inną niż modele bazowe.
     */
    private fun uruchom(env: OrtEnvironment, sesja: OrtSession, dane: FloatArray, ksztalt: LongArray): FloatArray {
        val wejscie = sesja.inputNames.first()
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(dane), ksztalt).use { tensor ->
            sesja.run(mapOf(wejscie to tensor)).use { wynik -> splaszcz(wynik) }
        }
    }

    /** Pierwsze wyjście modelu jako płaska tablica, niezależnie od liczby wymiarów. */
    private fun splaszcz(wynik: OrtSession.Result): FloatArray {
        val tensor = wynik.get(0) as OnnxTensor
        val bufor = tensor.floatBuffer
        return FloatArray(bufor.remaining()).also { bufor.get(it) }
    }

    /**
     * Kończy nasłuch i oddaje mikrofon, zanim wróci - wołający (tura, tłumaczenie,
     * spotkanie) zaraz otwiera mikrofon sam.
     */
    fun stop() {
        val s = sesja ?: return
        sesja = null
        s.aktywna = false
        val watek = s.watek ?: return
        if (watek === Thread.currentThread()) return
        // Odczyt porcji trwa 80 ms, więc pętla kończy się szybko. Dłużej czeka
        // tylko wątek w trakcie negocjowania łącza Bluetooth - ten mikrofonu
        // jeszcze nie otworzył i sprawdzi flagę, zanim to zrobi.
        runCatching { watek.join(STOP_JOIN_MS) }
    }

    /** Zatrzymuje nasłuch i zwalnia sieci - przy wyłączaniu na dobre. */
    @Synchronized
    fun release() {
        stop()
        modele?.close()
        modele = null
        bladWczytania = null
    }

    companion object {
        /** Ile kolejnych ramek nad progiem uznajemy za frazę - patrz pomiar wyżej. */
        const val WYMAGANE_RAMKI = 2
        const val ASSET_DIR = "wakeword"
        const val MEL_ASSET = "melspectrogram.onnx"
        const val EMBEDDING_ASSET = "embedding_model.onnx"
        const val CLASSIFIER_ASSET = "hej_wiktor.onnx"

        /** Ile ostatnich okien zapamiętać przy wykryciu - do korekty fałszywego wybudzenia. */
        const val OKNA_KOREKTY = 3

        const val MIKROFON_TELEFON = "telefon"
        const val MIKROFON_OKULARY = "okulary (Bluetooth)"

        /** Komunikat, gdy klasyfikatora frazy jeszcze nie ma w APK. */
        const val BRAK_MODELU = "Brak modelu hej_wiktor - fraza „Hej Wiktor” jeszcze nie działa."

        private const val DIAG_INTERVAL_MS = 30_000L
        private const val STOP_JOIN_MS = 1_000L
    }
}
