package pl.victor.app.conversation

import java.util.concurrent.ConcurrentHashMap

/**
 * Które dane (kalendarz, poczta, pogoda, notatki) dokleić do pytania.
 *
 * ## Po co temat w ogóle zostaje otwarty
 * Po "jaka jest pogoda" pada "a jutro?" albo "to brać kurtkę?" - bez słowa
 * "pogoda". Bez pamięci tematu model dostawał wtedy pytanie BEZ danych i
 * odpowiadał, że nie zna pogody, minutę po tym, jak ją podał.
 *
 * ## Czemu z terminem
 * Wcześniej raz otwarty temat zostawał otwarty do końca życia procesu. Dziennik
 * z 29 września pokazuje skutek: od pierwszego pytania o pogodę KAŻDE pytanie
 * niosło prognozę, od "podsumuj maile" - osiem wiadomości, a od jednej tury
 * z nagraniem - także kalendarz. "Opowiedz mi kawał" szło z promptem ponad
 * 8000 znaków; pierwsze pytanie sesji miało 1243. Do tego zapytania sieciowe
 * o pocztę i pogodę przy każdej turze i maile wysyłane do modelu przy
 * pytaniach, które ich nie dotyczą.
 *
 * Teraz temat żyje [ttlMs] od OSTATNIEJ wzmianki - dość na pytania
 * dopytujące, za mało, żeby ciągnąć się przez cały dzień.
 *
 * ## Wymuszenie nie otwiera tematu
 * Briefing i tura, w której pytanie jest nagraniem (nie wiadomo, o czym jest),
 * dostają wszystko - ale jednorazowo. Wcześniej to właśnie one otwierały
 * wszystkie cztery tematy naraz, na stałe.
 */
class ContextTopics(
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lastMention = ConcurrentHashMap<String, Long>()

    /**
     * @param pytanieOTemat pytanie pasuje do wzorca tematu - odświeża termin
     * @param wymuszone dołóż dane bez względu na pytanie, ale nie otwieraj tematu
     * @return czy dokleić dane tego tematu do pytania
     */
    fun dokleić(temat: String, pytanieOTemat: Boolean, wymuszone: Boolean = false): Boolean {
        val teraz = clock()
        // WYMUSZONE NIE OTWIERA TEMATU - nawet gdy tekst pasuje do wzorca.
        //
        // Dziennik z biegu 154: tura z nagraniem dostaje wymuszone konteksty,
        // a jej tekst (polecenie dla modelu) wymienia "pogodę" i "temperatury".
        // Temat pogody otwierał się więc na pięć minut i następne pytanie -
        // "włącz muzykę na youtubie" - dostawało blok pogodowy.
        if (wymuszone) return true
        if (pytanieOTemat) {
            lastMention[temat] = teraz
            return true
        }
        val ostatnio = lastMention[temat] ?: return false
        if (teraz - ostatnio <= ttlMs) return true
        lastMention.remove(temat)
        return false
    }

    fun clear() = lastMention.clear()

    companion object {
        const val TTL_MS = 5 * 60_000L
    }
}
