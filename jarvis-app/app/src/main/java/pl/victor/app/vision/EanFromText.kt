package pl.victor.app.vision

/**
 * Kod produktu z CYFR wydrukowanych pod kreskami - bez dekodowania samych kresek.
 *
 * ## Po co
 * Z dziennika użytkownika z 23 września: kilkanaście prób skanowania puszki
 * kukurydzy i ML Kit NIE ODCZYTAŁ KODU ANI RAZU (`pytanie o kod, ale ŻADNEGO nie
 * odczytano`), podczas gdy model patrzący na TO SAMO zdjęcie mówił "widzę
 * puszkę kukurydzy Bonduelle, kod kreskowy jest w pełni widoczny".
 *
 * To nie jest sprzeczność. Dekoder kresek potrzebuje, żeby NAJCIEŃSZA kreska
 * miała co najmniej piksel - a na miniaturze z okularów ma ułamek piksela. Cyfry
 * pod kodem są za to kilkukrotnie większe od kresek i rozpoznawanie tekstu
 * czyta je z tej samej miniatury bez trudu.
 *
 * ## Czemu to jest bezpieczne
 * Bo EAN ma CYFRĘ KONTROLNĄ. Przypadkowy ciąg trzynastu cyfr z OCR-a (numer
 * partii, data, cena) przechodzi sprawdzenie z prawdopodobieństwem 1 na 10 - a
 * i wtedy baza produktów po prostu go nie zna. Ciąg, który przeszedł, prawie na
 * pewno JEST kodem z opakowania. Bez tego sprawdzenia ta droga pytałaby Open
 * Food Facts o daty ważności.
 *
 * ## Czego tu nie robimy
 * Nie "poprawiamy" cyfr, które sprawdzenia nie przeszły. Zamiana litery O na
 * zero albo l na jedynkę dawałaby kody, których nie było na opakowaniu - a to
 * gorsze niż brak kodu, bo prowadzi do cudzego produktu z cudzymi alergenami.
 */
object EanFromText {

    /**
     * Pierwszy poprawny kod produktu w tekście albo `null`.
     *
     * ## Tylko w układach, w jakich kody się DRUKUJE
     * Pierwsza wersja przesuwała okno po każdym ciągu cyfr. Cyfra kontrolna
     * przepuszcza przypadkowy ciąg z szansą 1 na 10 - a okno przesuwne robi z
     * tego 1 na 10 NA KAŻDĄ POZYCJĘ. Numer telefonu z opakowania ("48 123 456
     * 789") miałby kilkadziesiąt procent szans, że któreś okno przejdzie. To
     * znaczy pytanie bazy o cudzy produkt - z cudzymi alergenami.
     *
     * Kody mają za to standardowy układ druku i tylko ten przyjmujemy:
     *  - EAN-13: jednym ciągiem albo 1 + 6 + 6 ("5 901234 123457"),
     *  - UPC-A:  jednym ciągiem albo 1 + 5 + 5 + 1 ("0 36000 29145 2"),
     *  - EAN-8:  jednym ciągiem albo 4 + 4 ("9638 5074").
     *
     * Dłuższy kod wygrywa: EAN-8 wycięty z dłuższego ciągu to prawie zawsze
     * przypadek, a nie osobny kod.
     */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (grupa in CIĄGI.findAll(text)) {
            val kawałki = grupa.value.split(Regex("""[ \t\u00A0]""")).filter { it.isNotEmpty() }
            for ((długość, układy) in UKŁADY) {
                for (układ in układy) {
                    // Szukamy układu jako CIĄGŁEGO fragmentu listy kawałków -
                    // kod bywa poprzedzony albo zakończony inną liczbą w tej
                    // samej linii, ale nigdy nie jest z nią sklejony w środku.
                    for (start in 0..(kawałki.size - układ.size)) {
                        val okno = kawałki.subList(start, start + układ.size)
                        if (okno.map { it.length } != układ) continue
                        val kandydat = okno.joinToString("")
                        if (kandydat.length == długość && poprawnaCyfraKontrolna(kandydat)) {
                            return kandydat
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Cyfra kontrolna GS1 - wspólna dla EAN-8, UPC-A (12) i EAN-13.
     *
     * Wagi 3 i 1 naprzemiennie, licząc OD PRAWEJ, z pominięciem samej cyfry
     * kontrolnej. Liczenie od prawej sprawia, że jedna funkcja obsługuje
     * wszystkie trzy długości.
     */
    fun poprawnaCyfraKontrolna(kod: String): Boolean {
        if (kod.length !in DŁUGOŚCI || !kod.all { it.isDigit() }) return false
        // Same zera przechodzą sprawdzenie, ale nie są żadnym kodem - OCR
        // czyta je z pustych pól formularzy i ramek.
        if (kod.all { it == '0' }) return false
        val cyfry = kod.map { it - '0' }
        val kontrolna = cyfry.last()
        val suma = cyfry.dropLast(1).reversed().withIndex().sumOf { (i, c) ->
            if (i % 2 == 0) c * 3 else c
        }
        return (10 - suma % 10) % 10 == kontrolna
    }

    private val DŁUGOŚCI = listOf(13, 12, 8)

    /** Standardowe układy druku - patrz [find]. Kolejność: od najdłuższego kodu. */
    private val UKŁADY: List<Pair<Int, List<List<Int>>>> = listOf(
        13 to listOf(listOf(13), listOf(1, 6, 6)),
        12 to listOf(listOf(12), listOf(1, 5, 5, 1)),
        8 to listOf(listOf(8), listOf(4, 4))
    )

    /** Cyfry rozdzielone co najwyżej pojedynczymi białymi znakami. */
    private val CIĄGI = Regex("""\d(?:[ \t ]?\d)+""")
}
