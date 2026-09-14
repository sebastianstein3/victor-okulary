package pl.victor.app.stream

/**
 * Przepisuje klatkę z dekodera do postaci, z której da się zrobić JPEG.
 *
 * ## Po co w ogóle przepisywać
 * `MediaCodec` oddaje obraz w `YUV_420_888` - a to nie jest jeden układ
 * bajtów, tylko RODZINA układów. Każdy producent układu scalonego ma swój:
 * inne odstępy między wierszami (`rowStride`), inne między pikselami
 * (`pixelStride`), czasem chrominancja przeplatana, czasem osobno. Kod, który
 * zakłada jeden z nich, działa na jednym telefonie i daje zielone pasy na
 * drugim.
 *
 * Tu przepisujemy to na NV21 - jedyny układ, który przyjmuje `YuvImage`,
 * czyli najprostsza droga do JPEG-a bez dokładania bibliotek.
 *
 * ## Czemu to jest osobno i czyste
 * Bo tu właśnie mieszkają błędy tego rodzaju, który NIE KRZYCZY: pomylony
 * odstęp nie rzuca wyjątkiem, tylko przesuwa obraz o kilka pikseli na wiersz i
 * wychodzi z tego ukos albo pasy. Czysta funkcja daje się sprawdzić testem,
 * kod siedzący w dekoderze nie daje się sprawdzić wcale.
 */
object YuvFrame {

    /** Klatka w układzie NV21 razem z wymiarami, które naprawdę ma. */
    data class Nv21(val bytes: ByteArray, val width: Int, val height: Int) {
        // ByteArray w data class porównuje się przez referencję - domyślny
        // equals kłamałby o równości dwóch identycznych klatek.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Nv21 && width == other.width && height == other.height &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            31 * (31 * bytes.contentHashCode() + width) + height
    }

    /**
     * Składa NV21 z trzech płaszczyzn, po drodze pomniejszając obraz.
     *
     * ## Czemu pomniejszanie jest TUTAJ, a nie potem
     * Bo klatka z okularów ma 1600x1200, czyli prawie dwa megapiksele, a model
     * nie potrzebuje ich, żeby powiedzieć "przed tobą schody". Zmniejszenie po
     * zrobieniu JPEG-a znaczyłoby kodowanie dwóch megapikseli, dekodowanie ich
     * z powrotem i skalowanie - trzy razy więcej pracy niż wzięcie co drugiego
     * piksela od razu. Przy obrazie idącym do modelu kilka razy na minutę to
     * różnica, którą czuć na baterii i na rachunku.
     *
     * @param sample bierz co N-ty piksel; 1 to pełna rozdzielczość
     * @return klatka albo `null`, gdy dane są niespójne - uszkodzona klatka ma
     *   zostać pominięta, a nie wywrócić aplikację
     */
    @Suppress("LongParameterList")
    fun toNv21(
        y: ByteArray, yRowStride: Int, yPixelStride: Int,
        u: ByteArray, uRowStride: Int, uPixelStride: Int,
        v: ByteArray, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int,
        sample: Int = 1
    ): Nv21? {
        val step = sample.coerceAtLeast(1)
        // Wymiary MUSZĄ być parzyste: chrominancja ma połowę rozdzielczości, a
        // nieparzysta szerokość zostawiłaby pół kolumny bez koloru.
        val outW = (width / step) and EVEN_MASK
        val outH = (height / step) and EVEN_MASK
        if (outW < MIN_SIDE || outH < MIN_SIDE) return null
        if (yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0) return null
        if (yPixelStride <= 0 || uPixelStride <= 0 || vPixelStride <= 0) return null

        // Sprawdzamy zasięg Z GÓRY, zamiast łapać wyjątek w środku pętli:
        // klatka przepisana w połowie jest gorsza niż pominięta, bo wygląda
        // jak obraz, a nie jak awaria.
        val yMax = (outH - 1) * step * yRowStride + (outW - 1) * step * yPixelStride
        if (yMax >= y.size) return null
        val halfW = outW / 2
        val halfH = outH / 2
        val uMax = (halfH - 1) * step * uRowStride + (halfW - 1) * step * uPixelStride
        val vMax = (halfH - 1) * step * vRowStride + (halfW - 1) * step * vPixelStride
        if (uMax >= u.size || vMax >= v.size) return null

        val out = ByteArray(outW * outH * 3 / 2)
        var pos = 0
        for (row in 0 until outH) {
            val srcRow = row * step * yRowStride
            for (col in 0 until outW) {
                out[pos++] = y[srcRow + col * step * yPixelStride]
            }
        }
        // NV21 przeplata chrominancję i bierze V PRZED U. Odwrotna kolejność
        // nie psuje kształtu - zamienia kolory, więc niebo wychodzi
        // pomarańczowe. Objaw wygląda na usterkę kamery, nie kodu.
        for (row in 0 until halfH) {
            val srcU = row * step * uRowStride
            val srcV = row * step * vRowStride
            for (col in 0 until halfW) {
                val offset = col * step
                out[pos++] = v[srcV + offset * vPixelStride]
                out[pos++] = u[srcU + offset * uPixelStride]
            }
        }
        return Nv21(out, outW, outH)
    }

    /**
     * Dobiera krok pomniejszania tak, żeby dłuższy bok zmieścił się w limicie.
     *
     * Całkowity, bo branie co N-tego piksela jest tanie, a prawdziwe skalowanie
     * z uśrednianiem kosztuje tyle, że przestałoby się opłacać.
     */
    fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
        val longer = maxOf(width, height)
        if (maxSide <= 0 || longer <= maxSide) return 1
        // Najmniejszy krok, przy którym bok MIEŚCI SIĘ w limicie. Warunek musi
        // być ostry: przy 1600 i limicie 800 krok 2 daje dokładnie 800, czyli
        // już dobrze - pierwsza wersja przeskakiwała tę wartość i schodziła
        // niepotrzebnie do 533 pikseli.
        var step = 1
        while (longer / step > maxSide) step++
        return step
    }

    /**
     * Zgrubny odcisk jasności klatki - do rozpoznania, czy scena się zmieniła.
     *
     * ## Czemu nie suma kontrolna
     * Bo już próbowano i nie zadziałało. Komentarz w AccessibilityService
     * opisuje to wprost: warunek "nowa scena" porównywał SUMĘ KONTROLNĄ BAJTÓW
     * zdjęcia, a dwa zdjęcia tej samej nieruchomej sceny nigdy nie są
     * identyczne co do bajtu - wystarczy szum matrycy. Warunek przepuszczał
     * więc wszystko i został usunięty jako udawanie.
     *
     * Odcisk porównuje OBRAZ, nie bajty: dzieli kadr na siatkę i liczy średnią
     * jasność w każdym polu. Szum matrycy uśrednia się do zera, a człowiek,
     * który wszedł w kadr, albo obrót głowy - nie.
     *
     * Bierzemy samą jasność, bez koloru: do pytania "czy to wciąż ta sama
     * scena" kolor nic nie wnosi, a kosztowałby drugie tyle liczenia.
     *
     * @return [GRID] razy [GRID] średnich jasności, albo `null` gdy klatka jest
     *   za mała, żeby siatka miała sens
     */
    fun fingerprint(frame: Nv21): IntArray? {
        if (frame.width < GRID || frame.height < GRID) return null
        val luma = frame.bytes
        val needed = frame.width * frame.height
        if (luma.size < needed) return null

        val out = IntArray(GRID * GRID)
        val cellW = frame.width / GRID
        val cellH = frame.height / GRID
        if (cellW <= 0 || cellH <= 0) return null

        for (cellY in 0 until GRID) {
            for (cellX in 0 until GRID) {
                var sum = 0L
                for (row in 0 until cellH) {
                    val base = (cellY * cellH + row) * frame.width + cellX * cellW
                    for (col in 0 until cellW) {
                        sum += luma[base + col].toInt() and 0xFF
                    }
                }
                out[cellY * GRID + cellX] = (sum / (cellW * cellH)).toInt()
            }
        }
        return out
    }

    /**
     * Czy między dwiema klatkami scena się zmieniła na tyle, żeby warto było
     * pytać model.
     *
     * Liczy średnią różnicę jasności po wszystkich polach siatki. Średnia, nie
     * maksimum: pojedyncze pole potrafi skoczyć od przejeżdżającego samochodu
     * na skraju kadru, a to nie jest jeszcze nowa scena.
     *
     * Brak któregokolwiek odcisku znaczy "nie wiem" - a wtedy pytamy, bo
     * milczenie z niepewności jest gorsze niż jedno zapytanie za dużo.
     */
    fun sceneChanged(before: IntArray?, after: IntArray?, threshold: Int = CHANGE_THRESHOLD): Boolean {
        if (before == null || after == null) return true
        if (before.size != after.size || before.isEmpty()) return true
        var sum = 0L
        for (i in before.indices) sum += kotlin.math.abs(before[i] - after[i])
        return sum / before.size > threshold
    }

    /** Bok siatki odcisku - 64 pola to dość, żeby odróżnić scenę, i mało, żeby liczyć. */
    private const val GRID = 8

    /**
     * Średnia różnica jasności (0-255), powyżej której uznajemy scenę za nową.
     *
     * Dobrane ostrożnie w stronę CZĘSTSZEGO pytania: przegapiona zmiana znaczy,
     * że niewidomy nie usłyszy o czymś, co się zmieniło - a to gorsze niż jeden
     * opis za dużo. Szum matrycy po uśrednieniu w polu siatki daje różnice
     * rzędu jedności, więc ósemka jest daleko od niego.
     */
    private const val CHANGE_THRESHOLD = 8

    /** Maska zerująca najmłodszy bit - czyli zaokrąglenie w dół do parzystej. */
    private const val EVEN_MASK = 0x7FFFFFFE

    /** Poniżej tego nie ma już czego opisywać. */
    private const val MIN_SIDE = 2
}
