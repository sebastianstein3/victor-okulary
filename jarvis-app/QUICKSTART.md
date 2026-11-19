# Quickstart - HeiCyan AI

## Co masz po zakończeniu tego setupu

- ✅ Działająca aplikacja Android z integracją HeyCyan
- ✅ Provider AI (Gemini 2.5 Flash) - działa od razu
- ✅ Burst capture: 5 zdjęć co 2s
- ✅ UI w języku polskim (Jetpack Compose, Material 3)
- ✅ Historia rozmów (Room database, 20 ostatnich)
- ✅ Szyfrowane ustawienia (EncryptedSharedPreferences)
- ✅ TTS przez głośniki okularów (Android TextToSpeech)
- ✅ AudioManager (nagrywanie + synteza mowy)

## Kolejne kroki

### 1. Wygeneruj klucz Gemini API
1. Idź do https://aistudio.google.com/
2. Kliknij "Get API key" → "Create API key"
3. Skopiuj klucz (od 2026 zaczyna się od `AQ.Ab...` – nowy format Auth Key; stare `AIza...` też działają do września 2026)

### 2. Szybki test API (opcjonalnie)
```bash
# Wklej swój klucz (format AQ.Ab8RN... lub AIza...)
export GEMINI_API_KEY=AQ.Ab8RN6LU...twoj_klucz
./scripts/test_gemini.sh
```
Powinieneś zobaczyć polską odpowiedź (np. "Dobrze, dziękuję!").
```bash
export GEMINI_API_KEY=AQ.Ab8RN6LU...twoj_klucz
./scripts/test_gemini.sh
```
Powinieneś zobaczyć polską odpowiedź.

**Format klucza**: od połowy 2026 Google generuje klucze w formacie `AQ.Ab8RN...` (Auth Key). Działają z natywnym endpointem Gemini (`?key=...` w URL). Stare `AIza...` też działają, ale od września 2026 mogą być odrzucane.

### 3. Build i instalacja na Samsung Galaxy A20
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. W aplikacji
1. Otwórz HeiCyan AI
2. Kliknij ⚙️ (Ustawienia)
3. Wpisz klucz Gemini API
4. Zaznacz "Wyszukiwanie w sieci" (domyślnie włączone)
5. Kliknij "Testuj połączenie" - powinno napisać "✓ Sukces!"
6. Wróć do głównego ekranu

### 5. Sparuj HeyCyan (po dostawie)
1. Włącz HeyCyan (przytrzymaj przycisk 3s)
2. W aplikacji kliknij Ustawienia → "Połącz z okularami"
3. Wybierz "HeiCyan-XXXX" z listy BLE
4. Po połączeniu: ikona "Gotowe" ✅

### 6. Test E2E
1. Wciśnij przycisk na okularach (lub duży przycisk w apce)
2. Czekaj 10s (5 zdjęć co 2s)
3. AI odpowie przez głośniki okularów (TTS)
4. Tekst odpowiedzi pojawi się w apce
5. Sprawdź historię 📜 (20 ostatnich rozmów)

## Co jeśli coś nie działa

| Problem | Rozwiązanie |
|---------|-------------|
| BLE nie łączy | Sprawdź czy HeyCyan jest w trybie parowania (LED miga) |
| Brak odpowiedzi AI | Sprawdź klucz API w Ustawieniach → "Testuj połączenie" |
| App crash | Wyślij logcat: `adb logcat -d *:E \| grep HeyCyan` |
| TTS nie mówi | Sprawdź głośność telefonu + połączenie BT audio z okularami |
| Build error | `./gradlew clean && ./gradlew :app:assembleDebug` |

## Stack technologiczny (szczegóły)

- **Język**: Kotlin 1.9.24
- **UI**: Jetpack Compose + Material 3
- **Min SDK**: 26 (Android 8.0) - Samsung A20 ma Android 11
- **Target SDK**: 34 (Android 14)
- **AI**: Gemini 2.5 Flash (multimodal, web search grounding)
- **BLE**: Vendor SDK `com.oudmon.ble.base` (AAR w `libs/`)
- **HTTP**: OkHttp 4.12
- **DB**: Room 2.6.1 (historia)
- **Crypto**: EncryptedSharedPreferences 1.1.0-alpha06 (AES-256-GCM)
- **TTS**: Android TextToSpeech (system, offline, polski)
- **Wiring**: EventBus (używany przez vendor SDK)

## Pliki projektu (pełna struktura)

```
heycyan-app/
├── README.md
├── QUICKSTART.md
├── docs/
│   ├── USER_STORIES.md
│   └── ARCHITECTURE.md
├── scripts/
│   └── test_gemini.sh
└── app/
    ├── libs/
    │   └── glasses_sdk_20250723_v01.aar   (387KB vendor SDK)
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/strings.xml
        ├── res/xml/network_security_config.xml
        └── java/pl/heycyan/app/
            ├── VictorApplication.kt
            ├── AIOrchestrator.kt
            ├── ai/
            │   ├── AIProvider.kt
            │   ├── AIProviderFactory.kt
            │   └── GeminiProvider.kt
            ├── ble/
            │   └── VictorManager.kt
            ├── camera/
            │   └── BurstCaptureManager.kt
            ├── audio/
            │   └── AudioManager.kt
            ├── data/
            │   ├── SettingsRepository.kt
            │   ├── AppDatabase.kt
            │   ├── ConversationDao.kt
            │   ├── ConversationEntry.kt
            │   └── HistoryRepository.kt
            └── ui/
                ├── MainActivity.kt
                ├── MainViewModel.kt
                ├── MainScreen.kt
                ├── theme/Theme.kt
                ├── settings/
                │   ├── SettingsActivity.kt
                │   └── SettingsViewModel.kt
                └── history/
                    ├── HistoryActivity.kt
                    └── HistoryViewModel.kt
```

## Co dalej po MVP

- [ ] OpenAI / Claude / MiniMax providers (szkielet gotowy w `AIProviderFactory.kt`)
- [ ] Fraza wybudzenia ("Hej Wiktor" - openWakeWord, albo "Hej Lens" w okularach)
- [ ] QR scanning (ML Kit - dependency dodana)
- [ ] TTS streaming (bez czekania na pełną odpowiedź)
- [ ] Własny model offline (whisper-tiny, Phi-3 vision)
- [ ] Auto-pobieranie zdjęć (zapisywanie do plików do historii)
