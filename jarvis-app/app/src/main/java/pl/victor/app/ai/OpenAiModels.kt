package pl.victor.app.ai

/** Różnice w parametrach między rodzinami modeli OpenAI. */
object OpenAiModels {

    /**
     * Czy ten model wymaga `max_completion_tokens` (i nie przyjmuje `temperature`):
     * rodziny GPT-5 i nowsze oraz modele rozumujące o1/o3/o4.
     */
    fun usesCompletionTokens(model: String): Boolean {
        val m = model.lowercase().removePrefix("models/")
        return Regex("""^(gpt-(\d+)(?!\d)|o\d)""").find(m)?.let { match ->
            val gpt = match.groupValues[2]
            gpt.isEmpty() || gpt.toInt() >= 5
        } ?: false
    }

    /**
     * Jak długo model ma "myśleć" przed odpowiedzią - albo `null`, gdy nie
     * wysyłamy tego pola wcale.
     *
     * GPT-6 (i 5.1+) domyślnie rozumuje na poziomie `medium`: kilka sekund
     * ciszy przed każdą odpowiedzią w okularach, a tokeny rozumowania liczą
     * się do `max_completion_tokens`, więc krótki limit potrafił zostawić
     * PUSTĄ odpowiedź. Asystent głosowy ma odpowiadać od razu.
     * Pierwsze GPT-5 (5, 5-mini, 5-nano) nie znają `none` - tam `minimal`.
     */
    fun reasoningEffort(model: String): String? {
        val m = model.lowercase().removePrefix("models/")
        val match = Regex("""^gpt-(\d+)(?:\.(\d+))?""").find(m) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull()
        return when {
            major >= 6 -> "none"
            major == 5 && minor != null && minor >= 1 -> "none"
            major == 5 -> "minimal"
            else -> null
        }
    }
}
