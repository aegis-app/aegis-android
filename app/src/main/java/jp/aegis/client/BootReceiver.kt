package jp.aegis.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ========================================
// 🔁 BootReceiver (再起動後の予約復旧)
// ========================================
// 📌 端末の再起動で AlarmManager の予約は消えるため、起動完了を受けて
// バックグラウンド同期の予約を張り直す
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Sync.scheduleNext(context.applicationContext)
        }
    }
}
