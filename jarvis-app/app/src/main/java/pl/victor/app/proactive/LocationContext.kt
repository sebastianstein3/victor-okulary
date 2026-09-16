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
import androidx.core.content.ContextCompat
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

    /**
     * Buduje fragment promptu z opisem miejsca.
     *
     * @return `null`, gdy nie ma uprawnienia, pozycji albo jest za stara
     */
    suspend fun buildPromptContext(context: Context): String? = withContext(Dispatchers.IO) {
        val location = lastKnownLocation(context) ?: return@withContext null

        val age = System.currentTimeMillis() - location.time
        if (location.time > 0 && age > MAX_AGE_MS) {
            Log.d(TAG, "Ostatnia pozycja sprzed ${age / 60_000} min - za stara, pomijam")
            return@withContext null
        }

        val place = describePlace(context, location)
        buildString {
            append("=== GDZIE JEST UŻYTKOWNIK ===\n")
            if (place != null) {
                append(place).append('\n')
                append("Jeśli na zdjęciu jest budynek, pomnik albo miejsce, które da się ")
                append("rozpoznać po tej lokalizacji - nazwij je. Jeśli nie masz pewności, ")
                append("powiedz to wprost, zamiast zgadywać.\n")
            } else {
                // Bez nazwy same współrzędne i tak coś dają - model zna świat.
                append("Współrzędne: ")
                append("%.4f".format(location.latitude)).append(", ")
                append("%.4f".format(location.longitude)).append('\n')
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
    private suspend fun freshLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return lastKnownLocation(context)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return lastKnownLocation(context)
        }
        val fresh = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
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
     * Nazwa miejsca po polsku: miasto, dzielnica, ulica.
     *
     * `Geocoder` bez sieci zwraca pustą listę - i to jest w porządku, bo wtedy
     * i tak nie ma z czym rozmawiać z modelem.
     */
    private fun describePlace(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale("pl", "PL"))
                .getFromLocation(location.latitude, location.longitude, 1)
            val address = addresses?.firstOrNull() ?: return null

            val parts = listOfNotNull(
                address.countryName,
                address.locality ?: address.subAdminArea,
                address.subLocality,
                address.thoroughfare
            ).distinct()

            if (parts.isEmpty()) null else parts.joinToString(", ")
        } catch (e: Exception) {
            // Brak sieci, brak usługi geokodowania, limit zapytań - wszystko
            // kończy się tak samo: nie znamy nazwy, lecimy dalej.
            Log.d(TAG, "Geokodowanie nie powiodło się: ${e.message}")
            null
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** Czy `Geocoder` w ogóle jest na tym urządzeniu (bywa go brak na emulatorach). */
    fun isGeocodingAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Geocoder.isPresent() else true
}
