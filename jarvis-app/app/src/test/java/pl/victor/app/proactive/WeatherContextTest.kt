package pl.victor.app.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pogoda dokleja się do promptu tylko wtedy, gdy pytanie faktycznie jej dotyczy.
 * Za szeroki wzorzec kosztowałby zapytanie do API przy KAŻDYM pytaniu; za wąski
 * wraca do stanu, w którym model zmyślał temperaturę.
 */
class WeatherContextTest {

    @Test
    fun `rozpoznaje pytania wprost o pogode`() {
        assertTrue(WeatherContext.isAboutWeather("jaka jest pogoda?"))
        assertTrue(WeatherContext.isAboutWeather("Jaka temperatura na zewnątrz"))
        assertTrue(WeatherContext.isAboutWeather("prognoza na jutro"))
    }

    @Test
    fun `rozpoznaje pytania zadane nie wprost`() {
        // Tak się pyta na głos - i to jest właściwy test tej funkcji.
        assertTrue(WeatherContext.isAboutWeather("brać kurtkę?"))
        assertTrue(WeatherContext.isAboutWeather("czy zmoknę po drodze"))
        assertTrue(WeatherContext.isAboutWeather("potrzebny parasol?"))
        assertTrue(WeatherContext.isAboutWeather("jak się ubrać na spacer"))
    }

    @Test
    fun `nie reaguje na zwykle pytania`() {
        assertFalse(WeatherContext.isAboutWeather("jaka jest stolica Francji"))
        assertFalse(WeatherContext.isAboutWeather("wyślij SMS do Ani"))
        assertFalse(WeatherContext.isAboutWeather("co to jest"))
        assertFalse(WeatherContext.isAboutWeather(""))
    }

    @Test
    fun `bez prognozy nie dokleja niczego`() {
        assertNull(WeatherContext.buildPromptContext(null))
        assertNull(WeatherContext.buildPromptContext(WeatherForecast("Kraków", emptyList())))
    }

    @Test
    fun `prognoza trafia do promptu z miastem i temperatura`() {
        val now = 1_700_000_000_000L
        val forecast = WeatherForecast(
            city = "Kraków",
            entries = listOf(
                WeatherEntry(
                    timestampMs = now + 3_600_000L,
                    tempCelsius = 12.4,
                    feelsLike = 9.1,
                    humidity = 70,
                    windSpeed = 5.0,
                    condition = "Rain",
                    description = "lekki deszcz",
                    rainMm3h = 1.2
                )
            ),
            sunsetMs = now + 7_200_000L
        )

        val prompt = WeatherContext.buildPromptContext(forecast, nowMs = now)
        assertNotNull(prompt)
        val text = prompt!!
        assertTrue(text.contains("Kraków"))
        assertTrue(text.contains("12°C"))
        assertTrue(text.contains("lekki deszcz"))
        // Odczuwalna różni się o ponad 2 stopnie, więc ma się pojawić.
        assertTrue(text.contains("odczuwalna"))
        assertTrue(text.contains("Do zachodu słońca"))
    }

    @Test
    fun `prognoza mowi, dla jakiego miejsca jest - i co zrobic z innym`() {
        // Zgłoszone: "nie ma informacji o pogodzie w Bodrum, bo ma pogodę tylko
        // dla Torunia". Blok był doklejany do każdego pytania o pogodę i kazał
        // się na sobie opierać - więc model odmawiał, zamiast sprawdzić.
        val note = WeatherContext.scopeNote("Toruń")
        assertTrue(note.contains("WYŁĄCZNIE miejscowości Toruń"))
        assertTrue("ma kazać sprawdzić w internecie", note.contains("wyszukiwarce internetowej"))
        // Model bez wyszukiwarki (zapasowy, lokalny) nie może zmyślać prognozy.
        assertTrue(note.contains("jeśli nie masz do niej dostępu"))
    }

    @Test
    fun `zastrzezenie o miejscu trafia do samego promptu`() {
        val now = 1_700_000_000_000L
        val forecast = WeatherForecast(
            city = "Toruń",
            entries = listOf(
                WeatherEntry(
                    timestampMs = now + 3_600_000L, tempCelsius = 10.0, feelsLike = 10.0,
                    humidity = 60, windSpeed = 2.0, condition = "Clear", description = "bezchmurnie"
                )
            )
        )
        val text = WeatherContext.buildPromptContext(forecast, nowMs = now)!!
        assertTrue(text.contains(WeatherContext.scopeNote("Toruń")))
    }

    @Test
    fun `stare wpisy prognozy nie trafiaja do promptu`() {
        val now = 1_700_000_000_000L
        val forecast = WeatherForecast(
            city = "Warszawa",
            entries = listOf(
                WeatherEntry(
                    timestampMs = now - 86_400_000L,
                    tempCelsius = 30.0,
                    feelsLike = 30.0,
                    humidity = 40,
                    windSpeed = 1.0,
                    condition = "Clear",
                    description = "wczorajszy upał"
                )
            )
        )
        assertNull(WeatherContext.buildPromptContext(forecast, nowMs = now))
    }
}
