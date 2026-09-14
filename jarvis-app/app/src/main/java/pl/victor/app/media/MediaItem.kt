package pl.victor.app.media

import pl.victor.app.ble.MediaLibrary

/**
 * Plik w archiwum - to, co ekran potrzebuje wiedzieć.
 *
 * W osobnym pliku od [MediaArchive], bo ten ciągnie za sobą bazę danych, a to
 * tutaj jest czystym typem i regułą porządkowania - jedyną częścią galerii,
 * którą da się sprawdzić testem bez telefonu.
 */
data class MediaItem(
    val name: String,
    val kind: MediaLibrary.Kind,
    val thumbnailPath: String?,
    val savedToPhone: Boolean,
    val stillOnGlasses: Boolean,
    val firstSeenAtMs: Long
) {
    /** Nazwa do pokazania - bez ścieżki, którą niesie spis w formacie JSON. */
    val displayName: String get() = name.substringAfterLast('/')
}

/**
 * Układa archiwum w grupy do pokazania: zdjęcia, wideo, nagrania, reszta.
 *
 * Najnowsze na górze - nazwy z okularów są sekwencyjne albo oparte na czasie,
 * więc malejąco po nazwie stawia ostatnie zdjęcia pierwsze. Puste grupy nie
 * trafiają do wyniku: pusta zakładka "Wideo" wygląda jak awaria, a znaczy
 * tylko tyle, że nikt nie nagrywał.
 */
fun groupForDisplay(items: List<MediaItem>): List<Pair<MediaLibrary.Kind, List<MediaItem>>> =
    items.groupBy { it.kind }
        .toList()
        .sortedBy { (kind, _) -> kind.ordinal }
        .map { (kind, list) -> kind to list.sortedByDescending { it.name } }
        .filter { (_, list) -> list.isNotEmpty() }

/**
 * Zawężenie widoku galerii.
 *
 * ## Po co, skoro jest siatka
 * Bo na sprzęcie leżało zmierzone sto dwadzieścia jeden zdjęć, a pytania, które
 * użytkownik naprawdę zadaje galerii, są dwa: "czego jeszcze nie mam w
 * telefonie" i "co da się jeszcze pobrać". Przewijanie stu kafelków i
 * odczytywanie plakietek z każdego z osobna jest odpowiedzią na oba, tyle że
 * najgorszą z możliwych.
 *
 * Zawężenia są ROZŁĄCZNE i jest ich mało z rozmysłu: każdy dokładany filtr to
 * kolejny stan, w którym lista wygląda na pustą, choć pliki są.
 */
enum class MediaFilter(val label: String) {

    /** Całe archiwum - także pliki, których na okularach już nie ma. */
    ALL("Wszystko"),

    /** Tylko to, co da się jeszcze pobrać ze sprzętu. */
    ON_GLASSES("Na okularach"),

    /**
     * To, czego jeszcze nie ma w telefonie, a da się pobrać.
     *
     * Warunek jest podwójny, bo sam "niezapisany" pokazywałby też pliki
     * skasowane z okularów - czyli takie, których zapisać się już NIE DA.
     * Lista rzeczy do zrobienia, na której połowa pozycji jest niewykonalna,
     * jest gorsza niż jej brak.
     */
    NOT_SAVED("Do zapisania"),

    /** To, co już leży w galerii telefonu. */
    SAVED("W telefonie")
}

/** Zawęża archiwum zgodnie z wyborem. */
fun applyFilter(items: List<MediaItem>, filter: MediaFilter): List<MediaItem> = when (filter) {
    MediaFilter.ALL -> items
    MediaFilter.ON_GLASSES -> items.filter { it.stillOnGlasses }
    MediaFilter.NOT_SAVED -> items.filter { !it.savedToPhone && it.stillOnGlasses }
    MediaFilter.SAVED -> items.filter { it.savedToPhone }
}
