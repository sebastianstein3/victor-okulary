package pl.victor.app.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import pl.victor.app.translation.LiveTranslationProtocol.Zdarzenie

/**
 * Jedna sesja rozmowy na żywo z asystentem (Gemini Live).
 *
 * Szkielet (WebSocket, mikrofon, odtwarzacz) jest wspólny z tłumaczeniem -
 * [LiveAudioSession]. Tu tylko to, co inne:
 *
 * - Nagrywanie zawsze ze źródła `VOICE_COMMUNICATION` - to ono włącza
 *   systemowe tłumienie echa, bez którego asystent słyszy sam siebie i
 *   przerywa własną odpowiedź.
 * - Przez SCO (okulary): mikrofon okularów wskazany wprost i odtwarzanie
 *   `USAGE_VOICE_COMMUNICATION` - inną drogą dźwięk nie wejdzie do łącza
 *   rozmowy (patrz [pl.victor.app.audio.BluetoothAudioRouter.ttsAudioAttributes]).
 * - Przez telefon: mikrofon telefonu, a odpowiedź gra jak zwykła mowa
 *   asystenta (`USAGE_MEDIA` - do okularów przez A2DP, gdy są, inaczej z
 *   głośnika telefonu). Echo odcina wtedy [BramkaEcha] w pętli.
 */
class LiveConversationSession(
    context: Context,
    private val klucz: String,
    private val instrukcja: String,
    val tryb: LiveConversationLogic.TrybDźwięku
) : LiveAudioSession(
    context = context,
    próbkowanieWejścia = LiveConversationProtocol.PRÓBKOWANIE_WEJŚCIA,
    źródłoNagrania = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    preferowaneWejście = if (tryb == LiveConversationLogic.TrybDźwięku.SCO) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    } else {
        AudioDeviceInfo.TYPE_BUILTIN_MIC
    },
    użycieOdtwarzania = if (tryb == LiveConversationLogic.TrybDźwięku.SCO) {
        AudioAttributes.USAGE_VOICE_COMMUNICATION
    } else {
        AudioAttributes.USAGE_MEDIA
    }
) {
    override val tag = "LiveConversation"

    override fun adres(): String = LiveConversationProtocol.adres(klucz)

    override fun ustawienia(): String = LiveConversationProtocol.ustawienia(instrukcja)

    override fun rozbierz(wiadomość: String): List<Zdarzenie> = LiveConversationProtocol.rozbierz(wiadomość)

    override fun ramkaDźwięku(pcm: ByteArray): String = LiveConversationProtocol.dźwięk(pcm)
}
