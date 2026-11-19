# Status projektu V.I.C.T.O.R. — raport z przejęcia

> Zastępuje ustalenia z `HANDOFF.md`. Kilka twierdzeń z tamtego dokumentu nie potwierdziło się
> w kodzie — szczegóły niżej.

## TL;DR

**Aplikacja się buduje.** APK debug powstaje na GitHub Actions, wszystkie testy
jednostkowe przechodzą. Wcześniej projekt nie przechodził nawet przez kompilator.

Pełny obraz tego, co działa, a czego nie — w [AUDIT.md](AUDIT.md).

### Jak zbudować

Build chodzi automatycznie przy każdym pushu na ten branch
(`.github/workflows/build.yml`). Gotowy APK jest do pobrania jako artefakt
`victor-debug-apk` z zakładki Actions.

Lokalnie na Macu:

```bash
cd jarvis-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
```

### Co się zmieniło od przejęcia projektu

Poza doprowadzeniem kodu do stanu, w którym się kompiluje, wyszło osiem rzeczy,
które **wyglądały na działające, a nie robiły nic**. Pełny opis w
[AUDIT.md](AUDIT.md); w skrócie:

| Co | Co się działo naprawdę |
|---|---|
| Komendy głosowe | 11 z 16 fraz, w tym domyślna, reagowało na „jarvis" zamiast na wybraną |
| Tryb konwersacyjny | nigdy nie usłyszał ani słowa — w projekcie nie było żadnego STT |
| Przełącznik komendy głosowej | zablokowany na sztywno, a pod spodem nie startował detektora |
| Skaner QR (`scan()`) | zawsze zwracał pustą listę |
| Tryby przechwytywania | różniły się wyłącznie nazwą — rozdzielczość była ignorowana |
| Pytania tekstowe | wymagały połączonych okularów, nawet „ile to 20 euro" |
| Kalendarz | czytany wyłącznie przez alerty pogodowe, AI o nim nie wiedziało |
| Przycisk „Pobierz klucz API" | pusty `TODO`, nie prowadził nigdzie |

### Dziewiąta rzecz, która "wyglądała na działającą"

Ta sama sesja, po dopisaniu tabeli wyżej, znalazła jeszcze jedną: **żaden wake
word ani połączenie BLE nie miały ochrony przed Doze**. Android usypiał proces
kilka minut po zgaszeniu ekranu — przełącznik w Ustawieniach pokazywał
"włączone", diagnostyka pokazywała "połączone", a telefon w kieszeni po prostu
przestawał reagować. Naprawione: usługa pierwszoplanowa
(`ble/VictorForegroundService.kt`) + ekran proszący o wyjątek optymalizacji
baterii (w onboardingu i w diagnostyce). Pełny opis, w tym co i dlaczego
zaczerpnięto z referencyjnej apki `CyanBridge`, w [AUDIT.md](AUDIT.md).

### Co można zrobić już teraz, bez okularów

Aplikacja ma **tryb symulowanych okularów**: Ustawienia → 🕶️ Diagnostyka okularów.
Udawany jest wyłącznie transport BLE — ramki notify składane są tak, jak
przysłałby je sprzęt, i idą przez ten sam kod dekodujący. Da się więc przejść
całą ścieżkę: przycisk → zdjęcie → model → odpowiedź głosem.

Krok po kroku: [URUCHOMIENIE.md](URUCHOMIENIE.md), Etap 0.

### Czego to nie oznacza

Zielony build i przechodzące testy znaczą tyle, że kod się kompiluje, pakuje,
wstaje na emulatorze i że wszystko **poza sprzętem** działa. **Sam sprzęt nie
został sprawdzony.** Bajty komend BLE, mapa ramek notify i przepływ Wi-Fi Direct
są zgodne z oficjalnym przewodnikiem SDK producenta i z działającą aplikacją
referencyjną, ale wymagają potwierdzenia na Twoim egzemplarzu — firmware bywa
różny między partiami.

Trzy rzeczy są najbardziej niepewne i wszystkie widać na ekranie diagnostycznym:
bajty komend wideo/audio, typy ramek notify oraz numer typu pliku dla nagrań.

## Weryfikacja 8 bugów z HANDOFF.md

| # | Bug wg HANDOFF | Stan faktyczny |
|---|----------------|----------------|
| 1 | `HeyCyanManager` w `AIOrchestrator.kt:75` | ✅ było naprawione |
| 2 | `HeyCyanManager` w komentarzu | ⚠️ pozostałość w `BurstCaptureManager.kt:24` — naprawione teraz |
| 3 | `photos.first().bytes` | ✅ było naprawione |
| 4 | `putJsonObject` bez nawiasu | ✅ było naprawione |
| 5 | `VictorManager.kt` używa API którego AAR nie ma | ❌ **NIE było naprawione** — przepisanie wprowadziło nowe, poważniejsze błędy |
| 6 | Calendar API `v3-rev99-1.2.0` | ✅ było naprawione |
| 7 | Brak `gradle.properties` | ✅ było naprawione |
| 8 | Brak `gradlew` | ❌ **NIE było naprawione** — `gradle/wrapper/` był pusty, `gradlew` to 18-liniowy placeholder |

### Dlaczego bug #5 nie był naprawiony

Poprzednia sesja przepisała `VictorManager.kt` z 484 do 295 linii, żeby „używać tylko tego,
co AAR faktycznie ma". Problem w tym, że:

1. Nadal wywoływała `glassesControl()` z **jednym** argumentem — a prawdziwa sygnatura to
   `glassesControl(byte[], ILargeDataResponse<GlassModelControlResponse>)`. 6 błędów kompilacji.
2. Skrócenie pliku **usunęło API, z którego korzystały 3 inne pliki** — skanowanie, parowanie
   i zdarzenia przycisku. Kolejne 8 błędów.

---

## Znalezione błędy kompilacji (13)

| Plik | Problem |
|------|---------|
| `ble/VictorManager.kt` | `glassesControl()` z 1 argumentem zamiast 2 — 6× |
| `ui/pairing/PairingViewModel.kt` | `ConnectionState.SCANNING` nie istniał |
| `ui/pairing/PairingViewModel.kt` | `ConnectionState.CONNECTED` nie istniał |
| `ui/pairing/PairingViewModel.kt` | typ `DiscoveredDevice` nie istniał |
| `ui/pairing/PairingViewModel.kt` | `startScan()`, `stopScan()`, `connect()`, `discoveredDevices` nie istniały |
| `ble/ButtonActionDetector.kt` | typ `ButtonEvent` **nie był zdefiniowany nigdzie w projekcie** |
| `AIOrchestrator.kt` | `buttonEvent`, `consumeButtonEvent()` nie istniały |
| `ui/pairing/PairingViewModel.kt` | `import pl.victor.app.HeiCyanApplication` — klasa nie istnieje |
| `ui/MainViewModel.kt` | to samo |
| `ui/settings/SettingsActivity.kt` | to samo, 2× w pełni kwalifikowane |
| `ui/settings/SettingsActivity.kt` | `VictorApplication.audio` — właściwość nie istniała |

---

## Co zostało naprawione

### 1. `VictorManager.kt` — przepisany na realne API (679 linii)

Wszystko zweryfikowane przez `javap` na `glasses_sdk_20250723_v01.aar` oraz porównane
z działającą aplikacją referencyjną
[CyanBridge](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK) — tą samą,
z której pochodzi AAR.

- **Stan połączenia** — z broadcastów `BleAction` (`BLE_GATT_CONNECTED`,
  `BLE_SERVICE_DISCOVERED`, `BLE_GATT_DISCONNECTED`). Wcześniej `initialize()` ustawiał
  na sztywno `READY` z komentarzem „TEST MODE", więc aplikacja twierdziła, że okulary są
  gotowe, nawet gdy nic nie było podłączone.
- **Skanowanie i parowanie** — `BleScannerHelper.scanDevice()` + `BleOperateManager.connectDirectly()`.
- **IP okularów** — faktycznie ustawiane z ramki notify `0x08`. Wcześniej pole `_glassesIp`
  **nigdy nie było zapisywane**, więc każde pobranie pliku kończyło się wyjątkiem.
- **Bateria** — realny odczyt z ramki `0x05`. Wcześniej: `Log.d("Battery level: 75% (placeholder)")`.
- **Przycisk na okularach** — z ramki `0x03`.
- **Singleton** — poprawione podwójne sprawdzanie z blokadą (wcześniej wyścig).

### 2. Zdjęcia idą teraz ścieżką, która działa

Pobieranie szło przez HTTP po Wi-Fi Direct — a projekt **nie ma ani jednej linii obsługi
Wi-Fi P2P** (`WifiP2pManager`, `bindProcessToNetwork` — zero trafień). Telefon nigdy nie
dołączał do sieci okularów, więc żądanie HTTP nie miało jak dojść.

Przełączyłem przechwytywanie na **miniatury po BLE** (`capturePhoto()`):
`glassesControl(0x02, 0x01, 0x06, q, q)` → `getPictureThumbnails()`. Nie wymaga Wi-Fi.
Zamiast sztywnego `delay(4000)` czekam na ramkę notify „zdjęcie gotowe" (`0x02`),
z odczekaniem jako zapasem — czyli tak szybko, jak pozwala sprzęt.

Zmienione: `BurstCaptureManager` (burst) i `AccessibilityService` (4 miejsca — tryby dla
osób niewidomych).

### 3. Gradle wrapper

Wygenerowany prawdziwy wrapper 8.7 (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`,
`gradle-wrapper.properties`).

---

## Protokół okularów — referencja

Ramki notify, `loadData[6]` = typ zdarzenia:

| Kod | Znaczenie | Dane |
|-----|-----------|------|
| `0x02` | Zdjęcie AI gotowe / przycisk foto | — |
| `0x03` | Przycisk AI / mikrofon | `[7] == 1` → wciśnięty |
| `0x04` | Postęp OTA | download / soc / nor |
| `0x05` | Bateria | `[7]` = %, `[8]` = 1 gdy ładowanie |
| `0x08` | IP okularów | `[7..10]` = IPv4 |
| `0x09` | Błąd P2P | `[7] == 0xFF` częsty, nie zawsze fatalny |
| `0x0A` | Przerwano rozpoznawanie obrazu | — |
| `0x0C` | Użytkownik uciszył asystenta (dotyk zauszników) | — |
| `0x0D` | Okulary odpięły aplikację | — |
| `0x0E` | Mało pamięci na okularach | — |
| `0x12` | Zmiana głośności na zausznikach | `[7]` = poziom |
| `0x16` | Kąt kamery | `[7]` = kąt |
| `0x17` / `0x18` | **Okulary proszą o rozmowę z AI** | `[7] == 1` → tryb tekstu na żywo |

Typy `0x0A`–`0x18` odczytane z tabeli skoków w aplikacji producenta
(`MainActivity$MyDeviceNotifyListener`, Prism Pro). Najważniejsze są dwa
ostatnie: to one przychodzą po wykryciu frazy wybudzenia przez same okulary
i bez ich obsługi `aiVoiceWake(true)` nie robi z perspektywy aplikacji nic.

Rejestracja: `LargeDataHandler.getInstance().addOutDeviceListener(100, listener)`,
gdzie listener dziedziczy po `GlassesDeviceNotifyListener`.

Komendy `glassesControl(payload, callback)`:

| Payload | Działanie |
|---------|-----------|
| `02 01 04` | Tryb transferu (Wi-Fi Direct) |
| `02 01 06 q q` | Zdjęcie AI + miniatura (`q` = jakość) |
| `02 01 0F` | Reset P2P |
| `02 04 01` / `02 05 01` | Start / stop wideo |
| `02 06 01` / `02 07 01` | Start / stop audio |

HTTP po Wi-Fi Direct (gdy już zostanie zaimplementowany):
`http://<ip>/files/media.config` (lista, jedna nazwa na linię), `http://<ip>/files/<nazwa>`.

---
## Co zostało do zrobienia

Wszystko, co dało się zrobić bez sprzętu, jest zrobione. Zostaje to, co wymaga
okularów w ręku.

### 1. Przejść checklistę uruchomienia

[URUCHOMIENIE.md](URUCHOMIENIE.md) — Etap 0 już teraz (symulator), etapy 1–5
po dostawie. Dokument mówi też, co zrobić, gdy dany etap nie zadziała.

### 2. Potwierdzić trzy rzeczy, których nie da się sprawdzić bez sprzętu

| Co | Gdzie to widać | Co zrobić, gdy się nie zgadza |
|---|---|---|
| Bajty komend wideo/audio | brak reakcji okularów na `Start wideo` | zmienić stałe `WORK_*` w `GlassesProtocol` |
| Mapa ramek notify | wpisy `Nieobsługiwany typ 0xNN` w dzienniku | dopisać typ w `decodeNotify()` |
| Numer typu pliku dla nagrań | pusta lista mimo nagrań w pamięci | przejść typy 0–7 selektorem, potem wpisać na sztywno |

Ekran diagnostyczny pokazuje surowy hex każdej ramki obok jej odczytanego
znaczenia — do naprawy wystarczy przepisać ten hex.

### 3. Rzeczy, których sprzęt nie zrobi

Podgląd na żywo z kamery i wyświetlanie czegokolwiek na okularach — ten model
nie ma wyświetlacza. Powody w sekcji „Znane granice"
w [URUCHOMIENIE.md](URUCHOMIENIE.md).

**Korekta wcześniejszego ustalenia:** strumień audio z mikrofonu okularów
figurował tu jako niemożliwy. To było błędne. Aplikacja producenta bierze go
po BLE (`initPackageNotify` → `AiChatResponse.getSubData()` → strumień Opus
dekodowany biblioteką JieLi). Nasz AAR ma obie te metody.

Dekoder jest już po naszej stronie — bez żadnej biblioteki natywnej:
`OpusDecoder` karmi systemowy `MediaCodec` surowymi pakietami, dokładając trzy
bufory konfiguracyjne (`OpusHead` z RFC 7845, opóźnienie kodeka, seek pre-roll),
których normalnie dostarcza kontener. Rozkodowane PCM idzie przez `WavWriter`
prosto do modelu jako `audio/wav` — z pominięciem rozpoznawania mowy, bo model
i tak potrafi wysłuchać pytania i od razu odpowiedzieć.

Ta droga włącza się **tylko** wtedy, gdy zwykłe rozpoznawanie nic nie usłyszało,
okulary faktycznie przysłały dający się rozkodować dźwięk, a wybrany model
przyjmuje nagrania (dziś: Gemini). Została jedna niewiadoma, której nie da się
rozstrzygnąć bez sprzętu: czy `subData` to goły pakiet Opusa, czy pakiet w ramce
producenta. Pomiar w Diagnostyce („Zmierz strumień z mikrofonu") pokazuje
rozmiary pakietów, podgląd pierwszego w hex i osobno liczy, ile z nich dekoder
przyjął — to odróżnia „okulary nie nadają" od „nadają, ale to nie jest goły
Opus".

---

## Rozmowa z AI przez okulary — jak to teraz działa

Trzy niezależne drogi zaczynają dokładnie tę samą turę rozmowy:

1. **Fraza wybudzenia wykryta przez okulary.** Nie wymaga konta Picovoice;
   przełącznik jest pierwszy w sekcji „Komenda głosowa" w Ustawieniach.
   Uwaga: aplikacja producenta zgłasza to ramką `0x17`/`0x18`, ale **na
   testowanym egzemplarzu wybudzenie przychodzi ramką `0x03`** — tą samą co
   przycisk AI (w dzienniku „wciśnięto przycisk AI"). Dlatego pojedyncze
   kliknięcie uruchamia NASŁUCH, a nie od razu zdjęcie; inaczej wybudzenie
   głosem kończyłoby się cichym zdjęciem zamiast rozmowy.
2. **Przycisk „Powiedz"** na ekranie głównym.
3. **Fraza wybudzenia wykryta przez telefon** (Picovoice, wymaga klucza).

Dalej jest wspólnie: podpinamy strumień audio z okularów → bierzemy łącze
audio → uciszamy to, co okulary odtwarzają → słuchamy → routing (warstwa
0/1/2) → odpowiedź głosem.

### Skąd bierze się PYTANIE — trzy drogi, w tej kolejności

1. **Mikrofon okularów przez klasyczny Bluetooth (SCO/HFP).** Najlepszy
   wariant, ale wymaga sparowania okularów jako zestawu słuchawkowego w
   ustawieniach Bluetooth telefonu — to osobne połączenie niż BLE i najczęstsza
   przyczyna „okulary nie mówią".
2. **Mikrofon telefonu.** Zawsze działa, choć w kieszeni słyszy niewiele.
3. **Strumień Opus po BLE.** Wchodzi, gdy pierwsze dwie nic nie usłyszały, a
   okulary przysłały dający się rozkodować dźwięk. Nagranie idzie wtedy do
   modelu jako WAV, z pominięciem rozpoznawania mowy (wymaga modelu
   przyjmującego audio — dziś Gemini).

Pułapka, o którą warto wiedzieć: **zestawienie SCO zawiesza odtwarzanie
A2DP**. Zestaw, który zgłasza profil rozmowy, ale go porządnie nie obsługuje,
jednocześnie milknie i nic nie słyszy. Aplikacja wykrywa to po trzech cichych
turach z rzędu, mówi o tym na głos i sama wraca na mikrofon telefonu;
przełącznik „Pytania mikrofonem okularów" w Ustawieniach pozwala wrócić.

### Gdy coś nie działa

Diagnostyka okularów → **„▶ Sprawdź wszystko"** przechodzi po kolei przez
wszystkie siedem ogniw (BLE, zgoda na mikrofon, rozpoznawanie mowy, profil
audio, syntezator, model, aparat) i mówi, które zawiodło — z konkretną
instrukcją, a nie samą diagnozą. Skrót do tego ekranu jest też na ekranie
błędu, obok „Spróbuj ponownie".

### Routing: trzy warstwy

| Warstwa | Co obsługuje | Kiedy |
|---|---|---|
| 0 — odruch | „zrób zdjęcie", „pauza", „włącz latarkę", „następna" | zawsze, offline, natychmiast |
| 1 — rozumienie | wszystko inne; AI odpowiada albo prosi o narzędzie znacznikiem `[[ACTION: ...]]` | domyślnie |
| 2 — awaria | pełna detekcja wzorcami (timer, budzik, telefon, SMS…); bez dopasowania — komunikat „nie mam internetu” z przykładami poleceń offline | gdy brak klucza API albo wszyscy dostawcy padli z braku sieci |

AI może samo poprosić o zdjęcie (`[[ACTION: type=take_photo]]`) — wtedy
aplikacja robi zdjęcie i zadaje to samo pytanie jeszcze raz, już z obrazem.
Dzięki temu aparat nie startuje przy każdym pytaniu.
