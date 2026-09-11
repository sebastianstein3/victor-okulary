#!/bin/bash
# Sprawdza pliki UI (Compose), których NIE obejmuje groupcheck.sh.
#
# ## Po co, skoro jest syntax.sh
# Bo syntax.sh odfiltrowuje "unresolved reference" - bez pełnego classpath
# KAŻDY symbol androidowy jest nierozwiązany i lista byłaby bezużyteczna.
# Tyle że wśród tego szumu ginie prawdziwy błąd: symbol, którego nie
# zaimportowano. Tak przeszedł build 181 (SettingsRepository bez importu) i
# wcześniej build 164 (zdublowane @Composable).
#
# ## Jak to obchodzimy
# Porównujemy NAZWY nierozwiązanych symboli z tą samą listą dla wersji pliku
# z HEAD. Szum jest w obu identyczny i się skraca; zostaje dokładnie to, co
# wprowadziła zmiana.
export LANG=C.utf8 LC_ALL=C.utf8
SP="$(cd "$(dirname "$0")" && pwd)"; K=$SP/kotlinc
# Ścieżkę repo zapisuje setup.sh do repo_path. Zmienna REPO ma pierwszeństwo.
REPO="${REPO:-$(cat "$SP/repo_path" 2>/dev/null)}"
[ -d "$REPO" ] || { echo "nie znam ścieżki repo - uruchom tools/setup.sh albo ustaw REPO=..."; exit 2; }
WORK=$SP/uicheck; mkdir -p "$WORK"

# BAZA MUSI BYĆ OSTATNIM ZIELONYM BUILDEM, NIE HEAD-em.
# Przy pierwszym podejściu porównywałem z HEAD - a HEAD zawierał już wpadkę,
# którą miałem złapać, więc różnica wyszła pusta i test przepuścił zepsuty kod.
# Brak pliku NIE może po cichu spaść na HEAD - to jest dokładnie ta awaria,
# którą opisuje komentarz wyżej. Lepiej się zatrzymać niż przepuścić zepsuty kod.
BASE_REF="${BASE_REF:-$(cat "$SP/last_green_ref" 2>/dev/null)}"
[ -n "$BASE_REF" ] || { echo "brak last_green_ref - skopiuj go z tools/ albo ustaw BASE_REF=<sha ostatniego zielonego>"; exit 2; }
echo "baza porównania: $BASE_REF"

unresolved() {  # $1 = ścieżka pliku do sprawdzenia
  java -cp "$K/kotlin-compiler.jar:$SP/khome/lib/annotations-13.0.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home "$SP/khome" \
    -classpath "$K/android-all.jar:$K/coroutines.jar:$K/kotlin-stdlib.jar" \
    -d "$SP/work/uicheck-out" -nowarn "$1" 2>&1 \
    | grep -oE "unresolved reference:? '?[A-Za-z_][A-Za-z0-9_]*'?" \
    | sed -E "s/.*[ ']([A-Za-z_][A-Za-z0-9_]*)'?$/\1/" | sort -u
}

status=0
for rel in "$@"; do
  base=$(basename "$rel")
  git -C "$REPO" show "$BASE_REF:$rel" > "$WORK/$base" 2>/dev/null || { echo "$base: nowy plik - pomijam porównanie"; continue; }
  unresolved "$REPO/$rel" > "$WORK/$base.now"
  unresolved "$WORK/$base"  > "$WORK/$base.base"
  new=$(comm -13 "$WORK/$base.base" "$WORK/$base.now")
  if [ -n "$new" ]; then
    echo "$base: NOWE nierozwiązane symbole (prawdopodobnie brak importu):"
    echo "$new" | sed 's/^/    /'
    status=1
  else
    echo "$base: brak nowych nierozwiązanych symboli"
  fi
done
exit $status
