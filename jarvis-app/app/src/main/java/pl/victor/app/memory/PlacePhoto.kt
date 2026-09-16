package pl.victor.app.memory

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zapis zdjęcia zapamiętanego miejsca do galerii telefonu.
 *
 * ## Czemu do galerii, a nie do katalogu aplikacji
 * Bo to zdjęcie ma być OGLĄDANE - po to się je robi. Katalog prywatny
 * aplikacji nie jest widoczny dla żadnej przeglądarki zdjęć i znika razem z
 * odinstalowaniem. Ta sama zasada, co przy pobieraniu zdjęć z okularów.
 */
object PlacePhoto {

    /** @return `true`, gdy plik powstał */
    fun saveToGallery(context: Context, bytes: ByteArray, placeName: String): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = placeName.lowercase()
            .replace(Regex("[^a-ząćęłńóśźż0-9]+"), "-")
            .trim('-')
            .ifEmpty { "miejsce" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "victor-$safeName-$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // RELATIVE_PATH istnieje dopiero od Androida 10. Na 8 i 9 (minSdk
            // tego projektu to 26) ta kolumna nie jest znana i zapis pada -
            // czyli funkcja działałaby wyłącznie na nowszych telefonach, a
            // osoba testująca na starszym zgłosiłaby "nie zapisuje zdjęć".
            // Tam plik trafia do domyślnego katalogu galerii, bez podfolderu.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/VICTOR"
                )
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        }.getOrElse {
            // Sprzątamy po sobie: pusty wpis w galerii wygląda jak uszkodzone
            // zdjęcie i zostaje tam na zawsze.
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
