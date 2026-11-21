package pl.victor.app.google

import android.content.Context
import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Draft
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

    /** Czy wolno zostawić szkic - osobna, nieobowiązkowa zgoda (gmail.compose). */
    fun hasDraftAccess(): Boolean = accountManager.hasGmailDraftAccess()

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
        send(EmailCompose.Outgoing(EmailCompose.parseAddress(to) ?: EmailCompose.Address(null, to), subject, body))

    /**
     * Wysyła gotowy mail - także odpowiedź w wątku ([EmailCompose.Outgoing.threadId]
     * i nagłówki `In-Reply-To`/`References`). Zwraca `true` gdy się udało.
     *
     * Treść maila nie trafia do logu - wystarczy adresat i długość.
     */
    suspend fun send(mail: EmailCompose.Outgoing): Boolean =
        withContext(Dispatchers.IO) {
            val service = buildService(GmailScopes.GMAIL_SEND) ?: return@withContext false
            val account = accountManager.getCurrentAccount() ?: return@withContext false
            try {
                val message = Message().setRaw(EmailCompose.toRaw(EmailCompose.buildMime(account.email, mail)))
                // Bez threadId Gmail i tak próbuje skleić wątek po nagłówkach i
                // temacie, ale tylko u nadawcy i nie zawsze - podany wprost wiąże
                // odpowiedź z wątkiem na pewno.
                mail.threadId?.let { message.setThreadId(it) }
                service.users().messages().send("me", message).execute()
                Log.i(tag, "Email wysłany (${mail.body.length} znaków, wątek: ${mail.threadId != null})")
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

    /**
     * Zostawia mail jako szkic w Gmailu - po „nie” albo ciszy na pytanie
     * „Wysłać?”. Wymaga zgody na szkice ([hasDraftAccess]); bez niej `false`.
     */
    suspend fun createDraft(mail: EmailCompose.Outgoing): Boolean =
        withContext(Dispatchers.IO) {
            if (!accountManager.hasGmailDraftAccess()) return@withContext false
            val account = accountManager.getCurrentAccount() ?: return@withContext false
            val credential = accountManager.getCredential(Collections.singleton(GmailScopes.GMAIL_COMPOSE))
                ?: return@withContext false
            try {
                val service = Gmail.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                    .setApplicationName("V.I.C.T.O.R.")
                    .build()
                val message = Message().setRaw(EmailCompose.toRaw(EmailCompose.buildMime(account.email, mail)))
                mail.threadId?.let { message.setThreadId(it) }
                service.users().drafts().create("me", Draft().setMessage(message)).execute()
                Log.i(tag, "Szkic zapisany (${mail.body.length} znaków)")
                true
            } catch (e: Exception) {
                GoogleAccountManager.noteApiFailure(e)
                Log.w(tag, "Nie udało się zapisać szkicu", e)
                false
            }
        }

    /**
     * Ostatnie maile w skrzynce odbiorczej z nagłówkami potrzebnymi do
     * odpowiedzi w wątku (Message-ID, References, Reply-To) - patrz
     * [EmailCompose.pickMessage].
     *
     * Tylko `in:inbox`: „ostatni mail” to ostatni OTRZYMANY, a nie własny
     * wysłany, który inaczej zwykle byłby na samej górze.
     */
    suspend fun getReplyCandidates(maxResults: Int = 15): List<EmailCompose.ReplyCandidate> =
        withContext(Dispatchers.IO) {
            val service = buildService(GmailScopes.GMAIL_READONLY) ?: return@withContext emptyList()
            try {
                val refs = service.users().messages().list("me")
                    .setMaxResults(maxResults.toLong())
                    .setQ("in:inbox")
                    .execute()
                    .messages ?: return@withContext emptyList()
                kotlinx.coroutines.coroutineScope {
                    refs.map { ref ->
                        async {
                            try {
                                val full = service.users().messages().get("me", ref.id)
                                    .setFormat("metadata")
                                    .setMetadataHeaders(
                                        listOf("From", "Reply-To", "Subject", "Message-ID", "References")
                                    )
                                    .execute()
                                val headers = full.payload?.headers.orEmpty()
                                fun header(name: String) =
                                    headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
                                EmailCompose.ReplyCandidate(
                                    id = full.id ?: ref.id,
                                    threadId = full.threadId,
                                    from = header("From").orEmpty(),
                                    replyTo = header("Reply-To"),
                                    subject = header("Subject").orEmpty(),
                                    messageIdHeader = header("Message-ID"),
                                    references = header("References"),
                                    receivedMs = full.internalDate ?: 0L
                                )
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
                if (GoogleAccountManager.noteApiFailure(e)) {
                    Log.w(tag, "Poczta: logowanie Google wygasło", e)
                } else {
                    Log.e(tag, "Failed to list messages for reply", e)
                }
                emptyList()
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
