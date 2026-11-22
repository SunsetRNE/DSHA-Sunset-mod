package com.deepseekharness.app.transfer

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONObject

/** 已认证文件代理提供的不含凭据的公开身份。 */
class TransferArtifact
@Throws(IOException::class)
constructor(
    id: String?,
    name: String?,
    mime: String?,
    bytes: Long,
    sha256: String?,
    version: String?,
) {
    @JvmField val id: String
    @JvmField val name: String
    @JvmField val mime: String
    @JvmField val sha256: String
    @JvmField val version: String
    @JvmField val bytes: Long

    init {
        if (id == null || !id.matches(Regex("[a-zA-Z0-9_-]{1,256}")))
            throw IOException("FILE_ARTIFACT_ID")
        if (!safeName(name)) throw IOException("FILE_NAME_INVALID")
        if (
            mime == null ||
                mime.length > 160 ||
                !mime.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"))
        )
            throw IOException("FILE_MIME_INVALID")
        if (bytes < 0 || bytes > MAX_BYTES) throw IOException("FILE_LIMIT_5GB")
        if (sha256 == null || !sha256.matches(Regex("[a-fA-F0-9]{64}")))
            throw IOException("FILE_SHA256_INVALID")
        if (
            version == null ||
                version.isEmpty() ||
                version.length > 512 ||
                version.chars().anyMatch { it < 32 || it == 127 }
        )
            throw IOException("FILE_VERSION_INVALID")
        this.id = id
        this.name = name!!
        this.mime = mime.lowercase(Locale.ROOT)
        this.bytes = bytes
        this.sha256 = sha256.lowercase(Locale.ROOT)
        this.version = version
    }

    @Throws(Exception::class)
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("mime", mime)
            .put("bytes", bytes)
            .put("sha256", sha256)
            .put("version", version)

    @Throws(IOException::class)
    fun requireSame(other: TransferArtifact?) {
        if (
            other == null ||
                id != other.id ||
                bytes != other.bytes ||
                sha256 != other.sha256 ||
                version != other.version
        )
            throw IOException("FILE_SOURCE_CHANGED")
    }

    companion object {
        const val MAX_BYTES: Long = 5_000_000_000L

        @JvmStatic
        fun safeName(value: String?): Boolean =
            value != null &&
                // Java String.isBlank 使用 Character.isWhitespace；Kotlin isBlank 另接受 NBSP。
                !value.codePoints().allMatch { Character.isWhitespace(it) } &&
                value.length <= 240 &&
                value.toByteArray(StandardCharsets.UTF_8).size <= 240 &&
                value != "." &&
                value != ".." &&
                value.chars().noneMatch {
                    it < 32 || it == 127 || it == '/'.code || it == '\\'.code
                }

        @JvmStatic
        @Throws(Exception::class)
        fun fromJson(value: JSONObject?): TransferArtifact {
            if (value == null) throw IOException("FILE_ARTIFACT_MISSING")
            val expected = java.util.Set.of("id", "name", "mime", "bytes", "sha256", "version")
            val keys = value.keys()
            while (keys.hasNext()) {
                if (!expected.contains(keys.next())) throw IOException("FILE_ARTIFACT_FIELDS")
            }
            for (key in expected) {
                val field = value.get(key)
                if (key == "bytes") {
                    if (field !is Int && field !is Long) throw IOException("FILE_SIZE_INVALID")
                } else if (field !is String) throw IOException("FILE_ARTIFACT_FIELDS")
            }
            return TransferArtifact(
                value.getString("id"),
                value.getString("name"),
                value.getString("mime"),
                value.getLong("bytes"),
                value.getString("sha256"),
                value.getString("version"),
            )
        }
    }
}
