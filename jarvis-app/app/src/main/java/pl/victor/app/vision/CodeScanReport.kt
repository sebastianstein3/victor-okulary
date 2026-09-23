package pl.victor.app.vision

/**
 * Co powiedzieć MODELOWI o tym, jak poszło odczytywanie kodu.
 *
 * ## Po co
 * Z dziennika z 23 września, kilkanaście tur o kod: model mówił "kod QR jest
 * niewyraźny", "kod kreskowy jest zasłonięty", "nie mam jak sprawdzić informacji
 * o produkcie" - o kodzie, który był wyraźny, na odsłoniętej puszce, przy
 * aplikacji, która MA dostęp do bazy Open Food Facts.
 *
 * Model nie kłamał, tylko zgadywał. Nie wiedział trzech rzeczy:
 *  1. że aplikacja próbowała odczytać kod sama - i czy się udało;
 *  2. że dostał MINIATURĘ, na której kresek po prostu nie ma - i dlaczego;
 *  3. że baza produktów istnieje i jest pytana automatycznie.
 *
 * Bez tego jedyne, co widzi, to niewyraźny obraz - i szuka dla niego przyczyny
 * w obrazie. Stąd "zasłonięty" i "rozmyty".
 *
 * ## Czego to NIE robi
 * Nie każe modelowi odczytać kodu z obrazu i podać go jako pewny. Cyfry
 * przeczytane przez model mogą być błędne, a błędny kod to cudzy produkt z
 * cudzymi alergenami. Model ma je PRZECZYTAĆ człowiekowi - a sprawdzenie w
 * bazie idzie dopiero wtedy, gdy człowiek je powtórzy i przejdą cyfrę
 * kontrolną (patrz [EanFromText]).
 */
object CodeScanReport {

    /**
     * @param pytanieOKod czy tura w ogóle dotyczy kodu
     * @param kodProduktu kod produktu, który się udało odczytać (kreski albo cyfry)
     * @param produktZnaleziony czy baza produktów go zna
     * @param pełnaRozdzielczość czy którekolwiek zdjęcie tej tury było oryginałem
     * @param powódMiniatury czemu oryginału nie było - `null`, gdy nie wiadomo
     * @return fragment promptu albo `null`, gdy nie ma czego dopowiadać
     */
    fun dlaModelu(
        pytanieOKod: Boolean,
        kodProduktu: String?,
        produktZnaleziony: Boolean,
        pełnaRozdzielczość: Boolean,
        powódMiniatury: String?
    ): String? {
        if (!pytanieOKod) return null
        // Produkt znaleziony - dane z bazy są już w prompcie osobno i mówią
        // same za siebie.
        if (kodProduktu != null && produktZnaleziony) return null

        if (kodProduktu != null) {
            return "Aplikacja odczytała kod produktu $kodProduktu i sprawdziła go w " +
                "bazie Open Food Facts - tego produktu w niej NIE MA. Powiedz to " +
                "wprost i krótko. Nie mów, że nie masz dostępu do bazy produktów: " +
                "masz, tylko tego jednego produktu tam brakuje. Możesz opisać, co " +
                "widać na opakowaniu."
        }

        val jakieZdjęcie = if (pełnaRozdzielczość) {
            "Zdjęcie było w pełnej rozdzielczości."
        } else {
            "Zdjęcie było tylko MINIATURĄ o niskiej rozdzielczości" +
                (powódMiniatury?.let { " (powód: $it)" } ?: "") +
                " - na miniaturze kreski kodu zlewają się ze sobą, więc to jest " +
                "najbardziej prawdopodobna przyczyna."
        }
        return "Aplikacja próbowała odczytać kod automatycznie i NIE DAŁA RADY. " +
            jakieZdjęcie + " " +
            "Nie wymyślaj innej przyczyny - nie mów, że kod jest zasłonięty albo " +
            "rozmazany, jeśli wyraźnie tego nie widać. " +
            "Aplikacja MA dostęp do bazy produktów Open Food Facts i sprawdza w " +
            "niej kody sama. Jeśli pod kodem kreskowym widzisz cyfry, przeczytaj " +
            "je wszystkie po kolei i powiedz, że użytkownik może je powtórzyć, na " +
            "przykład: sprawdź produkt, a potem cyfry - wtedy aplikacja sprawdzi " +
            "je w bazie. Jeśli to kod QR, nie zgaduj, jaki adres w nim jest." +
            (if (!pełnaRozdzielczość && powódMiniatury != null) {
                " Powiedz też krótko, co zrobić, żeby następnym razem przyszło " +
                    "ostrzejsze zdjęcie."
            } else {
                ""
            })
    }
}
