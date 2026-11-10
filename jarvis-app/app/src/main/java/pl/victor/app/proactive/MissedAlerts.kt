package pl.victor.app.proactive

/**
 * Alerty, których nie dało się wypowiedzieć, bo okulary były rozłączone.
 *
 * ## Po co
 * W dziennikach sprzętu wiersz "alert pominięty - okulary niepołączone" pada
 * 97 razy i żaden z tych alertów nie wrócił: powiadomienie zostawało w
 * telefonie, a człowiek w okularach nie dowiadywał się niczego. Okulary
 * wracają zwykle po kilkudziesięciu sekundach - wtedy alert jest wciąż
 * aktualny i wystarczy go powiedzieć.
 *
 * Trzymane w pamięci procesu, celowo: alert sprzed restartu aplikacji jest
 * już nieaktualny, a powiadomienie i tak leży w telefonie.
 */
class MissedAlerts(
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Entry(val text: String, val atMs: Long)

    private val entries = ArrayList<Entry>()

    /** Odkłada alert do powiedzenia po powrocie okularów. */
    @Synchronized
    fun remember(text: String) {
        if (text.isBlank()) return
        // Ten sam alert potrafi przyjść kilka razy pod rząd (kolejne przebiegi
        // workera) - liczy się najnowszy.
        entries.removeAll { it.text == text }
        entries.add(Entry(text, now()))
        while (entries.size > MAX_KEPT) entries.removeAt(0)
    }

    /**
     * Oddaje wciąż aktualne alerty i czyści pamięć.
     *
     * @return teksty od najstarszego; pusta lista, gdy nie ma czego mówić
     */
    @Synchronized
    fun takeFresh(): List<String> {
        val limit = now() - MAX_AGE_MS
        val fresh = entries.filter { it.atMs >= limit }.map { it.text }
        entries.clear()
        return fresh
    }

    companion object {
        /** Starszy alert (deszcz za kwadrans, spotkanie za pół godziny) już nie pomaga. */
        const val MAX_AGE_MS = 30 * 60 * 1000L

        /** Więcej naraz to już wykład, a nie alert. */
        const val MAX_KEPT = 2
    }
}
