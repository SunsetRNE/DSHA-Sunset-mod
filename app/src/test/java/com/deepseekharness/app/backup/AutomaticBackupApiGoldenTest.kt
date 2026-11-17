package com.deepseekharness.app.backup

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.SharedPreferences
import com.deepseekharness.app.util.AutomaticBackupPolicy
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.time.ZoneId
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test

/** 冻结 Java 调用方可见的名称、static 入口、返回值和 checked exception；允许 Kotlin 编译器的合成成员。 */
class AutomaticBackupApiGoldenTest {
    private fun method(
        owner: Class<*>,
        name: String,
        result: Class<*>,
        vararg args: Class<*>,
    ): java.lang.reflect.Method {
        val method = owner.getDeclaredMethod(name, *args)
        assertEquals("$name return", result, method.returnType)
        assertTrue("$name static", Modifier.isStatic(method.modifiers))
        if (
            owner != AutomaticBackups::class.java ||
                name !in
                    setOf(
                        "factoryResetSuspended",
                        "factoryResetPending",
                        "beginProducer",
                        "recordError",
                        "idleForOwner",
                    )
        ) {
            assertTrue(
                "$name remains callable from other Java packages",
                Modifier.isPublic(method.modifiers),
            )
        }
        return method
    }

    @Test
    fun automaticBackupsRetainsItsJavaStaticSurface() {
        val owner = AutomaticBackups::class.java
        System.getProperty("dsha.t14.compiled")?.let { compiled ->
            for (type in
                listOf(
                    owner,
                    AutomaticBackupService::class.java,
                    AutomaticBackupPruner::class.java,
                    AutomaticBackupPolicy::class.java,
                )) {
                assertEquals(
                    "must load freshly compiled ${type.name}",
                    java.io.File(compiled).canonicalFile,
                    java.io.File(type.protectionDomain.codeSource.location.toURI()).canonicalFile,
                )
            }
        }
        val context = Context::class.java
        val nothing = Void.TYPE
        assertTrue(Modifier.isFinal(owner.modifiers))
        assertEquals(0, owner.constructors.size)
        assertTrue(
            owner.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) }
        )
        assertEquals(9137, owner.getField("JOB").getInt(null))
        assertEquals(9138, owner.getField("NOW").getInt(null))
        for (name in listOf("JOB", "NOW")) {
            val modifiers = owner.getField(name).modifiers
            assertTrue(
                Modifier.isPublic(modifiers) &&
                    Modifier.isStatic(modifiers) &&
                    Modifier.isFinal(modifiers)
            )
        }
        method(owner, "prefs", SharedPreferences::class.java, context)
        for (name in listOf("enabled", "due")) method(
            owner,
            name,
            Boolean::class.javaPrimitiveType!!,
            context,
        )
        for (name in
            listOf("schedule", "stopped", "abortFactoryReset", "completeFactoryReset")) method(
            owner,
            name,
            nothing,
            context,
        )
        method(owner, "request", nothing, context, Boolean::class.javaPrimitiveType!!)
        method(owner, "completed", nothing, context, String::class.java)
        for (name in listOf("idle", "idleForOwner")) assertArrayEquals(
            arrayOf(IOException::class.java),
            method(owner, name, Boolean::class.javaPrimitiveType!!, context).exceptionTypes,
        )
        assertArrayEquals(
            arrayOf(IOException::class.java),
            method(owner, "password", CharArray::class.java, context).exceptionTypes,
        )
        for (name in listOf("prune", "suspendForFactoryReset")) assertArrayEquals(
            arrayOf(IOException::class.java),
            method(owner, name, nothing, context, BackupControl::class.java).exceptionTypes,
        )
        method(owner, "factoryResetSuspended", Boolean::class.javaPrimitiveType!!)
        method(owner, "factoryResetPending", Boolean::class.javaPrimitiveType!!, context)
        method(owner, "beginProducer", WorkerQuiescence.Lease::class.java)
        method(owner, "recordError", nothing, context, String::class.java)
    }

    @Test
    fun serviceKeepsItsFrameworkConstructorAndOverrides() {
        val owner = AutomaticBackupService::class.java
        assertEquals(JobService::class.java, owner.superclass)
        assertTrue(Modifier.isFinal(owner.modifiers))
        assertTrue(Modifier.isPublic(owner.getConstructor().modifiers))
        for (name in listOf("onStartJob", "onStopJob")) {
            val method = owner.getMethod(name, JobParameters::class.java)
            assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
            assertFalse(Modifier.isStatic(method.modifiers))
        }
        assertEquals(Void.TYPE, owner.getMethod("onDestroy").returnType)
    }

    @Test
    fun realJavaCallerCompilesAgainstTheFrozenStaticSurface() {
        val root = Files.createTempDirectory("automatic-backup-java-caller-")
        try {
            val source = root.resolve("AutomaticBackupJavaCaller.java")
            Files.writeString(
                source,
                """
                package com.deepseekharness.app.backup;
                import android.content.Context;
                import android.content.SharedPreferences;
                import com.deepseekharness.app.util.AutomaticBackupPolicy;
                import java.io.IOException;
                import java.util.List;
                final class AutomaticBackupJavaCaller {
                  static void use(Context context, BackupControl control) throws IOException {
                    int job = AutomaticBackups.JOB, now = AutomaticBackups.NOW;
                    SharedPreferences preferences = AutomaticBackups.prefs(context);
                    boolean enabled = AutomaticBackups.enabled(context);
                    boolean due = AutomaticBackups.due(context);
                    boolean idle = AutomaticBackups.idle(context);
                    boolean owned = AutomaticBackups.idleForOwner(context);
                    boolean suspended = AutomaticBackups.factoryResetSuspended();
                    boolean pending = AutomaticBackups.factoryResetPending(context);
                    WorkerQuiescence.Lease lease = AutomaticBackups.beginProducer();
                    AutomaticBackups.recordError(context, null);
                    AutomaticBackups.schedule(context);
                    AutomaticBackups.stopped(context);
                    AutomaticBackups.request(context, true);
                    char[] password = AutomaticBackups.password(context);
                    AutomaticBackups.completed(context, null);
                    AutomaticBackups.prune(context, control);
                    AutomaticBackups.suspendForFactoryReset(context, control);
                    AutomaticBackups.abortFactoryReset(context);
                    AutomaticBackups.completeFactoryReset(context);
                    AutomaticBackupPruner.prune(null, List.of(), control);
                    boolean policy = AutomaticBackupPolicy.due(null, 24, 180, 0, 0, 0, 0, null);
                    AutomaticBackupService service = new AutomaticBackupService();
                  }
                }
                """
                    .trimIndent(),
            )
            val classpath =
                listOf(
                        AutomaticBackups::class.java,
                        AutomaticBackupPolicy::class.java,
                        BackupControl::class.java,
                        Context::class.java,
                        kotlin.Unit::class.java,
                    )
                    .map {
                        java.io.File(it.protectionDomain.codeSource.location.toURI()).absolutePath
                    }
                    .distinct()
                    .joinToString(java.io.File.pathSeparator)
            val diagnostics = java.io.ByteArrayOutputStream()
            val result =
                ToolProvider.getSystemJavaCompiler()
                    .run(
                        null,
                        diagnostics,
                        diagnostics,
                        "-proc:none",
                        "-classpath",
                        classpath,
                        "-d",
                        root.toString(),
                        source.toString(),
                    )
            assertEquals(diagnostics.toString("UTF-8"), 0, result)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    @Test
    fun policyAndPrunerKeepStaticDescriptors() {
        val policy = AutomaticBackupPolicy::class.java
        assertEquals(0, policy.constructors.size)
        method(
            policy,
            "due",
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
            Integer.TYPE,
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            ZoneId::class.java,
        )
        val pruner = AutomaticBackupPruner::class.java
        assertEquals(0, pruner.constructors.size)
        assertArrayEquals(
            arrayOf(IOException::class.java),
            method(
                    pruner,
                    "prune",
                    Void.TYPE,
                    BackupFileSystem::class.java,
                    List::class.java,
                    BackupControl::class.java,
                )
                .exceptionTypes,
        )
    }
}
