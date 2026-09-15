package pl.victor.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zlicza tokeny zużyte przez modele - dzisiaj i w tej chwili.
 *
 * ## Po co
 * `tokensUsed` wraca z każdego dostawcy i z każdej odpowiedzi, po czym nic go
 * nie sumuje: trafia do wpisu historii i do dziennika, czyli tam, gdzie widać
 * JEDNO zapytanie. Użytkownik konta przedpłaconego pyta o co innego - "ile
 * zeszło dzisiaj" - i dotąd nie miał tego skąd wziąć inaczej niż z panelu
 * dostawcy.
 *
 * ## Czemu to nie jest blokada
 * Bo każdy próg, który mógłbym tu wpisać, byłby zgadnięty, a próg zgadnięty
 * za nisko wyłącza nawigację niewidomemu w połowie ulicy. Do ustawienia
 * sensownego limitu potrzebna jest ZMIERZONA liczba - i po to właśnie jest
 * [ratePerMinute] oraz wpis w dzienniku: żeby po jednym przebiegu było
 * wiadomo, ile ten tryb naprawdę pali na minutę.
 *
 * Arytmetyka (granica doby, okno tempa) siedzi w [UsageTally] i ma testy; tu
 * zostaje stan, zapis i wątki.
 */
class UsageMeter(context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _today = MutableStateFlow(load())
    /** Zużycie od początku dzisiejszej doby lokalnej. */
    val today: StateFlow<UsageTally.Day> = _today.asStateFlow()

    /**
     * Ostatnie zapytania - tylko do liczenia tempa, tylko w pamięci.
     *
     * Nie zapisujemy ich: tempo sprzed restartu aplikacji nie odpowiada na
     * żadne pytanie, a lista rosłaby przy każdym uruchomieniu.
     */
    private val recent = ArrayDeque<UsageTally.Event>()

    /** Dolicza jedno zapytanie. Wolno wołać z dowolnego wątku. */
    @Synchronized
    fun record(tokens: Int) {
        val now = clock()
        val next = UsageTally.add(_today.value, tokens, now)
        _today.value = next
        prefs.edit()
            .putLong(KEY_DAY, next.epochDay)
            .putLong(KEY_TOKENS, next.tokens)
            .putInt(KEY_REQUESTS, next.requests)
            .apply()
        recent.addLast(UsageTally.Event(now, tokens.coerceAtLeast(0)))
        // Obcinamy przy KAŻDYM dopisaniu, nie tylko przy odczycie: tryb ciągły
        // dopisuje tu kilkadziesiąt razy na minutę i przy godzinnym przebiegu
        // lista urosłaby do tysięcy wpisów, z których liczy się kilkanaście.
        val keep = UsageTally.within(recent.toList(), now, UsageTally.WINDOW_MS)
        if (keep.size != recent.size) {
            recent.clear()
            recent.addAll(keep)
        }
    }

    /** Tempo w tokenach na minutę albo `null`, gdy za mało danych - patrz [UsageTally.perMinute]. */
    @Synchronized
    fun ratePerMinute(): Int? = UsageTally.perMinute(recent.toList(), clock())

    /** Zeruje okno tempa - przy włączeniu trybu ciągłego, żeby mierzyć JEGO tempo. */
    @Synchronized
    fun resetRate() {
        recent.clear()
    }

    /** Stan na teraz, z uwzględnieniem zmiany doby od ostatniego zapisu. */
    fun todayAsOf(): UsageTally.Day = UsageTally.asOf(_today.value, clock())

    private fun load(): UsageTally.Day = UsageTally.asOf(
        UsageTally.Day(
            epochDay = prefs.getLong(KEY_DAY, 0L),
            tokens = prefs.getLong(KEY_TOKENS, 0L),
            requests = prefs.getInt(KEY_REQUESTS, 0)
        ),
        clock()
    )

    private companion object {
        const val PREFS = "victor_usage"
        const val KEY_DAY = "day"
        const val KEY_TOKENS = "tokens"
        const val KEY_REQUESTS = "requests"
    }
}
