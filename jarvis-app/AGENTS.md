# AGENTS.md - V.I.C.T.O.R. AI Glasses Assistant

> Kompletna dokumentacja projektu dla AI agentów / developerów, którzy przejmą pracę nad projektem.

---

## 1. Project Overview

**V.I.C.T.O.R.** - Android app dla HeyCyan smart glasses (okulary z kamerą, mikrofonem, głośnikiem - 161 zł).

**Główne założenie:** HeyCyan to tanie okulary bez AI, bez ekranu, tylko kamera + audio. Nasza apka dodaje im pełne AI multimodalne (4 providery), audio-first asystenta, który działa cały dzień na baterii.

**Kluczowe różnice vs konkurencja:**
- Audio-first (nie AR/VR) - dyskretny, lekki, do noszenia cały dzień
- Polski native (nie angielski)
- Open source (pełna kontrola)
- Self-hosted (klucze szyfrowane lokalnie, nic nie wycieka)
- Dla niewidomych (accessibility mode) - realnie zastępuje OrCam za 12 000 zł

**Status:** 102 pliki, ~18 036 linii kodu. Gotowe do build + test z prawdziwymi okularami.

---

## 2. Tech Stack

### Język i framework
- **Kotlin 1.9+** (pełny Kotlin, nie Java compatibility)
- **Jetpack Compose** (UI - Material 3, żadnych XML layout poza theme)
- **Min SDK 26 (Android 8.0)**, Target SDK 34 (Android 14)
- **Java 17** dla build

### Biblioteki kluczowe
| Library | Purpose |
|---------|---------|
| Compose BOM 2024.06.00 | UI framework |
| Material 3 | Design system |
| Compose Material Icons Extended | Ikony |
| Navigation Compose | Nawigacja między ekranami |
| Room 2.6+ | Lokalna baza (historia konwersacji) |
| WorkManager 2.9+ | Background tasks (proaktywne alerty) |
| Lifecycle/ViewModel | State management |
| OkHttp 4.12 | HTTP do AI providers + HeyCyan |
| kotlinx-serialization | JSON do API |
| Coroutines + Flow | Async |
| EncryptedSharedPreferences | Bezpieczne przechowywanie kluczy API |

### AI / ML
| Library | Purpose |
|---------|---------|
| **Google Gemini SDK** (via OkHttp) | Domyślny provider - vision + video + audio |
| **OpenAI SDK** (via OkHttp) | Backup provider |
| **Anthropic SDK** (via OkHttp) | Backup provider |
| **ML Kit Barcode** | QR scanning |
| **ML Kit Text Recognition v2** | OCR - offline |
| **ML Kit Translation** | Offline tłumacz (17 języków) |

### Hardware
| Library | Purpose |
|---------|---------|
| **HeyCyan AAR SDK** | Vendor BLE + HTTP (387KB, /libs/) |
| **openWakeWord (ONNX Runtime)** | Fraza "Hej Wiktor" na telefonie - on-device, offline, bez kluczy; alternatywa: "Hej Lens" w okularach |
| **Camera2** | Fallback capture (jeśli brak HeyCyan) |
| **Android TTS** | Synteza mowy - streaming, multi-voice |

### Integracje
| Library | Purpose |
|---------|---------|
| Google Sign-In | OAuth do Google Calendar |
| Google Calendar API v3 | Czyta/dodaje eventy |
| OpenWeatherMap | Pogoda + alerty |

---

## 3. File Structure

```
/workspace/jarvis-app/
├── README.md
├── AGENTS.md                    # Ten plik
├── QUICKSTART.md                # Szybki start dla developerów
├── docs/
│   ├── USER_STORIES.md          # Historyjki użytkownika
│   └── ARCHITECTURE.md          # Architektura
├── build.gradle.kts             # Top-level
├── settings.gradle.kts
├── scripts/
│   └── test_gemini.sh           # Test Gemini API
└── app/
    ├── libs/
    │   └── glasses_sdk_20250723_v01.aar  # HeyCyan vendor SDK (387KB)
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/
        │   │   ├── values/{strings,themes,colors}.xml
        │   │   ├── values-v21/themes.xml
        │   │   ├── mipmap-*/ic_launcher{,_round}.xml
        │   │   ├── drawable/ic_launcher_foreground.xml
        │   │   └── xml/{network_security_config,backup_rules,data_extraction_rules}.xml
        │   └── java/pl/victor/app/
        │       ├── VictorApplication.kt      # Application class
        │       ├── AIOrchestrator.kt         # Centralny koordynator
        │       ├── ai/
        │       │   ├── AIProvider.kt         # Interface
        │       │   ├── AIProviderFactory.kt  # Tworzy providery
        │       │   ├── GeminiProvider.kt     # Gemini 1.5+ (video, audio, image)
        │       │   ├── OpenAIProvider.kt     # GPT-4o (image only)
        │       │   ├── ClaudeProvider.kt     # Claude (image only)
        │       │   ├── MiniMaxProvider.kt    # MiniMax M2/M3 (image only)
        │       │   ├── ProviderCapabilities.kt  # Co provider obsługuje
        │       │   └── AIResponseCache.kt    # Memo dla powtórzeń
        │       ├── accessibility/
        │       │   └── AccessibilityService.kt  # 3 tryby dla niewidomych
        │       ├── actions/
        │       │   ├── Action.kt             # 16 typów akcji
        │       │   ├── ActionExecutor.kt     # SAFE mode (Intents)
        │       │   ├── DirectActionExecutor.kt  # DIRECT mode
        │       │   ├── ActionMode.kt         # SAFE/DIRECT enum
        │       │   ├── SmartActionDetector.kt   # Wykrywa z tekstu
        │       │   └── ContactResolver.kt    # SMS/call resolver
        │       ├── audio/
        │       │   └── AudioManager.kt       # TTS + audio focus + voice selection
        │       ├── ble/
        │       │   ├── VictorManager.kt      # BLE + HTTP do okularów
        │       │   └── ButtonActionDetector.kt  # 1x/2x/3x/long press
        │       ├── calendar/
        │       │   └── GoogleCalendarService.kt  # OAuth2 + Calendar API
        │       ├── camera/
        │       │   ├── BurstCaptureManager.kt  # 5 trybów capture
        │       │   └── CaptureModeSelector.kt  # Auto-degradacja
        │       ├── conversation/
        │       │   ├── ConversationContext.kt  # Multi-turn (10 wymian)
        │       │   └── ConversationalMode.kt  # Continuous listening
        │       ├── data/
        │       │   ├── AppDatabase.kt        # Room DB
        │       │   ├── ConversationDao.kt
        │       │   ├── ConversationEntry.kt
        │       │   ├── HistoryRepository.kt  # CRUD + Flow
        │       │   ├── ModelInfo.kt          # Data class
        │       │   ├── ModelRegistry.kt      # 30+ modeli AI
        │       │   ├── RemoteModelValidator.kt  # Sprawdza API
        │       │   ├── SmartModelResolver.kt # Auto-fallback
        │       │   ├── ModelDiscoveryService.kt  # Co tydzień
        │       │   ├── WakeWordRegistry.kt
        │       │   └── SettingsRepository.kt # Encrypted prefs
        │       ├── memory/
        │       │   └── LongTermMemory.kt     # TF-IDF similarity
        │       ├── persona/
        │       │   ├── Persona.kt            # Data class
        │       │   └── PersonaRegistry.kt    # 9 person + custom
        │       ├── power/
        │       │   ├── BatteryProfile.kt    # 3 tryby ECO/NORMAL/PERFORMANCE
        │       │   ├── PowerManager.kt      # Auto battery management
        │       │   └── WakelockHelper.kt    # Bezpieczne krótkie locki
        │       ├── proactive/
        │       │   ├── WeatherService.kt     # OWM API
        │       │   ├── CalendarService.kt    # Android Calendar
        │       │   ├── ProactiveAlertsEngine.kt  # 8 typów alertów
        │       │   ├── ProactiveAlertsWorker.kt  # WorkManager
        │       │   └── ProactiveAlertsScheduler.kt
        │       ├── storage/
        │       │   └── PhotoStorage.kt       # filesDir/photos + videos
        │       ├── translation/
        │       │   └── SimultaneousTranslator.kt  # Offline ML Kit
        │       ├── vision/
        │       │   ├── QRScanner.kt          # ML Kit
        │       │   ├── QRTestActivity.kt     # UI do testów
        │       │   └── OCRReader.kt          # ML Kit Text Recognition v2
        │       ├── wakeword/
        │       │   ├── OpenWakeWord.kt       # openWakeWord: ONNX + mikrofon
        │       │   └── OwwPipeline.kt        # strumieniowe cechy (czysta logika)
        │       ├── web/
        │       │   ├── WebContentFetcher.kt  # HTTP GET URL
        │       │   └── URLAnalyzer.kt        # Wyciąga URL z QR
        │       └── ui/
        │           ├── MainActivity.kt        # Launcher
        │           ├── MainViewModel.kt
        │           ├── MainScreen.kt
        │           ├── theme/Theme.kt         # HeiCyanTheme (do zmiany nazwy)
        │           ├── settings/
        │           │   ├── SettingsActivity.kt  # 9 sekcji + tryb zasilania
        │           │   └── SettingsViewModel.kt
        │           ├── history/
        │           │   ├── HistoryActivity.kt
        │           │   └── HistoryViewModel.kt
        │           ├── pairing/
        │           │   ├── PairingActivity.kt
        │           │   └── PairingViewModel.kt
        │           ├── components/
        │           │   ├── ModelBadge.kt
        │           │   ├── NewModelsBanner.kt
        │           │   └── ActionConfirmationDialog.kt
        │           └── onboarding/
        │               ├── OnboardingActivity.kt   # 9 kroków
        │               ├── OnboardingViewModel.kt
        │               └── OnboardingState.kt
        └── test/
            └── java/pl/victor/app/data/
                ├── SmartModelResolverTest.kt   # 19 testów
                └── ModelRegistryTest.kt         # 8 testów
```

---

## 4. Architecture - szczegóły

### Warstwy

```
┌────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                   │
│  MainActivity, MainScreen, Settings, History, Onboarding│
└──────────────────┬─────────────────────────────────────┘
                   │ StateFlow / ViewModels
┌──────────────────▼─────────────────────────────────────┐
│                  Orchestrator Layer                     │
│              AIOrchestrator (centr.)                    │
│  Koordynuje: capture, AI, TTS, akcje, accessibility     │
└─────┬──────────┬──────────┬──────────┬─────────────────┘
      │          │          │          │
┌─────▼─────┐ ┌──▼─────┐ ┌──▼──────┐ ┌▼──────────┐
│  Camera   │ │  AI    │ │  Audio  │ │  Actions  │
│  (5 tryb) │ │(4 prov)│ │  (TTS)  │ │ (16 typ)  │
└───────────┘ └────────┘ └─────────┘ └───────────┘
      │          │          │          │
┌─────▼──────────▼──────────▼──────────▼─────────────────┐
│                  Hardware / External                   │
│  HeyCyan (BLE+HTTP), Mic, Speaker, Calendar, OWM      │
└────────────────────────────────────────────────────────┘
```

### Data Flow - mowa z okularów (najważniejsza ścieżka)

Ta ścieżka jest osobno, bo NIE przechodzi przez mikrofon telefonu i przez to
łatwo ją przeoczyć. Wygląda tak:

1. Okulary wysyłają notify `0x03` - u producenta to "przycisk AI", ale na tym
   egzemplarzu tą samą ramką przychodzi też WŁASNE słowo wybudzenia okularów
   (nie `0x17`/`0x18`, jak w aplikacji producenta).
2. `ButtonActionDetector` zlicza kliknięcia w oknie 500 ms i emituje akcję.
   Jedno kliknięcie = `startVoiceTurn(fromGlasses = true)`.
3. `GlassesVoiceCapture.start()` dopisuje się do strumienia BLE
   (`VictorManager.addMicStreamListener` → `initPackageNotify`). Pakiety
   `AiChatResponse.getSubData()` to surowy **Opus**.
4. Nasłuch to WYŚCIG (`AIOrchestrator.listenUntilSpeechEnds`): rozpoznawanie
   mowy z mikrofonu telefonu kontra cisza w strumieniu z okularów. Okulary
   nadają tylko wtedy, gdy w mikrofonie coś jest, więc ich cisza jest lepszym
   sygnałem końca wypowiedzi niż piętnastosekundowy zegar rozpoznawania.
5. `GlassesVoiceCapture.stop()` dekoduje Opus → PCM 48 kHz (`OpusDecoder`).
6. `PcmResampler` przelicza na 16 kHz - tyle wymaga rozpoznawanie mowy w
   Androidzie.
7. `SpeechToText.transcribe()` podaje to rozpoznawaniu NA URZĄDZENIU przez
   `EXTRA_AUDIO_SOURCE` (deskryptor potoku, Android 13+). Działa przy
   zgaszonym ekranie i nie walczy o mikrofon z wykrywaniem słowa kluczowego.
8. Wynikiem jest TEKST, który wchodzi w zwykły `handleUserTrigger` - i dopiero
   dzięki temu działają wykrywanie komend, pamięć rozmowy i rozpoznanie
   pytania "co widzę" (które wywołuje aparat).

Gdy kroku 7 nie da się wykonać (Android < 13, brak pobranego modelu języka),
nagranie idzie do modelu jako WAV - to działa tylko z Gemini i tylko przez
`analyzeStream`, bo pozostali providerzy nie przyjmują audio.

### Data Flow - reszta

1. **User trigger** (przycisk, wake word, text) → MainScreen
2. **MainViewModel** → **AIOrchestrator.handleUserTrigger()**
3. **Orchestrator**:
   a. **Capture** (5 trybów: PHOTO/HIGH_QUALITY/FAST_BURST/VIDEO_SHORT/VIDEO_LONG)
   b. **QR scan** (jeśli są zdjęcia)
   c. **URL fetch** (jeśli QR zawiera URL)
   d. **OCR** (jeśli pytanie pasuje)
   e. **Persona + multi-turn context** (buduje prompt)
   f. **AI provider** (Gemini/OpenAI/Claude/MiniMax)
   g. **Cache check** (jeśli powtórzone pytanie)
   h. **Streaming TTS** (zdanie po zdaniu)
   i. **Action detection** (z tekstu odpowiedzi)
   j. **History save** (Room DB)
   k. **Photo save** (filesDir/photos/)

### Key Decisions

**1. AI Provider architecture (4 providery + auto-fallback)**
- Hardcoded list w `ModelRegistry` (30+ modeli)
- `RemoteModelValidator` sprawdza API
- `SmartModelResolver` auto-migruje deprecated modele
- `ModelDiscoveryService` sprawdza co tydzień nowe

**2. Capture modes (5)**
- Provider ma `capabilities` (images/video/audio)
- `CaptureModeSelector` wybiera najlepszy tryb
- Auto-degradacja: video → burst jeśli provider nie obsługuje
- Resolution: LOW/MEDIUM/HIGH/ULTRA (adaptive)

**3. Memory: 2 warstwy**
- **Multi-turn context** (ConversationContext): 10 ostatnich wymian w sesji
- **Long-term memory** (LongTermMemory): TF-IDF similarity do historii

**4. Power management (3 tryby)**
- `PowerManager` monitoruje baterię (BroadcastReceiver)
- Auto-przełącza tryb na bazie %
- ECO: bez wake word, alerty 60 min
- NORMAL: wake word ON, alerty 15 min
- PERFORMANCE: alerty 5 min, AI Pro

**5. Accessibility (3 tryby dla niewidomych)**
- READ_TEXT: OCR + TTS ciągły
- DESCRIBE_SCENE: AI opis co 5s
- NAVIGATE: ostrzeżenia o przeszkodach

---

## 5. Konfiguracja API Keys (EncryptedSharedPreferences)

Wszystkie klucze są szyfrowane AES-256-GCM. User wpisuje je przez Onboarding lub Settings.

| Service | Użycie | Wymagane? |
|---------|--------|-----------|
| **Gemini** | Domyślny provider AI | TAK |
| **OpenAI** | Backup provider | opcjonalne |
| **Anthropic** | Backup provider | opcjonalne |
| **MiniMax** | Ostatnia deska ratunku | opcjonalne |
| **OpenWeatherMap** | Pogoda + alerty | opcjonalne |
| (brak) | Fraza wybudzenia działa bez klucza (openWakeWord / Hej Lens) | - |
| **Google OAuth** | Calendar API | opcjonalne |

**WAŻNE: Klucze NIGDY nie powinny być hardcoded w kodzie ani commitowane do repo!**

User wkleja je przez UI (OnboardingViewModel.saveAndFinish / Settings). Klucze są zapisywane w EncryptedSharedPreferences i czytane przez SettingsRepository.

### Flow dodawania klucza

```
User → OnboardingActivity/Settings 
   → OnboardingViewModel.setApiKey() / SettingsViewModel.setApiKey()
   → SettingsRepository.setApiKey(providerId, key)
   → EncryptedSharedPreferences (AES-256-GCM)
   → SettingsRepository.getApiKey(providerId) przy starcie
   → AIProviderFactory.createProvider() z kluczem
```

---

## 6. Build Instructions

### Wymagania
- Android Studio Hedgehog (2023.1.1) lub nowszy
- JDK 17
- Android SDK z platform 34
- Gradle 8.x (wrapper w projekcie)

### Krok po kroku

```bash
# 1. Otwórz w Android Studio
# File → Open → /workspace/jarvis-app

# 2. Sync Gradle (automatyczny)

# 3. Build APK
./gradlew assembleDebug

# 4. APK w:
# app/build/outputs/apk/debug/app-debug.apk

# 5. Zainstaluj na telefonie
adb install app/build/outputs/apk/debug/app-debug.apk

# 6. Pierwszy raz - przejdź Onboarding (9 kroków)
# Wpisz klucz Gemini w kroku 3
```

### Bez Android Studio (CLI)

```bash
# 1. Pobierz Android SDK
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip
mv cmdline-tools latest

# 2. Env
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# 3. Licencje + SDK
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 4. Build
cd /workspace/jarvis-app
export ANDROID_HOME=~/android-sdk
./gradlew assembleDebug
```

---

## 7. Testing

### Testy jednostkowe (27)
```bash
./gradlew test
```

Pokrywają:
- **ModelRegistryTest** (8 testów) - poprawność listy modeli
- **SmartModelResolverTest** (19 testów) - logika wyboru modelu, fallback

### Testy manualne (przed buildem)

```bash
# 1. Test Gemini API (z linii komend)
bash scripts/test_gemini.sh

# 2. QR scanning test
adb shell am start -n pl.victor.app.debug/pl.victor.app.vision.QRTestActivity

# 3. Tryby capture
# Settings → Aparat → Burst/Single/Fast/Video
```

### Testy E2E (po dostarczeniu HeyCyan)

1. **BLE Pairing** - sparuj okulary przez Settings → Parowanie
2. **nRF Connect** - zweryfikuj UUIDy (są w AGENTS.md: `7905FFF0...`, `6e40fff0...`)
3. **5 zdjęć** - naciśnij przycisk na okularach 1x → 5 zdjęć → AI → TTS
4. **Wideo** - długi przycisk → 5s wideo 1080p → Gemini analiza
5. **Wake word** - "Computer" (domyślnie) → mikrofon aktywny
6. **Akcje** - "Wyślij SMS do mamy" → dialog → wysłane
7. **Accessibility** - "czytaj" → tryb ciągłego czytania

---

## 8. Known Issues / TODO

### Bugi znalezione w code review (nierozwiązane)

1. **HeyCyan video byte values (0x04, 0x05)** - podejrzewane ale niezweryfikowane
   - `ble/VictorManager.kt:startVideoRecording()` - `byteArrayOf(0x02, 0x04, 0x01)`
   - Te wartości trzeba potwierdzić z rzeczywistymi okularami (nRF Connect)
   - Sprawdź też `0x05` dla stop i `0x06/0x07` dla audio

2. **`@Suppress("unused")` na `wakeWord`** w ConversationalMode - do usunięcia po dodaniu STT

3. **BurstCaptureManager.capturePhoto** - placeholder, wymaga HeyCyan
   - Zwraca pusty bitmap, trzeba podłączyć do glassesManager.takePhoto()

4. **GoogleAccountCredential** - używa prywatnego wrappera z powodu zależności
   - `calendar/GoogleCalendarService.kt` ma `private object GoogleAccountCredential`

5. **OnboardingActivity - nieużywane importy** (`SkipNext`, `CircularProgressIndicator`)
   - To warnings, nie errors - można wyczyścić

6. **AIOrchestrator line 380** - miał bug `${accumulatedText.length` (brak `}`)
   - Już naprawiony

### Ograniczenia

- **HeyCyan firmware** - SDK z 07/2023, może wymagać aktualizacji
- **OpenWeatherMap key** - aktywuje się do 24h po rejestracji
- **openWakeWord** - model "Hej Wiktor" jest w assets/wakeword/ (bazowe modele Apache-2.0)
- **Google OAuth** - trzeba skonfigurować OAuth client w Google Cloud Console
- **HeyCyan Wi-Fi Direct** - do testów z prawdziwymi okularami

### Planowane ficzery (roadmap)

| Ficzer | Trudność | Priorytet |
|--------|----------|-----------|
| Lokalny Whisper (offline STT) | ⭐⭐⭐⭐ | wysoki |
| Voice cloning (ElevenLabs) | ⭐⭐ | średni |
| Lokalne LLM (Llama 3 8B) | ⭐⭐⭐⭐⭐ | niski |
| Outlook Calendar | ⭐⭐ | średni |
| AirDrop iPhone | ⭐⭐⭐ | niski |
| Skróty gestów | ⭐⭐ | średni |

---

## 9. FAQ dla innego AI

### Q: Jak dodać nowy model AI?
A: Edytuj `data/ModelRegistry.kt` - dodaj wpis do `MODELS` listy. Potem `RemoteModelValidator` sprawdzi czy działa. Jeśli `deprecated` - nowy model automatycznie go zastąpi.

### Q: Jak dodać nową personę?
A: Edytuj `persona/PersonaRegistry.kt` - dodaj nowy `Persona(...)` w `all()`. Emoji + nazwa + opis + system prompt.

### Q: Jak dodać nowy capture mode?
A: 
1. Dodaj do enum w `ai/ProviderCapabilities.kt::CaptureMode`
2. Dodaj `frameIntervalMs` i `expectedImageCount`
3. Dodaj case w `camera/BurstCaptureManager.capture()`
4. Sprawdź `capabilities.supportsMode()` - czy provider obsługuje

### Q: Jak dodać nową akcję głosową?
A:
1. Dodaj sealed class do `actions/Action.kt`
2. Dodaj do `enum class ActionType`
3. Dodaj detekcję w `SmartActionDetector.detect()`
4. Dodaj execution w `ActionExecutor.execute()` (lub `DirectActionExecutor`)

### Q: Jak testować bez okularów HeyCyan?
A: 
- Użyj `vision/QRTestActivity` do testów QR
- Użyj `scripts/test_gemini.sh` do testów AI
- Dla TTS: wpisz tekst w polu input → odpowiedź
- Dla accessibility: kliknij przycisk "Czytaj" w MainScreen

### Q: Jak dodać nowy język do tłumacza?
A: Dodaj do `SimultaneousTranslator.SUPPORTED_LANGUAGES` map. Język musi być w ML Kit (sprawdź dokumentację).

### Q: Jak zmienić domyślny provider?
A: `ai/AIProviderFactory.createProvider()` - zmień kolejność lub hardcoded default. Albo dodaj Settings UI.

---

## 10. Konwencje kodu

- **Package:** `pl.victor.app` (wszystko)
- **Stara nazwa:** `pl.heycyan.app` - **zostaw**, bo zmiana = dużo pracy
- **Stary theme:** `HeiCyanTheme` - zostało, też nie ruszaj
- **Język UI:** Polski (strings.xml)
- **Język person:** Polski system prompts
- **Język kodu:** angielskie nazwy zmiennych (jak Kotlin/Java standard)
- **Komentarze:** po polsku (user jest polski)
- **Log.d/w TAG:** klasy zawsze zadeklarowane jako `private const val TAG`

### Imports
- Kolejność: android, androidx, kotlinx, java, com, lokalne
- Wildcard imports: tylko dla R.* i material3.*

### Compose
- `remember` dla state
- `collectAsState()` dla Flow
- `viewModel()` dla ViewModels
- Brak XML layout (poza theme)

---

## 11. Dalsze kroki dla innego AI

Jeśli przejmujesz ten projekt, zacznij od:

1. **Przeczytaj ten plik w całości** (zwłaszcza sekcje 3, 4, 5, 8)
2. **Sprawdź `scripts/test_gemini.sh`** - zweryfikuj że Gemini API działa
3. **Zbuduj APK** (`./gradlew assembleDebug`)
4. **Przetestuj w emulatorze** - bez HeyCyan działa: AI, TTS, OCR, tłumacz
5. **Poczekaj na HeyCyan** (14-21 dni od zamówienia)
6. **E2E testy** - pairing, capture, video

### Hot fix'y typowe

- **TTS nie mówi** → sprawdź czy Android ma polski głos (Settings → System → Languages → Add)
- **Wake word nie działa** → Ustawienia: wybrana fraza i status modelu, RECORD_AUDIO permission, wpisy „Hej Wiktor” w Diagnostyce
- **AI error 401** → sprawdź czy klucz Gemini ma `?key=` (nowe Auth Keys od 2026)
- **BLE nie łączy** → sprawdź Bluetooth permissions (Android 12+)
- **WorkManager nie działa** → sprawdź czy Doze mode nie blokuje (battery optimization)

---

## 12. Kontakt

Projekt rozwijany przez użytkownika (Polska) z AI asystentem. Główne decyzje:

- Hardware: **HeyCyan 161 zł** (nie droższe alternatywy)
- AI: **multi-provider z auto-fallback** (nie pojedynczy)
- Język: **polski native** (nie angielski)
- Prywatność: **lokalne klucze** (nie chmura)
- Dla kogo: **głównie dla siebie + niewidomi** (nie mas-market)

Priorytet: **zrobić działający prototyp** przed rozbudową. Zawsze testować z prawdziwymi okularami zanim commitujesz większe zmiany.

**Powodzenia! 🚀**
