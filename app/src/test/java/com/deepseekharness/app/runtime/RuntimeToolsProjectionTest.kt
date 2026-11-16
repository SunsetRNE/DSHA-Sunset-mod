package com.deepseekharness.app.runtime

import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.system.StructTimespec
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** 模拟内核元数据、运行真实 cacheMiss 与投影；不运行或跳过任何工具安装。 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = RuntimeToolsRegistryAndroidTest.OwnedApplication::class,
    shadows =
        [RuntimeToolsProjectionTest.Metadata::class, RuntimeToolsProjectionTest.ApkInput::class],
)
class RuntimeToolsProjectionTest {
    @Implements(value = RuntimeApkAssets::class, isInAndroidSdk = false)
    internal class ApkInput {
        companion object {
            var snapshot = RuntimeApkAssets.Snapshot(emptyMap(), "preloads", "proof-sha")

            @JvmStatic
            @Implementation
            fun get(context: Context): RuntimeApkAssets.Snapshot = snapshot
        }
    }

    @Implements(Os::class)
    class Metadata {
        companion object {
            val nodes = HashMap<String, StructStat>()

            @JvmStatic
            @Implementation
            fun lstat(path: String): StructStat =
                nodes[path] ?: throw ErrnoException("lstat", OsConstants.EIO)
        }
    }

    private lateinit var root: File
    private lateinit var tool: File
    private lateinit var apk: File
    private lateinit var context: Context
    private val saved = HashMap<String, Any?>()

    private fun field(name: String) =
        RuntimeTools::class.java.getDeclaredField(name).also { it.isAccessible = true }

    private fun set(name: String, value: Any?) {
        field(name).set(null, value)
    }

    private fun node(inode: Long, mode: Int, nanos: Long = 1): StructStat =
        StructStat(
            1,
            inode,
            mode,
            1,
            1000,
            1000,
            0,
            10,
            StructTimespec(1, 0),
            StructTimespec(2, nanos),
            StructTimespec(3, nanos),
            4096,
            1,
        )

    @Before
    fun setup() {
        root =
            File(RuntimeEnvironment.getApplication().filesDir, "projection-root").apply { mkdirs() }
        tool = File(root, "managed.js").apply { writeText("tool") }
        apk = File(root.parentFile, "projection.apk").apply { writeText("apk") }
        context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getPackageCodePath() = apk.path
            }
        Metadata.nodes.clear()
        Metadata.nodes[root.absolutePath] = node(2, OsConstants.S_IFDIR or 493)
        Metadata.nodes[tool.absolutePath] = node(3, OsConstants.S_IFREG or 420)
        for (name in
            listOf(
                "preparedRoot",
                "preparedApk",
                "preparedStamp",
                "preparedWithNpm",
                "preparedInvalidation",
                "preparedObservationRevision",
            )) {
            saved[name] = field(name).get(null)
        }
        @Suppress("UNCHECKED_CAST") val files = field("preparedFiles").get(null) as MutableSet<File>
        saved["files"] = files.toList()
        files.clear()
        files.add(tool)
        set("preparedRoot", root.canonicalPath)
        set("preparedApk", "${apk.path}:${apk.length()}:${apk.lastModified()}")
        set("preparedStamp", RuntimeAssetStamp.read(root, listOf(tool)))
        set("preparedWithNpm", true)
    }

    @After
    fun restore() {
        for ((name, value) in saved) if (name != "files") set(name, value)
        @Suppress("UNCHECKED_CAST") val files = field("preparedFiles").get(null) as MutableSet<File>
        files.clear()
        @Suppress("UNCHECKED_CAST") files.addAll(saved["files"] as List<File>)
        Metadata.nodes.clear()
    }

    @Test
    fun productionInputsBindOnlyTheCurrentSettingsScopeAndFreshFileAndApkFacts() {
        for ((index, name) in
            listOf(".runtime-descriptor.json", ".offline-extracted", ".offline-identity")
                .withIndex()) {
            val file = File(root.parentFile, name).apply { writeText("fixed input") }
            Metadata.nodes[file.absolutePath] = node(10L + index, OsConstants.S_IFREG or 420)
        }
        val inputs = RuntimePreparationInputs(context, root)
        val ports = RuntimeHostPorts.fromOwner(context.applicationContext)
        assertNull(inputs.observe())
        lateinit var first: RuntimeDescriptorPreparation.Identity
        ports.open().use {
            first = inputs.observe()!!
            assertSame(ports.settings(), first.settings)
            assertTrue(first.matches(inputs.observe()!!))
            ApkInput.snapshot = RuntimeApkAssets.Snapshot(emptyMap(), "new-preloads", "new-proof")
            assertFalse(first.matches(inputs.observe()!!))
            Metadata.nodes[File(root.parentFile, ".offline-identity").absolutePath] =
                node(90, OsConstants.S_IFREG or 420)
            assertFalse(first.matches(inputs.observe()!!))
        }
        assertNull(inputs.observe())
        ports.open().use { assertNotSame(first.settings, inputs.observe()!!.settings) }
    }

    @Test
    fun stableReadOnlyProjectionDoesNotChangeTheExistingPreparedState() {
        val stamp = field("preparedStamp").get(null)
        val first = RuntimeTools.preparationIdentity(context, root)
        assertNotNull(first)
        assertEquals(first, RuntimeTools.preparationIdentity(context, root))
        assertEquals(stamp, field("preparedStamp").get(null))
        assertTrue(field("preparedWithNpm").getBoolean(null))
    }

    @Test
    fun rootApkNpmAndMissingStampUseTheOriginalCacheMissConditions() {
        assertNull(RuntimeTools.preparationIdentity(context, File(root, "another")))
        apk.appendText("new")
        assertNull(RuntimeTools.preparationIdentity(context, root))
        set("preparedApk", "${apk.path}:${apk.length()}:${apk.lastModified()}")
        set("preparedWithNpm", false)
        assertNull(RuntimeTools.preparationIdentity(context, root))
        set("preparedWithNpm", true)
        set("preparedStamp", null)
        assertNull(RuntimeTools.preparationIdentity(context, root))
    }

    @Test
    fun replacementNanosecondsModeAndUnavailableMetadataAllRejectProjection() {
        val original = Metadata.nodes[tool.absolutePath]!!
        for (replacement in
            listOf(
                node(7, OsConstants.S_IFREG or 420),
                node(3, OsConstants.S_IFREG or 420, 2),
                node(3, OsConstants.S_IFREG or 493),
                node(3, OsConstants.S_IFLNK or 420),
            )) {
            Metadata.nodes[tool.absolutePath] = replacement
            assertNull(RuntimeTools.preparationIdentity(context, root))
        }
        Metadata.nodes.remove(tool.absolutePath)
        assertNull(RuntimeTools.preparationIdentity(context, root))
        Metadata.nodes[tool.absolutePath] = original
        assertNotNull(RuntimeTools.preparationIdentity(context, root))
    }

    @Test
    fun maintenanceOwnerCannotGetAReusableProjection() {
        val field =
            com.deepseekharness.app.util.MaintenanceGate::class.java.getDeclaredField("owner")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val owner =
            field.get(com.deepseekharness.app.util.MaintenanceGate.shared()) as ThreadLocal<Boolean>
        val previous = owner.get()
        owner.set(true)
        try {
            assertNull(RuntimeTools.preparationIdentity(context, root))
        } finally {
            if (previous == null) owner.remove() else owner.set(previous)
        }
    }

    @Test
    fun failedPreparationAdvancesOnlyItsObservationRevision() {
        val revision = field("preparedObservationRevision").getLong(null)
        val stamp = field("preparedStamp").get(null)
        assertThrows(Exception::class.java) { RuntimeTools.stage(context, root) }
        assertEquals(revision + 1, field("preparedObservationRevision").getLong(null))
        assertEquals(stamp, field("preparedStamp").get(null))
    }

    @Test
    fun explicitRepairAdvancesObservationWithoutChangingTheOriginalInvalidationRule() {
        val revision = field("preparedObservationRevision").getLong(null)
        RuntimeTools.invalidate()
        assertEquals(revision + 1, field("preparedObservationRevision").getLong(null))
        assertNull(field("preparedStamp").get(null))
        assertNull(RuntimeTools.preparationIdentity(context, root))
    }
}
