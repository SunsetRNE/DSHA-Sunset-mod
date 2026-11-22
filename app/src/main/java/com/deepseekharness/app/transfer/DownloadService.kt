package com.deepseekharness.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.deepseekharness.app.ui.FileTransfersActivity
import com.deepseekharness.app.util.NotificationIds
import com.deepseekharness.app.util.UiText

/** 用户发起的 dataSync 服务；开机和进程重建只恢复日志，等待手动继续。 */
class DownloadService : Service() {
    private var engine: DownloadEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false
    private var lastStartId = 0
    private var ownedAdmission = 0L
    private val observer =
        Observer<DownloadEngine.State> { value ->
            if (engine!!.isBusy()) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(value))
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(value))
                stopSelf(lastStartId)
            }
        }

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine.get(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    UiText.choose("文件下载", "File downloads"),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val engine = engine!!
        try {
            startForeground(NOTIFICATION_ID, notification(engine.state().value))
            val action = if (intent == null) "" else intent.action
            val id = intent?.getStringExtra("taskId")
            if (PAUSE == action) engine.pause(id) else if (CANCEL == action) engine.cancel(id)
            if (!engine.isBusy()) {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            var lock = wakeLock
            if (lock == null) {
                lock =
                    getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHA:file-download")
                wakeLock = lock
                lock.setReferenceCounted(false)
            }
            if (!lock.isHeld) lock.acquire(6 * 60 * 60 * 1000L)
            ownedAdmission = engine.runQueued()
            if (!observing) {
                observing = true
                engine.state().observeForever(observer)
            }
        } catch (denied: RuntimeException) {
            engine.pauseAll(UiText.choose("下载服务已暂停，请在文件页继续", "Downloads paused; resume in Files"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(state: DownloadEngine.State?): Notification {
        var active: DownloadTask? = null
        if (state != null) {
            for (task in state.tasks) {
                if (task.id!!.equals(state.activeId)) {
                    active = task
                    break
                }
            }
        }
        val busy = engine != null && engine!!.isBusy()
        val open =
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                Intent(this, FileTransfersActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(
                    if (active == null) UiText.choose("DSHA 文件下载", "DSHA file downloads")
                    else active.displayName
                )
                .setContentText(
                    if (active == null)
                        UiText.choose("在文件页查看下载记录", "View transfer history in Files")
                    else active.message
                )
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setOngoing(busy)
                .setAutoCancel(!busy)
        if (active != null && busy) {
            builder.setProgress(
                100,
                if (active.total == 0L) 0 else (active.downloaded * 100 / active.total).toInt(),
                false,
            )
            builder.addAction(0, UiText.choose("暂停", "Pause"), action(PAUSE, active.id, 1))
            builder.addAction(0, UiText.choose("取消", "Cancel"), action(CANCEL, active.id, 2))
        }
        return builder.build()
    }

    private fun action(action: String, id: String?, request: Int): PendingIntent =
        PendingIntent.getService(
            this,
            NOTIFICATION_ID + request,
            Intent(this, DownloadService::class.java).setAction(action).putExtra("taskId", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onTimeout(startId: Int, fgsType: Int) {
        engine!!.pauseAll(
            UiText.choose(
                "系统已暂停长时间下载，进度保留，请手动继续",
                "System time limit; progress kept for manual resume",
            )
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val engine = engine!!
        if (observing) engine.state().removeObserver(observer)
        if (engine.isBusy())
            engine.serviceGone(
                ownedAdmission,
                UiText.choose("下载服务中断，进度保留", "Service interrupted; progress kept"),
            )
        val lock = wakeLock
        if (lock != null && lock.isHeld) lock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        private const val CHANNEL = "dsha_file_downloads"
        private const val NOTIFICATION_ID = NotificationIds.FILE_DOWNLOAD
        private const val PAUSE = "com.deepseekharness.app.FILE_PAUSE"
        private const val CANCEL = "com.deepseekharness.app.FILE_CANCEL"
    }
}
