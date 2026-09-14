package pl.victor.app.stream

/**
 * Składa obraz H.264 z pakietów RTP (RFC 6184) w postać, którą przyjmuje
 * dekoder Androida.
 *
 * ## Po co to jest
 * Bo obraz nie przychodzi kawałkami "jedna klatka - jeden pakiet". Sieć ma
 * ograniczenie na wielkość pakietu, więc klatka kluczowa bywa pocięta na
 * kilkadziesiąt fragmentów, a drobne jednostki bywają sklejone po kilka w
 * jeden pakiet. Dopóki się ich nie poskłada, nie ma czego dekodować.
 *
 * ## Trzy postacie, w których przychodzi obraz
 * - **pojedyncza jednostka** (typ 1-23) - cały NAL w jednym pakiecie
 * - **STAP-A** (typ 24) - kilka krótkich jednostek sklejonych, każda
 *   poprzedzona dwubajtową długością
 * - **FU-A** (typ 28) - jedna jednostka pocięta na fragmenty; pierwszy ma
 *   znacznik początku, ostatni znacznik końca, a właściwy typ siedzi w drugim
 *   bajcie nagłówka i trzeba go odtworzyć
 *
 * ## Czemu to w ogóle działa, skoro media3 odmawia
 * Bo parametry obrazu (SPS i PPS) okulary wysyłają W STRUMIENIU, jako zwykłe
 * jednostki typu 7 i 8 - a nie w opisie sesji, gdzie media3 ich wymaga. Dla
 * dekodera Androida to jest w porządku: dostaje je tą samą drogą co resztę i
 * konfiguruje się sam. Cała "niezgodność" polegała na tym, w którym miejscu
 * protokołu ich szukać.
 *
 * Wyjście jest w postaci Annex-B: każda jednostka poprzedzona `00 00 00 01`.
 * Tego oczekuje `MediaCodec`.
 */
class H264Depacketizer {

    /** Fragmenty bieżącej jednostki FU-A, zbierane do znacznika końca. */
    private val pending = java.io.ByteArrayOutputStream()

    /** Czy jesteśmy w środku składania pociętej jednostki. */
    private var assembling = false

    /** Ile pakietów odrzuciliśmy jako niezrozumiałe - do dziennika. */
    var dropped: Int = 0
        private set

    /** Ile jednostek wydaliśmy. */
    var produced: Int = 0
        private set

    /** Czy widzieliśmy już parametry obrazu - bez nich dekoder nie ruszy. */
    var sawParameterSets: Boolean = false
        private set

    /**
     * Wrzuca ładunek jednego pakietu RTP i zwraca gotowe jednostki.
     *
     * @param payload ładunek RTP, czyli pakiet BEZ nagłówka RTP
     * @return zero, jedna albo kilka jednostek w postaci Annex-B
     */
    fun push(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) {
            dropped++
            return emptyList()
        }
        return when (val type = payload[0].toInt() and 0x1F) {
            in SINGLE_NAL_RANGE -> listOf(emit(payload))
            TYPE_STAP_A -> splitStapA(payload)
            TYPE_FU_A -> assembleFuA(payload)
            else -> {
                // Nie znamy - pomijamy TEN pakiet, nie cały strumień. To ta sama
                // zasada, przez którą powstał ten plik: nieznane nie jest błędem.
                dropped++
                emptyList()
            }
        }
    }

    /** Zaczyna od nowa - po zerwaniu połączenia zebrane fragmenty są bezwartościowe. */
    fun reset() {
        pending.reset()
        assembling = false
    }

    private fun emit(nal: ByteArray): ByteArray {
        val type = nal[0].toInt() and 0x1F
        if (type == TYPE_SPS || type == TYPE_PPS) sawParameterSets = true
        produced++
        val out = ByteArray(START_CODE.size + nal.size)
        START_CODE.copyInto(out)
        nal.copyInto(out, START_CODE.size)
        return out
    }

    /**
     * Rozdziela kilka jednostek sklejonych w jeden pakiet.
     *
     * Po bajcie nagłówka idą pary: dwubajtowa długość (starszy bajt pierwszy) i
     * tyle bajtów treści. Uszkodzona długość kończy rozbiór tego pakietu - to,
     * co udało się wyjąć wcześniej, zostaje.
     */
    private fun splitStapA(payload: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var offset = 1
        while (offset + 2 <= payload.size) {
            val length = ((payload[offset].toInt() and 0xFF) shl 8) or
                (payload[offset + 1].toInt() and 0xFF)
            offset += 2
            if (length <= 0 || offset + length > payload.size) {
                dropped++
                break
            }
            out += emit(payload.copyOfRange(offset, offset + length))
            offset += length
        }
        return out
    }

    /**
     * Skleja jednostkę pociętą na fragmenty.
     *
     * Pierwszy fragment ma ustawiony bit początku i to przy nim odtwarzamy
     * prawdziwy nagłówek jednostki: ważność bierzemy z nagłówka FU, a typ z
     * jego drugiego bajtu. Ostatni ma bit końca i dopiero on wydaje wynik.
     *
     * Fragment przychodzący BEZ rozpoczętego składania jest pomijany - to
     * znaczy, że początek gdzieś przepadł, a sklejanie od środka dałoby
     * dekoderowi śmieci udające klatkę.
     */
    private fun assembleFuA(payload: ByteArray): List<ByteArray> {
        if (payload.size < 2) {
            dropped++
            return emptyList()
        }
        val indicator = payload[0].toInt() and 0xFF
        val header = payload[1].toInt() and 0xFF
        val start = header and 0x80 != 0
        val end = header and 0x40 != 0
        val nalType = header and 0x1F

        if (start) {
            pending.reset()
            // Bity ważności (nal_ref_idc) zostają z nagłówka FU, typ z drugiego bajtu.
            pending.write((indicator and 0xE0) or nalType)
            assembling = true
        } else if (!assembling) {
            dropped++
            return emptyList()
        }
        pending.write(payload, 2, payload.size - 2)

        if (!end) return emptyList()
        val nal = pending.toByteArray()
        pending.reset()
        assembling = false
        if (nal.isEmpty()) {
            dropped++
            return emptyList()
        }
        return listOf(emit(nal))
    }

    private companion object {
        /** Znacznik początku jednostki w postaci Annex-B. */
        val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** Typy, które są całą jednostką same w sobie. */
        val SINGLE_NAL_RANGE = 1..23

        const val TYPE_SPS = 7
        const val TYPE_PPS = 8
        const val TYPE_STAP_A = 24
        const val TYPE_FU_A = 28
    }
}

/**
 * Nagłówek pakietu RTP (RFC 3550) - tyle, ile trzeba, żeby dostać się do treści.
 *
 * Nagłówek ma stałe dwanaście bajtów, po nich opcjonalnie lista nadawców i
 * opcjonalne rozszerzenie. Pominięcie któregokolwiek z nich znaczy, że do
 * dekodera trafiłoby kilka bajtów nagłówka udających obraz.
 */
object RtpPacket {

    /** Ładunek pakietu albo `null`, gdy pakiet jest za krótki lub uszkodzony. */
    fun payload(packet: ByteArray): ByteArray? {
        if (packet.size < FIXED_HEADER) return null
        val version = (packet[0].toInt() and 0xC0) ushr 6
        if (version != 2) return null

        val csrcCount = packet[0].toInt() and 0x0F
        val hasExtension = packet[0].toInt() and 0x10 != 0
        var offset = FIXED_HEADER + csrcCount * 4
        if (offset > packet.size) return null

        if (hasExtension) {
            // Rozszerzenie: dwa bajty profilu, dwa bajty długości LICZONEJ W
            // SŁOWACH CZTEROBAJTOWYCH - nie w bajtach. Pomyłka tutaj przesuwa
            // początek obrazu i psuje każdą klatkę po cichu.
            if (offset + 4 > packet.size) return null
            val words = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                (packet[offset + 3].toInt() and 0xFF)
            offset += 4 + words * 4
            if (offset > packet.size) return null
        }

        val padding = packet[0].toInt() and 0x20 != 0
        var end = packet.size
        if (padding) {
            val padLength = packet[packet.size - 1].toInt() and 0xFF
            if (padLength <= 0 || end - padLength < offset) return null
            end -= padLength
        }
        if (offset >= end) return null
        return packet.copyOfRange(offset, end)
    }

    /** Numer typu ładunku - ten sam, który stoi w `m=` w opisie sesji. */
    fun payloadType(packet: ByteArray): Int? =
        if (packet.size < FIXED_HEADER) null else packet[1].toInt() and 0x7F

    private const val FIXED_HEADER = 12
}
