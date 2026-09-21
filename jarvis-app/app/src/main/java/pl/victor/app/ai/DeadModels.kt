package pl.victor.app.ai

/**
 * Modele, o których wiemy w TEJ sesji, że dostawca ich nie zna.
 *
 * ## Skąd to się wzięło
 * Z dziennika użytkownika: od 21:11 każda tura zaczynała się próbą na
 * `gemini-2.5-flash-lite`, dostawała 404 i schodziła na kolejnego dostawcę.
 * Dwadzieścia kilka razy pod rząd. Aplikacja miała w ręku odpowiedź serwera
 * mówiącą wprost "nie ma takiego modelu" - i wyrzucała ją po każdej turze,
 * żeby za chwilę zapytać o to samo.
 *
 * Koszt nie jest teoretyczny: to opóźnienie doliczone do KAŻDEJ odpowiedzi,
 * a użytkownik zgłaszał wprost, że "wszystko działa wolno".
 *
 * ## Dlaczego tylko na czas sesji, a nie w ustawieniach
 * Bo nie wiemy, czemu modelu nie ma. Może to literówka w nazwie, ale może i
 * klucz bez dostępu do tej rodziny modeli albo model dopiero co włączony w
 * konsoli Google. Trwały zapis kazałby człowiekowi go odklikiwać; restart
 * aplikacji i tak daje świeży start, a to wystarczy.
 *
 * ## Czego to NIE robi
 * Nie zmienia wybranego modelu ani ustawień. Dostawca z martwym modelem
 * przesuwa się na KONIEC kolejki prób, a nie znika - gdyby wszystkie okazały
 * się martwe, dalej trzeba czegoś spróbować.
 */
class DeadModels {

    private val martwe = mutableSetOf<String>()
    private val ogłoszone = mutableSetOf<String>()

    private fun klucz(providerId: String, modelId: String?) = "$providerId/${modelId ?: "?"}"

    /**
     * Zapisuje, że ten model nie istnieje u tego dostawcy.
     *
     * @return `true`, gdy to pierwszy raz w tej sesji - wołający może wtedy
     *   powiedzieć o tym człowiekowi, nie powtarzając się co turę
     */
    fun zapamiętaj(providerId: String, modelId: String?): Boolean {
        val k = klucz(providerId, modelId)
        martwe.add(k)
        return ogłoszone.add(k)
    }

    fun czyMartwy(providerId: String, modelId: String?): Boolean =
        klucz(providerId, modelId) in martwe

    /**
     * Przesuwa dostawców z martwym modelem na koniec kolejki prób.
     *
     * Kolejność wewnątrz obu grup zostaje bez zmian - to nadal jest kolejność
     * z ustawień, a nie nowe sortowanie.
     */
    fun przestaw(kolejka: List<String>, modelDla: (String) -> String?): List<String> {
        val (martwi, żywi) = kolejka.partition { czyMartwy(it, modelDla(it)) }
        return żywi + martwi
    }

    /** Tylko do testów i do zrzutu stanu w diagnostyce. */
    fun wszystkie(): Set<String> = martwe.toSet()
}
