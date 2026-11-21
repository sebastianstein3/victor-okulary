package pl.victor.app.google

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.drive.DriveScopes
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.tasks.TasksScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Jeden wspólny login Google dla całej apki.
 *
 * Wszystkie usługi Google (Calendar, Gmail, w przyszłości inne) proszą o
 * dostęp w JEDNYM ekranie zgody - user loguje się raz i ma dostęp do
 * wszystkiego, zamiast osobnego logowania (i osobnego ekranu zgody) per
 * usługa. Dodanie nowej usługi Google = dopisz jej scope do listy w
 * `signInOptions` poniżej.
 *
 * Wymaga w Google Cloud Console (projekt OAuth apki):
 * - włączonego Calendar API, Gmail API, Drive API i Google Tasks API
 * - scope'ów poniżej dodanych do OAuth consent screen
 * To jest konfiguracja po stronie Google, nie kodu - sam kod nie wystarczy.
 */
class GoogleAccountManager(private val context: Context) {

    private val tag = "GoogleAccountManager"

    /**
     * Zakres PODSTAWOWY - o ten prosi zwykłe logowanie.
     *
     * ## Dlaczego nie ma tu poczty
     * Bo `gmail.readonly` jest w klasyfikacji Google zakresem ZASTRZEŻONYM, a
     * kalendarz tylko wrażliwym. To nie jest różnica kosmetyczna: aplikacja z
     * zakresem zastrzeżonym przechodzi weryfikację z płatnym audytem
     * bezpieczeństwa, a do tego czasu w stanie "opublikowana" jest blokowana dla
     * wszystkich komunikatem "Dostęp zablokowany - aplikacja nie przeszła
     * weryfikacji". Zgłoszone z użycia dokładnie w tym brzmieniu.
     *
     * Kto nie korzysta z poczty, nie ma powodu w to wchodzić. Dlatego poczta jest
     * dokładana osobno - tak samo jak Dysk - i ekran zgody przy pierwszym
     * logowaniu prosi wyłącznie o kalendarz.
     */
    private val calendarScopes = listOf(
        Scope(CalendarScopes.CALENDAR),
        Scope(CalendarScopes.CALENDAR_EVENTS)
    )

    /** Zakres poczty - DODATKOWY, patrz [calendarScopes]. */
    private val gmailScopes = listOf(
        Scope(GmailScopes.GMAIL_READONLY),
        Scope(GmailScopes.GMAIL_SEND)
    )

    /**
     * Dostęp do Dysku jest DODATKOWY, nie wymagany.
     *
     * ## Dlaczego nie w [signInOptions]
     * Bo [getCurrentAccount] uznaje konto bez KTÓREGOKOLWIEK ze scope'ów za
     * niezalogowane. Dopisanie Dysku do listy obowiązkowej unieważniłoby
     * wszystkie istniejące logowania - kalendarz przestałby działać
     * do czasu, aż użytkownik przejdzie ekran zgody jeszcze raz, a przedtem
     * doda nowy scope w Google Cloud Console. Zgoda na Dysk jest więc pytana
     * osobno i dopiero wtedy, gdy ktoś naprawdę włącza eksport.
     *
     * `drive.file` to najwęższy możliwy zakres: aplikacja widzi WYŁĄCZNIE pliki,
     * które sama utworzyła. Nie ma dostępu do reszty Dysku i nie może jej mieć.
     */
    private val driveScope = Scope(DriveScopes.DRIVE_FILE)

    /**
     * Szkice w Gmailu - DODATKOWE do poczty i NIEWYMAGANE.
     *
     * Potrzebne tylko do jednego: gdy użytkownik odpowie „nie” albo zamilknie
     * na pytanie „Wysłać?”, mail zostaje jako szkic zamiast przepaść
     * (`drafts.create` wymaga `gmail.compose` - samo `gmail.send` nie wystarcza).
     * Nie wchodzi do [hasGmailAccess]: konta, które włączyły pocztę przed tą
     * zmianą, nie mają tej zgody, a nie mogą przez to stracić czytania i
     * wysyłania. Prośba o pocztę dokłada ją od teraz za jednym razem.
     */
    private val gmailComposeScope = Scope(GmailScopes.GMAIL_COMPOSE)

    /**
     * Google Tasks - DODATKOWE, pytane dopiero przy włączeniu synchronizacji
     * list w Ustawieniach. Ten sam powód co [driveScope]: dopisanie do zgód
     * obowiązkowych unieważniłoby wszystkie dotychczasowe logowania.
     */
    private val tasksScope = Scope(TasksScopes.TASKS)

    /**
     * Zgody, o które prosi zwykłe logowanie. Musi stać PO [driveScope] i
     * [tasksScope]: pola inicjalizują się w kolejności zapisu, a [options]
     * sięga po nie wszystkie.
     */
    private val signInOptions = options(gmail = false, drive = false)

    /**
     * Buduje zestaw zgód: podstawa plus to, o co akurat prosimy.
     *
     * ## Dlaczego zestaw musi być KUMULATYWNY
     * `GoogleSignIn.getLastSignedInAccount` pamięta zakresy z OSTATNIEGO żądania.
     * Gdyby prośba o Dysk wymieniała tylko podstawę i Dysk, konto po tej operacji
     * wyglądałoby na pozbawione poczty - mimo że zgoda po stronie Google dalej
     * istnieje. Dlatego każda prośba dokłada się do już posiadanych, a nie
     * zastępuje ich.
     */
    private fun options(
        gmail: Boolean,
        drive: Boolean,
        compose: Boolean = false,
        tasks: Boolean = false
    ): GoogleSignInOptions {
        val all = calendarScopes.toMutableList()
        if (gmail) all.addAll(gmailScopes)
        if (compose) all.add(gmailComposeScope)
        if (drive) all.add(driveScope)
        if (tasks) all.add(tasksScope)
        // Pierwszy zakres osobno, reszta rozwinięta - dokładnie tak, jak wygląda
        // sygnatura requestScopes(Scope, vararg Scope).
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(all.first(), *all.drop(1).toTypedArray())
            .build()
    }

    /** Czy konto ma już zgodę na zapis plików na Dysku. */
    fun hasDriveAccess(): Boolean = hasScopes(listOf(driveScope))

    /** Czy konto ma już zgodę na pocztę - wymaga OBU zakresów, czytania i wysyłki. */
    fun hasGmailAccess(): Boolean = hasScopes(gmailScopes)

    /** Czy wolno zostawiać szkice w Gmailu - patrz [gmailComposeScope]. */
    fun hasGmailDraftAccess(): Boolean = hasScopes(listOf(gmailComposeScope))

    /** Czy konto ma zgodę na Google Tasks - patrz [tasksScope]. */
    fun hasTasksAccess(): Boolean = hasScopes(listOf(tasksScope))

    private fun hasScopes(scopes: List<Scope>): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return scopes.all { GoogleSignIn.hasPermissions(account, it) }
    }

    /**
     * Intent proszący o zgodę na Dysk - obsługiwany tak samo jak zwykłe
     * logowanie ([handleSignInResult]), bo Google dokłada zgodę do już
     * zalogowanego konta.
     */
    fun getDriveConsentIntent(): Intent =
        GoogleSignIn.getClient(
            context,
            options(
                gmail = hasGmailAccess(), drive = true,
                compose = hasGmailDraftAccess(), tasks = hasTasksAccess()
            )
        ).signInIntent

    /**
     * Intent proszący o zgodę na pocztę - obsługiwany jak [getDriveConsentIntent].
     * Razem z pocztą prosi o szkice ([gmailComposeScope]) - przy włączonej już
     * poczcie ten sam przycisk dokłada same szkice.
     */
    fun getGmailConsentIntent(): Intent =
        GoogleSignIn.getClient(
            context,
            options(
                gmail = true, drive = hasDriveAccess(),
                compose = true, tasks = hasTasksAccess()
            )
        ).signInIntent

    /** Intent proszący o zgodę na Google Tasks - obsługiwany jak [getDriveConsentIntent]. */
    fun getTasksConsentIntent(): Intent =
        GoogleSignIn.getClient(
            context,
            options(
                gmail = hasGmailAccess(), drive = hasDriveAccess(),
                compose = hasGmailDraftAccess(), tasks = true
            )
        ).signInIntent

    /** Credential do Google Tasks albo `null`, gdy brak zgody albo logowania. */
    fun getTasksCredential(): GoogleAccountCredential? {
        if (!hasTasksAccess()) return null
        return getCredential(java.util.Collections.singleton(TasksScopes.TASKS))
    }

    /** Credential do Dysku albo `null`, gdy brak zgody. */
    fun getDriveCredential(): GoogleAccountCredential? {
        if (!hasDriveAccess()) return null
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return GoogleAccountCredential
            .usingOAuth2(context, java.util.Collections.singleton(DriveScopes.DRIVE_FILE))
            .setSelectedAccountName(account.email)
    }

    private val signInClient: GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions)

    /**
     * Intent do uruchomienia flow logowania Google (jeden dla wszystkich usług).
     */
    fun getSignInIntent(): Intent = signInClient.signInIntent

    /**
     * Czy user jest zalogowany i ma nadany zakres PODSTAWOWY (kalendarz).
     *
     * Poczta i Dysk świadomie się tu nie liczą: są dokładane osobno, a konto bez
     * nich jest w pełni sprawnym kontem - po prostu bez tych dwóch funkcji.
     * Wciągnięcie ich tutaj unieważniłoby logowanie każdemu, kto ich nie chce.
     */
    fun isSignedIn(): Boolean = getCurrentAccount() != null

    fun getCurrentAccount(): GoogleSignInAccount? {
        // Wygasłe logowanie to brak logowania. `getLastSignedInAccount` czyta pamięć
        // TELEFONU, a nie pyta Google, więc po unieważnieniu tokenu dalej zwracałoby
        // konto - a karta w Ustawieniach pisałaby "połączono" mimo że nic nie działa.
        if (loginExpired) return null
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(account, *signInOptions.scopeArray)) {
            account
        } else {
            null
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        clearLoginExpired()
        signInClient.signOut()
        Log.i(tag, "Wylogowano z konta Google")
    }

    /**
     * Credential do zbudowania dowolnego klienta Google API (Calendar, Gmail, ...).
     * Zwraca `null` gdy user nie jest zalogowany (patrz [isSignedIn]).
     */
    fun getCredential(scopes: Collection<String>): GoogleAccountCredential? {
        val account = getCurrentAccount() ?: return null
        return GoogleAccountCredential
            .usingOAuth2(context, scopes)
            .setSelectedAccountName(account.email)
    }

    /**
     * Odczytuje wynik logowania z intencji zwróconej przez ekran wyboru konta.
     *
     * ## Dlaczego to musi być JEDNO miejsce
     * Logowanie da się uruchomić z dwóch ekranów - głównego i ustawień - a
     * obsługa wyniku była w każdym inna. W ustawieniach czytała błąd, na ekranie
     * głównym sprawdzała samo `resultCode` i milczała. To jest dokładnie ten
     * rodzaj różnicy, po którym "raz działa, raz nie": ta sama nieudana próba
     * kończyła się komunikatem albo ciszą, zależnie od tego, skąd się zaczęło.
     *
     * ## Czemu `resultCode` nie wystarczy
     * Google Sign-In sygnalizuje błąd WEWNĄTRZ intencji, a nie kodem wyniku.
     * `DEVELOPER_ERROR` (klient OAuth nieskonfigurowany dla tego pakietu i
     * odcisku SHA-1) wraca tak samo jak zwykłe cofnięcie się z ekranu wyboru
     * konta - z zewnątrz nie do odróżnienia, mimo że pierwsze to trwała usterka
     * konfiguracji, a drugie decyzja użytkownika.
     */
    fun handleSignInResult(data: Intent?): SignInOutcome {
        if (data == null) {
            return SignInOutcome.Cancelled
        }
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
                ?: return SignInOutcome.Failed(
                    "Google nie zwróciło konta. Spróbuj jeszcze raz."
                )
            val missing = missingScopes(account)
            if (missing.isNotEmpty()) {
                Log.w(tag, "Zalogowano, ale bez zgód: $missing")
                SignInOutcome.MissingConsent(account, missing)
            } else {
                Log.i(tag, "Konto Google połączone: ${account.email}")
                clearLoginExpired()
                SignInOutcome.Success(account)
            }
        } catch (e: ApiException) {
            Log.e(tag, "Logowanie Google nie powiodło się (kod ${e.statusCode})", e)
            when (e.statusCode) {
                GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> SignInOutcome.Cancelled
                else -> SignInOutcome.Failed(describeStatus(e.statusCode))
            }
        }
    }

    /** Zgody, których konto NIE ma, opisane po ludzku. */
    fun missingScopes(account: GoogleSignInAccount): List<String> =
        signInOptions.scopeArray
            .filterNot { GoogleSignIn.hasPermissions(account, it) }
            .map { SCOPE_NAMES[it.scopeUri] ?: it.scopeUri }

    /** Co się stało przy logowaniu - w formie, którą da się pokazać człowiekowi. */
    sealed class SignInOutcome {
        data class Success(val account: GoogleSignInAccount) : SignInOutcome()

        /** Zalogowano, ale użytkownik nie dał wszystkich zgód. */
        data class MissingConsent(
            val account: GoogleSignInAccount,
            val missing: List<String>
        ) : SignInOutcome()

        object Cancelled : SignInOutcome()

        data class Failed(val message: String) : SignInOutcome()
    }

    companion object {

        /**
         * Czy ostatnie wywołanie Google API odbiło się o wygasłe logowanie.
         *
         * ## Dlaczego to musi być stan, a nie wynik pojedynczego wywołania
         * Bo dowiadujemy się o tym W ŚRODKU zapytania o kalendarz albo pocztę, a
         * zareagować musi zupełnie inne miejsce: karta konta w Ustawieniach i
         * kontekst budowany dla modelu. Instancja [GoogleAccountManager] jest
         * tworzona na miejscu w każdym z tych miejsc osobno, więc pole instancji
         * nic by nie dało.
         */
        @Volatile
        private var loginExpired = false

        /**
         * Zgłasza błąd wywołania API i rozstrzyga, czy to wygasłe logowanie.
         *
         * @return prawda, gdy trzeba zalogować się ponownie - wołający ma wtedy
         *   powiedzieć to użytkownikowi zamiast udawać, że danych po prostu nie ma
         */
        fun noteApiFailure(error: Throwable?): Boolean {
            if (!ExpiredLogin.looksExpired(error)) return false
            loginExpired = true
            return true
        }

        /** Czy trzeba zalogować się ponownie. */
        fun isLoginExpired(): Boolean = loginExpired

        /** Kasuje stan po udanym logowaniu albo wylogowaniu. */
        fun clearLoginExpired() {
            loginExpired = false
        }
        /**
         * Co pokazać, gdy logowanie wróciło jako przerwane.
         *
         * ## Dlaczego to nie jest zwykłe "anulowano"
         * Ekran zgody Google jest cudzą stroną w przeglądarce. Gdy Google go
         * ZABLOKUJE - "Dostęp zablokowany: aplikacja nie przeszła weryfikacji" -
         * użytkownik może tylko się cofnąć, a do nas wraca dokładnie ten sam wynik,
         * co po świadomej rezygnacji. Nie da się ich odróżnić, więc aplikacja
         * milczała także wtedy, gdy przyczyną była konfiguracja po stronie Google.
         *
         * Stąd komunikat warunkowy: mówi, co zrobić, JEŚLI blokada wystąpiła, i nie
         * twierdzi, że wystąpiła. Kto po prostu się rozmyślił, przeczyta pierwsze
         * zdanie i zignoruje resztę.
         *
         * Blokada dotyczy tej aplikacji z definicji: prosi o `gmail.readonly`, czyli
         * zakres, który Google klasyfikuje jako ZASTRZEŻONY. Aplikacja z takim
         * zakresem w stanie "opublikowana" jest blokowana dla wszystkich do czasu
         * pełnej weryfikacji z płatnym audytem - dla prywatnej aplikacji jedyną
         * sensowną drogą jest tryb testowy z własnym adresem na liście.
         */
        const val CANCELLED_HINT: String =
            "Logowanie przerwane. Jeśli Google pokazało \"Dostęp zablokowany - " +
                "aplikacja nie przeszła weryfikacji\", dodaj swój adres jako " +
                "użytkownika testowego: Google Cloud Console → Ekran zgody OAuth → " +
                "Odbiorcy → Użytkownicy testowi. Stan publikacji musi być " +
                "\"Testowanie\"."

        /**
         * Opis kodu błędu logowania. Bez tego użytkownik widzi liczbę albo nic,
         * a to są przyczyny, z których każda wymaga czegoś zupełnie innego.
         */
        fun describeStatus(statusCode: Int): String = when (statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "Klient OAuth nie jest skonfigurowany dla tej wersji aplikacji " +
                    "(nazwa pakietu + odcisk SHA-1 podpisu). Trzeba go dodać w " +
                    "Google Cloud Console - to konfiguracja po stronie Google, " +
                    "nie usterka telefonu."
            CommonStatusCodes.NETWORK_ERROR ->
                "Brak połączenia z siecią. Włącz internet i spróbuj ponownie."
            CommonStatusCodes.INTERNAL_ERROR ->
                "Usługi Google zgłosiły błąd wewnętrzny. Spróbuj za chwilę."
            CommonStatusCodes.INVALID_ACCOUNT ->
                "Wybrane konto jest niedostępne. Wybierz inne."
            CommonStatusCodes.SIGN_IN_REQUIRED ->
                "Konto wymaga ponownego zalogowania."
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Logowanie anulowane."
            GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS ->
                "Logowanie już trwa - poczekaj na ekran wyboru konta."
            GoogleSignInStatusCodes.SIGN_IN_FAILED ->
                "Logowanie nie powiodło się. Sprawdź, czy Usługi Google Play są " +
                    "aktualne."
            else -> "Logowanie nie powiodło się (kod $statusCode)."
        }

        /** Nazwy zgód po polsku - w komunikacie o brakującej zgodzie. */
        private val SCOPE_NAMES: Map<String, String> = mapOf(
            CalendarScopes.CALENDAR to "Kalendarz",
            CalendarScopes.CALENDAR_EVENTS to "Wydarzenia w kalendarzu",
            GmailScopes.GMAIL_READONLY to "Czytanie poczty",
            GmailScopes.GMAIL_SEND to "Wysyłanie poczty",
            GmailScopes.GMAIL_COMPOSE to "Szkice w poczcie",
            DriveScopes.DRIVE_FILE to "Zapis notatek na Dysku",
            TasksScopes.TASKS to "Listy w Google Tasks"
        )
    }
}
