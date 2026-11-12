package com.deepseekharness.app.util

/**
 * 把任意字符串安全地变成一个 shell 参数（POSIX 单引号法）。
 *
 * 这里是一条**安全边界**：插件名、仓库地址、路径来自市场索引或用户输入，最终会拼进 `bash -c` 的命令串里跑进容器。转义写错一次，恶意条目就能在 rootfs 里执行任意命令。
 *
 * 原实现内联在 `HarnessController` 的插件段里，抽成纯逻辑类之后 JVM 单测可以直接下断言。
 *
 * Kotlin 试点迁移（ADR-0004 决定一）：整文件逐字节等价迁为 Kotlin `object`，用 `@JvmStatic` 保留 Java 调用形态；不保留同名 Java 文件。
 */
object ShellQuote {
    /**
     * 单引号包裹，内部单引号按 POSIX 惯例拆成 `'\''`。
     *
     * 单引号内 shell 不做任何解释，`$`、反引号、`;`、换行、通配符都按字面量传递。
     *
     * `null` 给出空参数 `''` 而不是抛异常：调用点多在拼命令串中间，抛异常会打断整条操作。
     */
    @JvmStatic
    fun arg(v: String?): String = if (v == null) "''" else "'" + v.replace("'", "'\\''") + "'"
}
