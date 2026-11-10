package pl.victor.app.conversation

/**
 * "Głośniej", "ciszej", "głośność na 70 procent" - rozpoznawane lokalnie.
 *
 * Model zapytany o czynność odpowiada na nią słowami ("oczywiście, już
 * podgłaśniam"), a głośności nie ruszy. Dopasowanie obejmuje CAŁĄ wypowiedź:
 * "mów głośniej o tym zamku" albo "ścisz muzykę" to nie są te komendy.
 */
sealed class VolumeCommand {
    object Up : VolumeCommand()
    object Down : VolumeCommand()
    data class SetPercent(val percent: Int) : VolumeCommand()

    companion object {
        private const val LEAD = """(?:prosz[eę] |a |i |no |hej |teraz )?(?:zr[oó]b |daj |m[oó]w |ustaw |graj )?"""
        private const val DEGREE = """(?:troch[eę] |troszk[eę] |odrobin[eę] |jeszcze |nieco )*"""
        private const val TAIL = """(?: troch[eę]| troszk[eę]| jeszcze| prosz[eę]| okulary| w okularach| g[lł]o[sś]no[sś][cć])*"""

        private val UP = Regex(
            "^$LEAD$DEGREE(?:g[lł]o[sś]niej|podg[lł]o[sś]nij|podg[lł]o[sś]|pog[lł]o[sś]nij|zwi[eę]ksz g[lł]o[sś]no[sś][cć])$TAIL$"
        )
        private val DOWN = Regex(
            "^$LEAD$DEGREE(?:ciszej|[sś]cisz|przycisz|zmniejsz g[lł]o[sś]no[sś][cć])$TAIL$"
        )
        private val SET = Regex(
            """^(?:prosz[eę] )?(?:ustaw |zmie[nń] |daj )?g[lł]o[sś]no[sś][cć](?: okular[oó]w| w okularach)? (?:na |do )?(\d{1,3}) ?(?:procent|proc|%)?$"""
        )
        private val SET_WORD = Regex(
            """^(?:prosz[eę] )?(?:ustaw |zmie[nń] |daj )?g[lł]o[sś]no[sś][cć](?: okular[oó]w| w okularach)? (?:na |do )?(maksimum|maksa|maks|max|pe[lł]n[aą]|po[lł]ow[eę]|p[oó][lł]|minimum)$"""
        )
        private val MAX_PHRASE = Regex(
            """^(?:prosz[eę] )?(?:ustaw |daj |zr[oó]b )?(?:maksymaln[aą]|pe[lł]n[aą]) g[lł]o[sś]no[sś][cć]$"""
        )

        /** Najcichsze, na co pozwala komenda z liczbą - patrz [parse]. */
        const val MIN_PERCENT = 10

        /**
         * @return komenda albo `null`, gdy to zwykłe zdanie
         */
        fun parse(text: String): VolumeCommand? {
            val t = text.lowercase().trim().trimEnd('.', '!', '?').trim()
            if (t.isEmpty()) return null
            if (UP.matches(t)) return Up
            if (DOWN.matches(t)) return Down
            if (MAX_PHRASE.matches(t)) return SetPercent(100)
            SET.matchEntire(t)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return null
                if (n > 100) return null
                // "Głośność na zero" wyciszyłaby asystenta, którego potem nie
                // słychać, gdy potwierdza następną komendę - dolna granica.
                return SetPercent(n.coerceAtLeast(MIN_PERCENT))
            }
            SET_WORD.matchEntire(t)?.let { m ->
                return when (m.groupValues[1].take(3)) {
                    "mak", "max", "peł", "pel" -> SetPercent(100)
                    "min" -> SetPercent(MIN_PERCENT)
                    else -> SetPercent(50)
                }
            }
            return null
        }
    }
}
