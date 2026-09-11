package pl.victor.app.ble

/**
 * Typy opisujące okulary - wydzielone z [VictorManager], bo nie zależą od Androida
 * i dzięki temu [GlassesSimulator] oraz testy jednostkowe mogą ich używać
 * bez ciągnięcia za sobą vendor SDK.
 */

/** Liczba niezsynchronizowanych plików w pamięci okularów. */
data class MediaCount(
    val images: Int,
    val videos: Int,
    val records: Int
) {
    val total: Int get() = images + videos + records
}

/** Urządzenie znalezione podczas skanowania BLE. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /**
     * Czy to urządzenie, z którym aplikacja już się kiedyś łączyła.
     *
     * ## Po co
     * Skan BLE pokazuje WSZYSTKO, co nadaje w pobliżu - telewizor, słuchawki,
     * cudzy telefon. Zgłoszone wprost: „znalazł okulary (LG), tylko że to nie
     * są okulary, których używam". Filtrowanie po nazwie byłoby zgadywaniem:
     * nie wiemy, jak przedstawia się KAŻDY egzemplarz tych okularów, a ukrycie
     * tego właściwego jest gorsze niż pokazanie jednego za dużo.
     *
     * Dlatego nie ukrywamy niczego, tylko wskazujemy to, o czym wiemy na pewno:
     * adres, z którym połączenie już raz zadziałało.
     */
    val known: Boolean = false
)

/** Stan połączenia z okularami. */
enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    READY,
    ERROR
}

/** Zdarzenie z fizycznego przycisku na okularach. */
sealed class ButtonEvent {
    object ShortClick : ButtonEvent()
    object DoubleClick : ButtonEvent()
    object TripleClick : ButtonEvent()
    object LongPress : ButtonEvent()
    object Release : ButtonEvent()
}

/** Błąd warstwy komunikacji z okularami. */
class VictorException(message: String) : Exception(message)

/**
 * Wpis dziennika ramek notify - surowy hex plus odczytane znaczenie.
 * Używany przez ekran diagnostyczny; pozwala porównać, co przysłały okulary,
 * z tym, jak to zrozumiał [GlassesProtocol].
 */
data class NotifyLogEntry(
    val timestampMs: Long,
    val hex: String,
    val meaning: String
)

/**
 * Nagranie głosowe w pamięci okularów, widoczne kanałem `RecordHandle`.
 * @see GlassesRecordings
 */
data class Recording(
    val fileName: String,
    val lengthBytes: Int
)
