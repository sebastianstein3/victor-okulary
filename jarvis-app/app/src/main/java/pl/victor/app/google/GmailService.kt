package pl.victor.app.google

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Gmail API integration - czyta ostatnie maile i wysyła nowe.
 *
 * Logowanie idzie przez współdzielony [GoogleAccountManager] - to samo
 * konto Google co Calendar, patrz jego dokumentacja.
 *
 * Wymaga w build.gradle.kts:
 * - implementation("com.google.apis:google-api-services-gmail:v1-rev20260727-2.0.0")
 * (play-services-auth i google-api-client-android już są dla Calendar)
 */
class GmailService(context: Context) {

    private val tag = "GmailService"
    private val accountManager = GoogleAccountManager(context)

    /**
     * Sprawdza czy user jest zalogowany (wspólne konto Google).
     */
    fun isSignedIn(): Boolean = accountManager.isSignedIn()

    /** Czy konto ma zgodę na pocztę - poczta jest zgodą DODATKOWĄ, patrz GoogleAccountManager. */
    fun hasAccess(): Boolean = accountManager.hasGmailAccess()

    private fun buildService(scope: String): Gmail? {
        // Bez zgody na pocztę nie ma po co budować klienta: żądanie doszłoby do
        // Google i wróciło błędem 403, czyli awarią zamiast czytelnego "nie
        // włączyłeś tej funkcji". Wszyscy wołający traktują null jako brak poczty.
        if (!accountManager.hasGmailAccess()) return null
        val credential = accountManager.getCredential(Collections.singleton(scope)) ?: return null
        return try {
            Gmail.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("V.I.C.T.O.R.")
                .build()
        } catch (e: Exception) {
            Log.e(tag, "Failed to build Gmail service", e)
            null
        }
    }

    /**
     * Pobiera ostatnie maile (nagłówki + fragment treści, bez pobierania pełnej wiadomości).
     */
    suspend fun getRecentMessages(
        maxResults: Int = 10,
        unreadOnly: Boolean = false
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        val service = buildService(GmailScopes.GMAIL_READONLY) ?: return@withContext emptyList()
        try {
            val request = service.users().messages().list("me")
                .setMaxResults(maxResults.toLong())
            if (unreadOnly) {
                request.setQ("is:unread")
            }
            val list = request.execute()

            // RÓWNOLEGLE: dotąd 1 + 10 zapytań jedno po drugim, więc poczta często nie
            // mieściła się w budżecie kontekstu (6 s) i odpadała z odpowiedzi.
            val refs = list.messages ?: return@withContext emptyList()
            kotlinx.coroutines.coroutineScope {
                refs.map { ref ->
                    async {
                        try {
                            val full = service.users().messages().get("me", ref.id)
                                .setFormat("metadata")
                                .setMetadataHeaders(listOf("From", "Subject"))
                                .execute()
                            parseSummary(full)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(tag, "Failed to fetch message ${ref.id}", e)
                            null
                        }
                    }
                }.map { it.await() }.filterNotNull()
            }
        } catch (e: Exception) {
            // Wygasłe logowanie nie może wyglądać jak pusta skrzynka.
            if (GoogleAccountManager.noteApiFailure(e)) {
                Log.w(tag, "Poczta: logowanie Google wygasło", e)
            } else {
                Log.e(tag, "Failed to list messages", e)
            }
            emptyList()
        }
    }

    /**
     * Wysyła email. Zwraca `true` gdy się udało.
     */
    suspend fun sendEmail(to: String, subject: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            val service = buildService(GmailScopes.GMAIL_SEND) ?: return@withContext false
            val account = accountManager.getCurrentAccount() ?: return@withContext false
            try {
                val mime = "From: ${account.email}\r\n" +
                        "To: $to\r\n" +
                        "Subject: ${encodeSubjectHeader(subject)}\r\n" +
                        "Content-Type: text/plain; charset=\"UTF-8\"\r\n" +
                        "\r\n" +
                        body
                val raw = Base64.encodeToString(
                    mime.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )
                service.users().messages().send("me", Message().setRaw(raw)).execute()
                Log.i(tag, "Email sent to $to")
                true
            } catch (e: Exception) {
                if (GoogleAccountManager.noteApiFailure(e)) {
                    Log.w(tag, "Wysyłka maila: logowanie Google wygasło", e)
                } else {
                    Log.e(tag, "Failed to send email", e)
                }
                false
            }
        }

    private fun parseSummary(message: Message): EmailSummary {
        val headers = message.payload?.headers.orEmpty()
        val from = headers.firstOrNull { it.name.equals("From", ignoreCase = true) }?.value
            ?: "(nieznany nadawca)"
        val subject = headers.firstOrNull { it.name.equals("Subject", ignoreCase = true) }?.value
            ?: "(brak tematu)"
        return EmailSummary(
            id = message.id ?: "",
            from = from,
            subject = subject,
            snippet = message.snippet ?: "",
            receivedMs = message.internalDate ?: 0L,
            isUnread = message.labelIds?.contains("UNREAD") == true
        )
    }

    /** Nagłówki maila to ASCII (RFC 2047) - temat z polskimi znakami trzeba zakodować. */
    private fun encodeSubjectHeader(subject: String): String {
        val encoded = Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "=?UTF-8?B?$encoded?="
    }
}

/**
 * Skrót maila do kontekstu AI i list w UI (niezależny od Gmail API).
 */
data class EmailSummary(
    val id: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val receivedMs: Long,
    val isUnread: Boolean
)
