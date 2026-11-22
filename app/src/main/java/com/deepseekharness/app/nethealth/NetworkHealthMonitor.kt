package com.deepseekharness.app.nethealth

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.deepseekharness.app.core.ConfigStore
import com.deepseekharness.app.core.HarnessController
import com.deepseekharness.app.device.NetworkDiagnostics
import com.deepseekharness.app.device.NetworkStateMonitor
import com.deepseekharness.app.util.NetworkHealthPolicy
import com.deepseekharness.app.util.NetworkSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * 网络健康一期：只检测、只记录，不重启 Web、不发通知、不改系统设置。
 *
 * 信号：系统拦截（默认网络回调的 blocked）、Web 运行但前台服务缺失、容器 resolv.conf 过期、宿主侧出网探测、Web 运行期间 60
 * 秒心跳的调度断档。所有工作在单个后台线程串行执行；记录写在私有目录，最多 200 条，不含地址与凭据。
 *
 * 不修改运行时描述符钉住的类，只读调用它们的公开方法。
 */
class NetworkHealthMonitor private constructor(context: Context?) {
    /** 一次体检的结果；text 已脱敏，可直接复制。 */
    class Report
    internal constructor(
        @JvmField val verdict: NetworkHealthPolicy.Verdict?,
        @JvmField val evidence: String?,
        @JvmField val text: String?,
    )

    private val app: Context = context!!.applicationContext
    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            val thread = Thread(runnable, "dsha-nethealth")
            thread.isDaemon = true
            thread
        }
    private val store = File(File(app.filesDir, "nethealth"), "events.txt")

    // 以下只在 worker 线程读写
    private var events: List<NetworkHealthPolicy.Event>? = null
    private var lastBeat = 0L
    private var pendingGap = 0L
    private var settle: ScheduledFuture<*>? = null
    @Volatile private var published: List<NetworkHealthPolicy.Event> = java.util.List.of()
    @Volatile private var lastVerdict: NetworkHealthPolicy.Verdict? = NetworkHealthPolicy.Verdict.OK

    private fun start() {
        worker.execute {
            events = load()
            published = java.util.List.copyOf(events!!)
        }
        try {
            NetworkStateMonitor.ensureStarted(app).addListener { _, _ ->
                schedule(NETWORK_SETTLE_MS)
            }
        } catch (unavailable: RuntimeException) {
            Log.w(TAG, "network monitor unavailable: " + unavailable.javaClass.simpleName)
        }
        worker.scheduleWithFixedDelay(
            this::heartbeat,
            NetworkHealthPolicy.HEARTBEAT_MS,
            NetworkHealthPolicy.HEARTBEAT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** App 全部界面离开前台：稍后复核一次（主要用于发现前台服务缺失）。 */
    fun onAppBackground() {
        schedule(BACKGROUND_SETTLE_MS)
    }

    /** 最近事件快照（旧在前），可在任意线程读取。 */
    fun events(): List<NetworkHealthPolicy.Event> = published

    fun lastVerdict(): NetworkHealthPolicy.Verdict? = lastVerdict

    /** 手动体检：总是探测出网，并附脱敏的宿主网络与容器 DNS 状态。回调在后台线程。 */
    fun checkNow(done: Consumer<Report?>?) {
        worker.execute {
            var report: Report?
            try {
                val signals = collect()
                if (signals.connected) signals.probe = NetworkHealthProbe.run()
                val verdict = NetworkHealthPolicy.classify(signals)
                val evidence = NetworkHealthPolicy.evidence(signals)
                record(verdict, evidence)
                report =
                    Report(
                        verdict,
                        evidence,
                        "DSHA_NET_HEALTH_CHECK_V1\nverdict=" +
                            verdict.name +
                            "\nevidence=" +
                            evidence +
                            "\nprobe_host=" +
                            NetworkHealthProbe.HOST +
                            "\n" +
                            NetworkHealthPolicy.redactNetReport(netReport()),
                    )
            } catch (failure: RuntimeException) {
                report = null
                Log.w(TAG, "check failed: " + failure.javaClass.simpleName)
            }
            done?.accept(report)
        }
    }

    // ---------- 内部：全部在 worker 线程 ----------

    private fun schedule(delayMs: Long) {
        worker.execute {
            settle?.cancel(false)
            settle = worker.schedule(this::evaluate, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun heartbeat() {
        try {
            val now = SystemClock.elapsedRealtime()
            // 省电模式空闲时本来就允许 CPU 休眠，此时的心跳断档不是故障，不计入疑似冻结。
            if (!webActive() || ConfigStore(app).isEcoMode) {
                lastBeat = 0
                return
            }
            val gap = NetworkHealthPolicy.frozenGap(lastBeat, now)
            lastBeat = now
            if (gap > 0) {
                pendingGap = gap
                evaluate()
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "heartbeat failed: " + failure.javaClass.simpleName)
        }
    }

    private fun evaluate() {
        try {
            val signals = collect()
            signals.frozenGapMs = pendingGap
            pendingGap = 0
            if (NetworkHealthPolicy.shouldProbe(signals)) signals.probe = NetworkHealthProbe.run()
            record(NetworkHealthPolicy.classify(signals), NetworkHealthPolicy.evidence(signals))
        } catch (failure: RuntimeException) {
            Log.w(TAG, "evaluate failed: " + failure.javaClass.simpleName)
        }
    }

    private fun collect(): NetworkHealthPolicy.Signals {
        val s = NetworkHealthPolicy.Signals()
        val controller = HarnessController.get(app)
        // 启动中由 WebStartProtection 先拿前台服务；此窗口不判缺失，避免误报。
        s.webActive = controller.requiresRuntimeProtection() && !controller.isStarting
        s.foregroundService = harnessForeground()
        var snapshot: NetworkSnapshot = NetworkStateMonitor.ensureStarted(app).current()
        if (!snapshot.connected()) snapshot = NetworkStateMonitor.query(app)
        s.connected = snapshot.connected()
        s.blocked = snapshot.blocked
        s.validated = snapshot.validated
        s.vpn = snapshot.vpn
        s.transport = snapshot.transport
        s.resolvState = NetworkHealthPolicy.resolvState(netReport())
        return s
    }

    private fun webActive(): Boolean {
        val controller = HarnessController.get(app)
        return controller.requiresRuntimeProtection()
    }

    private fun netReport(): String? =
        NetworkDiagnostics.render(
            app,
            ConfigStore(app).dnsMode,
            java.util.List.of(File(app.filesDir, "linux/ubuntu").absolutePath),
        )

    /** 本应用 HarnessService 是否处于前台；getRunningServices 对调用方自身服务仍返回结果。 */
    @Suppress("DEPRECATION")
    private fun harnessForeground(): Boolean {
        try {
            val manager =
                app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return false
            for (info in manager.getRunningServices(64)) {
                if (info.service == null) continue
                if (app.packageName != info.service.packageName) continue
                if (HARNESS_SERVICE == info.service.className) return info.foreground
            }
        } catch (unavailable: RuntimeException) {
            // 查询失败按「未知」处理：视为存在，避免误报缺失。
            return true
        }
        return false
    }

    private fun record(verdict: NetworkHealthPolicy.Verdict?, evidence: String?) {
        lastVerdict = verdict
        if (verdict == NetworkHealthPolicy.Verdict.OK) return
        if (events == null) events = load()
        events = NetworkHealthPolicy.append(events, System.currentTimeMillis(), verdict, evidence)
        published = java.util.List.copyOf(events!!)
        save(events!!)
    }

    private fun load(): List<NetworkHealthPolicy.Event> {
        try {
            if (!store.isFile || store.length() > 256 * 1024) return ArrayList()
            return NetworkHealthPolicy.parse(
                String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8)
            )
        } catch (unreadable: Exception) {
            return ArrayList()
        }
    }

    /** 先写临时文件再改名，进程中途被杀不留半截记录。 */
    private fun save(value: List<NetworkHealthPolicy.Event>?) {
        val dir = store.parentFile
        val temp = File(dir, "events.txt.tmp")
        try {
            if (dir != null && !dir.isDirectory && !dir.mkdirs()) return
            FileOutputStream(temp).use { out ->
                out.write(NetworkHealthPolicy.serialize(value).toByteArray(StandardCharsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(store)) temp.delete()
        } catch (unwritable: Exception) {
            temp.delete()
            Log.w(TAG, "save failed: " + unwritable.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "DSHA-NETHEALTH"
        private const val NETWORK_SETTLE_MS: Long = 5_000
        private const val BACKGROUND_SETTLE_MS: Long = 3_000
        private const val HARNESS_SERVICE = "com.deepseekharness.app.HarnessService"
        private val START = Any()
        @Volatile private var instance: NetworkHealthMonitor? = null

        /** 惰性启动一次；随进程存活。失败不影响 App 其它功能。 */
        @JvmStatic
        fun ensureStarted(context: Context?): NetworkHealthMonitor {
            val local = instance
            if (local != null) return local
            synchronized(START) {
                if (instance == null) {
                    val created = NetworkHealthMonitor(context)
                    created.start()
                    instance = created
                }
                return instance!!
            }
        }
    }
}
