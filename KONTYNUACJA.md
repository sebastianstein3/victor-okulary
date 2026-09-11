# Przekazanie pracy — V.I.C.T.O.R.

> Dla modelu AI, który przejmuje ten projekt. Stan na commit `86c7682`
> (build CI #183, zielony w całości).
>
> Stary `HANDOFF.md` w tym repo jest **nieaktualny** — pochodzi z czasów, gdy
> projekt przenosiło się ZIP-ami. Nie czytaj go jako instrukcji.

---

## 1. Zasady, które obowiązują od pierwszej minuty

**Rozmawiaj po polsku.** Użytkownik poprosił o to wprost i to nie podlega
negocjacji. Komentarze w kodzie i komunikaty commitów też są po polsku —
trzymaj tę konwencję, inaczej kod przestanie być spójny.

**Nie zgaduj. To jest najważniejsze zdanie w tym dokumencie.** Ten projekt
stracił kilka rund na poprawkach, które naprawiały coś, co *wyglądało* na
przyczynę. Dwukrotnie wyszło to na jaw dopiero z pytania użytkownika, nie z
mojego sprawdzenia. Jeśli nie wiesz, czy Twoja poprawka w ogóle się wykonuje
na zgłoszonej ścieżce — sprawdź to, zanim ją opiszesz jako naprawioną.

**Mów prawdę o stanie.** Gdy coś jest niezweryfikowane, powiedz to. Gdy
poprzednia poprawka nie zadziałała, powiedz to wprost, bez owijania.

**Użytkownik testuje na prawdziwym sprzęcie** (okulary HeyCyan + Samsung
A23 5G). Ty nie masz do nich dostępu. Cokolwiek dotyczy zachowania sprzętu,
rozstrzyga się dziennikiem diagnostycznym (sekcja 5), a nie rozumowaniem.

---

## 2. Gdzie co jest

| Rzecz | Adres |
|---|---|
| Repozytorium | `sebastianstein3/victor-okulary` — **prywatne** |
| Gałąź robocza | `claude/jarvis-ai-glasses-project-zxsabo` |
| Kod aplikacji | `jarvis-app/` (projekt Gradle, `pl.victor.app`) |
| Gałąź na dzienniki | `victor-diagnostics` — **nie buduje się**, celowo |
| CI | `.github/workflows/build.yml` — słucha TYLKO gałęzi roboczej |
| APK | artefakt `victor-debug-apk` w zakończonym buildzie |

Aplikacja buduje się jako `pl.victor.app.debug` (`applicationIdSuffix`), CI
robi `assembleDebug`. Ma to znaczenie przy konfiguracji OAuth Google:
nazwa pakietu to `pl.victor.app.debug`, a odcisk SHA-1 klucza debugowego CI
to `39:01:DB:1F:51:31:88:26:0D:9B:A6:1A:E7:42:79:1B:3B:04:FC:F0`.

---

## 3. Co to za aplikacja

Asystent głosowy w okularach AI z rodziny HeyCyan. Telefon jest mózgiem,
okulary są mikrofonem, głośnikiem i aparatem. Pełna lista funkcji jest
w aplikacji (ekran „Komendy") i w `CommandCatalog.kt` — nie powtarzam jej tu.

Najkrótszy opis pełnej tury:

```
wybudzenie (fraza w okularach albo przycisk)
  → nasłuch: strumień z mikrofonu okularów po BLE + mikrofon telefonu równolegle
  → transkrypcja: pięć dróg w kolejności jakości (chmura → systemowa → Vosk
    → nasłuch telefonu → samo nagranie do modelu)
  → decyzja o zdjęciu (wzorce „co widzisz" albo prośba modelu)
  → zebranie kontekstu (kalendarz, poczta, pogoda, notatki, lokalizacja, pamięć)
  → model (strumieniowo)
  → mowa zdanie po zdaniu, W TRAKCIE generowania
  → ewentualna akcja ze znacznika [[ACTION: ...]]
```

Sercem jest `AIOrchestrator.kt` (~3700 linii). To jest plik, w którym
spędzisz najwięcej czasu.

---

## 4. Otwarte problemy — lista od użytkownika

To jest **aktualne zgłoszenie**, z którym zostajesz. Stan na moment przekazania:

### 4.1 Zdjęcia nie są przechwytywane — NIEROZSTRZYGNIĘTE

> „Aplikacja dalej nie przechwytuje zdjęć." / „Chyba nie dostają zdjęć, gdy
> pytam «co widzisz»."

Wraca po raz kolejny. Droga w `VictorManager.captureAiPhotoInternal()` ma
**trzy warianty awaryjne** i była już kilka razy poprawiana na podstawie
domysłów o zachowaniu firmware'u.

**Nie poprawiaj tego, zanim nie zobaczysz dziennika.** Dołożyłem zapis
każdej próby osobno: czy okulary zgłosiły gotowe zdjęcie, ile bajtów
przyszło, czy to w ogóle JPEG. To rozstrzyga między trzema różnymi
przyczynami, które wymagają trzech różnych napraw:

- okulary nie przyjmują komendy migawki → problem z kanałem komend
- przyjmują, ale miniatura nie dochodzi → problem z transferem
- dochodzą bajty, które nie są JPEG-iem → transfer się urywa

Warto też sprawdzić drugi warunek: `wantsToLook` w `AIOrchestrator` wymaga
`audioQuestion == null`. Gdy pytanie poszło do modelu jako NAGRANIE (bo
żadna transkrypcja nie dała tekstu), wzorce „co widzisz" nie mają czego
dopasować i aparat nie wystartuje. Dziennik pokaże, czy tak było.

### 4.2 Odpowiedzi nie na temat, zawieszanie po pierwszym pytaniu — POPRAWIONE, DO POTWIERDZENIA

> „Okulary odpowiadają jakby na inne pytania niż zadane."
> „Zwykle jedno pytanie jest okej, ale później coś się zawiesza."

Znalazłem przyczynę i jest konkretna: **odpowiedź szła na głos dwa razy.**
Raz zdanie po zdaniu w trakcie generowania, a potem jeszcze raz w całości
przez `speakAndAwait(response.text)` — bo tylko tak dało się poczekać na
koniec mówienia. Do tego każde `speak()` używało `QUEUE_FLUSH`, czyli
**wypierało** to, co właśnie leciało. Zdanie drugie ucinało pierwsze w pół
słowa, trzecie drugie, a pełna odpowiedź ucinała resztę i zaczynała od nowa.

Użytkownik słyszał poszarpane początki zdań, które do siebie nie pasują — i
tura nie kończyła się, dopóki nie dobrzmiało drugie czytanie.

Naprawione w `af89056`: zdania kolejkują się (`QUEUE_ADD`), a na końcu
`awaitStreamSpoken()` czeka na to, co już zostało wypowiedziane.
**Nie zweryfikowane na sprzęcie.**

### 4.3 Okulary rozłączają się — CZĘŚCIOWO

Auto-reconnect ma teraz nieskończone próby z rosnącymi odstępami
(`ReconnectBackoff`), wstrzymane przy wyłączonym Bluetoothie. Ale samo
rozłączanie może mieć przyczynę po stronie firmware'u albo zasilania —
dziennik zapisuje każde rozłączenie z informacją, czy było świadome.

### 4.4 Bluetooth „tylko połączenia" zamiast multimediów — DWIE PRZYCZYNY USUNIĘTE

Profil rozmowy (SCO) trzyma urządzenie w trybie rozmowy tak długo, jak ktoś
go trzyma. Usunąłem dwa miejsca, które brały go niepotrzebnie:

1. `SpeechToText.listen()` brał SCO przy KAŻDYM nasłuchu, także gdy okulary
   nadawały dźwięk własną drogą po BLE (commit `d219557`)
2. Tura brała SCO na całą długość, także na część, która tylko mówi —
   a do mówienia wystarczy A2DP (commit `17b9555`)

Teraz SCO idzie tylko wtedy, gdy okulary nie są widoczne jako urządzenie
multimedialne. **Jeśli użytkownik zgłosi, że przestał słyszeć odpowiedzi** —
to jest pierwsze miejsce do sprawdzenia: `AudioManager.canSpeakOverMedia()`.

### 4.5 Długi czas od pytania do odpowiedzi — CZĘŚCIOWO, DIAGNOZA W TOKU

Użytkownik pytał wprost, czy to przez „przeszukiwanie informacji o
użytkowniku", czy przez warstwy transkrypcji, czy przez czekanie na pełną
odpowiedź modelu. **Dziennik odpowiada na to pomiarem**, nie domysłem —
rozbija czas na etapy i pokazuje osobno, które źródła kontekstu doszły.

Czytanie w trakcie generowania (o które prosił) **już było napisane**, tylko
niszczone przez podwójną wypowiedź z 4.2. Powinno zacząć działać dopiero
teraz.

---

## 5. Dziennik diagnostyczny — Twoje główne narzędzie

To jest najważniejsza rzecz, jaką dostajesz. Powstał dokładnie dlatego, że
zgadywanie przestało wystarczać.

**Gdzie:** `pl.victor.app.diagnostics.DiagnosticLog` / `DiagFormat` /
`DiagnosticUploader`.

**Jak działa:** aplikacja zapisuje każdy etap tury z czasem **od jej
początku** i po każdej turze (nie częściej niż raz na minutę) wypycha plik
przez API GitHuba do gałęzi `victor-diagnostics`, katalog `diagnostics/`.

**Jak czytać:**

```
HH:MM:SS.mmm   +1234 ms  ETAP         treść   pole=wartość
```

Druga kolumna to czas od początku tury — przy „długo trwa" to jedyna liczba,
która ma znaczenie. Turę otwiera `--- TURA xxxx ---`, zamyka
`--- KONIEC TURY xxxx ---`.

Etapy: `SESJA`, `WAKE`, `PRZYCISK`, `NASŁUCH`, `TRANSKRYPCJA`, `ZDJĘCIE`,
`KONTEKST`, `MODEL`, `MOWA`, `AKCJA`, `BLE`, `AUDIO`, `BŁĄD`.

**Czego szukać najpierw:**

| Objaw | Wiersz, który rozstrzyga |
|---|---|
| „długo trwa" | `MODEL  PIERWSZY FRAGMENT odpowiedzi  ms=…` |
| „nie słyszy, co mówię" | `TRANSKRYPCJA … droga=… wynik=…` |
| „nie robi zdjęć" | `ZDJĘCIE  próba 1/1b/2 …  bajtów=… jpeg=…` |
| „to przez kontekst?" | `KONTEKST zebrany  ms=… kalendarz=… pamięć=…` |
| „rozłącza się" | `BLE  ROZŁĄCZONO  świadome=…` |
| tryb rozmowy vs media | `AUDIO  mowa przez A2DP / biorę profil rozmowy` |

**Bezpieczeństwo:** każdy wiersz przechodzi przez `DiagFormat.redact()` —
16 testów pilnuje, że klucze OpenAI, Google, Anthropic, tokeny GitHuba i
długie ciągi literowo-cyfrowe są zaciemniane. **Nie obchodź tego.** Plik
trafia do repozytorium; wyciek klucza jest natychmiastowy i nieodwracalny
zwykłym commitem.

**Co użytkownik musi zrobić raz:** wygenerować token na
github.com/settings/personal-access-tokens/new — dostęp tylko do
`victor-okulary`, uprawnienie **Contents: Read and write** — i wkleić go w
**Ustawienia → 🩺 Dziennik diagnostyczny**.

**Token jest dla TELEFONU, nie dla Ciebie. Nigdy o niego nie proś i nigdy nie
przyjmuj go w rozmowie.** Telefon potrzebuje go, żeby PISAĆ dzienniki; Ty
potrzebujesz tylko je CZYTAĆ, a do tego wystarczy zwykły `git fetch`. Jeśli
użytkownik wklei token w czacie, powiedz mu wprost, żeby go natychmiast
unieważnił — zapis rozmowy jest kolejnym miejscem, w którym sekret przestaje
być sekretem. Pisz „wklej token w aplikacji", nigdy „potrzebuję tokenu":
zdarzyło się, że to drugie sformułowanie sprowokowało wklejenie go tutaj.

---

## 6. Jak weryfikować kod przed wypchnięciem

**Gradle NIE zadziała w Twoim środowisku.** `maven.google.com` i
`dl.google.com` są zablokowane przez proxy, więc AGP i AndroidX się nie
pobiorą. Jedynym prawdziwym buildem jest CI — trwa ~15 minut i kolejkuje się
po jednym.

Dlatego jest zestaw sit w `tools/`. **Uruchom `tools/setup.sh`** — pobierze
kompilator i biblioteki z `repo1.maven.org` (to jedyne działające lustro).

| Skrypt | Co sprawdza | Kiedy |
|---|---|---|
| `groupcheck.sh` | kompiluje wszystkie źródła **non-UI** naraz | po każdej zmianie logiki |
| `uicheck.sh` | nowe nierozwiązane symbole w plikach UI | po każdej zmianie w `ui/` |
| `annocheck.sh` | zdublowane adnotacje przed deklaracją | zawsze |
| `syntax.sh` | składnia pojedynczego pliku | doraźnie |
| `runtests.sh` | testy jednostkowe bez Gradle'a | po zmianie testowanego kodu |

### Protokół, którego się trzymaj

1. `groupcheck.sh` → porównaj **RODZAJE** błędów z `tools/baseline-groupcheck.txt`:
   ```bash
   norm() { sed -E 's/^[^:]*\/([^/]+\.kt):[0-9]+:[0-9]+: //' "$1" | sort -u; }
   comm -13 <(norm tools/baseline-groupcheck.txt) <(norm nowy.txt)
   ```
   **Nie porównuj LICZBY błędów.** Liczba potrafi się nie zmienić, gdy
   zmienia się ich znaczenie — tak przeszedł czerwony build #164.
2. `uicheck.sh` dla każdego zmienionego pliku UI.
3. `annocheck.sh` na całości.
4. `runtests.sh` dla testów dotkniętego kodu.
5. Dopiero wtedy commit i push.

**Czysto lokalnie ≠ zielony build.** Lokalny kompilator to Kotlin 1.9.24, a
projekt buduje się 2.3.20. To sito, nie dowód.

### Baza porównania dla `uicheck.sh`

Skrypt czyta ostatni zielony commit z pliku `last_green_ref` obok siebie
(albo ze zmiennej `BASE_REF`). **Aktualizuj go po każdym zielonym buildzie.**
Na dziś: `86c76822e336ecf08647dca753a39fff34233dbf`.

Przy pierwszym podejściu porównywałem z `HEAD` — a `HEAD` zawierał już
wpadkę, którą miałem złapać, więc różnica wyszła pusta i test przepuścił
zepsuty kod. Zauważyłem to dopiero, gdy celowo wstawiłem błąd z powrotem.
**Zawsze sprawdź, że nowy test faktycznie łapie błąd, dla którego powstał.**

---

## 7. Pułapki, które już kosztowały czerwony build albo zmarnowaną poprawkę

Każda z nich wydarzyła się naprawdę.

**Wstawianie kodu między adnotację a deklarację.** Build #164 padł na kapcie
z `Composable is not a repeatable annotation type`, bo użyłem za krótkiej
kotwicy (samej linii `private fun X() {`), a nad nią stały KDoc, `@OptIn` i
`@Composable`. Nowa funkcja wylądowała w środku. Stąd `annocheck.sh`.
**Kotwicz się na początku bloku KDoc, nie na linii deklaracji.**

**Symbol bez importu w pliku UI.** Build #181. `SettingsActivity.kt`
odwołuje się do `SettingsRepository` **pełną nazwą pakietu** wszędzie —
importu nie ma. `syntax.sh` odfiltrowuje „unresolved reference", więc tego
nie złapał. Stąd `uicheck.sh`.

**`${'$'}` w łańcuchu Kotlina wypisuje literalny znak dolara.** Trzy miejsca
w interfejsie pokazywały `$` zamiast wartości. Użytkownik zgłosił to jako
„jakieś dziwne znaki".

**Poprawka w gałęzi, która się nie wykonuje.** Transkrypcja w chmurze
siedziała WEWNĄTRZ gałęzi ciszy — odpalała się tylko wtedy, gdy telefon nie
usłyszał NIC. A zgłoszony błąd był inny: telefon słyszał ŹLE. Wynik był
niepusty, gałąź się nie wykonywała, poprawka nie działała ani razu.
Sprawdzałem, czy kod się kompiluje, a nie czy się wykonuje.

**`QUEUE_FLUSH` w syntezatorze mowy wypiera trwającą wypowiedź.** Patrz 4.2.
Jeśli dokładasz cokolwiek, co mówi w trakcie tury, użyj `speakQueued`.

**`LinkedHashMap` z `accessOrder` nie jest bezpieczna wątkowo** — przestawia
listę także przy odczycie. `AIResponseCache` dostał zamek po tym, jak
zauważyłem, że czytamy i piszemy z korutyn na puli IO.

---

## 8. Mapa kodu — gdzie czego szukać

```
jarvis-app/app/src/main/java/pl/victor/app/
├── AIOrchestrator.kt         ★ cała tura: nasłuch → model → mowa → akcja
├── VictorApplication.kt        singletony, start dziennika
├── ble/
│   ├── VictorManager.kt      ★ warstwa nad vendor SDK; BLE, zdjęcia, reconnect
│   ├── GlassesProtocol.kt      surowe bajty; czyste funkcje, testowalne
│   ├── GlassesWifiTransfer.kt  Wi-Fi Direct (galeria, pełne zdjęcia)
│   ├── WifiDirectDiagnosis.kt  powody niepowodzeń, po ludzku (12 testów)
│   ├── ReconnectBackoff.kt     odstępy ponawiania (7 testów)
│   └── GlassesSimulator.kt     tryb bez sprzętu — UŻYWAJ GO
├── diagnostics/              ★ DiagnosticLog, DiagFormat (16 testów), Uploader
├── audio/
│   ├── AudioManager.kt       ★ TTS, strumień mowy, kolejkowanie
│   └── BluetoothAudioRouter.kt SCO vs A2DP — źródło sprawy 4.4
├── conversation/
│   ├── SpeechToText.kt         rozpoznawanie systemowe + z nagrania
│   ├── ConversationalMode.kt   pauzowanie wake worda na czas nasłuchu
│   └── ConversationContext.kt  pamięć rozmowy, wygasa po kwadransie
├── actions/                    detekcja i wykonanie akcji; CommandCatalog
├── ai/                         dostawcy modeli + ProviderFailure
├── accessibility/              tryby dla osób niewidomych
├── calendar/, google/          Kalendarz i Gmail przez jedno konto
├── proactive/                  alerty, briefing, konteksty do promptu
└── ui/                         Compose — NIE objęte groupcheck.sh
```

**Tryb symulacji okularów** (`Ustawienia → Symulowane okulary`, klasa
`GlassesSimulator`) pozwala przejść całą ścieżkę bez sprzętu. Używaj go,
zanim poprosisz użytkownika o test.

---

## 9. Zablokowane na sprzęcie — nie da się ruszyć bez okularów

1. **Protokół panelu dotykowego** — nieznany, nie ma czym podsłuchać
2. **Ramkowanie pakietów `subData`** strumienia mikrofonu — do potwierdzenia
3. **Transkrypcja mowy bezpośrednio z okularów** — do potwierdzenia
4. **Numer drugiego przycisku i bajt trybu zdjęcia** — kod przepuszcza już
   każdy numer i zapisuje go w dzienniku, ale znaczenia nie znamy

Do tego **własna fraza wybudzenia przez Picovoice Console** — wymaga konta
i płatnego planu, dlatego równolegle jest Vosk (offline, bez konta).

---

## 10. Ograniczenia środowiska, o których trzeba wiedzieć

- **Proxy przepuszcza tylko `repo1.maven.org`** i to wyłącznie do pobierania
  znanych współrzędnych. Zablokowane: `maven.google.com`, `dl.google.com`,
  `search.maven.org`, `alphacephei.com`, `huggingface.co`.
  **Nie dodawaj zależności ani adresów modeli, których nie umiesz sprawdzić.**
- **Nie masz `gh` CLI** — do GitHuba idź przez narzędzia MCP (`mcp__github__*`).
- **CI kolejkuje się po jednym**, ~15 min z testem na emulatorze. Nie wypychaj
  serii commitów pod rząd, jeśli chcesz szybko znać wynik.
- Do dzienników wysyłanych przez aplikację **nie potrzebujesz nic robić** —
  czytasz je z gałęzi `victor-diagnostics`.

---

## 11. Pierwsze trzy rzeczy, które zrobiłbym na Twoim miejscu

1. **Sprawdź, czy są dzienniki** w `victor-diagnostics/diagnostics/`. Jeśli
   tak — zacznij od nich, a nie od kodu. Jeśli nie — przypomnij
   użytkownikowi o tokenie (sekcja 5).
2. **Rozstrzygnij sprawę zdjęć (4.1) z dziennika.** To jedyny punkt z
   listy, którego w ogóle nie tknąłem merytorycznie — bo bez danych każda
   zmiana byłaby czwartym domysłem z rzędu.
3. **Poproś o potwierdzenie 4.2 i 4.4.** Obie poprawki są zweryfikowane
   kompilacją i CI, ale ŻADNA nie była na sprzęcie. Jeśli 4.2 nie zniknęło,
   przyczyna jest gdzie indziej i trzeba zacząć od nowa — nie doklejaj
   kolejnej warstwy do tej samej hipotezy.

---

## 12. Stan weryfikacji na moment przekazania

- Build CI **#183 zielony w całości** — kompilacja, testy jednostkowe, test
  dymny na emulatorze
- **39 plików testowych**, wszystkie przechodzą
- Kompilacja grupowa: **bez nowych rodzajów błędów** względem bazy
- **Zero** z ostatnich poprawek nie zostało potwierdzone na prawdziwym
  sprzęcie — użytkownik nie zdążył przetestować przed przeniesieniem pracy
