package pl.victor.app.ble

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Detektor akcji przycisku fizycznego na okularach.
 *
 * Mapuje eventy z ButtonEvent na konkretne akcje użytkownika:
 * - 1x kliknięcie  → QUICK_QUESTION (słuchaj, o co pytam)
 * - 2x kliknięcie  → LOOK_AND_DESCRIBE (zrób zdjęcie i powiedz, co widzisz)
 * - 3x kliknięcie  → READ_AND_TRANSLATE (przeczytaj i przetłumacz napis)
 * - 4x kliknięcie  → NEW_CONVERSATION (nowa rozmowa, reset historii)
 *
 * ## Dlaczego akurat tak
 * Gest ma być tym łatwiejszy, im częściej się go używa. Czytanie tekstu jest
 * funkcją, dla której nosi się te okulary, gdy nie widzi się dobrze etykiety,
 * ulotki albo tabliczki - więc siedzi możliwie nisko. Reset jest najrzadszy i
 * odwracalny głosem ("nowy temat"), więc zszedł najniżej.
 *
 * ## CZYTANIE WISIAŁO NA GEŚCIE, KTÓREGO TEN SPRZĘT NIE WYSYŁA
 * Do 16 września ta akcja była pod PRZYTRZYMANIEM - gestem najłatwiejszym do
 * trafienia bez patrzenia, więc z zamiaru słusznym. Tyle że okulary nie
 * zgłaszają przytrzymania W OGÓLE (patrz niżej: jedyne, co przychodzi, to
 * `ShortClick`), więc najważniejsza funkcja dostała jedyny gest NIEOSIĄGALNY.
 *
 * Zgłoszone dwa razy: "tłumaczenie po wciśnięciu przycisku nie działa,
 * kompletnie nic nie mówi" i "w trybie czytania niczego nie czyta". Obie razy
 * szukałem przyczyny w obrazie i w promptach - a akcja nie startowała wcale.
 * W dzienniku z 16 września jest szesnaście wciśnięć przycisku i WSZYSTKIE to
 * `numer=1`, ani jednego przytrzymania.
 *
 * Gałąź `LongPress` zostaje, bo nic nie kosztuje i inny egzemplarz może ją
 * wysyłać - ale nie wolno na niej niczego opierać.
 *
 * Skanowanie QR zostało wcześniej zdjęte z potrójnego kliknięcia (nisza) i
 * dalej jest pod komendą głosową oraz w spisie komend.
 *
 * ## PROSTUJĘ WŁASNE ZDANIE: SPRZĘT NIE SKLEJA SZYBKICH SERII
 * Napisałem wcześniej, że gesty wieloklikowe są nieosiągalne, bo okulary
 * łączą szybkie wciśnięcia na poziomie firmware'u. To jest nieprawda i obala
 * to jedna obserwacja z terenu: DWA kliknięcia działają - robi się zdjęcie i
 * trafia do modelu. Gdyby sprzęt sklejał serie, nie działałyby również dwa.
 *
 * Martwe są trzy i cztery, a przyczyny z samego kodu nie umiem wskazać. Dwie
 * możliwości rozdziela [lastGapsMs]; do czasu pomiaru żadna funkcja nie ma
 * prawa wisieć wyłącznie na tych gestach. Dlatego "przeczytaj i przetłumacz"
 * ma od teraz komendę głosową - patrz
 * [pl.victor.app.actions.SmartActionDetector.detectGesture].
 *
 * Detekcja: okno czasowe 500ms między kliknięciami.
 *
 * ## Dlaczego liczenie kliknięć idzie przez timer, nie przez osobne eventy
 * Prawdziwy sprzęt zgłasza przez BLE tylko JEDEN typ zdarzenia na wciśnięcie
 * przycisku (`ButtonEvent.ShortClick`) - `DoubleClick`/`TripleClick`/`LongPress`
 * nigdy nie przychodzą same z siebie z [pl.victor.app.ble.VictorManager]. Więc
 * to TA klasa musi zliczyć kolejne `ShortClick`i w oknie czasowym i po jego
 * upływie (bez kolejnego kliknięcia) zdecydować, co się wydarzyło - stąd
 * [flushJob]. Wcześniej ten timer nie istniał, więc żadne kliknięcie nigdy nie
 * kończyło się akcją.
 */
class ButtonActionDetector {

    private var lastClickTime: Long = 0
    private var clickCount: Int = 0
    private val CLICK_WINDOW_MS = 500L

    /**
     * Odstępy między kliknięciami ZAMKNIĘTEJ serii, w milisekundach.
     *
     * ## Po co to jest
     * Zgłoszono, że jedno i dwa kliknięcia działają, a trzy i cztery nie. To
     * wyklucza moją wcześniejszą hipotezę ("sprzęt skleja szybkie serie") -
     * gdyby sklejał, nie działałyby też dwa. Zostają dwie możliwości i nie
     * umiem dziś rozstrzygnąć między nimi z samego kodu:
     *
     *  1. Trzecie kliknięcie pada PÓŹNIEJ niż [CLICK_WINDOW_MS] po drugim. Okno
     *     zamyka się wtedy na dwóch, leci zdjęcie, a trzecie zaczyna nową serię
     *     - z zewnątrz wygląda to jak "trzy kliknięcia robią zdjęcie".
     *  2. Firmware okularów sam obsługuje podwójne kliknięcie (stąd słyszalna
     *     migawka) i przez czas pracy aparatu nie zgłasza kolejnych wciśnięć.
     *     Wtedy trzecie kliknięcie nie dociera do nas w ogóle.
     *
     * Różnica jest zasadnicza: pierwsze naprawia się liczbą, drugiego nie da
     * się naprawić wcale i trzeba przenieść funkcję na głos. Te odstępy
     * rozstrzygają to jednym spojrzeniem w dziennik - jeśli w serii "3 kliknięć"
     * widać dwa odstępy, gubi je okno; jeśli widać jeden, gubi je firmware.
     */
    @Volatile
    var lastGapsMs: List<Long> = emptyList()
        private set

    /** Ile kliknięć zamknęło ostatnią serię - patrz [lastGapsMs]. */
    @Volatile
    var lastClickCount: Int = 0
        private set

    private val gaps = mutableListOf<Long>()

    private val _action = MutableSharedFlow<ButtonAction>(replay = 0, extraBufferCapacity = 1)
    val action: SharedFlow<ButtonAction> = _action.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null

    /**
     * Przetwarza event z przycisku. Wywołaj z obserwatora buttonEvent w VictorManager.
     */
    fun processEvent(event: ButtonEvent) {
        when (event) {
            ButtonEvent.ShortClick -> handleClick()
            ButtonEvent.DoubleClick -> {
                flushJob?.cancel()
                clickCount = 2
                tryEmitAction(ButtonAction.LOOK_AND_DESCRIBE)
                reset()
            }
            ButtonEvent.TripleClick -> {
                flushJob?.cancel()
                clickCount = 3
                tryEmitAction(ButtonAction.READ_AND_TRANSLATE)
                reset()
            }
            ButtonEvent.LongPress -> {
                flushJob?.cancel()
                tryEmitAction(ButtonAction.READ_AND_TRANSLATE)
                reset()
            }
            ButtonEvent.Release -> {
                // Nic nie rób - obsłużone w LongPress
            }
        }
    }

    /**
     * Obsługa pojedynczego kliku - liczy go i (re)startuje okno oczekiwania
     * na kolejny. Jeśli kolejny klik nie przyjdzie w [CLICK_WINDOW_MS], zlicza
     * się to co już mamy - patrz [flushPendingClick].
     */
    private fun handleClick() {
        val now = SystemClock.elapsedRealtime()

        if (now - lastClickTime > CLICK_WINDOW_MS) {
            // Nowa sekwencja kliknięć
            clickCount = 1
            gaps.clear()
        } else {
            clickCount++
            gaps.add(now - lastClickTime)
        }

        lastClickTime = now

        flushJob?.cancel()
        flushJob = scope.launch {
            delay(CLICK_WINDOW_MS)
            flushPendingClick()
        }
    }

    /**
     * Zamyka okno liczenia kliknięć i emituje akcję odpowiadającą ich liczbie.
     * Woła się sama po [CLICK_WINDOW_MS] od ostatniego kliknięcia.
     */
    fun flushPendingClick() {
        // Zapisane PRZED reset() - inaczej orkiestrator odczytałby wyzerowane
        // pola, bo akcja dociera do niego już po zamknięciu serii.
        lastClickCount = clickCount
        lastGapsMs = gaps.toList()
        when {
            clickCount == 1 -> tryEmitAction(ButtonAction.QUICK_QUESTION)
            clickCount == 2 -> tryEmitAction(ButtonAction.LOOK_AND_DESCRIBE)
            clickCount == 3 -> tryEmitAction(ButtonAction.READ_AND_TRANSLATE)
            clickCount >= 4 -> tryEmitAction(ButtonAction.NEW_CONVERSATION)
        }
        reset()
    }

    private fun tryEmitAction(action: ButtonAction) {
        _action.tryEmit(action)
    }

    private fun reset() {
        clickCount = 0
        lastClickTime = 0
        gaps.clear()
        flushJob = null
    }
}
