package pl.victor.app.actions

/**
 * Głosowa obsługa maila między „Wysłać?” a wysłaniem - czysta logika.
 *
 * ## Po co osobno
 * Bo mail to jedyna akcja, którą po pytaniu można jeszcze POPRAWIAĆ. SMS i
 * połączenie mają odpowiedź tak/nie; przy mailu ułożonym przez model naturalne
 * jest „popraw, napisz że będę o piątej” - a dokładnie to zdanie
 * [ConfirmationReply.parse] czyta jako „nie” (pierwsze rozstrzygające słowo)
 * i kasowało szkic. Rozpoznanie poprawki musi więc iść PRZED tak/nie.
 */
object EmailVoice {

    /**
     * Uwaga do szkicu, gdy wypowiedź jest prośbą o poprawkę - albo `null`.
     *
     * Zwraca CAŁĄ wypowiedź (z czasownikiem): model lepiej rozumie „zmień
     * temat na urlop” niż samo „temat na urlop”.
     */
    fun correction(text: String?): String? {
        val t = text?.trim()?.trimEnd('.', '!')?.trim().orEmpty()
        if (t.isEmpty()) return null
        val lower = norm(t)
        val hit = CORRECTION_STARTS.any { start ->
            lower == start || lower.startsWith("$start ") || lower.startsWith("$start,") ||
                lower.startsWith("$start:")
        }
        if (!hit) return null
        // Sam czasownik („popraw”) to jeszcze nie uwaga - bez treści model nie
        // ma czego zmienić i zgadywałby.
        return t.takeIf { it.split(Regex("\\s+")).size >= 2 }
    }

    /**
     * Poprawka maila w NOWEJ turze (poza nasłuchem "Wysłać?"). Ostrzej niż
     * [correction]: "zmień motyw na ciemny", "usuń ostatnią notatkę" czy
     * "mniej więcej ile to kosztuje?" to polecenia, nie uwagi do szkicu
     * (przegląd 22.11). Wymagamy jednoznacznego czasownika redakcji albo
     * słowa o mailu.
     */
    fun correctionInNewTurn(text: String): String? {
        val t = correction(text) ?: return null
        val lower = t.lowercase()
            .replace('ą', 'a').replace('ć', 'c').replace('ę', 'e').replace('ł', 'l')
            .replace('ń', 'n').replace('ó', 'o').replace('ś', 's').replace('ź', 'z').replace('ż', 'z')
        val jednoznaczne = listOf(
            "popraw", "dopisz", "przeredaguj", "przepisz", "napisz raczej", "napisz ze",
            "dodaj ze", "dodaj, ze", "niech bedzie", "skroc", "wydluz"
        ).any { lower.startsWith(it) }
        val oMailu = Regex("""\b(mail\w*|tresc\w*|temat\w*|wiadomosc\w*|odpowiedz\w*|szkic\w*)\b""").containsMatchIn(lower)
        return t.takeIf { jednoznaczne || oMailu }
    }

    private val CORRECTION_STARTS = listOf(
        "popraw", "zmien", "zamien", "dopisz", "dodaj ze", "dodaj, ze", "usun", "wytnij",
        "skroc", "wydluz", "napisz raczej", "napisz ze", "zamiast", "niech bedzie",
        "bardziej", "mniej", "temat", "inaczej", "przeredaguj", "przepisz"
    )

    /**
     * Pytanie zadawane na głos: adresat, temat, treść - i dopiero na końcu
     * „Wysłać?”. Kolejność ma znaczenie: decyzja pada po usłyszeniu całości,
     * a nie po pierwszym słowie.
     */
    fun confirmationSpeech(recipient: String, subject: String, body: String, isReply: Boolean): String =
        buildString {
            append(if (isReply) "Odpowiedź do: " else "Mail do: ").append(recipient).append(". ")
            if (subject.isNotBlank()) append("Temat: ").append(subject.trim().trimEnd('.')).append(". ")
            append("Treść: ").append(body.trim())
            if (!body.trim().endsWith('.') && !body.trim().endsWith('?') && !body.trim().endsWith('!')) append('.')
            append(" Wysłać?")
        }

    /** Ta sama treść w okienku na telefonie - tam w wierszach. */
    fun confirmationMessage(recipient: String, email: String, subject: String, body: String): String =
        buildString {
            append("Do: ").append(recipient)
            if (!recipient.equals(email, ignoreCase = true)) append(" <").append(email).append('>')
            append("\nTemat: ").append(subject.ifBlank { "(bez tematu)" })
            append("\n\n").append(body)
        }

    /**
     * Polecenie dla modelu: popraw szkic według uwagi. Odpowiedź w dwóch
     * oznaczonych wierszach, żeby dało się ją rozebrać bez zgadywania.
     */
    fun revisePrompt(recipient: String, subject: String, body: String, remark: String): String =
        """
        Poprawiasz szkic maila według uwagi użytkownika. Zachowaj jego ton i
        pisz w pierwszej osobie, krótko. Zmień tylko to, o co prosi uwaga.
        Nie dodawaj podpisu ani pozdrowień, jeśli ich nie było.

        Adresat: $recipient
        TEMAT: $subject
        TREŚĆ:
        $body

        Uwaga użytkownika: $remark

        Odpowiedz WYŁĄCZNIE w formacie:
        TEMAT: <temat>
        TREŚĆ: <treść maila>
        """.trimIndent()

    /**
     * Rozbiera odpowiedź modelu na temat i treść.
     *
     * @return para (temat, treść) albo `null`, gdy odpowiedź nie ma treści -
     *   wtedy zostaje poprzedni szkic, a nie pusty mail
     */
    fun parseRevision(reply: String?, previousSubject: String): Pair<String, String>? {
        val text = reply?.trim()?.removeSurrounding("```")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val subjectMatch = Regex("""(?im)^\s*\**temat\**\s*:\s*(.*)$""").find(text)
        val bodyMatch = Regex("""(?is)\**tre[śsŚS][ćcĆC]\**\s*:\s*(.*)$""").find(text)
        val subject = subjectMatch?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: previousSubject
        val body = bodyMatch?.groupValues?.get(1)?.trim()
            // Model, który zignorował format, oddał zwykle samą treść.
            ?: if (subjectMatch == null) text else null
        if (body.isNullOrBlank()) return null
        return subject to body
    }

    /** Czy wypowiedź była ciszą (nic nie usłyszano). */
    fun isSilence(heard: String?): Boolean = heard.isNullOrBlank()

    /**
     * Czy zdanie mówi o poczcie - „odpowiedz na ostatni MAIL od Ani”. Takie
     * zdanie nie może trafić do odpowiadania na SMS-y i komunikatory.
     */
    fun mentionsMail(text: String): Boolean {
        val lower = norm(text)
        return MAIL_WORD.containsMatchIn(lower)
    }

    private val MAIL_WORD = Regex("""(?<![a-z])(e-?mail\w*|mail\w*|meil\w*|gmail\w*|poczt\w*)""")

    private fun norm(s: String): String = s.lowercase()
        .replace("ą", "a").replace("ć", "c").replace("ę", "e")
        .replace("ł", "l").replace("ń", "n").replace("ó", "o")
        .replace("ś", "s").replace("ź", "z").replace("ż", "z")
        .replace(Regex("\\s+"), " ").trim()
}
