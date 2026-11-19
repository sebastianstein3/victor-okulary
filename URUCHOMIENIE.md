# Uruchomienie z okularami — checklista

Dokument na moment, w którym okulary przychodzą. Kroki są ułożone tak, żeby
każdy kolejny opierał się na potwierdzonym poprzednim — jak coś padnie, wiadomo
dokładnie gdzie, zamiast zgadywać.

Zanim okulary dotrą, przejdź **Etap 0** — cała ścieżka aplikacji da się sprawdzić
na symulatorze, bez sprzętu.

---

## Etap 0 — zanim okulary przyjdą (symulator)

Cel: mieć pewność, że wszystko poza sprzętem działa. Jak tu coś nie gra, to nie
jest wina okularów.

1. **Zainstaluj APK.** Pobierz artefakt `victor-debug-apk` z zakładki Actions
   (najnowszy zielony build), rozpakuj, `adb install -r victor-debug.apk`.
2. **Przejdź onboarding**, wpisz klucz API providera AI (Gemini albo inny).
3. **Ustawienia → 🕶️ Diagnostyka okularów → włącz „Tryb symulacji”.**
4. **Kliknij „Połącz”.** Stan ma przejść: `łączenie` → `połączone` → `gotowe`,
   a w dzienniku ramek ma pojawić się bateria.
5. **Przejdź przyciski testów po kolei:**
   - `Bateria` → w dzienniku ramka `05`
   - `Liczba plików` → wynik pod przyciskami
   - `Zdjęcie` → „Zdjęcie OK: N B, nagłówek FF D8”
   - `Start wideo` / `Stop wideo`, `Start audio` / `Stop audio`
   - `Tryb transferu` → w dzienniku najpierw błąd P2P 255, potem `IP okularów: 192.168.49.1`
   - `Lista plików`, `Pobierz zdjęcie`
6. **Wstrzyknij „Przycisk AI”** i sprawdź, czy V.I.C.T.O.R. odpowiada głosem —
   to przejście całej ścieżki: przycisk → zdjęcie → model → TTS.
7. **Wyłącz tryb symulacji** i — z rozłączonymi okularami — wpisz w aplikacji
   pytanie, które nie dotyczy otoczenia, np. „ile to 20 euro w złotych”.
   Ma odpowiedzieć bez robienia zdjęcia. To sprawdza klucz API, model i TTS
   niezależnie od całej warstwy BLE, więc jak tu coś nie gra, nie ma sensu
   szukać winy w okularach.

Jeśli krok 6 działa, jedyne co zostaje niesprawdzone to sam sprzęt.

---

## Etap 1 — parowanie

**Zanim zaczniesz:** naładuj okulary, włącz Bluetooth i lokalizację w telefonie,
**wyłącz tryb symulacji** w diagnostyce.

1. Jeśli okulary były wcześniej sparowane z oficjalną aplikacją HeyCyan —
   odepnij je tam i usuń z listy Bluetooth w systemie. Vendor SDK łączy się
   bezpośrednio i cudze powiązanie potrafi to blokować.
2. Ustawienia → **Połącz z okularami** → przyznaj uprawnienia.
3. Okulary mają pojawić się na liście skanu. Nazwa zwykle zawiera `cyan`
   albo `glass`; jak nie ma nazwy, rozpoznasz je po sile sygnału (najmocniejszy
   przy okularach trzymanych obok telefonu).
4. Kliknij urządzenie. Stan ma dojść do **`gotowe`**.

**Gdy skan nic nie znajduje:**

```bash
adb logcat -c && adb logcat -s VictorManager:V BluetoothAdapter:W
```

- Brak wpisu `Start skanowania BLE` → uprawnienia nieprzyznane.
- Skan startuje, ale zero wyników → okulary śpią (przytrzymaj przycisk),
  albo trzyma je inne połączenie.

**Gdy stan zatrzymuje się na `połączone` i nie idzie do `gotowe`:** to wykrywanie
usług GATT. Rozłącz, wyłącz i włącz Bluetooth, spróbuj raz jeszcze.

---

## Etap 2 — pierwsze ramki

Wejdź w **Diagnostykę okularów** (symulacja wyłączona) i zostaw ekran otwarty.

Sprawdź w tej kolejności:

| # | Przycisk | Czego oczekiwać w dzienniku |
|---|----------|------------------------------|
| 1 | `Bateria` | ramka typu `05`, opis `Bateria N%` |
| 2 | (wciśnij przycisk **na okularach**) | `Wciśnięto przycisk AI` albo `Zdjęcie gotowe` |
| 3 | `Liczba plików` | wynik: liczba zdjęć / wideo / nagrań |
| 4 | `Zdjęcie` | `Zdjęcie gotowe`, potem wynik `Zdjęcie OK: N B, nagłówek FF D8` |

**To jest najważniejszy moment całego uruchomienia.** Jeśli w dzienniku pojawiają
się ramki, protokół jest zgodny i reszta to już tylko strojenie. Jeśli dziennik
zostaje pusty mimo połączenia w stanie `gotowe` — nasłuch notify nie działa
i trzeba sprawdzić, czy `LargeDataHandler.addOutDeviceListener(100, ...)` jest
tym, czego oczekuje firmware w tych egzemplarzach.

**Jeśli pojawią się ramki z opisem `Nieobsługiwany typ 0xNN`** — to nowość
w firmware. Przepisz hex z dziennika; mapowanie typów siedzi w
`GlassesProtocol.decodeNotify()` i dopisanie nowego typu to kilka linii.

---

## Etap 3 — zdjęcia i AI

1. **Zdjęcie przez BLE** (`Zdjęcie` w diagnostyce). Nagłówek `FF D8` oznacza
   poprawny JPEG. Rozmiar poniżej 2 kB to podejrzanie mało — prawdopodobnie
   ucięty transfer.
2. **Cała ścieżka:** wciśnij przycisk na okularach i zadaj pytanie o to, co widzisz.
   V.I.C.T.O.R. ma odpowiedzieć głosem, opisując realną scenę.
3. Jeśli odpowiedź jest ogólnikowa i nie pasuje do tego, co masz przed sobą —
   model dostał zły obraz. Sprawdź w historii, jakie zdjęcie poszło do modelu.

**Jakość miniatury** reguluje parametr `quality` w `capturePhoto()`
(zakres 0–6, domyślnie 2). Wyżej = ostrzejszy obraz, ale dłuższy transfer po BLE.

---

## Etap 4 — Wi-Fi Direct (pełna rozdzielczość i wideo)

Ten etap jest osobno, bo jest najbardziej zawodny — zależy od telefonu.

1. `Tryb transferu` w diagnostyce.
2. Spodziewaj się ramki `Błąd P2P, kod 255` — **to normalne**, okulary zgłaszają
   ją rutynowo, zanim podniosą grupę.
3. Po kilku sekundach ma przyjść `IP okularów: 192.168.x.x`.
4. `Lista plików` → `Pobierz zdjęcie`.

**Gdy IP nie przychodzi:**
- Android 13+: potrzebne uprawnienie `NEARBY_WIFI_DEVICES` (ekran parowania o nie prosi).
- Wcześniejsze wersje: `ACCESS_FINE_LOCATION` **i włączona lokalizacja w systemie**.
- Telefon musi mieć włączone Wi-Fi (samo Wi-Fi Direct nie wystarczy).
- Niektóre nakładki producenta ubijają grupę P2P przy aktywnym Wi-Fi — spróbuj
  z rozłączoną siecią domową.
- Utknęło: `Reset P2P`, potem `Tryb transferu` jeszcze raz.

**Po pobraniu plików sesja transferu zamyka się sama.** Gdyby telefon stracił
internet po transferze, to znaczy, że `endTransferSession()` nie doszło —
`Reset P2P` przywraca routing.

---

## Etap 5 — nagrania głosowe przez BLE

Osobna droga, niezależna od Wi-Fi Direct. Warto ją sprawdzić **właśnie wtedy,
gdy Etap 4 nie działa** — nagrania da się wtedy i tak pobrać.

1. Nagraj coś: `Start audio` → mów → `Stop audio`.
2. `Lista nagrań` w sekcji „Nagrania głosowe przez BLE”.
3. Jeśli lista wraca pusta — zmień **typ pliku** przyciskami `−` / `+`
   i spróbuj ponownie. Przejdź 0…7.
4. Gdy lista się pojawi, `Pobierz nagranie`.

**Zapisz numer typu pliku, który zadziałał.** Producent go nie udokumentował,
w SDK startuje z zerem, a znając właściwą wartość można ją ustawić na sztywno
i zdjąć selektor z ekranu.

Jeśli żaden typ nie zwraca listy, kanał `RecordHandle` prawdopodobnie nie jest
w tym firmware obsługiwany — wtedy jedyną drogą do nagrań zostaje Wi-Fi Direct
z Etapu 4.

---

## Komenda głosowa i tryb konwersacyjny

Nie zależą od okularów — da się je sprawdzić od razu.

1. **Wybierz frazę.** Ustawienia → sekcja frazy wybudzenia:
   - **„Hej Wiktor”** — telefon słucha frazy sam (openWakeWord, offline, bez
     kont, kluczy i opłat). Zużywa trochę baterii.
   - **„Hej Lens”** — słuchają tylko okulary (fraza producenta), telefon nie
     nasłuchuje. Najoszczędniej, ale skuteczność zależy od egzemplarza.
2. Przy „Hej Wiktor” sprawdź, czy pod opcją widać „model gotowy”. Komunikat
   powie, jeśli czegoś brakuje (zgoda na mikrofon, plik modelu).
3. Powiedz frazę. Usłyszysz **krótki sygnał** — dopiero on znaczy „mów
   teraz”. Wcześniejszy dźwięk wybudzenia gra firmware okularów i wypada
   nawet o kilka sekund przed startem nagrywania (okno rozpoznawania kliknięć
   plus zestawienie łącza SCO). Mówienie przed sygnałem to najczęstsza
   przyczyna „wybudziłem, powiedziałem, a on nic”.

Picovoice (Porcupine) i Vosk zostały usunięte: darmowe klucze Picovoice
przestały działać 30.06.2026, a Vosk był ciężki dla baterii i mylił słowa.

**Skąd bierze się pytanie.** Aplikacja próbuje po kolei trzech dróg:
mikrofonu okularów przez profil rozmowy (SCO/HFP), mikrofonu telefonu, a na
końcu strumienia Opus po BLE — ten ostatni wchodzi tylko wtedy, gdy dwa
pierwsze nic nie usłyszały, okulary przysłały dający się rozkodować dźwięk, a
wybrany model przyjmuje nagrania (dziś Gemini). Przełącznik „Pytania
mikrofonem okularów" w Ustawieniach wyłącza pierwszą z nich; aplikacja robi to
też sama po trzech cichych turach z rzędu, bo zestawienie SCO zawiesza
odtwarzanie A2DP i zestaw potrafi wtedy jednocześnie zamilknąć i przestać
słyszeć.

**Tryb konwersacyjny** korzysta z systemowego rozpoznawania mowy. Na czas
słuchania wykrywanie komendy jest wstrzymywane — mikrofon obsługuje tylko
jednego odbiorcę naraz. Jeśli telefon nie ma pakietu rozpoznawania mowy
(zdarza się na czystym AOSP), tryb nic nie usłyszy; w logu będzie
„Rozpoznawanie mowy niedostępne".

---

## Logi

```bash
# Wszystko istotne
adb logcat -s VictorManager:V GlassesWifiTransfer:V AIOrchestrator:V

# Sam protokół okularów
adb logcat -s VictorManager:V | grep -i notify

# Wywrotki aplikacji
adb logcat -b crash
```

Nieobsłużone wyjątki lądują też w plikach na urządzeniu
(`CrashReporter`, katalog `files/crashes/`, 10 najnowszych):

```bash
adb exec-out run-as pl.victor.app ls files/crashes
adb exec-out run-as pl.victor.app cat files/crashes/<nazwa>
```

---

## Co zgłosić, jeśli coś nie działa

Żeby dało się to naprawić bez zgadywania, potrzebne są trzy rzeczy:

0. **Raport z „Sprawdź wszystko"** (Diagnostyka okularów, pierwsza karta).
   Przechodzi po kolei przez wszystkie siedem ogniw ścieżki i mówi, które
   nie działa — to zwykle wystarcza zamiast punktów niżej.
1. **Na którym etapie** z tej listy się zatrzymało.
2. **Zrzut dziennika ramek** z ekranu diagnostycznego (hex + opisy).
3. **Log:** `adb logcat -s VictorManager:V > log.txt` z okresu próby.

Sam hex ramek wystarcza, żeby dopisać obsługę nieznanego typu albo poprawić
bajty komendy.

---

## Znane granice

Rzeczy, które **nie zadziałają** niezależnie od sprzętu — nie trać na nie czasu:

- ~~**Nagrywanie audio z mikrofonu okularów w czasie rzeczywistym.**~~
  **To ustalenie było błędne.** Kanał `RecordHandle` faktycznie oddaje nagranie
  dopiero jako plik, ale istnieje DRUGA droga, której wcześniej nie znaliśmy:
  aplikacja producenta bierze dźwięk z mikrofonu po BLE przez
  `initPackageNotify` — pakiety `AiChatResponse`, których `getSubData()` to
  strumień **Opus** (dekodowany biblioteką JieLi
  `com.jieli.jl_audio_decode.opus.OpusManager`) i podawany prosto do
  rozpoznawania mowy.

  Nasz AAR ma `initPackageNotify` i `removeGptNotify`, a dekoder jest już po
  naszej stronie: `OpusDecoder` karmi systemowy `MediaCodec` surowymi pakietami
  (bez żadnej biblioteki natywnej), a `WavWriter` pakuje wynik w WAV, który
  idzie prosto do modelu. Aplikacja sięga po tę drogę sama — gdy zwykłe
  rozpoznawanie mowy nic nie usłyszy, a okulary przysłały dający się rozkodować
  dźwięk i wybrany model przyjmuje nagrania (dziś: Gemini).

  Została jedna niewiadoma: czy `subData` to goły pakiet Opusa, czy pakiet w
  ramce producenta. Rozstrzyga to przycisk **„Zmierz strumień z mikrofonu
  (15 s)"** w Diagnostyce — pokazuje rozmiary pakietów, podgląd pierwszego w
  hex i osobno liczy, ile z nich dekoder przyjął:
  - zero pakietów → okulary nie nadają tym kanałem, ta droga odpada;
  - pakiety są, zero rozkodowanych → to nie jest goły Opus, przyślij podgląd hex;
  - pakiety rozkodowane → droga działa, aplikacja użyje jej automatycznie.

  Niezależnie od tego działa ścieżka przez **klasyczny Bluetooth** (SCO/HFP),
  która nie wymaga żadnego dekodowania — pod warunkiem sparowania okularów
  jako zestawu słuchawkowego w ustawieniach Bluetooth telefonu. Uwaga: jej
  zestawienie **zawiesza odtwarzanie A2DP**, więc zestaw, który zgłasza profil
  rozmowy, ale go nie obsługuje, milknie i jednocześnie nic nie słyszy.
  Aplikacja wykrywa to po trzech cichych turach z rzędu i sama wraca na
  mikrofon telefonu (przełącznik w Ustawieniach pozwala wrócić).
- **Podgląd na żywo z kamery.** Okulary nie udostępniają strumienia wideo —
  tylko pojedyncze zdjęcia i nagrane pliki.
- **Wyświetlanie czegokolwiek na okularach.** Ten model nie ma wyświetlacza;
  całe wyjście idzie głosem.
- **OTA firmware.** Komenda jest zaimplementowana i symulator odgrywa postęp,
  ale nie ma pliku firmware ani sposobu jego weryfikacji — nie używaj tego
  na jedynym egzemplarzu sprzętu.
