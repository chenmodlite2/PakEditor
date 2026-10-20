#!/bin/bash
# JVM core tests (no Android APIs at runtime). Excludes AppState.java.
set -e
PROJ=/root/pakeditor-android
OUT=/root/build/out_test
AJ=/root/build/android-34/android-UpsideDownCake/android.jar
STUB=/root/build/lambda-stub.jar
rm -rf "$OUT"; mkdir -p "$OUT/classes"
cd "$PROJ/test"
SRC=$(find "$PROJ/src/com/chenmodlite/pakeditor/core" -name '*.java')
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ:$STUB" \
  -cp "$AJ:$STUB" -d "$OUT/classes" CoreTest.java IoStoreTest.java $SRC
java -cp "$OUT/classes" CoreTest
java -cp "$OUT/classes" IoStoreTest
