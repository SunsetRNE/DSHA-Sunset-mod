package com.deepseekharness.app.saf

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P1：失败提权仍隔离，失败缩权/撤销也必须继续拒绝能力；不靠失败 raw map 授权。 */
@RunWith(SafGrantsTest.NoApkRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class SafRestrictionFailureTest {
    private val tree = Uri.parse("content://test.dsha.restrictions/tree/ROOT")
    private val id = SafGrantRecord.idOf(tree.toString())
    private lateinit var app: Context
    private lateinit var context: FailedCommitContext
    private lateinit var store: SafLedgerStore
    private lateinit var workbench: SafWorkbench
    private lateinit var initialGrant: SafGrantRecord

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("saf_grants", 0).edit().clear().commit()
        app.getSharedPreferences(SafLedgerStore.NAMESPACE, 0).edit().clear().commit()
        app.contentResolver.takePersistableUriPermission(tree, 3)
        context = FailedCommitContext(app)
        store = SafLedgerStore(context.ledger)
        val pending =
            SafGrantLedger.empty().beginTake(tree.toString(), "应用", "目录", 1, 3, 1_000).ledger
        assertTrue(store.save(pending.commitTake(tree.toString(), 3, 2, true, 1_001).ledger))
        initialGrant = store.load().findById(id)!!
        workbench = SafWorkbench(context) { File("/unused-rootfs") }
    }

    @Test
    fun failedWriteDisableRejectsLookupBridgeNewWriteAndOldTicketGuards() {
        val old = workbench.prepare(id, "", false)
        context.failCommits = true
        SafGrants.setWritable(context, tree, false)
        assertWriteDenied(old)
        assertFalse(
            "适配器重建也不能恢复旧写偏好",
            SafLedgerStore(context.ledger).load().findById(id)!!.userWritable,
        )
    }

    @Test
    fun writeDisableFailureWithoutMemoryMutationStillKeepsTheDenial() {
        val old = workbench.prepare(id, "", false)
        context.applyBeforeFailure = false
        context.failCommits = true
        SafGrants.setWritable(context, tree, false)
        assertWriteDenied(old)
    }

    @Test
    fun failedReleaseAndReleasingSaveNeverRestoreActiveAndRetryOnlyOwnedBits() {
        val old = workbench.prepare(id, "", false)
        context.failCommits = true
        context.resolver.failRelease = true
        SafGrants.forget(context, tree)
        assertRevoked(old)
        assertEquals(SafGrantRecord.State.RELEASING, store.load().findById(id)!!.state)
        assertTrue(context.resolver.released.isEmpty())

        // 系统释放先恢复，磁盘仍失败：仍要保留移除意图，不能因其它功能的读位还在就恢复 ACTIVE。
        context.resolver.failRelease = false
        SafGrants.cleanupPending(context)
        assertRevoked(old)
        assertEquals(1, flags(tree))
        assertEquals(listOf(2), context.resolver.released)

        context.failCommits = false
        SafGrants.cleanupPending(context)
        assertNull(store.load().findById(id))
        assertRevoked(old)
        assertEquals("只释放本功能新增的写位，其它功能原有读位保留", 1, flags(tree))
        assertEquals("磁盘重试不得再次释放原有读位", listOf(2), context.resolver.released)
    }

    @Test
    fun unrelatedSuccessfulMetadataSaveCannotClearWriteDisableIntent() {
        val before = store.load()
        val old = workbench.prepare(id, "", false)
        context.failCommits = true
        SafGrants.setWritable(context, tree, false)
        context.failCommits = false
        assertTrue(store.save(before.setMetadata(tree.toString(), "应用", "无关保存").ledger))
        assertTrue(store.save(before.setMetadata(tree.toString(), "应用", "第二次无关保存").ledger))
        context.failCommits = true
        assertWriteDenied(old)

        context.failCommits = false
        SafGrants.cleanupPending(context)
        assertFalse(store.load().findById(id)!!.userWritable)
        // 只有关闭意图已确实重试后，新的显式开启才能成功。
        SafGrants.setWritable(context, tree, true)
        assertTrue(SafGrants.lookup(context, id).grant!!.writable)
    }

    @Test
    fun unrelatedSuccessfulSaveCannotClearPendingRemoval() {
        val before = store.load()
        val old = workbench.prepare(id, "", false)
        context.failCommits = true
        context.resolver.failRelease = true
        SafGrants.forget(context, tree)
        context.failCommits = false
        assertTrue(store.save(before.setMetadata(tree.toString(), "应用", "无关保存").ledger))
        assertTrue(store.save(before.setMetadata(tree.toString(), "应用", "第二次无关保存").ledger))
        context.failCommits = true
        assertRevoked(old)
        assertEquals(SafGrantRecord.State.RELEASING, store.load().findById(id)!!.state)

        context.failCommits = false
        context.resolver.failRelease = false
        SafGrants.cleanupPending(context)
        assertNull(store.load().findById(id))
        assertEquals(1, flags(tree))
        assertEquals(listOf(2), context.resolver.released)
    }

    @Test
    fun successfulSaveForAnotherTreeDoesNotRemoveTheOriginalFence() {
        val other = Uri.parse("content://test.dsha.restrictions/tree/OTHER")
        app.contentResolver.takePersistableUriPermission(other, 3)
        val pending = store.load().beginTake(other.toString(), "另一应用", "另一目录", 0, 3, 2_000).ledger
        val before = pending.commitTake(other.toString(), 3, 3, true, 2_001).ledger
        assertTrue(store.save(before))
        val old = workbench.prepare(id, "", false)
        context.failCommits = true
        SafGrants.setWritable(context, tree, false)
        context.failCommits = false
        assertTrue(store.save(before.setMetadata(other.toString(), "另一应用", "更新名称").ledger))
        context.failCommits = true
        assertWriteDenied(old)
        val otherId = SafGrants.id(other)
        assertTrue(SafGrants.lookup(context, otherId).grant!!.writable)
        val ticket = workbench.prepare(otherId, "", false)
        workbench.cancel(ticket)
    }

    private fun assertWriteDenied(old: SafWorkbench.Ticket) {
        val lookup = SafGrants.lookup(context, id)
        assertNull(lookup.failure)
        assertNotNull(lookup.grant)
        assertTrue("现场系统仍有写位，拒绝不能冒充系统撤权", lookup.grant!!.canWrite)
        assertFalse("失败的关闭写入也必须立即缩权", lookup.grant!!.writable)
        assertBridgeFailure(true, "READ_ONLY")
        assertEquals(
            "HANDLE_NOT_PERMITTED",
            assertThrows(SafTree.Failure::class.java) {
                    workbench.prepare(id, "new.txt", false)
                }
                .code,
        )
        assertEquals(
            "HANDLE_NOT_PERMITTED",
            assertThrows(SafTree.Failure::class.java) {
                    workbench.retarget(old, "new.txt")
                }
                .code,
        )
        assertRunningFailure(old, "PERMISSION_DENIED")
    }

    private fun assertRevoked(old: SafWorkbench.Ticket) {
        assertEquals("NO_GRANT", SafGrants.lookup(context, id).failure)
        assertTrue(SafGrants.listLight(context).isEmpty())
        assertBridgeFailure(true, "NO_GRANT")
        assertBridgeFailure(false, "NO_GRANT")
        assertEquals(
            "NO_GRANT",
            assertThrows(SafTree.Failure::class.java) {
                    workbench.prepare(id, "", true)
                }
                .code,
        )
        assertEquals(
            "NO_GRANT",
            assertThrows(SafTree.Failure::class.java) {
                    workbench.prepare(id, "", false)
                }
                .code,
        )
        assertEquals(
            "NO_GRANT",
            assertThrows(SafTree.Failure::class.java) {
                    workbench.retarget(old, "new.txt")
                }
                .code,
        )
        assertRunningFailure(old, "HANDLE_REVOKED")
    }

    private fun assertRunningFailure(old: SafWorkbench.Ticket, code: String) {
        val method =
            workbench.javaClass.getDeclaredMethod(
                "running",
                SafWorkbench.Ticket::class.java,
                SafGrantRecord::class.java,
                Long::class.javaPrimitiveType,
            )
        method.isAccessible = true
        val wrapped =
            assertThrows(InvocationTargetException::class.java) {
                method.invoke(
                    workbench,
                    old,
                    initialGrant,
                    android.os.SystemClock.elapsedRealtime() + 10_000,
                )
            }
        assertTrue(wrapped.cause is java.io.IOException)
        assertEquals(code, wrapped.cause!!.message)
    }

    private fun assertBridgeFailure(write: Boolean, code: String) {
        val service = com.deepseekharness.app.HttpShellService(context)
        val type = Class.forName("com.deepseekharness.app.HttpShellService\$BridgeRequest")
        val constructor = type.declaredConstructors.single { it.parameterCount == 8 }
        constructor.isAccessible = true
        val request =
            constructor.newInstance(
                "/app/saf/ls?tree=$id&path=",
                "",
                "",
                false,
                null,
                null,
                null,
                null,
            )
        val method =
            service.javaClass.getDeclaredMethod("safTree", type, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val failure =
            assertThrows(InvocationTargetException::class.java) {
                method.invoke(service, request, write)
            }
        assertEquals(code, (failure.cause as SafTree.Failure).code)
    }

    private fun flags(uri: Uri): Int {
        var result = 0
        for (permission in app.contentResolver.persistedUriPermissions) {
            if (permission.uri == uri) {
                if (permission.isReadPermission) result = result or 1
                if (permission.isWritePermission) result = result or 2
            }
        }
        return result
    }

    private class FailedCommitContext(base: Context) : ContextWrapper(base) {
        var failCommits = false
        var applyBeforeFailure = true
        val resolver = ControlledResolver(base)
        private val real = base.getSharedPreferences(SafLedgerStore.NAMESPACE, 0)
        val ledger: SharedPreferences =
            object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return Proxy.newProxyInstance(
                        javaClass.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { proxy, method, args ->
                        if (method.name == "commit" && failCommits) {
                            if (applyBeforeFailure) editor.apply()
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

        override fun getContentResolver(): ContentResolver = resolver

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == SafLedgerStore.NAMESPACE) ledger else super.getSharedPreferences(name, mode)
    }

    private class ControlledResolver(base: Context) : ContentResolver(base) {
        private val real = base.contentResolver
        var failRelease = false
        val released = ArrayList<Int>()

        override fun getPersistedUriPermissions(): List<UriPermission> =
            real.persistedUriPermissions

        override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) =
            real.takePersistableUriPermission(uri, modeFlags)

        override fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) {
            if (failRelease) throw SecurityException("RELEASE_FAILED")
            real.releasePersistableUriPermission(uri, modeFlags)
            released.add(modeFlags)
        }
    }
}
