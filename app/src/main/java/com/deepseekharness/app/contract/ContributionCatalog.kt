package com.deepseekharness.app.contract

import java.util.Objects

/**
 * 贡献目录：叠加宿主布局，以保存顺序、sortOrder、全局键稳定排序。
 *
 * 未知但合法的布局键保留；未知贡献查询和撤销失败关闭。AboutLicense 聚合继续 deferred。
 */
class ContributionCatalog(layoutStore: LayoutStore?, diagnostics: DiagnosticsSink?) {
    private val layoutStore = Objects.requireNonNull(layoutStore, "layoutStore")!!
    private val diagnostics = Objects.requireNonNull(diagnostics, "diagnostics")!!
    private val entries = LinkedHashMap<String, Entry>()
    private var layout: LayoutSnapshot

    init {
        val loaded =
            try {
                this.layoutStore.load()
            } catch (error: RuntimeException) {
                this.diagnostics.report(
                    ContractCodes.LAYOUT_LOAD_FAILED,
                    "layout load failed: ${error.javaClass.simpleName}",
                )
                null
            }
        if (loaded == null) {
            this.diagnostics.report(
                ContractCodes.LAYOUT_LOAD_FAILED,
                "layout store returned no snapshot",
            )
            layout = LayoutSnapshot.empty()
        } else {
            layout = loaded
        }
    }

    @Synchronized
    fun add(contribution: Contribution?, generation: Long): ContractResult {
        if (contribution == null || generation < 1) {
            diagnostics.report(ContractCodes.MODULE_INVALID_CONTRIBUTION, "catalog add rejected")
            return ContractResult.rejected(ContractCodes.MODULE_INVALID_CONTRIBUTION)
        }
        val key = contribution.globalKey()
        if (entries.containsKey(key)) {
            diagnostics.report(ContractCodes.DUPLICATE_CONTRIBUTION, "duplicate contribution: $key")
            return ContractResult.rejected(ContractCodes.DUPLICATE_CONTRIBUTION)
        }
        entries[key] = Entry(contribution, generation)
        return ContractResult.ok()
    }

    @Synchronized
    fun revoke(contributionKey: String?): ContractResult {
        if (contributionKey == null || !entries.containsKey(contributionKey)) {
            diagnostics.report(
                ContractCodes.UNKNOWN_CONTRIBUTION,
                "unknown contribution revoke: ${contributionKey ?: ""}",
            )
            return ContractResult.rejected(ContractCodes.UNKNOWN_CONTRIBUTION)
        }
        entries.remove(contributionKey)
        return ContractResult.ok()
    }

    @Synchronized
    fun contains(contributionKey: String?): Boolean =
        contributionKey != null && entries.containsKey(contributionKey)

    @Synchronized fun size(): Int = entries.size

    @Synchronized
    fun contributions(): List<ContributionView> {
        val sorted = sortedEntries()
        val views = ArrayList<ContributionView>(sorted.size)
        for (entry in sorted) views.add(view(entry))
        return java.util.List.copyOf(views)
    }

    @Synchronized
    fun visibleContributions(): List<ContributionView> {
        val visible = ArrayList<ContributionView>()
        for (view in contributions()) if (!view.hidden()) visible.add(view)
        return java.util.List.copyOf(visible)
    }

    @Synchronized
    fun byKind(kind: Contribution.Kind?): List<ContributionView> {
        if (kind == null) return java.util.List.of()
        val matching = ArrayList<ContributionView>()
        for (view in contributions()) if (view.kind() == kind) matching.add(view)
        return java.util.List.copyOf(matching)
    }

    @Synchronized
    fun find(contributionKey: String?): ContributionView? {
        val entry = entries[contributionKey]
        if (entry == null) {
            diagnostics.report(
                ContractCodes.UNKNOWN_CONTRIBUTION,
                "unknown contribution lookup: ${contributionKey ?: ""}",
            )
            return null
        }
        return view(entry)
    }

    @Synchronized fun layout(): LayoutSnapshot = layout

    @Synchronized
    fun setHidden(contributionKey: String?, hidden: Boolean): ContractResult {
        if (contributionKey == null || !entries.containsKey(contributionKey)) {
            diagnostics.report(
                ContractCodes.UNKNOWN_CONTRIBUTION,
                "unknown contribution hide: ${contributionKey ?: ""}",
            )
            return ContractResult.rejected(ContractCodes.UNKNOWN_CONTRIBUTION)
        }
        return persist(layout.withHidden(contributionKey, hidden), "setHidden")
    }

    @Synchronized
    fun setOrder(contributionKeys: List<String?>?): ContractResult {
        if (contributionKeys == null) {
            diagnostics.report(ContractCodes.INVALID_LAYOUT, "setOrder requires keys")
            return ContractResult.rejected(ContractCodes.INVALID_LAYOUT)
        }
        val knownKeys = ArrayList<String>(contributionKeys.size)
        for (key in contributionKeys) {
            if (!LayoutSnapshot.validKey(key)) {
                diagnostics.report(ContractCodes.INVALID_LAYOUT, "setOrder rejected invalid key")
                return ContractResult.rejected(ContractCodes.INVALID_LAYOUT)
            }
            if (!entries.containsKey(key)) {
                diagnostics.report(
                    ContractCodes.UNKNOWN_CONTRIBUTION,
                    "setOrder ignores unknown key: $key",
                )
                continue
            }
            knownKeys.add(key!!)
        }
        val next =
            try {
                layout.mergedOrder(knownKeys)
            } catch (error: IllegalArgumentException) {
                diagnostics.report(
                    ContractCodes.INVALID_LAYOUT,
                    "setOrder rejected: ${error.message}",
                )
                return ContractResult.rejected(ContractCodes.INVALID_LAYOUT)
            }
        return persist(next, "setOrder")
    }

    private fun persist(next: LayoutSnapshot, operation: String): ContractResult {
        try {
            layoutStore.save(next)
        } catch (error: RuntimeException) {
            diagnostics.report(
                ContractCodes.LAYOUT_SAVE_FAILED,
                "layout save failed during $operation: ${error.javaClass.simpleName}",
            )
            return ContractResult.rejected(ContractCodes.LAYOUT_SAVE_FAILED)
        }
        layout = next
        return ContractResult.ok()
    }

    private fun sortedEntries(): List<Entry> {
        val sorted = ArrayList(entries.values)
        val positions = HashMap<String, Int>()
        for (position in layout.order().indices) positions[layout.order()[position]] = position
        sorted.sortWith { left, right ->
            val leftKey = left.contribution.globalKey()
            val rightKey = right.contribution.globalKey()
            val leftPosition = positions[leftKey]
            val rightPosition = positions[rightKey]
            if (leftPosition != null && rightPosition != null)
                Integer.compare(leftPosition, rightPosition)
            else if (leftPosition != null) -1
            else if (rightPosition != null) 1
            else {
                val bySortOrder =
                    Integer.compare(left.contribution.sortOrder(), right.contribution.sortOrder())
                if (bySortOrder != 0) bySortOrder else leftKey.compareTo(rightKey)
            }
        }
        return sorted
    }

    private fun view(entry: Entry): ContributionView {
        val key = entry.contribution.globalKey()
        val hidden = entry.contribution.hidden() || layout.isHidden(key)
        return ContributionView.snapshotOf(entry.contribution, hidden, entry.generation)
    }

    private class Entry(val contribution: Contribution, val generation: Long)
}
