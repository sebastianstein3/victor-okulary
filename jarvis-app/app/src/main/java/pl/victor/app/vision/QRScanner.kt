package pl.victor.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Skaner kodów - ML Kit, na urządzeniu, bez sieci i bez tokenów.
 *
 * ## Dwa różne zadania pod jedną nazwą
 * QR i kod kreskowy dekoduje ta sama biblioteka, ale wymagają czego innego.
 * QR ma duże znaczniki w rogach i moduły po kilka pikseli - składa się nawet
 * z miniatury. EAN-13 to kilkadziesiąt pionowych kresek, z których najcieńsza
 * na miniaturze ma piksel albo mniej, a po kompresji JPEG zlewa się
 * z sąsiednią. Zgłoszone wprost: "nie rozpoznaje kodów kreskowych" - przy
 * działającym QR.
 *
 * Stąd dwie zmiany względem pierwszej wersji tego pliku:
 *
 *  1. **pełna lista formatów.** Było siedem, brakowało UPC-A i UPC-E (kody
 *     z towarów spoza Europy - a to właśnie przy nich najczęściej chce się
 *     wiedzieć, co to jest), a także ITF, CODE_39, CODE_93 i CODABAR, którymi
 *     znakuje się opakowania zbiorcze, leki i książki;
 *  2. **kilka podejść zamiast jednego.** Gdy pierwszy skan nic nie da,
 *     powtarzamy go na powiększonym obrazie - powód i granice tej sztuczki
 *     opisuje [BarcodeAttempts].
 */
class QRScanner {

    private val tag = "QRScanner"

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            // UPC-A i UPC-E to kody spoza Europy. Brakowało ich, a to
            // dokładnie te towary, przy których najczęściej chce się
            // zapytać "co to właściwie jest".
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
            // ITF - opakowania zbiorcze; CODE_39/93 - leki, magazyny;
            // CODABAR - biblioteki i krew. Każdy z nich bywa jedynym kodem
            // na opakowaniu.
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_AZTEC
        )
        .build()

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(options)

    /**
     * Skanuje kody z bitmapy.
     *
     * Wcześniej ta metoda była zaślepką: uruchamiała skan, ignorowała wynik
     * i **zawsze** zwracała pustą listę. Kod, który jej użył, po cichu nie
     * znajdował żadnego kodu. Teraz czeka na wynik ML Kit na wątku IO.
     *
     * @return znalezione kody (zazwyczaj 0 lub 1)
     */
    suspend fun scan(bitmap: Bitmap): List<ScannedCode> =
        withContext(Dispatchers.IO) { scanSync(bitmap) }

    /** Skanuje kody z bajtów obrazu (JPEG/PNG). */
    suspend fun scanImageBytes(imageBytes: ByteArray): List<ScannedCode> =
        withContext(Dispatchers.IO) { scanImageBytesSync(imageBytes) }

    /** Skanuje kody z pliku na dysku. */
    suspend fun scanFile(path: String): List<ScannedCode> = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(path) ?: return@withContext emptyList()
        scanSync(bitmap)
    }

    /**
     * Sync wrapper - blokuje na wyniku (używać tylko z coroutine na IO dispatcher).
     *
     * Uwaga: ML Kit jest natywnie async. Ten wrapper używa CompletableFuture.
     * Zwraca pustą listę jeśli timeout.
     */
    fun scanSync(bitmap: Bitmap, timeoutMs: Long = 3000): List<ScannedCode> {
        // PIERWSZE PODEJŚCIE NA ORYGINALE, KOLEJNE NA POWIĘKSZENIU.
        //
        // Do wersji z kodem QR wystarczało jedno. Przy kresce o grubości
        // piksela nie wystarcza - patrz [BarcodeAttempts], gdzie jest powód i
        // granice tej sztuczki. Plan prób jest tam, bo to zwykła arytmetyka i
        // da się ją sprawdzić testem; tutaj zostaje samo wywołanie ML Kit.
        val mnożniki = BarcodeAttempts.mnożniki(minOf(bitmap.width, bitmap.height))
        for (m in mnożniki) {
            val próba = if (m == 1) bitmap else powiększ(bitmap, m) ?: continue
            val wynik = jedenSkan(próba, timeoutMs)
            if (próba !== bitmap) próba.recycle()
            if (wynik.isNotEmpty()) {
                if (m > 1) Log.i(tag, "Kod odczytany dopiero po powiększeniu ${m}x")
                return wynik
            }
        }
        if (BarcodeAttempts.beznadziejnyRozmiar(bitmap.width)) {
            // NIE "nie ma kodu", TYLKO "nie ma go jak zobaczyć".
            //
            // Te dwie rzeczy wyglądają z zewnątrz identycznie, a wymagają
            // czego innego: pierwsza - wycelować gdzie indziej, druga -
            // włączyć Wi-Fi albo przestawić źródło zdjęcia na pełne.
            Log.w(
                tag,
                "Obraz ma ${bitmap.width} px szerokości - dla kodu kreskowego " +
                    "za mało (potrzeba circa ${BarcodeAttempts.MIN_SZEROKOŚĆ_EAN})"
            )
        }
        return emptyList()
    }

    /** Czy ten obraz jest za mały, żeby kod kreskowy miał w nim szansę. */
    fun zaMałyNaKodKreskowy(bitmap: Bitmap): Boolean =
        BarcodeAttempts.beznadziejnyRozmiar(bitmap.width)

    private fun powiększ(bitmap: Bitmap, mnożnik: Int): Bitmap? = try {
        Bitmap.createScaledBitmap(
            bitmap, bitmap.width * mnożnik, bitmap.height * mnożnik, true
        )
    } catch (e: Throwable) {
        // OutOfMemory przy powiększaniu nie może zabić tury - lepiej oddać
        // "nie znalazłem kodu" niż wywalić aplikację w sklepie.
        Log.w(tag, "Nie udało się powiększyć ${mnożnik}x: ${e.message}")
        null
    }

    private fun jedenSkan(bitmap: Bitmap, timeoutMs: Long): List<ScannedCode> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val task = scanner.process(image)

        return try {
            // ML Kit zwraca Task z Play Services, nie java.util.concurrent.Future.
            val barcodes = Tasks.await(task, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            barcodes.map { barcode ->
                ScannedCode(
                    rawValue = barcode.rawValue ?: "",
                    format = formatName(barcode.format),
                    url = barcode.url?.url,
                    type = barcodeValueType(barcode.valueType)
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Skan kodu nie powiódł się: ${e.message}")
            emptyList()
        }
    }

    /**
     * Convenience: skanuj z bajtów.
     */
    fun scanImageBytesSync(imageBytes: ByteArray): List<ScannedCode> {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return emptyList()
        return scanSync(bitmap)
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "UNKNOWN($format)"
    }

    private fun barcodeValueType(type: Int): String = when (type) {
        Barcode.TYPE_URL -> "URL"
        Barcode.TYPE_EMAIL -> "EMAIL"
        Barcode.TYPE_PHONE -> "PHONE"
        Barcode.TYPE_SMS -> "SMS"
        Barcode.TYPE_WIFI -> "WIFI"
        Barcode.TYPE_GEO -> "GEO"
        Barcode.TYPE_CALENDAR_EVENT -> "CALENDAR"
        Barcode.TYPE_CONTACT_INFO -> "CONTACT"
        Barcode.TYPE_TEXT -> "TEXT"
        else -> "OTHER"
    }

    fun close() {
        scanner.close()
    }
}

/**
 * Wynik skanowania QR.
 */
data class ScannedCode(
    val rawValue: String,
    val format: String,
    val url: String? = null,
    val type: String = "TEXT"
) {
    /**
     * Human-readable opis kodu.
     */
    fun describe(): String = when (type) {
        "URL" -> "Link: $url"
        "EMAIL" -> "Email: $rawValue"
        "PHONE" -> "Telefon: $rawValue"
        "WIFI" -> "WiFi: $rawValue"
        "CONTACT" -> "Wizytówka: $rawValue"
        else -> rawValue
    }
}
