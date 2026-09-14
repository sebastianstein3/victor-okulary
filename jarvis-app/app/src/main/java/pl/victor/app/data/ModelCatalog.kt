package pl.victor.app.data

/**
 * Lista modeli, która nadąża za rzeczywistością.
 *
 * ## Problem, który to rozwiązuje
 * Wpisana na sztywno lista modeli starzeje się w tygodniach: providerzy
 * zmieniają nazwy, wycofują warianty i dokładają nowe. Efekt jest zawsze taki
 * sam - użytkownik wybiera model, którego już nie ma, i dostaje błąd, którego
 * nie umie zinterpretować. W drugą stronę jest równie źle: nowy, lepszy model
 * istnieje od miesiąca, a w aplikacji go nie widać.
 *
 * ## Zasada
 * Prawdą jest to, co odpowiada API. Nasz katalog ([ModelRegistry]) daje tylko
 * OPISY - ładną nazwę, możliwości, notkę - dla tych modeli, które znamy.
 *
 * - model potwierdzony przez API i opisany u nas → wchodzi z pełnym opisem
 * - model potwierdzony przez API, ale nieznany → wchodzi z surowym ID
 * - model opisany u nas, którego API NIE zwraca → wypada
 * - API nie odpowiedziało (brak klucza, brak sieci) → pokazujemy katalog, bo
 *   pusty wybór jest gorszy niż wybór trochę nieaktualny
 *
 * Wydzielone i czyste, bo to jest miejsce, w którym łatwo o pomyłkę w jedną ze
 * stron: albo ukryjemy działający model, albo pokażemy nieistniejący.
 */
object ModelCatalog {

    /**
     * SKĄD wzięła się lista, którą właśnie widzi użytkownik.
     *
     * ## Czemu to musi być widoczne
     * Bo cztery zupełnie różne sytuacje dawały dotąd ten sam ekran: dwie
     * pozycje na liście i żadnej wskazówki, czy to wszystko, co provider
     * serwuje, czy tylko wszystko, co MY o nim wiemy.
     *
     * Zgłoszenie brzmiało: "pokazuje bardzo mało modeli, jakby się nie
     * aktualizowały". I nie dało się odpowiedzieć, bo brak klucza, nieudane
     * pobranie i szczera odpowiedź API "mam dwa modele" wyglądają identycznie.
     * Jedna linijka pod listą to rozstrzyga - i od razu mówi, co zrobić.
     */
    sealed interface Source {

        /** Lista przyszła z API providera. To jest stan normalny. */
        data class FromApi(val count: Int) : Source

        /** API odpowiedziało, ale nie wymieniło ani jednego modelu. */
        data object ApiEmpty : Source

        /** Nie było o co pytać - brak klucza dla tego providera. */
        data object NoApiKey : Source

        /** Pytanie poszło i się nie udało. */
        data class AskFailed(val reason: String?) : Source

        /** Zdanie do pokazania pod listą. */
        fun message(): String = when (this) {
            is FromApi ->
                "Lista prosto z API providera ($count " + plural(count) + ")."
            ApiEmpty ->
                "API providera nie wymieniło żadnego modelu - poniżej katalog " +
                    "aplikacji, który może być nieaktualny."
            NoApiKey ->
                "Bez klucza API nie ma jak zapytać providera o listę - poniżej " +
                    "katalog aplikacji, który może być nieaktualny."
            is AskFailed ->
                "Nie udało się pobrać listy z API" +
                    (reason?.let { " ($it)" } ?: "") +
                    " - poniżej katalog aplikacji, który może być nieaktualny."
        }
    }

    /** Poprawna forma rzeczownika po liczbie - inaczej "1 modeli". */
    private fun plural(count: Int): String {
        if (count == 1) return "model"
        val lastTwo = count % 100
        val last = count % 10
        val few = last in 2..4 && lastTwo !in 12..14
        return if (few) "modele" else "modeli"
    }

    /**
     * @param providerId provider, dla którego budujemy listę
     * @param liveIds ID modeli zwrócone przez API; pusta lista = "nie wiemy"
     * @param includeDeprecated czy pokazywać modele oznaczone u nas jako wycofane
     */
    fun forPicker(
        providerId: String,
        liveIds: List<String>,
        includeDeprecated: Boolean = false
    ): List<ModelInfo> {
        val known = ModelRegistry.forProvider(providerId)
            .filter { includeDeprecated || !it.deprecated }

        // Brak odpowiedzi z API to NIE jest "brak modeli". Bez klucza albo bez
        // sieci lista przychodzi pusta, a wtedy ukrycie wszystkiego zostawiłoby
        // użytkownika z pustym wyborem i bez możliwości cokolwiek ustawić.
        if (liveIds.isEmpty()) return known

        val live = liveIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val liveSet = live.toSet()

        val confirmed = known.filter { liveSet.contains(it.id) }
        val discovered = live
            .filter { id -> known.none { it.id == id } }
            .map { id -> discoveredModel(id, providerId) }

        // Opisane pierwsze: o nich umiemy powiedzieć, czym są i do czego służą.
        return confirmed + discovered.sortedByDescending { it.id }
    }

    /**
     * Model, o którym wiemy tylko tyle, że provider go serwuje.
     *
     * Możliwości zgadujemy z nazwy i tylko tam, gdzie nazwa jest jednoznaczna.
     * Fałszywe "obsługuje obrazy" kończy się wysłaniem zdjęcia do modelu
     * tekstowego i błędem zamiast odpowiedzi, więc domyślnie zakładamy mniej.
     */
    private fun discoveredModel(id: String, providerId: String): ModelInfo {
        val lower = id.lowercase()
        return ModelInfo(
            id = id,
            displayName = id,
            providerId = providerId,
            supportsVision = VISION_MARKERS.any { lower.contains(it) },
            description = "Wykryty w API providera - opisu nie mamy"
        )
    }

    /** Cząstki nazw, po których model niemal na pewno przyjmuje obrazy. */
    private val VISION_MARKERS = listOf("vision", "-vl", "vl2", "multimodal", "omni")

    /**
     * Czy zapisany wybór użytkownika nadal istnieje.
     *
     * @return ten sam model albo `null`, gdy zniknął i trzeba wrócić do domyślnego
     */
    fun keepIfStillThere(modelId: String?, available: List<ModelInfo>): String? =
        modelId?.takeIf { chosen -> available.any { it.id == chosen } }
}
