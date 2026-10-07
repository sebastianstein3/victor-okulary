package pl.victor.app.messages

/**
 * Wiadomość z powiadomienia innej aplikacji (WhatsApp, SMS, Messenger...).
 *
 * @param klucz klucz powiadomienia z systemu - po nim szukamy akcji "Odpowiedz"
 * @param możnaOdpowiedzieć czy powiadomienie miało pole odpowiedzi
 */
data class IncomingMessage(
    val klucz: String,
    val pakiet: String,
    val aplikacja: String,
    val nadawca: String,
    val tekst: String,
    val czasMs: Long,
    val możnaOdpowiedzieć: Boolean,
    val rozmowaGrupowa: Boolean = false
)

/**
 * Które aplikacje czytać i jak je nazywać na głos.
 *
 * Lista jest zamknięta: czytanie WSZYSTKICH powiadomień znaczyłoby
 * promocje, aktualizacje i "zrób kopię zapasową" w uchu co kilka minut.
 */
object MessagingApps {

    data class Aplikacja(val pakiet: String, val nazwa: String, val domyślnieWłączona: Boolean = true)

    val ZNANE = listOf(
        Aplikacja("com.whatsapp", "WhatsApp"),
        Aplikacja("com.whatsapp.w4b", "WhatsApp Business"),
        Aplikacja("com.google.android.apps.messaging", "SMS"),
        Aplikacja("com.samsung.android.messaging", "SMS"),
        Aplikacja("com.android.mms", "SMS"),
        Aplikacja("com.facebook.orca", "Messenger"),
        Aplikacja("org.telegram.messenger", "Telegram"),
        Aplikacja("org.thoughtcrime.securesms", "Signal"),
        Aplikacja("com.viber.voip", "Viber", domyślnieWłączona = false),
        Aplikacja("com.google.android.gm", "Gmail", domyślnieWłączona = false)
    )

    fun nazwa(pakiet: String): String? = ZNANE.firstOrNull { it.pakiet == pakiet }?.nazwa

    fun domyślne(): Set<String> = ZNANE.filter { it.domyślnieWłączona }.map { it.pakiet }.toSet()
}

/**
 * Czysta logika skrzynki: co jest nowe, co ogłosić, czego nie powtarzać.
 *
 * WhatsApp i SMS publikują to samo powiadomienie wielokrotnie - z każdą nową
 * wiadomością w rozmowie, po odczytaniu, po zmianie dźwięku. Bez pamięci
 * "już powiedziane" ta sama wiadomość wracałaby do ucha kilka razy.
 */
class InboxLogic(private val pamięćMs: Long = PAMIĘĆ_MS, private val maks: Int = MAKS) {

    private val wiadomości = ArrayDeque<IncomingMessage>()
    private val znane = LinkedHashMap<String, Long>()

    /** @return `true`, gdy wiadomość jest nowa i warto ją ogłosić. */
    fun dodaj(m: IncomingMessage): Boolean {
        val teraz = m.czasMs
        znane.entries.removeAll { teraz - it.value > pamięćMs }
        val odcisk = "${m.pakiet}|${m.nadawca}|${m.tekst}"
        if (odcisk in znane) {
            // Ta sama treść, nowsze powiadomienie: odśwież klucz (do odpowiedzi
            // potrzebny jest NAJNOWSZY), ale nie ogłaszaj drugi raz.
            val i = wiadomości.indexOfFirst { "${it.pakiet}|${it.nadawca}|${it.tekst}" == odcisk }
            if (i >= 0) wiadomości[i] = m
            return false
        }
        znane[odcisk] = teraz
        wiadomości.addLast(m)
        while (wiadomości.size > maks) wiadomości.removeFirst()
        return true
    }

    fun ostatnie(n: Int = 5): List<IncomingMessage> = wiadomości.toList().takeLast(n)

    /**
     * Do kogo odpowiedzieć: po imieniu (dowolna część, bez wielkości liter i
     * odmiany - "Ani" pasuje do "Ania Kowalska"), a bez imienia - do ostatniej.
     */
    fun adresat(imię: String?): IncomingMessage? {
        val kandydaci = wiadomości.toList().asReversed()
        if (imię.isNullOrBlank()) return kandydaci.firstOrNull()
        val rdzeń = rdzeń(imię)
        return kandydaci.firstOrNull { m ->
            m.nadawca.lowercase().split(Regex("""[\s,:]+""")).any { słowo ->
                słowo.length >= 2 && (rdzeń(słowo) == rdzeń || słowo.startsWith(rdzeń) || rdzeń.startsWith(rdzeń(słowo)))
            }
        }
    }

    fun wyczyść() {
        wiadomości.clear()
        znane.clear()
    }

    companion object {
        const val PAMIĘĆ_MS = 30 * 60_000L
        const val MAKS = 20

        /**
         * Rdzeń imienia na potrzeby dopasowania odmian: "Ani", "Anię", "Ania"
         * -> "an"; "Tomkowi", "Tomek" -> "tom". Celowo zgrubnie - lista
         * kandydatów to kilka ostatnich nadawców, nie książka adresowa.
         */
        fun rdzeń(słowo: String): String {
            val s = słowo.lowercase().trim().trimEnd('.', ',', '!', '?')
            val bez = KOŃCÓWKI.firstOrNull { s.endsWith(it) && s.length - it.length >= 2 }?.let { s.dropLast(it.length) } ?: s
            return bez.take(4)
        }

        private val KOŃCÓWKI = listOf("kowi", "owi", "kiem", "iem", "ego", "ka", "ki", "ku", "ia", "ii", "ię", "ią", "ie", "i", "a", "ę", "ą", "u", "y")
    }
}
