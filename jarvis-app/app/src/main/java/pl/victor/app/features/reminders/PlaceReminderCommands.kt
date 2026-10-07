package pl.victor.app.features.reminders

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.victor.app.data.SettingsRepository

/**
 * Komendy przypomnień w miejscach i zapisu domu/pracy - warstwa 0, bez modelu.
 * Wydzielone z orkiestratora.
 */
class PlaceReminderCommands(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val reminders: PlaceReminders,
    /** Mówi odpowiedź i kończy turę z tym tekstem. */
    private val odpowiedz: (String) -> Unit
) {
    private val logic = PlaceReminderLogic

    /** @return `true`, gdy zdanie było jedną z tych komend i zostało obsłużone */
    fun obsłuż(text: String): Boolean {
        logic.zapisDomuLubPracy(text)?.let { nazwa ->
            scope.launch {
                val pozycja = pl.victor.app.proactive.LocationContext.currentPosition(context)
                odpowiedz(
                    if (pozycja == null) {
                        "Nie znam teraz położenia - sprawdź, czy lokalizacja jest włączona."
                    } else {
                        settings.savePlace(nazwa, pozycja.first, pozycja.second, System.currentTimeMillis())
                        if (nazwa == logic.DOM) "Zapamiętałem, gdzie jest dom." else "Zapamiętałem, gdzie jest praca."
                    }
                )
            }
            return true
        }
        logic.prośba(text)?.let { prośba ->
            val r = reminders.dodaj(prośba)
            val brakMiejsca = (r.cel as? Cel.Zapisane)?.takeIf { settings.getPlace(it.nazwa) == null }
            odpowiedz(
                logic.potwierdzenie(r) + if (brakMiejsca != null) {
                    val gdzie = if (brakMiejsca.nazwa == logic.DOM) "w domu, powiedz: tu jest mój dom" else "w pracy, powiedz: tu pracuję"
                    " Nie wiem jeszcze, gdzie to jest - gdy będziesz $gdzie."
                } else {
                    ""
                }
            )
            return true
        }
        if (logic.czyLista(text)) {
            val lista = reminders.lista.value
            odpowiedz(
                if (lista.isEmpty()) {
                    "Nie masz przypomnień związanych z miejscem."
                } else {
                    "Przypomnienia: " + lista.joinToString(". ") { "${it.co} - przy: ${it.gdzie}" } + "."
                }
            )
            return true
        }
        if (logic.czyUsuńWszystkie(text)) {
            reminders.usuńWszystkie()
            odpowiedz("Usunąłem przypomnienia związane z miejscem.")
            return true
        }
        return false
    }
}
