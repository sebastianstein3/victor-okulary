package pl.victor.app.camera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.victor.app.ai.CaptureMode
import pl.victor.app.ai.ImageResolution
import pl.victor.app.ble.VictorManager
import pl.victor.app.storage.PhotoStorage

/**
 * Capture modes - każdy ma swoją strategię:
 *
 * - BURST_PHOTO: 5 zdjęć co 1s (5s total) - domyślny, kompatybilny
 * - HIGH_QUALITY_SINGLE: 1 zdjęcie HD - detale, OCR
 * - FAST_BURST: 5 zdjęć co 200ms (1s total) - gesty (statyczne)
 * - VIDEO_SHORT: 3s wideo 24 FPS - gesty (dynamiczne) [HeyCyan: 1080p MP4]
 * - VIDEO_LONG: 5s wideo 10 FPS - pełna obserwacja [HeyCyan: 1080p MP4]
 *
 * Zdjęcia pobierane są jako miniatury przez BLE (VictorManager.capturePhoto) - ta ścieżka
 * nie wymaga Wi-Fi Direct, więc jest szybka i działa od razu po sparowaniu okularów.
 *
 * Wideo idzie przez Wi-Fi Direct (VictorManager.downloadLatestVideo): telefon dołącza
 * do grupy okularów i pobiera plik po HTTP. Wymaga uprawnienia NEARBY_WIFI_DEVICES
 * na Androidzie 13+ (wcześniej ACCESS_FINE_LOCATION).
 */
class BurstCaptureManager(
    private val context: Context,
    private val photoStorage: PhotoStorage,
    private val glassesManager: VictorManager
) {
    private val tag = "BurstCaptureManager"

    /** Górny limit zdjęć w serii - chroni przed zablokowaniem okularów. */
    private val MAX_BURST_COUNT = 10

    /**
     * Górna granica czasu na CAŁĄ serię.
     *
     * Czterdzieści pięć sekund mieści jedno zdjęcie z pełnym budżetem (32 s)
     * plus jedno-dwa szybkie, a nie mieści serii samych porażek. Liczy się od
     * startu serii i jest sprawdzana przed każdym kolejnym zdjęciem.
     */
    private val BURST_TOTAL_BUDGET_MS = 45_000L

    /**
     * Główna metoda - przechwytuje multimedia zgodnie z trybem.
     *
     * @return CaptureResult ze zdjęciami lub wideo
     */
    suspend fun capture(
        mode: CaptureMode,
        resolution: ImageResolution = mode.defaultResolution,
        countOverride: Int? = null,
        intervalMsOverride: Long? = null,
        preferFullResolution: Boolean = false,
        onProgress: (Int) -> Unit = {}
    ): CaptureResult = withContext(Dispatchers.IO) {
        Log.i(tag, "Starting capture: mode=$mode, res=$resolution, pełna=$preferFullResolution")

        when {
            // Wideo nagrywają okulary własnym firmware - rozdzielczości nie da
            // się z aplikacji ustawić, więc nie przekazujemy jej dalej, żeby nie
            // udawać, że coś robi.
            mode.requiresVideo -> captureVideo(mode, onProgress)
            else -> captureBurst(
                mode, resolution, countOverride, intervalMsOverride,
                preferFullResolution, onProgress
            )
        }
    }

    /**
     * Burst capture - N zdjęć z HeyCyan (po BLE/HTTP).
     */
    private suspend fun captureBurst(
        mode: CaptureMode,
        resolution: ImageResolution,
        countOverride: Int?,
        intervalMsOverride: Long?,
        preferFullResolution: Boolean,
        onProgress: (Int) -> Unit
    ): CaptureResult {
        // Ustawienia użytkownika mają pierwszeństwo przed domyślnymi wartościami trybu.
        // Przy pełnej rozdzielczości robimy JEDNO zdjęcie: pobranie oryginału idzie
        // przez Wi-Fi Direct i trwa kilkanaście sekund, więc seria pięciu oznaczałaby
        // ponad minutę czekania na odpowiedź.
        val count = if (preferFullResolution) {
            1
        } else {
            (countOverride ?: mode.expectedImageCount).coerceIn(1, MAX_BURST_COUNT)
        }
        val intervalMs = intervalMsOverride ?: mode.frameIntervalMs
        val images = mutableListOf<ByteArray>()

        // Sprawdź czy okulary połączone
        if (glassesManager.connectionState.value != pl.victor.app.ble.ConnectionState.READY) {
            Log.w(tag, "HeyCyan nie połączony - zwracam puste")
            return CaptureResult(mode, emptyList(), null, 0)
        }

        // Rozdzielczość trybu przekłada się na dwie rzeczy: jakość miniatury,
        // o którą prosimy okulary, i limity, do których dopasowujemy wynik.
        val thumbnailQuality = ImageScaler.thumbnailQualityFor(resolution)

        // SERIA MA WSPÓLNY LIMIT CZASU, NIE TYLKO POJEDYNCZE ZDJĘCIE.
        //
        // Pojedyncze przechwytywanie ma własny budżet (PHOTO_TOTAL_BUDGET_MS,
        // 32 s), ale pętla nie miała ŻADNEGO: tryb „Burst 5 zdjęć" przy
        // niedziałającym aparacie oznaczał pięć razy pełny budżet, czyli ponad
        // dwie i pół minuty stania z pytaniem bez odpowiedzi. Przy górnej
        // granicy dziesięciu zdjęć - ponad pięć minut.
        //
        // To jest w części moja wina: podnosząc budżet pojedynczego zdjęcia z
        // 22 na 32 s (bo 22 były mniejsze niż jedna uczciwa próba) podniosłem
        // tym samym najgorszy przypadek serii o połowę, nie zauważając, że nic
        // go nie ogranicza.
        val burstStartedAtMs = System.currentTimeMillis()
        var attempted = 0
        for (i in 0 until count) {
            // Sprawdzamy PRZED kolejnym zdjęciem, nie po - przerwanie w połowie
            // transferu zostawiłoby okulary w trakcie wysyłania.
            if (i > 0 && System.currentTimeMillis() - burstStartedAtMs > BURST_TOTAL_BUDGET_MS) {
                Log.w(
                    tag,
                    "Seria przerwana po ${System.currentTimeMillis() - burstStartedAtMs} ms " +
                        "- mam ${images.size} z $count"
                )
                break
            }
            attempted = i + 1
            Log.d(tag, "Zdjęcie ${i + 1}/$count (przez BLE, jakość $thumbnailQuality)")
            onProgress(i + 1)

            // Miniatura po BLE: jedna komenda robi zdjęcie i odsyła bajty JPEG.
            // Przy pytaniu o tekst bierzemy zamiast niej ORYGINAŁ z pamięci
            // okularów - na miniaturze liter z bliska po prostu nie ma.
            val raw = if (preferFullResolution) {
                glassesManager.captureSharpPhoto(thumbnailQuality)
            } else {
                glassesManager.capturePhoto(thumbnailQuality)
            }
            val photo = raw?.let { ImageScaler.fit(it, resolution) }
            if (photo != null) {
                images.add(photo)
                photoStorage.saveConversationPhoto(photo, "burst_${i + 1}")
            } else {
                // PIERWSZA PORAŻKA KOŃCZY SERIĘ.
                //
                // capturePhoto nie jest jedną próbą: w środku ma drogę
                // producenta, zejście na bezpieczną jakość i komendę zdjęcia AI,
                // a porażka znaczy, że WSZYSTKIE wyczerpały swój budżet. Kolejny
                // przebieg pętli nie jest nowym eksperymentem, tylko tym samym
                // powtórzonym - za cenę następnych trzydziestu sekund ciszy.
                Log.w(tag, "Nie udało się pobrać zdjęcia ${i + 1}/$count - kończę serię")
                break
            }

            if (i < count - 1) {
                delay(intervalMs)
            }
        }

        if (images.isEmpty()) {
            Log.w(tag, "Nie pobrano żadnego zdjęcia z okularów")
        }

        // Liczba FAKTYCZNIE podjętych prób, nie zaplanowanych: przy serii
        // przerwanej limitem wskaźnik postępu skakałby inaczej na koniec.
        onProgress(attempted)

        return CaptureResult(
            mode = mode,
            images = images,
            video = null,
            videoDurationMs = 0
        )
    }

    /**
     * Nagrywanie wideo (1080p MP4).
     *
     * Sterowanie idzie po BLE, a gotowy plik pobierany jest przez Wi-Fi Direct.
     * Zestawienie grupy P2P trwa kilkanaście sekund, więc pobranie wideo jest
     * wyraźnie wolniejsze niż zdjęcie po BLE.
     */
    private suspend fun captureVideo(
        mode: CaptureMode,
        onProgress: (Int) -> Unit
    ): CaptureResult {
        val durationMs = when (mode) {
            CaptureMode.VIDEO_SHORT -> 3_000L
            CaptureMode.VIDEO_LONG -> 5_000L
            else -> 3_000L
        }

        Log.i(tag, "HeyCyan video recording for ${durationMs}ms")
        onProgress(0)

        if (glassesManager.connectionState.value != pl.victor.app.ble.ConnectionState.READY) {
            Log.w(tag, "HeyCyan nie połączony - video niemożliwe")
            return CaptureResult(mode, emptyList(), null, 0)
        }

        // Start
        glassesManager.startVideoRecording()
        delay(durationMs)
        onProgress(50)

        // Stop
        glassesManager.stopVideoRecording()
        delay(500)  // daj czas na flush

        onProgress(75)

        // Pobierz najnowsze wideo przez HTTP
        val video = glassesManager.downloadLatestVideo()
        if (video != null) {
            photoStorage.saveVideo(video, "video_${System.currentTimeMillis()}.mp4")
            Log.i(tag, "Video downloaded: ${video.size} bytes")
        } else {
            Log.w(
                tag,
                "Nie pobrano wideo - sprawdź uprawnienie do Wi-Fi Direct i czy okulary " +
                    "weszły w tryb transferu. Nagranie pozostaje w ich pamięci."
            )
        }

        onProgress(100)

        return CaptureResult(
            mode = mode,
            images = emptyList(),
            video = video,
            videoDurationMs = durationMs
        )
    }
}

/**
 * Wynik przechwytywania.
 */
data class CaptureResult(
    val mode: CaptureMode,
    val images: List<ByteArray>,
    val video: ByteArray?,
    val videoDurationMs: Long
) {
    val isEmpty: Boolean get() = images.isEmpty() && (video == null || video.isEmpty())
}
