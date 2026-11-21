package pl.victor.app.actions

import android.content.Context
import pl.victor.app.google.EmailCompose
import pl.victor.app.google.GmailService
import pl.victor.app.google.GoogleAccountManager

/**
 * Zamienia prośbę o maila na GOTOWY mail - zanim padnie pytanie „Wysłać?”.
 *
 * ## Dlaczego przed pytaniem, a nie po
 * Ta sama zasada co przy SMS-ach (patrz `handleActions` w orkiestratorze):
 * człowiek ma zatwierdzać to, co naprawdę pójdzie. „Napisz do Tomka” po
 * potwierdzeniu zamienione na adres wybrany przez dopasowywacz to zgoda na
 * coś, czego nie słyszał. Dlatego adres, nazwa z książki adresowej, temat
 * „Re: …” i wątek są ustalane tutaj, a pytanie je czyta.
 *
 * Adresat: najpierw kontakty (adres e-mail kontaktu, dopasowanie po brzmieniu -
 * [ContactMatch]), potem nadawcy ostatnich maili (gdy poczta jest włączona).
 */
class EmailPreparer(context: Context) {

    private val gmail = GmailService(context)
    private val contacts = ContactResolver(context)

    sealed class Result {
        /** Mail gotowy do pytania „Wysłać?” - [source] mówi, skąd wziął się adres. */
        data class Ready(val action: Action.SendEmail, val source: String) : Result()

        /** Nie da się przygotować - [speech] mówi to wprost i co z tym zrobić. */
        data class Problem(val speech: String, val reason: String) : Result()
    }

    /** Czy maile mogą iść przez Gmail API (z potwierdzeniem), a nie przez aplikację poczty. */
    fun canSendDirectly(): Boolean = gmail.hasAccess()

    /**
     * @return wynik dla akcji mailowej albo `null`, gdy akcja nie dotyczy poczty
     */
    suspend fun prepare(action: Action): Result? = when (action) {
        is Action.ReplyEmail -> prepareReply(action)
        is Action.SendEmail -> prepareNew(action)
        else -> null
    }

    private suspend fun prepareReply(action: Action.ReplyEmail): Result {
        accessProblem()?.let { return it }
        val candidates = gmail.getReplyCandidates()
        if (candidates.isEmpty()) {
            // Wygasłe logowanie wygląda z zewnątrz jak pusta skrzynka - a rada
            // jest zupełnie inna.
            return if (GoogleAccountManager.isLoginExpired()) {
                Result.Problem(EXPIRED, "logowanie wygasło")
            } else {
                Result.Problem("Nie widzę żadnych maili w skrzynce odbiorczej.", "pusta skrzynka")
            }
        }
        val picked = EmailCompose.pickMessage(candidates, action.from)
            ?: return Result.Problem(
                "Nie widzę w ostatnich mailach nic od: ${action.from}. Powiedz, od kogo był mail, " +
                    "tak jak jest podpisany.",
                "brak nadawcy"
            )
        val reply = EmailCompose.replyTo(picked, action.body)
            ?: return Result.Problem(
                "Nie umiem odczytać adresu nadawcy tego maila, więc nie mam dokąd odpowiedzieć.",
                "brak adresu w nagłówku"
            )
        return Result.Ready(
            Action.SendEmail(
                to = reply.to.email,
                subject = reply.subject,
                body = reply.body,
                resolvedName = reply.to.name,
                threadId = reply.threadId,
                inReplyTo = reply.inReplyTo,
                references = reply.references
            ),
            source = "wątek"
        )
    }

    private suspend fun prepareNew(action: Action.SendEmail): Result {
        val withSubject = if (action.subject.isBlank()) {
            action.copy(subject = EmailCompose.defaultSubject(action.body))
        } else {
            action
        }
        val to = withSubject.to.trim()
        if (EmailCompose.isEmailAddress(to)) return Result.Ready(withSubject.copy(to = to), "adres")

        contacts.findEmailContact(to)?.let { found ->
            return Result.Ready(
                withSubject.copy(to = found.email, resolvedName = found.displayName),
                "kontakty"
            )
        }
        if (gmail.hasAccess()) {
            val sender = EmailCompose.pickSender(gmail.getReplyCandidates(), to)
            if (sender != null) {
                return Result.Ready(
                    withSubject.copy(to = sender.email, resolvedName = sender.name ?: to),
                    "nadawcy maili"
                )
            }
        }
        return Result.Problem(
            "Nie znalazłem adresu e-mail dla: $to - ani w kontaktach" +
                (if (gmail.hasAccess()) ", ani wśród nadawców ostatnich maili" else "") +
                ". Podaj adres albo dopisz go do kontaktu.",
            "brak adresu"
        )
    }

    /** Co włączyć, gdy poczta przez API jest niedostępna - albo `null`, gdy jest. */
    private fun accessProblem(): Result.Problem? = when {
        GoogleAccountManager.isLoginExpired() -> Result.Problem(EXPIRED, "logowanie wygasło")
        !gmail.isSignedIn() -> Result.Problem(
            "Żeby odpowiadać na maile głosem, połącz konto Google w Ustawieniach i włącz tam pocztę.",
            "brak konta Google"
        )
        !gmail.hasAccess() -> Result.Problem(
            "Poczta jest wyłączona. Włącz ją w Ustawieniach, w karcie konta Google, przyciskiem " +
                "\"Włącz pocztę\" - potrzebne są zgody na czytanie i wysyłanie.",
            "brak zgody gmail.send"
        )
        else -> null
    }

    private companion object {
        const val EXPIRED = "Logowanie Google wygasło - zaloguj się ponownie w Ustawieniach, " +
            "w karcie konta Google."
    }
}
