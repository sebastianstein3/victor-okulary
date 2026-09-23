package pl.victor.app.vision

/**
 * Które odczytane kody są KODAMI PRODUKTU i w jakiej postaci pytać o nie bazę.
 *
 * ## Skąd to się wzięło
 * W orkiestratorze stał filtr `format == "EAN_13" || format == "EAN_8"`.
 * Wpuszczał dwa formaty z pięciu, którymi znakuje się towar w sklepie - i
 * robił to po cichu: kod dawał się odczytać, po czym kończył jako trzynaście
 * cyfr przeczytanych na głos. Dla osoby niewidomej w sklepie to jest różnica
 * między "płatki owsiane, 500 g, zawiera gluten" a ciągiem cyfr.
 *
 * ## Czemu UPC to nie jest osobny świat
 * UPC-A to ten sam ciąg co EAN-13 z wiodącym zerem - i tak właśnie trzyma go
 * Open Food Facts. UPC-E jest jego skróconym zapisem, ale ML Kit rozwija go do
 * pełnych dwunastu cyfr, zanim odda `rawValue`, więc po naszej stronie
 * wystarczy dopełnić do trzynastu. Zapytanie bazy o dwanaście cyfr wraca
 * pustką, choć produkt tam JEST - a z zewnątrz wygląda to jak "nie ma go w
 * bazie".
 *
 * ## Czego tu nie ma
 * CODE_128, ITF, CODE_39 i reszta są odczytywane (patrz [QRScanner]), ale NIE
 * są kodami produktu: niosą numery palet, partii i wypożyczeń. Pytanie o nie
 * Open Food Facts byłoby zawsze chybione, a odpowiedź "nie ma w bazie"
 * myląca - bo sugeruje, że mogłoby być.
 */
object ProductCode {

    /** Formaty, które naprawdę oznaczają towar - i nic poza nimi. */
    private val FORMATY_PRODUKTU = setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E")

    /**
     * @return nazwa formatu, gdy to kod produktu; `null`, gdy to coś innego
     *   (paleta, partia, wypożyczenie, adres w QR)
     */
    fun toKodProduktu(format: String): String? = format.takeIf { it in FORMATY_PRODUKTU }

    /**
     * Postać, o którą pyta się bazę.
     *
     * Dwunastocyfrowy UPC dopełniamy wiodącym zerem do EAN-13. Ośmiocyfrowego
     * EAN-8 NIE dopełniamy - to osobny, krótszy kod, a nie skrócony EAN-13, i
     * baza trzyma go dokładnie tak, jak jest.
     */
    fun znormalizuj(raw: String): String {
        val cyfry = raw.trim()
        if (!cyfry.all { it.isDigit() }) return cyfry
        return if (cyfry.length == DŁUGOŚĆ_UPC) "0$cyfry" else cyfry
    }

    private const val DŁUGOŚĆ_UPC = 12
}
