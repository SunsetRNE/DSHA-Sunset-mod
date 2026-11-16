package com.deepseekharness.app.saf

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.UriPermission
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** 首迁失败的确定性切换点，以及慢 Provider 期间的真实并发；不靠 sleep 或线程调度碰运气。 */
@RunWith(SafGrantsTest.NoApkRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class SafMigrationRaceTest {
    private lateinit var context: Context
    private val tree = Uri.parse("content://test.dsha.migration/tree/ROOT")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("saf_grants", 0).edit().clear().commit()
        context.getSharedPreferences(SafLedgerStore.NAMESPACE, 0).edit().clear().commit()
        context.contentResolver.takePersistableUriPermission(tree, 3)
        context
            .getSharedPreferences("saf_grants", 0)
            .edit()
            .putStringSet("uris", setOf(tree.toString()))
            .putBoolean("writable:$tree", true)
            .putString("acquired:$tree", "w")
            .putString("app:$tree", "测试应用")
            .putString("label:$tree", "旧目录")
            .commit()
    }

    private fun migrate(target: Context = context) {
        assertEquals(1, SafGrants.listLight(target).size)
    }

    @Test
    fun failedMigrationLookupCannotReadLegacyAfterAnotherOperationMigrates() {
        val failing = FailureContext(context)
        failing.afterFailure = {
            migrate(failing)
            SafGrants.setWritable(failing, tree, false)
        }
        val found = SafGrants.lookup(failing, SafGrants.id(tree))
        assertNull(found.failure)
        assertFalse("不能把新账本的只读偏好切回旧路径的可写偏好", found.grant!!.writable)
    }

    @Test
    fun failedMigrationListCannotReadLegacyAfterAnotherOperationMigrates() {
        val failing = FailureContext(context)
        failing.afterFailure = {
            migrate(failing)
            SafGrants.setWritable(failing, tree, false)
        }
        assertFalse(SafGrants.listLight(failing).single().writable)
    }

    @Test
    fun failedMigrationAddCannotReportAnUnregisteredGrantAsSuccess() {
        val failing = FailureContext(context)
        failing.afterFailure = { migrate(failing) }
        val other = Uri.parse("content://test.dsha.migration/tree/OTHER")
        val added = SafGrants.add(failing, other, true)
        assertTrue(added.failure, added.added)
        assertNotNull(
            "成功新增必须与后续桥 lookup 使用同一账本",
            SafGrants.lookup(context, SafGrants.id(other)).grant,
        )
        assertEquals(
            setOf(tree.toString()),
            context.getSharedPreferences("saf_grants", 0).getStringSet("uris", emptySet()),
        )
    }

    @Test
    fun failedMigrationRemoveCannotLeaveAnActiveLedgerRegistration() {
        val failing = FailureContext(context)
        failing.afterFailure = { migrate(failing) }
        SafGrants.forget(failing, tree)
        assertEquals("NO_GRANT", SafGrants.lookup(context, SafGrants.id(tree)).failure)
        val held = context.contentResolver.persistedUriPermissions.single { it.uri == tree }
        assertTrue("其它功能原有读位保留", held.isReadPermission)
        assertFalse("仅释放本功能新增写位", held.isWritePermission)
    }

    @Test
    fun failedMigrationSetWritableCannotWriteTheRetiredNamespace() {
        val failing = FailureContext(context)
        failing.afterFailure = { migrate(failing) }
        SafGrants.setWritable(failing, tree, false)
        assertFalse(SafGrants.lookup(context, SafGrants.id(tree)).grant!!.writable)
        assertTrue(
            "首迁成功后旧键保持只读",
            context.getSharedPreferences("saf_grants", 0).getBoolean("writable:$tree", false),
        )
    }

    @Test
    fun bridgeWriteLookupRejectsTheNewLedgerReadOnlyPreferenceAfterFailure() {
        val failing = FailureContext(context)
        failing.afterFailure = {
            migrate(failing)
            SafGrants.setWritable(failing, tree, false)
        }
        val service = com.deepseekharness.app.HttpShellService(failing)
        val requestType = Class.forName("com.deepseekharness.app.HttpShellService\$BridgeRequest")
        val constructor = requestType.declaredConstructors.single { it.parameterCount == 8 }
        constructor.isAccessible = true
        val request =
            constructor.newInstance(
                "/app/saf/push?tree=${SafGrants.id(tree)}&path=x&src=/root/x",
                "",
                "",
                false,
                null,
                null,
                null,
                null,
            )
        val method =
            service.javaClass.getDeclaredMethod(
                "safTree",
                requestType,
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true
        val wrapped =
            assertThrows(InvocationTargetException::class.java) {
                method.invoke(service, request, true)
            }
        assertEquals("READ_ONLY", (wrapped.cause as SafTree.Failure).code)
    }

    @Test
    fun workbenchCanResolveTheGrantReportedAddedDuringMigrationFailure() {
        val failing = FailureContext(context)
        failing.afterFailure = { migrate(failing) }
        val other = Uri.parse("content://test.dsha.migration/tree/OTHER")
        assertTrue(SafGrants.add(failing, other, true).added)
        val workbench = SafWorkbench(failing) { java.io.File("/unused-rootfs") }
        val ticket = workbench.prepare(SafGrants.id(other), "", true)
        assertEquals(SafGrants.id(other), ticket.treeId)
        workbench.cancel(ticket)
    }

    @Test
    fun failedCommitThatChangesMemoryDoesNotInitializeTheLedger() {
        val failing = FailureContext(context, applyBeforeFailure = true)
        val store = SafLedgerStore(failing.ledger)
        assertFalse(store.save(SafTestSupport.single(tree.toString(), 3)))
        assertTrue(
            "模拟 Android commit 失败前已更新内存",
            failing.ledger.contains(SafLedgerCodec.PREF_SCHEMA),
        )
        assertFalse("schema 内存值不能冒充已提交的迁移", SafLedgerStore(failing.ledger).initialized())
        assertEquals(0, SafLedgerStore(failing.ledger).load().size())
        assertTrue("下一次真实提交可以恢复", store.save(SafTestSupport.single(tree.toString(), 1)))
        assertTrue(store.initialized())
        assertFalse(store.load().findByUri(tree.toString())!!.writable)
    }

    @Test
    fun failedActiveCommitKeepsPendingEvenWhenPreferencesMemoryChanged() {
        context.getSharedPreferences("saf_grants", 0).edit().clear().commit()
        val failing = FailureContext(context, failAt = 3, applyBeforeFailure = true)
        val added = SafGrants.add(failing, tree, true)
        assertFalse(added.added)
        assertEquals("STATE_UNAVAILABLE", added.failure)
        assertEquals(
            SafGrantRecord.State.PENDING,
            SafLedgerStore(failing.ledger).load().findByUri(tree.toString())!!.state,
        )
        assertEquals("NO_GRANT", SafGrants.lookup(failing, SafGrants.id(tree)).failure)
    }

    @Test
    fun unknownSystemStateAfterTakeCannotActivateRequestedWriteBits() {
        val controlled = UnknownAfterTakeContext(context)
        val added = SafGrants.add(controlled, tree, true)
        assertFalse("take 请求的位不是已观察的系统权限事实", added.added)
        assertEquals("PERMISSION_STATE_UNAVAILABLE", added.failure)
        assertEquals(
            SafGrantRecord.State.PENDING,
            SafLedgerStore.open(controlled).load().findByUri(tree.toString())!!.state,
        )
        controlled.unknown = false
        SafGrants.cleanupPending(controlled)
        assertEquals(
            SafGrantRecord.State.ACTIVE,
            SafLedgerStore.open(controlled).load().findByUri(tree.toString())!!.state,
        )
    }

    @Test
    fun slowFallbackListRevalidatesMigrationPreferenceAndSystemFactsOutsideLock() {
        context.getSharedPreferences("saf_grants", 0).edit().remove("label:$tree").commit()
        val provider = BlockingLabels()
        val info =
            ProviderInfo().apply {
                authority = tree.authority
                exported = true
                grantUriPermissions = true
            }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(tree.authority, provider)
        val failing = FailureContext(context)
        val pool = Executors.newSingleThreadExecutor()
        val before = context.getSharedPreferences("saf_grants", 0).all
        val slow = pool.submit<List<SafGrants.Grant>> { SafGrants.list(failing) }
        try {
            assertTrue("Provider 已进入可控等待点", provider.entered.await(5, TimeUnit.SECONDS))
            // list/lookup/add/remove/setWritable 全部在慢查询未结束时完成；查询不能持授权锁。
            migrate(failing)
            assertNotNull(SafGrants.lookup(failing, SafGrants.id(tree)).grant)
            SafGrants.setWritable(failing, tree, false)
            val other = Uri.parse("content://test.dsha.migration/tree/OTHER")
            assertTrue(SafGrants.add(failing, other, true).added)
            SafGrants.forget(failing, other)
            context.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            provider.release.countDown()
            val result = slow.get(5, TimeUnit.SECONDS).single()
            assertFalse("不能拼接查询前系统位和切换后的旧偏好", result.canWrite)
            assertFalse(result.writable)
            assertEquals(
                "慢查询回调不能再写已退休的旧命名空间",
                before,
                context.getSharedPreferences("saf_grants", 0).all,
            )
        } finally {
            provider.release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failedWritablePreferenceCommitDoesNotEnableLookupOrWorkbenchWrites() {
        migrate()
        SafGrants.setWritable(context, tree, false)
        val failing = FailureContext(context, applyBeforeFailure = true)
        SafGrants.setWritable(failing, tree, true)
        assertFalse(SafGrants.lookup(failing, SafGrants.id(tree)).grant!!.writable)
        val workbench = SafWorkbench(failing) { java.io.File("/unused-rootfs") }
        assertThrows(SafTree.Failure::class.java) {
            workbench.prepare(SafGrants.id(tree), "", false)
        }
        SafGrants.setWritable(failing, tree, true)
        assertTrue(SafGrants.lookup(failing, SafGrants.id(tree)).grant!!.writable)
    }

    @Test
    fun reentrantLookupCannotAuthorizeTheUnconfirmedPreferencesMap() {
        context.getSharedPreferences("saf_grants", 0).edit().clear().commit()
        val failing = FailureContext(context, applyBeforeFailure = true)
        var found: SafGrants.Lookup? = null
        failing.afterFailure = { found = SafGrants.lookup(failing, SafGrants.id(tree)) }
        assertFalse(SafLedgerStore(failing.ledger).save(SafTestSupport.single(tree.toString(), 3)))
        assertEquals("NO_GRANT", found!!.failure)
    }

    @Test
    fun unresolvedPendingCannotLoseItsOriginalBeforeAndIntent() {
        val store = SafLedgerStore.open(context)
        assertTrue(
            store.save(
                SafGrantLedger.empty().beginTake(tree.toString(), "应用", "目录", 1, 3, 1_000).ledger
            )
        )
        val original = store.load().findByUri(tree.toString())!!
        val controlled = UnknownAfterTakeContext(context, unknownOnTake = false)
        controlled.failRelease = true
        val added = SafGrants.add(controlled, tree, true)
        assertFalse(added.added)
        assertEquals("STATE_UNAVAILABLE", added.failure)
        assertEquals(0, controlled.takeCalls)
        assertEquals(original, store.load().findByUri(tree.toString()))
        controlled.failRelease = false
        SafGrants.cleanupPending(controlled)
        assertNull(store.load().findByUri(tree.toString()))
        assertTrue(
            context.contentResolver.persistedUriPermissions
                .single { it.uri == tree }
                .isReadPermission
        )
        assertFalse(
            context.contentResolver.persistedUriPermissions
                .single { it.uri == tree }
                .isWritePermission
        )
    }

    @Test
    fun fallbackUnknownAfterTakeKeepsLegacyPendingAndOnlyReleasesItsDelta() {
        val controlled = UnknownAfterTakeContext(context)
        val failing = FailureContext(controlled, failAt = 0)
        val other = Uri.parse("content://test.dsha.migration/tree/OTHER")
        val added = SafGrants.add(failing, other, true)
        assertFalse(added.added)
        assertEquals("PERMISSION_STATE_UNAVAILABLE", added.failure)
        assertTrue(
            context
                .getSharedPreferences("saf_grants", 0)
                .getStringSet("pending", emptySet())!!
                .contains(other.toString())
        )
        controlled.unknown = false
        SafGrants.cleanupPending(failing)
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == other })
        assertTrue(
            context.contentResolver.persistedUriPermissions
                .single { it.uri == tree }
                .isWritePermission
        )
        assertEquals(1, controlled.takeCalls)
    }

    @Test
    fun slowLedgerMetadataCannotOverwriteARegrantedEpoch() {
        migrate()
        val store = SafLedgerStore.open(context)
        assertTrue(store.save(store.load().setMetadata(tree.toString(), "测试应用", "").ledger))
        val oldEpoch = store.load().findByUri(tree.toString())!!.epoch
        val provider = BlockingLabels()
        val info =
            ProviderInfo().apply {
                authority = tree.authority
                exported = true
                grantUriPermissions = true
            }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(tree.authority, provider)
        val pool = Executors.newSingleThreadExecutor()
        val slow = pool.submit<List<SafGrants.Grant>> { SafGrants.list(context) }
        try {
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
            SafGrants.forget(context, tree)
            assertTrue(SafGrants.add(context, tree, true).added)
            val regranted = store.load().findByUri(tree.toString())!!
            assertTrue(regranted.epoch > oldEpoch)
            assertEquals("新目录", regranted.label)
            provider.release.countDown()
            assertEquals("新目录", slow.get(5, TimeUnit.SECONDS).single().label)
            assertEquals("新目录", store.load().findByUri(tree.toString())!!.label)
        } finally {
            provider.release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun seedLegacyPending(): FailureContext {
        context
            .getSharedPreferences("saf_grants", 0)
            .edit()
            .putStringSet("pending", setOf(tree.toString()))
            .putString("pending-before:$tree", "1")
            .putString("pending-intent:$tree", "3")
            .commit()
        val controlled = UnknownAfterTakeContext(context, unknownOnTake = false)
        controlled.failRelease = true
        return FailureContext(controlled, failAt = 0)
    }

    @Test
    fun fallbackPendingDoesNotAuthorizeLookupOrListWhileCleanupFails() {
        val failing = seedLegacyPending()
        assertEquals("NO_GRANT", SafGrants.lookup(failing, SafGrants.id(tree)).failure)
        assertTrue(SafGrants.listLight(failing).isEmpty())
    }

    @Test
    fun fallbackAddCannotOverwriteUnresolvedPendingOwnership() {
        val failing = seedLegacyPending()
        val before = context.getSharedPreferences("saf_grants", 0).all
        val added = SafGrants.add(failing, tree, true)
        assertFalse(added.added)
        assertEquals("STATE_UNAVAILABLE", added.failure)
        assertEquals(before, context.getSharedPreferences("saf_grants", 0).all)
    }

    @Test
    fun fallbackRemoveCannotOverwriteUnresolvedPendingOwnership() {
        val failing = seedLegacyPending()
        val before = context.getSharedPreferences("saf_grants", 0).all
        SafGrants.forget(failing, tree)
        assertEquals(before, context.getSharedPreferences("saf_grants", 0).all)
        assertEquals("NO_GRANT", SafGrants.lookup(failing, SafGrants.id(tree)).failure)
    }

    /** 一次失败可发生在任意提交；回调模拟同步 prefs 监听器重入，跨线程另由 Provider 栅栏覆盖。 */
    private class FailureContext(
        base: Context,
        private val failAt: Int = 1,
        private val applyBeforeFailure: Boolean = false,
    ) : ContextWrapper(base) {
        var afterFailure: (() -> Unit)? = null
        private var commits = 0
        private val real = base.getSharedPreferences(SafLedgerStore.NAMESPACE, 0)
        val ledger: SharedPreferences =
            object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return Proxy.newProxyInstance(
                        javaClass.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { proxy, method, args ->
                        if (method.name == "commit" && (++commits == failAt || failAt == 0)) {
                            if (applyBeforeFailure) editor.apply()
                            afterFailure?.invoke()
                            false
                        } else {
                            try {
                                val result = method.invoke(editor, *(args ?: emptyArray()))
                                if (result === editor) proxy else result
                            } catch (wrapped: InvocationTargetException) {
                                throw wrapped.cause!!
                            }
                        }
                    } as SharedPreferences.Editor
                }
            }

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == SafLedgerStore.NAMESPACE) ledger else super.getSharedPreferences(name, mode)
    }

    private class UnknownAfterTakeContext(
        base: Context,
        private val unknownOnTake: Boolean = true,
    ) : ContextWrapper(base) {
        var unknown = false
        var failRelease = false
        var takeCalls = 0
        private val resolver =
            object : ContentResolver(base) {
                override fun getPersistedUriPermissions(): List<UriPermission> {
                    if (unknown) throw IllegalStateException("PERMISSION_STATE_UNAVAILABLE")
                    return base.contentResolver.persistedUriPermissions
                }

                override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
                    takeCalls++
                    base.contentResolver.takePersistableUriPermission(uri, modeFlags)
                    unknown = unknownOnTake
                }

                override fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) {
                    if (failRelease) throw SecurityException("RELEASE_FAILED")
                    base.contentResolver.releasePersistableUriPermission(uri, modeFlags)
                }
            }

        override fun getContentResolver(): ContentResolver = resolver
    }

    private class BlockingLabels : ContentProvider() {
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            order: String?,
        ): Cursor = query(uri, projection, selection, args, order, null)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            order: String?,
            signal: CancellationSignal?,
        ): Cursor {
            val oldQuery = first.compareAndSet(true, false)
            if (oldQuery) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "PROVIDER_FIXTURE_TIMEOUT" }
            }
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).apply {
                addRow(arrayOf(if (oldQuery) "迟到目录" else "新目录"))
            }
        }

        override fun getType(uri: Uri): String = DocumentsContract.Document.MIME_TYPE_DIR

        override fun insert(uri: Uri, values: ContentValues?): Uri? =
            throw UnsupportedOperationException()

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
            throw UnsupportedOperationException()

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            args: Array<out String>?,
        ): Int = throw UnsupportedOperationException()
    }
}
