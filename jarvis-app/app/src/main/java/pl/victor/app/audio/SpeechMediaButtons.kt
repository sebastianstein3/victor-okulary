package pl.victor.app.audio

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * Odbiera klawisze multimediów (dotknięcie zausznika, przycisk słuchawek), gdy
 * asystent MÓWI - i tylko wtedy.
 *
 * ## Po co
 * Dziennik z biegu 152, test "dotknij zausznika w trakcie historii o smoku":
 * sześćdziesiąt sekund mowy, a z okularów przyszły wyłącznie zmiany
 * głośności - ani jednej ramki BLE "ucisz". Mowa szła wtedy profilem rozmowy
 * (A2DP nie wstał), a w tym profilu dotknięcie nie jest komunikatem BLE, tylko
 * klawiszem zestawu słuchawkowego wysyłanym klasycznym Bluetooth. Android
 * oddaje taki klawisz aktywnej sesji multimediów - a my żadnej nie mieliśmy,
 * więc klawisz trafiał donikąd. W profilu multimediów dotknięcie przychodziło
 * ramką BLE i działało (przerwania o 18:03 w tym samym dzienniku).
 *
 * Sesja jest aktywna WYŁĄCZNIE w trakcie mówienia: poza nim klawisz należy do
 * odtwarzacza muzyki i nie wolno go przechwytywać.
 */
class SpeechMediaButtons(
    context: Context,
    private val onPress: (keyCode: Int) -> Unit
) {
    private val session: MediaSession? = runCatching {
        MediaSession(context.applicationContext, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val key = keyEventOf(mediaButtonIntent) ?: return false
                    if (!isInterruptKey(key.keyCode)) return false
                    // Jedno naciśnięcie to dwa zdarzenia (wciśnięcie i
                    // puszczenie), a przytrzymanie - seria powtórzeń.
                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                        onPress(key.keyCode)
                    }
                    return true
                }
            }, Handler(Looper.getMainLooper()))
        }
    }.onFailure { Log.w(TAG, "Nie udało się utworzyć sesji multimediów", it) }.getOrNull()

    /** Włącza sesję na czas mówienia i wyłącza zaraz po nim. */
    fun setSpeaking(speaking: Boolean) {
        val s = session ?: return
        runCatching {
            // Stan "gra" jest potrzebny: system oddaje klawisz sesji, która
            // ostatnio odtwarzała, a nie po prostu ostatniej aktywnej.
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                    .setState(
                        if (speaking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .build()
            )
            s.isActive = speaking
        }.onFailure { Log.w(TAG, "Sesja multimediów: zmiana stanu nie wyszła", it) }
    }

    fun release() {
        runCatching { session?.release() }
    }

    companion object {
        private const val TAG = "SpeechMediaButtons"

        /**
         * Klawisze, które znaczą "przestań". Następny/poprzedni utwór i
         * głośność do nich nie należą - głośność zostaje gestem głośności.
         */
        fun isInterruptKey(keyCode: Int): Boolean = keyCode in setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_PLAY
        )

        @Suppress("DEPRECATION")
        private fun keyEventOf(intent: Intent): KeyEvent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
    }
}
