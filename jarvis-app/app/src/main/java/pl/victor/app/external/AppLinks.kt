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
        /** Co powiedzieć, gdy ta próba się powiedzie. */
        val describe: String
    )

    /** Aplikacja docelowa wraz z nazwą pakietu i ludzką nazwą. */
    enum class Target(val packageName: String, val label: String) {
        JAKDOJADE("pl.jakdojade", "Jakdojade"),
        SHAZAM("com.shazam.android", "Shazam"),
        UBER("com.ubercab", "Uber"),
        BOLT("ee.mtakso.client", "Bolt"),
        YANOSIK("pl.neptis.yanosik.mobi.android", "Yanosik")
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
                describe = "Otwieram Jakdojade z celem „$destination”."
            ),
            Attempt(
                uri = "jakdojade://route?destination=$q",
                describe = "Otwieram Jakdojade z celem „$destination”."
            ),
            launchOnly(Target.JAKDOJADE, "Otwieram Jakdojade - cel wpisz sam.")
        )
    }

    /**
     * "Co to za piosenka" w Shazamie.
     *
     * Pierwsza próba to jawna akcja rozpoznawania, druga własny schemat
     * aplikacji, trzecia zwykłe otwarcie. Żadnej z pierwszych dwóch nie mam
     * jak sprawdzić bez telefonu z Shazamem.
     */
    fun shazam(): List<Attempt> = listOf(
        Attempt(
            action = "com.shazam.android.intent.actions.START_TAGGING",
            packageName = Target.SHAZAM.packageName,
            describe = "Shazam słucha."
        ),
        Attempt(
            uri = "shazam://autoshazam",
            describe = "Shazam słucha."
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
                describe = "Otwieram Bolta z celem „$destination”."
            ),
            launchOnly(Target.UBER, "Otwieram Ubera - cel wpisz sam."),
            launchOnly(Target.BOLT, "Otwieram Bolta - cel wpisz sam.")
        )
    }

    /** Yanosik nie ma znanego mi adresu z celem - zostaje samo otwarcie. */
    fun yanosik(): List<Attempt> =
        listOf(launchOnly(Target.YANOSIK, "Otwieram Yanosika."))

    private fun launchOnly(target: Target, describe: String) =
        Attempt(packageName = target.packageName, describe = describe)

    /** Czy ta próba jest ostatecznością, czyli zwykłym uruchomieniem aplikacji. */
    fun Attempt.isPlainLaunch(): Boolean = uri == null && action == null
}
