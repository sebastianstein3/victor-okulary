package pl.victor.app.hermes

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.victor.app.ai.executeCancellable
import java.io.File
import java.util.concurrent.TimeUnit

/** Adres mostu plików - czysta logika. */
object HermesPlikiAdres {
    const val DOMYŚLNY_PORT = 8643

    /**
     * Adres mostu, gdy użytkownik nie wpisał własnego: ten sam komputer co
     * brama Hermesa, port [DOMYŚLNY_PORT]. `null`, gdy adres bramy jest zły.
     */
    fun pochodny(adresBramy: String): String? {
        val dobry = HermesAdres.sprawdź(adresBramy) as? HermesAdres.Wynik.Dobry ?: return null
        val uri = runCatching { java.net.URI(dobry.baza) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val hostZapis = if (host.contains(':')) "[$host]" else host
        return "${uri.scheme}://$hostZapis:$DOMYŚLNY_PORT"
    }

    /** Nazwa pliku do zapisania w pamięci podręcznej - bez ścieżek i dziwnych znaków. */
    fun bezpiecznaNazwa(nazwa: String): String {
        val ostatni = nazwa.substringAfterLast('/').substringAfterLast('\\')
        val czysta = ostatni.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trimStart('.')
        val krótka = if (czysta.length > 80) czysta.takeLast(80) else czysta
        return krótka.ifBlank { "plik" }
    }

    /** Rozszerzenie dla nazwy bez niego, gdy serwer podał typ. */
    fun rozszerzeniePoTypie(mime: String): String? = when (mime.lowercase().substringBefore(';').trim()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/svg+xml" -> "svg"
        "application/pdf" -> "pdf"
        else -> null
    }
}

/**
 * Pobiera pliki wymienione przez Hermesa do pamięci podręcznej telefonu.
 *
 * - Adres `https://…` ([HermesPlik.zdalny]) - wprost, BEZ klucza (klucz Hermesa
 *   nie może iść do cudzego serwera).
 * - Ścieżka na komputerze - przez most plików (`tools/hermes-pliki`), z kluczem
 *   Hermesa; most sam pilnuje, które foldery wolno czytać.
 *
 * Pobiera tylko na polecenie użytkownika (dotknięcie karty), nigdy sam: adres
 * z odpowiedzi agenta bywa podsunięty przez czytaną przez niego stronę.
 */
class HermesPlikiKlient(
    private val adresMostu: () -> String?,
    private val klucz: () -> String?,
    private val katalog: File
) {
    sealed class Wynik {
        data class Gotowy(val plik: File, val mime: String) : Wynik()
        data class Błąd(val tekst: String) : Wynik()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun pobierz(p: HermesPlik): Wynik = withContext(Dispatchers.IO) {
        try {
            val żądanie = zbudujŻądanie(p) ?: return@withContext Wynik.Błąd(przyczynaBraku(p))
            katalog.mkdirs()
            sprzątnijStare()
            var wynik: Wynik = Wynik.Błąd("Nie udało się pobrać pliku.")
            client.newCall(żądanie).executeCancellable { odp ->
                if (!odp.isSuccessful) {
                    wynik = Wynik.Błąd(
                        when (odp.code) {
                            401, 403 -> "Most plików odrzucił klucz."
                            404 -> "Nie ma takiego pliku albo leży poza folderami udostępnionymi przez most plików."
                            413 -> "Plik jest za duży."
                            else -> "Serwer odpowiedział kodem ${odp.code}."
                        }
                    )
                    return@executeCancellable
                }
                val długość = odp.body?.contentLength() ?: -1L
                if (długość > LIMIT_BAJTÓW) {
                    wynik = Wynik.Błąd("Plik jest za duży (limit ${LIMIT_BAJTÓW / 1024 / 1024} MB).")
                    return@executeCancellable
                }
                val typSerwera = odp.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                val mime = when {
                    p.mime != "image/*" && p.mime != "application/octet-stream" -> p.mime
                    typSerwera.isNotEmpty() && typSerwera != "application/octet-stream" -> typSerwera
                    else -> HermesPliki.typPoNazwie(p.nazwa) ?: p.mime
                }
                var nazwa = HermesPlikiAdres.bezpiecznaNazwa(p.nazwa)
                if (!nazwa.contains('.')) {
                    HermesPlikiAdres.rozszerzeniePoTypie(mime)?.let { nazwa = "$nazwa.$it" }
                }
                val plik = File(katalog, "${(p.odnośnik.hashCode().toLong() and 0xffffffffL).toString(16)}-$nazwa")
                val źródło = odp.body?.byteStream() ?: return@executeCancellable
                var razem = 0L
                plik.outputStream().use { wyj ->
                    val blok = ByteArray(64 * 1024)
                    while (true) {
                        val n = źródło.read(blok)
                        if (n < 0) break
                        razem += n
                        if (razem > LIMIT_BAJTÓW) {
                            plik.delete()
                            wynik = Wynik.Błąd("Plik jest za duży (limit ${LIMIT_BAJTÓW / 1024 / 1024} MB).")
                            return@executeCancellable
                        }
                        wyj.write(blok, 0, n)
                    }
                }
                wynik = Wynik.Gotowy(plik, mime)
            }
            wynik
        } catch (e: java.io.InterruptedIOException) {
            Wynik.Błąd("Pobieranie trwało za długo.")
        } catch (e: java.io.IOException) {
            // Tylko klasa wyjątku - komunikat bywa z adresem.
            Log.w(TAG, "Hermes: pobranie pliku - ${e::class.java.simpleName}")
            Wynik.Błąd(
                if (p.zdalny) "Nie połączyłem się z serwerem pliku."
                else "Nie łączę się z mostem plików. Działa na komputerze (hermes_pliki.py) i adres jest dobry?"
            )
        }
    }

    private fun przyczynaBraku(p: HermesPlik): String =
        if (p.zdalny) "Ten adres nie jest szyfrowany albo jest niepoprawny - nie pobieram."
        else "Brak adresu mostu plików. Uruchom hermes_pliki.py na komputerze i sprawdź adres w Ustawieniach → Hermes."

    private fun zbudujŻądanie(p: HermesPlik): Request? {
        if (p.zdalny) {
            val url = p.odnośnik.toHttpUrlOrNull() ?: return null
            // Zwykłe HTTP tylko w sieci lokalnej - z internetu tylko HTTPS.
            if (!url.isHttps && !pl.victor.app.home.HaAdres.jestLokalny(url.host)) return null
            return Request.Builder().url(url).get().build()
        }
        val most = adresMostu()?.takeIf { it.isNotBlank() } ?: return null
        val baza = (HermesAdres.sprawdź(most) as? HermesAdres.Wynik.Dobry)?.baza ?: return null
        val url = "$baza/plik".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("sciezka", p.odnośnik)?.build() ?: return null
        val b = Request.Builder().url(url).get()
        klucz()?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
            ?.let { b.header("Authorization", "Bearer $it") }
        return b.build()
    }

    /** Pamięć podręczna nie rośnie bez końca: pliki starsze niż tydzień znikają. */
    private fun sprzątnijStare() {
        val granica = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        katalog.listFiles()?.forEach { if (it.isFile && it.lastModified() < granica) it.delete() }
    }

    companion object {
        private const val TAG = "HermesPlikiKlient"
        const val LIMIT_BAJTÓW = 50L * 1024 * 1024
    }
}
