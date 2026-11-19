package pl.victor.app.messages

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import pl.victor.app.data.SettingsRepository

/**
 * Łapie powiadomienia z komunikatorów i przekazuje je do [MessageInbox].
 *
 * Wymaga zgody "Dostęp do powiadomień", którą użytkownik daje sam w
 * ustawieniach systemu (przycisk w Ustawieniach aplikacji). Bez niej system
 * tej usługi nie uruchamia w ogóle.
 */
class NotificationReaderService : NotificationListenerService() {

    override fun onListenerConnected() {
        MessageInbox.ustawPołączenie(true)
        Log.i(TAG, "Dostęp do powiadomień aktywny")
    }

    override fun onListenerDisconnected() {
        MessageInbox.ustawPołączenie(false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { MessageInbox.usuń(sbn.key) }
        if (sbn.packageName == pl.victor.app.navigation.MapsNavigation.PAKIET_MAP) {
            runCatching { pl.victor.app.navigation.MapsNavigation.usunięte(sbn.key) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { obsłuż(sbn) }.onFailure { Log.w(TAG, "Nie udało się odczytać powiadomienia", it) }
    }

    private fun obsłuż(sbn: StatusBarNotification) {
        val settings = SettingsRepository.getInstance(this)
        // Prowadzenie Google Maps - stałe powiadomienie z następnym manewrem.
        // Przed filtrem wiadomości: to nie wiadomość i ma własny przełącznik.
        if (sbn.packageName == pl.victor.app.navigation.MapsNavigation.PAKIET_MAP) {
            if (settings.isNavPromptsEnabled()) obsłużNawigację(sbn)
            return
        }
        if (!settings.isMessageReadingEnabled()) return
        if (sbn.packageName == packageName) return
        if (sbn.packageName !in settings.getMessageApps()) return
        val n = sbn.notification
        // Podsumowanie grupy ("3 nowe wiadomości") i stałe powiadomienia
        // ("WhatsApp Web jest aktywny") to nie są wiadomości.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return

        val extras = n.extras
        val ostatnia = ostatniaZRozmowy(extras)
        val tekst = (ostatnia?.first ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (tekst.isEmpty() || PODSUMOWANIE.containsMatchIn(tekst.lowercase())) return
        if (MessageInbox.toNaszaOdpowiedź(tekst)) return
        val tytuł = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val grupa = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val rozmowa = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val nadawca = (ostatnia?.second ?: tytuł).ifBlank { return }
        val pokazanyNadawca = if (grupa && !rozmowa.isNullOrBlank() && rozmowa != nadawca) {
            "$nadawca w grupie $rozmowa"
        } else {
            nadawca
        }
        val odpowiedz = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        MessageInbox.przyjmij(
            IncomingMessage(
                klucz = sbn.key,
                pakiet = sbn.packageName,
                aplikacja = MessagingApps.nazwa(sbn.packageName) ?: sbn.packageName,
                nadawca = pokazanyNadawca,
                tekst = tekst,
                czasMs = sbn.postTime,
                możnaOdpowiedzieć = odpowiedz != null,
                rozmowaGrupowa = grupa
            ),
            odpowiedz
        )
    }

    /**
     * Powiadomienie Map. Prowadzenie ma kategorię "navigation" i jest stałe -
     * inne powiadomienia Map (np. udostępnianie lokalizacji) pomijamy.
     */
    private fun obsłużNawigację(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_NAVIGATION && !sbn.isOngoing) return
        val e = n.extras
        val stan = pl.victor.app.navigation.MapsNotificationParser.rozbierz(
            tytuł = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            tekst = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            podtekst = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            terazMs = System.currentTimeMillis()
        ) ?: return
        pl.victor.app.navigation.MapsNavigation.przyjmij(sbn.key, stan)
    }

    /**
     * (tekst, nadawca) ostatniej wiadomości ze stylu rozmowy - dokładniejsze
     * niż tytuł powiadomienia, który w grupie jest nazwą grupy.
     */
    private fun ostatniaZRozmowy(extras: Bundle): Pair<String, String?>? {
        @Suppress("DEPRECATION")
        val wiadomości: Array<Parcelable>? = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val b = wiadomości?.lastOrNull() as? Bundle ?: return null
        val tekst = b.getCharSequence("text")?.toString() ?: return null
        val nadawca = b.getCharSequence("sender")?.toString()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                (b.getParcelable<Parcelable>("sender_person") as? Person)?.name?.toString()
            } else {
                null
            }
        return tekst to nadawca
    }

    companion object {
        private const val TAG = "NotificationReader"

        /** "2 nowe wiadomości", "5 new messages" - zbiorcze, nie treść. */
        private val PODSUMOWANIE = Regex("""^\d+\s+(nowe|nowych|new)\s+(wiadomo|messages)""")
    }
}
