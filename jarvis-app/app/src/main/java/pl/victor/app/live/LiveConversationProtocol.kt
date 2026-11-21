package pl.victor.app.live

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pl.victor.app.translation.LiveTranslationProtocol
import pl.victor.app.translation.LiveTranslationProtocol.Usługa
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie

/**
 * Rozmowa na żywo z asystentem przez Gemini Live - sam protokół, bez sieci i
 * dźwięku.
 *
 * ## Po co
 * Zwykła tura to cztery kroki po kolei: nasłuch do końca zdania, transkrypcja,
 * model, synteza mowy. Każdy czeka na poprzedni, więc odpowiedź przychodzi po
 * kilku sekundach, a w słowo wejść się nie da - asystent mówi do końca.
 * Gemini Live słucha strumienia i odpowiada głosem od razu; gdy człowiek
 * zaczyna mówić, serwer sam przerywa odpowiedź (`interrupted`).
 *
 * ## Co jest wspólne z tłumaczeniem na żywo
 * Ten sam BidiGenerateContent: adres, ramka `realtimeInput` z PCM 16 kHz,
 * odpowiedź PCM 24 kHz i rozbiór wiadomości serwera - wszystko z
 * [LiveTranslationProtocol]. Różni się tylko `setup`: zwykły model rozmowy,
 * głos, instrukcja systemowa (persona) i transkrypcja wejścia, z której
 * rozpoznajemy "koniec rozmowy".
 *
 * ## Język
 * Dokumentacja Live API (ai.google.dev, listopad 2026): modele z natywnym
 * dźwiękiem same wybierają język i NIE przyjmują `speechConfig.languageCode`.
 * Polski wymuszamy więc instrukcją systemową, a nie polem konfiguracji -
 * nieznane pole w `setup` kończy sesję błędem, zanim padnie słowo.
 *
 * Narzędzi (function calling) tu nie ma - model w tym trybie tylko rozmawia.
 */
object LiveConversationProtocol {

    /** Model Gemini Live do rozmowy - jedyne miejsce z jego nazwą. */
    const val MODEL = "gemini-3.8-live"

    /**
     * Gotowy głos Gemini (`prebuiltVoiceConfig.voiceName`). Głosy są
     * wielojęzyczne - mówią po polsku tym samym brzmieniem. Spokojny, niski
     * głos pasuje do V.I.C.T.O.R.-a.
     */
    const val GŁOS = "Charon"

    /** Wejście: PCM 16-bit mono 16 kHz, jak w tłumaczeniu przez Gemini. */
    val PRÓBKOWANIE_WEJŚCIA: Int = Usługa.GEMINI.próbkowanieWejścia

    /** Adres WebSocketu - ten sam co w tłumaczeniu przez Gemini. */
    fun adres(klucz: String): String = LiveTranslationProtocol.adres(Usługa.GEMINI, klucz)

    /**
     * Pierwsza wiadomość: `setup`.
     *
     * - `inputAudioTranscription` - tekst tego, co mówi użytkownik: z niego
     *   rozpoznajemy "koniec rozmowy" i liczymy ciszę.
     * - `outputAudioTranscription` - tekst odpowiedzi: do dziennika i do
     *   kontekstu zwykłych tur po rozmowie.
     * - `contextWindowCompression.slidingWindow` - bez niego sesja audio
     *   kończy się po ok. 15 minutach, gdy okno kontekstu się zapełni.
     */
    fun ustawienia(instrukcja: String, głos: String = GŁOS): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$MODEL")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", głos) }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", instrukcja) } }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
        }
    }.toString()

    /** Kawałek dźwięku z mikrofonu (PCM 16 kHz). */
    fun dźwięk(pcm: ByteArray): String = LiveTranslationProtocol.dźwięk(Usługa.GEMINI, pcm)

    /** Wiadomość serwera -> zdarzenia (z [Zdarzenie.Przerwane] przy wejściu w słowo). */
    fun rozbierz(wiadomość: String): List<Zdarzenie> = LiveTranslationProtocol.rozbierz(Usługa.GEMINI, wiadomość)

    /**
     * Instrukcja systemowa sesji: persona V.I.C.T.O.R.-a, zasady rozmowy
     * głosowej i krótki kontekst (czas, miejsce, jeśli jest).
     *
     * Celowo BEZ katalogu akcji `[[ACTION: ...]]` ze zwykłej tury: w tym trybie
     * nikt nie czyta znaczników, a model, który je zna, wypowiadałby je na głos.
     */
    fun instrukcja(persona: String, czas: String, miejsce: String?): String = buildString {
        append(persona.trim()).append("\n\n")
        append(ZASADY_ROZMOWY).append("\n\n")
        append(czas.trim()).append('\n')
        miejsce?.trim()?.takeIf { it.isNotEmpty() }?.let { append('\n').append(it).append('\n') }
    }

    /**
     * Krótki opis miejsca do instrukcji - sama nazwa, bez współrzędnych: w
     * rozmowie wystarcza "gdzie jestem", a liczby model mógłby czytać na głos.
     */
    fun miejsce(opis: String?, miasto: String?): String? {
        val nazwa = opis?.trim()?.takeIf { it.isNotEmpty() } ?: miasto?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return "=== GDZIE JEST UŻYTKOWNIK (GPS telefonu) ===\n$nazwa"
    }

    const val ZASADY_ROZMOWY =
        "=== ROZMOWA GŁOSOWA NA ŻYWO ===\n" +
            "Rozmawiasz z użytkownikiem głosem, na żywo, przez jego okulary albo telefon. " +
            "Mów ZAWSZE po polsku, chyba że użytkownik wyraźnie poprosi o inny język. " +
            "Odpowiadaj krótko, jak w rozmowie: zwykle jedno do trzech zdań, bez list, " +
            "nagłówków, emotikon i znaczników. Użytkownik może wejść Ci w słowo - wtedy " +
            "przestań i słuchaj.\n" +
            "W tym trybie nie masz narzędzi: nie zrobisz zdjęcia, nie wyślesz wiadomości, " +
            "nie zadzwonisz, nie ustawisz alarmu ani przypomnienia i nie sprawdzisz " +
            "internetu. Gdy użytkownik o to prosi, powiedz krótko, że trzeba powiedzieć " +
            "„koniec rozmowy” i poprosić o to zwykłym poleceniem. Nie zmyślaj bieżących " +
            "danych (pogoda, wiadomości, kursy, godziny otwarcia)."
}
