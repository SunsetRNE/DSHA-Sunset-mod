package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.module.ModuleExec;
import com.deepseekharness.app.module.ModuleScheduler;
import com.deepseekharness.app.module.ModuleState;
import com.deepseekharness.app.util.UiText;

/**
 * 模块定时任务触发器：到点先登记下一天，再在后台线程执行。
 *
 * <p>执行与手点按钮走同一道门：这张卡片的执行授权没开，任务只发「被跳过」通知。
 * 通知带结果摘要（任务身份 + 命令输出截断、启停结果），用户能知道任务到底干了什么。
 *
 * <p>容器启停调用 {@code HarnessController} 的生命周期入口（{@code startWeb/stopWeb}），
 * 与主页按钮完全同路；不按裸 PID 或端口猜杀任何进程。
 * {@code startWeb} 返回的是「启动请求已被受理」，真正的启动在宿主自己的后台流水线上继续；
 * {@code stopWeb} 同样只是排队停止，所以通知说的是「请求已提交」，不谎称已完成。
 */
public class ModuleScheduleReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !"dsha.module.SCHEDULE_FIRE".equals(intent.getAction())) return;
    String id = intent.getStringExtra("id");
    if (id == null || id.isEmpty()) return;
    Context app = context.getApplicationContext();
    ModuleScheduler.Entry entry = ModuleScheduler.byId(app, id);
    if (entry == null) return;
    // 先把明天的闹钟排上，再管这次执行；执行失败也不影响明天的任务。
    // 模块已被移除时，执行线程里会把这条任务连记录一起撤掉（含刚排上的明天）。
    ModuleScheduler.armNextDay(app, entry);
    final PendingResult result = goAsync();
    final PowerManager.WakeLock wakeLock = acquireWakeLock(app);
    new Thread(
            () -> {
              try {
                run(app, entry);
              } catch (Throwable error) {
                notifyResult(
                    app,
                    entry,
                    false,
                    UiText.format(
                        UiText.choose("执行出错：%s", "Task failed: %s"),
                        error.getMessage() == null ? error.toString() : error.getMessage()));
              } finally {
                result.finish();
                releaseWakeLock(wakeLock);
              }
            },
            "dsha-module-schedule")
        .start();
  }

  /** 后台任务持有短时 CPU 锁，避免熄屏/Doze 期间刚启动就被挂起。 */
  private static PowerManager.WakeLock acquireWakeLock(Context context) {
    try {
      PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
      if (power == null) return null;
      PowerManager.WakeLock lock =
          power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsha:module-schedule");
      lock.setReferenceCounted(false);
      lock.acquire(10 * 60 * 1000L);
      return lock;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void releaseWakeLock(PowerManager.WakeLock lock) {
    if (lock == null) return;
    try {
      if (lock.isHeld()) lock.release();
    } catch (RuntimeException ignored) {
      // 已释放或系统拒绝
    }
  }

  /** 包内可见以便单测直接验证授权门与清理，不必走 goAsync 线程。 */
  static void run(Context context, ModuleScheduler.Entry entry) {
    // 模块已被移除：撤销这条任务并安静退出，不向用户残留一条指向不存在模块的通知。
    if (ModuleScheduler.dropIfCardMissing(context, entry)) return;
    // 顺手清一次其它已移除模块的孤儿任务；清单读不出来时它自己会一条不动。
    ModuleScheduler.dropOrphans(context);
    // 开关关闭的模块不执行定时任务；保留登记，用户重新打开后仍按原计划运行。
    if (!ModuleState.isEnabled(context, entry.cardId)) {
      notifyResult(
          context,
          entry,
          false,
          UiText.choose("模块已关闭，任务已跳过。", "Module is disabled; task skipped."));
      return;
    }
    if (!ModuleExec.isAllowed(context, entry.cardId)) {
      notifyResult(
          context,
          entry,
          false,
          UiText.choose(
              "这张卡片没有执行授权，任务已跳过。回主页点一次执行类按钮可授权。",
              "This card has no execution permission; task skipped. Tap an execution button on the home card once to grant it."));
      return;
    }
    switch (entry.kind) {
      case "exec" -> {
        ModuleExec.Result result = ModuleExec.run(context, entry.value);
        String output = result.output;
        if (output.length() > 300) output = output.substring(0, 300) + "…";
        notifyResult(
            context,
            entry,
            result.ok,
            (result.ok ? UiText.choose("完成：", "Done: ") : UiText.choose("失败：", "Failed: "))
                + output);
      }
      case "dsh.start" -> {
        HarnessController controller = HarnessController.get(context);
        StringBuilder status = new StringBuilder();
        boolean admitted = controller.startWeb(msg -> status.append(msg).append(' '));
        notifyResult(
            context,
            entry,
            admitted,
            (admitted
                    ? UiText.choose(
                        "容器启动请求已受理，结果见主页。", "Container start admitted; see the home page.")
                    : UiText.choose("启动未受理。", "Start was not admitted. "))
                + status);
      }
      case "dsh.stop" -> {
        HarnessController controller = HarnessController.get(context);
        StringBuilder status = new StringBuilder();
        controller.stopWeb(
            msg -> {
              status.append(msg).append(' ');
            });
        notifyResult(
            context,
            entry,
            true,
            UiText.format(
                UiText.choose("容器停止请求已提交。%s", "Container stop request submitted. %s"), status));
      }
      default ->
          notifyResult(
              context,
              entry,
              false,
              UiText.format(UiText.choose("未知任务类型：%s", "Unknown task kind: %s"), entry.kind));
    }
  }

  private static void notifyResult(
      Context context, ModuleScheduler.Entry entry, boolean ok, String text) {
    // 先落盘：通知权限被拒绝时用户仍能在模块中心看到最近一次结果。
    ModuleScheduler.recordResult(context, entry, ok, text);
    try {
      NotificationManager manager =
          (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager == null) return;
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        NotificationChannel channel =
            new NotificationChannel(
                ModuleScheduler.NOTIFICATION_CHANNEL,
                UiText.choose("模块定时任务", "Module schedules"),
                NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
      }
      Notification.Builder builder =
          android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
              ? new Notification.Builder(context, ModuleScheduler.NOTIFICATION_CHANNEL)
              : new Notification.Builder(context);
      // 标题带任务身份（名字 + 时间 + 动作），用户不用点开就知道是哪条任务干了什么。
      String identity =
          entry.label
              + "（"
              + String.format(java.util.Locale.ROOT, "%02d:%02d", entry.hour, entry.minute)
              + " "
              + entry.kind
              + "）";
      builder
          .setSmallIcon(android.R.drawable.stat_notify_more)
          .setContentTitle(
              UiText.format(UiText.choose("定时任务 · %s", "Scheduled task · %s"), identity))
          .setContentText(text)
          .setStyle(new Notification.BigTextStyle().bigText(text))
          .setAutoCancel(true);
      manager.notify((int) (entry.id.hashCode() & 0x7fffffffL), builder.build());
    } catch (Throwable ignored) {
      // 通知失败不影响任务本身
    }
  }
}
