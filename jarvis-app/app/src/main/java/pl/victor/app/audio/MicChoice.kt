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
     * Trzy powody, każdy wystarczający sam z siebie:
     *  - człowiek poprosił o mikrofon okularów ([wantsGlassesMic]) - wtedy warto
     *    spróbować jeszcze raz, nawet jeśli orkiestrator przed chwilą nie dał
     *    rady: na API 31+ to tylko pytanie o listę urządzeń do rozmowy, a ta
     *    potrafi się uzupełnić dopiero po wybudzeniu okularów;
     *  - łącze i tak już stoi ([overSco]) - skoro płacimy, to korzystajmy;
     *  - nie ma strumienia BLE ([bleStreamLive] `false`) - nie zostaje wtedy
     *    żadna inna droga do dźwięku.
     */
    fun useBluetoothMic(
        wantsGlassesMic: Boolean,
        overSco: Boolean,
        bleStreamLive: Boolean
    ): Boolean = wantsGlassesMic || overSco || !bleStreamLive

    /**
     * Czy tekst rozpoznany przez telefon należy traktować jako niepewny.
     *
     * Prawda dokładnie wtedy, gdy pytanie szło z okularów, człowiek prosił o ich
     * mikrofon, a profil rozmowy nie wstał - czyli gdy zbierał je telefon leżący
     * w kieszeni, na dystans, na jaki nikt go nie przewidywał. Wtedy nagranie z
     * okularów warto przepisać MIMO gotowego tekstu z telefonu; poza tym
     * przypadkiem gotowy tekst wygrywa, bo jest za darmo.
     */
    fun phoneMicNotTrusted(
        fromGlasses: Boolean,
        wantsGlassesMic: Boolean,
        overSco: Boolean
    ): Boolean = fromGlasses && wantsGlassesMic && !overSco
}
