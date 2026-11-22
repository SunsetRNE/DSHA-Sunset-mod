package com.deepseekharness.app.contract

import java.util.Objects
import java.util.regex.Pattern

/** 未命中只返回宿主 fallback，不回退到其他模块。 */
class RouteTable(diagnostics: DiagnosticsSink?) {
    class Target(key: String?, private val hostFallback: Boolean) {
        private val key: String

        init {
            if (key == null || !TARGET_KEY.matcher(key).matches())
                throw IllegalArgumentException("ROUTE_TARGET_INVALID")
            this.key = key
        }

        fun key(): String = key

        fun hostFallback(): Boolean = hostFallback

        override fun equals(other: Any?): Boolean =
            other is Target && key == other.key && hostFallback == other.hostFallback

        override fun hashCode(): Int =
            31 * key.hashCode() + java.lang.Boolean.hashCode(hostFallback)

        override fun toString(): String = "Target[key=$key, hostFallback=$hostFallback]"

        companion object {
            private val TARGET_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

            @JvmStatic fun host(): Target = Target(HOST_FALLBACK_KEY, true)

            @JvmStatic fun module(key: String?): Target = Target(key, false)
        }
    }

    private val diagnostics = Objects.requireNonNull(diagnostics, "diagnostics")!!
    private val routes = LinkedHashMap<String, Target>()

    @Synchronized
    fun register(routeKey: String?, target: Target?): ContractResult {
        if (!LayoutSnapshot.validKey(routeKey) || target == null) {
            diagnostics.report(ContractCodes.INVALID_ROUTE, "invalid route registration")
            return ContractResult.rejected(ContractCodes.INVALID_ROUTE)
        }
        if (routes.containsKey(routeKey)) {
            diagnostics.report(ContractCodes.DUPLICATE_ROUTE, "duplicate route: $routeKey")
            return ContractResult.rejected(ContractCodes.DUPLICATE_ROUTE)
        }
        routes[routeKey!!] = target
        return ContractResult.ok()
    }

    @Synchronized
    fun revoke(routeKey: String?): ContractResult {
        if (routeKey == null || !routes.containsKey(routeKey)) {
            diagnostics.report(ContractCodes.UNKNOWN_CONTRIBUTION, "unknown route revoke")
            return ContractResult.rejected(ContractCodes.UNKNOWN_CONTRIBUTION)
        }
        routes.remove(routeKey)
        return ContractResult.ok()
    }

    @Synchronized
    fun contains(routeKey: String?): Boolean = routeKey != null && routes.containsKey(routeKey)

    @Synchronized
    fun resolve(routeKey: String?): Target {
        val target = if (routeKey == null) null else routes[routeKey]
        if (target == null) {
            diagnostics.report(
                ContractCodes.ROUTE_MISSING,
                "route not registered: ${routeKey ?: ""}",
            )
            return Target.host()
        }
        return target
    }

    @Synchronized fun size(): Int = routes.size

    companion object {
        const val HOST_FALLBACK_KEY = "host.fallback"
    }
}
