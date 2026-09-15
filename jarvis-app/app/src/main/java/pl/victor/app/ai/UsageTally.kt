package pl.victor.app.ai

import java.time.Instant
import java.time.ZoneId

/**
 * Arytmetyka zużycia tokenów - bez Androida, bez zapisu, bez zegara.
 *
 * ## Po co to w ogóle istnieje
 * Bo `tokensUsed` wraca z KAŻDEGO dostawcy i z każdej odpowiedzi, po czym nikt
 * go nie sumuje. Ląduje w dzienniku diagnostycznym i we wpisie historii, czyli
 * tam, gdzie widać JEDNO zapytanie - a pytanie, które naprawdę zadaje
 * użytkownik konta przedpłaconego, brzmi "ile zeszło dzisiaj".
 *
 * Konto przedpłacone kończy się TWARDO: przy zerze asystent po prostu
 * przestaje odpowiadać. Licznik nie zapobiega temu sam z siebie, ale zamienia
 * niespodziankę w liczbę, którą widać wcześniej.
 *
 * ## Czemu osobno i czysto
 * Bo tu mieszkają dwa błędy, których nie widać z ekranu: zła granica doby
 * (licznik, który nie zeruje się o północy, po tygodniu pokazuje tydzień) i złe
 * okno przy liczeniu tempa (tempo liczone od uruchomienia aplikacji zaniża
 * bieżące zużycie tym bardziej, im dłużej aplikacja chodzi). Jedno i drugie
 * daje się sprawdzić testem tylko wtedy, gdy zegar jest parametrem.
 */
object UsageTally {

    /** Stan licznika dobowego - tyle wystarczy zapisać na dysku. */
    data class Day(
        /** Numer dnia (epoch day) w strefie użytkownika. */
        val epochDay: Long = 0L,
        val tokens: Long = 0L,
        val requests: Int = 0
    )

    /**
     * Dolicza zapytanie, zerując stan przy zmianie doby.
     *
     * Doba LOKALNA, nie UTC: użytkownik rozlicza się z własnym dniem, a przy
     * polskiej strefie granica w UTC wypadałaby o drugiej w nocy - czyli
     * licznik zerowałby się w środku wieczornego używania.
     */
    fun add(state: Day, tokens: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Day {
        val today = epochDayOf(nowMs, zone)
        val safe = tokens.coerceAtLeast(0)
        return if (state.epochDay == today) {
            state.copy(tokens = state.tokens + safe, requests = state.requests + 1)
        } else {
            Day(epochDay = today, tokens = safe.toLong(), requests = 1)
        }
    }

    /**
     * Stan widziany "teraz" - czyli wyzerowany, gdy zapisany dzień już minął.
     *
     * Bez tego licznik wczytany z dysku po północy pokazywałby wczorajszą sumę
     * aż do pierwszego zapytania.
     */
    fun asOf(state: Day, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Day =
        if (state.epochDay == epochDayOf(nowMs, zone)) state else Day(epochDay = epochDayOf(nowMs, zone))

    fun epochDayOf(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()

    /** Jedno zapytanie w oknie tempa. */
    data class Event(val atMs: Long, val tokens: Int)

    /**
     * Zdarzenia mieszczące się w oknie, licząc wstecz od `nowMs`.
     *
     * Wydzielone, bo to samo obcięcie służy dwóm rzeczom: liczeniu tempa i
     * pilnowaniu, żeby lista zdarzeń nie rosła bez końca.
     */
    fun within(events: List<Event>, nowMs: Long, windowMs: Long): List<Event> {
        val from = nowMs - windowMs
        return events.filter { it.atMs > from }
    }

    /**
     * Tempo zużycia w tokenach na minutę, liczone z OKNA, nie od początku.
     *
     * ## Czemu z okna
     * Bo pytanie brzmi "ile ten tryb pali TERAZ", a nie "ile średnio od
     * uruchomienia aplikacji". Tryb nawigacji włączony na dwie minuty po
     * godzinie bezczynności ma pokazać swoje tempo, a nie tę samą liczbę
     * podzieloną przez sześćdziesiąt.
     *
     * Dopóki okno nie jest pełne, dzielimy przez CZAS, KTÓRY NAPRAWDĘ MINĄŁ od
     * pierwszego zdarzenia. Dzielenie przez pełną minutę zaniżałoby tempo
     * dokładnie wtedy, gdy jest najbardziej potrzebne - w pierwszych
     * sekundach nowo włączonego trybu.
     *
     * @return tokeny na minutę albo `null`, gdy nie ma z czego liczyć
     */
    fun perMinute(events: List<Event>, nowMs: Long, windowMs: Long = WINDOW_MS): Int? {
        val recent = within(events, nowMs, windowMs)
        if (recent.size < MIN_EVENTS) return null
        val first = recent.minOf { it.atMs }
        val spanMs = (nowMs - first).coerceAtLeast(1L)
        val tokens = recent.sumOf { it.tokens.toLong() }
        return ((tokens * MINUTE_MS) / spanMs).toInt()
    }

    /** Okno tempa - pięć minut wygładza pojedynczą dłuższą odpowiedź. */
    const val WINDOW_MS = 5 * 60_000L

    /**
     * Poniżej dwóch zapytań tempo jest wymysłem.
     *
     * Z jednego zdarzenia wychodzi liczba tym większa, im krócej temu padło -
     * przy zapytaniu sprzed sekundy dałoby to sześćdziesięciokrotność jego
     * kosztu i wyglądałoby na alarm.
     */
    private const val MIN_EVENTS = 2
    private const val MINUTE_MS = 60_000L
}
