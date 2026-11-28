package jp.aegis.client.a2s

// ========================================
// 🗄️ EventStore (受信イベントの保持・重複排除)
// ========================================
// 📌 SSE の再生と REST の since= 回収が同じイベントを何度も運んでくるため、
// 状態への取り込みは必ずここを通す。重複判定は A.A.S の契約どおり
// key + updatedAt で、同一視キーは種別ごとに決まる:
// - EEW は取消でも変わらない baseKey で同一視する (key は取消で変わる)
// - それ以外は封筒の key そのもの
// 📝 解除イベント (released: true) は保持せず、既存の同キーイベントを取り除く
// (REST の一覧に解除は載らないという A.A.S の意味論に合わせる)
class EventStore(private val maxItems: Int = 200) {

    // 📦 保持中のイベント (取り込み順ではなく updatedAt の新しい順に並べる)
    private val items = ArrayList<A2sEvent>()

    // 🕐 保持中で最も新しい updatedAt (since= 回収の起点。空なら未同期)
    var lastUpdatedAt: String = ""
        private set

    // 📥 取り込み結果
    enum class IngestResult {
        NEW,       // 初めて見たイベント
        UPDATED,   // 同一視キーは既存だが updatedAt が進んだ (内容更新)
        DUPLICATE, // key + updatedAt が既存と一致 (何もしない)
        RELEASED   // 解除イベントで既存を取り除いた (または対象なし)
    }

    // 🔑 種別ごとの同一視キー
    fun identityKey(ev: A2sEvent): String = when (ev.kind) {
        "eew" -> "eew:" + (ev.eew?.baseKey ?: ev.key)
        else -> ev.kind + ":" + ev.key
    }

    // 🔍 既存一覧に同じ key + updatedAt のイベントがあるか (純粋な判定)
    fun isDuplicate(ev: A2sEvent): Boolean {
        val id = identityKey(ev)
        return items.any { identityKey(it) == id && it.updatedAt == ev.updatedAt }
    }

    // 📥 イベントを状態へ取り込む。戻り値で新規・更新・重複・解除を区別する
    fun ingest(ev: A2sEvent): IngestResult {
        if (!A2sParse.isValid(ev)) return IngestResult.DUPLICATE
        val id = identityKey(ev)
        if (isReleased(ev)) {
            items.removeAll { identityKey(it) == id }
            return IngestResult.RELEASED
        }
        val existing = items.firstOrNull { identityKey(it) == id }
        val result = when {
            existing == null -> IngestResult.NEW
            existing.updatedAt == ev.updatedAt -> return IngestResult.DUPLICATE
            else -> IngestResult.UPDATED
        }
        items.removeAll { identityKey(it) == id }
        items.add(ev)
        items.sortByDescending { it.updatedAt }
        while (items.size > maxItems) items.removeAt(items.size - 1)
        if (ev.updatedAt > lastUpdatedAt) lastUpdatedAt = ev.updatedAt
        return result
    }

    // 📤 保持中イベントのスナップショット (updatedAt の新しい順)
    fun snapshot(): List<A2sEvent> = ArrayList(items)

    // 📤 種別で絞ったスナップショット
    fun byKind(kind: String): List<A2sEvent> = items.filter { it.kind == kind }

    // 📤 最新の EEW (無ければ null)。取消報も含め、最も updatedAt が新しいもの
    fun latestEew(): A2sEvent? = items.firstOrNull { it.kind == "eew" }

    // 🧹 全消去 (設定変更時など)
    fun clear() {
        items.clear()
        lastUpdatedAt = ""
    }

    // 🔍 解除イベントかどうか (warning と共通ペイロード種別が持つ)
    private fun isReleased(ev: A2sEvent): Boolean = when {
        ev.warning != null -> ev.warning.released
        ev.generic != null -> ev.generic.released
        else -> false
    }
}
