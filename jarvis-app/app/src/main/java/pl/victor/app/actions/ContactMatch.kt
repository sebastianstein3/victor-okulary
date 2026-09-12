package pl.victor.app.actions

/**
 * Dopasowanie nazwy wypowiedzianej przez człowieka do nazwy kontaktu.
 *
 * ## Czemu to jest osobno i czemu punktuje, a nie odpowiada tak/nie
 * Bo wynikiem jest TELEFON DO KOGOŚ ALBO SMS DO KOGOŚ. Poprzednia wersja
 * oddawała pierwszy wiersz, który przeszedł test logiczny, i miała wśród
 * warunków taki:
 *
 *     query.contains(displayName)
 *
 * czyli „nazwa kontaktu mieści się w zapytaniu". Przy kontakcie zapisanym jako
 * „Jan" prośba o Janusza spełnia go co do litery - `"janusz".contains("jan")` -
 * i telefon dzwoni do niewłaściwej osoby. Nikt się o tym nie dowie, bo
 * wywołujący dostaje sam numer i nie ma jak sprawdzić, czyj.
 *
 * Punktacja pozwala wybrać NAJLEPSZE dopasowanie zamiast pierwszego, a przy
 * remisie między różnymi osobami - odmówić. Odmowa kończy się komunikatem „nie
 * znalazłem kontaktu", który da się naprawić powtórzeniem imienia. Pomyłka
 * kończy się rozmową z obcą osobą.
 *
 * Wszystko liczone na nazwach ZNORMALIZOWANYCH przez [normalize]: bez ogonków,
 * małymi literami, bez zbędnych spacji.
 */
object ContactMatch {

    /** Wynik dla nazw, które do siebie nie pasują. */
    const val NO_MATCH = -1

    /**
     * Jak dobrze `query` opisuje kontakt `displayName`. Więcej = lepiej,
     * [NO_MATCH] = wcale.
     */
    fun score(query: String, displayName: String): Int {
        val q = normalize(query)
        val name = normalize(displayName)
        if (q.isBlank() || name.isBlank()) return NO_MATCH

        if (q == name) return 100

        val qTokens = q.split(' ').filter { it.isNotBlank() }
        val nameTokens = name.split(' ').filter { it.isNotBlank() }
        if (qTokens.isEmpty() || nameTokens.isEmpty()) return NO_MATCH

        // „Ania Nowak" wobec „Nowak Ania" - komplet słów się zgadza, kolejność
        // nie musi. Ludzie mówią imieniem i nazwiskiem w obu kolejnościach.
        //
        // Ta sama reguła obsługuje samo imię wobec imienia z nazwiskiem („ania"
        // wobec „ania nowak"), bo jedno słowo też jest kompletem słów. Miałem
        // tu osobną regułę na ten przypadek - test pokazał, że nigdy nie
        // dochodziła do głosu, więc jej nie ma.
        if (qTokens.all { it in nameTokens }) return 90

        // Skrócenie imienia: „agni" wobec „agnieszka wojcik". Próg trzech liter,
        // bo przy dwóch pasuje pół książki adresowej.
        if (q.length >= MIN_PREFIX && nameTokens.first().startsWith(q)) return 60
        if (q.length >= MIN_PREFIX && nameTokens.any { it.startsWith(q) }) return 50

        // ODMIANA PRZEZ PRZYPADKI - i to jest miejsce, w którym trzeba uważać.
        //
        // Po polsku imię w zdaniu rzadko stoi w mianowniku: „powiedz Łukaszowi",
        // „napisz do Agnieszki". Część odmian DOKŁADA końcówkę, więc nazwa
        // kontaktu jest wtedy początkiem zapytania - odwrotnie niż wyżej.
        //
        // Ale dokładnie ten sam kształt ma pomyłka, która psuła poprzednią
        // wersję: „jan" jest początkiem „janusz". Różnicy nie da się rozstrzygnąć
        // bez słownika odmian, więc stawiam próg długości: pięć liter. „lukasz"
        // w „lukaszowi" przechodzi, „jan" w „janusz" nie. Krótkie imiona tracą
        // odmianę, ale krótkie imiona to właśnie te, które wpadają na siebie
        // nawzajem - i lepiej, żeby wtedy padło pytanie niż telefon.
        if (nameTokens.any { it.length >= MIN_INFLECTED && q.startsWith(it) }) return 40
        if (qTokens.any { qt ->
                nameTokens.any { it.length >= MIN_INFLECTED && qt.startsWith(it) }
            }
        ) return 30

        return NO_MATCH
    }

    /**
     * Wybiera najlepszy kontakt z listy `(klucz, nazwa)`.
     *
     * @return klucz zwycięzcy albo `null`, gdy nic nie pasuje ALBO gdy dwie
     *   różne osoby pasują tak samo dobrze
     */
    fun <T> best(query: String, candidates: List<Pair<T, String>>): T? {
        var bestScore = NO_MATCH
        var bestKey: T? = null
        var tied = false
        for ((key, name) in candidates) {
            val s = score(query, name)
            if (s == NO_MATCH) continue
            when {
                s > bestScore -> {
                    bestScore = s
                    bestKey = key
                    tied = false
                }
                // Remis liczy się tylko między RÓŻNYMI osobami: ten sam kontakt
                // potrafi wystąpić w kilku wierszach (kilka numerów).
                s == bestScore && key != bestKey -> tied = true
            }
        }
        return if (tied) null else bestKey
    }

    /**
     * Małe litery, bez polskich znaków, pojedyncze spacje.
     *
     * Ogonki lecą PO OBU STRONACH porównania - i to jest cała różnica wobec
     * poprzedniej wersji, która normalizowała zapytanie, a potem szukała nim w
     * bazie kontaktów przez SQL `LIKE`. Kolumna z nazwami ogonki ma, więc
     * „lukasz" nie trafiał w „Łukasz" i żaden kontakt z polskim imieniem nie
     * dawał się znaleźć.
     */
    fun normalize(s: String): String = s.trim().lowercase()
        .replace("ą", "a").replace("ć", "c").replace("ę", "e")
        .replace("ł", "l").replace("ń", "n").replace("ó", "o")
        .replace("ś", "s").replace("ź", "z").replace("ż", "z")
        .replace(Regex("\\s+"), " ")

    /** Najkrótszy skrót imienia, który wolno dopasować. */
    private const val MIN_PREFIX = 3

    /** Najkrótsza nazwa kontaktu, której wolno szukać w odmienionym zapytaniu. */
    private const val MIN_INFLECTED = 5
}
