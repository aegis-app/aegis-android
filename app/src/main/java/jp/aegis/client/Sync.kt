package jp.aegis.client

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import jp.aegis.client.a2s.A2sHttp
import jp.aegis.client.a2s.A2sParse
import jp.aegis.client.a2s.EventStore
import jp.aegis.client.a2s.NotifyPlanner

// ========================================
// 🔔 Sync (バックグラウンド同期と通知)
// ========================================
// 📌 同期は AlarmManager のチェーン方式: SyncReceiver がアラームを受ける
// たびに次回を予約する。正確なアラーム権限は要求せず
// setAndAllowWhileIdle を使う。同期本体は REST の since= 回収で、
// SSE はフォアグラウンド専用 (電池・Doze 配慮は旧実装からの継承事項)
// 📝 通知はプラットフォームの Notification.Builder で組み立てるため、
// このアプリに外部依存は存在しない (minSdk 26 でチャンネル API が使える)
object Sync {

    const val EXTRA_TAB = "aegis_tab" // 通知タップ時に開くタブの指定

    private const val CH_URGENT = "urgent" // 緊急通知チャンネル (EEW・津波)
    private const val CH_NOTICE = "notice" // 防災通知チャンネル (地震・警報)
    private const val REQ_SYNC = 2001      // 同期アラームの PendingIntent 識別子

    // ----------------------------------------
    // ⏰ スケジュール
    // ----------------------------------------

    // ⏰ 次回同期を予約する (無効設定なら予約を取り消す)
    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = syncPendingIntent(ctx)
        if (!Settings.isBgSyncEnabled(ctx) || !A2sParse.isEnabled(Settings.a2sUrl(ctx), Settings.a2sToken(ctx))) {
            am.cancel(pi)
            return
        }
        val intervalMs = Settings.intervalMinutes(ctx).coerceAtLeast(5) * 60_000L
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + intervalMs,
            pi
        )
    }

    private fun syncPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, SyncReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, REQ_SYNC, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ----------------------------------------
    // 🔄 同期本体 (ワーカースレッドから呼ぶ)
    // ----------------------------------------

    // 📥 since= で取りこぼしを回収し、新規分だけ通知する。初回 (since が空) は
    // 全件が初期同期扱いになるため、登録だけして通知は鳴らさない
    fun runSync(ctx: Context) {
        val url = Settings.a2sUrl(ctx)
        val token = Settings.a2sToken(ctx)
        if (!A2sParse.isEnabled(url, token)) return
        val firstRun = Settings.lastSince(ctx).isEmpty()
        val events = try {
            A2sHttp.fetchEvents(url, token, "", Settings.lastSince(ctx), 50)
        } catch (e: Exception) {
            return // 到達不能時は静かに終わる (次回に回収する)
        }
        val store = EventStore()
        val notified = Settings.notifiedIds(ctx)
        var maxUpdated = Settings.lastSince(ctx)
        for (ev in events) {
            val result = store.ingest(ev)
            if (ev.updatedAt > maxUpdated) maxUpdated = ev.updatedAt
            val fresh = result == EventStore.IngestResult.NEW || result == EventStore.IngestResult.UPDATED
            if (firstRun || !fresh) continue
            val plan = NotifyPlanner.plan(
                ev, replay = false, fresh = true,
                nowMillis = System.currentTimeMillis(),
                quakeThresholdScale = Settings.quakeThresholdScale(ctx),
                warnMode = Settings.warnMode(ctx)
            ) ?: continue
            if (!notified.add(plan.id)) continue // 既に通知済みなら二重に鳴らさない
            postNotification(ctx, plan)
        }
        Settings.saveNotifiedIds(ctx, notified)
        if (maxUpdated.isNotEmpty()) Settings.setLastSince(ctx, maxUpdated)
    }

    // ----------------------------------------
    // 🔔 通知の投稿
    // ----------------------------------------

    // 📣 計画どおりに通知を 1 件投稿する (権限が無ければ何もしない)
    fun postNotification(ctx: Context, plan: NotifyPlanner.Plan) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_TAB, "events")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, plan.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channel = if (plan.urgent) CH_URGENT else CH_NOTICE
        val notification = android.app.Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(plan.title)
            .setContentText(plan.body.lineSequence().firstOrNull() ?: "")
            .setStyle(android.app.Notification.BigTextStyle().bigText(plan.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(plan.id.hashCode(), notification)
    }

    // 🔔 通知テスト用: そのまま表示されるダミー計画を投稿する
    fun postTestNotification(ctx: Context) {
        postNotification(
            ctx,
            NotifyPlanner.Plan(
                id = "test:" + System.currentTimeMillis(),
                urgent = false,
                title = "🔔 AEGIS 通知テスト",
                body = "通知が表示されれば、通知設定は正常です。"
            )
        )
    }

    // 📣 通知チャンネルを用意する (緊急 = 高重要度、防災 = 通常の高)
    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_URGENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_URGENT, "緊急通知", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "緊急地震速報・津波の通知" }
            )
        }
        if (nm.getNotificationChannel(CH_NOTICE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_NOTICE, "防災通知", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "地震・気象警報などの通知" }
            )
        }
    }
}
