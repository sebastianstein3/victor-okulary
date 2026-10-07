package pl.victor.app.proactive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Gdzie jest użytkownik - jako kontekst do pytań o to, co widzi.
 *
 * ## Po co
 * Model patrzący na samo zdjęcie widzi "kościół" albo "rzeźbę". Ten sam obraz
 * plus "Rzym, okolice Piazza Navona" pozwala mu powiedzieć, KTÓRY to kościół.
 * Różnica jest największa dokładnie tam, gdzie pytanie ma sens - na wakacjach,
 * w obcym mieście, przed budynkiem, którego nazwy się nie zna.
 *
 * ## Czego tu celowo NIE ma
 * - Nie ruszamy Google Play Services. `LocationManager` jest w każdym Androidzie,
 *   a do nazwania okolicy wystarczy ostatnia znana pozycja - nie potrzebujemy
 *   świeżego, energochłonnego fixa GPS.
 * - Nie prosimy o uprawnienie sami. Gdy go nie ma, po prostu nic nie dokleja -
 *   pytanie i tak dostanie odpowiedź, tyle że bez kontekstu miejsca.
 * - Nie doklejamy współrzędnych, gdy udało się ustalić nazwę. Model ma
 *   powiedzieć "jesteś przy Wawelu", a nie recytować stopnie i minuty.
 */
object LocationContext {

    private const val TAG = "LocationContext"

    /** Starsza niż to pozycja opisuje już inne miejsce niż to, na które patrzymy. */
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    /** Do czego doklejamy położenie - od tego zależy, co model ma z nim zrobić. */
    enum class Cel { ZDJĘCIE, OKOLICA, PRZEWODNIK }

    /** Gdzie jest użytkownik - liczby i nazwa, jeśli dało się ją ustalić. */
    data class Tutaj(
        val lat: Double,
        val lon: Double,
        val miasto: String?,
        val opis: String?,
        val wiekMs: Long,
        val dokładnośćM: Float?
    )

    /**
     * Bieżące położenie z nazwą miejsca.
     *
     * Najpierw ostatnia znana pozycja - jest natychmiast. Świeży pomiar tylko
     * wtedy, gdy ostatnia jest starsza niż [ŚWIEŻA_MS] i wołający zgodził się
     * na niego czekać ([naŚwieżąMs]); przy pytaniu "co jest w okolicy" pozycja
     * sprzed kwadransa to już inna ulica.
     *
     * @param naŚwieżąMs ile wolno czekać na świeży pomiar; 0 = wcale
     */
    suspend fun tutaj(context: Context, naŚwieżąMs: Long = 0L): Tutaj? = withContext(Dispatchers.IO) {
        var location = lastKnownLocation(context)
        val wiek = location?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        if (wiek > ŚWIEŻA_MS && naŚwieżąMs > 0) {
            freshLocation(context, naŚwieżąMs)?.let { location = it }
        }
        val loc = location ?: return@withContext null
        val age = System.currentTimeMillis() - loc.time
        if (loc.time > 0 && age > MAX_AGE_MS) {
            Log.d(TAG, "Ostatnia pozycja sprzed ${age / 60_000} min - za stara, pomijam")
            return@withContext null
        }
        val adres = adresZPamięci(context, loc.latitude, loc.longitude)
        Tutaj(
            lat = loc.latitude,
            lon = loc.longitude,
            miasto = adres?.first,
            opis = adres?.second,
            wiekMs = age,
            dokładnośćM = if (loc.hasAccuracy()) loc.accuracy else null
        )
    }

    /**
     * Buduje fragment promptu z opisem miejsca.
     *
     * ## Nie tylko do zdjęć
     * Dziennik z biegu 154: "gdzie w okolicy można zjeść coś taniego" - model
     * nie dostał położenia i szukał "tanie jedzenie Toruń", bo Toruń był w
     * ustawieniach pogody. Pytanie o okolicę bez okolicy to zgadywanie, więc
     * położenie idzie teraz także do takich pytań, z poleceniem, co z nim
     * zrobić ([Cel]).
     *
     * @return `null`, gdy nie ma uprawnienia, pozycji albo jest za stara
     */
    suspend fun buildPromptContext(context: Context, cel: Cel = Cel.ZDJĘCIE): String? {
        val tu = tutaj(context, naŚwieżąMs = if (cel == Cel.ZDJĘCIE) 0L else OKOLICA_ŚWIEŻA_MS)
            ?: return null
        return opisDlaModelu(tu, cel)
    }

    /** Sam tekst dla modelu - osobno, żeby dało się go sprawdzić testem. */
    fun opisDlaModelu(tu: Tutaj, cel: Cel): String = buildString {
        append("=== GDZIE JEST UŻYTKOWNIK (GPS telefonu, ")
        append(if (tu.wiekMs < 2 * 60_000L) "teraz" else "${tu.wiekMs / 60_000} min temu")
        append(") ===\n")
        tu.opis?.let { append(it).append('\n') }
        append("Współrzędne: ")
        append(String.format(Locale.US, "%.5f, %.5f", tu.lat, tu.lon))
        tu.dokładnośćM?.let { append(" (dokładność ok. ").append(it.toInt()).append(" m)") }
        append('\n')
        when (cel) {
            Cel.ZDJĘCIE -> {
                append("Jeśli na zdjęciu jest budynek, pomnik albo miejsce, które da się ")
                append("rozpoznać po tej lokalizacji - nazwij je. Jeśli nie masz pewności, ")
                append("powiedz to wprost, zamiast zgadywać.\n")
            }
            Cel.OKOLICA -> {
                append("Pytanie dotyczy okolicy, w której użytkownik JEST TERAZ. Szukaj ")
                append("miejsc w pobliżu TEGO położenia (wyszukiwarką, jeśli ją masz), a nie ")
                append("w mieście z ustawień. Podawaj nazwy i orientacyjną odległość pieszo. ")
                append("Nie pytaj, gdzie jest - to już wiesz.\n")
            }
            Cel.PRZEWODNIK -> {
                append("Mów jak przewodnik: dwa, trzy najciekawsze miejsca w pobliżu tego ")
                append("położenia, po jednym zdaniu o każdym i jak daleko jest pieszo. ")
                append("Konkretnie, bez wstępów - to będzie czytane na głos.\n")
            }
        }
    }

    /**
     * Współrzędne "tutaj" albo `null`.
     *
     * Wystawione na zewnątrz dla pamięci miejsca
     * ([pl.victor.app.memory.PlaceMemory]): tam potrzebne są same liczby, a nie
     * gotowy opis dla modelu, który buduje [buildPromptContext].
     */
    suspend fun currentPosition(context: Context): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            freshLocation(context)?.let { it.latitude to it.longitude }
        }

    /**
     * ŚWIEŻY pomiar położenia, z ostatnią znaną pozycją jako ratunkiem.
     *
     * ## Czemu nie sama ostatnia znana
     * Bo do pamięci miejsca ("zapamiętaj, gdzie zaparkowałem") jest dokładnie
     * zła. Ostatnia znana pozycja pochodzi z chwili, w której coś na telefonie
     * ostatnio pytało o lokalizację - a telefon w kieszeni potrafi jej nie
     * odświeżać kwadransami. Zapisalibyśmy wtedy miejsce, w którym użytkownik
     * był kilka minut temu, czyli na parkingu nawet kilkaset metrów obok.
     *
     * Funkcja, której cały sens to odnalezienie samochodu, myliłaby się o
     * więcej niż odległość, którą ma wskazać.
     *
     * ## Czemu mimo to zostaje ratunek
     * Bo świeży pomiar bywa niemożliwy: w garażu podziemnym GPS nie widzi
     * nieba. Stara pozycja jest wtedy lepsza niż żadna - ale dopiero WTEDY, a
     * nie zamiast próby.
     *
     * ## Limit czasu
     * Osiem sekund. Tyle zwykle zajmuje zimny start GPS na otwartej przestrzeni,
     * a człowiek stoi w tym czasie przy samochodzie i czeka na "zapamiętane".
     * Dłużej znaczyłoby, że odejdzie, zanim usłyszy potwierdzenie.
     */
    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(
        context: Context,
        timeoutMs: Long = FRESH_FIX_TIMEOUT_MS
    ): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return lastKnownLocation(context)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return lastKnownLocation(context)
        }
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { manager.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location) {}
                    }

                    // Puste, ale WYMAGANE na Androidzie 8 i 9: bez nich
                    // rejestracja nasłuchu rzuca AbstractMethodError na części
                    // urządzeń. Domyślne implementacje weszły dopiero w API 30.
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                runCatching {
                    manager.requestLocationUpdates(
                        provider, 0L, 0f, listener, android.os.Looper.getMainLooper()
                    )
                }.onFailure { if (cont.isActive) cont.resume(null) {} }
                cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
            }
        }
        if (fresh == null) {
            Log.d(TAG, "Świeży pomiar nie przyszedł w czasie - biorę ostatnią znaną pozycję")
        }
        return fresh ?: lastKnownLocation(context)
    }

    /** Patrz [freshLocation]. */
    private const val FRESH_FIX_TIMEOUT_MS = 8_000L

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) {
            Log.d(TAG, "Brak uprawnienia do lokalizacji - pomijam kontekst miejsca")
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return try {
            // Bierzemy najświeższą z dostępnych dostawców. GPS bywa nieaktualny
            // w budynku, sieć bywa niedokładna - razem dają rozsądny wynik.
            manager.allProviders
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            Log.w(TAG, "Odczyt lokalizacji nie powiódł się", e)
            null
        }
    }

    /**
     * (miasto, pełny opis) po polsku - z pamięci, gdy pytaliśmy o to samo
     * miejsce przed chwilą.
     *
     * `Geocoder` idzie przez sieć i potrafi zająć sekundę, a pytania o okolicę
     * przychodzą seriami ("gdzie zjeść", "a ile tam kosztuje", "jak dojść").
     * Ten sam kwartał (zaokrąglenie do circa 100 m) przez [ADRES_PAMIĘĆ_MS]
     * nie zmienia nazwy ulicy.
     *
     * `Geocoder` bez sieci zwraca pustą listę - i to jest w porządku, bo wtedy
     * i tak nie ma z czym rozmawiać z modelem.
     */
    private fun adresZPamięci(context: Context, lat: Double, lon: Double): Pair<String?, String>? {
        val klucz = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        ostatniAdres?.let { (k, czas, wynik) ->
            if (k == klucz && System.currentTimeMillis() - czas < ADRES_PAMIĘĆ_MS) return wynik
        }
        val wynik = describePlace(context, lat, lon)
        if (wynik != null) ostatniAdres = Triple(klucz, System.currentTimeMillis(), wynik)
        return wynik
    }

    @Volatile
    private var ostatniAdres: Triple<String, Long, Pair<String?, String>>? = null

    private const val ADRES_PAMIĘĆ_MS = 10 * 60_000L

    /** Starsza pozycja przy pytaniu o okolicę - mierzymy na nowo. */
    private const val ŚWIEŻA_MS = 2 * 60_000L

    /** Ile pytanie o okolicę może czekać na GPS - mieści się w budżecie kontekstów. */
    private const val OKOLICA_ŚWIEŻA_MS = 3_500L

    private fun describePlace(context: Context, lat: Double, lon: Double): Pair<String?, String>? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale("pl", "PL"))
                .getFromLocation(lat, lon, 1)
            val address = addresses?.firstOrNull() ?: return null

            val miasto = address.locality ?: address.subAdminArea
            val ulica = listOfNotNull(address.thoroughfare, address.subThoroughfare)
                .joinToString(" ").ifBlank { null }
            val parts = listOfNotNull(
                address.countryName,
                miasto,
                address.subLocality,
                ulica
            ).distinct()

            if (parts.isEmpty()) null else miasto to parts.joinToString(", ")
        } catch (e: Exception) {
            // Brak sieci, brak usługi geokodowania, limit zapytań - wszystko
            // kończy się tak samo: nie znamy nazwy, lecimy dalej.
            Log.d(TAG, "Geokodowanie nie powiodło się: ${e.message}")
            null
        }
    }

    /** Czy aplikacja ma zgodę na lokalizację - do komunikatu "nie wiem, gdzie jesteś". */
    fun maZgodę(context: Context): Boolean = hasLocationPermission(context)

    private fun hasLocationPermission(context: Context): Boolean {
        // Wprost na kontekście (API 23+, a minSdk to 26) - bez androidx, żeby
        // klasa dawała się sprawdzić testem bez Androida.
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** Czy `Geocoder` w ogóle jest na tym urządzeniu (bywa go brak na emulatorach). */
    fun isGeocodingAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Geocoder.isPresent() else true
}
