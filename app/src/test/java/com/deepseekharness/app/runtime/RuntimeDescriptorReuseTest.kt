package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.RuntimeDescriptor
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 以实际 RuntimeDescriptor 解析器计数，变化/未知负控不能靠一个 ready=true 通过。 */
class RuntimeDescriptorReuseTest {
    private class Fixture {
        var apk: Any = Any()
        var assets: Any = Any()
        var settings: Any = Any()
        var root = "root/dev/ino/descriptor/ctime/ns"
        var tools = "sha256:tools:revision:1"
        var known = true
        var current = true
        var content = RuntimeDescriptorPreparationTest.bytes()
        var reads = 0
        val lines = ArrayList<String>()
        val preparation = RuntimeDescriptorPreparation { identity() }

        fun identity(): RuntimeDescriptorPreparation.Identity? =
            if (!known) null
            else RuntimeDescriptorPreparation.Identity(apk, assets, settings, root, tools)

        fun scope(generation: Long = 7) = preparation.open(generation, { current }, lines::add)

        fun read(
            kind: RuntimeDescriptorPreparation.Kind = RuntimeDescriptorPreparation.Kind.INSTALLED
        ): RuntimeDescriptor =
            preparation.descriptor(kind) {
                reads++
                content.clone()
            }

        fun count(kind: String, key: String): Long =
            lines
                .last {
                    it.startsWith("STARTUP_PREP_OBSERVATION: phase=descriptor-$kind $key=")
                }
                .substringAfter("$key=")
                .toLong()
    }

    @Test
    fun fixedSameInvocationParsesEachKindOnceWhileReadingAllSixTimes() {
        val f = Fixture()
        f.scope().use {
            repeat(2) {
                f.read()
                f.read(RuntimeDescriptorPreparation.Kind.EXPECTED)
                f.read()
            }
        }
        assertEquals(6, f.reads)
        assertEquals(4, f.count("installed", "calls"))
        assertEquals(1, f.count("installed", "parses"))
        assertEquals(3, f.count("installed", "hits"))
        assertEquals(2, f.count("expected", "calls"))
        assertEquals(1, f.count("expected", "parses"))
        assertEquals(1, f.count("expected", "hits"))
    }

    @Test
    fun apkSplitAssetManagerSettingsRootFileAndToolsChangesEachMiss() {
        val changes: List<(Fixture) -> Unit> =
            listOf(
                { it.apk = Any() },
                { it.assets = Any() },
                { it.settings = Any() },
                { it.root += ":same-path-replaced" },
                { it.tools += ":repair-revision" },
            )
        for (change in changes) {
            val f = Fixture()
            f.scope().use {
                val first = f.read()
                change(f)
                assertNotSame(first, f.read())
            }
            assertEquals(2, f.reads)
            assertEquals(2, f.count("installed", "parses"))
            assertEquals(0, f.count("installed", "hits"))
        }
    }

    @Test
    fun contentChangeWithIdenticalMetadataStillParsesFreshBytes() {
        val f = Fixture()
        f.scope().use {
            val first = f.read()
            f.content = RuntimeDescriptorPreparationTest.bytes("b")
            val second = f.read()
            assertNotEquals(first.id(), second.id())
            assertTrue(first.compatible(second))
            assertFalse(first.latest(second))
        }
        assertEquals(2, f.count("installed", "parses"))
        assertEquals(0, f.count("installed", "hits"))
    }

    @Test
    fun unknownCannotResurrectAnOlderEntryEvenAfterTheOldIdentityReturns() {
        val f = Fixture()
        f.scope().use {
            val first = f.read()
            f.known = false
            assertNotSame(first, f.read())
            f.known = true
            assertNotSame(first, f.read())
            assertNotSame(first, f.read())
        }
        assertEquals(4, f.count("installed", "parses"))
        assertEquals(0, f.count("installed", "hits"))
    }

    @Test
    fun sameGenerationExplicitStopCancellationRetiresTheScope() {
        val f = Fixture()
        f.scope().use {
            val first = f.read()
            f.current = false
            assertNotSame(first, f.read())
            f.current = true
            assertNotSame(first, f.read())
        }
        assertEquals(3, f.count("installed", "parses"))
        assertEquals(0, f.count("installed", "hits"))
    }

    @Test
    fun scopeCloseOperationChangeAndWorkerThreadCannotReusePreviousResults() {
        val f = Fixture()
        lateinit var first: RuntimeDescriptor
        f.scope().use {
            first = f.read()
            val other = CompletableFuture.supplyAsync { f.read() }.get(5, TimeUnit.SECONDS)
            assertNotSame(first, other)
            assertSame(first, f.read())
        }
        f.scope(8).use { assertNotSame(first, f.read()) }
        assertNotSame(first, f.read())
        assertEquals(5, f.reads)
    }

    @Test
    fun replacementDuringReadCannotPublishTheMixedObservation() {
        val f = Fixture()
        f.scope().use {
            val first =
                f.preparation.descriptor(RuntimeDescriptorPreparation.Kind.INSTALLED) {
                    f.root += ":replaced-during-read"
                    f.content.clone()
                }
            assertNotSame(first, f.read())
            val stable = f.read()
            assertSame(stable, f.read())
        }
        assertEquals(2, f.count("installed", "parses"))
        assertEquals(2, f.count("installed", "hits"))
    }

    @Test
    fun repairOrMaintenanceInvalidationNeverReplaysAndDoesNotAuthorizeHealth() {
        val f = Fixture()
        f.scope().use {
            val first = f.read()
            f.preparation.invalidate()
            assertNotSame(first, f.read())
            assertNotSame(first, f.read())
            assertFalse(RuntimeDescriptor.healthy(null, first.id()))
        }
        assertEquals(3, f.reads)
        assertEquals(3, f.count("installed", "parses"))
    }

    @Test
    fun readAndParseFailureRetireTheOldSuccessRatherThanRetryTheAction() {
        val f = Fixture()
        f.scope().use {
            val first = f.read()
            var attempts = 0
            assertThrows(IOException::class.java) {
                f.preparation.descriptor(RuntimeDescriptorPreparation.Kind.INSTALLED) {
                    attempts++
                    throw IOException("EXECUTION_UNKNOWN")
                }
            }
            assertEquals(1, attempts)
            assertNotSame(first, f.read())
            f.content = byteArrayOf()
            assertThrows(IOException::class.java) { f.read() }
            f.content = RuntimeDescriptorPreparationTest.bytes()
            assertNotSame(first, f.read())
        }
        assertEquals(0, f.count("installed", "hits"))
    }
}
