package pl.victor.app.ai

/**
 * Co zrobić, gdy Gemini odrzuci zapytanie kodem 400.
 *
 * ## Po co
 * Dziennik z biegu 152: SZEŚĆDZIESIĄT odmów "Request contains an invalid
 * argument" z rzędu, każde pytanie od pierwszego. Wieczór wcześniej ten sam
 * model i ten sam kod odpowiadały bez zarzutu - zmieniło się coś poza kodem
 * (ustawienie albo to, co serwer akceptuje). Skutek był gorszy niż sama
 * odmowa: rozmowa szła awaryjnie do dostawcy bez wyszukiwarki, więc pogody w
 * Bodrum nie dało się sprawdzić, a model zapewniał, że "może otworzyć w
 * necie".
 *
 * Dotąd ponawialiśmy tylko wtedy, gdy w odmowie padło słowo "thinking". Ta
 * odmowa go nie zawierała - mówi tylko, że JAKIŚ argument jest zły. Zamiast
 * zgadywać który, zdejmujemy po kolei to, co w zapytaniu jest dodatkiem:
 * najpierw prośbę o ograniczenie myślenia (oszczędność, nie treść), potem
 * wyszukiwarkę. Samo pytanie zostaje nietknięte, więc odmowa, która przetrwa
 * obie naprawy, jest prawdziwym błędem i trafia do człowieka.
 */
object GeminiBadRequest {

    enum class Naprawa { BEZ_OGRANICZENIA_MYŚLENIA, BEZ_WYSZUKIWARKI }

    /**
     * @return co zdjąć z zapytania przed ponowieniem albo `null`, gdy
     * ponawianie nic nie da (inny kod, zły klucz, nie było już czego zdjąć)
     */
    fun naprawa(
        kod: Int,
        odpowiedź: String,
        wysłanoOgraniczenieMyślenia: Boolean,
        wysłanoWyszukiwarkę: Boolean
    ): Naprawa? {
        if (kod != 400) return null
        // Zły klucz to też 400 - ale na niego żadna zmiana zapytania nie pomoże.
        if (odpowiedź.contains("API_KEY_INVALID", ignoreCase = true) ||
            odpowiedź.contains("API key not valid", ignoreCase = true)
        ) return null
        return when {
            wysłanoOgraniczenieMyślenia -> Naprawa.BEZ_OGRANICZENIA_MYŚLENIA
            wysłanoWyszukiwarkę -> Naprawa.BEZ_WYSZUKIWARKI
            else -> null
        }
    }

    /** Odpowiedź serwera w jednej linii - dziennik tnie wpisy na znakach nowej linii. */
    fun jednaLinia(odpowiedź: String, maks: Int = 600): String =
        odpowiedź.replace(Regex("\\s+"), " ").trim().take(maks)
}
