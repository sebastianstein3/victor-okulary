package pl.victor.app.vision

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Pobranie opisu produktu po kodzie kreskowym - część sieciowa.
 *
 * Czysta logika (odpowiedź -> zdanie) siedzi w [ProductLookup] i ma własne
 * testy. Tutaj jest wyłącznie HTTP, żeby tamto dało się uruchomić bez sieci.
 *
 * ## Limit czasu jest krótki z rozmysłu
 * To jest dodatek do odpowiedzi, nie jej treść. Człowiek stoi w sklepie z
 * produktem w ręce i czeka - a przy braku zasięgu w markecie (piwnica,
 * regały) zapytanie i tak nie dojdzie. Lepiej odpowiedzieć bez opisu z bazy
 * niż kazać czekać.
 */
class ProductLookupClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
) {
    private val tag = "ProductLookup"

    /** @return zdanie o produkcie albo `null` - gdy nie ma go w bazie lub sieć milczy. */
    suspend fun describe(barcode: String): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                val request = Request.Builder()
                    .url(ProductLookup.urlFor(barcode))
                    // Open Food Facts prosi w regulaminie API o nazwę aplikacji
                    // w User-Agent. To nie jest grzeczność: bez tego potrafią
                    // ograniczyć ruch.
                    .header("User-Agent", USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    ProductLookup.describe(response.body?.string().orEmpty())
                }
            }.onFailure { Log.w(tag, "Nie udało się odpytać bazy produktów", it) }
                .getOrNull()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 4_000L
        private const val USER_AGENT = "VICTOR-Glasses/0.1 (github.com/sebastianstein3/victor-okulary)"
    }
}
