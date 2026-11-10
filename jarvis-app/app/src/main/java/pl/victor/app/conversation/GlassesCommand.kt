package pl.victor.app.conversation

/**
 * Komendy dotyczące samych okularów: film, bateria, liczba plików.
 *
 * Rozpoznawane lokalnie z tego samego powodu co głośność i tryby: model
 * zapytany o czynność odpowiada na nią słowami, a stanu baterii okularów nie
 * zna wcale. Dopasowanie obejmuje CAŁĄ wypowiedź - "nagraj film" to komenda,
 * "jaki film nagrał Wajda" już nie.
 */
sealed class GlassesCommand {
    object VideoStart : GlassesCommand()
    object VideoStop : GlassesCommand()
    object Battery : GlassesCommand()
    object MediaCount : GlassesCommand()

    companion object {
        private const val LEAD = """(?:prosz[eę] |a |i |no |hej |teraz )?"""
        private const val VIDEO = """(?:film\w*|wideo|video)"""

        private val VIDEO_START = Regex(
            "^$LEAD(?:nagraj|nagrywaj|zacznij nagrywa[cć]|rozpocznij nagrywanie|w[lł][aą]cz nagrywanie|" +
                "kr[eę][cć]|nakr[eę][cć])(?: mi| kr[oó]tki| kr[oó]tkie)? $VIDEO" +
                "(?: okularami| z okular[oó]w| w okularach)?$"
        )

        private val VIDEO_STOP = Regex(
            "^$LEAD(?:zatrzymaj|zako[nń]cz|sko[nń]cz|przerwij|wy[lł][aą]cz|stop|koniec)" +
                "(?: nagrywani[ae]| nagrywania)?(?: $VIDEO| filmu)?$"
        )

        /** Samo "stop" albo "koniec" to nie jest komenda filmu - musi paść, CO kończymy. */
        private val VIDEO_STOP_NEEDS = Regex("""nagrywani|film|wideo|video""")

        private val BATTERY = Regex("""bateri|na[lł]adowan""")

        /** Pytanie o baterię czegoś innego niż okulary idzie do modelu. */
        private val OTHER_DEVICE = Regex(
            """telefon|kom[oó]r|laptop|komputer|zegar|s[lł]uchaw|samoch|aut[aoe]\b|rower|hulajn|tablet|powerbank"""
        )

        private val MEDIA_COUNT = Regex(
            "^$LEAD(?:ile|ilu) (?:zdj[eę][cć]|film[oó]w|plik[oó]w|nagra[nń])" +
                """(?: i (?:zdj[eę][cć]|film[oó]w|nagra[nń]))?(?: jest| mam| masz| maj[aą]| siedzi| le[zż]y)?""" +
                """(?: zapisanych)? (?:w|na) (?:okularach|pami[eę]ci okular[oó]w)$"""
        )

        /** Dłuższe zdanie o baterii to rozmowa, nie pytanie o stan. */
        private const val BATTERY_MAX_WORDS = 7

        /**
         * @return komenda albo `null`, gdy to zwykłe zdanie
         */
        fun parse(text: String): GlassesCommand? {
            val t = text.lowercase().trim().trimEnd('.', '!', '?').trim()
            if (t.isEmpty()) return null
            if (VIDEO_START.matches(t)) return VideoStart
            if (VIDEO_STOP.matches(t) && VIDEO_STOP_NEEDS.containsMatchIn(t)) return VideoStop
            if (MEDIA_COUNT.matches(t)) return MediaCount
            // "Naładowany" bez okularów to nie pytanie o baterię ("jestem
            // naładowany energią").
            val aboutBattery = t.contains("bateri") || (BATTERY.containsMatchIn(t) && t.contains("okular"))
            if (aboutBattery && !OTHER_DEVICE.containsMatchIn(t) &&
                t.split(Regex("\\s+")).size <= BATTERY_MAX_WORDS
            ) {
                return Battery
            }
            return null
        }
    }
}

/**
 * Kiedy powiedzieć o słabej baterii okularów.
 *
 * Dwa progi, każdy raz na rozładowanie: komunikat powtarzany przy każdym
 * odczycie uczyłby go ignorować. Ładowanie albo powrót powyżej progu z
 * zapasem zeruje pamięć.
 */
class LowBatteryWatch {
    private var announcedAt = Int.MAX_VALUE

    /**
     * @return próg do ogłoszenia (20 albo 10) lub `null`, gdy nic nie mówimy
     */
    @Synchronized
    fun check(level: Int?, charging: Boolean): Int? {
        if (level == null) return null
        if (charging || level > RESET_ABOVE) {
            announcedAt = Int.MAX_VALUE
            return null
        }
        val threshold = THRESHOLDS.firstOrNull { level <= it } ?: return null
        if (threshold >= announcedAt) return null
        return threshold
    }

    /** Wołane PO faktycznym wypowiedzeniu - pominięty komunikat ma wrócić. */
    @Synchronized
    fun announced(threshold: Int) {
        announcedAt = minOf(announcedAt, threshold)
    }

    companion object {
        /** Od najniższego: przy 8% mówimy "10", nie "20". */
        private val THRESHOLDS = listOf(10, 20)
        const val RESET_ABOVE = 25
    }
}
