package com.deepseekharness.app.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 通知只打开原票据指向的现存呈现，不创建会话，也不回退到后来选中的任务。 */
internal object AssistantNotificationNavigation {
    @JvmStatic
    fun openIntent(context: Context, ticket: AssistantNotificationTicket?): Intent {
        val intent = Intent(context, AssistantActivity::class.java)
        if (ticket != null) {
            intent.putExtra("bound", true)
            intent.data = Uri.parse(ticket.forAction("OPEN").identity())
        }
        return intent
    }

    @JvmStatic
    fun voiceIntent(context: Context, origin: Intent): Intent {
        val intent = Intent(context, AssistantVoiceActivity::class.java)
        if (requested(origin)) {
            intent.data = origin.data
            intent.putExtra("bound", origin.getBooleanExtra("bound", false))
        }
        return intent
    }

    @JvmStatic fun requested(intent: Intent): Boolean = intent.data != null

    @JvmStatic
    fun resolve(context: Context, intent: Intent): AssistantSessionController? {
        if (!intent.getBooleanExtra("bound", false)) return null
        val address = intent.dataString ?: return null
        val owner = AssistantWindowOwner.from(context)
        return owner.service()?.notificationTarget(address)
    }

    @JvmStatic
    fun send(
        context: Context,
        intent: Intent,
        selected: AssistantSessionController,
        message: String,
    ): Boolean =
        synchronized(selected) {
            if (!requested(intent)) return@synchronized selected.send(message)
            if (resolve(context, intent) !== selected) return@synchronized false
            val snapshot = selected.snapshot()
            selected.sendFromNotification(message, snapshot.binding(), snapshot.turnId())
        }

    @JvmStatic
    fun resume(context: Context, intent: Intent, selected: AssistantSessionController): Boolean =
        synchronized(selected) {
            if (!requested(intent)) return@synchronized selected.resume()
            if (resolve(context, intent) !== selected) return@synchronized false
            val snapshot = selected.snapshot()
            selected.resumeFromNotification(snapshot.binding(), snapshot.turnId())
        }
}
