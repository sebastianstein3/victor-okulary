package pl.victor.app.hermes

import pl.victor.app.memory.UserFacts
import pl.victor.app.notes.Notes

/**
 * Tryb "Hermes jako główny mózg" - które zdania Victor oddaje Hermesowi zamiast
 * obsługiwać je sam: fakty o użytkowniku, notatki, kalendarz, poczta, Dysk.
 *
 * ## Co zostaje w Victorze (celowo)
 * - **Wysyłanie** maili, SMS-ów i połączenia: dziś mają potwierdzenie głosem z
 *   telefonu, a Hermes przez API nie ma narzędzia do pytania o zgodę na takie
 *   rzeczy (dostaje tylko zgody na niebezpieczne polecenia). Dlatego "wyślij
 *   maila do Ani" idzie starą drogą.
 * - Komendy urządzenia (głośność, kamera, tłumaczenie, parking, nawigacja,
 *   dom, odjazdy) - szybkie, działają bez sieci i bez komputera.
 *
 * ## Co dostaje Hermes
 * Zdanie owinięte krótkim poleceniem, które mówi mu, GDZIE ma coś zapisać
 * (pamięć o użytkowniku, plik `Notatki.md` w folderze roboczym), żeby efekt był
 * ten sam przy każdym wywołaniu, a nie zależał od jego humoru.
 */
object HermesMózg {

    /** Cokolwiek, co zawiera czasownik wysyłania - zostaje po stronie Victora. */
    private val WYSYŁANIE = Regex(
        """\b(wyślij|wyslij|wysłać|wyslac|napisz do|odpisz|odpowiedz na|zadzwoń|zadzwon|sms)\b""",
        RegexOption.IGNORE_CASE
    )

    private val GOOGLE = Regex(
        """\b(kalendarz\w*|\bmail(e|a|em|u|ach)?\b|e-?mail\w*|gmail\w*|skrzynk\w* (odbiorcz\w*|mailow\w*)|""" +
            """google drive|dysk\w* google|z dysku|na dysku|notebook ?lm)\b""",
        RegexOption.IGNORE_CASE
    )

    const val DOPISEK_GOOGLE =
        "(Użyj swoich narzędzi Google: kalendarz, poczta, Dysk. Nie wysyłaj maili ani wiadomości i nie usuwaj " +
            "niczego bez wyraźnej prośby w tym poleceniu.)"

    /** @return polecenie dla Hermesa albo `null`, gdy zdanie zostaje w Victorze */
    fun rozpoznaj(wypowiedź: String): HermesPolecenie? {
        val t = wypowiedź.trim()
        if (t.isEmpty()) return null

        UserFacts.extract(t)?.let { fakt ->
            return polecenie(
                "Zapamiętaj na stałe w swojej pamięci o użytkowniku ten fakt: „$fakt”. " +
                    "Jeśli taki fakt już masz albo przeczy wcześniejszemu, zaktualizuj go. " +
                    "Potwierdź jednym krótkim zdaniem."
            )
        }
        UserFacts.extractForget(t)?.let { co ->
            return polecenie(
                "Usuń ze swojej pamięci o użytkowniku wszystko, co dotyczy: „$co”. " +
                    "Powiedz jednym zdaniem, co usunąłeś; jeśli nic takiego nie było, powiedz to."
            )
        }
        if (UserFacts.isListRequest(t)) {
            return polecenie(
                "Powiedz krótko (3-5 zdań), co pamiętasz o użytkowniku - z własnej pamięci. Niczego nie zmyślaj."
            )
        }
        Notes.extract(t)?.let { treść ->
            return polecenie(
                "Dopisz notatkę do pliku Notatki.md w folderze roboczym (utwórz plik, jeśli go nie ma) jako nową linię " +
                    "„- [data i godzina] treść”. Treść notatki: „$treść”. Potwierdź jednym krótkim zdaniem, bez ścieżki."
            )
        }
        if (Notes.isListRequest(t)) {
            return polecenie(
                "Przeczytaj plik Notatki.md z folderu roboczego i powiedz krótko pięć ostatnich notatek. " +
                    "Jeśli pliku nie ma, powiedz, że notatek jeszcze nie ma."
            )
        }
        if (!WYSYŁANIE.containsMatchIn(t) && GOOGLE.containsMatchIn(t)) {
            return polecenie("$t\n$DOPISEK_GOOGLE")
        }
        return null
    }

    private fun polecenie(tekst: String) = HermesPolecenie(tekst, jawne = false)
}
