#!/bin/bash
# Odtwarza lokalne środowisko weryfikacji po przeniesieniu na inne konto.
#
# ## Po co to istnieje
# Gradle NIE ZBUDUJE tego projektu w środowisku agenta: maven.google.com i
# dl.google.com są zablokowane przez proxy, więc AGP i AndroidX się nie
# pobiorą. Jedynym prawdziwym buildem jest CI na GitHubie - a ten trwa ~15
# minut i kolejkuje się po jednym.
#
# Dlatego weryfikacja przed wypchnięciem idzie "na sucho": samym kompilatorem
# Kotlina, bez AGP. Nie rozwiąże Compose ani AndroidX, ale rozwiąże WŁASNE
# symbole projektu - a to wyłapuje większość realnych wpadek.
#
# ## Co działa przez proxy, a co nie
#   repo1.maven.org   - DZIAŁA (tylko pobieranie znanych współrzędnych)
#   maven.google.com  - zablokowane
#   dl.google.com     - zablokowane
#   search.maven.org  - zablokowane (czyli nie da się SZUKAĆ, tylko pobierać)
#   alphacephei.com   - zablokowane
#   huggingface.co    - zablokowane
set -e
# Korzeń repo wywodzimy z położenia tego skryptu (leży w <repo>/tools/), a nie
# z zaszytej ścieżki - kontener, w którym powstał, miał repo pod
# /home/user/claude-routines i po przeniesieniu na inne konto nic się nie zgadzało.
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$HOME/victor-verify}"
M=https://repo1.maven.org/maven2
mkdir -p "$DEST/kotlinc" "$DEST/khome/lib"
# groupcheck.sh i uicheck.sh działają z $DEST, nie z repo - muszą skądś wziąć
# jego ścieżkę. Zapisujemy ją obok nich, tak jak last_green_ref.
echo "$REPO" > "$DEST/repo_path"
cd "$DEST/kotlinc"

get() { # $1=url $2=nazwa docelowa
  [ -f "$2" ] && { echo "jest: $2"; return; }
  echo "pobieram: $2"
  curl -fsSL "$1" -o "$2"
}

# Kompilator Kotlina.
#
# UWAGA: projekt buduje się Kotlinem 2.3.20 (patrz build.gradle.kts), a tutaj
# jest 1.9.24. To jest ŚWIADOMY kompromis - 1.9.24 wystarcza do wyłapania
# nierozwiązanych symboli i błędów składni, ale komunikaty bywają inne niż w
# CI (np. "unresolved reference: X" zamiast "Unresolved reference 'X'"), a
# najnowszej składni może nie znać. Lokalne "czysto" NIE JEST gwarancją
# zielonego buildu - jest sitem, nie dowodem.
KV=1.9.24
get "$M/org/jetbrains/kotlin/kotlin-compiler/$KV/kotlin-compiler-$KV.jar" kotlin-compiler.jar
get "$M/org/jetbrains/kotlin/kotlin-stdlib/$KV/kotlin-stdlib-$KV.jar" kotlin-stdlib.jar
get "$M/org/jetbrains/kotlin/kotlin-reflect/$KV/kotlin-reflect-$KV.jar" kotlin-reflect.jar
get "$M/org/jetbrains/kotlin/kotlin-script-runtime/$KV/kotlin-script-runtime-$KV.jar" kotlin-script-runtime.jar
get "$M/org/jetbrains/kotlin/kotlin-daemon-embeddable/$KV/kotlin-daemon-embeddable-$KV.jar" kotlin-daemon-embeddable.jar
get "$M/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" trove4j.jar
get "$M/org/jetbrains/annotations/13.0/annotations-13.0.jar" ../khome/lib/annotations-13.0.jar

# Android bez SDK: jar Robolectrica ma komplet klas frameworka (API 34).
# To jedyna droga, bo dl.google.com jest zablokowane.
get "$M/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar" android-all.jar

# Zależności projektu - wersje MUSZĄ zgadzać się z app/build.gradle.kts.
get "$M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar" coroutines.jar
get "$M/org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.8.1/kotlinx-coroutines-test-jvm-1.8.1.jar" coroutines-test.jar
get "$M/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.6.3/kotlinx-serialization-core-jvm-1.6.3.jar" serialization-core.jar
get "$M/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.6.3/kotlinx-serialization-json-jvm-1.6.3.jar" serialization-json.jar
get "$M/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar" okhttp.jar
get "$M/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar" okio.jar
get "$M/junit/junit/4.13.2/junit-4.13.2.jar" junit-4.13.2.jar
get "$M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" hamcrest-core-1.3.jar

# Vendor SDK okularów - rozpakowany z AAR w repozytorium.
mkdir -p "$DEST/aar"
AAR=$(find "$REPO/jarvis-app" -name "*.aar" | head -1)
if [ -n "$AAR" ]; then
  (cd "$DEST/aar" && unzip -oq "$AAR")
  echo "rozpakowano vendor SDK: $AAR"
else
  echo "UWAGA: nie znalazłem AAR okularów - groupcheck zgłosi więcej błędów niż baseline"
fi

# android.util.Log ma w android-all metody natywne, które w zwykłej JVM
# rzucają UnsatisfiedLinkError - testy potrzebują atrapy.
#
# Katalogi robocze idą do $DEST/work, nie do /tmp: na Windowsie /tmp leży poza
# projektem, a skrypty mają nie zostawiać niczego na zewnątrz.
mkdir -p "$DEST/work/logstub/android/util"
cat > "$DEST/work/logstub/android/util/Log.java" <<'JAVA'
package android.util;
public class Log {
  public static int v(String t, String m) { return 0; }
  public static int d(String t, String m) { return 0; }
  public static int i(String t, String m) { return 0; }
  public static int w(String t, String m) { return 0; }
  public static int e(String t, String m) { return 0; }
  public static int v(String t, String m, Throwable x) { return 0; }
  public static int d(String t, String m, Throwable x) { return 0; }
  public static int i(String t, String m, Throwable x) { return 0; }
  public static int w(String t, String m, Throwable x) { return 0; }
  public static int e(String t, String m, Throwable x) { return 0; }
}
JAVA
javac -d "$DEST/work/logstub" "$DEST/work/logstub/android/util/Log.java"

echo
echo "Gotowe: $DEST"
echo "Skrypty w tools/ zakładają, że leżą OBOK katalogu kotlinc - skopiuj je:"
echo "  cp $REPO/tools/*.sh $DEST/"
echo "  cp $REPO/tools/baseline-groupcheck.txt $DEST/gc22.txt"
