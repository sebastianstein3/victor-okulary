package pl.victor.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * Odcisk [PhotoMatch] prosto z bajtów JPEG - część zależna od Androida.
 *
 * Osobno, bo samo porównanie jest arytmetyką i ma testy, a dekodowanie obrazu
 * wymaga frameworka. Tu zostaje tylko zmniejszenie do 9 x 8 i przeliczenie na
 * jasność.
 */
object PhotoFingerprint {

    /**
     * @param górnaCzęść jaka część wysokości obrazu idzie do odcisku, od góry.
     *   Mniej niż 1 przy URWANEJ miniaturze: dekoder dopełnia brakujący dół
     *   szarością, a odcisk całości wyszedłby wtedy "inny" dla tego samego
     *   zdjęcia. Góra jest w urwanym pliku kompletna, bo JPEG z okularów
     *   zapisuje się wierszami od góry.
     */
    fun of(jpeg: ByteArray, górnaCzęść: Float = 1f): Long? = runCatching {
        // Najpierw sam rozmiar, potem dekodowanie od razu zmniejszone - pełne
        // zdjęcie z okularów to kilka megapikseli i nie ma powodu trzymać go w
        // pamięci tylko po to, żeby zrobić z niego 72 piksele.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) {
            sample *= 2
        }
        val small = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val wysokość = (small.height * górnaCzęść.coerceIn(0.1f, 1f)).toInt().coerceAtLeast(1)
        val kadr = if (wysokość < small.height) {
            Bitmap.createBitmap(small, 0, 0, small.width, wysokość)
        } else {
            small
        }
        val grid = Bitmap.createScaledBitmap(kadr, PhotoMatch.SZEROKOŚĆ, PhotoMatch.WYSOKOŚĆ, true)
        val jasność = IntArray(PhotoMatch.SZEROKOŚĆ * PhotoMatch.WYSOKOŚĆ) { i ->
            val c = grid.getPixel(i % PhotoMatch.SZEROKOŚĆ, i / PhotoMatch.SZEROKOŚĆ)
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        if (grid !== kadr) grid.recycle()
        if (kadr !== small) kadr.recycle()
        small.recycle()
        PhotoMatch.odcisk(jasność)
    }.getOrNull()
}
