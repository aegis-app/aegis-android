#!/usr/bin/env bash
# AEGIS Android - 単体テスト (Android に依存しない純粋層を kotlinc で
# コンパイルし、JVM で実行する。android.jar は不要)
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"

# JAVA_HOME 未設定なら VM 内の JDK を使う
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/workspace/tools/jdk-21" ]; then
  JAVA_HOME="$HOME/workspace/tools/jdk-21"
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVA="${JAVA_BIN}java"
command -v "$JAVA" >/dev/null 2>&1 || JAVA=java
[ -n "$JAVA_BIN" ] && export PATH="${JAVA_BIN}:$PATH"

# Kotlin コンパイラ (KOTLINC 環境変数 → VM 内の導入済みコンパイラ → PATH の順に探す)
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ] && [ -x "$HOME/workspace/tools/kotlin/bin/kotlinc" ]; then
  KOTLINC="$HOME/workspace/tools/kotlin/bin/kotlinc"
fi
if [ -z "$KOTLINC" ]; then KOTLINC="$(command -v kotlinc || true)"; fi
{ [ -n "$KOTLINC" ] && [ -x "$KOTLINC" ]; } || { echo "kotlinc が見つかりません" >&2; exit 1; }
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# 📝 テスト対象は純粋層のみ (json / a2s の解析・ストア・通知判定・表示文 / weather の解析)
"$KOTLINC" -nowarn -d "$OUT" \
  app/src/main/java/jp/aegis/client/Sections.kt \
  app/src/main/java/jp/aegis/client/json/MiniJson.kt \
  app/src/main/java/jp/aegis/client/a2s/A2sModels.kt \
  app/src/main/java/jp/aegis/client/a2s/A2sHttp.kt \
  app/src/main/java/jp/aegis/client/a2s/A2sParse.kt \
  app/src/main/java/jp/aegis/client/a2s/EventStore.kt \
  app/src/main/java/jp/aegis/client/a2s/SseParser.kt \
  app/src/main/java/jp/aegis/client/a2s/DisplayText.kt \
  app/src/main/java/jp/aegis/client/a2s/EewCountdown.kt \
  app/src/main/java/jp/aegis/client/a2s/NotifyPlanner.kt \
  app/src/main/java/jp/aegis/client/briefing/Briefing.kt \
  app/src/main/java/jp/aegis/client/demo/Demo.kt \
  app/src/main/java/jp/aegis/client/diag/Diagnose.kt \
  app/src/main/java/jp/aegis/client/news/News.kt \
  app/src/main/java/jp/aegis/client/places/Places.kt \
  app/src/main/java/jp/aegis/client/radar/Radar.kt \
  app/src/main/java/jp/aegis/client/server/ServerMode.kt \
  app/src/main/java/jp/aegis/client/weather/WeatherParse.kt \
  tests/TestMain.kt
"$JAVA" -cp "$OUT:$STDLIB" jp.aegis.client.TestMainKt
