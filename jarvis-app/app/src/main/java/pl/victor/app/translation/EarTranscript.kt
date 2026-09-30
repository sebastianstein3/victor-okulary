package pl.victor.app.translation

/**
 * Stan panelu tłumaczenia: to, co słychać, i to, co z tego wyszło.
 *
 * ## Po co osobna klasa
 * Pętla tłumaczenia ze słuchu żyje w orkiestratorze, którego nie da się
 * uruchomić bez telefonu i okularów. Wszystko, co decyduje o tym, CO widać na
 * ekranie (który odcinek jest właśnie czytany, kiedy tekst częściowy znika,
 * ile historii trzymać, jak wygląda tekst do skopiowania), jest tutaj - i da
 * się to sprawdzić zwykłym testem.
 *
 * ## Dwa kanały, jak w aplikacji producenta
 * Gotowe odcinki ([segmenty]) i ogon, który jeszcze się zmienia
 * ([częściowyOryginał], [częściowyPrzekład]). U producenta ogon i gotowy
 * tekst wyglądają identycznie, więc słowo, które za chwilę się zmieni,
 * wygląda na pewne. U nas ekran rysuje ogon inaczej - dlatego trzymamy go
 * osobno, a nie doklejamy do ostatniego odcinka.
 *
 * Klasa jest niezmienna: każda zmiana to nowa kopia. Stan idzie przez
 * StateFlow do ekranu, a tam porównanie "czy coś się zmieniło" działa tylko
 * na nowych obiektach.
 */
data class EarTranscript(
    val trwa: Boolean = false,
    val z: String = "en",
    val na: String = "pl",
    val segmenty: List<Segment> = emptyList(),
    val częściowyOryginał: String = "",
    val częściowyPrzekład: String = "",
    /** Odcinek, który syntezator właśnie czyta - ekran go podświetla. */
    val czytanyId: Long? = null,
    val status: Status = Status.BEZCZYNNY,
    /** Ostatni komunikat dla człowieka (błąd, powód zakończenia) albo `null`. */
    val komunikat: String? = null
) {
    data class Segment(
        val id: Long,
        val oryginał: String,
        val przekład: String,
        val czasMs: Long
    )

    enum class Status { BEZCZYNNY, SŁUCHAM, TŁUMACZĘ, MÓWIĘ }

    /** Nowa sesja: pusta, z podanymi językami. */
    fun start(z: String, na: String): EarTranscript =
        EarTranscript(trwa = true, z = z, na = na, status = Status.SŁUCHAM)

    /**
     * Koniec sesji. Tekst ZOSTAJE na ekranie - tak jak u producenta: po
     * zatrzymaniu człowiek chce przeczytać, co padło, a nie patrzeć na pustkę.
     */
    fun stop(komunikat: String? = null): EarTranscript = copy(
        trwa = false,
        częściowyOryginał = "",
        częściowyPrzekład = "",
        czytanyId = null,
        status = Status.BEZCZYNNY,
        komunikat = komunikat ?: this.komunikat
    )

    /**
     * Tekst częściowy z rozpoznawania.
     *
     * Krótszy od poprzedniego NIE zastępuje go, chyba że to zupełnie inny
     * początek. Rozpoznawanie potrafi na chwilę oddać krótszą wersję tego
     * samego zdania ("dzień dobry, jak" po "dzień dobry, jak się"), a
     * cofanie się tekstu na ekranie wygląda jak błąd. Ten sam zabieg stosuje
     * aplikacja producenta.
     */
    fun zCzęściowym(tekst: String): EarTranscript {
        val nowy = tekst.trim()
        if (nowy.isEmpty()) return this
        val stary = częściowyOryginał
        val cofnięcie = nowy.length < stary.length && stary.startsWith(nowy, ignoreCase = true)
        if (cofnięcie) return this
        // Przekład częściowy należy do STAREGO ogona - przy zupełnie nowym
        // zdaniu nie pasuje już do niczego.
        val przekład = if (nowy.startsWith(stary.take(ZGODNY_POCZĄTEK), ignoreCase = true)) {
            częściowyPrzekład
        } else {
            ""
        }
        return copy(
            częściowyOryginał = nowy,
            częściowyPrzekład = przekład,
            status = Status.SŁUCHAM
        )
    }

    /** Przekład częściowy - tylko gdy wciąż pasuje do bieżącego ogona. */
    fun zCzęściowymPrzekładem(dlaOryginału: String, przekład: String): EarTranscript =
        if (dlaOryginału.isNotBlank() && częściowyOryginał.startsWith(dlaOryginału.trim(), ignoreCase = true)) {
            copy(częściowyPrzekład = przekład.trim())
        } else {
            this
        }

    /**
     * Gotowy odcinek. Ogon znika, bo właśnie się nim stał.
     *
     * Historia jest przycięta do [MAX_SEGMENTÓW]: tryb potrafi chodzić godzinę,
     * a lista na ekranie rośnie z każdym zdaniem.
     */
    fun zSegmentem(oryginał: String, przekład: String, czasMs: Long): EarTranscript {
        val id = (segmenty.lastOrNull()?.id ?: 0L) + 1
        val nowe = (segmenty + Segment(id, oryginał.trim(), przekład.trim(), czasMs))
            .takeLast(MAX_SEGMENTÓW)
        return copy(
            segmenty = nowe,
            częściowyOryginał = "",
            częściowyPrzekład = "",
            status = Status.SŁUCHAM
        )
    }

    fun czyta(id: Long?): EarTranscript =
        copy(czytanyId = id, status = if (id != null) Status.MÓWIĘ else if (trwa) Status.SŁUCHAM else Status.BEZCZYNNY)

    fun wyczyść(): EarTranscript = copy(
        segmenty = emptyList(),
        częściowyOryginał = "",
        częściowyPrzekład = "",
        czytanyId = null,
        komunikat = null
    )

    /** Cały oryginał, akapit na odcinek - górne pole panelu. */
    fun oryginałCały(): String = segmenty.joinToString("\n") { it.oryginał }

    /** Cały przekład - dolne pole panelu. */
    fun przekładCały(): String = segmenty.joinToString("\n") { it.przekład }

    /**
     * Tekst do skopiowania albo wysłania: każdy odcinek jako para, z godziną.
     * Tego u producenta brakuje - tam zapis idzie tylko do jego własnej bazy.
     */
    fun doUdostępnienia(nazwaZ: String, nazwaNa: String, godzina: (Long) -> String): String =
        buildString {
            append("Tłumaczenie: ").append(nazwaZ).append(" → ").append(nazwaNa).append("\n\n")
            segmenty.forEach { s ->
                append("[").append(godzina(s.czasMs)).append("] ").append(s.oryginał).append("\n")
                append("→ ").append(s.przekład).append("\n\n")
            }
        }.trimEnd()

    companion object {
        const val MAX_SEGMENTÓW = 200

        /** Ile znaków początku musi się zgadzać, żeby przekład ogona przetrwał. */
        private const val ZGODNY_POCZĄTEK = 6
    }
}
