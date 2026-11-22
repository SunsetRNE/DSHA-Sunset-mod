package com.deepseekharness.app.plugins

import com.deepseekharness.app.util.PluginOutput
import com.deepseekharness.app.util.SensitiveData
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.util.HashSet
import java.util.Objects
import java.util.Set

private val DETAIL_TOKEN = Regex("[A-Za-z0-9_.:-]{0,128}")

/** Detail 归一化不依赖 companion 初始化顺序：EMPTY 在类初始化期间就会构造。 */
private fun detailToken(value: String?): String =
    if (value != null && DETAIL_TOKEN.matches(value)) value else "unknown"

/** 只解释一次真实命令结果；未知/超时/截断不因输出里有成功 JSON 而变成已完成。 */
class PluginOperationResult private constructor() {
    enum class Kind {
        FAILED,
        TIMEOUT,
        CANCELLED,
        INVALIDATED,
        UNCONFIRMED,
    }

    @JvmRecord
    data class Detail(
        val errorCode: String?,
        val stage: String?,
        val application: String?,
        val restartReason: String?,
    ) {
        init {
            @Suppress("VAL_REASSIGNMENT")
            this.errorCode = detailToken(errorCode)
            @Suppress("VAL_REASSIGNMENT")
            this.stage = detailToken(stage)
            @Suppress("VAL_REASSIGNMENT")
            this.application = detailToken(application)
            @Suppress("VAL_REASSIGNMENT")
            this.restartReason = detailToken(restartReason)
        }

        final override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Detail &&
                    errorCode == other.errorCode &&
                    stage == other.stage &&
                    application == other.application &&
                    restartReason == other.restartReason)

        final override fun hashCode(): Int =
            Objects.hash(errorCode, stage, application, restartReason)

        final override fun toString(): String =
            "Detail[errorCode=$errorCode, stage=$stage, application=$application, " +
                "restartReason=$restartReason]"

        companion object {
            @JvmField val EMPTY: Detail = Detail("", "", "", "")
        }
    }

    class Failure
    internal constructor(
        @JvmField val kind: Kind,
        @JvmField val detail: Detail,
        message: String?,
        @JvmField val exitUnknown: Boolean,
    ) : IOException(SensitiveData.redact(message)) {
        constructor(
            kind: Kind,
            code: String,
            message: String?,
        ) : this(kind, Detail(code, "", "", ""), message, false)

        constructor(
            kind: Kind,
            detail: Detail,
            message: String?,
        ) : this(kind, detail, message, false)
    }

    @JvmRecord
    data class Receipt(
        val status: String,
        val message: String,
        val detail: Detail,
        val cleanupVerified: Boolean,
        val executionUnknown: Boolean,
        val exitUnknown: Boolean,
    ) {
        final override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Receipt &&
                    status == other.status &&
                    message == other.message &&
                    detail == other.detail &&
                    cleanupVerified == other.cleanupVerified &&
                    executionUnknown == other.executionUnknown &&
                    exitUnknown == other.exitUnknown)

        final override fun hashCode(): Int =
            Objects.hash(status, message, detail, cleanupVerified, executionUnknown, exitUnknown)

        final override fun toString(): String =
            "Receipt[status=$status, message=$message, detail=$detail, " +
                "cleanupVerified=$cleanupVerified, executionUnknown=$executionUnknown, " +
                "exitUnknown=$exitUnknown]"
    }

    companion object {
        /** 返回回执字段只作投影；操作类型和目标还须由原有精确读回校验。 */
        @JvmStatic
        @Throws(Failure::class)
        fun parse(output: String): Receipt {
            val json = PluginOutput.resultJson(output)
            if (json.isEmpty() || json.length > 8 * 1024 * 1024)
                throw unknown("PLUGIN_RESULT_MISSING", "没有收到可核验的插件管理结果")
            var status: String? = null
            var message: String? = null
            var code = ""
            var stage = ""
            var application = ""
            var restart = ""
            var cleanup = false
            var executionUnknown = false
            var exitUnknown: Boolean? = null
            val fields = HashSet<String>()
            try {
                JsonReader(StringReader(json)).use { reader ->
                    reader.setStrictness(Strictness.STRICT)
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (!fields.add(name)) throw IOException("duplicate field")
                        when (name) {
                            "status",
                            "message",
                            "errorCode",
                            "stage",
                            "application",
                            "restartReason" -> {
                                if (reader.peek() != JsonToken.STRING)
                                    throw IOException("field type")
                                val value = reader.nextString()
                                when (name) {
                                    "status" -> status = value
                                    "message" -> message = value
                                    "errorCode" -> code = value
                                    "stage" -> stage = value
                                    "application" -> application = value
                                    "restartReason" -> restart = value
                                }
                            }
                            "cleanupVerified",
                            "executionUnknown",
                            "exitUnknown" -> {
                                if (reader.peek() != JsonToken.BOOLEAN)
                                    throw IOException("field type")
                                val value = reader.nextBoolean()
                                if (name == "cleanupVerified") cleanup = value
                                else if (name == "executionUnknown") executionUnknown = value
                                else exitUnknown = value
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (
                        reader.peek() != JsonToken.END_DOCUMENT || status == null || message == null
                    )
                        throw IOException("incomplete result")
                }
            } catch (malformed: IOException) {
                throw unknown("PLUGIN_RESULT_INVALID", "插件管理回执不完整或格式无法核验")
            } catch (malformed: RuntimeException) {
                throw unknown("PLUGIN_RESULT_INVALID", "插件管理回执不完整或格式无法核验")
            }
            val resolvedStatus =
                status ?: throw unknown("PLUGIN_RESULT_INVALID", "插件管理回执不完整或格式无法核验")
            val resolvedMessage =
                message ?: throw unknown("PLUGIN_RESULT_INVALID", "插件管理回执不完整或格式无法核验")
            return Receipt(
                resolvedStatus,
                SensitiveData.redact(resolvedMessage),
                Detail(code, stage, application, restart),
                cleanup,
                executionUnknown,
                exitUnknown
                    ?: (executionUnknown ||
                        resolvedStatus == "unknown" ||
                        code == "PLUGIN_EXECUTION_UNKNOWN" ||
                        resolvedStatus !in setOf("ok", "error", "cancelled", "pending", "partial")),
            )
        }

        @JvmStatic
        @Throws(Failure::class)
        fun requireCompleted(
            exitCode: Int,
            timedOut: Boolean,
            truncated: Boolean,
            output: String,
        ): Receipt {
            if (timedOut) throw unknownExit("PLUGIN_COMMAND_TIMEOUT", "插件命令等待超时，退出和提交结果尚未确认")
            if (exitCode < 0) throw unknownExit("PLUGIN_COMMAND_EXIT_UNKNOWN", "插件命令退出尚未确认")
            if (truncated)
                throw Failure(
                    Kind.UNCONFIRMED,
                    Detail("PLUGIN_OUTPUT_TRUNCATED", "", "", ""),
                    "插件命令输出不完整，结果尚未确认",
                    exitCode != 0,
                )
            val receipt: Receipt =
                try {
                    parse(output)
                } catch (malformed: Failure) {
                    if (exitCode != 0)
                        throw Failure(Kind.UNCONFIRMED, malformed.detail, malformed.message, true)
                    throw malformed
                }
            if (receipt.exitUnknown)
                throw Failure(Kind.UNCONFIRMED, receipt.detail, receipt.message, true)
            if (
                receipt.executionUnknown || receipt.status in setOf("pending", "partial", "unknown")
            )
                throw Failure(Kind.UNCONFIRMED, receipt.detail, receipt.message)
            if (receipt.status == "cancelled")
                throw Failure(
                    if (receipt.cleanupVerified) Kind.CANCELLED else Kind.UNCONFIRMED,
                    receipt.detail,
                    receipt.message,
                )
            if (receipt.status == "error") {
                if (receipt.detail.errorCode == "PLUGIN_COMMAND_TIMEOUT_CONFIRMED")
                    throw Failure(
                        if (receipt.cleanupVerified) Kind.TIMEOUT else Kind.UNCONFIRMED,
                        receipt.detail,
                        receipt.message,
                    )
                if (receipt.detail.errorCode == "PLUGIN_EXECUTION_UNKNOWN")
                    throw Failure(Kind.UNCONFIRMED, receipt.detail, receipt.message)
                if (
                    receipt.detail.errorCode in
                        setOf(
                            "PLUGIN_TARGET_VERSION_CHANGED",
                            "PLUGIN_SOURCE_CHANGED",
                            "PLUGIN_CANDIDATE_CHANGED",
                            "PLUGIN_COMPATIBILITY_CHANGED",
                        ) ||
                        receipt.detail.errorCode == "" &&
                            receipt.message == "PLUGIN_TARGET_VERSION_CHANGED"
                )
                    throw Failure(Kind.INVALIDATED, receipt.detail, receipt.message)
                throw Failure(Kind.FAILED, receipt.detail, receipt.message)
            }
            if (receipt.status != "ok" || exitCode != 0)
                throw unknown("PLUGIN_COMMAND_EXIT", "插件命令没有正常完成，结果尚未确认")
            return receipt
        }

        @JvmStatic
        fun findFailure(error: Throwable?): Failure? {
            var first: Failure? = null
            var depth = 0
            var current = error
            while (current != null && depth < 8) {
                if (current is Failure) {
                    if (current.exitUnknown) return current
                    if (first == null) first = current
                }
                current = current.cause
                depth++
            }
            return first
        }

        @JvmStatic
        fun unknownExit(code: String, message: String): Failure =
            Failure(Kind.UNCONFIRMED, Detail(code, "", "", ""), message, true)

        @JvmStatic
        fun unknown(code: String, message: String): Failure =
            Failure(Kind.UNCONFIRMED, code, message)
    }
}
