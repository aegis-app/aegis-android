#!/usr/bin/env bash
# AEGIS Android - オフライン手動ビルド (Gradle/AGP を使わず aapt2 + kotlinc + D8 で APK を作る)
# このアプリは外部ライブラリを一切使わないため、パイプラインは旧実装より大幅に単純:
#   単体テスト → aapt2 compile/link (R.java 生成) → kotlinc + javac (R.java)
#   → D8 (kotlin-stdlib.jar を同梱) → zipalign → apksigner
# 使い方:
#   ./build-apk.sh           単体テスト → ビルド → デバッグ署名 (dist/aegis-client.apk)
#   ./build-apk.sh release   単体テスト → ビルド → リリース署名 (dist/aegis-client-release.apk)
#     リリース署名は環境変数で指定する (スクリプト内に秘密情報は書かない):
#       RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS
#       RELEASE_KEY_PASS (省略時は RELEASE_KEYSTORE_PASS と同じ)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar が見つかりません: $AJAR" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "aapt2 が見つかりません: $BT" >&2; exit 1; }
JAVAC="$JAVA_HOME/bin/javac"; KEYTOOL="$JAVA_HOME/bin/keytool"
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ]; then
  if [ -x "$HOME/workspace/tools/kotlin/bin/kotlinc" ]; then KOTLINC="$HOME/workspace/tools/kotlin/bin/kotlinc"; else KOTLINC="$(command -v kotlinc)"; fi
fi
KOTLIN_HOME_DIR="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
STDLIB="$KOTLIN_HOME_DIR/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

MODE="${1:-debug}"
case "$MODE" in
  debug|release) ;;
  *) echo "使い方: $0 [debug|release]" >&2; exit 1 ;;
esac

echo "[1/6] unit tests"
./tests/run-tests.sh

BUILD=build/manual
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" dist

echo "[2/6] aapt2 compile + link (R.java 生成)"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/res.zip"
"$BT/aapt2" link \
  -I "$AJAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0.0 \
  -o "$BUILD/app-unsigned.apk" \
  "$BUILD/res.zip"

echo "[3/6] kotlinc + javac"
find app/src/main/java -name '*.kt' > "$BUILD/sources.txt"
"$KOTLINC" -nowarn -jvm-target 17 -cp "$AJAR" -d "$BUILD/classes" @"$BUILD/sources.txt" $(find "$BUILD/gen" -name 'R.java')
"$JAVAC" -nowarn --release 17 -cp "$AJAR:$BUILD/classes" -d "$BUILD/classes" $(find "$BUILD/gen" -name '*.java')

echo "[4/6] D8 (dex 化、kotlin-stdlib.jar を同梱)"
"$BT/d8" --min-api 26 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') "$STDLIB"

echo "[5/6] APK へ dex を入れて整列"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
(cd "$BUILD/dex" && zip -q "../app-with-dex.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "[6/6] 署名"
if [ "$MODE" = "release" ]; then
  : "${RELEASE_KEYSTORE:?release には RELEASE_KEYSTORE が必要です}"
  : "${RELEASE_KEYSTORE_PASS:?release には RELEASE_KEYSTORE_PASS が必要です}"
  : "${RELEASE_KEY_ALIAS:?release には RELEASE_KEY_ALIAS が必要です}"
  OUT="dist/aegis-client-release.apk"
  "$BT/apksigner" sign --ks "$RELEASE_KEYSTORE" --ks-pass "pass:$RELEASE_KEYSTORE_PASS" \
    --ks-key-alias "$RELEASE_KEY_ALIAS" --key-pass "pass:${RELEASE_KEY_PASS:-$RELEASE_KEYSTORE_PASS}" \
    --out "$OUT" "$BUILD/app-aligned.apk"
else
  KS="debug.keystore"
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi
  OUT="dist/aegis-client.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$OUT" "$BUILD/app-aligned.apk"
fi

"$BT/apksigner" verify "$OUT"
"$BT/aapt2" dump badging "$OUT" | head -3
echo "成果物: $OUT ($(stat -c%s "$OUT") bytes, sha256: $(sha256sum "$OUT" | cut -d' ' -f1))"
