#!/bin/bash
# Szuka ZDUBLOWANYCH adnotacji przed deklaracjami w Kotlinie.
#
# Po co: kompilacja grupowa i sprawdzenie skladni tego NIE lapia, bo @Composable
# jest w harnessie nierozwiazane i wyglada jak zwykly szum. A dwukrotne
# @Composable wywraca CALY build na kapcie ("not a repeatable annotation type").
# Zdarzylo sie dwa razy: przy wstawianiu funkcji POMIEDZY adnotacje a deklaracje.
# Na Windowsie "python3" to zaslepka Microsoft Store, ktora tylko wypisuje
# komunikat i konczy sie bledem - skrypt wygladal wtedy na wykonany, a nie
# sprawdzil niczego. Bierzemy pierwszy interpreter, ktory naprawde dziala.
PY_BIN=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "" >/dev/null 2>&1; then PY_BIN=$c; break; fi
done
[ -n "$PY_BIN" ] || { echo "nie znalazlem dzialajacego Pythona - annocheck pominiety"; exit 2; }
"$PY_BIN" - "$@" <<'PY'
import io, sys, glob
bad = 0
files = sys.argv[1:] or glob.glob("jarvis-app/app/src/**/*.kt", recursive=True)
for path in files:
    try:
        lines = io.open(path, encoding="utf-8").read().split("\n")
    except Exception:
        continue
    for i, l in enumerate(lines):
        t = l.strip()
        if not (t.startswith("fun ") or t.startswith("private fun ") or
                t.startswith("internal fun ") or t.startswith("class ") or
                t.startswith("private class ") or t.startswith("object ")):
            continue
        seen = {}
        j = i - 1
        while j >= 0:
            p = lines[j].strip()
            if p.startswith("@"):
                name = p.split("(")[0]
                seen[name] = seen.get(name, 0) + 1
            elif p == "" or p.startswith("*") or p.startswith("/*") or p == "*/" or p.startswith("//"):
                pass
            else:
                break
            j -= 1
        for name, count in seen.items():
            if count > 1:
                print(f"{path}:{i+1}: {name} powtorzone {count}x przed: {t}")
                bad += 1
print(f"zdublowane adnotacje: {bad}")
sys.exit(1 if bad else 0)
PY
