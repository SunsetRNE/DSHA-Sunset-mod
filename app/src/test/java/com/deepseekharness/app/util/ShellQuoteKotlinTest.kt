package com.deepseekharness.app.util

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ShellQuote 迁移为 Kotlin 后的边界回归。
 *
 * 期望值取自迁移前 Java 实现的真实输出（含 UTF-8 字节级 golden），断言的是 Java → Kotlin 字节行为等价，不是按新实现自己证明自己。
 */
class ShellQuoteKotlinTest {

    @Test
    fun nullAndEmptyAreEmptyQuotedArg() {
        assertEquals("''", ShellQuote.arg(null))
        assertEquals("''", ShellQuote.arg(""))
    }

    @Test
    fun singleQuoteIsEscapedPosixStyle() {
        assertEquals("'a'\\''b'", ShellQuote.arg("a'b"))
        assertEquals("''\\'''", ShellQuote.arg("'"))
        assertEquals("'two '\\'''\\'' quotes'", ShellQuote.arg("two '' quotes"))
    }

    @Test
    fun newlineStaysInsideSingleQuotes() {
        assertEquals("'line\nnext'", ShellQuote.arg("line\nnext"))
    }

    @Test
    fun nonAsciiIsLiteralUtf8() {
        assertEquals("'中文 é'", ShellQuote.arg("中文 é"))
        assertArrayEquals(
            byteArrayOf(
                0x27,
                0xe4.toByte(),
                0xb8.toByte(),
                0xad.toByte(),
                0xe6.toByte(),
                0x96.toByte(),
                0x87.toByte(),
                0x20,
                0xc3.toByte(),
                0xa9.toByte(),
                0x27,
            ),
            ShellQuote.arg("中文 é").toByteArray(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun shellMetacharactersAreLiteral() {
        assertEquals(
            "'a;b\$(rm -rf /)`c`d\${x}e'",
            ShellQuote.arg("a;b\$(rm -rf /)`c`d\${x}e"),
        )
        assertEquals(
            "'\$(touch x);`id`\\end'",
            ShellQuote.arg("\$(touch x);`id`\\end"),
        )
    }

    @Test
    fun migrationGoldenOutputsMatchByteForByte() {
        // 每个 golden 同时给出迁移前 Java 实现输出的 UTF-8 十六进制，防止平台编码差异。
        val cases =
            listOf(
                Golden("", "''", "2727"),
                Golden("a'b", "'a'\\''b'", "2761275c27276227"),
                Golden("'", "''\\'''", "27275c272727"),
                Golden(
                    "two '' quotes",
                    "'two '\\'''\\'' quotes'",
                    "2774776f20275c2727275c27272071756f74657327",
                ),
                Golden("line\nnext", "'line\nnext'", "276c696e650a6e65787427"),
                Golden("中文 é", "'中文 é'", "27e4b8ade6968720c3a927"),
                Golden(
                    "a;b\$(rm -rf /)`c`d\${x}e",
                    "'a;b\$(rm -rf /)`c`d\${x}e'",
                    "27613b622428726d202d7266202f2960636064247b787d6527",
                ),
                Golden(
                    "@deepseek-ai/dsh-foo@1.0.0 ' OR 1=1 --",
                    "'@deepseek-ai/dsh-foo@1.0.0 '\\'' OR 1=1 --'",
                    "2740646565707365656b2d61692f6473682d666f6f40312e302e3020275c2727204f5220313d31202d2d27",
                ),
            )
        for (case in cases) {
            val actual = ShellQuote.arg(case.value)
            assertEquals("quoted value: " + case.value, case.expected, actual)
            assertEquals(
                "utf8 bytes: " + case.value,
                case.utf8Hex,
                actual.toByteArray(StandardCharsets.UTF_8).joinToString("") {
                    "%02x".format(it)
                },
            )
        }
    }

    @Test
    fun independentPosixScannerRoundTripsAllBoundaryValues() {
        val values =
            listOf(
                "",
                "'",
                "' OR 1=1 --",
                "two '' quotes",
                "line\nnext",
                "中文 é",
                "\$(touch x);`id`\\end",
                "@deepseek-ai/dsh-foo@1.0.0 ' OR 1=1 --",
            )
        for (value in values) {
            assertEquals(value, parseLiteralWord(ShellQuote.arg(value)))
        }
    }

    private data class Golden(val value: String, val expected: String, val utf8Hex: String)

    /** 独立 POSIX literal-word 扫描器；拒绝未引用空白、展开或命令语法。 */
    private fun parseLiteralWord(word: String): String {
        val value = StringBuilder()
        var quote = '\u0000'
        var i = 0
        while (i < word.length) {
            val c = word[i]
            if (quote == '\'') {
                if (c == '\'') quote = '\u0000' else value.append(c)
                i++
                continue
            }
            if (c == '\\') {
                i++
                if (i == word.length) throw AssertionError("unfinished escape")
                value.append(word[i])
                i++
                continue
            }
            if (c == '\'' && quote == '\u0000') {
                quote = c
                i++
                continue
            }
            if (c == '"') {
                quote = if (quote == '"') '\u0000' else '"'
                i++
                continue
            }
            if (
                c == '$' ||
                    c == '`' ||
                    (quote == '\u0000' && (c.isWhitespace() || ";&|<>()".indexOf(c) >= 0))
            ) {
                throw AssertionError("non-literal shell syntax")
            }
            value.append(c)
            i++
        }
        if (quote != '\u0000') throw AssertionError("unfinished quote")
        return value.toString()
    }
}
