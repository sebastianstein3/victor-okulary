package pl.victor.app.ble

/**
 * Rozstrzyga, czy strumień z mikrofonu okularów to WYBUDZENIE, czy szum.
 *
 * ## Skąd progi
 * Z pomiaru, nie z przypuszczenia. W dwóch dziennikach z terenu okna
 * trzysekundowe rozłożyły się tak:
 *
 *     1-20 pakietów   - 44 razy (szum między turami, po zakończonym nasłuchu)
 *     61 pakietów     - 1 raz
 *     125, 150, 150   - 3 razy, jedna seria, tuż po "hej lens"
 *
 * Między dwudziestoma a stu dwudziestoma pięcioma nie ma NICZEGO - to nie jest
 * jedno zjawisko w dwóch natężeniach, tylko dwie różne rzeczy.
 *
 * ## Czemu osobna klasa, a nie pole w VictorManagerze
 * Bo VictorManagera nie da się uruchomić bez Androida i okularów, a próg z
 * karencją to arytmetyka, którą trzeba umieć sprawdzić. Tak samo wcześniej
 * wyszedł hamulec trybów ciągłych.
 *
 * Klasa jest wołana z wątku BLE i tylko stamtąd - stąd brak synchronizacji.
 */
class MicWakeDetector(
    private val packetsNeeded: Int = WAKE_PACKETS,
    private val windowMs: Long = WAKE_WINDOW_MS,
    private val cooldownMs: Long = WAKE_COOLDOWN_MS
) {
    private var windowStartMs = 0L
    private var inWindow = 0
    private var lastWakeMs = 0L

    /**
     * Zgłasza jeden pakiet, którego nikt nie zamawiał.
     *
     * @return `true`, gdy właśnie uznaliśmy to za wybudzenie
     */
    fun onStrayPacket(nowMs: Long): Boolean {
        if (nowMs - windowStartMs > windowMs) {
            windowStartMs = nowMs
            inWindow = 0
        }
        inWindow++
        if (inWindow < packetsNeeded) return false
        if (lastWakeMs != 0L && nowMs - lastWakeMs < cooldownMs) return false
        lastWakeMs = nowMs
        inWindow = 0
        return true
    }

    /** Po starcie tury licznik nie ma czego pilnować. */
    fun reset() {
        windowStartMs = 0L
        inWindow = 0
    }

    companion object {
        /**
         * Próg leży w środku pustki między szumem a frazą, bliżej szumu:
         * fałszywe wybudzenie kosztuje jedno zapytanie do modelu, a przegapione
         * kosztuje całą funkcję.
         */
        const val WAKE_PACKETS = 50

        const val WAKE_WINDOW_MS = 2_000L

        /**
         * Jedna fraza to jedno wybudzenie.
         *
         * W dzienniku seria zajęła TRZY kolejne okna po trzy sekundy, więc bez
         * karencji jedno "hej lens" odpaliłoby trzy tury pod rząd.
         */
        const val WAKE_COOLDOWN_MS = 15_000L
    }
}
