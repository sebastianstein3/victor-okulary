package pl.victor.app.vision

/**
 * Czy dwa zdjęcia pokazują TO SAMO - bez patrzenia na nazwy plików i zegary.
 *
 * ## Skąd to się wzięło
 * Zgłoszone: "gdy każę zeskanować kod, AI dostaje zdjęcie sprzed kilku dni
 * zamiast aktualnego". Dziennik ramek pokazuje, że droga pełnej rozdzielczości
 * ZADZIAŁAŁA - migawka, tryb transferu, trzydzieści sekund pobierania. Tylko że
 * pobrała niewłaściwy plik.
 *
 * Aplikacja robiła zdjęcie komendą "zdjęcie AI z miniaturą", a potem brała z
 * pamięci okularów NAJNOWSZY plik i zakładała, że to ono. Na tym egzemplarzu
 * zdjęcie AI najwyraźniej nie trafia do pamięci - więc "najnowszy plik" był
 * zdjęciem zrobionym kilka dni wcześniej. Model dostawał obraz z innego dnia,
 * a na liście produktów mogło wylądować coś, czego człowiek nie trzyma w ręku.
 *
 * To jest gorsze niż miniatura: miniatura jest niewyraźna, ale PRAWDZIWA.
 *
 * ## Jak rozstrzygamy
 * Mamy w ręku miniaturę zrobioną sekundę wcześniej - to jest punkt odniesienia,
 * co do którego nie ma wątpliwości. Obie wersje sprowadzamy do odcisku 9 x 8
 * pikseli w skali szarości i porównujemy "który sąsiad jest jaśniejszy"
 * (tzw. dHash). Ten odcisk nie zależy od rozdzielczości, kompresji JPEG ani
 * ogólnej jasności - zależy od tego, CO jest w kadrze.
 *
 * Dwa zdjęcia tej samej sceny różnią się na kilku bitach z 64. Dwa zdjęcia
 * różnych scen - średnio na połowie, bo wtedy każdy bit jest rzutem monetą.
 *
 * ## Czego to NIE sprawdza
 * Nie odróżni dwóch zdjęć tego samego miejsca z tego samego ujęcia zrobionych w
 * różne dni. Do tego nie ma ono zresztą znaczenia: jeśli kadr jest ten sam, to
 * i kod na nim jest ten sam.
 */
object PhotoMatch {

    const val SZEROKOŚĆ = 9
    const val WYSOKOŚĆ = 8

    /**
     * Ile bitów z 64 może się różnić, żeby uznać zdjęcia za tę samą scenę.
     *
     * Różne sceny dają około 32. Próg ustawiony dużo poniżej - bo pomyłka w jedną
     * stronę kosztuje ostre zdjęcie (zostaje miniatura), a w drugą podsuwa
     * modelowi obraz z innego dnia.
     */
    const val MAX_RÓŻNICA = 14

    /**
     * Odcisk z gotowej siatki 9 x 8 jasności (0-255), wiersz po wierszu.
     *
     * @return 64 bity albo `null`, gdy siatka ma zły rozmiar
     */
    fun odcisk(jasność: IntArray): Long? {
        if (jasność.size != SZEROKOŚĆ * WYSOKOŚĆ) return null
        var bity = 0L
        var i = 0
        for (y in 0 until WYSOKOŚĆ) {
            for (x in 0 until SZEROKOŚĆ - 1) {
                val lewy = jasność[y * SZEROKOŚĆ + x]
                val prawy = jasność[y * SZEROKOŚĆ + x + 1]
                if (lewy > prawy) bity = bity or (1L shl i)
                i++
            }
        }
        return bity
    }

    fun różnica(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun toSamo(a: Long, b: Long): Boolean = różnica(a, b) <= MAX_RÓŻNICA
}
