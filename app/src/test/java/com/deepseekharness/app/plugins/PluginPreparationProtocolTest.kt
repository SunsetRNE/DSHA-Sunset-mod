package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginInvocationContext.Binding
import com.deepseekharness.app.plugins.PluginInvocationContext.Stage
import com.deepseekharness.app.plugins.PluginPreparationProtocol.Origin
import com.deepseekharness.app.plugins.PluginPreparationProtocol.Outcome
import com.deepseekharness.app.plugins.PluginPreparationProtocol.Status
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PluginPreparationProtocolTest {
    private fun context(
        bindings: List<Binding> = listOf(Binding("/data/current", "/root/.dsh")),
        budgets: Map<Stage, Long> = PluginPreparationProtocol.normalWebBudgets(false),
        cancel: Long = 0,
        tools: String = "a".repeat(64),
        rootId: String = "1:2",
        dataId: String = "1:3",
        startup: String = "startup-1",
        operation: String = "operation-1",
        generation: Long = 7,
    ) =
        PluginInvocationContext(
            "/files/linux/ubuntu",
            "/data/current",
            rootId,
            dataId,
            bindings,
            "/root/.dsh",
            "web",
            startup,
            operation,
            generation,
            cancel,
            tools,
            budgets,
            "b".repeat(32),
        )

    private val completed = Outcome(Status.COMPLETED, true, true)

    @Test
    fun frozenCollectionsAndSeparateLockParents() {
        val bindings = mutableListOf(Binding("/data/current", "/root/.dsh"))
        val budgets = PluginPreparationProtocol.normalWebBudgets(false).toMutableMap()
        val snapshot = context(bindings, budgets)
        val fingerprint = snapshot.fingerprint()
        bindings.clear()
        budgets[Stage.CAS] = 1
        assertEquals(fingerprint, snapshot.fingerprint())
        assertEquals("/files/linux/ubuntu/root/.dsha-data.lock", snapshot.hostDataLock())
        assertEquals("/data/current/profiles/web/package.json.lock", snapshot.hostProfileLock())
        assertNotEquals("/data/.dsha-data.lock", snapshot.hostDataLock())
    }

    @Test
    fun allInvocationInputsInvalidateFingerprint() {
        val frozen = context().fingerprint()
        listOf(
                context(cancel = 1),
                context(tools = "c".repeat(64)),
                context(rootId = "1:4"),
                context(dataId = "1:5"),
                context(startup = "startup-2"),
                context(operation = "operation-2"),
                context(generation = 8),
                context(budgets = PluginPreparationProtocol.normalWebBudgets(true)),
                context(
                    bindings =
                        listOf(
                            Binding("/data/current", "/root/.dsh"),
                            Binding("/private/script", "/root/.dsh/plugin-manager.py"),
                        )
                ),
            )
            .forEach { assertNotEquals(frozen, it.fingerprint()) }
    }

    @Test
    fun bindingOrderAndSpecificFileOverridesAreFrozen() {
        val value =
            context(
                bindings =
                    listOf(
                        Binding("/other", "/root/.dsh"),
                        Binding("/data/current", "/root/.dsh"),
                        Binding("/managed/manager", "/root/.dsh/plugin-manager.py"),
                    )
            )
        assertEquals("/managed/manager", value.resolve("/root/.dsh/plugin-manager.py"))
        assertEquals("/data/current/profiles/web", value.resolve("/root/.dsh/profiles/web"))
    }

    @Test
    fun threeIndependentCommandsKeepBudgetsAndEnvironment() {
        val value = context()
        val protocol = PluginPreparationProtocol(value, Origin.HOST_STARTUP) { value.fingerprint() }
        val requests = mutableListOf<PluginPreparationProtocol.Request>()
        Stage.entries.forEach { stage ->
            assertEquals(
                completed,
                protocol.dispatch(stage) {
                    requests.add(it)
                    completed
                },
            )
        }
        assertEquals(3, requests.size)
        assertEquals(listOf(900_000L, 90_000L, 3_000_000L), requests.map { it.timeoutMs })
        assertEquals(
            listOf("python3", "-u", "/root/.dsh/materialize-cas-links.py"),
            requests[0].argv,
        )
        assertEquals(
            listOf("python3", "-u", "/root/.dsh/register-builtin-plugins.py"),
            requests[1].argv,
        )
        assertEquals("migrate-review-markers", requests[2].argv.last())
        assertEquals("auto", requests[2].environment["DSHA_PLUGIN_DOWNLOAD_SOURCE"])
        requests.forEach {
            assertSame(value, it.context)
            assertEquals("/root/.dsh", it.environment["DSH_HOME"])
            assertEquals("b".repeat(32), it.environment["DSHA_PLUGIN_TASK"])
        }
        assertThrows(IllegalStateException::class.java) {
            protocol.dispatch(Stage.CAS) { completed }
        }
    }

    @Test
    fun partialAndUnknownRemainTerminalWithoutReplay() {
        for (status in listOf(Status.UNKNOWN, Status.PARTIAL, Status.FAILED, Status.CANCELLED)) {
            val value = context()
            val protocol =
                PluginPreparationProtocol(value, Origin.HOST_STARTUP) { value.fingerprint() }
            var calls = 0
            val outcome = Outcome(status, status == Status.PARTIAL, false)
            assertSame(
                outcome,
                protocol.dispatch(Stage.CAS) {
                    calls++
                    outcome
                },
            )
            assertThrows(IllegalStateException::class.java) {
                protocol.dispatch(Stage.CAS) {
                    calls++
                    completed
                }
            }
            assertThrows(IllegalStateException::class.java) {
                protocol.dispatch(Stage.REGISTER) {
                    calls++
                    completed
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun exceptionAfterDispatchCannotBeRetried() {
        val value = context()
        val protocol = PluginPreparationProtocol(value, Origin.HOST_STARTUP) { value.fingerprint() }
        var calls = 0
        assertThrows(IOException::class.java) {
            protocol.dispatch(Stage.CAS) {
                calls++
                throw IOException("lost receipt")
            }
        }
        assertThrows(IllegalStateException::class.java) {
            protocol.dispatch(Stage.CAS) {
                calls++
                completed
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun staleBeforeDispatchDoesNotSendAndStaleAfterDispatchDoesNotAdvance() {
        for (changeAfter in listOf(false, true)) {
            val value = context()
            var current: String? = if (changeAfter) value.fingerprint() else null
            val protocol = PluginPreparationProtocol(value, Origin.HOST_STARTUP) { current }
            var calls = 0
            assertEquals(
                Status.INVALIDATED,
                protocol
                    .dispatch(Stage.CAS) {
                        calls++
                        current = null
                        completed
                    }
                    .status,
            )
            assertEquals(if (changeAfter) 1 else 0, calls)
            assertThrows(IllegalStateException::class.java) {
                protocol.dispatch(Stage.REGISTER) { completed }
            }
        }
    }

    @Test
    fun resultNeedsBothConfirmedExitAndValidatedStageReceipt() {
        for (outcome in
            listOf(
                Outcome(Status.COMPLETED, false, true),
                Outcome(Status.COMPLETED, true, false),
            )) {
            val value = context()
            val protocol =
                PluginPreparationProtocol(value, Origin.HOST_STARTUP) { value.fingerprint() }
            assertEquals(Status.UNKNOWN, protocol.dispatch(Stage.CAS) { outcome }.status)
            assertThrows(IllegalStateException::class.java) {
                protocol.dispatch(Stage.CAS) { completed }
            }
        }
    }

    @Test
    fun nodeLockedCallbackNeverSynchronouslyWaitsForHostStop() {
        val value = context()
        val protocol =
            PluginPreparationProtocol(value, Origin.NODE_LOCKED_CALLBACK) { value.fingerprint() }
        var stopCalls = 0
        assertEquals(
            Status.INVALIDATED,
            protocol
                .dispatch(Stage.CAS) {
                    stopCalls++
                    completed
                }
                .status,
        )
        assertEquals(0, stopCalls)
    }

    @Test
    fun outOfOrderAndWrongOwnerCannotDispatch() {
        val value = context()
        val protocol = PluginPreparationProtocol(value, Origin.HOST_STARTUP) { value.fingerprint() }
        assertThrows(IllegalStateException::class.java) {
            protocol.dispatch(Stage.REGISTER) { completed }
        }
        var failure: Throwable? = null
        val thread = Thread {
            try {
                protocol.dispatch(Stage.CAS) { completed }
            } catch (error: Throwable) {
                failure = error
            }
        }
        thread.start()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun interruptedBeforeDispatchNeverSendsAndPreservesFlag() {
        val value = context()
        val protocol = PluginPreparationProtocol(value, Origin.HOST_STARTUP) { value.fingerprint() }
        var calls = 0
        Thread.currentThread().interrupt()
        try {
            assertEquals(
                Status.CANCELLED,
                protocol
                    .dispatch(Stage.CAS) {
                        calls++
                        completed
                    }
                    .status,
            )
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, calls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun malformedPathsHomeAndBudgetFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { context(bindings = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            context(bindings = listOf(Binding("/data/../current", "/root/.dsh")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(budgets = mapOf(Stage.CAS to 1L))
        }
        assertThrows(IllegalArgumentException::class.java) { context(cancel = -1) }
    }
}
