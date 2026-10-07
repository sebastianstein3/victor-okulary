package pl.victor.app.features.meeting

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notatki ze spotkania: nagrywa mikrofonem telefonu, przepisuje na bieżąco,
 * na koniec robi podsumowanie z zadaniami i zapisuje je w notatkach.
 *
 * ## Czemu mikrofon telefonu, a nie okularów
 * Okulary nadają dźwięk tylko w trybie pytania (po przycisku albo "Hey
 * Lens") - przez godzinę spotkania nie nadają. Telefon leżący na stole
 * słyszy też rozmówców, a o nich na spotkaniu chodzi.
 *
 * ## Jak przepisuje
 * [SpeechSegmenter] tnie nagranie na wypowiedzi po pauzach, a każda idzie
 * do rozpoznawania mowy na telefonie ([przepisz]) - w trakcie spotkania, w
 * tle. Po zatrzymaniu zostaje tylko podsumowanie, a nie godzina
 * przepisywania.
 *
 * @param przepisz PCM 16 kHz mono -> tekst albo `null`
 * @param podsumuj polecenie -> notatka modelu albo `null`
 */
class MeetingRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val przepisz: suspend (ByteArray) -> String?,
    private val podsumuj: suspend (String) -> String?,
    private val dziennik: (String, Map<String, Any?>) -> Unit
) {
    sealed class Stan {
        object Bezczynny : Stan()
        data class Nagrywa(val startMs: Long) : Stan()
        object Podsumowuje : Stan()
    }

    private val _stan = MutableStateFlow<Stan>(Stan.Bezczynny)
    val stan: StateFlow<Stan> = _stan.asStateFlow()

    private val _linie = MutableStateFlow<List<MeetingLine>>(emptyList())

    /** Przepisane wypowiedzi bieżącego spotkania - na żywo do ekranu. */
    val linie: StateFlow<List<MeetingLine>> = _linie.asStateFlow()

    val store = MeetingStore(context)

    private var nagrywanie: Job? = null
    private var przepisywanie: Job? = null
    private var kolejka: Channel<Pair<Long, ShortArray>>? = null
    private var startMs = 0L
    @Volatile private var nieprzepisanych = 0

    val nagrywa: Boolean get() = _stan.value is Stan.Nagrywa

    /** @return `false`, gdy mikrofon jest niedostępny (brak zgody, zajęty). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_stan.value != Stan.Bezczynny) return false
        val bufor = AudioRecord.getMinBufferSize(HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bufor, HZ / 5 * 2)
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            dziennik("spotkanie: mikrofon niedostępny", emptyMap())
            return false
        }
        startMs = System.currentTimeMillis()
        nieprzepisanych = 0
        _linie.value = emptyList()
        _stan.value = Stan.Nagrywa(startMs)
        val k = Channel<Pair<Long, ShortArray>>(Channel.UNLIMITED)
        kolejka = k
        dziennik("spotkanie: start", emptyMap())

        przepisywanie = scope.launch {
            for ((odStartu, próbki) in k) {
                val tekst = runCatching { przepisz(SpeechSegmenter.doBajtów(próbki)) }.getOrNull()
                    ?.trim()?.takeIf { it.isNotEmpty() }
                if (tekst == null) {
                    nieprzepisanych++
                } else {
                    _linie.value = _linie.value + MeetingLine(odStartu, tekst.replaceFirstChar { it.uppercase() })
                }
            }
        }

        nagrywanie = scope.launch(Dispatchers.IO) {
            val segmenter = SpeechSegmenter(HZ)
            val ramka = ShortArray(HZ / 50) // 20 ms
            var przeczytane = 0L
            try {
                rec.startRecording()
                while (isActive && _stan.value is Stan.Nagrywa) {
                    val n = rec.read(ramka, 0, ramka.size)
                    if (n <= 0) continue
                    przeczytane += n
                    val kawałek = if (n == ramka.size) ramka.copyOf() else ramka.copyOf(n)
                    segmenter.podaj(kawałek)?.let { wypowiedź ->
                        val koniecMs = przeczytane * 1000 / HZ
                        k.trySend((koniecMs - wypowiedź.size * 1000L / HZ) to wypowiedź)
                    }
                }
                segmenter.domknij()?.let { k.trySend((przeczytane * 1000 / HZ) to it) }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                k.close()
            }
        }
        return true
    }

    /**
     * Zatrzymuje, czeka na przepisanie reszty, robi podsumowanie i zapisuje.
     *
     * @return zapisane spotkanie albo `null`, gdy nic nie nagrywało
     */
    suspend fun stop(): Meeting? {
        if (_stan.value !is Stan.Nagrywa) return null
        val koniec = System.currentTimeMillis()
        _stan.value = Stan.Podsumowuje
        nagrywanie?.join()
        przepisywanie?.join()
        val linie = _linie.value
        dziennik(
            "spotkanie: koniec nagrywania",
            mapOf("min" to (koniec - startMs) / 60_000, "wypowiedzi" to linie.size, "nieprzepisanych" to nieprzepisanych)
        )
        val podsumowanie = if (linie.isEmpty()) {
            null
        } else {
            runCatching { podsumuj(MeetingNotes.polecenie(linie, koniec - startMs)) }.getOrNull()
        }
        val m = Meeting(
            id = startMs.toString(),
            startMs = startMs,
            koniecMs = koniec,
            linie = linie,
            podsumowanie = podsumowanie,
            nieprzepisanych = nieprzepisanych
        )
        withContext(Dispatchers.IO) { store.zapisz(m) }
        _stan.value = Stan.Bezczynny
        return m
    }

    companion object {
        const val HZ = 16_000
        private const val TAG = "MeetingRecorder"
    }
}
