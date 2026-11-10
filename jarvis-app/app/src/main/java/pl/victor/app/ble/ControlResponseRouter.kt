package pl.victor.app.ble

/**
 * Rozdaje odpowiedzi okularów na komendy sterujące temu, kto na nie czeka.
 *
 * ## Po co
 * SDK ma na odpowiedzi `glassesControl` JEDEN slot: każde wywołanie nadpisuje
 * callback poprzedniego. Komenda czekająca na odpowiedź (tryb transferu,
 * podgląd na żywo, liczba plików) traciła ją więc, gdy w tym samym czasie
 * poszła dowolna inna komenda - a cudza odpowiedź trafiała do niej jako jej
 * własna. Stąd m.in. "okulary nie odpowiedziały na komendę" przy okularach,
 * które odpowiedziały (audyt 28.10).
 *
 * Teraz callback w SDK jest jeden i stały, a o adresacie decyduje ten router.
 *
 * ## Po czym poznajemy adresata
 * Odpowiedź niesie typ pracy komendy, na którą odpowiada (bajt 8 ramki; w
 * bajtkodzie SDK to pole `glassWorkType`). Czy KAŻDY firmware go odsyła, nie
 * wiemy - dlatego dopasowanie po typie ma pierwszeństwo, ale gdy typ nic nie
 * mówi, odpowiedź dostaje najdłużej czekający, czyli tak jak przed zmianą.
 */
class ControlResponseRouter<R>(
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Waiter<R>(val workType: Int?, val atMs: Long, val deliver: (R) -> Unit)

    private val waiters = ArrayList<Waiter<R>>()

    /** Typy komend wysłanych bez czekania na odpowiedź -> kiedy poszły. */
    private val recentSends = HashMap<Int, Long>()

    /**
     * Zapisuje oczekującego na odpowiedź komendy o typie [workType].
     * @return uchwyt do [cancel], gdy czekający się podda
     */
    @Synchronized
    fun expect(workType: Int?, deliver: (R) -> Unit): Any {
        purge()
        return Waiter(workType, now(), deliver).also { waiters.add(it) }
    }

    @Synchronized
    fun cancel(handle: Any) {
        waiters.remove(handle)
    }

    /** Poszła komenda, na której odpowiedź nikt nie czeka. */
    @Synchronized
    fun noteSent(workType: Int?) {
        if (workType != null) recentSends[workType] = now()
    }

    /**
     * Oddaje odpowiedź właściwemu czekającemu.
     *
     * @param workType typ pracy z odpowiedzi albo `null`, gdy jej nie niesie
     * @return `true`, gdy ktoś ją dostał
     */
    fun route(workType: Int?, response: R): Boolean {
        val target = synchronized(this) { pick(workType) } ?: return false
        // Poza zamkiem: odbiorca może od razu wysłać następną komendę.
        target.deliver(response)
        return true
    }

    private fun pick(workType: Int?): Waiter<R>? {
        purge()
        if (workType != null) {
            waiters.firstOrNull { it.workType == workType }?.let {
                waiters.remove(it)
                return it
            }
            // Odpowiedź na komendę, na którą nikt nie czekał - nie wolno jej
            // podać komuś, kto czeka na co innego.
            val sentAt = recentSends[workType]
            if (sentAt != null && now() - sentAt <= RECENT_SEND_MS) {
                recentSends.remove(workType)
                return null
            }
        }
        // Typ nic nie rozstrzygnął: zachowanie sprzed zmiany, tylko bez
        // gubienia - dostaje ten, kto czeka najdłużej.
        return waiters.firstOrNull()?.also { waiters.remove(it) }
    }

    private fun purge() {
        val limit = now() - WAITER_TTL_MS
        waiters.removeAll { it.atMs < limit }
        recentSends.values.removeAll { it < now() - RECENT_SEND_MS }
    }

    @Synchronized
    fun pending(): Int = waiters.size

    companion object {
        /** Czekający, o którym zapomniano, nie może łapać odpowiedzi w nieskończoność. */
        const val WAITER_TTL_MS = 30_000L

        /** Tyle po wysłaniu komendy jej odpowiedź uznajemy za "jej". */
        const val RECENT_SEND_MS = 5_000L
    }
}
