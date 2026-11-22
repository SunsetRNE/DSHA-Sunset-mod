package com.deepseekharness.app.contract

import java.util.Objects
import java.util.regex.Pattern

/**
 * 模块贡献声明。CARD 是界面卡片，ROUTE 是页面路由；全局键固定为 owner#id，用于跨重启的布局记录。
 *
 * [title] / [titleEn] 是宿主呈现用的中英文字面值；不做自动翻译，也不含协议/用户原文。
 */
class Contribution(
    kind: Kind?,
    owner: ModuleId?,
    id: String?,
    title: String?,
    titleEn: String?,
    sortOrder: Int,
    hidden: Boolean,
    routeTarget: String?,
) {
    enum class Kind {
        CARD,
        ROUTE,
    }

    private val kind: Kind
    private val owner: ModuleId
    private val id: String
    private val title: String
    private val titleEn: String
    private val sortOrder: Int
    private val hidden: Boolean
    private val routeTarget: String?

    init {
        val checkedKind: Kind = Objects.requireNonNull(kind, "kind")!!
        val checkedOwner: ModuleId = Objects.requireNonNull(owner, "owner")!!
        if (id == null || !LOCAL_ID.matcher(id).matches()) {
            throw IllegalArgumentException("CONTRIBUTION_ID_INVALID")
        }
        if (
            title == null ||
                title.codePoints().allMatch(Character::isWhitespace) ||
                title.length > 200
        ) {
            throw IllegalArgumentException("CONTRIBUTION_TITLE_INVALID")
        }
        if (
            titleEn == null ||
                titleEn.codePoints().allMatch(Character::isWhitespace) ||
                titleEn.length > 200
        ) {
            throw IllegalArgumentException("CONTRIBUTION_TITLE_EN_INVALID")
        }
        if (checkedKind == Kind.ROUTE) {
            if (routeTarget == null || !ROUTE_TARGET.matcher(routeTarget).matches()) {
                throw IllegalArgumentException("CONTRIBUTION_ROUTE_TARGET_INVALID")
            }
        } else if (routeTarget != null) {
            throw IllegalArgumentException("CONTRIBUTION_ROUTE_TARGET_UNEXPECTED")
        }
        this.kind = checkedKind
        this.owner = checkedOwner
        this.id = id
        this.title = title
        this.titleEn = titleEn
        this.sortOrder = sortOrder
        this.hidden = hidden
        this.routeTarget = routeTarget
    }

    fun kind(): Kind = kind

    fun owner(): ModuleId = owner

    fun id(): String = id

    fun title(): String = title

    fun titleEn(): String = titleEn

    fun sortOrder(): Int = sortOrder

    fun hidden(): Boolean = hidden

    fun routeTarget(): String? = routeTarget

    fun globalKey(): String = owner.value() + "#" + id

    override fun equals(other: Any?): Boolean =
        other is Contribution &&
            kind == other.kind &&
            owner == other.owner &&
            id == other.id &&
            title == other.title &&
            titleEn == other.titleEn &&
            sortOrder == other.sortOrder &&
            hidden == other.hidden &&
            routeTarget == other.routeTarget

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + kind.hashCode()
        result = 31 * result + owner.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + titleEn.hashCode()
        result = 31 * result + sortOrder
        result = 31 * result + if (hidden) 1231 else 1237
        result = 31 * result + (routeTarget?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "Contribution[kind=$kind, owner=$owner, id=$id, title=$title, titleEn=$titleEn, " +
            "sortOrder=$sortOrder, hidden=$hidden, routeTarget=$routeTarget]"

    companion object {
        private val LOCAL_ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}")
        private val ROUTE_TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

        @JvmStatic
        fun card(
            owner: ModuleId?,
            id: String?,
            title: String?,
            titleEn: String?,
            sortOrder: Int,
        ): Contribution = Contribution(Kind.CARD, owner, id, title, titleEn, sortOrder, false, null)

        @JvmStatic
        fun card(
            owner: ModuleId?,
            id: String?,
            title: String?,
            titleEn: String?,
            sortOrder: Int,
            hidden: Boolean,
        ): Contribution =
            Contribution(Kind.CARD, owner, id, title, titleEn, sortOrder, hidden, null)

        @JvmStatic
        fun route(
            owner: ModuleId?,
            id: String?,
            title: String?,
            titleEn: String?,
            sortOrder: Int,
            routeTarget: String?,
        ): Contribution =
            Contribution(Kind.ROUTE, owner, id, title, titleEn, sortOrder, false, routeTarget)

        @JvmStatic
        fun route(
            owner: ModuleId?,
            id: String?,
            title: String?,
            titleEn: String?,
            sortOrder: Int,
            routeTarget: String?,
            hidden: Boolean,
        ): Contribution =
            Contribution(Kind.ROUTE, owner, id, title, titleEn, sortOrder, hidden, routeTarget)
    }
}
