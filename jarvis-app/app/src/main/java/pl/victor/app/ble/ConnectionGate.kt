package pl.victor.app.ble

/**
 * Rozstrzyga, czy „usługi GATT wykryte" oznacza NOWE połączenie z okularami,
 * czy tylko echo tego samego, które już obsłużyliśmy.
 *
 * ## Dlaczego to musi być osobna decyzja
 * Cała konfiguracja okularów jest robiona RAZ NA POŁĄCZENIE: powitanie
 * producenta (czas, informacje, głośność, `openBT`, liczba plików), włączenie
 * wykrywania frazy i subskrypcja mikrofonu. Dotąd pilnował tego znacznik
 * kasowany wyłącznie ramką `BLE_GATT_DISCONNECTED`.
 *
 * Ramka rozłączenia potrafi nie przyjść. Dziennik z 11 września jest tego
 * dowodem: sesja od 15:52, zdjęcie udane o 16:01, potem `POŁĄCZONO` o 17:33 -
 * i ANI JEDNEGO `ROZŁĄCZONO` po drodze. Okulary wróciły na nowym łączu, a
 * aplikacja uznała, że to wciąż to samo, które skonfigurowała półtorej godziny
 * wcześniej. Nie poszło więc nic: ani `openBT` (w dzienniku `a2dp=false`), ani
 * wykrywanie frazy, ani ponowna subskrypcja mikrofonu. Od 17:33 okulary nie
 * zrobiły już żadnego zdjęcia.
 *
 * ## Jak odróżniamy echo od nowego łącza
 * Vendor SDK rozgłasza `service_discovered` drugi raz po uzbrojeniu kanału
 * zapisu - to echo i powitania nie powtarzamy. Sprawdzone w bajtkodzie AAR
 * (`javap` na `classes.jar`): `BleOperateManager.setReady` robi
 * `postDelayed(BleOperateManager$3, 2500)` i tylko wtedy, gdy `ready` było
 * jeszcze `false`, a sam `BleOperateManager$3.run()` rozgłasza WYŁĄCZNIE
 * `service_discovered` - żadnego `gatt_connected` ani `start_connect`.
 * Dlatego znak pierwszy niżej nie może wziąć echa za nowe łącze.
 * Nowe łącze poznajemy po JEDNYM z dwóch znaków, bo żaden sam nie wystarcza:
 *
 * 1. **Zapowiedź połączenia** (`BLE_START_CONNECT` / `BLE_GATT_CONNECTED`) od
 *    ostatniego wykrycia usług - łapie ponowne łączenie tak szybkie, że nie
 *    zmieściłoby się w oknie czasu.
 * 2. **Upływ [ECHO_WINDOW_MS]** od ostatniego wykrycia usług - łapie dokładnie
 *    ten przypadek z dziennika, w którym do aplikacji nie doszła ŻADNA ramka
 *    stanu poza samym wykryciem usług.
 *
 * Pomyłka w jedną stronę kosztuje powtórzone powitanie (same odczyty i
 * synchronizacje, nic nie psują), w drugą - okulary bez konfiguracji na całe
 * połączenie. Dlatego przy wątpliwości uznajemy łącze za nowe.
 */
class ConnectionGate {

    private var lastReadyAtMs = 0L
    private var sawConnectAttempt = false

    /** Vendor SDK zapowiedział łączenie (`BLE_START_CONNECT`/`BLE_GATT_CONNECTED`). */
    fun onConnecting() {
        sawConnectAttempt = true
    }

    /** GATT padł - następne wykrycie usług jest na pewno nowym łączem. */
    fun onDisconnected() {
        lastReadyAtMs = 0L
        sawConnectAttempt = true
    }

    /**
     * @param nowMs czas zdarzenia
     * @return `true`, gdy to nowe połączenie i trzeba skonfigurować okulary od zera
     */
    fun onServicesDiscovered(nowMs: Long): Boolean {
        val previous = lastReadyAtMs
        val announced = sawConnectAttempt
        lastReadyAtMs = nowMs
        sawConnectAttempt = false
        if (previous <= 0L || announced) return true
        // Zegar potrafi skoczyć w tył (zmiana strefy, synchronizacja czasu) -
        // ujemny odstęp nie może uchodzić za echo.
        val elapsed = nowMs - previous
        return elapsed < 0L || elapsed > ECHO_WINDOW_MS
    }

    companion object {
        /**
         * Ile po wykryciu usług traktujemy kolejne wykrycie jako echo SDK.
         *
         * Echo producenta przychodzi po ~2,5 s; dziesięć sekund daje zapas na
         * wolniejszy telefon, a jednocześnie jest o rzędy wielkości krótsze niż
         * realna przerwa w połączeniu.
         */
        const val ECHO_WINDOW_MS = 10_000L
    }
}
