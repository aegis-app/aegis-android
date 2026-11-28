package jp.aegis.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ========================================
// ⏰ SyncReceiver (定期同期・朝夕ブリーフィングのアラーム受け口)
// ========================================
// 📌 AlarmManager から呼ばれ、バックグラウンド同期と朝夕ブリーフィングを
// 実行する。チェーン方式: 次回のアラームを先に予約してから作業するので、
// 作業中にプロセスが終了しても連鎖が切れない (旧実装と同じ方針)
class SyncReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SYNC = "jp.aegis.client.ACTION_SYNC"
        const val ACTION_BRIEFING_AM = "jp.aegis.client.ACTION_BRIEFING_AM"
        const val ACTION_BRIEFING_PM = "jp.aegis.client.ACTION_BRIEFING_PM"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        when (intent?.action) {
            ACTION_BRIEFING_AM, ACTION_BRIEFING_PM -> {
                val morning = intent?.action == ACTION_BRIEFING_AM
                // ⏰ 次回を先に予約してから作業する (チェーンを切らさない)
                Sync.scheduleBriefing(appCtx, morning)
                val pending = goAsync()
                Thread {
                    try {
                        Sync.runBriefing(appCtx, morning)
                    } catch (e: Exception) {
                        // 📝 失敗しても次回予約は済んでいる
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            else -> {
                // 📝 ACTION_SYNC、および旧予約 (アクションなし) は定期同期として扱う
                val pending = goAsync()
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
    }
}
