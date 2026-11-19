// Top-level build file
plugins {
    id("com.android.application") version "8.5.0" apply false
    // 2.3.20, nie 1.9.24. Pierwotnym powodem było wiązanie do llama.cpp (model
    // lokalny, dziś usunięty), skompilowane Kotlinem 2.3.x. Zostaje, bo od tego
    // czasu dopasowano do niego inne zależności (Room 2.8.4 w app/build.gradle.kts)
    // i składnia kompilatora 2.x (compilerOptions) - cofanie nie daje nic poza
    // ryzykiem. Gradle 8.7 i AGP 8.5.0 są wspierane przez Kotlina 2.3.20.
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    // Od Kotlina 2.0 kompilator Compose jest częścią repo Kotlina, nie osobnym
    // artefaktem sterowanym przez composeOptions.kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
