#!/usr/bin/env bash
# 不依赖 Gradle 的最小构建脚本（aapt2 + javac + d8 + zipalign + apksigner）。
# 用法：
#   export ANDROID_HOME=/path/to/android-sdk     # 需含 platforms/android-34 与 build-tools/33.0.1
#   ./scripts/build.sh
# 产物：build/DigitalClock.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:?请先设置 ANDROID_HOME}"
BT="$SDK/build-tools/33.0.1"
AJ="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/build"
KS="${KEYSTORE:-$ROOT/.signing/clock.jks}"
KS_PASS="${KEYSTORE_PASS:-clock2026}"
KS_ALIAS="${KEYSTORE_ALIAS:-clock}"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile res"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res/resources.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 33 \
  -A "$ROOT/app/src/main/assets" \
  "$OUT/res/resources.zip"

echo "[3/6] javac"
find "$ROOT/app/src/main/java" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
"$BT/d8" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/6] package + align"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] apksigner"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 24 --out "$OUT/DigitalClock.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 24 "$OUT/DigitalClock.apk"

echo "完成：$OUT/DigitalClock.apk"
