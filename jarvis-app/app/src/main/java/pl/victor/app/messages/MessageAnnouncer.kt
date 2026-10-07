package pl.victor.app.messages

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.victor.app.conversation.ContextTopics
import pl.victor.app.data.SettingsRepository

/**
 * Wiadomości z powiadomień w rozmowie: ogłaszanie nowych w okularach i
 * kontekst dla modelu ("odpowiedz jej, że...").
 *
 * Wydzielone z orkiestratora - on tylko woła [kontekst] przy zbieraniu
 * kontekstów i [start] raz na uruchomienie.
 */
class MessageAnnouncer(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val tematy: ContextTopics,
    private val okulary: () -> Boolean,
    private val cisza: () -> Boolean,
    private val mów: suspend (String) -> Unit,
    private val dziennik: (String, Map<String, Any?>) -> Unit,
    private val bezpiecznie: suspend (String, suspend () -> Unit) -> Unit
) {

    /**
     * Ostatnie wiadomości - gdy pytanie ich dotyczy albo przed chwilą jakąś
     * przeczytaliśmy. Lokalne, bez sieci: nic nie dokłada do czasu odpowiedzi.
     */
    fun kontekst(pytanie: String, wymuszone: Boolean): String? {
        if (!settings.isMessageReadingEnabled()) return null
        val ostatnie = MessageInbox.ostatnie(5)
        val dotyczy = MessagesPrompt.dotyczyWiadomości(pytanie)
        if (!tematy.dokleić(TEMAT, pytanieOTemat = dotyczy, wymuszone = wymuszone && ostatnie.isNotEmpty())) {
            return null
        }
        return MessagesPrompt.dlaModelu(ostatnie)
    }

    /**
     * Czyta nowe wiadomości - tylko w okularach (głośnik telefonu czytający
     * cudze SMS-y na głos to nie funkcja, tylko wpadka) i tylko wtedy, gdy
     * nikomu nie wchodzi w słowo.
     */
    fun start() {
        scope.launch {
            MessageInbox.nowe.collect { m ->
                bezpiecznie("ogłoszenie wiadomości") {
                    if (!settings.isMessageReadingEnabled()) return@bezpiecznie
                    if (!okulary()) return@bezpiecznie
                    val doKiedy = System.currentTimeMillis() + CZEKAJ_MS
                    while (!cisza() && System.currentTimeMillis() < doKiedy) delay(CO_ILE_MS)
                    if (!cisza()) {
                        dziennik("wiadomość nieprzeczytana - cały czas zajęte", mapOf("od" to m.nadawca))
                        return@bezpiecznie
                    }
                    dziennik(
                        "czytam wiadomość",
                        mapOf("aplikacja" to m.aplikacja, "odpowiedź" to m.możnaOdpowiedzieć)
                    )
                    // Temat otwarty: "odpowiedz jej, że..." za chwilę dostanie
                    // listę wiadomości, także przez model.
                    tematy.dokleić(TEMAT, pytanieOTemat = true)
                    mów(MessagesPrompt.ogłoszenie(m, settings.isMessageContentRead()))
                }
            }
        }
    }

    private companion object {
        const val TEMAT = "wiadomości"

        /** Jak długo czekać na ciszę, zanim wiadomość przepadnie bez czytania. */
        const val CZEKAJ_MS = 2 * 60_000L
        const val CO_ILE_MS = 500L
    }
}
