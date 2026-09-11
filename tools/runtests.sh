#!/bin/bash
# Lokalne uruchomienie testów jednostkowych bez Gradle (który nie ma tu dostępu
# do sieci, żeby pobrać AGP). Kompiluje wskazane pliki + test i uruchamia JUnit.
# android.util.Log jest podmieniony na atrapę - w android-all.jar to metody
# natywne, które w zwykłej JVM rzucają UnsatisfiedLinkError.
# Użycie: runtests.sh <klasa.testowa> <plik.kt> [plik.kt ...]
set -e
SP="$(cd "$(dirname "$0")" && pwd)"; K=$SP/kotlinc
W=$SP/work
CLS=$1; shift
rm -rf "$W/kt-out" && mkdir -p "$W/kt-out"
java -cp "$K/kotlin-compiler.jar:$SP/khome/lib/annotations-13.0.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home "$SP/khome" \
  -classpath "$K/android-all.jar:$K/kotlin-stdlib.jar:$K/coroutines.jar:$K/junit-4.13.2.jar:$K/hamcrest-core-1.3.jar" \
  -d "$W/kt-out" -nowarn "$@" 2>&1 | grep -v JAVA_TOOL | grep "error:" && exit 1
java -cp "$W/logstub:$W/kt-out:$K/kotlin-stdlib.jar:$K/coroutines.jar:$K/junit-4.13.2.jar:$K/hamcrest-core-1.3.jar:$K/android-all.jar" \
  org.junit.runner.JUnitCore "$CLS" 2>&1 | grep -v JAVA_TOOL
