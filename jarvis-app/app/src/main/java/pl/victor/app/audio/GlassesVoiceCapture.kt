package pl.victor.app.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.victor.app.ble.VictorManager
import java.io.ByteArrayOutputStream

/**
 * Zbiera dźwięk z mikrofonu okularów przesyłany po BLE i składa z niego WAV.
 *
 * ## Skąd to się bierze
 * Aplikacja producenta nie używa mikrofonu okularów przez profil zestawu
 * słuchawkowego. Odbiera pakiety `AiChatResponse` po BLE, dekoduje je jako Opus
 * i podaje prosto do rozpoznawania mowy. Tutaj jest to samo, z jedną różnicą:
 * zamiast do rozpoznawania mowy PCM idzie do modelu multimodalnego jako WAV -
 * model i tak potrafi wysłuchać pytania i od razu na nie odpowiedzieć, więc
 * jeden krok mniej i jedna rzecz mniej do zepsucia.
 *
 * ## Dlaczego to nie jest ścieżka domyślna
 * Bo nie wiadomo z góry, czy dany egzemplarz w ogóle nadaje tym kanałem i czy
 * `subData` to goły pakiet Opusa. Klasa jest napisana tak, żeby jej
 * niepowodzenie NIC nie psuło: gdy nie przyjdzie ani jeden pakiet albo dekoder
 * odmówi, [stop] zwraca wynik z zerami, a wołający leci swoją dotychczasową
 * drogą (klasyczny Bluetooth albo mikrofon telefonu).
 *
 * [Result.describe] mówi wprost, na czym stanęło - łącznie z kształtem
 * pierwszego pakietu, bo to jedyna rzecz, której nie da się ustalić bez sprzętu.
 */
class GlassesVoiceCapture(private val glasses: VictorManager) {

    private val decoder = OpusDecoder()

    /**
     * Surowe pakiety odłożone do rozkodowania PO zakończeniu nagrania.
     *
     * ## Dlaczego nie dekodujemy na bieżąco
     * `onPacket` przychodzi z wątku obsługi BLE. Dekodowanie potrafi na nim
     * zaczekać - a przy zgadywaniu ramkowania nawet 150 ms na wariant, cztery
     * warianty, osiem pakietów. Zablokowanie wątku BLE na kilka sekund to nie
     * jest "wolniej": to gubione pakiety i zrywane połączenie z okularami.
     * Odkładanie kosztuje pamięć, której nie ma czego żałować - Opus przy
     * 48 kHz to jakieś 8 kB na sekundę, a nagranie trwa kilkanaście sekund.
     */
    private val packets = mutableListOf<ByteArray>()
    private val lock = Any()

    private var decodedPackets = 0
    private var failedPackets = 0

    /**
     * O ile bajtów od początku pakietu zaczyna się ładunek Opusa.
     *
     * `-1` znaczy "jeszcze nie wiem". Producent może opakowywać dane własnym
     * nagłówkiem (numer sekwencji, długość), a tego nie da się ustalić inaczej
     * niż empirycznie - dekoder albo przyjmie ładunek, albo nie. Sprawdzamy
     * więc kilka typowych przesunięć na pierwszych pakietach i zapamiętujemy to,
     * które zadziałało. Zgadywanie kosztuje ułamek sekundy raz na nagranie, a
     * bez niego cała ścieżka po BLE stoi lub upada na jednym założeniu.
     */
    private var payloadOffset = -1

    /**
     * Stały rozmiar pojedynczego pakietu Opusa wewnątrz pakietu BLE, albo `-1`
     * gdy pakiet BLE niesie dokładnie jeden pakiet Opusa.
     *
     * ## Dlaczego to w ogóle istnieje
     * Oficjalna aplikacja producenta dekoduje ten strumień z ustawieniami
     * `hasHead=false, packetSize=40` - czyli surowe pakiety Opusa o STAŁEJ
     * długości 40 bajtów, bez nagłówka. Jeden pakiet BLE mieści ich kilka, bo
     * ma do 244 bajtów ładunku.
     *
     * Bez tego podziału dekoder dostawał sklejkę kilku pakietów jako jeden i
     * rozkodowywał TYLKO PIERWSZY - reszta przepadała po cichu. Efekt był
     * dokładnie taki, jak zgłoszono: model dostawał nagranie i odpowiadał, że
     * nie słyszy w nim pytania, bo z każdej sekundy mowy zostawał ułamek.
     */
    private var packetSize = -1
    private var probeAttempts = 0
    private var startedAtMs = 0L
    private var active = false

    /** Kiedy przyszedł ostatni pakiet - patrz [awaitSpeechEnd]. */
    @Volatile
    private var lastPacketAtMs = 0L

    /**
     * Ile milisekund FAKTYCZNEJ mowy już przyszło.
     *
     * ## Dlaczego nie liczba pakietów
     * Bo pakiet to nie sekunda. Na tym sprzęcie jeden pakiet BLE niesie jedną
     * ramkę Opusa, czyli 20 ms - więc próg "15 pakietów" znaczył 0,3 s. Krótki
     * pisk na starcie plus sekunda ciszy wyglądały wtedy jak skończona
     * wypowiedź i tura kończyła się, ZANIM użytkownik zdążył cokolwiek
     * powiedzieć. Zgłoszono to dokładnie tak: "z okularów wychodzi tylko
     * urywek dźwięku 0,2-0,3 sekundy".
     *
     * Sumujemy odstępy między kolejnymi pakietami, ale tylko te krótkie -
     * długa przerwa to cisza, a nie mowa, i nie ma jej po co wliczać.
     */
    @Volatile
    private var voicedMs = 0L

    private var previousPacketAtMs = 0L

    /**
     * Ile mowy przyszło dotąd z okularów - do decyzji podejmowanych NA ŻYWO,
     * w trakcie nasłuchu.
     *
     * Potrzebne, żeby orkiestrator mógł rozstrzygnąć, który mikrofon właściwie
     * słucha. Gdy telefon leży w kieszeni, a łącze SCO nie stoi, systemowe
     * rozpoznawanie mowy zbiera stłumiony bełkot z mikrofonu telefonu i zwraca
     * przypadkowe słowa - a to wygrywało wyścig z okularami, które słyszą
     * dobrze. Zgłoszone jako "często nie rozumie, co się mówi".
     */
    val voicedMsSoFar: Long get() = synchronized(lock) { voicedMs }

    /**
     * Podpina się pod strumień i zaczyna ODKŁADAĆ pakiety.
     *
     * Dekodowanie idzie dopiero w [stop] - patrz [packets].
     *
     * @return `false`, gdy urządzenie nie ma dekodera Opusa - wtedy i tak
     *   zbieramy pakiety, bo sam ich licznik jest już odpowiedzią na pytanie
     *   "czy okulary nadają"
     */
    fun start(): Boolean = synchronized(lock) {
        if (active) return true
        packets.clear()
        decodedPackets = 0
        failedPackets = 0
        payloadOffset = -1
        probeAttempts = 0
        startedAtMs = System.currentTimeMillis()
        lastPacketAtMs = 0L
        previousPacketAtMs = 0L
        voicedMs = 0L
        active = true

        val decoderOk = decoder.start()
        // Liczniki w VictorManager celowo NIE są tu zerowane: należą do pomiaru
        // w diagnostyce, a wybudzenie okularów w jego trakcie jest normalnym
        // przebiegiem (instrukcja na ekranie wprost o to prosi). Ta klasa liczy
        // własne pakiety, więc nie potrzebuje cudzych.
        //
        // Z ZALEGŁOŚCIĄ: okulary nadają od chwili wciśnięcia przycisku, a tu
        // jesteśmy dopiero po ~960 ms (okno na podwójne kliknięcie plus
        // rozruch). Pakiety z tego odstępu dotąd przepadały razem z pierwszymi
        // słowami pytania - patrz [pl.victor.app.ble.MicBacklog].
        val backlog = glasses.addMicStreamListenerWithBacklog(packetListener)
        adoptBacklog(backlog)
        decoderOk
    }

    /**
     * Wstawia dźwięk sprzed startu na POCZĄTEK nagrania i cofa zegary tury.
     *
     * ## Czemu można po prostu dopisać pakiety na początek
     * Bo ta klasa podczas zbierania NIC nie dekoduje - [packetListener] tylko
     * odkłada bajty, a dekoder rusza dopiero w `stop()`. Nie ma więc stanu
     * dekodera, który kolejność mogłaby popsuć wstecz; liczy się wyłącznie to,
     * żeby lista była w kolejności przyjścia. Zaległość przychodzi już w tej
     * kolejności i jest w całości STARSZA od czegokolwiek, co dojdzie dalej,
     * bo została odcięta w tej samej chwili, w której podpięliśmy odbiornik.
     *
     * ## Czemu zegary trzeba cofnąć, a nie zostawić
     * Bo pakiety niosą własne znaczniki czasu, a wykrywanie końca wypowiedzi
     * liczy [voicedMs] z ODSTĘPÓW między nimi. Policzone od „teraz" wyszłyby
     * zerowe (cała zaległość wpadłaby w jednej chwili), czyli mowa sprzed
     * startu nie liczyłaby się wcale i nasłuch trwałby dłużej, niż trzeba -
     * dokładnie ta usterka, którą [awaitSpeechEnd] ma leczyć.
     *
     * [startedAtMs] też idzie wstecz, bo człowiek zaczął mówić wtedy, a nie
     * teraz; inaczej karencja [SpeechEnd.MIN_LISTEN_MS] liczyłaby się od
     * połowy zdania.
     */
    private fun adoptBacklog(backlog: List<pl.victor.app.ble.MicBacklog.Packet>) {
        if (backlog.isEmpty()) return
        synchronized(lock) {
            if (!active) return
            // NA POCZĄTEK, nie na koniec. Między powrotem z
            // `addMicStreamListenerWithBacklog` a tym miejscem odbiornik jest
            // już podpięty, więc wątek BLE mógł zdążyć dołożyć świeży pakiet.
            // Dopisanie zaległości na koniec ustawiłoby wtedy dźwięk sprzed
            // startu ZA dźwiękiem po starcie - a pomieszana kolejność ramek to
            // dla dekodera Opusa szum nie do odróżnienia od zerwanego łącza.
            // Wstawienie na początek jest poprawne zawsze: zaległość została
            // odcięta w chwili podpięcia, więc jest starsza od wszystkiego, co
            // odbiornik może dostać.
            packets.addAll(0, backlog.map { it.payload })
            // Czas mowy liczymy z WŁASNYCH odstępów zaległości (n-1 przerw).
            // Gdyby liczyć go od „teraz", wyszłoby zero - cała zaległość
            // wpada w jednej chwili - czyli mowa sprzed startu nie liczyłaby
            // się wcale i nasłuch trwałby dłużej, niż trzeba.
            for (i in 1 until backlog.size) {
                voicedMs += SpeechEnd.voicedGap(backlog[i].atMs - backlog[i - 1].atMs)
            }
            // maxOf, bo świeży pakiet z wyścigu opisanego wyżej jest NOWSZY niż
            // cała zaległość - cofnięcie tych znaczników kazałoby wykrywaniu
            // ciszy liczyć od przeszłości. Tracimy przy tym najwyżej jedną
            // przerwę między zaległością a pierwszym świeżym pakietem, czyli
            // kilkadziesiąt milisekund, i to w stronę OSTROŻNĄ: mniej
            // policzonej mowy znaczy dłuższy nasłuch, nie ucięte pytanie.
            val lastBacklogAtMs = backlog.last().atMs
            previousPacketAtMs = maxOf(previousPacketAtMs, lastBacklogAtMs)
            lastPacketAtMs = maxOf(lastPacketAtMs, lastBacklogAtMs)
            // Człowiek zaczął mówić wtedy, a nie teraz - inaczej karencja
            // [SpeechEnd.MIN_LISTEN_MS] liczyłaby się od połowy zdania.
            startedAtMs = minOf(startedAtMs, backlog.first().atMs)
        }
    }

    /**
     * Odpina się od strumienia, NIC nie dekodując.
     *
     * Musi być zwykłą funkcją, nie `suspend`: wołający sprząta po turze w bloku
     * `finally`, a ten wykonuje się także po ANULOWANIU. Wywołanie funkcji
     * zawieszalnej w anulowanej korutynie natychmiast rzuca - subskrypcja BLE
     * zostałaby wtedy zarejestrowana na zawsze, bo nikt by jej nie zdjął.
     * Przerwana tura nie ma czego dekodować, więc to wystarcza.
     */
    fun detach() {
        detachAndTake() ?: return
        // Instancji MediaCodec jest w systemie skończona liczba. Przerwana tura
        // nie ma czego dekodować, więc dekoder zwalniamy od razu - inaczej po
        // kilku przerwaniach `createDecoderByType` zaczęłoby odmawiać.
        decoder.release()
    }

    /** Zdejmuje subskrypcję i oddaje zebrane pakiety; `null`, gdy już nieaktywne. */
    private fun detachAndTake(): List<ByteArray>? {
        val collected: List<ByteArray>
        synchronized(lock) {
            if (!active) return null
            active = false
            collected = packets.toList()
            packets.clear()
        }
        runCatching { glasses.removeMicStreamListener(packetListener) }
            .onFailure { Log.w(TAG, "Odpięcie strumienia nie powiodło się", it) }
        return collected
    }

    /**
     * Wołane z wątku BLE - musi być szybkie, więc tylko odkłada pakiet.
     *
     * Trzymane w polu, bo odpięcie wymaga TEJ SAMEJ referencji: lambda zapisana
     * w miejscu wywołania byłaby przy usuwaniu innym obiektem.
     */
    private val packetListener: (ByteArray) -> Unit = { packet ->
        synchronized(lock) {
            if (active && packets.size < MAX_BUFFERED_PACKETS) {
                packets.add(packet.copyOf())
                val now = System.currentTimeMillis()
                if (previousPacketAtMs > 0L) {
                    voicedMs += SpeechEnd.voicedGap(now - previousPacketAtMs)
                }
                previousPacketAtMs = now
                lastPacketAtMs = now
            }
        }
    }

    /**
     * Zawiesza się do chwili, w której użytkownik SKOŃCZYŁ mówić.
     *
     * ## Po co, skoro jest rozpoznawanie mowy
     * Bo ono nie zawsze słyszy. Gdy telefon leży zablokowany w kieszeni,
     * rozpoznawanie czeka do końca swojego limitu - piętnastu sekund - mimo że
     * użytkownik powiedział wszystko po trzech. Z zewnątrz wygląda to dokładnie
     * tak, jak zgłoszono: "przestaję mówić, a nagranie dalej długo trwa", po
     * czym odpowiedź przychodzi z wielkim opóźnieniem.
     *
     * Okulary nadają pakiety tylko wtedy, gdy w mikrofonie coś jest, więc cisza
     * w strumieniu jest lepszym sygnałem końca wypowiedzi niż zegar. Gdy okulary
     * nie nadają w ogóle, ta funkcja NIGDY nie wraca - i o to chodzi: wtedy
     * jedynym sędzią zostaje rozpoznawanie mowy ze swoim limitem.
     */
    suspend fun awaitSpeechEnd(
        silenceMs: Long = SpeechEnd.SILENCE_ENDS_SPEECH_MS,
        minVoicedMs: Long = SpeechEnd.MIN_VOICED_MS,
        graceMs: Long = SpeechEnd.MIN_LISTEN_MS,
        maxMs: Long = SpeechEnd.MAX_SPEECH_MS
    ) {
        while (true) {
            val now = System.currentTimeMillis()
            val (voiced, lastAt) = synchronized(lock) { voicedMs to lastPacketAtMs }
            if (SpeechEnd.endsOnSilence(
                    voicedMs = voiced,
                    sinceLastPacketMs = if (lastAt > 0L) now - lastAt else -1L,
                    sinceStartMs = now - startedAtMs,
                    silenceMs = silenceMs,
                    minVoicedMs = minVoicedMs,
                    graceMs = graceMs
                )
            ) {
                Log.i(TAG, "Strumień ucichł po ${voiced} ms mowy - koniec wypowiedzi")
                return
            }
            // Twardy limit, bo cisza w strumieniu to NIE jest pewny sygnał.
            // Okulary potrafią nadawać bez przerwy, także wtedy, gdy nikt nie
            // mówi - a wtedy warunek wyżej nie spełni się nigdy i o końcu tury
            // decyduje dopiero piętnastosekundowy zegar rozpoznawania mowy.
            // Zgłoszono to wprost: "już coś powiem, a okulary nadal bardzo
            // długo nasłuchują".
            if (now - startedAtMs >= maxMs) {
                Log.i(TAG, "Limit czasu wypowiedzi ($maxMs ms) - kończę nasłuch")
                return
            }
            delay(SILENCE_POLL_MS)
        }
    }

    /**
     * Dekoduje pakiet, po drodze ustalając, gdzie w nim zaczyna się Opus.
     *
     * Gdy przesunięcie jest już znane, to zwykłe wywołanie dekodera. Gdy nie -
     * próbuje kilku typowych i zapamiętuje pierwsze, które dało dźwięk. Po
     * nieudanej próbie dekoder trzeba wyczyścić, bo śmieciowy pakiet potrafi
     * zostawić go w stanie odrzucającym także poprawne dane.
     */
    /**
     * Ustala [payloadOffset], próbując rozkodować pierwsze pakiety.
     *
     * PCM z tej fazy jest wyrzucany - to tylko rozpoznanie ramkowania. Zysk z
     * zachowania go (ułamek sekundy) nie jest wart poszarpanego początku
     * wypowiedzi, który zostaje po karmieniu dekodera śmieciami.
     */
    private fun probePayloadOffset(collected: List<ByteArray>) {
        if (payloadOffset >= 0) return
        for (packet in collected) {
            if (decodeWithKnownOrGuessedOffset(packet, 0L) != null) return
            if (probeAttempts >= MAX_PROBE_PACKETS) return
        }
    }

    /**
     * Rozmiar pojedynczego pakietu Opusa albo `-1`, gdy dzielenie nie ma sensu.
     *
     * Dzielimy TYLKO wtedy, gdy każdy odebrany ładunek jest wielokrotnością
     * kandydata i przynajmniej jeden jest dłuższy niż on sam. Bez tego drugiego
     * warunku strumień, w którym każdy pakiet BLE niesie dokładnie jeden pakiet
     * Opusa, zostałby "podzielony" na jedną część - niby bez szkody, ale z
     * fałszywym wpisem w raporcie diagnostycznym.
     */
    private fun detectPacketSize(collected: List<ByteArray>): Int {
        val offset = payloadOffset.coerceAtLeast(0)
        return OpusFraming.detectPacketSize(collected.map { it.size - offset })
    }

    /**
     * Rozbija pakiet BLE na pakiety Opusa gotowe do podania dekoderowi.
     *
     * Gdy ramkowanie jest nieznane albo pakiet niesie tylko jeden ładunek,
     * zwraca jeden element - czyli zachowuje się tak jak przed tą zmianą.
     */
    private fun framesOf(packet: ByteArray): List<ByteArray> {
        val offset = payloadOffset.coerceAtLeast(0)
        if (packet.size <= offset) return emptyList()
        val body = if (offset == 0) packet else packet.copyOfRange(offset, packet.size)
        return OpusFraming.split(body, packetSize)
    }

    private fun decodeWithKnownOrGuessedOffset(packet: ByteArray, timeUs: Long): ByteArray? {
        if (payloadOffset >= 0) {
            if (packet.size <= payloadOffset) return null
            val body = if (payloadOffset == 0) packet
                else packet.copyOfRange(payloadOffset, packet.size)
            return decoder.decode(body, timeUs)
        }
        if (probeAttempts >= MAX_PROBE_PACKETS) return null
        probeAttempts++

        for (offset in CANDIDATE_OFFSETS) {
            if (packet.size <= offset + MIN_OPUS_PAYLOAD) continue
            val body = if (offset == 0) packet else packet.copyOfRange(offset, packet.size)
            // Dłuższy limit na wyjście: dekoder dopiero się rozkręca, a krótkie
            // czekanie odrzuciłoby POPRAWNE przesunięcie tylko dlatego, że
            // pierwsza porcja PCM nie zdążyła wyjść.
            val decoded = decoder.decode(body, timeUs, OpusDecoder.PROBE_TIMEOUT_US)
            if (decoded != null) {
                payloadOffset = offset
                Log.i(TAG, "Ładunek Opusa zaczyna się o $offset B od początku pakietu")
                return decoded
            }
            // Podaliśmy dekoderowi śmieci - to normalny etap zgadywania, ale
            // trzeba go z tego wyprowadzić, zanim spróbujemy następnego wariantu.
            if (!decoder.recover()) return null
        }
        return null
    }

    /**
     * Odpina się od strumienia, rozkodowuje odłożone pakiety i zwraca wynik.
     *
     * Całe dekodowanie dzieje się TUTAJ, a nie w trakcie nagrywania - patrz
     * [packets]. Wołający jest korutyną, więc idzie na wątek roboczy: kilka
     * sekund dźwięku to kilkaset milisekund pracy, której nie chcemy na wątku
     * głównym ani tym bardziej na wątku BLE.
     */
    suspend fun stop(): Result = withContext(Dispatchers.Default) {
        val collected = detachAndTake() ?: return@withContext Result()

        // FAZA 1 - ustal ramkowanie. Najpierw gdzie zaczyna się Opus w pakiecie
        // (nagłówek producenta), potem czy pakiet BLE niesie JEDEN pakiet Opusa,
        // czy kilka sklejonych.
        if (decoder.isReady) {
            probePayloadOffset(collected)
            packetSize = detectPacketSize(collected)
            // Zgadywanie karmi dekoder śmieciami, więc przed właściwym
            // dekodowaniem musi wystartować od czysta - inaczej pierwsze
            // sekundy wypowiedzi wychodzą poszarpane.
            decoder.recover()
        }

        // FAZA 2 - dekoduj wszystko od początku, już bez zgadywania.
        val pcm = ByteArrayOutputStream()
        collected.forEach { packet ->
            framesOf(packet).forEach { frame ->
                // Znacznik czasu liczony z już zebranego PCM, nie z zegara: dekoder
                // oczekuje ciągłej osi czasu strumienia, a nie momentu odbioru
                // pakietu (BLE potrafi je dostarczyć nierówno).
                val timeUs = pcm.size().toLong() * 1_000_000L /
                    (OpusDecoder.SAMPLE_RATE.toLong() * BYTES_PER_SAMPLE)
                val decoded = if (decoder.isReady) decoder.decode(frame, timeUs) else null
                if (decoded != null) {
                    pcm.write(decoded)
                    decodedPackets++
                } else {
                    failedPackets++
                }
            }
        }
        decoder.release()

        val samples = pcm.toByteArray()
        Result(
            packets = collected.size,
            bytes = collected.sumOf { it.size },
            decodedPackets = decodedPackets,
            failedPackets = failedPackets,
            packetSizes = collected.map { it.size },
            firstPacketHex = collected.firstOrNull()?.let { hex(it, HEX_PREVIEW_BYTES) },
            pcmBytes = samples.size,
            pcm = samples.takeIf { it.isNotEmpty() },
            wav = if (samples.isEmpty()) null else WavWriter.wrap(samples, OpusDecoder.SAMPLE_RATE),
            durationMs = System.currentTimeMillis() - startedAtMs,
            payloadOffset = payloadOffset,
            packetSize = packetSize
        ).also { Log.i(TAG, it.describe()) }
    }

    /**
     * Co przyszło z okularów i co się z tym udało zrobić.
     *
     * Rozdzielenie `packets` od `decodedPackets` jest tu najważniejsze: to
     * właśnie ta para odróżnia "okulary nie nadają" od "nadają, ale to nie jest
     * goły Opus" - dwie awarie, które bez pomiaru wyglądają identycznie.
     */
    data class Result(
        val packets: Int = 0,
        val bytes: Int = 0,
        val decodedPackets: Int = 0,
        val failedPackets: Int = 0,
        val packetSizes: List<Int> = emptyList(),
        val firstPacketHex: String? = null,
        val pcmBytes: Int = 0,
        /**
         * Same próbki 16-bit mono, bez nagłówka WAV - tym karmimy rozpoznawanie
         * mowy na urządzeniu (patrz [pl.victor.app.conversation.SpeechToText.transcribe]).
         */
        val pcm: ByteArray? = null,
        val wav: ByteArray? = null,
        val durationMs: Long = 0L,
        /** Gdzie w pakiecie zaczyna się Opus; `-1`, gdy nie udało się ustalić. */
        val payloadOffset: Int = -1,
        /**
         * Stała długość pojedynczego pakietu Opusa wewnątrz pakietu BLE albo
         * `-1`, gdy pakiet BLE niesie dokładnie jeden pakiet Opusa.
         */
        val packetSize: Int = -1
    ) {
        /** Czy jest z czego zrobić pytanie do modelu. */
        val hasAudio: Boolean get() = wav != null && pcmBytes >= MIN_USEFUL_PCM_BYTES

        val audioSeconds: Double
            get() = WavWriter.durationSeconds(pcmBytes, OpusDecoder.SAMPLE_RATE)

        fun describe(): String = buildString {
            if (packets == 0) {
                append("Okulary nie przysłały ani jednego pakietu audio po BLE.")
                return@buildString
            }
            append("Pakiety: ").append(packets).append(" (").append(bytes).append(" B)")
            val distinct = packetSizes.distinct().sorted()
            if (distinct.size <= SIZES_IN_SUMMARY) {
                append(", rozmiary: ").append(distinct.joinToString("/"))
            } else {
                append(", rozmiary ").append(distinct.first()).append("-").append(distinct.last())
            }
            append('\n')
            firstPacketHex?.let { append("Pierwszy pakiet: ").append(it).append('\n') }
            when {
                decodedPackets == 0 ->
                    append("Dekoder Opusa nie przyjął ANI JEDNEGO pakietu - to nie jest ")
                        .append("goły strumień Opusa albo urządzenie nie ma dekodera.")
                failedPackets > 0 ->
                    append("Rozkodowano ").append(decodedPackets).append(" z ")
                        .append(packets).append(" pakietów; ")
                        .append("%.1f".format(audioSeconds)).append(" s dźwięku.")
                else ->
                    append("Rozkodowano wszystko: ").append("%.1f".format(audioSeconds))
                        .append(" s dźwięku (").append(pcmBytes).append(" B PCM).")
            }
            if (payloadOffset > 0) {
                append("\nPakiety mają ").append(payloadOffset)
                    .append("-bajtowy nagłówek producenta przed Opusem.")
            }
            // Ta linijka jest ważniejsza, niż wygląda: pakiet BLE mieści kilka
            // pakietów Opusa, a bez podziału dekoder brał tylko pierwszy i
            // reszta przepadała bez błędu.
            if (packetSize > 0) {
                append("\nJeden pakiet BLE niesie kilka pakietów Opusa po ")
                    .append(packetSize).append(" B - dzielimy je przed dekodowaniem.")
            }
        }
    }

    private fun hex(bytes: ByteArray, limit: Int): String =
        bytes.take(limit).joinToString(" ") { "%02X".format(it) } +
            if (bytes.size > limit) " ..." else ""

    companion object {
        private const val TAG = "GlassesVoiceCapture"
        private const val BYTES_PER_SAMPLE = 2
        private const val HEX_PREVIEW_BYTES = 16
        private const val SIZES_IN_SUMMARY = 6

        /**
         * Poniżej tego nie ma sensu wysyłać nagrania do modelu - 0,3 s to nawet
         * nie jedno słowo, a zapytanie i tak kosztuje.
         */
        private const val MIN_USEFUL_PCM_BYTES = 28_800

        /**
         * Przesunięcia ładunku, których warto spróbować: goły pakiet, bajt typu
         * lub numeru sekwencji, dwubajtowa długość, czterobajtowy nagłówek.
         */
        /**
         * Gdzie NAPRAWDĘ zaczyna się Opus w pakiecie z okularów.
         *
         * ## To jest odpowiedź na "mikrofon okularów nie słyszy mowy"
         * Aplikacja producenta czyta ten strumień jako
         * `copyOfRange(subData, 6, długość)` - stała szóstka, bez zgadywania.
         * `subData` to dokładnie te same bajty, które dostajemy my.
         *
         * Myśmy tego przesunięcia nie znali i zgadywali z listy `0, 1, 2, 4`.
         * Zgadywanie musiało wybrać źle, bo SZÓSTKI NA TEJ LIŚCIE NIE BYŁO -
         * a Opus jest tolerancyjny i z przesunięcia 0 rozkodowywał śmieci
         * jako poprawny dźwięk. Stąd wynik nie do rozszyfrowania z samych
         * liczników: w dzienniku z 12 września dwadzieścia tur po kolei ma
         * `rozkodowanych=456 odrzuconych=0 przesunięcie=0`, a model na każde
         * pytanie odpowiada, że słyszy tylko kroki. Pakiety schodziły co do
         * sztuki - tyle że dekoder dostawał je przesunięte o sześć bajtów.
         */
        private const val KNOWN_PAYLOAD_OFFSET = 6

        /**
         * Przesunięcia próbowane, GDY [KNOWN_PAYLOAD_OFFSET] nie zadziała.
         *
         * Kolejność ma znaczenie większe niż zwykle: test "czy się rozkodowało"
         * NIE ODRÓŻNIA trafnego przesunięcia od chybionego, bo dekoder przyjmie
         * jedno i drugie. Pierwsze na liście wygrywa - i dlatego lista zaczyna
         * się od wartości, którą znamy z aplikacji producenta, a nie od zera.
         */
        private val CANDIDATE_OFFSETS = intArrayOf(KNOWN_PAYLOAD_OFFSET, 0, 1, 2, 4)

        /** Po tylu pakietach bez trafienia przestajemy zgadywać. */
        private const val MAX_PROBE_PACKETS = 8


        /** Krótszy ładunek nie jest sensownym pakietem Opusa - nie ma czego próbować. */
        private const val MIN_OPUS_PAYLOAD = 4

        /**
         * Sufit na odłożone pakiety - około minuty dźwięku.
         *
         * Nagranie trwa najwyżej kilkanaście sekund, więc tego limitu nie da się
         * osiągnąć w normalnej pracy. Jest po to, żeby zawieszony strumień (bo
         * okulary zapomniały przestać nadawać) nie zjadł pamięci telefonu.
         */
        private const val MAX_BUFFERED_PACKETS = 3_000


        private const val SILENCE_POLL_MS = 150L

    }
}
