package pl.victor.app.hermes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Jak potoczyła się prośba Hermesa o zgodę - to widać na karcie w panelu. */
enum class StatusZgody { CZEKA, ZEZWOLONO, ODMÓWIONO, WYGASŁA }

/** Jeden wiersz rozmowy z Hermesem w panelu. */
sealed class HermesWpis {
    abstract val id: Long
    abstract val czas: Long

    /** To, co napisałeś albo powiedziałeś ([głosem]). */
    data class Użytkownik(
        override val id: Long, override val czas: Long,
        val tekst: String, val głosem: Boolean
    ) : HermesWpis()

    /** Odpowiedź Hermesa; [trwa] dopóki dopisują się kawałki. */
    data class Hermes(
        override val id: Long, override val czas: Long,
        val tekst: String, val trwa: Boolean
    ) : HermesWpis()

    /** Co Hermes robi na komputerze ("czyta plik…"). */
    data class Narzędzie(
        override val id: Long, override val czas: Long,
        val opis: String, val zakończone: Boolean
    ) : HermesWpis()

    /** Prośba o zgodę na operację na komputerze. */
    data class Zgoda(
        override val id: Long, override val czas: Long,
        val opis: String, val requestId: String?, val status: StatusZgody
    ) : HermesWpis()

    /** Pliki i obrazy, o których Hermes wspomniał w odpowiedzi - do otwarcia w panelu. */
    data class Pliki(
        override val id: Long, override val czas: Long,
        val pliki: List<HermesPlik>
    ) : HermesWpis()

    /** Komunikat Victora (nie Hermesa): błąd, wyjaśnienie, że Hermes jest wyłączony. */
    data class Informacja(
        override val id: Long, override val czas: Long,
        val tekst: String, val błąd: Boolean
    ) : HermesWpis()
}

/**
 * Rozmowa z Hermesem pokazywana w panelu aplikacji - czysta logika, bez Androida.
 *
 * ## Co tu trafia
 * Zarówno to, co wpisujesz w panelu, jak i polecenia głosowe ("Hermes, …")
 * z okularów - panel jest więc jedynym miejscem, gdzie widać całą odpowiedź
 * (głos czyta ją na bieżąco, ale nie da się jej przewinąć ani skopiować).
 *
 * ## Prywatność
 * Rozmowa żyje TYLKO w pamięci procesu: nie ma pliku ani bazy. Zamknięcie
 * aplikacji ją kasuje (Hermes trzyma własną pamięć u siebie, na komputerze).
 * Treść nie trafia do dziennika diagnostycznego.
 *
 * ## Granice
 * Maksymalnie [limitWpisów] wierszy i [limitZnaków] znaków w jednej odpowiedzi -
 * agent potrafi wypluć logi na megabajty, a lista w Compose ma ten tekst
 * trzymać w pamięci.
 */
class HermesPanelStore(
    private val teraz: () -> Long = { System.currentTimeMillis() },
    private val limitWpisów: Int = 200,
    private val limitZnaków: Int = 20_000
) {
    private val _wpisy = MutableStateFlow<List<HermesWpis>>(emptyList())
    val wpisy: StateFlow<List<HermesWpis>> = _wpisy.asStateFlow()

    private val _trwa = MutableStateFlow(false)

    /** Czy zadanie Hermesa jest w toku (panel pokazuje wtedy "Zatrzymaj"). */
    val trwa: StateFlow<Boolean> = _trwa.asStateFlow()

    private var następnyId = 1L

    private fun id(): Long = następnyId++

    private fun zmień(f: (List<HermesWpis>) -> List<HermesWpis>) {
        var lista = f(_wpisy.value)
        if (lista.size > limitWpisów) lista = lista.takeLast(limitWpisów)
        _wpisy.value = lista
    }

    /** Nowe zadanie: dopisuje Twoją wypowiedź i oznacza rozmowę jako trwającą. */
    @Synchronized
    fun rozpocznij(tekst: String, głosem: Boolean) {
        zmień { it + HermesWpis.Użytkownik(id(), teraz(), tekst.trim(), głosem) }
        _trwa.value = true
    }

    /** Kawałek odpowiedzi - dopisuje do trwającej odpowiedzi albo zaczyna nową. */
    @Synchronized
    fun tekst(kawałek: String) {
        if (kawałek.isEmpty()) return
        zmień { lista ->
            val ostatni = lista.lastOrNull()
            if (ostatni is HermesWpis.Hermes && ostatni.trwa) {
                val nowy = (ostatni.tekst + kawałek).let {
                    if (it.length > limitZnaków) it.take(limitZnaków) + "…" else it
                }
                lista.dropLast(1) + ostatni.copy(tekst = nowy)
            } else {
                lista + HermesWpis.Hermes(id(), teraz(), kawałek.take(limitZnaków), trwa = true)
            }
        }
    }

    /**
     * Narzędzie: start dopisuje wiersz, koniec domyka ostatni niezakończony
     * wiersz o tym samym opisie (a gdy takiego nie ma - dopisuje zakończony).
     */
    @Synchronized
    fun narzędzie(opis: String, zakończone: Boolean) {
        val czysty = opis.trim().ifEmpty { "narzędzie" }
        zmień { lista ->
            // Wiersz odpowiedzi przed narzędziem jest skończony - dalszy tekst
            // zacznie nowy dymek pod narzędziem.
            val zamknięta = lista.map { if (it is HermesWpis.Hermes && it.trwa) it.copy(trwa = false) else it }
            if (!zakończone) {
                zamknięta + HermesWpis.Narzędzie(id(), teraz(), czysty, false)
            } else {
                val idx = zamknięta.indexOfLast { it is HermesWpis.Narzędzie && !it.zakończone && it.opis == czysty }
                if (idx >= 0) {
                    zamknięta.toMutableList().also {
                        it[idx] = (it[idx] as HermesWpis.Narzędzie).copy(zakończone = true)
                    }
                } else {
                    zamknięta + HermesWpis.Narzędzie(id(), teraz(), czysty, true)
                }
            }
        }
    }

    /** Prośba o zgodę; ta sama (po [requestId]) nie dopisuje się drugi raz. */
    @Synchronized
    fun zgoda(opis: String, requestId: String?) {
        zmień { lista ->
            if (requestId != null && lista.any { it is HermesWpis.Zgoda && it.requestId == requestId }) lista
            else lista.map { if (it is HermesWpis.Hermes && it.trwa) it.copy(trwa = false) else it } +
                HermesWpis.Zgoda(id(), teraz(), opis, requestId, StatusZgody.CZEKA)
        }
    }

    /** Decyzja o oczekującej zgodzie (przycisk albo głos). */
    @Synchronized
    fun rozstrzygnijZgodę(zezwól: Boolean) {
        zmień { lista ->
            val idx = lista.indexOfLast { it is HermesWpis.Zgoda && it.status == StatusZgody.CZEKA }
            if (idx < 0) lista
            else lista.toMutableList().also {
                it[idx] = (it[idx] as HermesWpis.Zgoda).copy(
                    status = if (zezwól) StatusZgody.ZEZWOLONO else StatusZgody.ODMÓWIONO
                )
            }
        }
    }

    /** Pliki z odpowiedzi Hermesa (puste - bez wiersza). */
    @Synchronized
    fun pliki(lista: List<HermesPlik>) {
        if (lista.isEmpty()) return
        zmień { it + HermesWpis.Pliki(id(), teraz(), lista) }
    }

    /** Komunikat Victora (nie odpowiedź Hermesa). */
    @Synchronized
    fun informacja(tekst: String, błąd: Boolean = false) {
        zmień { it + HermesWpis.Informacja(id(), teraz(), tekst, błąd) }
    }

    /**
     * Koniec zadania (udany, z błędem albo przerwany): domyka wszystko, co
     * jeszcze "trwa", a wiszącą zgodę oznacza jako wygasłą - nie ma już komu
     * odpowiedzieć.
     */
    @Synchronized
    fun zakończ(błąd: String? = null) {
        zmień { lista ->
            val domknięta = lista.map {
                when {
                    it is HermesWpis.Hermes && it.trwa -> it.copy(trwa = false)
                    it is HermesWpis.Narzędzie && !it.zakończone -> it.copy(zakończone = true)
                    it is HermesWpis.Zgoda && it.status == StatusZgody.CZEKA -> it.copy(status = StatusZgody.WYGASŁA)
                    else -> it
                }
            }
            if (błąd.isNullOrBlank()) domknięta
            else domknięta + HermesWpis.Informacja(id(), teraz(), błąd, błąd = true)
        }
        _trwa.value = false
    }

    /** "Wyczyść rozmowę" w panelu - nie rusza zadania, które trwa. */
    @Synchronized
    fun wyczyść() {
        _wpisy.value = if (_trwa.value) {
            // Zadanie w toku: zostawiamy wszystko od ostatniej Twojej wypowiedzi,
            // żeby odpowiedź nie została sierotą bez pytania.
            val od = _wpisy.value.indexOfLast { it is HermesWpis.Użytkownik }
            if (od >= 0) _wpisy.value.drop(od) else emptyList()
        } else {
            emptyList()
        }
    }

    /** Cała rozmowa jako tekst do skopiowania. */
    fun jakoTekst(): String = _wpisy.value.joinToString("\n\n") {
        when (it) {
            is HermesWpis.Użytkownik -> "Ja: ${it.tekst}"
            is HermesWpis.Hermes -> "Hermes: ${HermesPliki.bezZnaczników(it.tekst)}"
            is HermesWpis.Pliki -> "[Pliki: ${it.pliki.joinToString { p -> p.nazwa }}]"
            is HermesWpis.Narzędzie -> "[${it.opis}]"
            is HermesWpis.Zgoda -> "[Zgoda: ${it.opis} - ${it.status.name.lowercase()}]"
            is HermesWpis.Informacja -> "(${it.tekst})"
        }
    }
}
