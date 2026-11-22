package com.deepseekharness.app.runtime

import com.deepseekharness.app.util.ColdInstallPlan
import java.io.File

/** Immutable launch choices; diagnostics deliberately omit command and environment values. */
class LaunchSpec private constructor(builder: Builder) {
    @JvmField val runtime: ContainerRuntime
    @JvmField val hardlinks: Boolean
    @JvmField val isolated: Boolean
    @JvmField val coldTrace: Boolean
    @JvmField val pipedInput: Boolean
    @JvmField val dataDomain: File?
    @JvmField val record: BoundedGuestSessions.Operation?
    @JvmField val coldMode: ColdInstallPlan.Mode?
    @JvmField val environment: Map<String, String>
    @JvmField val binds: List<ContainerRuntime.Bind>

    init {
        val selectedRuntime = builder.runtime
        if (selectedRuntime == null) throw IllegalArgumentException("LAUNCH_RUNTIME_MISSING")
        if (builder.record != null && !builder.isolated)
            throw IllegalArgumentException("LAUNCH_RECORD_REQUIRES_ISOLATION")
        runtime = selectedRuntime
        hardlinks = builder.hardlinks
        isolated = builder.isolated
        coldTrace = builder.coldTrace
        pipedInput = builder.pipedInput
        dataDomain = builder.dataDomain
        record = builder.record
        coldMode = builder.coldMode
        environment = java.util.Map.copyOf(builder.environment)
        binds = java.util.List.copyOf(builder.binds)
        for (bind in binds) {
            if (
                !bind.guest.startsWith("/") ||
                    bind.guest.contains("/../") ||
                    bind.guest.endsWith("/..")
            )
                throw IllegalArgumentException("LAUNCH_BIND_FORMAT")
            if (bind.host.indexOf('\u0000') >= 0 || bind.guest.indexOf('\u0000') >= 0)
                throw IllegalArgumentException("LAUNCH_BIND_FORMAT")
        }
        for (entry in environment.entries) {
            if (!KEY_PATTERN.matches(entry.key) || entry.value.indexOf('\u0000') >= 0)
                throw IllegalArgumentException("LAUNCH_ENVIRONMENT_FORMAT")
        }
    }

    class Builder internal constructor(internal val runtime: ContainerRuntime?) {
        internal var hardlinks: Boolean = false
        internal var isolated: Boolean = false
        internal var coldTrace: Boolean = false
        internal var pipedInput: Boolean = false
        internal var dataDomain: File? = null
        internal var record: BoundedGuestSessions.Operation? = null
        internal var coldMode: ColdInstallPlan.Mode? = null
        internal var environment: Map<String, String> = emptyMap()
        internal val binds: MutableList<ContainerRuntime.Bind> = ArrayList()

        fun hardlinks(value: Boolean): Builder {
            hardlinks = value
            return this
        }

        fun isolated(value: Boolean): Builder {
            isolated = value
            return this
        }

        fun coldTrace(value: Boolean): Builder {
            coldTrace = value
            return this
        }

        fun pipedInput(value: Boolean): Builder {
            pipedInput = value
            return this
        }

        fun dataDomain(value: File?): Builder {
            dataDomain = value
            return this
        }

        fun record(value: BoundedGuestSessions.Operation?): Builder {
            record = value
            return this
        }

        fun coldMode(value: ColdInstallPlan.Mode?): Builder {
            coldMode = value
            return this
        }

        fun environment(value: Map<String, String>): Builder {
            environment = value
            return this
        }

        fun bind(host: String, guest: String): Builder {
            binds.add(ContainerRuntime.Bind(host, guest))
            return this
        }

        fun build(): LaunchSpec = LaunchSpec(this)
    }

    override fun toString(): String =
        "LaunchSpec[runtime=" +
            runtime.id() +
            ", isolated=" +
            isolated +
            ", pipedInput=" +
            pipedInput +
            ", environmentKeys=" +
            environment.keys +
            "]"

    companion object {
        private val KEY_PATTERN = Regex("[A-Z_][A-Z0-9_]{0,99}")

        @JvmStatic fun runtime(runtime: ContainerRuntime?): Builder = Builder(runtime)
    }
}
