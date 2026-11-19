package pl.victor.app.conversation

/**
 * Ustawienia zmieniane głosem: tryb bez kamery i motyw.
 *
 * Bieg 183: "włącz tryb bez aparatu" poszło do modelu, który odpowiedział
 * "wyłączam korzystanie z aparatu" - i nic nie przełączył, bo nie miał czym.
 * Takie polecenia rozpoznajemy sami i wykonujemy od razu.
 */
object SettingsVoiceCommands {

    sealed class Polecenie {
        data class BezKamery(val włącz: Boolean) : Polecenie()
        /** "light", "dark" albo "system" - jak SettingsRepository.THEME_*. */
        data class Motyw(val tryb: String) : Polecenie()
    }

    private val BEZ_KAMERY = Regex("""\b(w[lł][aą]cz|wy[lł][aą]cz|uruchom|zako[nń]cz)\w*\s+(tryb\s+)?bez\s+(kamery|aparatu)""")
    private val MOTYW = Regex(
        """\b(motyw|tryb|wygl[aą]d|kolory|ekran)\w*\s+(na\s+)?(jasny|ciemny|nocny|dzienny|jak\s+telefon|systemowy|automatyczny)"""
    )

    fun rozpoznaj(tekst: String): Polecenie? {
        val t = tekst.lowercase().trim()
        BEZ_KAMERY.find(t)?.let { m ->
            val wyłącz = m.groupValues[1].startsWith("wy") || m.groupValues[1].startsWith("zako")
            return Polecenie.BezKamery(włącz = !wyłącz)
        }
        MOTYW.find(t)?.let { m ->
            val tryb = when {
                m.groupValues[3].startsWith("jasn") || m.groupValues[3].startsWith("dzien") -> "light"
                m.groupValues[3].startsWith("ciem") || m.groupValues[3].startsWith("noc") -> "dark"
                else -> "system"
            }
            return Polecenie.Motyw(tryb)
        }
        return null
    }

    fun potwierdzenie(p: Polecenie): String = when (p) {
        is Polecenie.BezKamery ->
            if (p.włącz) "Włączyłem tryb bez kamery. Nie robię zdjęć, a dwa kliknięcia czytają wiadomości."
            else "Wyłączyłem tryb bez kamery. Kamera znowu działa."
        is Polecenie.Motyw -> when (p.tryb) {
            "light" -> "Ustawiłem jasny motyw."
            "dark" -> "Ustawiłem ciemny motyw."
            else -> "Motyw będzie taki jak w telefonie."
        }
    }
}
