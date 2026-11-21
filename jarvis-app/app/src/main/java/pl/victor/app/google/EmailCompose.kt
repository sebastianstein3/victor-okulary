package pl.victor.app.google

import pl.victor.app.actions.ContactMatch
import java.util.Base64

/**
 * Składanie maila do wysłania przez Gmail API - bez Androida i bez klienta Google.
 *
 * ## Po co osobno
 * Bo tu są rzeczy, których pomyłki nie widać w aplikacji, tylko u adresata:
 * odpowiedź bez `In-Reply-To` i `References` ląduje u niego jako NOWY wątek,
 * temat z polskimi znakami bez kodowania RFC 2047 przychodzi jako krzaki, a
 * znak nowej linii w temacie (z dyktowania) pozwoliłby dopisać własny nagłówek.
 * Wszystko to da się sprawdzić testem JVM, więc siedzi tutaj, a [GmailService]
 * tylko wysyła gotowy tekst.
 *
 * Base64 z `java.util` (Android ma go od API 26, a minSdk to 26) - ta sama
 * implementacja działa w aplikacji i w testach, gdzie `android.util.Base64`
 * jest atrapą zwracającą `null`.
 */
object EmailCompose {

    /** Adres z opcjonalną nazwą - „Ania Kowalska <ania@example.com>”. */
    data class Address(val name: String?, val email: String) {
        /** Nazwa do powiedzenia na głos: nazwa, a bez niej sam adres. */
        val spoken: String get() = name?.takeIf { it.isNotBlank() } ?: email
    }

    /**
     * Mail z ostatnich w skrzynce, na który da się odpowiedzieć - tylko pola
     * potrzebne do wątku. Niezależny od klas Gmail API, żeby wybór dał się
     * przetestować.
     */
    data class ReplyCandidate(
        val id: String,
        val threadId: String?,
        val from: String,
        val replyTo: String?,
        val subject: String,
        val messageIdHeader: String?,
        val references: String?,
        val receivedMs: Long
    )

    /** Gotowy mail - to, co pytanie „Wysłać?” opisuje i co potem idzie do Google. */
    data class Outgoing(
        val to: Address,
        val subject: String,
        val body: String,
        val threadId: String? = null,
        val inReplyTo: String? = null,
        val references: String? = null
    )

    private val ANGLE = Regex("""^(.*?)<\s*([^<>\s]+@[^<>\s]+)\s*>\s*$""")
    private val BARE = Regex("""^[^\s<>@,;]+@[^\s<>@,;]+\.[^\s<>@,;]+$""")

    /** Czy tekst jest samym adresem e-mail (a nie imieniem z kontaktów). */
    fun isEmailAddress(text: String): Boolean = BARE.matches(text.trim())

    /**
     * Rozbiera nagłówek „From”/„Reply-To”. Bierze PIERWSZY adres - „Reply-To”
     * bywa listą, a odpowiadamy jednej osobie.
     *
     * @return adres albo `null`, gdy w tekście żadnego nie ma
     */
    fun parseAddress(header: String?): Address? {
        val first = header?.let(::firstOfList)?.trim().orEmpty()
        if (first.isEmpty()) return null
        ANGLE.matchEntire(first)?.let { m ->
            val name = m.groupValues[1].trim().trim('"', '\'').trim().ifBlank { null }
            return Address(name, m.groupValues[2].trim())
        }
        return if (isEmailAddress(first)) Address(null, first) else null
    }

    /** Pierwszy adres z listy - przecinek w cudzysłowie należy do nazwy („Nowak, Ania”). */
    private fun firstOfList(header: String): String {
        var inQuotes = false
        header.forEachIndexed { i, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return header.substring(0, i)
            }
        }
        return header
    }

    /**
     * Temat odpowiedzi. Bez dokładania drugiego „Re:” do „Re:” - ani do „RE:”,
     * „Odp:” czy „Fwd: Re:”, które zostawiają inne programy pocztowe.
     */
    fun replySubject(original: String?): String {
        val s = sanitizeHeader(original.orEmpty()).trim()
        if (s.isEmpty()) return "Re:"
        return if (REPLY_PREFIX.containsMatchIn(s)) s else "Re: $s"
    }

    private val REPLY_PREFIX = Regex("""^(?:re|odp|aw|sv)\s*:""", RegexOption.IGNORE_CASE)

    /**
     * Temat, gdy model go nie podał: początek pierwszego zdania treści.
     * Mail bez tematu ląduje u wielu adresatów w spamie albo na dole listy.
     */
    fun defaultSubject(body: String, maxLength: Int = 50): String {
        val first = sanitizeHeader(body).split(Regex("(?<=[.!?])\\s")).firstOrNull()
            ?.trim()?.trimEnd('.', '!', '?')?.trim().orEmpty()
        if (first.isEmpty()) return "(bez tematu)"
        if (first.length <= maxLength) return first
        val cut = first.take(maxLength)
        val atSpace = cut.substringBeforeLast(' ', cut)
        return atSpace.trimEnd(',', ';', ':', ' ') + "…"
    }

    /**
     * Nagłówek „References” odpowiedzi: dotychczasowe odwołania plus
     * identyfikator maila, na który odpowiadamy (RFC 5322, 3.6.4). Dzięki
     * temu łańcuch wątku zgadza się także u adresata, który nie używa Gmaila.
     */
    fun referencesFor(originalReferences: String?, originalMessageId: String?): String? {
        val parts = sanitizeHeader(originalReferences.orEmpty()).split(Regex("\\s+"))
            .filter { it.isNotBlank() }.toMutableList()
        val id = originalMessageId?.let(::sanitizeHeader)?.trim()?.ifBlank { null }
        if (id != null && id !in parts) parts += id
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Odpowiedź na wybrany mail: adresat z „Reply-To” (a bez niego z „From”),
     * temat „Re: …”, nagłówki wątku i identyfikator wątku Gmaila.
     *
     * @return `null`, gdy w nagłówkach nie ma adresu, na który dałoby się odpisać
     */
    fun replyTo(original: ReplyCandidate, body: String): Outgoing? {
        val to = parseAddress(original.replyTo) ?: parseAddress(original.from) ?: return null
        // Nazwa z „From”, gdy „Reply-To” jej nie ma - pytanie „Wysłać?” ma
        // mówić „do Ani Kowalskiej”, a nie przeliterowany adres.
        val named = if (to.name == null) {
            parseAddress(original.from)?.takeIf { it.email.equals(to.email, true) }?.name
                ?.let { to.copy(name = it) } ?: to
        } else {
            to
        }
        return Outgoing(
            to = named,
            subject = replySubject(original.subject),
            body = body,
            threadId = original.threadId,
            inReplyTo = original.messageIdHeader?.let(::sanitizeHeader)?.trim()?.ifBlank { null },
            references = referencesFor(original.references, original.messageIdHeader)
        )
    }

    /**
     * Wybiera mail, na który użytkownik chce odpowiedzieć.
     *
     * @param who imię/nazwa/adres nadawcy albo `null` = najnowszy mail
     * @return najnowszy mail od najlepiej pasującego nadawcy; `null`, gdy nikt
     *   nie pasuje. Remis między różnymi nadawcami rozstrzyga świeżość - to
     *   jest odpowiedź na „ostatni mail od Ani”, a nazwa i tak pada w pytaniu
     *   „Wysłać?”, zanim cokolwiek pójdzie.
     */
    fun pickMessage(candidates: List<ReplyCandidate>, who: String?): ReplyCandidate? {
        val newestFirst = candidates.sortedByDescending { it.receivedMs }
        if (who.isNullOrBlank()) return newestFirst.firstOrNull()
        var best: ReplyCandidate? = null
        var bestScore = ContactMatch.NO_MATCH
        for (c in newestFirst) {
            val s = senderScore(who, c.from)
            if (s > bestScore) {
                best = c
                bestScore = s
            }
        }
        return best
    }

    /**
     * Jak dobrze `who` opisuje nadawcę z nagłówka: po nazwie (to samo
     * dopasowanie co kontakty, z brzmieniem i odmianą) albo po adresie.
     */
    fun senderScore(who: String, fromHeader: String): Int {
        val address = parseAddress(fromHeader) ?: return ContactMatch.NO_MATCH
        if (who.trim().equals(address.email, ignoreCase = true)) return 100
        val byName = address.name?.let { ContactMatch.score(who, it) } ?: ContactMatch.NO_MATCH
        // „ania.kowalska@…” bez nazwy w nagłówku - część przed @ jak nazwa.
        val local = address.email.substringBefore('@').replace(Regex("[._\\-+]+"), " ")
        val byLocal = ContactMatch.score(who, local)
        return maxOf(byName, byLocal)
    }

    /**
     * Adresat spośród nadawców ostatnich maili - gdy w kontaktach nie ma
     * adresu („napisz do Tomka”, a Tomek pisał wczoraj z pracy).
     */
    fun pickSender(candidates: List<ReplyCandidate>, who: String): Address? =
        pickMessage(candidates, who)?.takeIf { senderScore(who, it.from) > ContactMatch.NO_MATCH }
            ?.let { parseAddress(it.from) }

    /**
     * Usuwa z wartości nagłówka znaki końca linii. Inaczej podyktowany temat
     * z nową linią mógłby dopisać do maila dowolny nagłówek (np. „Bcc:”).
     */
    fun sanitizeHeader(value: String): String =
        value.replace(Regex("[\\r\\n]+"), " ").trim()

    /**
     * Słowo nagłówka zakodowane wg RFC 2047, gdy ma znaki spoza ASCII.
     * Czysty ASCII zostaje jak jest - czytelniej w surowym źródle maila.
     */
    fun encodeWord(value: String): String {
        val clean = sanitizeHeader(value)
        if (clean.all { it.code in 0x20..0x7E }) return clean
        val b64 = Base64.getEncoder().encodeToString(clean.toByteArray(Charsets.UTF_8))
        return "=?UTF-8?B?$b64?="
    }

    /** „Nazwa <adres>” z nazwą zakodowaną, gdy trzeba. */
    fun formatAddress(address: Address): String {
        val name = address.name?.let(::sanitizeHeader)?.takeIf { it.isNotBlank() }
            ?: return sanitizeHeader(address.email)
        val encoded = encodeWord(name)
        // Nazwa ASCII ze znakami specjalnymi (przecinek, kropka) musi być w cudzysłowie.
        val shown = if (encoded == name && name.any { it in SPECIALS }) {
            "\"" + name.replace("\"", "") + "\""
        } else {
            encoded
        }
        return "$shown <${sanitizeHeader(address.email)}>"
    }

    private const val SPECIALS = "()<>[]:;@\\,.\""

    /**
     * Pełny tekst maila (RFC 5322) gotowy do zakodowania w [toRaw].
     *
     * Treść idzie w base64 z łamaniem linii co 76 znaków - polskie znaki i
     * długie akapity z dyktowania przechodzą wtedy przez każdy serwer bez
     * przekłamań, czego surowy 8-bitowy tekst nie gwarantuje.
     */
    fun buildMime(from: String?, mail: Outgoing): String {
        val headers = mutableListOf<String>()
        from?.takeIf { it.isNotBlank() }?.let { headers += "From: ${sanitizeHeader(it)}" }
        headers += "To: ${formatAddress(mail.to)}"
        headers += "Subject: ${encodeWord(mail.subject)}"
        mail.inReplyTo?.let { headers += "In-Reply-To: ${sanitizeHeader(it)}" }
        mail.references?.let { headers += "References: ${sanitizeHeader(it)}" }
        headers += "MIME-Version: 1.0"
        headers += "Content-Type: text/plain; charset=\"UTF-8\""
        headers += "Content-Transfer-Encoding: base64"
        val body = Base64.getMimeEncoder(76, "\r\n".toByteArray())
            .encodeToString(mail.body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
        return headers.joinToString("\r\n") + "\r\n\r\n" + body
    }

    /** Pole `raw` Gmail API: cały mail w base64url (RFC 4648, §5). */
    fun toRaw(mime: String): String =
        Base64.getUrlEncoder().encodeToString(mime.toByteArray(Charsets.UTF_8))
}
