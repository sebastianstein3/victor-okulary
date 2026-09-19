package pl.victor.app.external

import java.net.URLEncoder

/**
 * Otwieranie CUDZYCH aplikacji z gotowym zadaniem.
 *
 * ## Czemu lista kandydatów, a nie jeden adres
 * Bo adresów głębokich tych aplikacji nie da się sprawdzić bez ich
 * zainstalowania, a producenci zmieniają je między wersjami i nigdzie nie
 * obiecują zgodności. Jeden wpisany na sztywno adres dałby funkcję, która
 * CICHO NIE DZIAŁA: aplikacja się otwiera, zadanie nie wykonuje, a użytkownik
 * dowiaduje się o tym, stojąc na przystanku.
 *
 * Dlatego każde zadanie ma UPORZĄDKOWANĄ listę prób, od najbardziej
 * szczegółowej do najogólniejszej, a na końcu zawsze stoi zwykłe uruchomienie
 * aplikacji. Wołający próbuje po kolei i MÓWI, co się udało - patrz
 * [Attempt.describe].
 *
 * ## Co to znaczy w praktyce
 * Przy pierwszym uruchomieniu w terenie dziennik pokaże, KTÓRY kandydat
 * zadziałał. Wtedy listę można skrócić do jednego wpisu - ale dopiero wtedy,
 * na podstawie pomiaru, a nie mojego przypuszczenia.
 */
object AppLinks {

    /**
     * Kodowanie celu do adresu - czystą Javą, nie klasą Androida.
     *
     * ## Czemu nie klasa Uri z Androida
     * Bo w testach jednostkowych jest ATRAPĄ: przy
     * `unitTests.isReturnDefaultValues = true` jej `encode` oddaje null i test
     * pada, choć na telefonie wszystko działa. Dokładnie ten sam błąd
     * popełniłem dzień wcześniej w ReadingOrder z klasą Rect - mój lokalny
     * runner ma `android-all.jar` z PRAWDZIWĄ implementacją, więc pokazuje
     * zieloną tam, gdzie CI pokaże czerwoną.
     *
     * `URLEncoder` tej pułapki nie ma. Plus zamieniamy na %20, bo w adresie
     * `geo:` plus bywa pokazywany dosłownie zamiast jako spacja.
     */
    private fun enc(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** Jedna próba otwarcia: adres albo jawna akcja, opcjonalnie w konkretnej aplikacji. */
    data class Attempt(
        val uri: String? = null,
        val action: String? = null,
        val packageName: String? = null,
        /**
         * Nazwa aplikacji widoczna na pulpicie - zapas, gdy [packageName] nie
         * trafia. Patrz [Target]: nazwy pakietów bywają zgadnięte źle, nazwy
         * z pulpitu czyta się z systemu.
         */
        val label: String? = null,
        /** Co powiedzieć, gdy ta próba się powiedzie. */
        val describe: String
    )

    /** Aplikacja docelowa wraz z nazwą pakietu i ludzką nazwą. */
    /**
     * Aplikacja docelowa: nazwa pakietu i nazwa, jaką widać na pulpicie.
     *
     * ## Nazwa pakietu jest WSKAZÓWKĄ, nie jedyną drogą
     * Zgadłem "pl.jakdojade", a naprawdę jest to "com.citynav.jakdojade.pl.android"
     * - i przez to aplikacja meldowała, że Jakdojade nie jest zainstalowane,
     * stojąc obok jego ikony. Nazwy pakietów nie da się wywnioskować z nazwy
     * aplikacji i producenci je zmieniają, więc opieranie całej funkcji na
     * jednym takim ciągu znaków jest kruche.
     *
     * Dlatego [label] nie jest ozdobą: gdy pakiet się nie znajdzie, wołający
     * szuka aplikacji po nazwie widocznej na pulpicie. To działa także wtedy,
     * gdy zgadłem źle albo gdy pakiet się zmienił.
     */
    enum class Target(val packageName: String, val label: String) {
        JAKDOJADE(pl.victor.app.actions.KnownApps.JAKDOJADE, "Jakdojade"),
        SHAZAM(pl.victor.app.actions.KnownApps.SHAZAM, "Shazam"),
        UBER(pl.victor.app.actions.KnownApps.UBER, "Uber"),
        BOLT(pl.victor.app.actions.KnownApps.BOLT, "Bolt"),
        YANOSIK(pl.victor.app.actions.KnownApps.YANOSIK, "Yanosik")
    }

    /**
     * Kolejne próby dla "zaplanuj dojazd do X" w Jakdojade.
     *
     * `geo:` jest pierwszy, bo to STANDARD Androida i aplikacje komunikacyjne
     * zwykle się pod niego podpinają - a jeśli tak, dostaną cel bez zgadywania
     * ich własnego formatu.
     */
    fun jakdojade(destination: String): List<Attempt> {
        val q = enc(destination)
        return listOf(
            Attempt(
                uri = "geo:0,0?q=$q",
                packageName = Target.JAKDOJADE.packageName,
                label = Target.JAKDOJADE.label,
                describe = "Otwieram Jakdojade z celem „$destination”."
            ),
            Attempt(
                uri = "jakdojade://route?destination=$q",
                describe = "Otwieram Jakdojade z celem „$destination”."
            ),
            // POMIAR Z TERENU: to jest droga, która faktycznie wchodzi.
            //
            // Dziennik z 17 września: "zadanie=TRANSIT_PLAN proba=3/3", czyli
            // oba adresy wyżej odpadły i zadziałało zwykłe otwarcie. Komunikat
            // musi więc mówić wprost, że celu NIE wpisaliśmy - inaczej człowiek
            // patrzy na ekran startowy, wierząc, że trasa się liczy.
            //
            // Samo pytanie "jak dojadę" nie idzie już tędy: bez nazwania
            // Jakdojade z nazwy trafia do Map z travelmode=transit, które cel
            // przyjmują (patrz SmartActionDetector.TRANSIT_QUESTION_REGEX).
            launchOnly(
                Target.JAKDOJADE,
                "Otwieram Jakdojade. Celu nie wpiszę za Ciebie - ta aplikacja " +
                    "nie przyjmuje go z zewnątrz."
            )
        )
    }

    /**
     * "Co to za piosenka" w Shazamie.
     *
     * Pierwsza próba to jawna akcja rozpoznawania, druga własny schemat
     * aplikacji, trzecia zwykłe otwarcie.
     *
     * ## Czemu żadna z tych wypowiedzi nie mówi już "Shazam słucha"
     * Bo tego NIE WIEMY. Wiemy tylko tyle, że system przyjął Intent - a to
     * znaczy, że aplikacja się otworzyła, nie że zaczęła nagrywać. Komentarz
     * w tym miejscu sam się do tego przyznawał ("żadnej z pierwszych dwóch nie
     * mam jak sprawdzić"), a wypowiedź obok twierdziła coś przeciwnego.
     *
     * Zgłoszone dokładnie tak: "dostałem tylko informację »shazam słucha«".
     * Zdanie, które zapewnia o czymś niesprawdzonym, jest gorsze niż zdanie
     * ostrożne: człowiek stoi i czeka, aż piosenka się skończy, zamiast dotknąć
     * przycisku.
     *
     * To ta sama usterka co "melduje sukces na martwym kanale" przy alercie
     * pogodowym i "mówi, że otwiera Jakdojade" przy trasie - trzeci nawrót.
     */
    fun shazam(): List<Attempt> = listOf(
        Attempt(
            action = "com.shazam.android.intent.actions.START_TAGGING",
            packageName = Target.SHAZAM.packageName,
            label = Target.SHAZAM.label,
            describe = "Włączam rozpoznawanie w Shazamie. Jeśli sam nie zacznie " +
                "słuchać, dotknij dużego przycisku."
        ),
        Attempt(
            uri = "shazam://autoshazam",
            describe = "Włączam rozpoznawanie w Shazamie. Jeśli sam nie zacznie " +
                "słuchać, dotknij dużego przycisku."
        ),
        launchOnly(Target.SHAZAM, "Otwieram Shazama - dotknij przycisku, żeby słuchał.")
    )

    /** Kurs do X: najpierw Uber, potem Bolt, bo tylko Uber dokumentuje swój adres. */
    fun ride(destination: String): List<Attempt> {
        val q = enc(destination)
        return listOf(
            Attempt(
                uri = "uber://?action=setPickup&pickup=my_location" +
                    "&dropoff[formatted_address]=$q",
                describe = "Otwieram Ubera z celem „$destination”."
            ),
            Attempt(
                uri = "bolt://action/setPickup?destination=$q",
                // Ostrożniej niż przy Uberze, i to jest różnica z pomiaru, nie z
                // ostrożności: Uber swój adres DOKUMENTUJE, Bolt nie. Schemat
                // Bolta jest odgadnięty, więc aplikacja może się otworzyć bez
                // celu - a wtedy zapewnienie "z celem" wysyła człowieka w drogę
                // przekonanego, że kurs jest zamówiony.
                describe = "Otwieram Bolta z celem „$destination” - sprawdź, " +
                    "czy się wpisał."
            ),
            launchOnly(Target.UBER, "Otwieram Ubera - cel wpisz sam."),
            launchOnly(Target.BOLT, "Otwieram Bolta - cel wpisz sam.")
        )
    }

    /** Yanosik nie ma znanego mi adresu z celem - zostaje samo otwarcie. */
    fun yanosik(): List<Attempt> =
        listOf(launchOnly(Target.YANOSIK, "Otwieram Yanosika."))

    private fun launchOnly(target: Target, describe: String) =
        Attempt(packageName = target.packageName, label = target.label, describe = describe)

    /** Czy ta próba jest ostatecznością, czyli zwykłym uruchomieniem aplikacji. */
    fun Attempt.isPlainLaunch(): Boolean = uri == null && action == null
}
