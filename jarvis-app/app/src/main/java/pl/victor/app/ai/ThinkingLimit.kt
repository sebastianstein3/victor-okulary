package pl.victor.app.ai

/**
 * Jak poprosić DANY model o krótsze myślenie.
 *
 * Rodzina 3.x nie daje się wyłączyć: `thinkingBudget: 0` kończy się odmową 400
 * w każdej turze (widać to w dzienniku - "ponawiam bez ograniczenia
 * myślenia" przy każdym pytaniu, czyli dodatkowy obieg i nic nie zaoszczędzone).
 * Te modele przyjmują za to POZIOM myślenia - najniższy wspólny dla Pro i
 * Flash to "low". Starsze 2.5 Flash przyjmują budżet 0, a 2.5 Pro wymaga
 * minimum 128.
 */
object ThinkingLimit {

    data class Prośba(val budżet: Int? = null, val poziom: String? = null)

    fun dla(model: String): Prośba {
        val m = model.lowercase().removePrefix("models/")
        return when {
            m.startsWith("gemini-3") -> Prośba(poziom = "low")
            m.startsWith("gemini-2.5-pro") -> Prośba(budżet = 128)
            else -> Prośba(budżet = 0)
        }
    }
}
