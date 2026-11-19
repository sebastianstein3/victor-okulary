package pl.victor.app.data

/**
 * Co zostało na telefonie po usuniętym modelu lokalnym (llama.cpp + Qwen 0.8B)
 * i jak to posprzątać - czysta logika, bez Androida, żeby dała się sprawdzić
 * testem JVM.
 *
 * Model usunięto, bo na Galaxy A23 był bezużyteczny: bez sieci wczytywał się
 * 13,8 s, liczył 19 s i nie oddał ani jednego tokena. Wymagał przy tym
 * jednorazowego pobrania circa 560 MB i bibliotek natywnych w APK. U osób,
 * które go pobrały, plik dalej zajmowałby pół gigabajta pamięci aplikacji,
 * a wybrany dostawca "local" wskazywałby na coś, czego już nie ma.
 *
 * Samo sprzątanie robią [SettingsRepository.removeLocalModelSettings]
 * (ustawienia) i [pl.victor.app.VictorApplication] (plik i kanał powiadomień).
 */
object LocalModelLeftovers {

    /** Identyfikator, pod którym model lokalny był zapisany jako dostawca AI. */
    const val OLD_PROVIDER_ID = "local"

    /** Katalog w `filesDir`, do którego trafiał plik .gguf (i jego niedokończona część .part). */
    const val MODELS_DIR = "local_models"

    /** Kanał powiadomień usługi pobierania modelu - bez sprzątania wisiałby w ustawieniach systemu. */
    const val DOWNLOAD_CHANNEL_ID = "local_model_download"

    /**
     * Klucze ustawień, które dotyczyły wyłącznie modelu lokalnego: klucz API,
     * wybrany model i czas ostatniego sprawdzenia listy modeli (przedrostki z
     * [SettingsRepository] + identyfikator dostawcy).
     */
    val ORPHAN_KEYS: List<String> = listOf(
        "api_key_$OLD_PROVIDER_ID",
        "selected_model_$OLD_PROVIDER_ID",
        "last_validation_$OLD_PROVIDER_ID"
    )

    /**
     * Kogo ustawić jako dostawcę AI zamiast modelu lokalnego.
     *
     * Pierwszy dostawca z wpisanym kluczem - człowiek, który miał wybrany model
     * offline, a obok klucz DeepSeeka, ma po aktualizacji dostać DeepSeeka, a
     * nie Gemini bez klucza i komunikat o jego braku. Gdy klucza nie ma nigdzie,
     * zostaje [default] - ekran ustawień i tak poprosi wtedy o klucz.
     *
     * @return nowy dostawca albo `null`, gdy aktywny dostawca nie był modelem
     *   lokalnym i nie ma czego zmieniać
     */
    fun replacementProvider(
        active: String?,
        order: List<String>,
        hasKey: (String) -> Boolean,
        default: String
    ): String? {
        if (active != OLD_PROVIDER_ID) return null
        return order.firstOrNull { it != OLD_PROVIDER_ID && hasKey(it) } ?: default
    }

    /** Wynik sprzątania ustawień - do dziennika diagnostycznego. */
    data class Cleanup(
        /** Dostawca ustawiony zamiast modelu lokalnego; `null` = aktywny dostawca bez zmian. */
        val newProvider: String?,
        /** Usunięte osierocone klucze ustawień. */
        val removedKeys: List<String>
    )
}
