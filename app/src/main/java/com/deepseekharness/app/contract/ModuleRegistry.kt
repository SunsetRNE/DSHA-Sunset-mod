package com.deepseekharness.app.contract

import java.util.IdentityHashMap
import java.util.Objects
import java.util.concurrent.atomic.AtomicLong

/**
 * 编译期模块的登记、安装、贡献、启停和清理；不加载外部代码。
 *
 * 安装以独占预约在锁外创建，再按预约身份发布；关闭与安全模式可使在途预约永久失效。 contractVersion/describe 校验、启停意图持久化与 ModuleHost scope 继续
 * deferred。
 */
class ModuleRegistry(
    layoutStore: LayoutStore?,
    safeMode: SafeModeGate?,
    diagnostics: DiagnosticsSink?,
) {
    private val diagnostics = Objects.requireNonNull(diagnostics, "diagnostics")!!
    private val safeMode = Objects.requireNonNull(safeMode, "safeMode")!!
    private val catalog =
        ContributionCatalog(Objects.requireNonNull(layoutStore, "layoutStore"), diagnostics)
    private val routes = RouteTable(diagnostics)
    private val modules = LinkedHashMap<ModuleId?, Entry>()
    // 工厂允许复用对象；从候选到关闭完成持有身份，旧候选不得清理别代已取得的同一实例。
    private val instanceOwners = IdentityHashMap<ModuleInstance, InstallAttempt>()

    @Synchronized
    fun register(descriptor: ModuleDescriptor?, factory: ModuleFactory?): ContractResult {
        if (descriptor == null || factory == null) {
            diagnostics.report(
                ContractCodes.MODULE_INVALID,
                "register requires descriptor and factory",
            )
            return ContractResult.rejected(ContractCodes.MODULE_INVALID)
        }
        if (modules.containsKey(descriptor.id())) {
            diagnostics.report(
                ContractCodes.MODULE_DUPLICATE_ID,
                "module already registered: ${descriptor.id()}",
            )
            return ContractResult.rejected(ContractCodes.MODULE_DUPLICATE_ID)
        }
        modules[descriptor.id()] = Entry(descriptor, factory)
        return ContractResult.ok()
    }

    /** 只在锁内预约和发布；factory、贡献回调及候选清理均由安装线程在锁外执行。 */
    fun install(id: ModuleId?): ContractResult {
        val entry: Entry
        val attempt: InstallAttempt
        synchronized(this) {
            entry =
                modules[id]
                    ?: run {
                        diagnostics.report(
                            ContractCodes.MODULE_UNKNOWN,
                            "install unknown module: $id",
                        )
                        return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
                    }
            if (entry.instance != null) return ContractResult.ok()
            if (safeMode.forcedSafeMode() && !entry.descriptor.alwaysOn()) {
                diagnostics.report(
                    ContractCodes.SAFE_MODE_RESTRICTED,
                    "safe mode rejects optional module: $id",
                )
                return ContractResult.rejected(ContractCodes.SAFE_MODE_RESTRICTED)
            }
            if (entry.installing != null || entry.closing != null) {
                diagnostics.report(
                    ContractCodes.MODULE_INSTALL_FAILED,
                    "module lifecycle busy: $id",
                )
                return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED)
            }
            attempt = InstallAttempt(GENERATION.incrementAndGet())
            entry.installing = attempt
        }
        var candidate: ModuleInstance? = null
        var ownsCandidate = false
        var published = false
        var failure: Throwable? = null
        try {
            val instance =
                try {
                    entry.factory.create()
                } catch (error: RuntimeException) {
                    diagnostics.report(
                        ContractCodes.MODULE_INSTALL_FAILED,
                        "module factory failed: $id (${error.javaClass.simpleName})",
                    )
                    return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED)
                }
            candidate = instance
            if (instance == null) {
                diagnostics.report(
                    ContractCodes.MODULE_INSTALL_FAILED,
                    "module factory returned null: $id",
                )
                return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED)
            }
            val current: Boolean
            synchronized(this) {
                current = entry.installing === attempt
                if (!instanceOwners.containsKey(instance)) {
                    instanceOwners[instance] = attempt
                    ownsCandidate = true
                }
            }
            if (!current || !ownsCandidate) {
                val code =
                    if (current) ContractCodes.MODULE_INSTALL_FAILED
                    else ContractCodes.STALE_GENERATION
                diagnostics.report(code, "module install no longer owns candidate: $id")
                return ContractResult.rejected(code)
            }
            val contributions =
                try {
                    instance.contributions()
                } catch (error: RuntimeException) {
                    diagnostics.report(
                        ContractCodes.MODULE_INSTALL_FAILED,
                        "module contributions failed: $id (${error.javaClass.simpleName})",
                    )
                    return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED)
                } ?: java.util.List.of()
            val keys = HashSet<String>()
            for (contribution in contributions) {
                if (
                    contribution == null ||
                        id != contribution.owner() ||
                        !keys.add(contribution.globalKey())
                ) {
                    diagnostics.report(
                        ContractCodes.MODULE_INVALID_CONTRIBUTION,
                        "invalid contribution for module: $id",
                    )
                    return ContractResult.rejected(ContractCodes.MODULE_INVALID_CONTRIBUTION)
                }
            }
            // 模块返回的 List 也可执行用户代码；两遍遍历、复制与校验均不得占 registry 锁。
            // 第二遍的空项保持原 NPE 边界，finally 仍先清理候选，且不留下部分登记。
            val copied = ArrayList<Contribution>()
            keys.clear()
            for (item in contributions) {
                val contribution = item!!
                if (id != contribution.owner() || !keys.add(contribution.globalKey())) {
                    diagnostics.report(
                        ContractCodes.MODULE_INVALID_CONTRIBUTION,
                        "invalid contribution for module: $id",
                    )
                    return ContractResult.rejected(ContractCodes.MODULE_INVALID_CONTRIBUTION)
                }
                copied.add(contribution)
            }
            val snapshot = java.util.List.copyOf(copied)
            val result = synchronized(this) { publish(entry, attempt, instance, snapshot) }
            published = result.accepted()
            if (result.rejected()) diagnostics.report(result.code(), "module install rejected: $id")
            return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                if (!published && candidate != null && ownsCandidate) {
                    val reason =
                        synchronized(this) {
                            attempt.cancelledBy ?: ModuleInstance.CloseReason.HOST_CLOSE
                        }
                    try {
                        closeQuietly(candidate, reason)
                    } catch (error: Throwable) {
                        if (failure == null) throw error
                        // 清理的次错不得掩盖正在传播的首错；诊断自身也不能中断预约归还。
                        reportQuietly(
                            ContractCodes.MODULE_CLOSE_FAILED,
                            "module candidate cleanup failed: $id (${error.javaClass.simpleName})",
                        )
                    }
                }
            } finally {
                synchronized(this) {
                    // 旧安装不得清掉 close 后的新预约，也不按候选的裸 key 撤销新代贡献。
                    if (entry.installing === attempt) entry.installing = null
                    if (!published && candidate != null && instanceOwners[candidate] === attempt)
                        instanceOwners.remove(candidate)
                }
            }
        }
    }

    /** 调用者持 registry 锁；只消费宿主已复制的不可变快照。 */
    private fun publish(
        entry: Entry,
        attempt: InstallAttempt,
        instance: ModuleInstance,
        contributions: List<Contribution>,
    ): ContractResult {
        if (entry.installing !== attempt || instanceOwners[instance] !== attempt)
            return ContractResult.rejected(ContractCodes.STALE_GENERATION)
        if (safeMode.forcedSafeMode() && !entry.descriptor.alwaysOn())
            return ContractResult.rejected(ContractCodes.SAFE_MODE_RESTRICTED)
        if (contributions.any { catalog.contains(it.globalKey()) })
            return ContractResult.rejected(ContractCodes.MODULE_INVALID_CONTRIBUTION)
        val addedKeys = ArrayList<String>()
        var committed = false
        try {
            for (contribution in contributions) {
                val added = catalog.add(contribution, attempt.generation)
                if (added.rejected()) return added
                addedKeys.add(contribution.globalKey())
                if (contribution.kind() == Contribution.Kind.ROUTE) {
                    val routed =
                        routes.register(
                            contribution.globalKey(),
                            RouteTable.Target.module(contribution.routeTarget()),
                        )
                    if (routed.rejected()) return routed
                }
            }
            entry.lease =
                GenerationLease.create(entry.descriptor.id(), attempt.generation, diagnostics)
            entry.contributions = contributions
            entry.instance = instance
            entry.installing = null
            committed = true
            return ContractResult.ok()
        } finally {
            if (!committed) revokeKeys(addedKeys)
        }
    }

    /** 停用必须在 registry 锁外 invalidate，避免 registry→lease ABBA。 */
    fun setEnabled(id: ModuleId?, enabled: Boolean): ContractResult {
        synchronized(this) {
            val entry = modules[id]
            if (entry == null) {
                diagnostics.report(ContractCodes.MODULE_UNKNOWN, "setEnabled unknown module: $id")
                return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
            }
            if (!enabled) {
                if (entry.descriptor.alwaysOn()) {
                    diagnostics.report(
                        ContractCodes.MODULE_ALWAYS_ON,
                        "always-on module cannot be disabled: $id",
                    )
                    return ContractResult.rejected(ContractCodes.MODULE_ALWAYS_ON)
                }
            } else {
                if (safeMode.forcedSafeMode() && !entry.descriptor.alwaysOn()) {
                    diagnostics.report(
                        ContractCodes.SAFE_MODE_RESTRICTED,
                        "safe mode rejects optional module: $id",
                    )
                    return ContractResult.rejected(ContractCodes.SAFE_MODE_RESTRICTED)
                }
            }
        }
        return if (enabled) install(id) else close(id, ModuleInstance.CloseReason.USER_DISABLED)
    }

    /** 锁内取消预约并摘除状态，锁外依次 invalidate、close、撤销贡献和诊断。 */
    fun close(id: ModuleId?, reason: ModuleInstance.CloseReason?): ContractResult {
        val work =
            synchronized(this) {
                val entry = modules[id]
                if (entry == null) {
                    diagnostics.report(ContractCodes.MODULE_UNKNOWN, "close unknown module: $id")
                    return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
                }
                detach(entry, reason ?: ModuleInstance.CloseReason.HOST_CLOSE)
            } ?: return ContractResult.ok()
        return finishClose(work)
    }

    /** 调用者持 registry 锁；即使 factory 尚未返回、没有 instance，也先使预约永久失效。 */
    private fun detach(entry: Entry, reason: ModuleInstance.CloseReason): CloseWork? {
        val pending = entry.installing
        if (pending != null) {
            pending.cancelledBy = reason
            entry.installing = null
            GENERATION.incrementAndGet()
        }
        val instance = entry.instance ?: return null
        GENERATION.incrementAndGet()
        val work =
            CloseWork(
                entry,
                instance,
                instanceOwners[instance]!!,
                entry.lease,
                entry.contributions,
                reason,
            )
        entry.lease = null
        entry.instance = null
        entry.contributions = java.util.List.of()
        entry.closing = work
        return work
    }

    private fun finishClose(work: CloseWork): ContractResult {
        val id = work.entry.descriptor.id()
        try {
            work.lease?.invalidateFromHost()
            var failure: Throwable? = null
            try {
                work.instance.close(work.reason)
            } catch (error: Throwable) {
                failure = error
                reportQuietly(
                    ContractCodes.MODULE_CLOSE_FAILED,
                    "module close failed: $id (${error.javaClass.simpleName})",
                )
            }
            for (contribution in work.contributions) {
                try {
                    synchronized(this) { revokeContribution(contribution) }
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                    reportQuietly(
                        ContractCodes.MODULE_CLOSE_FAILED,
                        "module contribution cleanup failed: $id (${error.javaClass.simpleName})",
                    )
                }
            }
            reportQuietly(ContractCodes.MODULE_DISABLED, "module closed: $id (${work.reason})")
            if (failure is Error) throw failure
            return if (failure == null) ContractResult.ok()
            else ContractResult.rejected(ContractCodes.MODULE_CLOSE_FAILED)
        } finally {
            synchronized(this) {
                // 关闭期间拒绝同模块重装，防止旧贡献的强制撤销误伤新代同键。
                if (work.entry.closing === work) work.entry.closing = null
                if (instanceOwners[work.instance] === work.owner)
                    instanceOwners.remove(work.instance)
            }
        }
    }

    fun enterSafeMode(): ContractResult {
        val optional = ArrayList<CloseWork>()
        synchronized(this) {
            safeMode.enterFromHost()
            for (entry in modules.values) {
                if (!entry.descriptor.alwaysOn()) {
                    val work = detach(entry, ModuleInstance.CloseReason.SAFE_MODE)
                    if (work != null) optional.add(work)
                }
            }
        }
        // 摘除的是当时的实例快照；退出安全模式后的新安装不属于这次清理。
        var failure: Error? = null
        for (work in optional) {
            try {
                finishClose(work)
            } catch (error: Error) {
                if (failure == null) failure = error
            }
        }
        if (failure != null) throw failure
        diagnostics.report(ContractCodes.SAFE_MODE_ENTERED, "safe mode entered")
        return ContractResult.ok()
    }

    @Synchronized
    fun exitSafeMode() {
        safeMode.exitFromHost()
    }

    @Synchronized
    fun revoke(contributionKey: String?): ContractResult {
        val revoked = catalog.revoke(contributionKey)
        if (revoked.accepted() && routes.contains(contributionKey)) routes.revoke(contributionKey)
        return revoked
    }

    @Synchronized
    fun contributions(): List<ContributionView> {
        val all = catalog.contributions()
        if (!safeMode.forcedSafeMode()) return all
        val filtered = ArrayList<ContributionView>()
        for (view in all) {
            val entry = modules[view.owner()]
            if (entry != null && entry.descriptor.alwaysOn()) filtered.add(view)
        }
        return java.util.List.copyOf(filtered)
    }

    @Synchronized
    fun visibleContributions(): List<ContributionView> {
        val visible = ArrayList<ContributionView>()
        for (view in contributions()) if (!view.hidden()) visible.add(view)
        return java.util.List.copyOf(visible)
    }

    @Synchronized fun layout(): LayoutSnapshot = catalog.layout()

    @Synchronized
    fun setContributionHidden(contributionKey: String?, hidden: Boolean): ContractResult =
        catalog.setHidden(contributionKey, hidden)

    @Synchronized
    fun setContributionOrder(contributionKeys: List<String?>?): ContractResult =
        catalog.setOrder(contributionKeys)

    @Synchronized
    fun resolveRoute(routeKey: String?): RouteTable.Target {
        if (safeMode.forcedSafeMode()) {
            val owner = routeOwner(routeKey)
            val entry = if (owner == null) null else modules[owner]
            if (entry == null || !entry.descriptor.alwaysOn()) {
                diagnostics.report(
                    ContractCodes.SAFE_MODE_RESTRICTED,
                    "safe mode route fallback: ${routeKey ?: ""}",
                )
                return RouteTable.Target.host()
            }
        }
        return routes.resolve(routeKey)
    }

    @Synchronized fun lease(id: ModuleId?): GenerationLease? = modules[id]?.lease

    @Synchronized fun generation(id: ModuleId?): Long = lease(id)?.generation() ?: 0

    /** registry 锁外进入 lease；失效后不补发、不重放。 */
    fun dispatch(id: ModuleId?, callbackGeneration: Long, callback: Runnable?): ContractResult {
        val lease: GenerationLease
        synchronized(this) {
            val entry = modules[id]
            if (entry == null) {
                diagnostics.report(ContractCodes.MODULE_UNKNOWN, "dispatch unknown module: $id")
                return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
            }
            if (callback == null) {
                diagnostics.report(
                    ContractCodes.INVALID_CALLBACK,
                    "dispatch requires callback: $id",
                )
                return ContractResult.rejected(ContractCodes.INVALID_CALLBACK)
            }
            val current = entry.lease
            if (
                current == null || !current.active() || current.generation() != callbackGeneration
            ) {
                diagnostics.report(
                    ContractCodes.STALE_GENERATION,
                    "stale callback generation for $id: $callbackGeneration",
                )
                return ContractResult.rejected(ContractCodes.STALE_GENERATION)
            }
            lease = current
        }
        if (!lease.runIfActive(callback)) {
            if (!lease.active()) {
                diagnostics.report(
                    ContractCodes.STALE_GENERATION,
                    "stale callback generation for $id: $callbackGeneration",
                )
                return ContractResult.rejected(ContractCodes.STALE_GENERATION)
            }
            return ContractResult.rejected(ContractCodes.MODULE_CALLBACK_FAILED)
        }
        return ContractResult.ok()
    }

    @Synchronized fun isRegistered(id: ModuleId?): Boolean = id != null && modules.containsKey(id)

    @Synchronized fun isInstalled(id: ModuleId?): Boolean = modules[id]?.instance != null

    @Synchronized fun descriptor(id: ModuleId?): ModuleDescriptor? = modules[id]?.descriptor

    fun safeModeGate(): SafeModeGate = safeMode

    private fun revokeContribution(contribution: Contribution) {
        val key = contribution.globalKey()
        if (catalog.contains(key)) catalog.revoke(key)
        if (routes.contains(key)) routes.revoke(key)
    }

    private fun revokeKeys(keys: List<String>) {
        for (key in keys) {
            if (catalog.contains(key)) catalog.revoke(key)
            if (routes.contains(key)) routes.revoke(key)
        }
    }

    private fun reportQuietly(code: String, detail: String) {
        try {
            diagnostics.report(code, detail)
        } catch (_: Throwable) {
            // 诊断故障（含 Error）不得中断清理，真正的清理失败由调用者保留。
        }
    }

    private class Entry(val descriptor: ModuleDescriptor, val factory: ModuleFactory) {
        var installing: InstallAttempt? = null
        var closing: CloseWork? = null
        var instance: ModuleInstance? = null
        var lease: GenerationLease? = null
        var contributions: List<Contribution> = java.util.List.of()
    }

    private class InstallAttempt(val generation: Long) {
        var cancelledBy: ModuleInstance.CloseReason? = null
    }

    private class CloseWork(
        val entry: Entry,
        val instance: ModuleInstance,
        val owner: InstallAttempt,
        val lease: GenerationLease?,
        val contributions: List<Contribution>,
        val reason: ModuleInstance.CloseReason,
    )

    companion object {
        private val GENERATION = AtomicLong()

        private fun routeOwner(routeKey: String?): ModuleId? {
            if (routeKey == null) return null
            val separator = routeKey.indexOf('#')
            if (separator <= 0) return null
            return try {
                ModuleId(routeKey.substring(0, separator))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun closeQuietly(instance: ModuleInstance, reason: ModuleInstance.CloseReason) {
            try {
                instance.close(reason)
            } catch (_: RuntimeException) {
                // 安装失败的兜底清理；异常不冒泡，也不重放。
            }
        }
    }
}
