package com.deepseekharness.app.util

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.regex.Pattern

/**
 * 网络健康一期：把检测信号归类为一个结论，并管理去重与环形记录。只判定，不做任何修复动作。
 *
 * 纯逻辑，零 Android import。结论优先级：前台服务缺失 > 系统拦截 > 容器 DNS 过期 > 出网失败 > 疑似冻结。 证据只保存枚举值、布尔值、耗时和异常类名，不保存
 * IP、DNS 地址、URL 路径或凭据。
 */
class NetworkHealthPolicy private constructor() {
    enum class Verdict {
        OK,
        FGS_MISSING,
        SYSTEM_BLOCKED,
        DNS_STALE,
        EGRESS_FAILED,
        SUSPECT_FROZEN,
    }

    /** 一次检测收集到的信号。probe 为 null 表示本次未探测。 */
    class Signals {
        @JvmField var webActive = false
        @JvmField var foregroundService = false
        @JvmField var connected = false
        @JvmField var blocked = false
        @JvmField var validated = false
        @JvmField var vpn = false
        @JvmField var transport: String? = "none"
        @JvmField var resolvState: String? = "unavailable"
        @JvmField var probe: Probe? = null
        @JvmField var frozenGapMs = 0L
    }

    /** 宿主侧出网探测结果；error 只允许异常类名或固定代码。 */
    class Probe(
        @JvmField val dnsOk: Boolean,
        @JvmField val httpsOk: Boolean,
        @JvmField val dnsMs: Long,
        @JvmField val httpsMs: Long,
        error: String?,
    ) {
        @JvmField val error: String = errorName(error)

        fun ok(): Boolean = dnsOk && httpsOk
    }

    class Event(
        @JvmField val at: Long,
        @JvmField val verdict: Verdict?,
        count: Int,
        evidence: String?,
    ) {
        @JvmField val count: Int = Math.max(1, count)
        @JvmField val evidence: String = evidence ?: ""
    }

    companion object {
        /** 心跳间隔；Web 运行期间按此节拍记录调度时间。 */
        const val HEARTBEAT_MS: Long = 60_000

        /** 实际间隔超过这个值视为曾被冻结或深度休眠（约 3 个心跳）。 */
        const val FROZEN_GAP_MS: Long = 180_000

        /** 同一结论在此窗口内只新增一条事件，其余累加计数。 */
        const val DEDUP_MS: Long = 10 * 60_000

        /** 私有记录最多保留的事件数。 */
        const val MAX_EVENTS: Int = 200

        /** 归类：Web 未运行时只看系统拦截和 DNS，不报前台服务缺失与冻结。 */
        @JvmStatic
        fun classify(s: Signals?): Verdict {
            if (s == null) return Verdict.OK
            if (s.webActive && !s.foregroundService) return Verdict.FGS_MISSING
            if (s.blocked) return Verdict.SYSTEM_BLOCKED
            if (s.connected && "stale" == s.resolvState) return Verdict.DNS_STALE
            if (s.connected && s.probe != null && !s.probe!!.ok()) return Verdict.EGRESS_FAILED
            if (s.webActive && s.frozenGapMs >= FROZEN_GAP_MS) return Verdict.SUSPECT_FROZEN
            return Verdict.OK
        }

        /** 信号可疑时值得补一次出网探测；已经能解释原因的结论不再探测。 */
        @JvmStatic
        fun shouldProbe(s: Signals?): Boolean {
            if (s == null || !s.connected || s.blocked) return false
            if (s.webActive && !s.foregroundService) return false
            return !s.validated || "stale" == s.resolvState
        }

        /** 心跳实际间隔超出预期的部分；未超过阈值返回 0。 */
        @JvmStatic
        fun frozenGap(previousBeat: Long, now: Long): Long {
            if (previousBeat <= 0 || now < previousBeat) return 0
            val gap = now - previousBeat
            return if (gap >= FROZEN_GAP_MS) gap else 0
        }

        /** 证据串：固定键顺序，便于用户直接贴到 issue。 */
        @JvmStatic
        fun evidence(s: Signals?): String {
            val out = StringBuilder()
            out.append("web=").append(if (s!!.webActive) 1 else 0)
            out.append(" fgs=").append(if (s.foregroundService) 1 else 0)
            out.append(" net=").append(if (s.connected) known(s.transport, TRANSPORTS) else "none")
            out.append(" vpn=").append(if (s.vpn) 1 else 0)
            out.append(" validated=").append(if (s.validated) 1 else 0)
            out.append(" blocked=").append(if (s.blocked) 1 else 0)
            out.append(" resolv=").append(known(s.resolvState, RESOLV_STATES))
            if (s.probe != null) {
                out.append(" dns=").append(if (s.probe!!.dnsOk) "${s.probe!!.dnsMs}ms" else "fail")
                out.append(" https=")
                    .append(if (s.probe!!.httpsOk) "${s.probe!!.httpsMs}ms" else "fail")
                if (s.probe!!.error.isNotEmpty()) out.append(" err=").append(s.probe!!.error)
            }
            if (s.frozenGapMs > 0) out.append(" gap=").append(s.frozenGapMs / 1000).append('s')
            return out.toString()
        }

        /** 追加一条结论。OK 不入记录；去重保留原时间，返回新的可变浅副本，超限丢弃最旧事件。 */
        @JvmStatic
        fun append(
            events: List<Event>?,
            now: Long,
            verdict: Verdict?,
            evidence: String?,
        ): MutableList<Event> {
            val next = ArrayList(events ?: java.util.List.of())
            if (verdict == null || verdict == Verdict.OK) return next
            for (i in next.size - 1 downTo 0) {
                val last = next[i]
                if (last.verdict != verdict) continue
                if (now >= last.at && now - last.at < DEDUP_MS) {
                    next[i] = Event(last.at, verdict, last.count + 1, evidence)
                    return next
                }
                break
            }
            next.add(Event(now, verdict, 1, evidence))
            while (next.size > MAX_EVENTS) next.removeAt(0)
            return next
        }

        /** 最近 n 条，新的在前；保留反转副本的可变 subList 语义。 */
        @JvmStatic
        fun recent(events: List<Event>?, n: Int): MutableList<Event> {
            val copy = ArrayList(events ?: java.util.List.of())
            Collections.reverse(copy)
            return copy.subList(0, Math.min(Math.max(0, n), copy.size))
        }

        /** 一行一条：at|VERDICT|count|evidence。证据内只替换原有分隔符与 CR/LF。 */
        @JvmStatic
        fun serialize(events: List<Event>?): String {
            val out = StringBuilder("DSHA_NET_HEALTH_V1\n")
            if (events != null) {
                for (e in events) {
                    out.append(e.at)
                        .append('|')
                        .append(e.verdict!!.name)
                        .append('|')
                        .append(e.count)
                        .append('|')
                        .append(e.evidence.replace('|', ' ').replace('\n', ' ').replace('\r', ' '))
                        .append('\n')
                }
            }
            return out.toString()
        }

        /** 解析记录；未知结论或坏行跳过，不让一行坏数据清空全部记录。 */
        @JvmStatic
        fun parse(text: String?): MutableList<Event> {
            val events = ArrayList<Event>()
            if (text == null || !text.startsWith("DSHA_NET_HEALTH_V1")) return events
            for (line in Pattern.compile("\\R").split(text)) {
                val parts = Pattern.compile("\\|").split(line, 4)
                if (parts.size != 4) continue
                try {
                    events.add(
                        Event(
                            java.lang.Long.parseLong(parts[0]),
                            Verdict.valueOf(parts[1]),
                            java.lang.Integer.parseInt(parts[2]),
                            parts[3],
                        )
                    )
                } catch (ignored: IllegalArgumentException) {
                    // 跳过坏行
                }
            }
            while (events.size > MAX_EVENTS) events.removeAt(0)
            return events
        }

        /** 从 DSHA_NET_V1 文本取 resolv_state：任一 rootfs 过期即 stale，全部 current 才 current。 */
        @JvmStatic
        fun resolvState(netReport: String?): String {
            if (netReport == null) return "unavailable"
            var result = "unavailable"
            for (line in Pattern.compile("\\R").split(netReport)) {
                if (!line.startsWith("resolv_state=")) continue
                // Java String.trim 仅移除 <= U+0020，不能换成 Kotlin 的 Unicode trim。
                val value = line.substring("resolv_state=".length).trim { it <= ' ' }
                if ("stale" == value) return "stale"
                if ("unavailable" == result || "current" == result) result = value
            }
            return safeToken(result)
        }

        /** 体检报告去掉 DNS 地址行，私有 DNS 只保留模式不保留服务器名。 */
        @JvmStatic
        fun redactNetReport(netReport: String?): String {
            if (netReport == null) return ""
            val out = StringBuilder()
            for (original in Pattern.compile("\\R").split(netReport)) {
                var line = original
                if (
                    line.isEmpty() ||
                        line.startsWith("device_dns=") ||
                        line.startsWith("resolv_dns=")
                )
                    continue
                if (line.startsWith("private_dns=")) {
                    val space = line.indexOf(' ')
                    if (space > 0) line = line.substring(0, space)
                }
                out.append(line).append('\n')
            }
            return out.toString()
        }

        /** 每个结论的计数，供面板摘要；保留首次出现顺序、null 键与 Int 环绕语义。 */
        @JvmStatic
        fun totals(events: List<Event>?): MutableMap<Verdict?, Int> {
            val totals = LinkedHashMap<Verdict?, Int>()
            if (events != null) for (e in events) totals.merge(e.verdict, e.count) { a, b -> a + b }
            return totals
        }

        private val TRANSPORTS =
            java.util.List.of("wifi", "cellular", "ethernet", "bluetooth", "other", "none")
        private val RESOLV_STATES =
            java.util.List.of("current", "stale", "user", "empty", "unavailable")

        /** 白名单取值：不在已知集合内一律记为 other，不把原始字符串写进记录。 */
        @JvmStatic
        @JvmName("known")
        internal fun known(value: String?, allowed: List<String>?): String =
            if (value != null && allowed!!.contains(value)) value else "other"

        /** 只取异常简单类名或固定代码：丢弃冒号后的消息与包名，避免带出主机或地址。 */
        @JvmStatic
        @JvmName("errorName")
        internal fun errorName(value: String?): String {
            if (value == null) return ""
            val head = Pattern.compile("[:\\s]").split(value, 2)[0]
            val dot = head.lastIndexOf('.')
            return safeToken(if (dot >= 0) head.substring(dot + 1) else head)
        }

        /** 只保留 [A-Za-z0-9._-]，其余替换为 _，最长 64。 */
        @JvmStatic
        @JvmName("safeToken")
        internal fun safeToken(value: String?): String {
            if (value == null) return ""
            val clean = Pattern.compile("[^A-Za-z0-9._-]").matcher(value).replaceAll("_")
            return if (clean.length > 64) clean.substring(0, 64) else clean
        }
    }
}
