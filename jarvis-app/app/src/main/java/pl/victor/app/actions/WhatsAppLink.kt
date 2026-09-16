package pl.victor.app.actions

import java.net.URLEncoder

/**
 * Adres otwierający rozmowę na WhatsAppie z gotowym tekstem.
 *
 * ## Czemu WhatsApp, skoro jest SMS
 * Bo ludzie piszą tam, a nie SMS-em. Asystent, który umie wysłać wiadomość
 * wyłącznie kanałem, z którego adresat nie korzysta, jest w tej jednej rzeczy
 * bezużyteczny.
 *
 * ## CZEGO TO NIE ROBI - i trzeba to powiedzieć wprost
 * NIE wysyła wiadomości. WhatsApp nie udostępnia żadnej drogi, którą obca
 * aplikacja mogłaby wysłać wiadomość w czyimś imieniu - i dobrze, bo inaczej
 * mógłby to zrobić dowolny program na telefonie. `wa.me` otwiera rozmowę z
 * WPISANYM tekstem; wysyłkę zatwierdza człowiek jednym dotknięciem.
 *
 * Aplikacja ma to mówić uczciwie ("otwieram WhatsAppa z gotową wiadomością"),
 * a nie udawać, że wysłała - bo to jest różnica między wiadomością dostarczoną
 * a wiadomością wiszącą w niewysłanym oknie.
 */
object WhatsAppLink {

    /**
     * @param phone numer w dowolnym zapisie - ze spacjami, myślnikami, plusem
     * @param text treść do wpisania w oknie rozmowy
     * @return adres `https://wa.me/...` albo `null`, gdy numer nie ma sensu
     */
    fun forNumber(phone: String, text: String): String? {
        val digits = normalizeNumber(phone) ?: return null
        val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return "https://wa.me/$digits?text=$encoded"
    }

    /**
     * Numer sprowadzony do samych cyfr z kierunkowym, bo `wa.me` innego nie
     * przyjmuje.
     *
     * Polski numer bez kierunkowego dostaje 48. To jedyne zgadywanie w tym
     * pliku i jest świadome: aplikacja jest polska, a numer dziewięciocyfrowy
     * bez kierunkowego w polskim telefonie znaczy polski numer. Numer z plusem
     * albo z zerami wiodącymi zostaje bez zmian - tam kraj jest już podany.
     */
    fun normalizeNumber(phone: String): String? {
        val raw = phone.trim()
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return when {
            raw.startsWith("+") -> digits
            digits.startsWith("00") -> digits.removePrefix("00")
            digits.length == POLISH_LENGTH -> POLISH_PREFIX + digits
            else -> digits
        }.takeIf { it.length in MIN_DIGITS..MAX_DIGITS }
    }

    private const val POLISH_LENGTH = 9
    private const val POLISH_PREFIX = "48"

    // Zakres z zalecenia E.164: najkrótsze numery krajowe z kierunkowym mają
    // osiem cyfr, najdłuższe piętnaście. Poza nim to nie jest numer telefonu,
    // tylko przesłyszenie - a otwieranie WhatsAppa na śmieciach wygląda gorzej
    // niż przyznanie, że nie zrozumiałem.
    private const val MIN_DIGITS = 8
    private const val MAX_DIGITS = 15
}
