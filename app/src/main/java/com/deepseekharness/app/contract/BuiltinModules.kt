package com.deepseekharness.app.contract

import android.content.Context
import android.content.SharedPreferences
import java.util.Objects

/** 随包可信内置模块登记；法定许可聚合继续由宿主与关于页兜底。 */
class BuiltinModules private constructor() {
    companion object {
        @JvmField val ABOUT_MODULE_ID = ModuleId("builtin.about")
        const val ABOUT_CONTRIBUTION_ID = "about"
        @JvmField val ABOUT_CONTRIBUTION_KEY = ABOUT_MODULE_ID.value() + "#" + ABOUT_CONTRIBUTION_ID
        const val ABOUT_TITLE = "关于与许可"
        const val ABOUT_TITLE_EN = "About and licenses"
        @JvmField
        val ABOUT_DESCRIPTOR =
            ModuleDescriptor(
                ABOUT_MODULE_ID,
                1,
                ABOUT_TITLE,
                java.util.Set.of(),
                ModuleDescriptor.Enablement.ALWAYS_ON,
            )
        private val LOCK = Any()
        private val BUILTIN_DIAGNOSTICS = DiagnosticsSink.discarding()
        private var cachedPreferences: SharedPreferences? = null
        private var cachedRegistry: ModuleRegistry? = null

        /** 同一应用上下文的独立布局命名空间复用同一进程内注册表。 */
        @JvmStatic
        fun ensureRegistered(context: Context?): ModuleRegistry {
            if (context == null) throw IllegalArgumentException("CONTEXT_REQUIRED")
            val owner = context.applicationContext ?: context
            return ensureRegistered(
                owner.getSharedPreferences(
                    SharedPreferencesLayoutStore.NAMESPACE,
                    Context.MODE_PRIVATE,
                )
            )
        }

        // final 类的 protected static 保留原 Java 同包入口；外包无法继承扩大可见性。
        @JvmStatic
        protected fun ensureRegistered(preferences: SharedPreferences?): ModuleRegistry {
            Objects.requireNonNull(preferences, "preferences")
            synchronized(LOCK) {
                val previous = cachedRegistry
                if (cachedPreferences === preferences && previous != null) return previous
                val registry =
                    ModuleRegistry(
                        SharedPreferencesLayoutStore(preferences, BUILTIN_DIAGNOSTICS),
                        SafeModeGate(),
                        BUILTIN_DIAGNOSTICS,
                    )
                ensureRegistered(registry)
                cachedPreferences = preferences
                cachedRegistry = registry
                return registry
            }
        }

        /** 幂等登记并安装 about 模块。 */
        @JvmStatic
        fun ensureRegistered(registry: ModuleRegistry?): ContractResult {
            if (registry == null) throw IllegalArgumentException("REGISTRY_REQUIRED")
            if (registry.isRegistered(ABOUT_MODULE_ID)) {
                return if (registry.isInstalled(ABOUT_MODULE_ID)) ContractResult.ok()
                else registry.install(ABOUT_MODULE_ID)
            }
            val registered = registry.register(ABOUT_DESCRIPTOR, ModuleFactory { aboutInstance() })
            if (registered.rejected()) return registered
            return registry.install(ABOUT_MODULE_ID)
        }

        private fun aboutInstance(): ModuleInstance =
            object : ModuleInstance {
                private val contributions =
                    java.util.List.of(
                        Contribution.card(
                            ABOUT_MODULE_ID,
                            ABOUT_CONTRIBUTION_ID,
                            ABOUT_TITLE,
                            ABOUT_TITLE_EN,
                            0,
                        )
                    )

                override fun contributions(): List<Contribution> = contributions
            }
    }
}
