package pl.victor.app.translation

/** Teksty trybu rozmowy w dwie strony - patrz AIOrchestrator.startTwoWayTranslation. */
object TwoWay {

    /** Zapowiedź po polsku - mówi, jak oddać sobie głos. */
    fun zapowiedź(nazwaJęzykaRozmówcy: String): String =
        "Tłumaczę rozmowę. Rozmówcę słyszysz po polsku w okularach. Gdy chcesz coś " +
            "powiedzieć, kliknij raz przycisk na oprawce i mów po polsku - przekład " +
            "na $nazwaJęzykaRozmówcy przeczyta głośnik telefonu. Dwa kliknięcia kończą."

    /** Twoje zdanie w historii panelu - odróżnione od słów rozmówcy. */
    fun mojeZdanie(tekst: String): String = "$PREFIKS$tekst"

    fun czyMoje(oryginał: String): Boolean = oryginał.startsWith(PREFIKS)

    const val PREFIKS = "Ty: "
}
