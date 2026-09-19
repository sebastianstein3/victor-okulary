package pl.victor.app.audio

/**
 * Skąd bierzemy pytanie: z mikrofonu okularów czy z mikrofonu telefonu.
 *
 * ## Dlaczego to mieszka osobno
 * Decyzja jest czystą logiką trzech warunków, ale zapisana była wprost w
 * argumencie wywołania w [pl.victor.app.AIOrchestrator] - czyli w miejscu,
 * którego nie da się uruchomić bez Androida i okularów. Nikt jej nigdy nie
 * sprawdził i przez to przeżyła w niej usterka, którą widać w jednym wierszu
 * tabeli prawdy: wyrażenie `overSco || !bleStreamLive` NIE ODWOŁYWAŁO SIĘ do
 * wyboru człowieka ani razu.
 *
 * Skutek był taki, że przy turze z okularów (strumień BLE żyje) i nieudanym
 * zestawieniu profilu rozmowy wychodził FAŁSZ - jawny zakaz sięgania po mikrofon
 * okularów, wydany komuś, kto właśnie o ten mikrofon poprosił. Zgłoszone
 * dosłownie: "wybrałem Pytania mikrofonem okularów, a wciąż włącza się mikrofon
 * w telefonie".
 */
object MicChoice {

    /**
     * Czy rozpoznawanie mowy ma próbować zająć profil rozmowy zestawu.
     *
     * ## Czemu NIE MA tu wyboru człowieka - i czemu raz go tu wstawiłem
     * Wstawiłem, bo uznałem, że ustawienie "Pytania mikrofonem okularów" nie
     * bierze udziału w decyzji. To było BŁĘDNE odczytanie kodu: ustawienie
     * decyduje PIĘTRO WYŻEJ, w [pl.victor.app.AIOrchestrator], o tym, czy w
     * ogóle podjąć próbę zestawienia profilu:
     *
     *     held = if (micStreamLive && !wantsGlassesMic) false
     *            else audio.beginConversationRouting()
     *
     * a [overSco] to już WYNIK tej próby. Prośba człowieka jest więc w tym
     * wyrażeniu obecna - tyle że jako skutek, nie jako warunek.
     *
     * Dopisanie `wantsGlassesMic ||` zrobiło z tego coś zupełnie innego. To
     * ustawienie stoi domyślnie na `true`, więc wynik stał się prawdą
     * praktycznie w każdej turze, a [pl.victor.app.conversation.SpeechToText]
     * zaczął brać profil rozmowy DRUGI RAZ, przy każdym rozpoznaniu. Dokładnie
     * przed tym ostrzega komentarz nad tamtym wywołaniem - i dokładnie to
     * usunęły kiedyś commity d219557 i 17b9555.
     *
     * Zgłoszone natychmiast po tej zmianie: "teraz prawie nic nie działa,
     * wszystko działa wolno". Cena to do czterech sekund negocjacji przed
     * KAŻDYM rozpoznaniem plus zawieszone A2DP, czyli okulary zamienione w
     * urządzenie "do połączeń" - za drugą próbę, która niczego nie dokłada,
     * bo pierwsza właśnie zawiodła z tego samego powodu.
     *
     * Zostają więc dwa powody, oba o stanie sprzętu, nie o życzeniu:
     *  - łącze już stoi ([overSco]) - skoro płacimy, to korzystajmy;
     *  - nie ma strumienia BLE ([bleStreamLive] `false`) - nie zostaje wtedy
     *    żadna inna droga do dźwięku.
     */
    fun useBluetoothMic(
        overSco: Boolean,
        bleStreamLive: Boolean
    ): Boolean = overSco || !bleStreamLive

    /**
     * Czy tekst rozpoznany przez telefon powstał NIE tym mikrofonem, o który
     * poproszono.
     *
     * Prawda dokładnie wtedy, gdy pytanie szło z okularów, człowiek prosił o ich
     * mikrofon, a profil rozmowy nie wstał.
     *
     * ## Służy wyłącznie do dziennika
     * Próbowałem na tej podstawie przepisywać nagranie z okularów MIMO gotowego
     * tekstu z telefonu. Odwołuję to: pomiar z pięciu dzienników mówi, że ta
     * droga nie oddała tekstu ANI RAZU w około czterdziestu turach, kosztując od
     * 1,1 do 5,9 sekundy. Dokładanie jej w turze, która i tak jest wolna, to
     * pewny koszt za niepewny zysk - a zgłoszenie brzmiało "wszystko działa
     * wolno".
     *
     * Wraca dopiero wtedy, gdy dziennik pokaże, że transkrypcja z okularów w
     * ogóle coś oddaje.
     */
    fun phoneMicNotTrusted(
        fromGlasses: Boolean,
        wantsGlassesMic: Boolean,
        overSco: Boolean
    ): Boolean = fromGlasses && wantsGlassesMic && !overSco
}
