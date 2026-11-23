package pl.victor.app.ble

/**
 * Tryb multimediów okularów (A2DP) widziany od strony telefonu - czysta logika.
 *
 * ## Po co
 * W dziennikach z biegów po 183 KAŻDA tura ma parę wierszy "proszę okulary o
 * włączenie trybu multimediów" i "tryb multimediów NIE wstał mimo prośby ...
 * Odtwarzanie (A2DP): brak / Rozmowa (SCO/HFP): W610T_A6BA". Cały dźwięk idzie
 * więc profilem rozmowy: gorsza jakość, dłuższe zestawianie łącza, a przekład
 * w tłumaczeniu na żywo gra z głośnika telefonu. Aplikacja prosiła w kółko i
 * nic więcej nie umiała powiedzieć.
 *
 * Z samej prośby nie da się wyczytać, czy okulary w ogóle mają tryb
 * multimediów (profil A2DP Sink), czy go mają, a telefon go po prostu nie
 * łączy - bo przełącznik "Dźwięk multimediów" przy okularach jest wyłączony.
 * Pierwsze naprawia się w ustawieniach Bluetooth jednym kliknięciem, drugiego
 * nie naprawi nikt. Ten plik rozstrzyga, które z nich zachodzi, i mówi to
 * człowiekowi uczciwie.
 *
 * Bez Androida, żeby dało się to sprawdzić testem - zbieranie danych z systemu
 * siedzi w [MediaModeProbe].
 */
object MediaModeAdvisor {

    /** AudioSink - profil "odtwarzam dźwięk", który zgłaszają słuchawki i głośniki. */
    const val UUID_AUDIO_SINK = "0000110b-0000-1000-8000-00805f9b34fb"

    /** Advanced Audio Distribution - ogólny identyfikator A2DP. */
    const val UUID_A2DP = "0000110d-0000-1000-8000-00805f9b34fb"

    /** Handsfree - profil rozmowy (HFP) po stronie zestawu. */
    const val UUID_HANDSFREE = "0000111e-0000-1000-8000-00805f9b34fb"

    /** Headset (HSP) - starszy profil rozmowy. */
    const val UUID_HEADSET = "00001108-0000-1000-8000-00805f9b34fb"

    /**
     * Czy okulary deklarują tryb multimediów.
     *
     * `null` znaczy "nie wiadomo": Android trzyma listę profili w pamięci
     * podręcznej i potrafi jej nie mieć (świeże parowanie, wyczyszczona pamięć
     * Bluetooth). Brak listy to NIE jest brak profilu - mówienie wtedy "okulary
     * nie mają trybu multimediów" byłoby nieprawdą.
     */
    fun declaresA2dpSink(uuids: Collection<String>?): Boolean? {
        if (uuids == null) return null
        val norm = uuids.map { it.trim().lowercase() }
        return UUID_AUDIO_SINK in norm || UUID_A2DP in norm
    }

    /** Czy okulary deklarują profil rozmowy - patrz [declaresA2dpSink]. */
    fun declaresHandsFree(uuids: Collection<String>?): Boolean? {
        if (uuids == null) return null
        val norm = uuids.map { it.trim().lowercase() }
        return UUID_HANDSFREE in norm || UUID_HEADSET in norm
    }

    /**
     * Wybiera spośród sparowanych urządzeń to, które jest KLASYCZNĄ częścią
     * okularów.
     *
     * ## Dlaczego nie po prostu adres z BLE
     * Okulary mają dwie twarze: BLE (sterowanie, zdjęcia - to łączy aplikacja)
     * i klasyczny Bluetooth (dźwięk - to łączy system). Adresy obu potrafią się
     * różnić, a wspólna bywa tylko nazwa, np. "W610T_A6BA". Najpewniejsza jest
     * nazwa, którą Android podaje dla urządzenia audio (z niej pochodzi
     * "Rozmowa (SCO/HFP): W610T_A6BA" w dzienniku), potem nazwa z BLE, adres i
     * wreszcie sam kształt nazwy.
     *
     * Urządzenie bez żadnego z tych dopasowań nie jest brane, choćby miało
     * profil multimediów - lista sparowanych zawiera też samochód i głośnik w
     * kuchni, a podpowiedź o cudzym urządzeniu byłaby gorsza niż żadna.
     */
    fun pickGlassesDevice(
        candidates: List<BondedCandidate>,
        audioName: String?,
        bleName: String?,
        bleAddress: String?
    ): BondedCandidate? {
        fun identity(c: BondedCandidate): Int {
            var score = 0
            val name = c.name?.trim().orEmpty()
            if (name.isNotEmpty() && audioName != null && name.equals(audioName.trim(), ignoreCase = true)) score += 8
            if (name.isNotEmpty() && bleName != null && name.equals(bleName.trim(), ignoreCase = true)) score += 4
            if (bleAddress != null && c.address.equals(bleAddress, ignoreCase = true)) score += 2
            if (name.isNotEmpty() && GlassesProtocol.looksLikeGlassesName(name)) score += 1
            return score
        }
        return candidates
            .map { it to identity(it) }
            .filter { it.second > 0 }
            .maxWithOrNull(
                // Najpierw klasyczna twarz okularów: urządzenie wyłącznie BLE
                // nie ma profili dźwięku w ogóle, więc jego "diagnoza" zawsze
                // brzmiałaby "nie oferują" - choćby pasowało adresem.
                compareBy<Pair<BondedCandidate, Int>> { if (it.first.classic) 1 else 0 }
                    .thenBy { it.second }
                    .thenBy { if (declaresA2dpSink(it.first.uuids) == true) 1 else 0 }
            )
            ?.first
    }

    /** Którą podpowiedź pokazać - `null`, gdy żadna nic by nie dała. */
    fun hintKind(d: MediaModeDiagnosis): MediaModeHintKind? = when {
        !d.permission -> MediaModeHintKind.NO_PERMISSION
        // System twierdzi, że profil multimediów JEST połączony. Przełącznik w
        // ustawieniach jest więc włączony i odsyłanie tam człowieka nic nie da -
        // przyczyna leży gdzie indziej (dziennik ma ją pokazać, nie ekran).
        d.a2dpState == A2dpLinkState.CONNECTED -> null
        // Nieudane zapytanie nie upoważnia do "nie są sparowane" - zostaje
        // ogólna instrukcja z zastrzeżeniem.
        d.probeFailed -> MediaModeHintKind.UNCERTAIN
        d.deviceName == null || !d.bonded -> MediaModeHintKind.NOT_PAIRED
        // Znaleziony tylko wpis BLE: jego profile nie mają multimediów z natury,
        // więc "nie oferują" byłoby nieprawdą - brakuje sparowanej części audio.
        !d.classic -> MediaModeHintKind.NOT_PAIRED
        d.declaresA2dpSink == true -> MediaModeHintKind.ENABLE_IN_SETTINGS
        d.declaresA2dpSink == false -> MediaModeHintKind.NOT_OFFERED
        else -> MediaModeHintKind.UNCERTAIN
    }

    /** Podpowiedź dla diagnozy albo `null` - patrz [hintKind]. */
    fun hintFor(d: MediaModeDiagnosis): MediaModeHint? =
        hintKind(d)?.let { hint(it, d.deviceName) }

    /** Treść podpowiedzi. Nazwa okularów, gdy ją znamy - człowiek ma wiedzieć, czego szukać. */
    fun hint(kind: MediaModeHintKind, deviceName: String?): MediaModeHint {
        val okulary = deviceName?.let { "okularach „$it”" } ?: "okularach"
        val instrukcja = "Ustawienia Bluetooth → ikona zębatki przy $okulary → włącz " +
            "„Dźwięk multimediów” / „Audio multimediów”."
        return when (kind) {
            MediaModeHintKind.ENABLE_IN_SETTINGS -> MediaModeHint(
                kind = kind,
                title = "Włącz dźwięk multimediów w okularach",
                body = "Okulary mają tryb multimediów, ale telefon go nie łączy - dźwięk " +
                    "idzie trybem rozmowy: gorsza jakość, wolniejszy start, a przekład " +
                    "w tłumaczeniu gra z głośnika telefonu. $instrukcja",
                action = MediaModeHintAction.BLUETOOTH_SETTINGS
            )
            MediaModeHintKind.NOT_OFFERED -> MediaModeHint(
                kind = kind,
                title = "Okulary nie oferują trybu multimediów",
                body = "Telefon widzi je wyłącznie jako zestaw do rozmów - nie zgłaszają " +
                    "profilu multimediów (A2DP). Dźwięk będzie szedł trybem rozmowy: " +
                    "działa, ale z gorszą jakością i wolniejszym startem. Czasem pomaga " +
                    "usunięcie okularów w ustawieniach Bluetooth i ponowne sparowanie.",
                action = MediaModeHintAction.BLUETOOTH_SETTINGS
            )
            MediaModeHintKind.NOT_PAIRED -> MediaModeHint(
                kind = kind,
                title = "Okulary nie są sparowane jako urządzenie audio",
                body = "Telefon nie ma ich na liście sparowanych urządzeń dźwięku, więc " +
                    "tryb multimediów nie ma czego połączyć. Sparuj okulary w " +
                    "ustawieniach Bluetooth telefonu, a potem przy ikonie zębatki " +
                    "włącz „Dźwięk multimediów” / „Audio multimediów”.",
                action = MediaModeHintAction.BLUETOOTH_SETTINGS
            )
            MediaModeHintKind.UNCERTAIN -> MediaModeHint(
                kind = kind,
                title = "Tryb multimediów okularów nie wstaje",
                body = "Dźwięk idzie trybem rozmowy (gorsza jakość, wolniejszy start). " +
                    "$instrukcja Jeśli takiego przełącznika nie ma, okulary nie oferują " +
                    "trybu multimediów i zostaje tryb rozmowy.",
                action = MediaModeHintAction.BLUETOOTH_SETTINGS
            )
            MediaModeHintKind.NO_PERMISSION -> MediaModeHint(
                kind = kind,
                title = "Brak zgody na urządzenia Bluetooth",
                body = "Bez zgody „Urządzenia w pobliżu” aplikacja nie widzi trybu " +
                    "multimediów okularów i każdy dźwięk idzie trybem rozmowy albo " +
                    "z telefonu. Nadaj ją w ustawieniach aplikacji.",
                action = MediaModeHintAction.APP_SETTINGS
            )
        }
    }
}

/** Sparowane urządzenie z listy systemu - tylko to, czego potrzebuje [MediaModeAdvisor]. */
data class BondedCandidate(
    val name: String?,
    val address: String,
    /** Czy ma klasyczną twarz Bluetooth (CLASSIC albo DUAL) - profile dźwięku żyją tylko tam. */
    val classic: Boolean,
    /** Profile z pamięci podręcznej systemu; `null`, gdy system ich nie zna. */
    val uuids: List<String>?
)

/** Stan profilu A2DP dla okularów - z `BluetoothProfile.getConnectionState`. */
enum class A2dpLinkState(val label: String) {
    CONNECTED("połączony"),
    CONNECTING("łączy"),
    DISCONNECTING("rozłącza"),
    DISCONNECTED("rozłączony"),
    UNKNOWN("nieznany")
}

/** Co telefon wie o trybie multimediów okularów - patrz [MediaModeProbe]. */
data class MediaModeDiagnosis(
    /** Czy jest zgoda BLUETOOTH_CONNECT - bez niej reszta pól jest pusta. */
    val permission: Boolean,
    /** Nazwa klasycznej twarzy okularów albo `null`, gdy wśród sparowanych jej nie ma. */
    val deviceName: String?,
    val bonded: Boolean,
    val classic: Boolean,
    /** Patrz [MediaModeAdvisor.declaresA2dpSink] - `null` = system nie zna profili. */
    val declaresA2dpSink: Boolean?,
    val declaresHandsFree: Boolean?,
    val uuidCount: Int?,
    val a2dpState: A2dpLinkState,
    /** Zapytanie systemu się nie udało (brak adaptera, wyjątek) - nic nie wiemy na pewno. */
    val probeFailed: Boolean = false
) {
    /** Pola do wiersza dziennika - jeden wiersz na połączenie. */
    fun fields(): Map<String, Any?> = mapOf(
        "zgodaBT" to permission,
        "urządzenie" to (deviceName ?: "nie znaleziono"),
        "sparowane" to bonded,
        "klasyczne" to classic,
        "deklarujeA2DP" to (declaresA2dpSink?.toString() ?: "nie wiadomo"),
        "deklarujeRozmowę" to (declaresHandsFree?.toString() ?: "nie wiadomo"),
        "profili" to uuidCount,
        "profilA2DP" to a2dpState.label,
        "zapytanieZawiodło" to probeFailed,
        "wniosek" to (MediaModeAdvisor.hintKind(this)?.label ?: "profil połączony - podpowiedź nic nie da")
    )

    companion object {
        val NO_PERMISSION = MediaModeDiagnosis(
            permission = false,
            deviceName = null,
            bonded = false,
            classic = false,
            declaresA2dpSink = null,
            declaresHandsFree = null,
            uuidCount = null,
            a2dpState = A2dpLinkState.UNKNOWN
        )

        /** Zgoda jest, ale wśród sparowanych nie ma niczego, co byłoby okularami. */
        val NOT_FOUND = NO_PERMISSION.copy(permission = true)

        /** Zapytanie systemu zawiodło - patrz [probeFailed]. */
        val PROBE_FAILED = NO_PERMISSION.copy(permission = true, probeFailed = true)
    }
}

enum class MediaModeHintKind(val label: String) {
    ENABLE_IN_SETTINGS("A2DP zadeklarowany, niepołączony - do włączenia w ustawieniach"),
    NOT_OFFERED("okulary nie deklarują A2DP"),
    NOT_PAIRED("brak sparowanej klasycznej części okularów"),
    UNCERTAIN("system nie zna profili okularów"),
    NO_PERMISSION("brak zgody BLUETOOTH_CONNECT")
}

/** Dokąd prowadzi przycisk podpowiedzi. */
enum class MediaModeHintAction { BLUETOOTH_SETTINGS, APP_SETTINGS }

/** Podpowiedź na ekran główny. */
data class MediaModeHint(
    val kind: MediaModeHintKind,
    val title: String,
    val body: String,
    val action: MediaModeHintAction
)

/**
 * Pamięć jednej sesji połączenia: ile razy z rzędu tryb multimediów nie wstał,
 * kiedy wolno poprosić znowu i czy podpowiedź już padła.
 *
 * ## Po co ograniczać prośby
 * Dziennik: para "proszę ... / NIE wstał" po KAŻDEJ turze, godzinami. Okulary,
 * które trzy razy z rzędu nie odpowiedziały na prośbę, nie odpowiedzą na
 * czwartą w tej samej sesji - a każda prośba to komenda po BLE i dwa wiersze
 * szumu, w którym giną wiersze ważne. Po [backoffAfterFailures] porażkach
 * prosimy raz na [backoffIntervalMs]; sukces (choćby wstał sam) wraca do
 * zwykłego trybu.
 *
 * Metody są zsynchronizowane: wynik prośby przychodzi z korutyny w tle, a
 * prośby idą z orkiestratora.
 */
class MediaModeSession(
    private val hintAfterFailures: Int = HINT_AFTER_FAILURES,
    private val backoffAfterFailures: Int = BACKOFF_AFTER_FAILURES,
    private val backoffIntervalMs: Long = BACKOFF_INTERVAL_MS
) {
    /** Nieudane próby z rzędu od ostatniego sukcesu. */
    var consecutiveFailures = 0
        private set

    private var lastRequestAtMs = 0L
    private var hintShown = false

    /** Co zmieniła ostatnia odnotowana próba - do jednorazowych wpisów w dzienniku. */
    enum class Transition { NONE, ENTERED_BACKOFF, RECOVERED }

    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        lastRequestAtMs = 0L
        hintShown = false
    }

    @Synchronized
    fun inBackoff(): Boolean = consecutiveFailures >= backoffAfterFailures

    /** Czy wolno teraz poprosić okulary o tryb multimediów. */
    @Synchronized
    fun mayRequest(nowMs: Long): Boolean =
        !inBackoff() || lastRequestAtMs == 0L || nowMs - lastRequestAtMs >= backoffIntervalMs

    @Synchronized
    fun noteRequest(nowMs: Long) {
        lastRequestAtMs = nowMs
    }

    /** Odnotowuje wynik sprawdzenia; zwraca zmianę trybu, jeśli zaszła. */
    @Synchronized
    fun noteResult(up: Boolean): Transition {
        if (up) {
            val wasBackingOff = inBackoff()
            consecutiveFailures = 0
            return if (wasBackingOff) Transition.RECOVERED else Transition.NONE
        }
        consecutiveFailures++
        return if (consecutiveFailures == backoffAfterFailures) Transition.ENTERED_BACKOFF else Transition.NONE
    }

    /**
     * Czy pora pokazać podpowiedź: dość porażek z rzędu, jeszcze jej w tej
     * sesji nie było i człowiek nie kazał jej więcej nie pokazywać.
     */
    @Synchronized
    fun hintDue(suppressed: Boolean): Boolean =
        !suppressed && !hintShown && consecutiveFailures >= hintAfterFailures

    @Synchronized
    fun markHintShown() {
        hintShown = true
    }

    companion object {
        /** Dwie porażki z rzędu - jedna bywa przypadkiem (BT klasyczny wraca wolniej niż BLE). */
        const val HINT_AFTER_FAILURES = 2

        const val BACKOFF_AFTER_FAILURES = 3

        /** Jedna próba na dziesięć minut - gdyby człowiek w międzyczasie coś przełączył. */
        const val BACKOFF_INTERVAL_MS = 10 * 60_000L
    }
}
