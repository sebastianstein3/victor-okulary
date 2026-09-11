#!/bin/bash
export LANG=C.utf8 LC_ALL=C.utf8
# Szybkie sprawdzenie skladni pojedynczych plikow Kotlina, bez pelnego classpath.
# Filtruje bledy wynikajace z braku zaleznosci - zostaja tylko realne bledy skladni.
SP="$(cd "$(dirname "$0")" && pwd)"
K=$SP/kotlinc
for f in "$@"; do
  out=$(java -cp "$K/kotlin-compiler.jar:$SP/khome/lib/annotations-13.0.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -kotlin-home "$SP/khome" \
    -classpath "$K/android-all.jar:$K/coroutines.jar:$K/kotlin-stdlib.jar" \
    -d "$SP/work/null-out" -nowarn "$f" 2>&1 \
    | grep -E "error:" \
    | grep -viE "unresolved reference|cannot access|not a subtype|type mismatch|no value passed|expression '.*' of type|inferred type|none of the following|overload resolution|cannot infer|should be called only from|not applicable to|smart cast|is missing|too many arguments|no parameter with name" \
    | head -6)
  printf '%-55s %s\n' "$(basename $f)" "$([ -z "$out" ] && echo 'skladnia OK' || echo 'BLEDY:')"
  [ -n "$out" ] && echo "$out"
done
