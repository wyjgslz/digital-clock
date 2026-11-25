#!/usr/bin/env bash
# 构建 Android 5.x 兼容版（minSdk 21 / targetSdk 22）。
# 用法：
#   export ANDROID_HOME=/path/to/android-sdk   # 需含 platforms/android-34 与任一 build-tools
#   ./scripts/build-lite.sh                    # 产物 build/DigitalClockLite.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:?请先设置 ANDROID_HOME}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
PLATFORM="${PLATFORM:-$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)}"
AJ="$PLATFORM/android.jar"
APP="$ROOT/lite"
OUT="$ROOT/build"
KS="${KEYSTORE:-$ROOT/.signing/clock.jks}"
KS_PASS="${KEYSTORE_PASS:-clock2026}"
KS_ALIAS="${KEYSTORE_ALIAS:-clock}"

if [ ! -f "$KS" ]; then
  echo "未找到 keystore，自动生成临时签名密钥：$KS"
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Digital Clock Lite, OU=Dev, O=DigitalClockLite, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/resources.zip"

echo "[2/6] aapt2 link (minSdk 21 / targetSdk 22)"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version 22 \
  -A "$APP/assets" \
  "$OUT/res/resources.zip"

echo "[3/6] javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
"$BT/d8" --lib "$AJ" --min-api 21 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/6] package + align"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] apksigner"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 21 --out "$OUT/DigitalClockLite.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 21 "$OUT/DigitalClockLite.apk"

echo "完成：$OUT/DigitalClockLite.apk"
