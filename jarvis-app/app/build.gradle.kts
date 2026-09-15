plugins {
    id("com.android.application") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    kotlin("kapt")
}

/**
 * Czy zbudować APK WYŁĄCZNIE pod arm64-v8a (`-Pvictor.abi.arm64=true`).
 *
 * ## Ile to daje - ZMIERZONE, nie szacowane
 * 113,7 MB -> 106 MB. Siedem megabajtów, czyli circa 7%.
 *
 * Zakładałem circa połowę, bo wychodziłem z założenia, że ciężar APK to
 * biblioteki natywne powielone w czterech architekturach (llama.cpp, Vosk,
 * Porcupine, ML Kit). Pomiar to obalił - te biblioteki są tylko ułamkiem pliku.
 * Gdzie siedzi reszta, wypisuje teraz krok "Rozmiar i skład APK" w build.yml;
 * dalsze cięcie ma sens dopiero na podstawie tej listy, nie kolejnej hipotezy.
 *
 * Zostaje mimo skromnego zysku: siedem megabajtów mniej przy każdym pobraniu
 * kosztuje zero i nic nie psuje. Nie jest natomiast rozwiązaniem problemu kwoty
 * artefaktów.
 *
 * ## Czemu przez właściwość, a nie na sztywno
 * Bo test dymny chodzi na emulatorze **x86_64**. APK z bibliotekami wyłącznie
 * arm64 nie zainstalowałby się tam w ogóle (INSTALL_FAILED_NO_MATCHING_ABIS) -
 * i zielony build zamieniłby się w czerwony emulator. Właściwość ustawia tylko
 * to zadanie w CI, które buduje plik DO POBRANIA; emulator buduje po staremu,
 * ze wszystkimi architekturami.
 *
 * Każdy telefon z Androidem 8+ sprzedawany od lat jest arm64, więc dla osób
 * testujących nic się nie zmienia. Lokalnie właściwości nie trzeba podawać.
 */
val tylkoArm64 = providers.gradleProperty("victor.abi.arm64").orNull == "true"

// android.kotlinOptions{} zostało usunięte w Kotlinie 2.2 - to jego zamiennik.
// Na poziomie zadania zamiast rozszerzenia kotlin{}, żeby nie zgadywać, jaki
// dokładnie kształt DSL wystawia akurat ta kombinacja pluginów.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Material3 i część API Compose są nadal oznaczone jako eksperymentalne.
        // Włączamy je raz dla całego modułu zamiast dopisywać @OptIn przy
        // każdej funkcji, która używa np. ExposedDropdownMenuBox.
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        )
    }
}

android {
    namespace = "pl.victor.app"
    // 35, nie 34: media3 1.5.1 (Live Stream Lab) wymaga compileSdk >= 35 w
    // metadanych AAR. To tylko podnosi zbiór API dostępnych przy kompilacji -
    // targetSdk zostaje na 34, więc zachowanie apki w runtime się nie zmienia.
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.victor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    /**
     * STAŁY klucz do podpisywania buildów debug.
     *
     * ## Po co to jest w repozytorium
     * Bez tego Gradle generuje `~/.android/debug.keystore` lokalnie, na każdej
     * maszynie inny - a na runnerze CI NOWY PRZY KAŻDYM BUILDZIE. Odcisk SHA-1
     * aplikacji zmieniał się więc co build.
     *
     * Logowanie Google wiąże klienta OAuth z parą (nazwa pakietu, odcisk SHA-1).
     * Przy ruchomym odcisku nie da się go zarejestrować: zarejestrowany dziś,
     * jutro już nie pasuje. Objawia się to komunikatem "klient OAuth nie jest
     * skonfigurowany dla tej wersji aplikacji" (DEVELOPER_ERROR), niezależnie od
     * tego, ile razy poprawi się konfigurację w Google Cloud Console.
     *
     * ## Czy to bezpieczne
     * Tak. Klucz debug NIE JEST tajny - domyślny klucz Androida ma publicznie
     * znane hasło ("android") i jest identyczny na milionach maszyn. Ten służy
     * wyłącznie do tego, żeby odcisk był POWTARZALNY. Do buildów release nie
     * wolno go użyć i nie jest do nich podpięty.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")

            // Patrz komentarz przy `tylkoArm64` na górze pliku.
            if (tylkoArm64) {
                ndk {
                    abiFilters += "arm64-v8a"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Od Kotlina 2.0 kompilator Compose idzie z pluginem
    // org.jetbrains.kotlin.plugin.compose (patrz plugins{} wyżej) - osobne
    // composeOptions.kotlinCompilerExtensionVersion nie jest już potrzebne.

    testOptions {
        unitTests {
            // Testy jednostkowe działają na JVM, gdzie android.util.Log to pusty
            // stub rzucający wyjątkiem. Bez tego każdy test klasy, która loguje,
            // wywala się na RuntimeException zamiast sprawdzić asercje.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            // Biblioteki Google API i Apache HttpClient wnoszą własne kopie
            // plików META-INF; bez wykluczenia mergeDebugJavaResource przerywa
            // build z powodu duplikatów.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    // HeyCyan vendor SDK (AAR z FerSaiyan repo)
    implementation(files("libs/glasses_sdk_20250723_v01.aar"))

    // WYMAGANE przez vendor SDK, choć nic tego nie deklaruje. AAR wciągnięty przez
    // files() nie niesie ŻADNYCH metadanych o zależnościach (w środku są tylko
    // classes.jar + manifest), więc Gradle nie ma skąd wiedzieć, że
    // BleOperateManager.init() sięga po LocalBroadcastManager. Bez tej linijki
    // aplikacja wywala się przy starcie na NoClassDefFoundError - i to Error, nie
    // Exception, więc nasze catch (e: Exception) w initialize() tego nie łapało.
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // EventBus - używany przez vendor SDK do komunikacji wewnętrznej
    implementation("org.greenrobot:eventbus:3.3.1")

    // GSON - do parsowania JSON (Room, Gemini)
    implementation("com.google.code.gson:gson:2.10.1")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Lokalny model AI (offline) - wiązanie Kotlin do llama.cpp (repo JitPack,
    // patrz settings.gradle.kts). Pinowane na sztywno - nowsze wersje zmieniały
    // kształt API (patrz LlamaCppInferenceEngine).
    //
    // Wyklucza własne androidx.core/core-ktx tej biblioteki (ciągnie 1.18.0,
    // które wymaga compileSdk 36 + AGP 8.9.1 - projekt ma 35/8.5.0) - zostaje
    // jawna, już sprawdzona wersja 1.13.1 z tego pliku. To mały wrapper JNI,
    // korzysta z core-ktx co najwyżej po wierzchu, nie z czegoś nowego w 1.18.
    implementation("io.github.ljcamargo:llamacpp-kotlin:0.4.0") {
        exclude(group = "androidx.core", module = "core-ktx")
        exclude(group = "androidx.core", module = "core")
    }

    // Room
    // 2.8.4, nie 2.6.1: room-compiler:2.6.1 niesie wewnątrz (shaded) przestarzałą
    // kotlinx-metadata-jvm, zatrzymaną na formacie metadanych Kotlina 2.0.0 -
    // dependencySubstitution nie miało czego podmienić, bo to nie osobna,
    // rozwiązywalna zależność, tylko część bajtkodu samego room-compiler.jar.
    // 2.8.4 to wciąż linia 2.x (kapt, nie KSP - to dopiero Room 3.0, alpha).
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    // Camera (fallback jeśli chcemy używać kamery telefonu)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Porcupine - on-device wake word
    implementation("ai.picovoice:porcupine-android:3.0.0")
    // Porcupine recorder (do audio capture)
    implementation("ai.picovoice:android-voice-processor:1.0.0")
    // ML Kit - Barcode scanning (QR)
    // Vosk - rozpoznawanie mowy offline, Apache 2.0, BEZ konta i klucza.
    // Używane wyłącznie jako alternatywa dla Picovoice przy wykrywaniu frazy
    // wybudzenia; model językowy pobierany jest na telefon, nie do APK.
    implementation("com.alphacephei:vosk-android:0.3.75")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ML Kit - Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // ML Kit - Translation (offline)
    implementation("com.google.mlkit:translate:17.0.2")

    // Google Sign-In + Calendar + Gmail API (jedno konto, wspólne logowanie)
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20260708-2.0.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20260727-2.0.0")
    // Dysk Google - eksport notatek do Dokumentu, który NotebookLM przyjmuje
    // jako źródło. Zakres drive.file: aplikacja widzi tylko własne pliki.
    implementation("com.google.apis:google-api-services-drive:v3-rev20260901-2.0.0")

    // WorkManager - do scheduled tasks (proactive alerts)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Media3/ExoPlayer + RTSP - wyłącznie dla gated Live Stream Lab (Opcje
    // programistyczne). Odtwarzacz i próbnik RTSP same w sobie nic nie wysyłają
    // do okularów - patrz pl.victor.app.livestream.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
