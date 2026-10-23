package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Remote mode runs without starting or retaining the local guest/Web/bridge service. */
public final class AssistantService extends Service {
  private record Pending(AssistantBinding binding, AssistantBackend backend) {}
  private static final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
  private AssistantSessionController controller;
  private volatile AssistantWindow window;
  private AssistantConfirmationWindow confirmations;
  private static volatile AssistantService live;
  /** Called on the remote dispatcher worker before publishing a new phone task lease. */
  public static boolean ensureRemoteTask(Context context,
      com.deepseekharness.app.remote.RemoteSessionIdentity identity, String taskSessionId, String turnId) {
    if (!android.provider.Settings.canDrawOverlays(context)) return false;
    AssistantBinding binding = new AssistantBinding(AssistantBinding.Target.REMOTE, identity.hostInstanceId,
        taskSessionId, identity.connectionEpoch, identity.generation, identity);
    var current = AssistantSessionController.active();
    if (current != null && current.snapshot().binding().equals(binding))
      return matchesTask(current, binding, turnId) && live != null && live.window != null && live.window.visible();
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return false;
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
      try { bind(context, binding, new RemoteAssistantBackend(context, identity)); }
      catch (RuntimeException unavailable) { }
      finally { ready.countDown(); }
    });
    try {
      if (!ready.await(2, java.util.concurrent.TimeUnit.SECONDS)) return false;
      long until = android.os.SystemClock.elapsedRealtime() + 2000;
      do {
        current = AssistantSessionController.active();
        if (matchesTask(current, binding, turnId) && live != null && live.window != null && live.window.visible()) return true;
        Thread.sleep(25);
      } while (android.os.SystemClock.elapsedRealtime() < until);
    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    return false;
  }
  private static boolean matchesTask(AssistantSessionController current, AssistantBinding binding, String turnId) {
    if (current == null) return false;
    var status = current.snapshot();
    return status.binding().equals(binding) && turnId != null && !turnId.isBlank()
        && turnId.equals(status.turnId()) && status.phase() == AssistantSessionState.Phase.RUNNING;
  }
  public static void bind(Context context, AssistantBinding binding, AssistantBackend backend) {
    String key = UUID.randomUUID().toString(); pending.put(key, new Pending(binding, backend));
    Intent intent = new Intent(context, AssistantService.class).putExtra("binding", key);
    try {
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    } catch (RuntimeException failure) { pending.remove(key); throw failure; }
  }
  public static void startRemote(Context context) {
    context.startActivity(new Intent(context, AssistantActivity.class).putExtra("target", "REMOTE")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }
  public static void startLocal(Context context, String sessionId) {
    context.startActivity(new Intent(context, AssistantActivity.class).putExtra("target", "LOCAL")
        .putExtra("sessionId", sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }
  @Override public void onCreate() {
    super.onCreate();
    live = this;
    var manager = getSystemService(NotificationManager.class);
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(
        "floating-assistant", UiText.choose("悬浮助手", "Floating assistant"), NotificationManager.IMPORTANCE_LOW));
    Intent open = new Intent(this, AssistantActivity.class).putExtra("bound", true);
    Intent stop = new Intent(this, AssistantService.class).setAction("STOP_TASK");
    Notification.Builder note = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "floating-assistant") : new Notification.Builder(this);
    note.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(UiText.choose("DSHA 悬浮助手", "DSHA floating assistant"))
        .setContentText(UiText.choose("查看绑定会话；暂停或停止当前任务", "View conversation; pause or stop the current task"))
        .setContentIntent(PendingIntent.getActivity(this, 1951, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
        .addAction(new Notification.Action.Builder(null, UiText.choose("停止任务", "Stop task"), PendingIntent.getService(this, 1952, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build()).setOngoing(true);
    startForeground(com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT, note.build());
    confirmations = new AssistantConfirmationWindow(this);
    PhoneControlCoordinator.get(this).setConfirmationHandler(confirmations::show);
  }
  @Override public int onStartCommand(Intent intent, int flags, int id) {
    if (intent == null) { stopSelf(); return START_NOT_STICKY; }
    if ("STOP_TASK".equals(intent.getAction())) { if (controller != null) controller.stop(); return START_NOT_STICKY; }
    String key = intent.getStringExtra("binding");
    Pending selection = key == null ? null : pending.remove(key);
    if (selection == null) { if (controller == null) stopSelf(); return START_NOT_STICKY; }
    if (window != null) window.close();
    if (controller != null) controller.close();
    controller = new AssistantSessionController(this, selection.binding, selection.backend);
    com.deepseekharness.app.OverlayController.teardown(this);
    window = new AssistantWindow(this, controller); window.show();
    return START_NOT_STICKY;
  }
  @Override public void onDestroy() {
    if (live == this) live = null;
    if (window != null) window.close();
    if (controller != null) controller.close();
    if (confirmations != null) confirmations.close();
    PhoneControlCoordinator.get(this).setConfirmationHandler(null);
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent) { return null; }
}
