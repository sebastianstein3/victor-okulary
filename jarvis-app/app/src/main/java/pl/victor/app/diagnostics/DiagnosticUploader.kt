package pl.victor.app.diagnostics

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Wysyła dziennik do gałęzi diagnostycznej w repozytorium przez API GitHuba.
 *
 * ## Dlaczego akurat do repozytorium
 * Bo to jedyne miejsce, do którego i użytkownik, i ja mamy dostęp bez
 * wymyślania nowej infrastruktury. Plik ląduje w gałęzi [BRANCH], która NIE
 * wyzwala budowania (workflow słucha wyłącznie gałęzi z kodem) - więc wysyłka
 * dziennika nie wywołuje buildu za każdym razem.
 *
 * ## Token
 * Własny token użytkownika, wpisany w ustawieniach jak każdy inny klucz.
 * Wystarczy uprawnienie `Contents: Read and write` na TO JEDNO repozytorium -
 * token o szerszym zakresie nie jest do niczego potrzebny. W dzienniku token
 * nigdy się nie pojawia (patrz [DiagFormat.redact]), a tutaj idzie wyłącznie
 * w nagłówku Authorization.
 */
class DiagnosticUploader(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Wgrywa treść pod ścieżkę w gałęzi diagnostycznej.
     *
     * Ta sama nazwa pliku jest nadpisywana w miarę rosnięcia sesji - dlatego
     * najpierw pytamy o `sha` istniejącej wersji. GitHub odrzuca zapis bez
     * niego, gdy plik już jest, a rosnący dziennik z definicji zapisujemy
     * wielokrotnie.
     *
     * @return nazwa commita albo opis błędu
     */
    suspend fun upload(fileName: String, content: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = "$DIR/$fileName"
                val existingSha = fetchSha(path)
                val body = JSONObject().apply {
                    put("message", "Dziennik diagnostyczny: $fileName")
                    put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
                    put("branch", BRANCH)
                    if (existingSha != null) put("sha", existingSha)
                }
                val request = Request.Builder()
                    .url("$API/contents/$path")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("X-GitHub-Api-Version", "2022-11-28")
                    .put(body.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException(explain(response.code, text))
                    }
                    path
                }
            }.onFailure { Log.w(TAG, "Wysyłka dziennika nie powiodła się", it) }
        }

    /** `sha` istniejącego pliku albo `null`, gdy go jeszcze nie ma. */
    private fun fetchSha(path: String): String? {
        val request = Request.Builder()
            .url("$API/contents/$path?ref=$BRANCH")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty()).optString("sha").ifBlank { null }
            }
        }.getOrNull()
    }

    /**
     * Zamienia kod HTTP na zdanie, po którym wiadomo, CO ZROBIĆ.
     *
     * Surowa odpowiedź GitHuba jest po angielsku i mówi o zasobach, nie o
     * tokenach - a jedyny realny powód niepowodzenia to źle nadany token.
     */
    private fun explain(code: Int, body: String): String = when (code) {
        401 -> "GitHub nie przyjął tokenu (401). Sprawdź, czy nie wygasł i czy " +
            "wkleiłeś go w całości."
        403 -> "GitHub odmówił (403). Token musi mieć uprawnienie " +
            "\"Contents: Read and write\" na repozytorium $OWNER/$REPO."
        404 -> "GitHub nie widzi repozytorium albo gałęzi $BRANCH (404). Token " +
            "musi obejmować repozytorium $OWNER/$REPO."
        422 -> "GitHub odrzucił zapis (422): $body"
        else -> "GitHub odpowiedział $code: ${body.take(200)}"
    }

    companion object {
        private const val TAG = "DiagnosticUploader"
        const val OWNER = "sebastianstein3"
        const val REPO = "victor-okulary"

        /**
         * Gałąź na dzienniki. Osobna od gałęzi z kodem CELOWO: workflow budujący
         * aplikację słucha wyłącznie tamtej, więc wysłanie dziennika nie
         * uruchamia kilkunastominutowego buildu.
         */
        const val BRANCH = "victor-diagnostics"
        private const val DIR = "diagnostics"
        private const val API = "https://api.github.com/repos/$OWNER/$REPO"
        private val JSON = "application/json".toMediaType()
    }
}
