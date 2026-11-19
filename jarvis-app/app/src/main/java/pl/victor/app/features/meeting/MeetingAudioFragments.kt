package pl.victor.app.features.meeting

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Podział wypowiedzi spotkania na fragmenty do rozpoznania mówców - bez I/O.
 *
 * Fragment to sklejone wypowiedzi (z [SpeakerTranscript.PRZERWA_BAJTÓW] ciszy
 * między nimi) do [maksBajtów]. Granica fragmentu wypada zawsze MIĘDZY
 * wypowiedziami, nigdy w środku zdania - a wypowiedź z [SpeechSegmenter] ma
 * najwyżej 20 s, więc zawsze mieści się w pustym fragmencie.
 */
class FragmentyNagrania(
    private val maksBajtów: Long = SpeakerTranscript.MAKS_FRAGMENT_BAJTÓW,
    private val przerwaBajtów: Int = SpeakerTranscript.PRZERWA_BAJTÓW
) {
    /** Wynik dodania wypowiedzi: czy zamknęła poprzedni fragment i ile ciszy dopisać przed nią. */
    data class Dodanie(val zamknięty: List<SpeakerTranscript.Ułożenie>?, val ciszaPrzed: Int)

    private val bieżące = mutableListOf<SpeakerTranscript.Ułożenie>()
    private var bajtów = 0L

    val pusty: Boolean get() = bieżące.isEmpty()

    fun dodaj(odStartuMs: Long, segmentBajtów: Int): Dodanie {
        var zamknięty: List<SpeakerTranscript.Ułożenie>? = null
        if (bieżące.isNotEmpty() && bajtów + przerwaBajtów + segmentBajtów > maksBajtów) {
            zamknięty = domknij()
        }
        val cisza = if (bieżące.isEmpty()) 0 else przerwaBajtów
        bajtów += cisza
        bieżące += SpeakerTranscript.Ułożenie(
            odStartuMs = odStartuMs,
            wFragmencieMs = bajtów * 1000 / SpeakerTranscript.BAJTÓW_NA_SEKUNDĘ,
            długośćMs = segmentBajtów * 1000L / SpeakerTranscript.BAJTÓW_NA_SEKUNDĘ
        )
        bajtów += segmentBajtów
        return Dodanie(zamknięty, cisza)
    }

    /** Zamyka bieżący fragment; `null`, gdy nic w nim nie ma. */
    fun domknij(): List<SpeakerTranscript.Ułożenie>? {
        if (bieżące.isEmpty()) return null
        val gotowy = bieżące.toList()
        bieżące.clear()
        bajtów = 0
        return gotowy
    }

    companion object {
        /** Cały podział naraz - do testów i do opisu w dzienniku. */
        fun podziel(
            segmenty: List<Pair<Long, Int>>,
            maksBajtów: Long = SpeakerTranscript.MAKS_FRAGMENT_BAJTÓW,
            przerwaBajtów: Int = SpeakerTranscript.PRZERWA_BAJTÓW
        ): List<List<SpeakerTranscript.Ułożenie>> {
            val f = FragmentyNagrania(maksBajtów, przerwaBajtów)
            val wynik = mutableListOf<List<SpeakerTranscript.Ułożenie>>()
            segmenty.forEach { (od, n) -> f.dodaj(od, n).zamknięty?.let { wynik += it } }
            f.domknij()?.let { wynik += it }
            return wynik
        }
    }
}

/**
 * Zapis wypowiedzi spotkania do plików fragmentów (PCM 16 kHz mono, bez
 * nagłówka) - jeden plik na fragment, w pamięci aplikacji.
 *
 * ## Po co na dysku
 * Spotkanie trzymało tylko tekst; dźwięk szedł kawałkami do przepisania i
 * znikał. Rozpoznanie mówców potrzebuje minut nagrania naraz - 7 min to
 * 13 MB, a w pamięci przez całe spotkanie to proszenie się o brak pamięci.
 * Plik fragmentu jest usuwany zaraz po udanym rozpoznaniu, reszta - na końcu
 * spotkania (patrz [MeetingRecorder.stop]).
 *
 * Nie jest wątkowo bezpieczna: woła ją tylko pętla nagrywania.
 *
 * @param plikDla numer fragmentu (od 1) -> plik
 * @param gotowy zamknięty fragment do rozpoznania
 */
class ZapisFragmentów(
    private val plikDla: (Int) -> File,
    private val gotowy: (Fragment) -> Unit,
    private val plan: FragmentyNagrania = FragmentyNagrania()
) {
    data class Fragment(val nr: Int, val plik: File, val ułożenie: List<SpeakerTranscript.Ułożenie>)

    private var nr = 0
    private var plik: File? = null
    private var wyjście: OutputStream? = null

    fun dopisz(odStartuMs: Long, pcm: ByteArray) {
        val d = plan.dodaj(odStartuMs, pcm.size)
        d.zamknięty?.let { oddaj(it) }
        val out = wyjście ?: otwórz()
        if (d.ciszaPrzed > 0) out.write(ByteArray(d.ciszaPrzed))
        out.write(pcm)
    }

    /** Zamyka ostatni fragment i oddaje go - na końcu spotkania. */
    fun domknij() {
        plan.domknij()?.let { oddaj(it) }
        zamknijPlik()
    }

    private fun otwórz(): OutputStream {
        nr++
        val f = plikDla(nr)
        f.parentFile?.mkdirs()
        plik = f
        return BufferedOutputStream(f.outputStream(), BUFOR).also { wyjście = it }
    }

    private fun oddaj(ułożenie: List<SpeakerTranscript.Ułożenie>) {
        val f = plik ?: return
        zamknijPlik()
        gotowy(Fragment(nr, f, ułożenie))
    }

    private fun zamknijPlik() {
        runCatching { wyjście?.close() }
        wyjście = null
        plik = null
    }

    private companion object {
        const val BUFOR = 64 * 1024
    }
}
