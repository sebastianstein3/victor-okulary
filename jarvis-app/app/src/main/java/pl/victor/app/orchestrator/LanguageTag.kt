package pl.victor.app.orchestrator

/**
 * Zamienia kod języka odpowiedzi na tag BCP-47 dla rozpoznawania mowy.
 * `SpeechRecognizer` oczekuje pełnego tagu z regionem - samo "pl" bywa
 * ignorowane i schodzi na język systemu.
 *
 * Wspólne dla orkiestratora i modułów, które słuchają (tłumaczenie ze słuchu).
 */
internal fun languageTagFor(languageCode: String): String = when (languageCode) {
    "pl" -> "pl-PL"
    "en" -> "en-US"
    "de" -> "de-DE"
    "fr" -> "fr-FR"
    "es" -> "es-ES"
    "it" -> "it-IT"
    "uk" -> "uk-UA"
    else -> languageCode
}
