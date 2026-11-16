package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.BackupJson
import com.deepseekharness.app.backup.RuntimeDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RuntimeDescriptorPreparationTest {
    companion object {
        fun bytes(revision: String = "a"): ByteArray {
            val value =
                linkedMapOf<String, Any>(
                    "version" to 1L,
                    "baseVersion" to "10",
                    "dshVersion" to "0.2.2-rc.1",
                    "launcherContract" to "DSHA_ARM64_V2",
                    "bridgeProtocol" to 3L,
                    "dataRead" to listOf("dsh-v3"),
                    "dataWrite" to "dsh-v3",
                    "inputs" to mapOf("runtime" to revision.repeat(64)),
                )
            value["runtimeId"] = RuntimeDescriptor.calculateId(value)
            return BackupJson.write(value, 2 * 1024 * 1024)
        }
    }

    @Test
    fun unscopedReadsKeepOriginalParsingAndReadEveryTime() {
        val preparation = RuntimeDescriptorPreparation()
        val reads = AtomicInteger()
        val source = RuntimeDescriptorPreparation.Bytes {
            reads.incrementAndGet()
            bytes()
        }
        val first = preparation.descriptor(RuntimeDescriptorPreparation.Kind.EXPECTED, source)
        val second = preparation.descriptor(RuntimeDescriptorPreparation.Kind.EXPECTED, source)
        assertNotSame(first, second)
        assertTrue(first.latest(second))
        assertEquals(2, reads.get())
    }

    @Test
    fun structuralScopeCountsEveryRealReadAndParse() {
        val preparation = RuntimeDescriptorPreparation()
        val lines = ArrayList<String>()
        val reads = AtomicInteger()
        val source = RuntimeDescriptorPreparation.Bytes {
            reads.incrementAndGet()
            bytes()
        }
        preparation.open(7, { true }, lines::add).use {
            repeat(2) {
                preparation.descriptor(RuntimeDescriptorPreparation.Kind.INSTALLED, source)
                preparation.descriptor(RuntimeDescriptorPreparation.Kind.EXPECTED, source)
                preparation.descriptor(RuntimeDescriptorPreparation.Kind.INSTALLED, source)
            }
        }
        assertEquals(6, reads.get())
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-installed calls=4"))
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-installed parses=4"))
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-expected calls=2"))
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-expected parses=2"))
    }

    @Test
    fun stableAndSelectedSameBootstrapShareOnlyTheOuterScope() {
        val preparation = RuntimeDescriptorPreparation()
        val lines = ArrayList<String>()
        preparation.open(7, { true }, lines::add).use {
            preparation.open(7, { true }, lines::add).use {
                preparation.descriptor(RuntimeDescriptorPreparation.Kind.EXPECTED) { bytes() }
            }
            assertTrue(lines.isEmpty())
        }
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-expected calls=1"))
        assertEquals(8, lines.size)
    }

    @Test
    fun anotherThreadDoesNotBorrowTheStartupInvocation() {
        val preparation = RuntimeDescriptorPreparation()
        val lines = ArrayList<String>()
        preparation.open(7, { true }, lines::add).use {
            val worker = Thread {
                preparation.descriptor(RuntimeDescriptorPreparation.Kind.EXPECTED) { bytes() }
            }
            worker.start()
            worker.join()
        }
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-expected calls=0"))
    }

    @Test
    fun failureAndBrokenObserverKeepTheirOriginalMeaning() {
        val preparation = RuntimeDescriptorPreparation()
        preparation.open(7, { true }, { throw IllegalStateException("observer") }).use {
            val error =
                assertThrows(IOException::class.java) {
                    preparation.descriptor(RuntimeDescriptorPreparation.Kind.INSTALLED) {
                        throw IOException("read")
                    }
                }
            assertEquals("read", error.message)
            assertThrows(IOException::class.java) {
                preparation.descriptor(RuntimeDescriptorPreparation.Kind.INSTALLED) {
                    byteArrayOf()
                }
            }
        }
        assertNotNull(
            preparation.descriptor(RuntimeDescriptorPreparation.Kind.EXPECTED) { bytes() }
        )
    }
}
