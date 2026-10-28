package pl.victor.app.data

import java.time.LocalDate

/**
 * Katalog modeli AI - jedno źródło prawdy o dostępnych modelach.
 *
 * Dlaczego hardcoded a nie pobierane z API?
 * - Nie każdy provider ma list-models endpoint
 * - Chcemy wiedzieć o deprecated zanim provider to ogłosi
 * - Fallback musi działać nawet bez internetu
 * - Statyczna analiza kodu może wykryć literówki
 *
 * Ten plik jest aktualizowany ręcznie co jakiś czas (co 2-3 miesiące).
 * Jeśli chcesz sprawdzić nowe modele, dodaj je z datą.
 */
object ModelRegistry {

    private val NOW = LocalDate.now()

    /**
     * Domyślny model dla każdego providera (pierwszy wybór przy braku preferencji).
     * Mapowanie: providerId -> modelId
     */
    val DEFAULT_MODELS = mapOf(
        "gemini" to "gemini-3.5-flash-lite",
        "openai" to "gpt-4o-mini",
        "claude" to "claude-sonnet-5-5",
        "minimax" to "MiniMax-Text-01",
        "deepseek" to "deepseek-chat"
    )

    /**
     * Wszystkie znane modele, pogrupowane po providerze.
     */
    val ALL_MODELS: List<ModelInfo> = listOf(
        // ========== DEEPSEEK ==========
        // Świadomie KRÓTKA lista: to są tylko OPISY dla modeli, o których coś
        // wiemy. Tym, co da się wybrać, rządzi odpowiedź API (ModelCatalog) -
        // nowe modele wejdą tu same, a wycofane wypadną bez naszej zmiany.
        ModelInfo(
            id = "deepseek-chat",
            displayName = "DeepSeek Chat",
            providerId = "deepseek",
            releaseDate = "2025-01",
            contextWindow = 64_000,
            supportsVision = false,
            description = "Tani i szybki model tekstowy, dobry polski"
        ),
        ModelInfo(
            id = "deepseek-reasoner",
            displayName = "DeepSeek Reasoner",
            providerId = "deepseek",
            releaseDate = "2025-01",
            contextWindow = 64_000,
            supportsVision = false,
            description = "Model rozumujący - wolniejszy, lepszy w zadaniach na myślenie"
        ),

        // ========== GOOGLE GEMINI ==========
        // Rodzina 3.x (stan na 28.10.2026). 3.5 Flash-Lite jest domyślny: działa
        // na kluczu użytkownika (dziennik z biegu 160) i jest najtańszy.
        ModelInfo(
            id = "gemini-3.5-flash-lite",
            displayName = "Gemini 3.5 Flash-Lite",
            providerId = "gemini",
            releaseDate = "2026-07",
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsAudio = true,
            supportsWebSearch = true,
            description = "Szybki i tani, domyślny - dobry do pytań głosowych"
        ),
        ModelInfo(
            id = "gemini-3.1-flash-lite",
            displayName = "Gemini 3.1 Flash-Lite",
            providerId = "gemini",
            releaseDate = "2026-05",
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsAudio = true,
            supportsWebSearch = true,
            description = "Jeszcze tańszy wariant Flash-Lite"
        ),
        ModelInfo(
            id = "gemini-3.6-flash",
            displayName = "Gemini 3.6 Flash",
            providerId = "gemini",
            releaseDate = "2026-07",
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsAudio = true,
            supportsWebSearch = true,
            description = "Mocniejszy Flash do trudniejszych pytań"
        ),
        ModelInfo(
            id = "gemini-3.8-flash",
            displayName = "Gemini 3.8 Flash",
            providerId = "gemini",
            releaseDate = "2026-09",
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsAudio = true,
            supportsWebSearch = true,
            description = "Najnowszy Flash - dobry do instrukcji krok po kroku i analizy zdjęć"
        ),
        ModelInfo(
            id = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            providerId = "gemini",
            releaseDate = "2025-06",
            deprecated = true,
            replacementId = "gemini-3.5-flash-lite",
            deprecationDate = "2026-09",
            description = "Starszy - Google ogranicza dostęp, użyj 3.5 Flash-Lite"
        ),
        ModelInfo(
            id = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            providerId = "gemini",
            releaseDate = "2025-06",
            contextWindow = 2_000_000,
            supportsVision = true,
            supportsAudio = true,
            supportsWebSearch = true,
            description = "Najlepszy model Google, głęboka analiza, dłuższe odpowiedzi"
        ),
        ModelInfo(
            id = "gemini-2.5-flash-lite",
            displayName = "Gemini 2.5 Flash-Lite",
            providerId = "gemini",
            releaseDate = "2025-07",
            deprecated = true,
            replacementId = "gemini-3.5-flash-lite",
            deprecationDate = "2026-09",
            description = "Starszy - użyj 3.5 Flash-Lite"
        ),
        // Deprecated Gemini
        ModelInfo(
            id = "gemini-1.5-flash",
            displayName = "Gemini 1.5 Flash",
            providerId = "gemini",
            releaseDate = "2024-09",
            deprecated = true,
            replacementId = "gemini-3.5-flash-lite",
            deprecationDate = "2025-09",
            description = "Wycofany - przejdź na 3.5 Flash-Lite"
        ),
        ModelInfo(
            id = "gemini-1.5-pro",
            displayName = "Gemini 1.5 Pro",
            providerId = "gemini",
            releaseDate = "2024-05",
            deprecated = true,
            replacementId = "gemini-2.5-pro",
            deprecationDate = "2025-09",
            description = "Wycofywany - przejdź na 2.5 Pro"
        ),

        // ========== OPENAI ==========
        ModelInfo(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            providerId = "openai",
            releaseDate = "2024-07",
            contextWindow = 128_000,
            supportsVision = true,
            supportsAudio = true,
            description = "Tani, szybki, dobry do prostych zadań"
        ),
        ModelInfo(
            id = "gpt-4o",
            displayName = "GPT-4o",
            providerId = "openai",
            releaseDate = "2024-05",
            contextWindow = 128_000,
            supportsVision = true,
            supportsAudio = true,
            description = "Flagowy model OpenAI, najlepszy angielski"
        ),
        ModelInfo(
            id = "gpt-4.1",
            displayName = "GPT-4.1",
            providerId = "openai",
            releaseDate = "2025-04",
            contextWindow = 1_000_000,
            supportsVision = true,
            description = "Nowa generacja, 1M tokenów kontekstu"
        ),
        ModelInfo(
            id = "gpt-4.1-mini",
            displayName = "GPT-4.1 Mini",
            providerId = "openai",
            releaseDate = "2025-04",
            contextWindow = 1_000_000,
            supportsVision = true,
            description = "Lekki GPT-4.1, 1M kontekstu, tańszy"
        ),
        // Deprecated OpenAI
        ModelInfo(
            id = "gpt-3.5-turbo",
            displayName = "GPT-3.5 Turbo",
            providerId = "openai",
            releaseDate = "2023-03",
            deprecated = true,
            replacementId = "gpt-4o-mini",
            description = "Stary model - użyj 4o-mini"
        ),
        ModelInfo(
            id = "gpt-4-turbo",
            displayName = "GPT-4 Turbo",
            providerId = "openai",
            releaseDate = "2024-04",
            deprecated = true,
            replacementId = "gpt-4o",
            description = "Stary - użyj GPT-4o"
        ),

        // ========== ANTHROPIC CLAUDE ==========
        ModelInfo(
            id = "claude-sonnet-5-5",
            displayName = "Claude Sonnet 5.5",
            providerId = "claude",
            releaseDate = "2026-09",
            contextWindow = 1_000_000,
            supportsVision = true,
            description = "Aktualny Sonnet - świetny polski, dobry do analizy"
        ),
        ModelInfo(
            id = "claude-haiku-5-5",
            displayName = "Claude Haiku 5.5",
            providerId = "claude",
            releaseDate = "2026-10",
            contextWindow = 1_000_000,
            supportsVision = true,
            description = "Najszybszy i najtańszy Claude - do krótkich pytań głosowych"
        ),
        ModelInfo(
            id = "claude-opus-5-5",
            displayName = "Claude Opus 5.5",
            providerId = "claude",
            releaseDate = "2026-09",
            contextWindow = 1_000_000,
            supportsVision = true,
            description = "Najsilniejszy z tej serii (droższy)"
        ),
        ModelInfo(
            id = "claude-sonnet-4-5",
            displayName = "Claude Sonnet 4.5",
            providerId = "claude",
            releaseDate = "2025-09",
            deprecated = true,
            replacementId = "claude-sonnet-5-5",
            deprecationDate = "2026-11-30",
            description = "Przestaje działać 30.11.2026 - użyj Sonnet 5.5"
        ),
        ModelInfo(
            id = "claude-sonnet-4-20250514",
            displayName = "Claude Sonnet 4",
            providerId = "claude",
            releaseDate = "2025-05",
            deprecated = true,
            replacementId = "claude-sonnet-5-5",
            deprecationDate = "2026-06-15",
            description = "Już nie działa - użyj Sonnet 5.5"
        ),
        ModelInfo(
            id = "claude-opus-4-1",
            displayName = "Claude Opus 4.1",
            providerId = "claude",
            releaseDate = "2025-08",
            deprecated = true,
            replacementId = "claude-opus-5-5",
            deprecationDate = "2026-08-05",
            description = "Już nie działa - użyj Opus 5.5"
        ),
        // Deprecated Claude
        ModelInfo(
            id = "claude-3-5-sonnet-20241022",
            displayName = "Claude 3.5 Sonnet",
            providerId = "claude",
            releaseDate = "2024-10",
            deprecated = true,
            replacementId = "claude-sonnet-5-5",
            description = "Stary - użyj Sonnet 5.5"
        ),
        ModelInfo(
            id = "claude-3-opus-20240229",
            displayName = "Claude 3 Opus",
            providerId = "claude",
            releaseDate = "2024-02",
            deprecated = true,
            replacementId = "claude-opus-5-5",
            description = "Stary - użyj Opus 4.1"
        ),

        // ========== MINIMAX ==========
        ModelInfo(
            id = "MiniMax-Text-01",
            displayName = "MiniMax Text-01",
            providerId = "minimax",
            releaseDate = "2024-08",
            contextWindow = 1_000_000,
            description = "Tylko tekst, duży kontekst, dobra alternatywa"
        ),
        ModelInfo(
            id = "MiniMax-VL-01",
            displayName = "MiniMax VL-01",
            providerId = "minimax",
            releaseDate = "2024-10",
            contextWindow = 1_000_000,
            supportsVision = true,
            description = "Wersja z obsługą obrazów (vision-language)"
        )
    )

    /**
     * Znajdź model po ID.
     */
    fun findById(modelId: String): ModelInfo? = ALL_MODELS.find { it.id == modelId }

    /**
     * Lista modeli dla danego providera (najnowsze pierwsze).
     */
    fun forProvider(providerId: String): List<ModelInfo> {
        return ALL_MODELS
            .filter { it.providerId == providerId }
            .sortedWith(
                compareByDescending<ModelInfo> { !it.deprecated }
                    .thenByDescending { it.releaseDate ?: "" }
            )
    }

    /**
     * Aktywne (nie-deprecated) modele dla providera.
     */
    fun activeForProvider(providerId: String): List<ModelInfo> {
        return forProvider(providerId).filter { !it.deprecated }
    }

    /**
     * Domyślny model dla providera.
     */
    fun defaultFor(providerId: String): ModelInfo? {
        val id = DEFAULT_MODELS[providerId] ?: return null
        return findById(id)
    }

    /**
     * Sprawdza czy model istnieje w naszym katalogu.
     */
    fun isKnown(modelId: String): Boolean = ALL_MODELS.any { it.id == modelId }
}
