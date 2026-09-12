package pl.victor.app.ble

import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.file.AlbumHandle
import com.oudmon.ble.base.communication.file.IEbookCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lista zdjęć i filmów z okularów **przez BLE**, bez Wi-Fi Direct.
 *
 * ## Skąd to się wzięło
 * Z pytania użytkownika: „galeria w starej apce Prism Pro działała - czy nie
 * możesz jej przejrzeć, żeby zobaczyć, jak to robiła". Samej apki nie mam, ale
 * mam AAR producenta - i tam jest odpowiedź.
 *
 * Vendor SDK ma klasę `AlbumHandle` z WŁASNYM kanałem szeregowym po BLE
 * (usługa `de5bf728-…`, notify `…729`, zapis `…72a`) i callbackiem
 * `IEbookCallback.onFileNames(ArrayList<String>)`. Listy plików NIE trzeba
 * więc brać przez HTTP z sieci Wi-Fi Direct - okulary podadzą ją po tym samym
 * łączu, którym idą komendy.
 *
 * To tłumaczy, czemu galeria producenta działała na telefonie, na którym nasza
 * nie działa: nasza stoi w całości na Wi-Fi Direct, a ten na tym egzemplarzu
 * nie wstaje (w dzienniku: „Wi-Fi Direct nie oddał oryginału", 61 i 86 sekund).
 *
 * ## Czego nadal nie wiemy
 * Numeru typu pliku. `start(fileType)` przyjmuje liczbę, której producent
 * nigdzie nie udokumentował, a SDK inicjalizuje ją na `0` - dokładnie ten sam
 * problem, który opisuje [GlassesRecordings]. Dlatego typ jest tu
 * PARAMETREM, a wynik każdej próby idzie do dziennika: dopiero sprzęt
 * powie, która wartość oznacza zdjęcia.
 *
 * Pobieranie samego pliku zostaje na [GlassesRecordings]: `AlbumHandle` nie ma
 * metody czytającej (`cmdSendPacket` i `readNextBigPocket` służą do WYSYŁANIA
 * do okularów), a `RecordHandle.readRecordFile(typ, nazwa)` przyjmuje typ jako
 * parametr, więc jest drogą ogólną, nie tylko audio.
 */
class GlassesAlbum(
    private val handle: AlbumHandle = AlbumHandle.getInstance(),
    private val largeDataHandler: LargeDataHandler = LargeDataHandler.getInstance()
) {

    private val mutex = Mutex()

    /**
     * Pobiera listę plików albumu.
     *
     * @param fileType nieudokumentowany numer typu - patrz opis klasy
     * @return nazwy plików albo pusta lista, gdy okulary nie odpowiedziały
     */
    suspend fun list(fileType: Int = DEFAULT_FILE_TYPE): List<String> = mutex.withLock {
        val result = CompletableDeferred<List<String>>()

        val callback = object : EbookCallbackAdapter() {
            override fun onFileNames(names: ArrayList<String>?) {
                val list = names.orEmpty().filter { it.isNotBlank() }
                if (!result.isCompleted) result.complete(list)
            }

            override fun onActionResult(code: Int) {
                if (code != 0 && !result.isCompleted) {
                    Log.w(TAG, "Lista albumu: okulary zgłosiły błąd (kod=$code)")
                    result.complete(emptyList())
                }
            }
        }

        session(callback) {
            handle.start(fileType)
            withTimeoutOrNull(LIST_TIMEOUT_MS) { result.await() } ?: run {
                Log.w(TAG, "Lista albumu: brak odpowiedzi w $LIST_TIMEOUT_MS ms")
                emptyList()
            }
        }
    }

    /**
     * Zestawia sesję `AlbumHandle` i sprząta po niej.
     *
     * Tak samo jak w [GlassesRecordings]: `initRegister()` podmienia callback w
     * `BleOperateManager`, a to jedno gniazdo na całą aplikację. `initEnable()`
     * na końcu przywraca nasłuch ramek notify - taniej niż ryzykować, że
     * przestaną dochodzić przyciski i zdjęcia.
     */
    private inline fun <T> session(callback: IEbookCallback, block: () -> T): T {
        handle.clearCallback()
        handle.registerCallback(callback)
        handle.initRegister()
        return try {
            block()
        } finally {
            runCatching { handle.endAndRelease() }
                .onFailure { Log.w(TAG, "endAndRelease nie powiodło się", it) }
            handle.clearCallback()
            runCatching { largeDataHandler.initEnable() }
                .onFailure { Log.w(TAG, "Przywrócenie nasłuchu notify nie powiodło się", it) }
        }
    }

    companion object {
        private const val TAG = "GlassesAlbum"

        /** SDK inicjalizuje `currFileType` na 0 - najlepszy punkt startu. */
        const val DEFAULT_FILE_TYPE = 0

        /**
         * Ile czekamy na listę przy JEDNYM typie pliku.
         *
         * Cztery sekundy, nie dziesięć. Okulary albo odpowiadają ramką `BC 80`
         * od razu (SDK składa listę z kolejnych ramek i oddaje ją, gdy przyjdzie
         * ramka o zerowej długości), albo nie odpowiadają wcale - w dzienniku
         * z 12 września dwie próby stanęły równo na 10,000 i 10,003 s, czyli na
         * limicie, bez ani jednej ramki. Dziesięć sekund ciszy razy kilka typów
         * to półtorej minuty patrzenia na pustą galerię.
         */
        private const val LIST_TIMEOUT_MS = 4_000L
    }
}

/** Pusta implementacja [IEbookCallback] - interfejs wymaga pięciu metod. */
private abstract class EbookCallbackAdapter : IEbookCallback {
    override fun onFileNames(names: ArrayList<String>?) = Unit
    override fun onProgress(fraction: Float) = Unit
    override fun onComplete() = Unit
    override fun onDeleteSuccess(type: Int) = Unit
    override fun onActionResult(code: Int) = Unit
}
