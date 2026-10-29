# ===================================================================
# ProGuard / R8 rules dla V.I.C.T.O.R.
# ===================================================================
# Reguły shrinkowania i obfuscacji dla release build.
# Generowane na podstawie wszystkich używanych bibliotek.
# ===================================================================

# Zachowaj line numbers dla crash reports (ale ukryj source file)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Zachowaj generyczne sygnatury (wymagane przez Kotlin reflection)
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# ===================================================================
# Kotlinx Serialization
# ===================================================================
# Wymagane dla naszych @Serializable DTOs (Gemini, OpenAI, Claude, MiniMax)
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keep,includedescriptorclasses class pl.victor.app.ai.**$$serializer { *; }
-keepclassmembers class pl.victor.app.ai.** {
    *** Companion;
}
-keepclasseswithmembers class pl.victor.app.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===================================================================
# OkHttp / Okio
# ===================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ===================================================================
# Room
# ===================================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Zachowaj nasze encje i DAO
-keep class pl.victor.app.data.ConversationEntry { *; }
-keep class pl.victor.app.data.ConversationDao { *; }
-keep class pl.victor.app.data.AppDatabase { *; }

# ===================================================================
# AndroidX Security (EncryptedSharedPreferences)
# ===================================================================
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ===================================================================
# Compose
# ===================================================================
# Compose ma swoje własne consumer rules, ale dodajemy ostrożnościowo
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.tooling.preview.** { *; }

# ===================================================================
# Vendor HeyCyan SDK (com.oudmon.ble.base)
# ===================================================================
# AAR ma swoje proguard rules, ale dodajemy na wszelki wypadek
-keep class com.oudmon.ble.base.** { *; }
-keep class com.oudmon.ble.** { *; }
-dontwarn com.oudmon.ble.**

# Zachowaj metody używane przez reflection w SDK
-keepclassmembers class com.oudmon.ble.base.** {
    public <init>(...);
    public void *(...);
    public *** *(...);
}

# ===================================================================
# EventBus
# ===================================================================
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-dontwarn org.greenrobot.eventbus.**

# ===================================================================
# ML Kit Barcode Scanning
# ===================================================================
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ===================================================================
# Nasze klasy
# ===================================================================
# Zachowaj ViewModels (potrzebne dla reflection przez viewModel())
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# Zachowaj Application class
-keep class pl.victor.app.VictorApplication { *; }

# Zachowaj Activities (nazwy muszą być widoczne dla systemu)
-keep public class * extends android.app.Activity
-keep public class * extends androidx.activity.ComponentActivity

# ===================================================================
# BuildConfig i inne
# ===================================================================
-keep class pl.victor.app.BuildConfig { *; }

# ===================================================================
# String obfuscation: NIE - bo mamy URL-e i JSON keys
# ===================================================================
# Nie obfuscuj stringów w classach, które budują JSON/URLs
-keepclassmembers class pl.victor.app.ai.** {
    private static final java.lang.String *;
}

# ===================================================================
# Nowe klasy v1.2 (dodane po testach)
# ===================================================================
# OCR (ML Kit - nie obfuscuj bo ma reflect)
-keep class com.google.mlkit.vision.text.** { *; }

# Tłumacz (ML Kit)
-keep class com.google.mlkit.nl.translate.** { *; }

# Google Sign-In / Calendar
-keep class com.google.android.gms.auth.api.** { *; }
-keep class com.google.api.services.calendar.** { *; }
-dontwarn com.google.api.client.googleapis.extensions.android.gms.**

# WebContentFetcher
-keep class pl.victor.app.web.** { *; }

# Translation
-keep class pl.victor.app.translation.** { *; }

# Memory
-keep class pl.victor.app.memory.** { *; }

# Conversation
-keep class pl.victor.app.conversation.** { *; }

# Calendar
-keep class pl.victor.app.calendar.** { *; }

# ===================================================================
# Log
# ===================================================================
# Usuń wszystkie wywołania Log.d/v w release (optymalizacja)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
# Zostaw e (errors) i w (warnings) dla crash reports

# ===================================================================
# Google API Client (Gmail, Kalendarz)
# ===================================================================
# Modele odpowiedzi czytane są refleksją po polach z adnotacją @Key. R8 bez
# tych reguł przemianowuje pola i Gmail/Kalendarz w release zwracają puste
# obiekty (CI nigdy nie buduje release, więc to nie było widać).
-keep class com.google.api.services.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**
-dontwarn javax.naming.**
