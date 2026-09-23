package pl.victor.app.vision

/**
 * Ile razy i jak powiększyć obraz, zanim uznamy, że kodu w nim nie ma.
 *
 * ## Czemu to w ogóle jest potrzebne
 * Kod QR i kod kreskowy to dwa różne zadania dla tej samej biblioteki. QR ma
 * kwadraty wielkości kilku pikseli i duże znaczniki w rogach - z miniatury da
 * się go złożyć. EAN-13 to kilkadziesiąt PIONOWYCH KRESEK, z których
 * najcieńsza na miniaturze ma grubość jednego piksela albo mniej; przy
 * kompresji JPEG zlewa się z sąsiednią i informacja po prostu przestaje
 * istnieć.
 *
 * Zgłoszone wprost: "nie rozpoznaje kodów kreskowych" - przy działającym
 * skanowaniu QR.
 *
 * ## Czemu powiększanie pomaga, skoro nie dokłada informacji
 * Bo jej nie dokłada, i to jest tu cała rzecz: dekoder ML Kit szuka krawędzi
 * z dokładnością do piksela. Gdy kreska ma pół piksela, próg jasności wypada
 * między próbkami i krawędzi nie ma. Dwukrotne powiększenie z interpolacją
 * rozkłada tę samą różnicę jasności na dwa piksele i krawędź znów daje się
 * znaleźć. To nie jest odzyskiwanie danych - to jest podanie ich w rozdziałce,
 * którą dekoder umie czytać.
 *
 * Działa tylko do pewnego momentu: kreska, która na oryginale zlała się
 * z sąsiednią w jedną szarą plamę, nie wróci z żadnego powiększenia. Stąd
 * [MAX_POWIĘKSZENIE] i stąd górny limit rozmiaru - powiększanie obrazu, który
 * i tak jest duży, to czysty koszt pamięci i czasu.
 *
 * ## Czemu osobny plik
 * Bo sam skan jest wywołaniem ML Kit, którego nie da się uruchomić w teście
 * jednostkowym, ale PLAN prób jest zwykłą arytmetyką - i to w nim siedzą
 * wszystkie decyzje, które mogą być złe.
 */
object BarcodeAttempts {

    /**
     * Powyżej tylu pikseli krótszego boku nie ma czego powiększać - kreski
     * mają już po kilka pikseli.
     */
    const val PRÓG_MAŁEGO_OBRAZU = 1_400

    /** Dalej niż czterokrotnie nie idziemy - patrz opis klasy. */
    const val MAX_POWIĘKSZENIE = 4

    /**
     * Kolejne mnożniki do wypróbowania dla obrazu o tym rozmiarze.
     *
     * Pierwszy jest ZAWSZE 1 - czyli oryginał. Powiększenia są tylko dla
     * obrazów małych i idą od najtańszego.
     *
     * @param krótszyBok mniejszy z dwóch wymiarów obrazu w pikselach
     */
    fun mnożniki(krótszyBok: Int): List<Int> {
        if (krótszyBok <= 0) return listOf(1)
        if (krótszyBok >= PRÓG_MAŁEGO_OBRAZU) return listOf(1)
        val wynik = mutableListOf(1)
        var m = 2
        while (m <= MAX_POWIĘKSZENIE && krótszyBok * m <= PRÓG_MAŁEGO_OBRAZU * 2) {
            wynik.add(m)
            m *= 2
        }
        return wynik
    }

    /**
     * Czy w tym obrazie kod kreskowy ma w ogóle SZANSĘ się zmieścić.
     *
     * EAN-13 to 95 modułów szerokości. Żeby dekoder miał po dwa piksele na
     * moduł, sam kod musi zająć circa 190 pikseli - a zajmuje zwykle ułamek
     * kadru. Poniżej tej szerokości całego obrazu nie ma o czym mówić i
     * warto powiedzieć to człowiekowi, zamiast milczeć.
     */
    fun beznadziejnyRozmiar(szerokość: Int): Boolean = szerokość in 1 until MIN_SZEROKOŚĆ_EAN

    /** 95 modułów EAN-13 razy dwa piksele, razy zapas na to, że kod nie wypełnia kadru. */
    const val MIN_SZEROKOŚĆ_EAN = 640
}
