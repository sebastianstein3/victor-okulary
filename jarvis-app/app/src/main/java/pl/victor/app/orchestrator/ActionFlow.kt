package pl.victor.app.orchestrator

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.AIOrchestrator.Companion.CAMERA_OFF_SPEECH
import pl.victor.app.OrchestratorState
import pl.victor.app.PendingActionConfirmation
import pl.victor.app.TriggerSource
import pl.victor.app.actions.Action
import pl.victor.app.actions.ActionConfirmation
import pl.victor.app.actions.ActionExecutor
import pl.victor.app.actions.ActionMode
import pl.victor.app.actions.ActionResult
import pl.victor.app.actions.ContactResolver
import pl.victor.app.actions.DirectActionExecutor
import pl.victor.app.actions.RouteAssist
import pl.victor.app.audio.AudioManager
import pl.victor.app.conversation.ConversationalMode
import pl.victor.app.data.SettingsRepository
import pl.victor.app.diagnostics.DiagFormat

/**
 * Wykonywanie akcji telefonu (SMS, połączenie, kalendarz, mail, nawigacja,
 * tryby dostępności) razem z bramką potwierdzeń - pytaniem "Wysłać?" na
 * głos, nasłuchem odpowiedzi i oknem w aplikacji.
 *
 * ## Po co osobna klasa
 * Wydzielone z [pl.victor.app.AIOrchestrator] BEZ ZMIANY ZACHOWANIA: ta sama
 * kolejność (najpierw kontakt i mail, potem pytanie, potem wykonanie), te
 * same zdania i wpisy w dzienniku. To jedyne miejsce w aplikacji, które
 * powstrzymuje rzeczy nieodwracalne, więc trzymamy je w całości razem.
 *
 * Akcja czekająca na potwierdzenie ([pendingActionConfirmation]) i nasłuch
 * odpowiedzi należą do tej klasy. Polecenie dla domu po "tak" wraca do
 * orkiestratora przez [runHomeCommand].
 */
internal class ActionFlow(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val audio: AudioManager,
    private val conversationalMode: ConversationalMode,
    private val accessibility: pl.victor.app.accessibility.AccessibilityService,
    private val actionExecutor: ActionExecutor,
    private val directActionExecutor: DirectActionExecutor,
    private val contactResolver: ContactResolver,
    private val emailPreparer: pl.victor.app.actions.EmailPreparer,
    /** Stan orkiestratora - akcja kończy się w nim jak tura. */
    private val state: MutableStateFlow<OrchestratorState>,
    /** Dziennik leniwie - z tego samego powodu co `AIOrchestrator.diag`. */
    private val dziennik: () -> pl.victor.app.diagnostics.DiagnosticLog,
    /** Pytanie pomocnicze do modelu (poprawka maila) - patrz `AIOrchestrator.askModelPlain`. */
    private val askModelPlain: suspend (String) -> String?,
    /** Polecenie dla Home Assistant po "tak" - patrz `AIOrchestrator.runHomeCommand`. */
    private val runHomeCommand: (TriggerSource, String, String) -> Unit
) {
    private val diag: pl.victor.app.diagnostics.DiagnosticLog
        get() = dziennik()

    // Akcja oczekująca na potwierdzenie (null = nic nie czeka)
    private val _pendingActionConfirmation = MutableStateFlow<PendingActionConfirmation?>(null)

    /**
     * Zamyka potwierdzanie GŁOSEM z nowej tury - okno w aplikacji zostaje.
     *
     * Woła to tura, która nie była odpowiedzią na pytanie. Bez tego przez
     * minutę "dobra, dzięki" po zupełnie innej rozmowie dzwoniło do Janusza
     * albo otwierało bramę (przegląd 22.11).
     */
    fun closeVoiceConfirmation() {
        val p = _pendingActionConfirmation.value ?: return
        if (p.createdAtMs == 0L) return
        _pendingActionConfirmation.value = p.copy(createdAtMs = 0L)
        runCatching {
            diag.event(DiagFormat.Phase.AKCJA, "potwierdzenie głosem zamknięte - nowa tura", emptyMap())
        }
    }
    val pendingActionConfirmation: StateFlow<PendingActionConfirmation?> =
        _pendingActionConfirmation.asStateFlow()

    /**
     * Rozwiązuje nazwę kontaktu na numer telefonu dla SendSms/MakeCall.
     * Inne typy akcji przechodzą bez zmian.
     *
     * @return akcja z numerem zamiast nazwy, ta sama akcja gdy `to` już jest
     *         numerem, albo `null` gdy kontaktu nie udało się znaleźć
     */
    private suspend fun resolveContactIfNeeded(action: Action): Action? = when (action) {
        is Action.SendSms -> {
            if (contactResolver.isPhoneNumber(action.to)) {
                action
            } else {
                // "smsto:" nie ma żadnego mechanizmu wyszukiwania po nazwie -
                // bez rozwiązania SMS nigdy by nie doszedł do adresata, więc
                // lepiej zgłosić to wprost niż cicho otworzyć aplikację SMS
                // z odbiorcą, którego nikt nie rozpozna.
                contactResolver.findContact(action.to)?.let { found ->
                    noteResolvedContact(action.to, found)
                    action.copy(to = found.phoneNumber, resolvedName = found.displayName)
                }
            }
        }
        is Action.MakeCall -> {
            if (contactResolver.isPhoneNumber(action.to)) {
                action
            } else {
                // ACTION_DIAL z nazwą czasem trafia w wyszukiwanie T9 dialera
                // (wpisanie liter na klawiaturze telefonu też sugeruje kontakty).
                // Gdy nie mamy dostępu do książki albo kontaktu nie ma, zostawiamy
                // oryginalną nazwę zamiast twardo failować - to jedyna ścieżka,
                // która wcześniej działała bez READ_CONTACTS.
                contactResolver.findContact(action.to)?.let { found ->
                    noteResolvedContact(action.to, found)
                    action.copy(to = found.phoneNumber, resolvedName = found.displayName)
                } ?: action
            }
        }
        else -> action
    }

    /**
     * Zapisuje w dzienniku, KOGO wybraliśmy z książki adresowej.
     *
     * ## Czemu to musi zostawiać ślad
     * Bo pomyłka w tym miejscu kończy się rozmową albo SMS-em do obcej osoby, a
     * z zewnątrz wygląda dokładnie tak samo jak trafienie: asystent mówi „dzwonię
     * do Janusza" i dzwoni. Numer jest w dzienniku ukrywany - wystarczy sama
     * nazwa, żeby dało się rozstrzygnąć, czy wybór był dobry.
     */
    private fun noteResolvedContact(asked: String, found: ContactResolver.Resolved) {
        runCatching {
            diag.event(
                DiagFormat.Phase.AKCJA, "kontakt z książki adresowej",
                mapOf("proszono" to asked, "wybrano" to found.displayName)
            )
        }
    }

    /**
     * Wykonuje wykryte akcje bez udziału AI (szybko, offline).
     * Wspiera SAFE i DIRECT mode.
     */
    fun handleActions(actions: List<Action>, originalText: String) {
        Log.i(TAG, "Executing ${actions.size} action(s) from: \"$originalText\"")

        // NAJPIERW ROZWIĄŻ KONTAKT, POTEM PYTAJ - nie odwrotnie.
        //
        // Kolejność była tu odwrócona i to jest usterka w jedynym miejscu
        // aplikacji, które powstrzymuje rzeczy nieodwracalne: pytanie o zgodę
        // budowało się z NAZWY, KTÓRĄ PADŁA („zadzwonić do Janusza?"), a zamiana
        // nazwy na numer szła dopiero w executeActionsList, PO potwierdzeniu.
        // Człowiek zatwierdzał więc imię, które sam wypowiedział, a dzwoniło do
        // kogoś, kogo wybrał dopasowywacz - i nie miał jak tego zauważyć.
        //
        // Teraz pytanie niesie nazwę Z KSIĄŻKI ADRESOWEJ. Przy dwóch podobnych
        // kontaktach to jedyny moment, w którym da się powiedzieć „nie ten".
        //
        // Rozwiązywanie jest zawieszalne, więc całość idzie w korutynę. Wołający
        // i tak nie czekali na wynik - executeActionsList sam startuje własną -
        // ale kolejność WEWNĄTRZ tej funkcji nie zmienia się ani o krok: nic nie
        // wykonuje się, dopóki potwierdzenie nie zapadnie.
        scope.launch {
            // `?: it` jest tu istotne: nierozwiązany kontakt zostaje surowy i
            // trafia do executeActionsList, które oddaje dotychczasowe
            // „Nie znalazłem kontaktu". Rozwiązany ma już numer w `to`, więc
            // tamtejsze powtórne rozwiązywanie rozpozna go jako numer i nie
            // pójdzie drugi raz do książki adresowej.
            val prepared = actions.map { resolveContactIfNeeded(it) ?: it }
            // MAIL: adres z kontaktów albo ze skrzynki, temat "Re: ..." i wątek -
            // też PRZED pytaniem, z tego samego powodu co kontakt wyżej.
            val ready = mutableListOf<Action>()
            for (a in prepared) {
                when (val r = prepareEmail(a)) {
                    null -> ready.add(a)
                    is pl.victor.app.actions.EmailPreparer.Result.Ready -> ready.add(r.action)
                    is pl.victor.app.actions.EmailPreparer.Result.Problem -> {
                        conversationalMode.onAiStartedSpeaking()
                        audio.speakAndAwait(r.speech, language = settings.getResponseLanguage())
                        state.value = OrchestratorState.Completed(r.speech)
                        conversationalMode.onAiFinishedSpeaking()
                        return@launch
                    }
                }
            }
            handlePreparedActions(ready)
        }
    }

    /**
     * Przygotowanie maila z wpisem w dzienniku - bez treści, tylko adresat,
     * długość i skąd wziął się adres.
     *
     * @return `null`, gdy akcja nie dotyczy poczty
     */
    private suspend fun prepareEmail(a: Action): pl.victor.app.actions.EmailPreparer.Result? {
        val r = try {
            emailPreparer.prepare(a)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Przygotowanie maila nie powiodło się", e)
            pl.victor.app.actions.EmailPreparer.Result.Problem(
                "Nie udało się przygotować maila. Spróbuj jeszcze raz.",
                "wyjątek: ${e.javaClass.simpleName}"
            )
        }
        when (r) {
            null -> {}
            is pl.victor.app.actions.EmailPreparer.Result.Ready -> runCatching {
                val m = r.action
                diag.event(
                    DiagFormat.Phase.AKCJA, "mail przygotowany",
                    mapOf(
                        "adresat" to (m.resolvedName ?: m.to),
                        "skąd" to r.source,
                        "odpowiedź" to m.isReply,
                        "znaków" to m.body.length,
                        "przezApi" to emailPreparer.canSendDirectly()
                    )
                )
            }
            is pl.victor.app.actions.EmailPreparer.Result.Problem -> runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "mail NIE przygotowany",
                    mapOf("powód" to r.reason, "typ" to a.type.name)
                )
            }
        }
        return r
    }

    /**
     * Druga połowa [handleActions], na akcjach z rozwiązanymi już kontaktami.
     *
     * Wydzielona wyłącznie po to, żeby bramka potwierdzeń została dokładnie tym,
     * czym była - jednym ciągiem warunków bez korutyn w środku.
     */
    private fun handlePreparedActions(prepared: List<Action>) {
        val mode = ActionMode.fromName(settings.getActionMode())
        // Odpowiedź na wiadomość: ustalamy adresata PRZED pytaniem, żeby
        // "Wysłać?" mówiło, do kogo i gdzie naprawdę pójdzie.
        val actions = prepared.map { a ->
            if (a is Action.ReplyMessage && a.resolvedName == null) {
                pl.victor.app.messages.MessageInbox.adresat(a.to)
                    ?.let { m -> a.copy(resolvedName = "${m.nadawca}, ${m.aplikacja}", klucz = m.klucz) } ?: a
            } else {
                a
            }
        }
        // Wysłanie wiadomości jest nieodwracalne - pytamy niezależnie od trybu
        // akcji, chyba że użytkownik wyłączył to w ustawieniach.
        val replyConfirm = settings.isMessageReplyConfirmed() &&
            actions.any { it is Action.ReplyMessage && it.resolvedName != null }
        Log.d(TAG, "Action mode: $mode")

        // W trybie DIRECT - sprawdź czy akcja wymaga potwierdzenia.
        //
        // Kalendarz pyta ZAWSZE, także w trybie SAFE, bo w SAFE też zapisujemy
        // go teraz naprawdę (patrz executeActionsList). Wcześniej SAFE otwierał
        // formularz w aplikacji kalendarza i to kliknięcie "Zapisz" było całym
        // potwierdzeniem - skoro formularza już nie ma, pytanie musi paść tutaj.
        val calendarViaApi = actions.any { it is Action.CreateCalendarEvent } &&
            directActionExecutor.canWriteCalendarDirectly()
        // Mail przez Gmail API idzie naprawdę i nieodwracalnie - pytamy ZAWSZE,
        // w każdym trybie akcji, bez wyłącznika (tak jak kalendarz).
        val emailViaApi = actions.any { it is Action.SendEmail } &&
            directActionExecutor.canSendEmailDirectly()
        if (mode == ActionMode.DIRECT || calendarViaApi || replyConfirm || emailViaApi) {
            // POTWIERDZENIE MUSI OBJĄĆ KAŻDĄ AKCJĘ Z LISTY, NIE TYLKO PIERWSZĄ.
            //
            // Niżej `executeActionsList(actions)` wykonuje CAŁĄ listę. Pytanie o
            // samą pierwszą znaczyło, że przy dwóch znacznikach od modelu
            // użytkownik potwierdzał "Wyślij SMS do Ani?", a przy okazji
            // wykonywało się połączenie, o które nikt nie zapytał. To jedyne
            // miejsce w całym przeglądzie, które mogło zrobić coś
            // nieodwracalnego bez zgody.
            val required = actions.mapNotNull {
                if (it is Action.ReplyMessage) {
                    if (replyConfirm && it.resolvedName != null) {
                        ActionConfirmation.Required(
                            title = "Wysłać odpowiedź?",
                            message = "Do: ${it.resolvedName}. Treść: ${it.body}",
                            confirmText = "📤 Wyślij"
                        )
                    } else {
                        null
                    }
                } else {
                    directActionExecutor.canExecuteDirect(it) as? ActionConfirmation.Required
                }
            }
            if (required.isNotEmpty()) {
                val single = required.singleOrNull()
                val title = single?.title ?: "Potwierdź wszystko, co zaraz zrobię"
                val message = single?.message
                    ?: required.joinToString("\n") { "• ${it.message}" }
                // Zapisz akcje do późniejszego wykonania.
                //
                // TE SAME akcje, które opisało pytanie - z rozwiązanymi już
                // kontaktami. Gdyby tu wróciła lista sprzed rozwiązania, człowiek
                // potwierdzałby jedno, a wykonywałoby się drugie.
                _pendingActionConfirmation.value = PendingActionConfirmation(
                    actions = actions,
                    title = title,
                    message = message,
                    confirmText = required.first().confirmText,
                    cancelText = required.first().cancelText
                )
                // Zapytaj GŁOSEM i wysłuchaj odpowiedzi.
                //
                // Dotąd jedyną drogą było kliknięcie w oknie na telefonie -
                // czyli asystent, którego cała reszta działa bez rąk, na
                // ostatnim kroku kazał sięgnąć po telefon. Zgłoszone: "żeby
                // dało się zatwierdzić głosowo, a nie klikając".
                //
                // Okno zostaje: gdy odpowiedź jest niejednoznaczna albo nie
                // padnie wcale, decyzja ma dokąd wrócić.
                //
                // Mail czytamy w kolejności adresat - temat - treść, a "Wysłać?"
                // pada NA KOŃCU: decyzja po usłyszeniu całości.
                val singleEmail = (actions.singleOrNull() as? Action.SendEmail)
                    ?.takeIf { single != null }
                if (singleEmail != null) {
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.AKCJA, "mail: pytam o wysłanie",
                            mapOf(
                                "adresat" to (singleEmail.resolvedName ?: singleEmail.to),
                                "odpowiedź" to singleEmail.isReply,
                                "znaków" to singleEmail.body.length
                            )
                        )
                    }
                }
                listenForConfirmation(
                    singleEmail?.let {
                        pl.victor.app.actions.EmailVoice.confirmationSpeech(
                            recipient = it.resolvedName ?: it.to,
                            subject = it.subject,
                            body = it.body,
                            isReply = it.isReply
                        )
                    } ?: "$title. $message"
                )
                return
            }
        }

        // Tryb SAFE lub brak potwierdzenia - wykonaj
        executeActionsList(actions)
    }

    /**
     * Wykonuje listę akcji (używane zarówno po wykryciu jak i po potwierdzeniu).
     */
    private fun executeActionsList(rawActions: List<Action>) {
        // PROWADZENIE Z ASYSTENTEM CZY BEZ - rozstrzygane TUTAJ, raz.
        //
        // Powiedziane wprost ("prowadź do apteki z asystentem") wygrywa; gdy nie
        // padło, decyduje ustawienie. Rozstrzygamy przed wykonaniem, żeby
        // wykonawca i wypowiadany komunikat mówiły o tym samym - inaczej
        // asystent meldowałby jedno, a robił drugie.
        val actions = rawActions.map { action ->
            if (action is Action.Navigate && action.assist == RouteAssist.FROM_SETTINGS) {
                action.copy(
                    assist = if (settings.isRouteAssistEnabled()) RouteAssist.ON
                    else RouteAssist.OFF
                )
            } else {
                action
            }
        }

        // Obsługa accessibility (nie wymaga trybu DIRECT/SAFE)
        val wszystkieDostępności = actions.filter {
            it is Action.ReadText || it is Action.DescribeScene ||
            it is Action.StartNavigation || it is Action.StopAccessibility
        }
        // Tryby dla niewidomych patrzą kamerą - w trybie bez kamery zostaje
        // tylko ich wyłączenie.
        val accessibilityActions = if (settings.isCameraFree()) {
            wszystkieDostępności.filter { it is Action.StopAccessibility }
        } else {
            wszystkieDostępności
        }
        if (accessibilityActions.size < wszystkieDostępności.size) {
            audio.speak(CAMERA_OFF_SPEECH, language = settings.getResponseLanguage())
            if (actions.size == wszystkieDostępności.size && accessibilityActions.isEmpty()) {
                state.value = OrchestratorState.Completed(CAMERA_OFF_SPEECH)
                conversationalMode.onAiFinishedSpeaking()
                return
            }
        }

        if (accessibilityActions.isNotEmpty()) {
            scope.launch {
                accessibilityActions.forEach { action ->
                    when (action) {
                        // W trybie czytania powtórzone "czytaj" znaczy KOLEJNĄ
                        // stronę, nie ponowne włączenie trybu. Zdjęcie powstaje
                        // dokładnie wtedy - patrz AccessibilityService.requestRead.
                        is Action.ReadText ->
                            if (!accessibility.requestRead()) accessibility.enableReadText()
                        is Action.DescribeScene -> {
                            // Jednorazowy opis
                            val desc = accessibility.describeOnce()
                            if (desc != null) {
                                audio.speak(desc, language = "pl")
                            }
                        }
                        is Action.StartNavigation -> accessibility.enableNavigate()
                        is Action.StopAccessibility -> accessibility.disable()
                        // Lista jest wcześniej przefiltrowana do akcji dostępności,
                        // ale Kotlin wymaga wyczerpania when po typie Action.
                        else -> Log.w(TAG, "Nieoczekiwana akcja w trybie dostępności: ${action.type}")
                    }
                }
            }
            // Jeśli wszystko to accessibility, zwracamy bezpośrednio.
            // Idle nie przechodzi przez wznowienie nasłuchu (patrz init), więc
            // tryb konwersacyjny wznawiamy tu wprost.
            if (actions.size == accessibilityActions.size) {
                state.value = OrchestratorState.Idle
                conversationalMode.onAiFinishedSpeaking()
                return
            }
        }

        // Trasa z asystentem: mapy mówią, GDZIE SKRĘCIĆ, a my - W CO SIĘ NIE
        // WYWRÓCIĆ. To są dwie różne rzeczy i dopiero razem dają to, o co w tej
        // aplikacji chodzi; osobno każda z nich ma sens i każda działa sama.
        //
        // Włączamy przed odpaleniem map, bo za chwilę na wierzchu będzie cudza
        // aplikacja i nasz kod nie dostanie już okazji.
        if (!settings.isCameraFree() &&
            actions.any { it is Action.Navigate && it.assist == RouteAssist.ON }
        ) {
            scope.launch { accessibility.enableNavigate() }
        }

        val mode = ActionMode.fromName(settings.getActionMode())

        scope.launch {
            val results = actions.map { rawAction ->
                // SmartActionDetector wyciąga z mowy samo słowo po "do"/"pod" - to
                // zwykle nazwa kontaktu, nie numer. ActionExecutor (tryb SAFE) wsadza
                // ten tekst wprost do intencji "tel:"/"smsto:" - Android nie rozwiązuje
                // tam nazw, więc "zadzwoń do mamy" bez tego kroku nigdy nie działało
                // w domyślnym trybie. Rozwiązanie robimy raz, przed obiema ścieżkami.
                val action = resolveContactIfNeeded(rawAction)
                if (action == null) {
                    val name = (rawAction as? Action.SendSms)?.to
                        ?: (rawAction as? Action.MakeCall)?.to
                    val failure = ActionResult.Failed("Nie znalazłem kontaktu „$name” w książce adresowej.")
                    Log.w(TAG, "Kontakt nierozwiązany: $name")
                    return@map rawAction to failure
                }

                // Spróbuj DIRECT jeśli tryb DIRECT i akcja to obsługuje
                // Kalendarz: pisz przez konto Google, jeśli jest podłączone -
                // NIEZALEŻNIE od trybu akcji. To była przyczyna zgłoszenia
                // "mówi, że dodaje coś do kalendarza, a finalnie nie dodaje":
                // domyślny tryb SAFE odpalał Intent, czyli otwierał formularz na
                // telefonie i meldował sukces, choć nikt niczego nie zapisał.
                val calendarViaApi = action is Action.CreateCalendarEvent &&
                    directActionExecutor.canWriteCalendarDirectly()
                // Mail z włączoną pocztą: wysyłka przez Gmail API (zatwierdzona
                // wyżej pytaniem "Wysłać?"). Dotąd nawet po potwierdzeniu szedł
                // do aplikacji poczty, bo tej gałęzi tu nie było.
                val emailViaApi = action is Action.SendEmail &&
                    directActionExecutor.canSendEmailDirectly()
                val result = if (calendarViaApi || emailViaApi || (mode == ActionMode.DIRECT &&
                    (action is Action.SendSms || action is Action.MakeCall ||
                        action is Action.CreateCalendarEvent))) {
                    val direct = directActionExecutor.executeDirect(action)
                    if (action is Action.SendEmail) {
                        runCatching {
                            diag.event(
                                DiagFormat.Phase.AKCJA,
                                if (direct is ActionResult.Success) "mail WYSŁANY" else "mail NIE wysłany",
                                mapOf(
                                    "adresat" to (action.resolvedName ?: action.to),
                                    "odpowiedź" to action.isReply,
                                    "znaków" to action.body.length,
                                    "powód" to (direct as? ActionResult.Failed)?.reason
                                )
                            )
                        }
                    }
                    // Fallback do SAFE jeśli direct się nie udało - ale nie dla
                    // maila: po nieudanej wysyłce otwarcie aplikacji poczty
                    // zgłosiłoby "Otworzyłem", a człowiek musi usłyszeć, że mail
                    // NIE poszedł.
                    if (direct is ActionResult.Failed && !emailViaApi) {
                        Log.w(TAG, "DIRECT failed, falling back to SAFE: ${direct.reason}")
                        actionExecutor.execute(action)
                    } else direct
                } else {
                    actionExecutor.execute(action)
                }
                Log.d(TAG, "Action ${action.type}: $result")
                action to result
            }

            // Zbuduj odpowiedź głosową
            val speech = when {
                results.all { it.second is ActionResult.Success } -> {
                    // Z WYNIKU, nie z opisu akcji. Opis akcji to zamiar ("dodaj
                    // do kalendarza") i brzmi jak wykonany, cokolwiek się
                    // wydarzyło - a część dróg tylko otwiera okno i czeka na
                    // użytkownika. Wynik wie, co naprawdę zaszło.
                    // Akcje bez własnego komunikatu zbieramy w JEDNO zdanie, a
                    // nie w łańcuszek "OK, ... OK, ...". Te z komunikatem mówią
                    // same za siebie i idą po nim.
                    val plain = results
                        .filter { (_, r) -> (r as ActionResult.Success).message == GENERIC_ACTION_SUCCESS }
                        .map { (action, _) -> action.description.lowercase() }
                    val spoken = results
                        .map { (_, r) -> (r as ActionResult.Success).message }
                        .filter { it != GENERIC_ACTION_SUCCESS }
                    val head = if (plain.isEmpty()) null else "OK, ${plain.joinToString(", ")}."
                    (listOfNotNull(head) + spoken).joinToString(" ")
                }
                results.any { it.second is ActionResult.Failed } -> {
                    val failed = results.filter { it.second is ActionResult.Failed }
                    "Nie udało się: ${failed.joinToString { (it.second as ActionResult.Failed).reason }}"
                }
                else -> "Wykonano"
            }

            // Mów i pokaż. Czekamy na koniec wypowiedzi, bo w trybie
            // konwersacyjnym zaraz potem wraca nasłuch - inaczej mikrofon
            // łapałby potwierdzenie akcji jako kolejne pytanie.
            conversationalMode.onAiStartedSpeaking()
            audio.speakAndAwait(speech, language = settings.getResponseLanguage())
            state.value = OrchestratorState.Completed(speech)
            conversationalMode.onAiFinishedSpeaking()
        }
    }

    /**
     * Zadaje pytanie o potwierdzenie na głos i słucha odpowiedzi.
     *
     * ## Dlaczego niejednoznaczna odpowiedź NIE wykonuje akcji
     * Bo te akcje wysyłają maile, dzwonią i dodają wydarzenia - żadnej z nich nie
     * da się cofnąć słowem. Przy głosie nie ma drugiego ekranu, na którym dałoby
     * się złapać pomyłkę, więc wszystko poza wyraźnym "tak" zostawia decyzję
     * użytkownikowi. Okno na telefonie czeka dalej.
     *
     * @param question pytanie do wypowiedzenia
     */
    /**
     * Nasłuch potwierdzenia - do przerwania, gdy rusza nowa tura.
     *
     * Bez tego naciśnięcie przycisku w trakcie pytania "Dodać do kalendarza?"
     * zostawiało DWA nasłuchy walczące o jeden mikrofon: ten i ten z nowej
     * tury. Zgłoszone jako "gdy ekran potwierdzenia się wyświetla, zadawanie
     * pytań się blokuje".
     */
    private var confirmationJob: kotlinx.coroutines.Job? = null

    private fun listenForConfirmation(question: String) {
        confirmationJob?.cancel()
        confirmationJob = scope.launch {
            val language = settings.getResponseLanguage()
            diag.event(
                DiagFormat.Phase.AKCJA, "pytam o potwierdzenie",
                mapOf("pytanie" to question.take(80))
            )
            // ŁĄCZE DO MIKROFONU ZESTAWU ZESTAWIAMY W TLE, W TRAKCIE PYTANIA.
            //
            // Do mikrofonu okularów prowadzi wyłącznie profil rozmowy (SCO), a
            // jego negocjacja trwa do czterech sekund. Gdyby szła po pytaniu,
            // użytkownik usłyszałby "Dodać do kalendarza?" i musiał odczekać w
            // ciszy, zanim ktokolwiek zacznie go słuchać - a "tak" pada od razu
            // po pytaniu i przepadłoby. Czytanie pytania trwa mniej więcej tyle
            // samo, więc jedno chowa się za drugim.
            val routing = async { runCatching { audio.beginConversationRouting() }
                .getOrDefault(false) }
            // speakAndAwait, nie speak: nasłuch nie może ruszyć w trakcie
            // czytania pytania, bo nagrałby własny głos asystenta.
            audio.speakAndAwait(question, language = language)
            val routed = routing.await()

            // PROFIL ROZMOWY ZWALNIAMY W `finally`, A NIE PO NASŁUCHU.
            //
            // Odkąd ten nasłuch da się PRZERWAĆ (robi to każda nowa tura -
            // patrz `confirmationJob`), zwolnienie ustawione po nim wykonałoby
            // się tylko wtedy, gdy nikt nie przerwał. Naciśnięcie przycisku w
            // trakcie pytania zostawiałoby podniesione SCO na zawsze - czyli
            // dokładnie tę usterkę, przez którą wczoraj urywały się odpowiedzi.
            val heard = try {
                if (_pendingActionConfirmation.value == null) return@launch
                runCatching {
                    conversationalMode.listenOnce(
                        languageTag = languageTagFor(language),
                        timeoutMs = CONFIRMATION_TIMEOUT_MS
                    )
                }.getOrNull()
            } finally {
                // withContext(NonCancellable): zwykłe wywołanie zawieszalne w
                // `finally` anulowanej korutyny rzuca natychmiast i nie zdąży
                // niczego zwolnić.
                if (routed) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        audio.endConversationRouting()
                    }
                }
            }

            // PRZERWANY NASŁUCH TO NIE "NIEZROZUMIAŁA ODPOWIEDŹ".
            //
            // Nową turą ten nasłuch jest anulowany, ale runCatching wyżej
            // połyka anulowanie i oddaje null - a to parsowało się jako
            // UNCLEAR i "Nie odczytałem odpowiedzi..." wchodziło w nową turę,
            // ucinając jej sygnał nasłuchu albo odpowiedź.
            currentCoroutineContext().ensureActive()

            // Użytkownik mógł w tym czasie kliknąć w oknie - wtedy nie ma już
            // czego potwierdzać i nie wolno wykonać akcji drugi raz.
            if (_pendingActionConfirmation.value == null) return@launch

            // MAIL: poprawka ("popraw...", "zmień...") PRZED tak/nie - parse()
            // czytałoby "popraw, napisz że nie przyjdę" jako odmowę.
            val emailDraft = _pendingActionConfirmation.value?.actions?.singleOrNull() as? Action.SendEmail
            val remark = emailDraft?.let { pl.victor.app.actions.EmailVoice.correction(heard) }
            if (emailDraft != null && remark != null) {
                reviseEmailDraft(emailDraft, remark)
                return@launch
            }
            // MAIL: cisza to rezygnacja (szkic zostaje w Gmailu) - mail nie
            // może wisieć bez końca w oknie, którego nikt w okularach nie widzi.
            if (emailDraft != null && pl.victor.app.actions.EmailVoice.isSilence(heard)) {
                diag.event(DiagFormat.Phase.AKCJA, "mail: cisza po pytaniu o wysłanie - anuluję")
                cancelAction()
                return@launch
            }

            when (pl.victor.app.actions.ConfirmationReply.parse(heard)) {
                pl.victor.app.actions.ConfirmationReply.Reply.YES -> {
                    Log.i(TAG, "Potwierdzenie głosem: tak")
                    diag.event(
                        DiagFormat.Phase.AKCJA, "potwierdzenie głosem",
                        mapOf("odpowiedź" to "TAK", "usłyszano" to heard?.take(40))
                    )
                    confirmAction()
                }
                pl.victor.app.actions.ConfirmationReply.Reply.NO -> {
                    Log.i(TAG, "Potwierdzenie głosem: nie")
                    diag.event(
                        DiagFormat.Phase.AKCJA, "potwierdzenie głosem",
                        mapOf("odpowiedź" to "NIE", "usłyszano" to heard?.take(40))
                    )
                    cancelAction()
                }
                pl.victor.app.actions.ConfirmationReply.Reply.UNCLEAR -> {
                    diag.event(
                        DiagFormat.Phase.AKCJA, "potwierdzenie NIEROZSTRZYGNIĘTE",
                        mapOf("usłyszano" to heard?.take(40))
                    )
                    // Milczenie i wahanie traktujemy tak samo: nie wykonujemy.
                    Log.i(TAG, "Potwierdzenie głosem nierozstrzygnięte: \"$heard\"")
                    audio.speak(
                        "Nie odczytałem odpowiedzi, więc nic nie robię. " +
                            "Powiedz \"tak\" albo potwierdź w aplikacji.",
                        language = language
                    )
                }
            }
        }
    }

    /**
     * Potwierdzenie akcji przez usera (z dialogu albo głosem).
     */
    fun confirmAction() {
        val pending = _pendingActionConfirmation.value
        if (pending != null) {
            Log.i(TAG, "User confirmed action: ${pending.actions}")
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "akcja POTWIERDZONA",
                    mapOf("co" to (if (pending.homeCommand != null) "DOM" else pending.actions.joinToString { it.type.name }))
                )
            }
            confirmationJob?.cancel()
            _pendingActionConfirmation.value = null
            // Polecenie dla domu (zamek, brama, alarm) nie jest akcją telefonu -
            // po "tak" idzie do Home Assistant.
            val dom = pending.homeCommand
            if (dom != null) {
                runHomeCommand(TriggerSource.VOICE, dom, dom)
            } else {
                executeActionsList(pending.actions)
            }
        }
    }

    /**
     * Anulowanie akcji przez usera.
     */
    fun cancelAction() {
        val pending = _pendingActionConfirmation.value
        if (pending != null) {
            Log.i(TAG, "User cancelled action: ${pending.actions}")
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "akcja ANULOWANA",
                    mapOf("co" to pending.actions.joinToString { it.type.name })
                )
            }
            confirmationJob?.cancel()
            _pendingActionConfirmation.value = null
            // Niewysłany mail zostaje w szkicach Gmaila - ułożona treść nie
            // przepada przez jedno "nie" albo chwilę ciszy.
            val emailDraft = pending.actions.singleOrNull() as? Action.SendEmail
            if (emailDraft != null && directActionExecutor.canSendEmailDirectly()) {
                scope.launch {
                    val saved = runCatching { directActionExecutor.saveEmailDraft(emailDraft) }
                        .getOrDefault(false)
                    runCatching {
                        diag.event(
                            DiagFormat.Phase.AKCJA, "mail anulowany",
                            mapOf(
                                "adresat" to (emailDraft.resolvedName ?: emailDraft.to),
                                "szkic" to saved
                            )
                        )
                    }
                    val speech = if (saved) "Anulowano. Mail został w szkicach Gmaila." else "Anulowano."
                    conversationalMode.onAiStartedSpeaking()
                    audio.speakAndAwait(speech, language = settings.getResponseLanguage())
                    state.value = OrchestratorState.Idle
                    conversationalMode.onAiFinishedSpeaking()
                }
                return
            }
            audio.speak("Anulowano", language = settings.getResponseLanguage())
            state.value = OrchestratorState.Idle
            // Idle nie wznawia nasłuchu samo (patrz init) - a anulowanie kończy
            // turę tak samo jak wykonanie akcji.
            conversationalMode.onAiFinishedSpeaking()
        }
    }

    /**
     * Poprawka szkicu maila według uwagi użytkownika - i ponowne "Wysłać?".
     *
     * Model dostaje szkic i uwagę, oddaje temat i treść (patrz
     * [pl.victor.app.actions.EmailVoice.revisePrompt]). Adresat i wątek się NIE
     * zmieniają - tych model nie dotyka, a odpowiedź zostaje odpowiedzią. Gdy
     * model zawiedzie, wraca poprzednia wersja, a nie pusty mail.
     *
     * Osobna korutyna, bo bywa wołane z wnętrza nasłuchu potwierdzenia, który
     * nowe pytanie zaraz przerywa.
     */
    fun reviseEmailDraft(draft: Action.SendEmail, remark: String) {
        confirmationJob?.cancel()
        _pendingActionConfirmation.value = null
        val who = draft.resolvedName ?: draft.to
        runCatching {
            diag.event(
                DiagFormat.Phase.AKCJA, "mail: poprawka szkicu",
                mapOf("adresat" to who, "uwaga" to remark.take(120))
            )
        }
        scope.launch {
            audio.speak("Poprawiam.", language = settings.getResponseLanguage())
            val reply = askModelPlain(
                pl.victor.app.actions.EmailVoice.revisePrompt(who, draft.subject, draft.body, remark)
            )
            val revised = pl.victor.app.actions.EmailVoice.parseRevision(reply, draft.subject)
            val next = if (revised == null) {
                runCatching {
                    diag.event(DiagFormat.Phase.AKCJA, "mail: poprawka nieudana - zostaje poprzednia wersja")
                }
                audio.speakAndAwait(
                    "Nie udało się poprawić - zostaje poprzednia wersja.",
                    language = settings.getResponseLanguage()
                )
                draft
            } else {
                // Odpowiedź trzyma temat "Re: ..." - zmiana tematu rozbiłaby wątek
                // u adresata, który skleja go po temacie.
                draft.copy(
                    subject = if (draft.isReply) draft.subject else revised.first,
                    body = revised.second
                )
            }
            runCatching {
                diag.event(
                    DiagFormat.Phase.AKCJA, "mail: nowa wersja",
                    mapOf("adresat" to who, "znaków" to next.body.length)
                )
            }
            handlePreparedActions(listOf(next))
        }
    }

    /**
     * Pyta o potwierdzenie spoza akcji telefonu (dom, rozkład w Mapach): to
     * samo okno i ten sam nasłuch, co przy akcjach - patrz [listenForConfirmation].
     */
    fun requestConfirmation(pending: PendingActionConfirmation, question: String) {
        _pendingActionConfirmation.value = pending
        listenForConfirmation(question)
    }

    /**
     * Nowa tura kończy czekanie na odpowiedź głosem (dwa nasłuchy na jednym
     * mikrofonie blokują się nawzajem). Samo okno zostaje.
     */
    fun stopListeningForConfirmation() {
        confirmationJob?.cancel()
    }

    companion object {
        /**
         * Ile czekamy na "tak" albo "nie" po pytaniu o potwierdzenie.
         *
         * Krócej niż zwykły nasłuch: to jest odpowiedź na pytanie zamknięte, więc
         * albo pada od razu, albo użytkownik sięga po telefon.
         */
        private const val CONFIRMATION_TIMEOUT_MS = 7_000L

        /**
         * Komunikat, który [ActionExecutor] zwraca, gdy nie ma nic ciekawego do
         * powiedzenia. Wtedy - i tylko wtedy - opowiadamy o akcji jej własnym
         * opisem. Bierzemy go STAMTĄD, żeby zmiana po tamtej stronie nie
         * rozjechała tego porównania po cichu.
         */
        private const val GENERIC_ACTION_SUCCESS = ActionExecutor.GENERIC_SUCCESS

        /** Ten sam znacznik logcata co orkiestrator - filtry logów działają jak dotąd. */
        private const val TAG = "AIOrchestrator"
    }
}
