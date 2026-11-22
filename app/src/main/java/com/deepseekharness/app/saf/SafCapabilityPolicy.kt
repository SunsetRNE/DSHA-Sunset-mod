package com.deepseekharness.app.saf

import com.deepseekharness.app.util.BridgePathPolicy
import java.util.regex.Pattern

/** SAF URI 与容器侧路径的边界判据；Provider 内容仍是信任边界。 */
class SafCapabilityPolicy private constructor() {
    enum class TreeUri {
        OK,
        MISSING,
        TOO_LONG,
        NOT_CONTENT,
        NOT_TREE,
        BAD_AUTHORITY,
        OWN_PROVIDER,
    }

    companion object {
        private val AUTHORITY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
        const val LOCAL_NOT_ABSOLUTE = "LOCAL_NOT_ABSOLUTE"
        const val LOCAL_CREDENTIAL = "LOCAL_CREDENTIAL"
        const val LOCAL_OUTSIDE_ROOTFS = "LOCAL_OUTSIDE_ROOTFS"

        @JvmStatic
        fun treeUriProblem(uri: String?, ownAuthority: String?): TreeUri {
            val lengthProblem = SafCapabilityLimits.uriProblem(uri)
            if (lengthProblem != null) {
                return if (SafCapabilityLimits.URI_MISSING == lengthProblem) TreeUri.MISSING
                else TreeUri.TOO_LONG
            }
            val value = uri!!.trim { it <= ' ' }
            if (!value.startsWith("content://")) return TreeUri.NOT_CONTENT
            val authorityStart = "content://".length
            val pathStart = value.indexOf('/', authorityStart)
            if (pathStart <= authorityStart) return TreeUri.BAD_AUTHORITY
            val authority = value.substring(authorityStart, pathStart)
            if (!AUTHORITY.matcher(authority).matches()) return TreeUri.BAD_AUTHORITY
            if (ownAuthority != null && ownAuthority == authority) return TreeUri.OWN_PROVIDER
            val path = value.substring(pathStart)
            if (!path.startsWith("/tree/") || path.length <= "/tree/".length)
                return TreeUri.NOT_TREE
            return TreeUri.OK
        }

        @JvmStatic
        fun localSourceProblem(rawGuestPath: String?): String? {
            if (!BridgePathPolicy.isAbsolute(rawGuestPath)) return LOCAL_NOT_ABSOLUTE
            if (BridgePathPolicy.denied(rawGuestPath)) return LOCAL_CREDENTIAL
            return null
        }

        @JvmStatic
        fun localCanonicalProblem(canonical: String?, rootfsCanonical: String?): String? {
            if (canonical == null || canonical.isEmpty()) return LOCAL_OUTSIDE_ROOTFS
            // 根不可证明时失败关闭；不把 deniedGuestView 的根外判据当作授权。
            if (
                rootfsCanonical == null ||
                    rootfsCanonical.codePoints().allMatch(Character::isWhitespace)
            )
                return LOCAL_OUTSIDE_ROOTFS
            if (BridgePathPolicy.deniedGuestView(canonical, rootfsCanonical)) {
                val root = BridgePathPolicy.normalize(rootfsCanonical)
                val path = BridgePathPolicy.normalize(canonical)
                return if (BridgePathPolicy.startsWithPath(path, root)) LOCAL_CREDENTIAL
                else LOCAL_OUTSIDE_ROOTFS
            }
            return null
        }

        @JvmStatic
        fun credentialPathName(relativePath: String?): Boolean {
            if (relativePath == null || relativePath.codePoints().allMatch(Character::isWhitespace))
                return true
            var value = relativePath.replace('\\', '/')
            while (value.startsWith("/")) value = value.substring(1)
            return BridgePathPolicy.denied("/$value")
        }
    }
}
