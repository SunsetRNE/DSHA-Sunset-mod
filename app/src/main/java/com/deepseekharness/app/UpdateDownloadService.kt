package com.deepseekharness.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.deepseekharness.app.core.UpdateEngine
import com.deepseekharness.app.core.UpdateRepository
import com.deepseekharness.app.ui.UpdateActivity
import com.deepseekharness.app.util.NotificationIds
import com.deepseekharness.app.util.UiText

/** 用户发起的 APK 下载；前台通知和重投递恢复独立于页面。 */
class UpdateDownloadService : Service() {
    private lateinit var engine: UpdateEngine
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val observer =
        Observer<UpdateRepository.State> { state ->
            if (state.busy) {
                notifyState(state)
            } else {
                stopForeground(false)
                notifyState(state)
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()
        engine = UpdateEngine.get(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    UiText.text("应用更新下载"),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // 即使重投递的是取消动作，也先满足 startForegroundService 的时限契约。
            startForeground(ID, notification(engine.state().value))
            if (intent != null && CANCEL == intent.action) {
                engine.cancel()
                if (!observing) {
                    stopForeground(true)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            if (!engine.shouldResume()) {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            if (wakeLock == null) {
                wakeLock =
                    getSystemService(PowerManager::class.java)!!.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "DSHA:update",
                    )
            }
            if (!wakeLock!!.isHeld) wakeLock!!.acquire(2 * 60 * 60 * 1000L)
            engine.runDownload()
            if (!observing) {
                observing = true
                engine.state().observeForever(observer)
            }
            return START_REDELIVER_INTENT
        } catch (error: Exception) {
            engine.startFailed(error)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun notification(state: UpdateRepository.State?): Notification {
        val openPage =
            Intent(this, UpdateActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        val open =
            PendingIntent.getActivity(
                this,
                ID,
                openPage,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(
                    if (state != null && state.apk != null) UiText.text("DSHA 更新已就绪")
                    else UiText.text("DSHA 应用更新")
                )
                .setContentText(if (state == null) UiText.text("正在准备下载…") else state.message)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(state != null && state.busy)
                .setAutoCancel(state == null || !state.busy)
        if (state != null && state.busy) {
            builder.setProgress(
                100,
                if (state.total > 0) (state.downloaded * 100 / state.total).toInt() else 0,
                state.total <= 0,
            )
            val cancel =
                PendingIntent.getService(
                    this,
                    ID,
                    Intent(this, UpdateDownloadService::class.java).setAction(CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(0, UiText.text("取消"), cancel)
        }
        return builder.build()
    }

    private fun notifyState(state: UpdateRepository.State) {
        try {
            getSystemService(NotificationManager::class.java)!!.notify(ID, notification(state))
        } catch (ignored: SecurityException) {}
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync 配额到期必须及时退出；保留分段文件供下一次前台操作继续。
        engine.pause(UiText.text("系统已暂停长时间下载，进度已保留，请在更新页继续"))
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (observing) engine.state().removeObserver(observer)
        if (engine.shouldResume()) {
            engine.pause(UiText.text("下载服务已中断，进度已保留，可继续下载"))
        }
        if (wakeLock != null && wakeLock!!.isHeld) wakeLock!!.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private companion object {
        private const val CHANNEL = "dsha_update_download"
        private const val ID = NotificationIds.UPDATE
        private const val CANCEL = "com.deepseekharness.app.UPDATE_CANCEL"
    }
}
