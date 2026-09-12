package pl.victor.app.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Miniatury plików z okularów.
 *
 * ## Czemu to w ogóle jest potrzebne
 * Serwer HTTP na okularach nie wystawia miniatur - oddaje wyłącznie pełne
 * pliki. Zdjęcie ma kilka megabajtów, a siatka galerii pokazuje ich kilkanaście
 * naraz. Trzymanie pełnych bitmap w pamięci to pewny `OutOfMemoryError`:
 * pojedyncze zdjęcie 4000x3000 zajmuje po rozkodowaniu 48 MB, a telefon daje
 * aplikacji kilkadziesiąt na wszystko.
 *
 * Dlatego dekodujemy od razu pomniejszone (`inSampleSize`), a pełnych bajtów
 * nie zachowujemy. Oryginał pobieramy ponownie dopiero, gdy ktoś naprawdę
 * chce ten plik otworzyć albo zapisać.
 */
object MediaThumbnails {

    /**
     * Dzielnik, z jakim dekoder ma wczytać obraz, żeby zmieścić się w [target]
     * pikselach na dłuższym boku.
     *
     * Android przyjmuje tu WYŁĄCZNIE potęgi dwójki i po cichu zaokrągla w dół
     * wartości spoza tego zbioru - dlatego liczymy je wprost, zamiast dzielić.
     * Zaniżamy, nigdy nie zawyżamy: miniatura odrobinę za duża jest tylko
     * większa, a za mała jest rozmyta i tego się już nie da odratować.
     *
     * @return potęga dwójki, nigdy mniejsza niż 1
     */
    fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val longer = maxOf(width, height)
        var sample = 1
        while (longer / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    /**
     * Dekoduje bajty JPEG do miniatury o dłuższym boku około [target] pikseli.
     *
     * Dwa przebiegi: pierwszy czyta sam nagłówek (`inJustDecodeBounds`), więc
     * nie alokuje niczego, drugi dekoduje już pomniejszony obraz. Bez pierwszego
     * przebiegu nie da się dobrać dzielnika, a dekodowanie „na próbę" w pełnym
     * rozmiarze jest dokładnie tym, czego chcemy uniknąć.
     *
     * @return miniatura albo `null`, gdy bajty nie są obrazem
     */
    fun decode(bytes: ByteArray, target: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, target)
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrNull()
    }

    /** Typ MIME po rozszerzeniu - dla systemowego odtwarzacza i dla MediaStore. */
    fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        "pcm", "raw" -> "audio/basic"
        else -> "application/octet-stream"
    }
}
