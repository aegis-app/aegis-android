# 🛡️ AEGIS Android クライアント

地震・津波・気象 自律アシスタント **AEGIS** の Android アプリ（クライアント）です。
Kotlin で実装し、**外部ライブラリはゼロ**: データ取得は `HttpURLConnection` と
自前の JSON パーサのみ、UI はプラットフォームの View で組み立てます。
地震・EEW・津波・気象警報・台風などの防災情報は中継ハブ **A.A.S
（Aegis Alert System）** からのみ受け取り、天気予報は従来どおり気象庁と
Open-Meteo から直接取得します。

| 項目 | 内容 |
|---|---|
| バージョン | v1.0.0 |
| 状態 | 全作り直し（旧 v1.15.x クライアントの再実装） |
| 入手先 | GitHub Releases |
| 実装 | Kotlin（プラットフォーム View、外部ライブラリなし） |
| 更新履歴 | [CHANGELOG.md](CHANGELOG.md) |

設計メモは [DESIGN.md](DESIGN.md)、英語版は [README.md](README.md) を参照。

## 📖 概要

- パッケージ: `jp.aegis.client` / アプリ名: AEGIS
- 対応: Android 8.0 (API 26) 以上、target API 34
- 地点: 高崎（群馬）・大宮（埼玉）が初期設定。設定タブから追加可能

## 📡 A.A.S への接続

地震・津波・EEW・警報・台風の情報源は A.A.S のみです（P2P / Wolfx 直結の
フォールバックはありません）。

- **有効化は設定式**: 設定タブで A.A.S の URL とトークンの両方を保存した
  ときだけ有効になります（SharedPreferences に保存、認証は
  `Authorization: Bearer <トークン>`）。実在のエンドポイントやトークンを
  コードや既定値に埋め込むことは決してしません。
- **SSE が主経路（表示中のみ）**: `GET /v1/stream` に接続します。接続直後に
  保持イベントが再生されますが、再生分（`event: ready` より前）は初期同期
  として状態へマージするだけで、新規通知としては扱いません。切断時は
  5秒→10秒→30秒→60秒のバックオフで再接続します。
- **バックグラウンドは REST**: `GET /v1/events?since=<最終更新時刻>` で
  取りこぼしを回収します。重複判定は `key` + `updatedAt`（EEW の同一視は
  取消でも変わらない `baseKey`）。
- **A.A.S 未設定・到達不能のとき**は地震系の情報は出ませんが、アプリは
  落ちず、天気など他の機能はそのまま使えます。

## ✨ 機能

- **ブリーフィング**: 「📌 現在のまとめ」カード（地点ごとの警報・津波の
  有無・最新地震・台風）+ 新鮮な EEW の固定表示 + 地点ごとの天気
  （気象庁予報 / Open-Meteo 現在気象）
- **イベント**: A.A.S の全 14 種別を新しい順に一覧表示し、タップで詳細。
  表示中は SSE でライブ更新
- **設定**: A.A.S の URL / トークン、接続テスト（`GET /v1/status`）、
  バックグラウンド同期（間隔・地震の震度しきい値・警報モード）、
  通知テスト、地点追加（名称・緯度経度・区域コード）
- **通知**: AlarmManager のチェーン方式で定期同期し、2 チャンネル
  （緊急: EEW・津波 / 防災: 地震・警報）へ投稿。通知 ID は安定させ、
  更新は上書きになる。再起動後は BootReceiver が予約を復旧する

## 🔨 ビルド

正式なビルドはオフライン手動パイプラインです（Gradle/AGP 不要、
ダウンロード不要）: 単体テスト → aapt2 → kotlinc → D8 → 署名。

```bash
# 必要: JDK 17+ と Android SDK (platforms;android-34 / build-tools;34.0.0)
./build.sh            # -> dist/aegis-client.apk (デバッグ署名)
# Windows では build.bat (WSL 経由で同じパイプラインを実行)
```

リリース署名は環境変数でのみ渡します:

```bash
RELEASE_KEYSTORE=/path/to/release.keystore \
RELEASE_KEYSTORE_PASS=... \
RELEASE_KEY_ALIAS=... \
./build-apk.sh release   # -> dist/aegis-client-release.apk
```

## 🧪 テスト

```bash
./tests/run-tests.sh   # 純粋なデータ層の JVM 単体テスト (87件)
```

## 📋 旧実装との差異

全作り直しにあたって再現しなかった機能があります（レーダー画像、
NHK ニュース、AEGIS サーバーモード、GPS 地点登録、朝夕ブリーフィング
通知、EEW 到達カウントダウン表示）。理由と扱いは DESIGN.md の
対応表に記録しています。
