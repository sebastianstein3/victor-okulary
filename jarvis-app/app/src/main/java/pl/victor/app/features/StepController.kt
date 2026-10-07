package pl.victor.app.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sterowanie trybem krok po kroku - głosem i przyciskiem. Stan i logika są w
 * [StepMode] i [StepLogic]; tu jest tylko mowa i model.
 */
class StepController(
    private val scope: CoroutineScope,
    private val mów: (String) -> Unit,
    private val mówIczekaj: suspend (String) -> Unit,
    private val model: suspend (String) -> String?,
    private val dziennik: (String, Map<String, Any?>) -> Unit
) {
    val steps = StepMode()

    val aktywny: Boolean get() = steps.aktywny

    /** Kontekst dla modelu przy pytaniu w trakcie instrukcji. */
    fun kontekst(): String? = steps.stan.value?.let { StepLogic.kontekst(it) }

    /**
     * Komenda głosowa: start ("krok po kroku: ...") albo sterowanie.
     * @return `true`, gdy zdanie zostało obsłużone
     */
    fun głosem(tekst: String): Boolean {
        if (steps.aktywny) {
            StepLogic.komenda(tekst)?.let {
                komenda(it)
                return true
            }
        }
        StepLogic.prośba(tekst)?.let {
            start(it)
            return true
        }
        return false
    }

    /** Rozpisuje zadanie na kroki (model) i podaje pierwszy. */
    fun start(zadanie: String) {
        scope.launch {
            mów("Chwila, rozpisuję to na kroki.")
            val kroki = model(StepLogic.polecenie(zadanie))?.let { StepLogic.kroki(it) }.orEmpty()
            dziennik("krok po kroku: start", mapOf("kroków" to kroki.size))
            if (kroki.isEmpty()) {
                mówIczekaj("Nie udało mi się rozpisać tego na kroki. Spróbuj zapytać inaczej.")
                return@launch
            }
            steps.start(zadanie, kroki)
            mówIczekaj(
                "Mam ${kroki.size} kroków. Klik - dalej, dwa kliki - pytanie, trzy - powtórz, cztery - koniec. " +
                    steps.bieżący()
            )
        }
    }

    fun komenda(k: StepLogic.Komenda) {
        val tekst = when (k) {
            StepLogic.Komenda.DALEJ -> steps.dalej()
            StepLogic.Komenda.POWTÓRZ -> steps.bieżący()
            StepLogic.Komenda.WSTECZ -> steps.wstecz()
            StepLogic.Komenda.KONIEC -> {
                steps.stop()
                "Kończę instrukcję."
            }
        } ?: return
        mów(tekst)
    }
}
