package pl.victor.app.media

import pl.victor.app.ble.MediaCount

/**
 * Czy wolno skasować pamięć okularów - decyzja podejmowana PRZED pokazaniem
 * pytania, żeby przycisk nie zapraszał do straty.
 *
 * Komenda czyści wszystko naraz i nieodwracalnie, więc zgoda użytkownika nie
 * wystarcza: kasujemy tylko to, co na pewno leży już w telefonie.
 */
object ReleaseSafety {

    sealed class Verdict {
        /** Można pytać; [count] plików na okularach, wszystkie zapisane w telefonie. */
        data class Allowed(val count: Int) : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    /** Sprawdzenie na podstawie archiwum, jeszcze bez pytania okularów o liczniki. */
    fun checkArchive(items: List<MediaItem>): Verdict {
        val onGlasses = items.filter { it.stillOnGlasses }
        if (onGlasses.isEmpty()) {
            return Verdict.Refused("Nie znam żadnych plików w okularach - najpierw wczytaj listę.")
        }
        val unsaved = onGlasses.count { !it.savedToPhone }
        if (unsaved > 0) {
            return Verdict.Refused(
                "$unsaved z ${onGlasses.size} plików nie jest jeszcze w telefonie. " +
                    "Najpierw użyj \"Zapisz wszystko\"."
            )
        }
        return Verdict.Allowed(onGlasses.size)
    }

    /**
     * Drugie sprawdzenie, tuż przed wysłaniem: okulary mogły w międzyczasie
     * zrobić nowe zdjęcia, o których lista nie wie.
     */
    fun checkCounters(known: Int, count: MediaCount?): Verdict = when {
        count == null ->
            Verdict.Refused("Okulary nie podały liczby plików - nie kasuję na ślepo.")
        count.total > known ->
            Verdict.Refused(
                "Okulary mają ${count.total} plików, a w telefonie znam $known. " +
                    "Odśwież listę i zapisz nowe, potem spróbuj ponownie."
            )
        else -> Verdict.Allowed(known)
    }
}
