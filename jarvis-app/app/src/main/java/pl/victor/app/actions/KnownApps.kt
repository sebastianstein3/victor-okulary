package pl.victor.app.actions

/**
 * Jedno miejsce z nazwami pakietów aplikacji, które znamy z nazwy.
 *
 * ## Po co, skoro listy już były
 * Bo były DWIE, o różnych kluczach, i rozjechały się w praktyce. Uber figurował
 * w jednej jako "com.ubercab", a w drugiej jako "com.uber" - poprawiłem tę
 * nazwę w pierwszej i nie zauważyłem, że istnieje druga kopia. Tak samo
 * "pl.jakdojade" (zgadnięte, nieprawdziwe) trzeba było poprawiać w trzech
 * miejscach naraz: w kodzie, w manifeście i w teście.
 *
 * Nazwy pakietów mają tę niemiłą własność, że NIE DA SIĘ ich wywnioskować z
 * nazwy aplikacji ani sprawdzić na oczy - trzeba je znać. Trzymanie ich w kilku
 * miejscach gwarantuje więc, że kiedyś się rozejdą, a objawem będzie cicha
 * odmowa: "nie mam takiej aplikacji", stojąc obok jej ikony.
 *
 * ## Co czego potrzebuje
 * - rozpoznawanie mowy potrzebuje: NAZWA MÓWIONA -> pakiet ([bySpokenName]),
 * - ekran ustawień potrzebuje: pakiet -> NAZWA NA EKRANIE ([displayList]),
 * - zadania w aplikacjach potrzebują pakietu i nazwy z pulpitu
 *   ([pl.victor.app.external.AppLinks.Target]).
 *
 * To są trzy różne widoki tych samych danych, więc mieszkają razem.
 */
object KnownApps {

    /**
     * @param packageName nazwa pakietu w Androidzie - wartość, której nie da
     *   się zgadnąć; każda tutaj jest sprawdzona w Google Play
     * @param display nazwa pokazywana na ekranie ustawień
     * @param spoken formy, w jakich człowiek powie tę aplikację; puste, gdy
     *   aplikacji się nie otwiera na komendę (np. my sami)
     */
    data class App(
        val packageName: String,
        val display: String,
        val spoken: List<String> = emptyList()
    )

    // Stałe dla tych, których używa też AppLinks - żeby nazwa pakietu stała
    // w repozytorium DOKŁADNIE raz, a nie raz tutaj i raz tam.
    const val JAKDOJADE = "com.citynav.jakdojade.pl.android"
    const val SHAZAM = "com.shazam.android"
    const val UBER = "com.ubercab"
    const val BOLT = "ee.mtakso.client"
    const val YANOSIK = "pl.neptis.yanosik.mobi.android"

    val ALL: List<App> = listOf(
        App("com.spotify.music", "Spotify", listOf("spotify")),
        App("com.google.android.youtube", "YouTube", listOf("youtube")),
        App("com.google.android.apps.maps", "Google Maps", listOf("mapy", "google maps")),
        App("com.google.android.apps.photos", "Google Photos"),
        App("com.google.android.gm", "Gmail", listOf("gmail", "mail")),
        App("com.whatsapp", "WhatsApp", listOf("whatsapp")),
        App("com.facebook.katana", "Facebook", listOf("facebook")),
        App("com.instagram.android", "Instagram", listOf("instagram")),
        App("com.twitter.android", "Twitter"),
        App("org.telegram.messenger", "Telegram", listOf("telegram")),
        App("com.slack", "Slack"),
        App("com.netflix.mediaclient", "Netflix", listOf("netflix")),
        App("com.amazon.mShop.android.shopping", "Amazon", listOf("amazon")),
        App("com.google.android.calendar", "Kalendarz", listOf("kalendarz", "calendar")),
        App("com.google.android.keep", "Notatki Keep", listOf("notatki", "keep")),
        App(UBER, "Uber", listOf("uber")),
        App(BOLT, "Bolt", listOf("bolt")),
        App(JAKDOJADE, "Jakdojade", listOf("jakdojade")),
        App(SHAZAM, "Shazam", listOf("shazam")),
        App(YANOSIK, "Yanosik", listOf("yanosik")),
        App("pl.victor.app", "V.I.C.T.O.R. (ta apka)")
    )

    /** Nazwa mówiona -> pakiet. Klucze są małymi literami. */
    val bySpokenName: Map<String, String> =
        ALL.flatMap { app -> app.spoken.map { it to app.packageName } }.toMap()

    /** Pakiet -> nazwa na ekranie, w kolejności z [ALL]. */
    val displayList: List<Pair<String, String>> =
        ALL.map { it.packageName to it.display }

    /** Pakiet aplikacji o tej nazwie na ekranie, albo `null`. */
    fun packageOf(display: String): String? =
        ALL.firstOrNull { it.display.equals(display, ignoreCase = true) }?.packageName
}
