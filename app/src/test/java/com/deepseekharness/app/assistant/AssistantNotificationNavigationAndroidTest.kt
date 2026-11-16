package com.deepseekharness.app.assistant

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import com.deepseekharness.app.DshaApp
import com.deepseekharness.app.R
import com.deepseekharness.app.control.PhoneControlCoordinator
import com.deepseekharness.app.core.HarnessController
import com.deepseekharness.app.util.NotificationIds
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPendingIntent
import org.robolectric.shadows.ShadowSettings

/** 真通知和 Activity 接收端反例；后端端口隔离，不启动 guest 或申请设备权限。 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = AssistantNotificationNavigationAndroidTest.App::class,
    shadows = [AssistantNotificationNavigationAndroidTest.NoRuntime::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantNotificationNavigationAndroidTest {
    class App : DshaApp() {
        override fun onCreate() {}
    }

    @Implements(value = HarnessController::class, isInAndroidSdk = false)
    class NoRuntime {
        companion object {
            var restores = 0

            @JvmStatic
            @Implementation
            fun get(context: Context): HarnessController =
                Shadow.newInstanceOf(HarnessController::class.java)
        }

        @Implementation fun addReadyWebPageListener(listener: Runnable) {}

        @Implementation fun removeReadyWebPageListener(listener: Runnable) {}

        @Implementation
        fun restoreRunningWebSession(listener: Runnable) {
            restores++
        }
    }

    private class Host(val binding: AssistantBinding) : AssistantBackend {
        @Volatile var turn = "17"
        val calls = Collections.synchronizedList(mutableListOf<String>())

        override fun current() = true

        override fun call(method: String, args: JSONObject): JSONObject {
            calls.add(method)
            assertEquals(binding.hostInstanceId(), args.optString("hostInstanceId"))
            assertEquals(binding.sessionId(), args.optString("sessionId"))
            assertTrue(
                "导航不得创建、发送或改选会话：$method",
                method == "sessions.status" || method == "sessions.cancel",
            )
            return JSONObject()
                .put("hostInstanceId", binding.hostInstanceId())
                .put("sessionId", binding.sessionId())
                .put("taskId", binding.sessionId())
                .put("turnId", turn)
                .put("turnActive", true)
                .put("running", true)
        }

        override fun returnConversation(binding: AssistantBinding) {}
    }

    private lateinit var app: App
    private lateinit var service: ServiceController<AssistantService>
    private val activities = mutableListOf<ActivityController<AssistantActivity>>()

    private fun address(session: String) = AssistantBinding("host", session, "connection", 4)

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun idle() = Shadow.extract<ShadowLooper>(Looper.getMainLooper()).idle()

    private fun drain(selected: AssistantSessionController) {
        (field(selected, "worker") as ScheduledExecutorService).submit {}.get(2, TimeUnit.SECONDS)
        idle()
    }

    private fun attach(host: Host): AssistantSessionController {
        AssistantService.bind(app, host.binding, host)
        val intent = Shadow.extract<ShadowApplication>(app).nextStartedService
        assertNotNull(intent)
        service.get().onStartCommand(intent, 0, 1)
        val selected = AssistantWindowOwner.from(app).current()!!
        drain(selected)
        return selected
    }

    private fun openIntent(): Intent {
        val manager = app.getSystemService(NotificationManager::class.java)
        val note =
            Shadow.extract<ShadowNotificationManager>(manager)
                .getNotification(NotificationIds.FLOATING_ASSISTANT)
        assertNotNull(note.contentIntent)
        return Intent(Shadow.extract<ShadowPendingIntent>(note.contentIntent).savedIntent)
    }

    private fun open(intent: Intent): ActivityController<AssistantActivity> {
        val activity = Robolectric.buildActivity(AssistantActivity::class.java, Intent(intent))
        activities.add(activity)
        activity.get().setTheme(R.style.Theme_DeepseekHarness)
        activity.setup().visible()
        return activity
    }

    private fun assertRejected(intent: Intent, expected: AssistantSessionController?) {
        val activity = open(intent)
        assertTrue(activity.get().isFinishing)
        assertNull(field(activity.get(), "bound"))
        assertSame(expected, AssistantWindowOwner.from(app).current())
        assertEquals(0, NoRuntime.restores)
    }

    @Before
    fun before() {
        app = RuntimeEnvironment.getApplication() as App
        NoRuntime.restores = 0
        ShadowSettings.setCanDrawOverlays(true)
        Shadow.extract<ShadowApplication>(app)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Shadow.extract<ShadowNotificationManager>(
                app.getSystemService(NotificationManager::class.java)
            )
            .setNotificationsEnabled(true)
        PhoneControlCoordinator.get(app).resume()
        service = Robolectric.buildService(AssistantService::class.java).create()
    }

    @After
    fun after() {
        for (activity in activities) activity.pause().stop().destroy()
        service.destroy()
        AssistantWindowOwner.from(app).current()?.detachFinishedTask()
    }

    @Test
    fun currentContentIntentAndConfigurationRecreationKeepTheExactControllerAndDraft() {
        val selected = attach(Host(address("current-full-session")))
        selected.setDraft("原会话草稿")
        val intent = openIntent()
        val activity = open(intent)
        assertFalse(activity.get().isFinishing)
        assertSame(selected, field(activity.get(), "bound"))
        assertSame(selected, AssistantNotificationNavigation.resolve(app, intent))
        activity.recreate()
        assertSame(selected, field(activity.get(), "bound"))
        assertEquals("原会话草稿", selected.snapshot().draft())
        assertEquals(0, NoRuntime.restores)
    }

    @Test
    fun oldTurnContentIntentCannotOpenTheVerifiedNextTurn() {
        val host = Host(address("same-full-session"))
        val selected = attach(host)
        val old = openIntent()
        host.turn = "18"
        assertTrue(selected.followVerifiedTask("18"))
        drain(selected)
        assertRejected(old, selected)
        assertSame(selected, AssistantNotificationNavigation.resolve(app, openIntent()))
        assertEquals("18", selected.snapshot().turnId())
    }

    @Test
    fun sameShortSuffixDoesNotRetargetAFullSessionNotification() {
        attach(Host(address("prefix-A-same1234")))
        val old = openIntent()
        val selected = attach(Host(address("prefix-B-same1234")))
        assertRejected(old, selected)
        assertSame(selected, AssistantNotificationNavigation.resolve(app, openIntent()))
    }

    @Test
    fun reselectingTheSameFullAddressStillRetiresTheOldOpenEpoch() {
        val host = Host(address("same-address"))
        val previous = attach(host)
        val old = openIntent()
        val selected = attach(Host(host.binding))
        assertNotSame(previous, selected)
        assertNotEquals(old.data, openIntent().data)
        assertRejected(old, selected)
    }

    @Test
    fun malformedMissingBoundAndWrongActionDataCannotSelectTheCurrentTask() {
        val selected = attach(Host(address("wrong-data")))
        val current = openIntent()
        assertRejected(Intent(current).setData(Uri.parse("dsha-assistant://task/broken")), selected)
        assertRejected(Intent(current).putExtra("bound", false), selected)
        assertRejected(
            Intent(current)
                .setData(Uri.parse(current.dataString!!.replace("/OPEN/", "/STOP_TASK/"))),
            selected,
        )
    }

    @Test
    fun staleOnNewIntentFinishesWithoutReplacingTheCurrentEditorBinding() {
        attach(Host(address("old-editor")))
        val old = openIntent()
        val selected = attach(Host(address("new-editor")))
        selected.setDraft("保留后继草稿")
        val activity = open(openIntent())
        activity.newIntent(old)
        assertTrue(activity.get().isFinishing)
        assertSame(selected, field(activity.get(), "bound"))
        assertEquals("保留后继草稿", selected.snapshot().draft())
        assertEquals(0, NoRuntime.restores)
    }

    @Test
    fun predecessorPauseCannotExposeTheReplacementControllersWindow() {
        attach(Host(address("predecessor")))
        val previous = open(openIntent())
        val selected = attach(Host(address("replacement")))
        open(openIntent())
        val window = field(service.get(), "window")!!
        assertEquals(true, field(window, "editorVisible"))
        previous.pause()
        assertSame(selected, AssistantWindowOwner.from(app).current())
        assertEquals(true, field(window, "editorVisible"))
    }

    @Test
    fun replacedEditorViewsCannotWriteDraftOrStopAfterTheSameControllerIsReopened() {
        val host = Host(address("same-controller-editor"))
        val selected = attach(host)
        val activity = open(openIntent())
        val previousInput = field(activity.get(), "input") as EditText
        val actions = field(activity.get(), "buttonContainer") as android.view.ViewGroup
        val previousStop =
            (0 until actions.childCount)
                .map { actions.getChildAt(it) }
                .filterIsInstance<Button>()
                .single { it.text.toString() in listOf("停止当前任务", "Stop current task") }
        // 同一个有效票据再次导航会重建视图；旧 TextWatcher 不能覆写新编辑器。
        activity.newIntent(openIntent())
        val currentInput = field(activity.get(), "input") as EditText
        currentInput.setText("当前草稿")
        previousInput.setText("迟到旧视图")
        previousStop.performClick()
        drain(selected)
        assertEquals("当前草稿", selected.snapshot().draft())
        assertFalse(host.calls.contains("sessions.cancel"))
        activity.pause().resume()
        currentInput.setText("恢复后仍可编辑")
        assertEquals("恢复后仍可编辑", selected.snapshot().draft())
    }

    @Test
    fun sameControllerActivityHandoffKeepsTheSuccessorEditorAndComposeGate() {
        val selected = attach(Host(address("same-controller-handoff")))
        val previous = open(openIntent())
        val successor = open(openIntent())
        val window = field(service.get(), "window")!!
        assertSame(selected, field(successor.get(), "bound"))
        assertTrue(selected.composing())
        previous.pause()
        assertEquals(true, field(window, "editorVisible"))
        assertTrue("前一个 Activity 不能释放后继编辑器的输入闸", selected.composing())
        successor.pause()
        assertEquals(false, field(window, "editorVisible"))
        assertFalse(selected.composing())
    }

    @Test
    fun explicitConversationPickerLeavesTheNotificationScopeAndReleasesOnlyItsEditor() {
        val selected = attach(Host(address("explicit-picker")))
        val activity = open(openIntent())
        val actions = field(activity.get(), "buttonContainer") as android.view.ViewGroup
        val picker =
            (0 until actions.childCount)
                .map { actions.getChildAt(it) }
                .filterIsInstance<Button>()
                .single { it.text.toString() in listOf("选择其他会话", "Choose another conversation") }
        assertTrue(picker.performClick())
        assertNull(activity.get().intent.data)
        assertNull(field(activity.get(), "bound"))
        assertFalse(selected.composing())
        assertEquals(1, NoRuntime.restores)
        activity.pause().resume()
        assertFalse(activity.get().isFinishing)
        activity.recreate()
        assertNull(field(activity.get(), "bound"))
        assertNull(activity.get().intent.data)
        assertFalse(activity.get().intent.getBooleanExtra("bound", false))
    }

    @Test
    fun permissionNavigationReleasesThePreviousEditorWithoutCancellingItsTask() {
        val host = Host(address("permission-after-editor"))
        val selected = attach(host)
        val activity = open(openIntent())
        assertTrue(selected.composing())
        activity.newIntent(AssistantService.permissionIntent(app))
        assertNull(field(activity.get(), "bound"))
        assertFalse(selected.composing())
        assertSame(selected, AssistantWindowOwner.from(app).current())
        assertFalse(host.calls.contains("sessions.cancel"))
    }

    @Test
    fun destroyedServiceNotificationDoesNotRecoverOrCreateAnotherConversation() {
        attach(Host(address("retired-service")))
        val old = openIntent()
        service.destroy()
        assertRejected(old, null)
    }
}
