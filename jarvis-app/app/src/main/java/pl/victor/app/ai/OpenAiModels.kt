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
}
