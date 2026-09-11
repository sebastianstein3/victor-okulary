#!/bin/bash
# Kompilacja grupowa wszystkich zrodel non-UI naraz. Bez AGP nie da sie
# rozwiazac Compose ani AndroidX, wiec liczy sie WYLACZNIE porownanie z
# baseline: nowe bledy dotyczace naszych wlasnych symboli to realne bledy.
export LANG=C.utf8 LC_ALL=C.utf8
SP="$(cd "$(dirname "$0")" && pwd)"; K=$SP/kotlinc
# Ścieżkę repo zapisuje setup.sh do repo_path. Zmienna REPO ma pierwszeństwo.
REPO="${REPO:-$(cat "$SP/repo_path" 2>/dev/null)}"
[ -d "$REPO" ] || { echo "nie znam ścieżki repo - uruchom tools/setup.sh albo ustaw REPO=..."; exit 2; }
SRC=$REPO/jarvis-app/app/src/main/java
# Tablica, nie ciąg znaków: ścieżka do repo może zawierać SPACJE (na Windowsie
# to norma). Przy podziale po spacjach find dostawał dwa nieistniejące katalogi,
# kompilator jeden plik i zwracał "unable to run REPL" - co wyglądało jak
# zielony wynik, bo wierszy z "error:" było mniej niż w bazie.
mapfile -t FILES < <(
  find "$SRC" -name '*.kt' | grep -v '/ui/' | grep -v 'Activity.kt$' | grep -v 'Screen.kt$'
)
[ "${#FILES[@]}" -gt 0 ] || { echo "nie znalazłem źródeł w $SRC"; exit 2; }
java -cp "$K/kotlin-compiler.jar:$SP/khome/lib/annotations-13.0.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home "$SP/khome" \
  -classpath "$K/android-all.jar:$K/kotlin-stdlib.jar:$K/coroutines.jar:$K/okhttp.jar:$K/okio.jar:$K/serialization-core.jar:$K/serialization-json.jar:$SP/aar/classes.jar" \
  -d "$SP/work/gc-out" -nowarn "${FILES[@]}" 2>&1 | grep "error:"
