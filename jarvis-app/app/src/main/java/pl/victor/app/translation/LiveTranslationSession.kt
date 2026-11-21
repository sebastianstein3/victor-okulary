package pl.victor.app.translation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import pl.victor.app.live.LiveAudioSession
import pl.victor.app.translation.LiveTranslationProtocol.Usługa
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie

/**
 * Jedna sesja tłumaczenia na żywo: WebSocket, mikrofon telefonu, odtwarzacz.
 *
 * Połączenie, mikrofon i odtwarzacz są we wspólnym [LiveAudioSession] (ten
 * sam szkielet ma rozmowa na żywo z asystentem); tu jest tylko to, co
 * odróżnia tłumaczenie:
 *
 * - MIKROFON TELEFONU, wprost. Gdy okulary są podłączone tylko w trybie
 *   rozmowy, Android potrafi oddać nagranie z ICH mikrofonu (daleko od
 *   rozmówcy) - a tu słuchamy osoby naprzeciwko. Źródło `VOICE_RECOGNITION`,
 *   bez obróbki rozmowy telefonicznej.
 * - Przekład gra z `USAGE_MEDIA` - tą samą drogą co mowa asystenta, czyli do
 *   okularów, gdy są połączone jako słuchawki.
 */
class LiveTranslationSession(
    context: Context,
    val usługa: Usługa,
    private val klucz: String,
    private val językDocelowy: String
) : LiveAudioSession(
    context = context,
    próbkowanieWejścia = usługa.próbkowanieWejścia,
    źródłoNagrania = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    preferowaneWejście = AudioDeviceInfo.TYPE_BUILTIN_MIC,
    użycieOdtwarzania = AudioAttributes.USAGE_MEDIA
) {
    override val tag = "LiveTranslation"

    override fun adres(): String = LiveTranslationProtocol.adres(usługa, klucz)

    override fun nagłówki(): Map<String, String> = LiveTranslationProtocol.nagłówki(usługa, klucz)

    override fun ustawienia(): String = LiveTranslationProtocol.ustawienia(usługa, językDocelowy)

    override fun rozbierz(wiadomość: String): List<Zdarzenie> =
        LiveTranslationProtocol.rozbierz(usługa, wiadomość)

    override fun ramkaDźwięku(pcm: ByteArray): String = LiveTranslationProtocol.dźwięk(usługa, pcm)

    /**
     * Grzeczne zakończenie - OpenAI dosyła wtedy resztę przekładu i
     * `session.closed`. Gemini nie ma takiej wiadomości.
     */
    override fun zakończenie(): String? = LiveTranslationProtocol.zakończenie(usługa)

    companion object {
        /** Poziom RMS, od którego przekład uznajemy za słyszalny (~ -50 dBFS). */
        const val SŁYSZALNE = LiveAudioSession.SŁYSZALNE
    }
}
