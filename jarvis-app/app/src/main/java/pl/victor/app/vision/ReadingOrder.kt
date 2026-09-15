package pl.victor.app.vision

/**
 * Kolejność czytania: najpierw to, co największe, potem reszta po kolei.
 *
 * ## Skąd to się wzięło
 * Zgłoszenie z terenu: „tryb czytania musi zaczynać od tego, co największe i
 * najważniejsze, ale potem niech czyta dalej - teraz przeczytał mi jedno słowo
 * i tyle".
 *
 * Dotąd szedł na głos surowy `fullText` z ML Kit, czyli bloki w kolejności, w
 * jakiej rozpoznał je silnik - z grubsza od góry do dołu. Na tabliczce, plakacie
 * albo opakowaniu to jest zła kolejność: NAJWAŻNIEJSZY jest napis największy, a
 * ten stoi gdzie indziej niż najwyżej. Człowiek patrzący na tablicę widzi
 * nagłówek w ułamku sekundy; słuchający musi czekać, aż lektor do niego dojdzie
 * - o ile w ogóle dojdzie, bo do tego czasu zwykle przerywa.
 *
 * ## Co robimy
 * Napis dominujący idzie PIERWSZY, reszta zaraz po nim, w naturalnej kolejności
 * czytania. Nic nie wypada - to jest przestawienie, nie skracanie.
 *
 * ## Czemu wysokość, a nie pole albo długość
 * Bo wysokość ramki to rozmiar LITER, a pole zależy głównie od tego, ile słów
 * jest w bloku. Długi wiersz drobnym drukiem ma większe pole niż dwuwyrazowy
 * nagłówek, a to nagłówek jest tym, po co ktoś patrzy.
 */
object ReadingOrder {

    /**
     * Ile razy wyższy od średniej musi być blok, żeby uznać go za dominujący.
     *
     * Próg, nie samo maksimum: na stronie jednolitego tekstu najwyższy blok
     * jest wyższy od pozostałych o kilka procent i przestawianie go na przód
     * byłoby losowym mieszaniem zdań. Dopiero wyraźna różnica znaczy nagłówek.
     */
    private const val DOMINANT_RATIO = 1.35

    /** Poniżej tylu bloków nie ma czego porządkować. */
    private const val MIN_BLOCKS = 2

    /**
     * @return tekst do wypowiedzenia - dominujący napis na przodzie, reszta w
     *   kolejności czytania. Gdy nie ma czego przestawiać (brak ramek, jeden
     *   blok, tekst jednolity), oddaje [fallback] bez zmian.
     */
    fun arrange(blocks: List<OCRBlock>, fallback: String): String {
        val usable = blocks.filter { it.text.isNotBlank() && it.boundingBox != null }
        if (usable.size < MIN_BLOCKS) return fallback

        val heights = usable.map { it.boundingBox!!.height() }
        if (heights.any { it <= 0 }) return fallback
        val average = heights.sum().toDouble() / heights.size

        val dominant = usable.maxByOrNull { it.boundingBox!!.height() } ?: return fallback
        val dominantHeight = dominant.boundingBox!!.height()
        if (dominantHeight < average * DOMINANT_RATIO) return fallback

        // Reszta w naturalnej kolejności czytania: z góry na dół, a w obrębie
        // tej samej wysokości od lewej. Porównanie po GÓRNEJ krawędzi, nie po
        // środku - inaczej wysoki nagłówek obok drobnego podpisu wypadałby
        // niżej niż stojący nad nim wiersz.
        val rest = usable
            .filter { it !== dominant }
            .sortedWith(
                compareBy({ it.boundingBox!!.top }, { it.boundingBox!!.left })
            )

        return buildString {
            append(dominant.text.trim())
            for (block in rest) {
                val text = block.text.trim()
                if (text.isEmpty()) continue
                // Kropka na końcu robi w syntezatorze pauzę - bez niej nagłówek
                // zlewa się z pierwszym wierszem drobnego druku w jedno zdanie.
                if (isNotEmpty() && !endsWith(".") && !endsWith("!") && !endsWith("?")) {
                    append('.')
                }
                append(' ')
                append(text)
            }
        }
    }
}
