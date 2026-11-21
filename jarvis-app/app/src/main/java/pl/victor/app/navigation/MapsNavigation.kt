package pl.victor.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Prowadzenie trasą przez asystenta - na podstawie powiadomienia Google Maps.
 *
 * ## Po co
 * Nawigację krok po kroku robi aplikacja Map (trasy jakości Google, za darmo).
 * Podczas prowadzenia Mapy trzymają stałe powiadomienie z NASTĘPNYM manewrem:
 * odległość, "Skręć w prawo w ul. Długą" i ile zostało do celu. Aplikacja ma
 * już dostęp do powiadomień (czyta wiadomości), więc może to powiadomienie
 * odczytać i:
 * - mówić o skrętach SWOIM głosem w okularach,
 * - odpowiadać na "ile jeszcze?", "gdzie skręcam?", "powtórz",
 * - dawać modelowi stan trasy, gdy pytanie jest inne ("co jest po drodze?").
 *
 * ## Ryzyko, które trzeba znać
 * Google może zmienić układ powiadomienia. Dlatego parser nie zakłada, w
 * którym polu jest odległość, a w którym manewr - szuka odległości w każdym,
 * a resztę traktuje jako opis. Gdy nic nie pasuje, po prostu milczy.
 */
object MapsNavigation {

    const val PAKIET_MAP = "com.google.android.apps.maps"

    /** Stan prowadzenia - `null`, gdy Mapy nie prowadzą. */
    data class Stan(
        /** Opis manewru bez odległości, np. "Skręć w prawo w ul. Długą". */
        val manewr: String,
        /** Odległość do manewru w metrach albo `null`, gdy jej nie podano. */
        val dystansM: Int?,
        /** Ile zostało do celu, jak pokazały Mapy ("12 min · 900 m · 12:45"). */
        val doCelu: String?,
        val czasMs: Long
    )

    private val _stan = MutableStateFlow<Stan?>(null)
    val stan: StateFlow<Stan?> = _stan.asStateFlow()

    /** Klucz powiadomienia Map - żeby po jego zniknięciu zakończyć prowadzenie. */
    @Volatile
    private var klucz: String? = null

    fun przyjmij(kluczPowiadomienia: String, nowy: Stan) {
        klucz = kluczPowiadomienia
        _stan.value = nowy
    }

    fun usunięte(kluczPowiadomienia: String) {
        if (kluczPowiadomienia == klucz) koniec()
    }

    fun koniec() {
        klucz = null
        _stan.value = null
    }

    /** Czy Mapy prowadzą teraz (i dały znać o sobie niedawno). */
    fun aktywna(terazMs: Long = System.currentTimeMillis()): Stan? =
        _stan.value?.takeIf { terazMs - it.czasMs < WAŻNOŚĆ_MS }

    /** Bez aktualizacji przez tyle uznajemy, że Mapy przestały prowadzić. */
    const val WAŻNOŚĆ_MS = 10 * 60_000L

    /** Kontekst dla modelu, gdy trwa prowadzenie. */
    fun kontekstDlaModelu(): String? {
        val s = aktywna() ?: return null
        return buildString {
            append("=== NAWIGACJA (Google Maps prowadzi użytkownika) ===\n")
            append("Następny manewr: ${NavMowa.zapowiedź(s)}\n")
            s.doCelu?.let { append("Do celu: ${NavMowa.doCelu(it)}\n") }
            append("Skręty mówi aplikacja sama - nie powtarzaj ich bez pytania. ")
            append("Pytania o okolicę trasy odnoś do tego miejsca.")
        }
    }
}

/** Rozbiór powiadomienia Map - czysta logika, bez Androida. */
object MapsNotificationParser {

    private val SAMA_ODLEGŁOŚĆ =
        Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft|yd)\.?\s*$""", RegexOption.IGNORE_CASE)

    /** "300 m · Skręć w prawo" albo "Za 300 m skręć w prawo". */
    private val ODLEGŁOŚĆ_NA_POCZĄTKU = Regex(
        """^\s*(?:za|in)?\s*(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft|yd)\b\.?\s*[·\-–,:]?\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * @return stan albo `null`, gdy w polach nie ma niczego, co wygląda na
     *   manewr (np. "Mapy Google działają w tle")
     */
    fun rozbierz(tytuł: String?, tekst: String?, podtekst: String?, terazMs: Long): MapsNavigation.Stan? {
        var dystans: Int? = null
        val opis = mutableListOf<String>()
        for (pole in listOf(tytuł, tekst)) {
            val p = pole?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val sama = SAMA_ODLEGŁOŚĆ.matchEntire(p)
            if (sama != null) {
                if (dystans == null) dystans = metry(sama.groupValues[1], sama.groupValues[2])
                continue
            }
            val początek = ODLEGŁOŚĆ_NA_POCZĄTKU.matchEntire(p)
            if (początek != null && dystans == null) {
                dystans = metry(początek.groupValues[1], początek.groupValues[2])
                opis += początek.groupValues[3].trim()
            } else {
                opis += p
            }
        }
        val manewr = opis.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
        if (manewr.isEmpty() || TŁO.containsMatchIn(manewr.lowercase())) return null
        return MapsNavigation.Stan(
            manewr = manewr.replaceFirstChar { it.uppercase() },
            dystansM = dystans,
            doCelu = podtekst?.trim()?.takeIf { it.isNotEmpty() },
            czasMs = terazMs
        )
    }

    /** Powiadomienia Map, które NIE są prowadzeniem. */
    private val TŁO = Regex("""działa w tle|running in the background|udostępnianie lokalizacji|location sharing""")

    fun metry(liczba: String, jednostka: String): Int? {
        val v = liczba.replace(',', '.').toDoubleOrNull() ?: return null
        val m = when (jednostka.lowercase()) {
            "km" -> v * 1000
            "mi" -> v * 1609.34
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            else -> v
        }
        return m.toInt()
    }
}

/** Co powiedzieć i kiedy - czysta logika, czas podaje wołający. */
class NavZapowiedzi {
    private var ostatniManewr: String? = null
    private var terazPowiedziane = false

    /**
     * Zdanie do powiedzenia po aktualizacji powiadomienia albo `null`.
     *
     * Mówimy przy NOWYM manewrze i raz tuż przed nim ([BLISKO_M]). Mapy
     * odświeżają odległość co kilka sekund ("300 m", "250 m"...) - to nie
     * jest powód, żeby gadać.
     */
    fun decyzja(stan: MapsNavigation.Stan): String? {
        val klucz = kluczManewru(stan.manewr)
        if (klucz != ostatniManewr) {
            ostatniManewr = klucz
            val blisko = stan.dystansM != null && stan.dystansM <= BLISKO_M
            terazPowiedziane = blisko
            return if (blisko) NavMowa.teraz(stan) else NavMowa.zapowiedź(stan)
        }
        if (!terazPowiedziane && stan.dystansM != null && stan.dystansM <= BLISKO_M) {
            terazPowiedziane = true
            return NavMowa.teraz(stan)
        }
        return null
    }

    fun wyzeruj() {
        ostatniManewr = null
        terazPowiedziane = false
    }

    companion object {
        /** Od tej odległości mówimy "teraz" - dla pieszego to kilkanaście kroków. */
        const val BLISKO_M = 40

        /** "za 5 min", "o 12:40", "3 min" - w kluczu manewru bez liczb. */
        private val ZEGAR = Regex("""(za\s+)?\d+\s*min\S*|\d{1,2}:\d{2}""")

        /**
         * Klucz "tego samego" manewru.
         *
         * Przy komunikacji miejskiej Mapy odliczają w powiadomieniu minuty do
         * odjazdu ("Autobus 175 za 5 min" -> "za 4 min"). Bez tego asystent
         * mówiłby co minutę, stojąc na przystanku. Przystanki do wysiadki
         * ("Wysiądź za 2 przystanki" -> "za 1 przystanek") zostają w kluczu -
         * to jest dokładnie to, co warto usłyszeć.
         */
        fun kluczManewru(manewr: String): String =
            manewr.lowercase().replace(ZEGAR, "#").replace(Regex("""\s+"""), " ").trim()
    }
}

/** Zdania po polsku - odległości słowami, żeby syntezator ich nie przekręcał. */
object NavMowa {

    fun zapowiedź(s: MapsNavigation.Stan): String {
        val d = s.dystansM ?: return kropka(s.manewr)
        return "Za ${odległość(d)} ${małą(s.manewr)}".let(::kropka)
    }

    fun teraz(s: MapsNavigation.Stan): String = kropka("Teraz ${małą(s.manewr)}")

    /** "12 min · 900 m · 12:45" -> "12 min, 900 m, przyjazd o 12:45". */
    fun doCelu(surowe: String): String {
        val części = surowe.split('·', '•', '|').map { it.trim() }.filter { it.isNotEmpty() }
        return części.joinToString(", ") { c ->
            if (Regex("""^\d{1,2}:\d{2}$""").matches(c)) "przyjazd o $c" else c
        }
    }

    fun odległość(m: Int): String = when {
        m >= 1000 -> {
            val km = Math.round(m / 100.0) / 10.0
            val tekst = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            if (km % 1.0 == 0.0) "$tekst ${odmiana(km.toInt(), "kilometr", "kilometry", "kilometrów")}"
            else "$tekst kilometra"
        }
        else -> {
            val r = if (m >= 100) (m + 5) / 10 * 10 else m
            "$r ${odmiana(r, "metr", "metry", "metrów")}"
        }
    }

    fun odmiana(n: Int, jeden: String, kilka: String, wiele: String): String {
        if (n == 1) return jeden
        val d = n % 10
        val s = n % 100
        return if (d in 2..4 && s !in 12..14) kilka else wiele
    }

    private fun małą(t: String) = t.replaceFirstChar { it.lowercase() }
    private fun kropka(t: String) = t.trimEnd().let { if (it.endsWith('.') || it.endsWith('!')) it else "$it." }
}

/** Krótkie pytania o trasę - odpowiadane od razu, bez modelu. */
object NavPytania {

    private val ILE = Regex("""\b(ile (jeszcze|zostało|zostalo|mi zostało)|daleko (jeszcze|do celu)|kiedy (będę|dojdę|dojadę|dotrę)|o której (będę|dojdę|dojadę|dotrę))""")
    private val GDZIE = Regex("""\b(gdzie (teraz )?(skręcam|skrecam|mam skręcić|skręcić)|co dalej|następny (skręt|manewr)|jaki (jest )?następny|powtórz (wskazówkę|kierunek|to)?|którędy)""")

    /**
     * Komunikacja miejska: "ile jeszcze przystanków?", "kiedy wysiadam?" - w
     * powiadomieniu Map stoi wtedy "Wysiądź za 2 przystanki", a nie odległość.
     * Sprawdzane PRZED [ILE], bo "ile jeszcze" złapałoby to jako pytanie o czas.
     */
    private val WYSIADKA = Regex(
        """(ile\s+(jeszcze\s+|mi\s+)?(zosta[lł]o\s+)?przystank|(kiedy|gdzie)\s+(mam\s+)?(si[eę]\s+)?(wysi|przesi)|""" +
            """wysiadam|przesiadam|na\s+kt[oó]rym\s+przystanku)"""
    )

    fun odpowiedź(pytanie: String, stan: MapsNavigation.Stan?): String? {
        stan ?: return null
        val t = pytanie.lowercase().trim()
        if (WYSIADKA.containsMatchIn(t)) return NavMowa.zapowiedź(stan)
        if (ILE.containsMatchIn(t)) {
            return stan.doCelu?.let { "Do celu: ${NavMowa.doCelu(it)}." }
                ?: "Mapy nie podają teraz, ile zostało. ${NavMowa.zapowiedź(stan)}"
        }
        if (GDZIE.containsMatchIn(t)) return NavMowa.zapowiedź(stan)
        return null
    }
}
