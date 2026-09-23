package pl.victor.app.ai

/**
 * Czy pytanie o obraz wymaga SZCZEGÓŁU, czy wystarczy ogólny widok.
 *
 * ## Po co to w ogóle istnieje
 * Zdjęcia z okularów idą domyślnie jako miniatura po BLE - kilkadziesiąt
 * kilobajtów, kilka razy mniejsza rozdzielczość niż to, co naprawdę robi
 * aparat. Do "co przede mną jest" to w zupełności wystarcza i jest szybkie.
 * Do "przeczytaj, co tu pisze" - nie wystarcza w ogóle: liter z bliska po
 * prostu nie widać, a model zgaduje albo mówi, że tekst jest nieczytelny.
 * Zgłoszone dokładnie tak: "AI chyba nadal dostaje miniaturki zdjęć, bo nie
 * może rozczytać liter z bliska".
 *
 * Pełna rozdzielczość wymaga Wi-Fi Direct, a zestawienie grupy P2P to
 * kilkanaście sekund. Dlatego NIE włączamy jej zawsze - tylko wtedy, gdy
 * pytanie faktycznie jej potrzebuje. Ta decyzja jest czystą funkcją, żeby
 * dało się ją sprawdzić testem, a nie warunkiem schowanym w orkiestratorze.
 */
object VisionDetail {

    /**
     * Czy do tego pytania trzeba zdjęcia w pełnej rozdzielczości.
     *
     * @param question pytanie użytkownika (albo polecenie z przycisku)
     */
    fun needsDetail(question: String): Boolean {
        val lower = question.lowercase()
        return STEMS.any { lower.contains(it) } || CODE_STEMS.any { lower.contains(it) }
    }

    /**
     * Czy pytanie dotyczy KODU (QR, kreskowego, EAN).
     *
     * Osobno od [needsDetail], bo do czego innego służy: tam decydujemy o
     * rozdzielczości, tu o tym, co powiedzieć, gdy kodu nie udało się odczytać.
     * Model, który nie wie, że skanowanie zawiodło, zaczyna zmyślać adres -
     * zgłoszone jako "AI nie czyta kodów QR", choć naprawdę nie miał czego
     * czytać.
     */
    fun isAboutCode(question: String): Boolean {
        val lower = question.lowercase()
        return CODE_STEMS.any { lower.contains(it) } || CODE_REGEX.containsMatchIn(lower)
    }

    /**
     * Rdzenie wskazujące na kod.
     *
     * "qr" osobno, bo polskie zdania odmieniają obie części na wszystkie
     * sposoby ("co jest na tym QR kodzie", "zeskanuj kod QR", "ten kjuar") -
     * i właśnie dlatego dawne "kod qr" nie łapało NICZEGO. W polszczyźnie nie
     * ma słowa zawierającego "qr", więc sam ten rdzeń jest bezpieczny.
     */
    private val CODE_STEMS = listOf(
        "qr", "kjuar",
        "barcode", "zeskanuj", "skanuj", "zeskanowa"
    )

    /**
     * "Kod kreskowy" we WSZYSTKICH przypadkach - i to jest poprawka do listy
     * wyżej.
     *
     * Stały tam gotowe zbitki: "kod kresk", "kod pask", "kod ean",
     * "kod produkt". Każda z nich łapie WYŁĄCZNIE mianownik. Wystarczy
     * powiedzieć naturalnie - "nie rozpoznaje kodów kreskowych", "sprawdź
     * z kodu kreskowego", "co jest pod tym kodem kreskowym" - i żadna nie
     * pasuje, bo między "kod" a "kresk" stoi już inna końcówka.
     *
     * Skutek nie był kosmetyczny. Od tej decyzji zależy, czy przy nieudanym
     * skanie sięgamy po zdjęcie w PEŁNEJ ROZDZIELCZOŚCI - a kodu EAN na
     * miniaturze nie odczyta żadna biblioteka, bo kreski mają tam grubość
     * jednego piksela. Zła odmiana = kod nie do odczytania, bez śladu.
     *
     * To ten sam błąd, na który wpadliśmy już przy `\w` i `\b` w regexach:
     * polska odmiana zjada dopasowanie, a test napisany na mianowniku tego
     * nie widzi.
     *
     * Sam rdzeń "kresk" byłby za szeroki - złapałby "kreskówkę" i "kreskę".
     * Stąd wymóg, żeby przed nim stało słowo zaczynające się od "kod".
     */
    private val CODE_REGEX = Regex(
        """kod[a-ząćęłńóśźż]*\s+(kresk|pask|ean|produkt)"""
    )

    /**
     * Rdzenie słów, po których wiadomo, że chodzi o czytanie albo o drobny
     * szczegół. Rdzenie, nie całe słowa - polska odmiana zjadłaby połowę
     * trafień ("etykiety", "etykietce", "z etykietą").
     */
    private val STEMS = listOf(
        // czytanie wprost
        "przeczytaj", "przeczytać", "odczytaj", "odczytać", "czytaj",
        "co tu pisze", "co jest napisane", "napisane", "napis",
        "tekst", "liter", "czcionk", "drobnym drukiem", "drobny druk",
        // rzeczy, które czyta się z bliska
        "etykie", "skład", "instrukcj", "ulotk", "opakowani",
        "menu", "karta dań", "paragon", "rachunek", "faktur", "umow",
        "dokument", "formularz", "recept", "dawkowani",
        "ważnoś", "przydatnoś", "spożyc",
        "numer seryjn", "seria i numer",
        "tablicz", "szyld", "cennik", "cena", "ceny", "cenę", "cenie", "kosztuje",
        "rozkład jazdy", "godziny otwarcia",
        // ekrany i wydruki
        "ekranie", "wyświetlacz", "monitor", "wydruk", "gazet", "książk"
    )
}
