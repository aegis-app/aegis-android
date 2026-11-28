package jp.aegis.client

// ========================================
// 🃏 テキストセクション (カード表示の共通形)
// ========================================
// 📌 タイトル + 本文 + 重要度レベルを持つ表示単位。AEGIS サーバーモードの
// /api/briefing の変換結果と、テスト表示 (デモ) のセクションで共用する
// 📝 level は "danger" / "warn" / "info" / "ok" のいずれか (旧実装の Section と同じ)

// 🃏 テキストセクション 1 件
data class TextSection(
    val title: String, // 見出し
    val body: String,  // 本文
    val level: String  // 重要度レベル (danger / warn / info / ok)
)
