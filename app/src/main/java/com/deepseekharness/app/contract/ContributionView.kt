package com.deepseekharness.app.contract

/** 贡献的只读呈现快照；[hidden] 已叠加宿主布局记录。 */
class ContributionView(
    key: String?,
    kind: Contribution.Kind?,
    owner: ModuleId?,
    private val title: String?,
    private val titleEn: String?,
    private val sortOrder: Int,
    private val hidden: Boolean,
    private val generation: Long,
    private val routeTarget: String?,
) {
    private val key: String
    private val kind: Contribution.Kind
    private val owner: ModuleId

    init {
        if (!LayoutSnapshot.validKey(key)) {
            throw IllegalArgumentException("CONTRIBUTION_KEY_INVALID")
        }
        if (kind == null || owner == null) {
            throw IllegalArgumentException("CONTRIBUTION_VIEW_INVALID")
        }
        if (generation < 1) throw IllegalArgumentException("CONTRIBUTION_GENERATION_INVALID")
        this.key = key!!
        this.kind = kind
        this.owner = owner
    }

    fun key(): String = key

    fun kind(): Contribution.Kind = kind

    fun owner(): ModuleId = owner

    fun title(): String? = title

    fun titleEn(): String? = titleEn

    fun sortOrder(): Int = sortOrder

    fun hidden(): Boolean = hidden

    fun generation(): Long = generation

    fun routeTarget(): String? = routeTarget

    override fun equals(other: Any?): Boolean =
        other is ContributionView &&
            key == other.key &&
            kind == other.kind &&
            owner == other.owner &&
            title == other.title &&
            titleEn == other.titleEn &&
            sortOrder == other.sortOrder &&
            hidden == other.hidden &&
            generation == other.generation &&
            routeTarget == other.routeTarget

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + key.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + owner.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (titleEn?.hashCode() ?: 0)
        result = 31 * result + sortOrder
        result = 31 * result + if (hidden) 1231 else 1237
        result = 31 * result + java.lang.Long.hashCode(generation)
        result = 31 * result + (routeTarget?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ContributionView[key=$key, kind=$kind, owner=$owner, title=$title, titleEn=$titleEn, " +
            "sortOrder=$sortOrder, hidden=$hidden, generation=$generation, routeTarget=$routeTarget]"

    companion object {
        /** final 类的 protected static 保留 Java 同包调用，外包不能继承扩大入口。 */
        @JvmStatic
        protected fun of(
            contribution: Contribution?,
            hidden: Boolean,
            generation: Long,
        ): ContributionView {
            val source = contribution!!
            return ContributionView(
                source.globalKey(),
                source.kind(),
                source.owner(),
                source.title(),
                source.titleEn(),
                source.sortOrder(),
                hidden,
                generation,
                source.routeTarget(),
            )
        }

        @JvmSynthetic
        internal fun snapshotOf(
            contribution: Contribution?,
            hidden: Boolean,
            generation: Long,
        ): ContributionView = of(contribution, hidden, generation)
    }
}
