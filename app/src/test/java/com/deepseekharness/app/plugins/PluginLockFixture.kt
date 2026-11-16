package com.deepseekharness.app.plugins

import java.util.Base64

/** 隔离竞争夹具的 JVM 端；编译真实生产适配器，不复制锁实现。 */
object PluginLockFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = System.`in`.bufferedReader()
        val fields = linkedMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: error("CONTEXT_EOF")
            if (line == "END") break
            val key = line.substringBefore('=')
            check(!fields.containsKey(key) && fields.size < 128)
            fields[key] =
                String(Base64.getDecoder().decode(line.substringAfter('=')), Charsets.UTF_8)
        }
        val bindings =
            (0 until fields.getValue("bindingCount").toInt()).map {
                PluginInvocationContext.Binding(
                    fields.getValue("binding.$it.host"),
                    fields.getValue("binding.$it.guest"),
                )
            }
        val budgets =
            PluginInvocationContext.Stage.entries.associateWith {
                fields.getValue("budget.${it.name}").toLong()
            }
        val context =
            PluginInvocationContext(
                fields.getValue("hostRoot"),
                fields.getValue("hostData"),
                fields.getValue("rootIdentity"),
                fields.getValue("dataIdentity"),
                bindings,
                fields.getValue("guestHome"),
                fields.getValue("profile"),
                fields.getValue("startupId"),
                fields.getValue("operationId"),
                fields.getValue("generation").toLong(),
                fields.getValue("cancelRevision").toLong(),
                fields.getValue("toolsSha256"),
                budgets,
                fields.getValue("taskId"),
            )
        check(context.fingerprint() == fields.getValue("fingerprint")) { "CONTEXT_FINGERPRINT" }
        PluginGuestLockAdapter().acquireProbe(context, args.toList(), 6000).use { lease ->
            println(lease.receipt)
            System.out.flush()
            input.readLine()
        }
        println("RELEASED")
    }
}
