package pl.victor.app.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Które funkcje potrzebują usługi pierwszoplanowej, żeby działać przy
 * zgaszonym ekranie: nagrywanie spotkania (mikrofon), przewodnik i
 * przypomnienia w miejscach (lokalizacja w tle).
 *
 * Usługa żyła dotąd tylko przy połączonych okularach albo włączonej frazie -
 * spotkanie bez okularów nagrywało więc ciszę po zgaszeniu ekranu, a przewodnik
 * i przypomnienia nie dostawały lokalizacji w tle (audyt 28.10).
 */
object BackgroundFeatures {
    private val aktywne = ConcurrentHashMap<String, Boolean>()
    private val _potrzebne = MutableStateFlow(false)

    /** `true`, gdy choć jedna funkcja potrzebuje usługi w tle. */
    val potrzebne: StateFlow<Boolean> = _potrzebne.asStateFlow()

    fun ustaw(klucz: String, włączona: Boolean) {
        if (włączona) aktywne[klucz] = true else aktywne.remove(klucz)
        _potrzebne.value = aktywne.isNotEmpty()
    }

    /** Które funkcje trzymają usługę - do opisu w powiadomieniu. */
    fun nazwy(): List<String> = aktywne.keys.sorted()

    const val SPOTKANIE = "spotkanie"
    const val PRZEWODNIK = "przewodnik"
    const val PRZYPOMNIENIA = "przypomnienia"
}
