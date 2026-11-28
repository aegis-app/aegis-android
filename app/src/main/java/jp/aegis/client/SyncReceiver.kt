package jp.aegis.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ========================================
// ⏰ SyncReceiver (定期同期のアラーム受け口)
// ========================================
// 📌 AlarmManager から呼ばれ、バックグラウンド同期を 1 回実行して
// 次回のアラームを予約し直す (チェーン方式)
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val appCtx = context.applicationContext
        Thread {
            try {
                Sync.runSync(appCtx)
            } catch (e: Exception) {
                // 📝 同期の失敗で予約チェーンを切らさない
            } finally {
                Sync.scheduleNext(appCtx)
                pending.finish()
            }
        }.start()
    }
}
