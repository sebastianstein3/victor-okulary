package pl.victor.app.conversation

/**
 * Co mikrofon FAKTYCZNIE dostarczył przez jeden nasłuch.
 *
 * ## Po co to istnieje
 * Bo bez tego "nasłuch trwał 10 sekund i nie dał tekstu" ma dwie zupełnie
 * różne przyczyny, a w dzienniku wyglądają identycznie: albo człowiek nic nie
 * powiedział, albo mikrofon przez cały ten czas podawał ciszę. Pierwsze nie
 * jest usterką. Drugie jest, i to poważną.
 *
 * Dokładnie ta niejednoznaczność kosztowała już ten projekt kilka dni przy
 * strumieniu z okularów - i rozstrzygnęła się dopiero, gdy do dziennika trafił
 * szczyt amplitudy: `szczyt=2` przy złym przesunięciu kontra `szczyt=1443`
 * przy dobrym. Tu jest ten sam pomiar, tylko po stronie mikrofonu telefonu.
 *
 * Systemowe rozpoznawanie mowy podaje te liczby samo, przez [android.speech.RecognitionListener].
 * U nas cztery z tych wywołań były pustymi zaślepkami, więc sygnał, który
 * przechodził przez aplikację, nigdzie nie był zapisywany.
 */
class MicSignal {

    /** Ile minęło od prośby o nasłuch do chwili, gdy silnik miał mikrofon. */
    @Volatile
    var readyMs: Long? = null
        private set

    /** Czy silnik w ogóle uznał, że ktoś zaczął mówić. */
    @Volatile
    var speechDetected: Boolean = false
        private set

    /** Najgłośniejsza chwila nasłuchu w decybelach, albo `null` gdy nie było próbek. */
    @Volatile
    var peakDb: Float? = null
        private set

    /** Ile razy silnik podał poziom sygnału. Zero znaczy, że mikrofon nie ruszył. */
    @Volatile
    var samples: Int = 0
        private set

    private val startedAtMs = System.currentTimeMillis()

    /** Silnik zgłosił gotowość - od tej chwili mikrofon naprawdę nagrywa. */
    fun noteReady() {
        if (readyMs == null) readyMs = System.currentTimeMillis() - startedAtMs
    }

    /** Silnik wykrył początek mowy. */
    fun noteSpeechStart() {
        speechDetected = true
    }

    /** Kolejny odczyt poziomu sygnału. */
    fun noteLevel(rmsDb: Float) {
        // NaN i nieskończoności zdarzają się na niektórych silnikach i
        // zatruwałyby szczyt na zawsze - porównanie z NaN jest zawsze fałszywe,
        // więc bez tego odsiewu `peakDb` potrafiłby utknąć na NaN.
        if (rmsDb.isNaN() || rmsDb.isInfinite()) return
        samples++
        val best = peakDb
        if (best == null || rmsDb > best) peakDb = rmsDb
    }

    /** Jednozdaniowa odpowiedź na pytanie "czy mikrofon coś przyniósł". */
    fun verdict(): String = describe(samples, peakDb, speechDetected)

    companion object {

        /**
         * Powyżej tylu decybeli uznajemy, że coś do mikrofonu doszło.
         *
         * Skala [android.speech.RecognitionListener.onRmsChanged] nie jest
         * znormalizowana między silnikami; dokumentacja podaje mniej więcej
         * -2 dB dla ciszy i 10 dB dla mowy. Bierzemy próg blisko dolnego końca,
         * bo rozstrzygamy tu "cokolwiek kontra nic", a nie głośność.
         */
        const val SILENCE_DB = 0f

        /**
         * Opisuje, co mikrofon przyniósł - bez sięgania do stanu, więc daje się
         * sprawdzić testem.
         */
        fun describe(samples: Int, peakDb: Float?, speechDetected: Boolean): String = when {
            samples == 0 ->
                "MIKROFON NIE RUSZYŁ - ani jednej próbki"
            speechDetected ->
                "mowa wykryta"
            peakDb == null || peakDb <= SILENCE_DB ->
                "CISZA NA MIKROFONIE - sygnał nie przekroczył progu"
            else ->
                "sygnał był, ale silnik nie uznał go za mowę"
        }
    }
}
