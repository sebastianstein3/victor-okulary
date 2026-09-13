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
