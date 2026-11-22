package com.deepseekharness.app.nethealth

import android.os.SystemClock
import com.deepseekharness.app.util.NetworkHealthPolicy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 宿主侧出网探测：一次 DNS 解析 + 一次无凭据 HTTPS HEAD。
 *
 * App 进程与容器同 UID，受同一条系统网络规则，宿主探测能代表「这个 UID 能否出网」。 只看能否建立 TLS 并收到任意 HTTP 状态码（401/403
 * 也算通），不跟随重定向、不读正文、不带 Cookie 或 key。必须在工作线程调用。
 */
internal class NetworkHealthProbe private constructor() {
    companion object {
        /** 默认目标：DSH 默认模型服务。只取主机，不带路径。 */
        internal const val HOST = "api.deepseek.com"

        private const val TIMEOUT_MS = 5_000

        @JvmStatic
        @JvmName("run")
        internal fun run(): NetworkHealthPolicy.Probe {
            val start = SystemClock.elapsedRealtime()
            var dnsOk: Boolean
            var error = ""
            // InetAddress 没有超时参数；放到单独线程限时，超时只放弃结果不重试。
            val lookup = Executors.newSingleThreadExecutor()
            try {
                val pending = lookup.submit(Callable { InetAddress.getAllByName(HOST) })
                dnsOk = pending.get(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS).isNotEmpty()
            } catch (timeout: TimeoutException) {
                dnsOk = false
                error = "DNS_TIMEOUT"
            } catch (failure: Exception) {
                dnsOk = false
                val cause = if (failure.cause == null) failure else failure.cause!!
                error = cause.javaClass.simpleName
            } finally {
                lookup.shutdownNow()
            }
            val dnsMs = SystemClock.elapsedRealtime() - start
            if (!dnsOk) return NetworkHealthPolicy.Probe(false, false, dnsMs, 0, error)

            val httpsStart = SystemClock.elapsedRealtime()
            var httpsOk = false
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://$HOST/").openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Connection", "close")
                httpsOk = connection.responseCode > 0
            } catch (failure: Exception) {
                error = failure.javaClass.simpleName
            } finally {
                connection?.disconnect()
            }
            return NetworkHealthPolicy.Probe(
                true,
                httpsOk,
                dnsMs,
                SystemClock.elapsedRealtime() - httpsStart,
                error,
            )
        }
    }
}
