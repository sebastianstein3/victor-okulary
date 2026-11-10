package pl.victor.app.ble

/**
 * Głośność okularów: trzy kanały (muzyka, rozmowa, dźwięki systemu) i kanał,
 * którym akurat sterują przyciski na zauszniku.
 *
 * ## Skąd układ
 * Z bajtkodu SDK: `setVolumeControl` składa ładunek
 * `02 01 min max cur 02 min max cur 03 min max cur kanał`, a ramka 0x12, którą
 * okulary same przysyłają, ma te same pozycje (znaczniki grup 1, 2, 3 i dziesięć
 * wartości). Zapis odsyła więc okularom dokładnie to, co same zgłosiły, ze
 * zmienioną WYŁĄCZNIE wartością bieżącą - zakresów nie ruszamy.
 */
data class GlassesVolume(
    val music: Channel,
    val call: Channel,
    val system: Channel,
    /** 1 muzyka, 2 rozmowa, 3 system - oddawane okularom bez zmian. */
    val buttonChannel: Int
) {
    data class Channel(val min: Int, val max: Int, val current: Int) {
        val valid: Boolean get() = max > min && min >= 0 && max <= MAX_SANE_LEVEL

        fun percent(): Int =
            if (!valid) 0 else ((current - min) * 100f / (max - min)).toInt().coerceIn(0, 100)

        /** Poziom odpowiadający procentowi - nigdy poza zakresem okularów. */
        fun atPercent(percent: Int): Channel {
            if (!valid) return this
            val level = min + Math.round((max - min) * percent.coerceIn(0, 100) / 100f)
            return copy(current = level.coerceIn(min, max))
        }

        /**
         * O [steps] "klików" w górę albo w dół; klik to [STEP_FRACTION] zakresu.
         * W dół nigdy do zera: "ciszej" ma ściszyć, a nie wyciszyć asystenta -
         * z niemych okularów nie da się już poprosić głosem o powrót.
         */
        fun stepped(steps: Int): Channel {
            if (!valid) return this
            val step = maxOf(1, Math.round((max - min) * STEP_FRACTION))
            val floor = if (steps < 0) min + 1 else min
            return copy(current = (current + steps * step).coerceIn(floor, max))
        }
    }

    /** Głośność mowy asystenta w procentach - kanał muzyki, bo nim idzie A2DP. */
    fun percent(): Int = music.percent()

    /** Ustawia mowę (muzyka i rozmowa) na procent; dźwięki systemu zostają. */
    fun withSpeechPercent(percent: Int): GlassesVolume =
        copy(music = music.atPercent(percent), call = call.atPercent(percent))

    fun withSpeechStepped(steps: Int): GlassesVolume =
        copy(music = music.stepped(steps), call = call.stepped(steps))

    /** Dziesięć liczb w kolejności argumentów `setVolumeControl`. */
    fun toCommand(): List<Int> = listOf(
        music.min, music.max, music.current,
        call.min, call.max, call.current,
        system.min, system.max, system.current,
        buttonChannel
    )

    /** Czy tym stanem wolno pisać do okularów - patrz [fromValues]. */
    val writable: Boolean get() = music.valid && call.valid && system.valid

    companion object {
        /** Powyżej tego "maksimum" to nie głośność, tylko źle odczytana ramka. */
        const val MAX_SANE_LEVEL = 100

        /** Jeden klik "głośniej"/"ciszej" - jedna piąta zakresu. */
        const val STEP_FRACTION = 0.2f

        /**
         * Z dziesięciu wartości ramki 0x12 albo odpowiedzi `getVolumeControl`.
         *
         * @return `null`, gdy liczb jest za mało - wtedy nie mamy czego odesłać
         */
        fun fromValues(values: List<Int>): GlassesVolume? {
            if (values.size < 10) return null
            return GlassesVolume(
                music = Channel(values[0], values[1], values[2]),
                call = Channel(values[3], values[4], values[5]),
                system = Channel(values[6], values[7], values[8]),
                buttonChannel = values[9]
            )
        }
    }
}
