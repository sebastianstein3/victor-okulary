package pl.victor.app.messages

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Skrzynka wiadomości z powiadomień - wspólna dla usługi, która je łapie
 * ([NotificationReaderService]), i orkiestratora, który je czyta i na nie
 * odpowiada.
 *
 * Trzyma też akcje "Odpowiedz" z powiadomień: to jedyna droga, żeby
 * odpowiedź poszła w tej samej aplikacji i tej samej rozmowie (WhatsApp nie
 * ma publicznego API do wysyłania - pole odpowiedzi w powiadomieniu ma).
 */
object MessageInbox {

    private const val TAG = "MessageInbox"

    private val logika = InboxLogic()
    private val akcjeOdpowiedzi = ConcurrentHashMap<String, Notification.Action>()

    private val _nowe = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 16)

    /** Każda NOWA wiadomość (bez powtórek tego samego powiadomienia). */
    val nowe: SharedFlow<IncomingMessage> = _nowe.asSharedFlow()

    private val _połączona = MutableStateFlow(false)

    /** Czy system podłączył naszą usługę - czyli czy zgoda na powiadomienia jest dana. */
    val połączona: StateFlow<Boolean> = _połączona.asStateFlow()

    internal fun ustawPołączenie(tak: Boolean) {
        _połączona.value = tak
    }

    @Synchronized
    internal fun przyjmij(m: IncomingMessage, odpowiedz: Notification.Action?) {
        if (odpowiedz != null) akcjeOdpowiedzi[m.klucz] = odpowiedz
        if (logika.dodaj(m)) _nowe.tryEmit(m)
    }

    @Synchronized
    fun ostatnie(n: Int = 5): List<IncomingMessage> = logika.ostatnie(n)

    @Synchronized
    fun adresat(imię: String?): IncomingMessage? = logika.adresat(imię)

    sealed class Wynik {
        object Wysłane : Wynik()
        data class Błąd(val powód: String) : Wynik()
    }

    /**
     * Wysyła odpowiedź przez pole "Odpowiedz" z powiadomienia.
     *
     * Działa, dopóki powiadomienie żyje - po jego zamknięciu (odczytaniu na
     * telefonie) aplikacja zwykle unieważnia akcję i trzeba powiedzieć to
     * wprost, zamiast udawać, że poszło.
     */
    fun odpowiedz(context: Context, m: IncomingMessage, treść: String): Wynik {
        val akcja = akcjeOdpowiedzi[m.klucz]
            ?: return Wynik.Błąd("${m.aplikacja} nie pozwala odpowiedzieć z powiadomienia")
        val wejścia = akcja.remoteInputs ?: return Wynik.Błąd("brak pola odpowiedzi")
        return try {
            val intent = Intent()
            val wyniki = Bundle()
            wejścia.forEach { wyniki.putCharSequence(it.resultKey, treść) }
            RemoteInput.addResultsToIntent(wejścia, intent, wyniki)
            akcja.actionIntent.send(context, 0, intent)
            Wynik.Wysłane
        } catch (e: Exception) {
            // CanceledException: powiadomienie zamknięte, akcja nieważna.
            Log.w(TAG, "Odpowiedź nie poszła", e)
            akcjeOdpowiedzi.remove(m.klucz)
            Wynik.Błąd("rozmowa została już zamknięta na telefonie - odpowiedz w aplikacji ${m.aplikacja}")
        }
    }
}
