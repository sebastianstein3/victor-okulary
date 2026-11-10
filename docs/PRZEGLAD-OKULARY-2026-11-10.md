# Przegląd 10.11.2026 - ile z okularów naprawdę wykorzystujemy

Trzy źródła: bajtkod SDK producenta (`glasses_sdk_20250723_v01.aar`, czytany
`javap`), 34 dzienniki z prawdziwego sprzętu (18.09-07.10, 307 tur) i kod
aplikacji. Nic z poniższych zmian nie było uruchomione na telefonie ani na
okularach - testy jednostkowe, sita kompilacji (`tools/setup.sh`) i CI.

## Co znalazłem

### 1. Powitanie okularów nigdy nie wychodziło (błąd pewny, z bajtkodu)

`syncTime`, `syncDeviceInfo`, `wearFunctionSupport` i `getVolumeControl` szły
z callbackiem `null`. SDK zaczyna każde z nich od `respMap.put(akcja, callback)`,
a `ConcurrentHashMap` na `null` rzuca wyjątek - który nasz `runCatching` łapał.
Okulary nie dostawały więc czasu (nazwy plików to znaczniki czasu), a aplikacja
nie znała wersji firmware, zgłaszanych funkcji ani stanu głośności.

### 2. Liczby z dzienników (12 najnowszych, 258 tur z odpowiedzią)

| Etap | Mediana | p90 |
|---|---|---|
| Koniec nasłuchu | 5,1 s | 8,7 s |
| Pytanie gotowe (tekst) | 7,8 s | 14,4 s |
| Pierwszy fragment modelu (od wysłania) | 1,4 s | 5,9 s |
| Od początku tury do pierwszego słowa | ~15 s | ~24 s |

- **Co piąta tura (44 z 218) słuchała do limitu 9 s.** Okulary nadają dźwięk bez
  przerwy, więc "cisza w strumieniu" nie następowała nigdy; gdy telefon w
  kieszeni nic nie usłyszał, kończył dopiero limit.
- Przepisanie nagrania z okularów nie oddało tekstu ani razu (251 prób, 2,3 s
  każda); od biegu 160 jest na to bezpiecznik po trzech pustych próbach.
- Zdjęcia: 61 z 68 udanych, prawie zawsze miniatura 24-39 kB; kod kreskowy z
  miniatury nie odczytał się ani razu (34 próby), ze strumienia - tak.
- Strumień na żywo wstaje w 8-12 s; z tego 1,2 s to było czekanie na odpowiedź,
  z której SDK nic nie czyta.
- Stan "bez trybu multimediów" jest lepki: po turze przez profil rozmowy
  następna też idzie nim w 63 z 73 przypadków; po turze przez A2DP - w 7 z 210.
  Telefon widzi okulary ALBO jako odtwarzanie, ALBO jako zestaw rozmowny.
- 97 alertów pominiętych przy rozłączonych okularach i żaden nie wrócił.
- O baterii okularów w dziennikach nie było ani jednego wiersza.

### 3. Możliwości SDK, których nie używaliśmy

Głośność (`setVolumeControl`), wersje (`DeviceInfoResponse`), zgłaszane funkcje
(`GlassesTouchSupportRsp`), wykrywanie założenia (`wearCheck`), nagrywanie
filmu z głosu, stan baterii na głos.

## Co zmieniłem

**Tury**
- Jedna tura naraz także w stanie `Idle` (przygotowanie łącza audio,
  transkrypcja przed przekazaniem pytania) - `TurnClaim`, znacznik tury.
- Licznik tur trzymających mikrofon frazy; blokada uśpienia z nazwą na turę.

**Okulary**
- Powitanie z prawdziwymi callbackami: czas, wersje, zgłaszane funkcje. O
  głośność nie pytamy - okulary same przysyłają ją ramką 0x12.
- Głośność okularów z telefonu: komendy "głośniej", "ciszej", "głośność na 70
  procent", suwak w ustawieniach. Układ ładunku wzięty z bajtkodu i zgodny z
  ramką 0x12, którą okulary same przysyłają; zmieniamy tylko wartości bieżące.
  Zapis idzie TYLKO, gdy okulary zgłoszą obsługę sterowania głośnością: SDK
  parsuje odpowiedź tej komendy bez sprawdzania długości ramki, na wątku
  głównym - u okularów bez tej funkcji krótka odpowiedź wywróciłaby aplikację.
- Koniec wypowiedzi po głośności dźwięku z okularów (`EnergyEndpoint`).
  Sprawdzony na 78 nagraniach z dzienników: nie ucina żadnego pytania, w 14
  skraca nasłuch o sekundę lub więcej. Działa od drugiej tury po starcie
  aplikacji (ramkowanie bierze z poprzedniego udanego nagrania).
- Odpowiedzi na komendy sterujące rozdaje `ControlResponseRouter`: SDK ma na
  nie jeden slot i każda komenda zabierała odpowiedź poprzedniej.
- Film na głos ("nagraj film" / "zatrzymaj nagrywanie") z kontrolą, czy okulary
  go zapisały; bateria i liczba plików na głos; ostrzeżenie przy 20% i 10%.
- Mało pamięci w okularach - mówimy o tym; zaległe alerty wracają po powrocie
  okularów (do pół godziny).
- Android 12+: nasłuch czeka do 1,5 s, aż łącze rozmowy faktycznie wstanie.
- Podgląd na żywo nie czeka na bezużyteczną odpowiedź (około 1,2 s szybciej).
- Diagnostyka: karta "O okularach"; do dziennika trafiają bateria, profile
  A2DP/HFP, typ pracy z odpowiedzi na komendę, pomiar miernika głośności.

**Po niezależnym przeglądzie zmian** (drugi model czytał diff i bajtkod SDK)
- Anulowanie tury w trakcie czekania na łącze rozmowy sprząta urządzenie
  rozmowy i naprawdę przerywa turę; samo czekanie patrzy na rozgłoszenie stanu
  SCO, a nie na urządzenie "zamówione".
- Router rozróżnia komendy po kluczu (rodzaj + tryb pracy) i liczy wysyłki,
  więc odpowiedź na "ustaw jakość zdjęcia" nie trafia do czekającego na tryb
  transferu.
- Karencja miernika głośności liczy się od faktycznego startu nasłuchu, a po
  krótkim dźwięku cisza musi trwać 3,5 s.
- Zwolnienia mikrofonu frazy i blokady uśpienia w turze głosowej są
  jednorazowe i wykonują się także wtedy, gdy sprzątanie tury wyleci wyjątkiem.
- Stara tura nie wysyła już "koniec nasłuchu" okularom, gdy słucha nowa.

## Czego dziennik ma teraz dowieść (pierwszy test na sprzęcie)

| Pytanie | Wiersz |
|---|---|
| Czy powitanie dochodzi | `okulary przyjęły synchronizację czasu`, `wersje okularów`, `funkcje zgłaszane przez okulary` |
| Co znaczy wykrywanie założenia | `wykrywanie założenia - odczyt open=...` (zdjąć i założyć okulary, połączyć ponownie) |
| Czy zapis głośności działa | `ustawiam głośność okularów` i zaraz po nim `głośność okularów` z nowymi wartościami |
| Czy miernik kończy nasłuch | `nagranie z okularów ... miernik=... koniecPoGłośności=true` |
| Czy firmware odsyła typ komendy | `Hotspot okularów: odpowiedź na komendę ... typ=4` |
| Skąd lepki brak multimediów | `brak A2DP - biorę profil rozmowy ... profile=a2dp=... hfp=...` |
| Czy film z głosu się nagrywa | `film na komendę: stop filmówPrzed=N filmówPo=N+1` |

## Zostało

- **Lepki stan "tylko rozmowa" (27% tur).** Nie wiadomo, czy to telefon nie
  łączy profilu multimediów, czy okulary go nie wystawiają - rozstrzygnie nowy
  wiersz z profilami. Dopiero wtedy da się to naprawiać bez zgadywania.
- **Wykrywanie założenia okularów** - tylko odczyt do dziennika; z SDK nie
  wynika, czy pole `open` to stan, czy ustawienie.
- **Zestawianie łącza rozmowy równolegle z pytaniem modelu** - zysk do kilku
  sekund w turach bez A2DP, ale wymaga przebudowy kolejki mowy.
- **Dotyk zauszników** (`TouchControlReq`: włączenie, czułość) - parametry
  `appType` i `strength` nieustalone.
- **Ramka 0x11** przychodzi ~100 ms po komendzie podglądu (`11 09 B4`) i po
  jego zatrzymaniu (`11 03 00`) - wygląda na stan podglądu, zostaje nieopisana.
- Bez zmian z audytu 28.10: `targetSdk 34`, globalny cleartext, brak
  `assembleRelease` w CI, Gemini Live / STT / TTS z rynku.
- Nie ruszać: `02 01 09` poza galerią, `02 01 0A` (reset fabryczny),
  `RestoreKeyReq`.
