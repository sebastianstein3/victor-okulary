#!/bin/bash
# Szuka IMPORTU USUNIĘTEGO, choć symbol dalej jest w użyciu.
#
# ## Po co osobno, skoro jest uicheck
# Bo uicheck TEGO NIE ZŁAPIE i nie da się tego naprawić w uicheck. Porównuje
# on nazwy nierozwiązanych symboli z wersją bazową i odejmuje to, co wspólne -
# a symbol z Compose jest nierozwiązany w OBU wersjach, bo offline nie ma
# classpathu. Usunięty import niczego więc nie zmienia w tej liście i różnica
# wychodzi pusta.
#
# Tak poszedł build 61: przy przepisywaniu kafelka z `clickable` na
# `combinedClickable` zniknął import `clickable`, a jedno użycie zostało w
# oknie dialogowym. Groupcheck nie dotyka plików UI, syntax filtruje
# "unresolved reference", uicheck odjął szum - i zepsuty plik przeszedł przez
# wszystkie trzy sprawdzarki.
#
# ## Jak to działa
# Porównuje wiersze `import` z wersją bazową. Dla każdego USUNIĘTEGO importu
# sprawdza, czy jego ostatni człon dalej występuje w pliku jako słowo - poza
# komentarzami i poza samymi wierszami importów.
#
# Fałszywy alarm jest tu tani (jedno spojrzenie), przegapienie kosztuje
# siedem minut builda, więc próg jest ustawiony w stronę zgłaszania.
export LANG=C.utf8 LC_ALL=C.utf8
SP="$(cd "$(dirname "$0")" && pwd)"
REPO="${REPO:-$(git -C "$SP" rev-parse --show-toplevel 2>/dev/null)}"
[ -d "$REPO" ] || { echo "nie znam ścieżki repo - ustaw REPO=..."; exit 2; }
cd "$REPO" || exit 2

BASE_REF="${BASE_REF:-$(cat "$SP/last_green_ref" 2>/dev/null)}"
[ -n "$BASE_REF" ] || { echo "brak last_green_ref - ustaw BASE_REF=<sha>"; exit 2; }
echo "baza porównania: $BASE_REF"

# Bez listy plików bierzemy to, co zmienione względem bazy.
if [ "$#" -gt 0 ]; then
  FILES=("$@")
else
  mapfile -t FILES < <(git diff --name-only "$BASE_REF" -- '*.kt')
fi
[ "${#FILES[@]}" -gt 0 ] || { echo "nie ma zmienionych plików .kt"; exit 0; }

# Treść pliku BEZ komentarzy i BEZ wierszy importów - czyli to, co naprawdę
# używa symboli. Wzmianka w dokumentacji ("zamiast `clickable`") nie jest
# użyciem i nie może udawać, że import jest potrzebny.
body() {
  sed -e 's://.*::' "$1" \
    | grep -v '^[[:space:]]*\*' \
    | grep -v '^[[:space:]]*/\*' \
    | grep -v '^[[:space:]]*import '
}

status=0
for f in "${FILES[@]}"; do
  [ -f "$f" ] || continue
  old=$(git show "$BASE_REF:$f" 2>/dev/null | grep '^import ' | sort -u)
  [ -n "$old" ] || continue
  new=$(grep '^import ' "$f" | sort -u)
  removed=$(comm -23 <(echo "$old") <(echo "$new"))
  [ -n "$removed" ] || continue
  tmp=$(mktemp); body "$f" > "$tmp"
  bad=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    # Alias `import a.b.C as D` wprowadza D, nie C.
    sym=$(echo "$line" | sed -E 's/.* as ([A-Za-z_][A-Za-z0-9_]*).*/\1/; t; s/^import +([a-zA-Z0-9_.]+).*/\1/; s/.*\.//')
    case "$sym" in *'*'*) continue;; esac
    if grep -qw "$sym" "$tmp"; then
      bad="$bad
    $sym   (usunięty: $line)"
    fi
  done <<< "$removed"
  rm -f "$tmp"
  if [ -n "$bad" ]; then
    echo "$(basename "$f"): IMPORT USUNIĘTY, A SYMBOL DALEJ UŻYTY:$bad"
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "usunięte importy: nic nie zostało osierocone"
exit $status
