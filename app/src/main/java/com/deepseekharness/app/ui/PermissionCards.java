package com.deepseekharness.app.ui;

import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;

/** 通知、电池优化、存储、无障碍、悬浮窗五张卡片的文案与按钮（状态原文尽量沿用现有页面）。 */
final class PermissionCards {
  private PermissionCards() {}

  /** s 的顺序见 PermissionPanel.states()。 */
  static PermissionCard.Model model(PermissionPanel panel, String id, boolean[] s) {
    switch (id) {
      case PermissionGoals.NOTIFY:
        return notify(panel, s[4]);
      case PermissionGoals.BATTERY:
        return battery(panel, s[6]);
      case PermissionGoals.STORAGE:
        return storage(panel, s[0]);
      case PermissionGoals.A11Y:
        return accessibility(panel);
      default:
        return overlay(panel, s[5]);
    }
  }

  private static PermissionCard.Model base(int icon, String title, String why, String... unlocks) {
    PermissionCard.Model m = new PermissionCard.Model();
    m.icon = icon;
    m.title = title;
    m.why = why;
    m.unlocks = unlocks;
    return m;
  }

  private static void state(PermissionCard.Model m, boolean ok, String okPill, String offPill) {
    m.ok = ok;
    m.pill = ok ? okPill : offPill;
    m.tone = ok ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.NEUTRAL;
  }

  private static PermissionCard.Model notify(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_bell,
            UiText.choose("通知", "Notifications"),
            UiText.choose(
                "部署与维护进度、手机任务的停止按钮、更新下载会显示在通知栏。",
                "Deployment and maintenance progress, the phone-task stop button and update downloads appear in notifications."),
            UiText.choose("部署进度", "Deployment progress"),
            UiText.choose("手机任务停止按钮", "Phone-task stop button"),
            UiText.choose("更新下载", "Update downloads"));
    boolean exhausted = panel.notificationRequestExhausted();
    // Android 11 / 12 没有运行时申请框：未开启只表示通知总开关被关闭，只能去系统设置打开。
    boolean settingsOnly = !PermissionPanel.notificationPromptAvailable();
    state(
        m,
        ok,
        UiText.choose("已开启", "On"),
        exhausted && !ok ? UiText.choose("已拒绝", "Denied") : UiText.choose("未开启", "Off"));
    if (!ok && exhausted) m.tone = OnboardingKit.Tone.WARN;
    m.status =
        ok
            ? UiText.choose(
                "已允许通知 · 部署进度和手机任务停止按钮会显示在通知栏",
                "Notifications allowed · deployment progress and the phone-task stop button appear in notifications")
            : settingsOnly
                ? UiText.choose(
                    "通知已关闭 · 请在系统通知设置中允许，部署进度和手机任务停止按钮才会显示在通知栏",
                    "Notifications are off · allow them in system notification settings to see deployment progress and the phone-task stop button")
                : exhausted
                    ? UiText.choose(
                        "系统不会再弹出申请框；如需开启，请在系统通知设置中允许。",
                        "Android will not ask again. To enable, allow notifications in system settings.")
                    : UiText.choose(
                        "未允许 · 部署进度和手机任务停止按钮不会显示在通知栏",
                        "Not allowed · deployment progress and the phone-task stop button will not appear");
    m.outside =
        settingsOnly
            ? UiText.choose(
                "打开系统通知设置，允许 DSH-A-Su 发送通知，并可打开“手机任务状态”类别。",
                "Open system notification settings, allow notifications for DSH-A-Su, and optionally enable the “Phone task status” category.")
            : UiText.choose(
                "Android 13+ 会弹出系统权限框；拒绝后只能在系统通知设置中开启，并可在其中打开“手机任务状态”类别。",
                "Android 13+ shows a system prompt; after denial, enable it in system notification settings, including the “Phone task status” category.");
    m.actions =
        new View[] {
          ok
              ? kit.ghost(
                  UiText.choose("系统通知设置", "Notification settings"),
                  v -> PermissionActions.openNotificationSettings(a))
              : exhausted || settingsOnly
                  ? kit.small(
                      UiText.choose("去系统通知设置", "Open notification settings"),
                      true,
                      v -> PermissionActions.openNotificationSettings(a))
                  : kit.small(
                      UiText.choose("允许通知", "Allow notifications"),
                      true,
                      v -> panel.requestNotifications())
        };
    return m;
  }

  private static PermissionCard.Model battery(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_battery,
            UiText.choose("电池优化", "Battery optimization"),
            UiText.choose(
                "减少系统在后台结束 Web、终端与保活服务。",
                "Reduces how often Android stops Web, the terminal and keep-alive services in the background."),
            UiText.choose("后台 Web 少被中断", "Fewer background Web interruptions"),
            UiText.choose("ADB 保活更稳定", "Steadier ADB keep-alive"));
    var battery = PermissionActions.batteryState(a);
    boolean restricted =
        battery == com.deepseekharness.app.util.BatteryBackgroundState.State.RESTRICTED;
    m.ok = ok;
    m.pill =
        ok
            ? UiText.choose("不受限制", "Unrestricted")
            : restricted
                ? UiText.choose("后台受限", "Restricted")
                : UiText.choose("未确认", "Unconfirmed");
    m.tone =
        ok
            ? OnboardingKit.Tone.GOOD
            : restricted ? OnboardingKit.Tone.WARN : OnboardingKit.Tone.NEUTRAL;
    m.status =
        ok
            ? UiText.choose(
                "已在系统电池优化豁免名单中。厂商的后台管理仍可能单独暂停应用。",
                "On the system battery-optimization exemption list. Vendor background managers may still pause the app separately.")
            : restricted
                ? UiText.choose(
                    "系统显示已限制 DSH-A-Su 的后台活动：后台 Web、终端与保活很可能被结束。",
                    "Android reports that background activity is restricted for DSH-A-Su; background Web, the terminal and keep-alive will likely be stopped.")
                : UiText.choose(
                    "未在系统电池优化豁免名单中。厂商的“允许后台运行 / 自启动”开关应用无法读取：若已在系统里允许，可忽略本项。",
                    "Not on the battery-optimization exemption list. Vendor “allow background / auto-start” switches cannot be read by apps; if you already allowed them, you can ignore this.");
    m.outside =
        UiText.choose(
            "电池优化：设为“不优化 / 不受限制”后这里会显示已开启。厂商后台开关（如联想“后台运行”）在应用详情 → 电池中设置。DSHA 不会自动申请豁免。",
            "Battery optimization: once set to unrestricted this card shows as on. Vendor background switches (for example Lenovo “Run in background”) are under App info → Battery. DSHA never requests an exemption itself.");
    m.actions =
        new View[] {
          ok
              ? kit.ghost(
                  UiText.choose("电池优化设置", "Battery settings"),
                  v -> PermissionActions.openBattery(a))
              : kit.small(UiText.text("设置电池优化"), true, v -> PermissionActions.openBattery(a)),
          kit.ghost(
              UiText.choose("应用详情（厂商后台开关）", "App info (vendor background switch)"),
              v -> PermissionActions.openAppDetails(a))
        };
    return m;
  }

  private static PermissionCard.Model storage(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_folder,
            UiText.choose("存储读写", "Storage access"),
            UiText.choose(
                "让容器和工作区读写手机共享存储，例如 /sdcard 下的项目。不开也能用，仅限私有目录。",
                "Lets the container and workspace use shared storage such as projects under /sdcard. Without it, only private folders are available."),
            UiText.choose("工作区可建在 /sdcard", "Workspace on /sdcard"),
            UiText.choose("读写下载与文档目录", "Downloads and Documents"));
    state(m, ok, UiText.choose("已开启", "On"), UiText.choose("未开启", "Off"));
    m.status =
        ok
            ? UiText.choose(
                "已开启：可读写系统允许的共享存储；其他应用私有目录仍受系统限制",
                "Allowed: shared storage permitted by Android; other apps' private directories remain restricted")
            : UiText.text("未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件");
    m.outside =
        UiText.choose(
            "打开系统「所有文件访问」页，允许 DSH-A-Su。建议在首次启动 Web 前完成。",
            "Open the system All files access page and allow DSH-A-Su, ideally before the first Web start.");
    if (ok)
      m.note =
          UiText.choose(
              "已在运行的 Web 与终端需重启后才能访问共享存储。",
              "Restart a running Web session or terminal before it can see shared storage.");
    m.actions =
        new View[] {
          ok
              ? kit.ghost(
                  UiText.choose("系统设置", "System settings"),
                  v -> PermissionActions.openAllFilesAccess(a))
              : kit.small(
                  UiText.choose("去开启所有文件访问", "Allow all files access"),
                  true,
                  v -> PermissionActions.openAllFilesAccess(a))
        };
    return m;
  }

  private static PermissionCard.Model accessibility(PermissionPanel panel) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    String state = PermissionActions.accessibilityState(a);
    boolean connected = PermissionActions.accessibilityConnected(a);
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_hand,
            UiText.choose("无障碍（屏幕操作）", "Accessibility (screen control)"),
            UiText.choose(
                "让助手读屏、点按和输入，在对话中使用 Computer Use。",
                "Lets the assistant read the screen, tap and type for Computer Use in conversations."),
            UiText.choose("读屏与点按", "Read and tap the screen"),
            UiText.choose("截屏", "Screenshots"));
    String service = a.getString(R.string.accessibility_service_label);
    if (connected) {
      state(m, true, UiText.choose("已连接", "Connected"), "");
      m.status =
          UiText.choose(
              "已连接，可以在对话中使用 Computer Use。",
              "Connected. Computer Use is available in conversations.");
      m.actions =
          new View[] {
            kit.ghost(
                UiText.choose("无障碍设置", "Accessibility settings"),
                v -> PermissionActions.openAccessibility(a))
          };
    } else if ("YES".equals(state)) {
      state(m, false, "", UiText.choose("等待连接", "Waiting"));
      m.tone = OnboardingKit.Tone.WARN;
      m.status =
          UiText.choose(
              "开关已开启，正在等待系统连接；可重新开关服务。",
              "Enabled, waiting for the system to connect. Try toggling the service.");
      m.actions =
          new View[] {
            kit.small(UiText.text("检查连接"), true, v -> panel.refresh()),
            kit.ghost(
                UiText.choose("重新打开无障碍设置", "Reopen accessibility settings"),
                v -> PermissionActions.openAccessibility(a))
          };
    } else {
      boolean unknown = !"NO".equals(state);
      state(m, false, "", unknown ? UiText.choose("无法确认", "Unknown") : UiText.choose("未开启", "Off"));
      m.status =
          unknown
              ? UiText.choose(
                  "暂时读不到系统无障碍设置，无法确认是否已开启；可打开系统设置查看。",
                  "The accessibility setting cannot be read right now; open system settings to check.")
              : UiText.choose(
                  "未开启 · 开启后可在对话中让助手操作屏幕",
                  "Off · enable it to let the assistant control the screen in conversations");
      m.actions =
          new View[] {
            kit.small(
                UiText.choose("去开启屏幕操作", "Enable screen control"),
                true,
                v -> PermissionActions.openAccessibility(a))
          };
    }
    m.outside =
        String.format(
            java.util.Locale.ROOT,
            UiText.choose(
                "打开系统无障碍设置，开启「%s」。Android 13+ 若提示“受限设置”，先在应用详情右上角菜单中允许。",
                "Open accessibility settings and turn on “%s”. On Android 13+, if restricted settings are reported, allow them from the app-info menu first."),
            service);
    return m;
  }

  private static PermissionCard.Model overlay(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_layers,
            UiText.choose("悬浮窗", "Display over other apps"),
            UiText.choose(
                "显示流式悬浮条、命令批准和手机任务状态窗。",
                "Shows the floating status bar, command approvals and the phone-task status window."),
            UiText.choose("流式悬浮条", "Floating status"),
            UiText.choose("手机任务状态窗", "Phone-task window"));
    state(m, ok, UiText.choose("已允许", "Allowed"), UiText.choose("未允许", "Not allowed"));
    m.status =
        ok
            ? UiText.choose("已允许显示在其他应用上层", "Allowed to display over other apps")
            : UiText.choose(
                "未允许 · 悬浮条、命令批准和手机任务状态窗不可用",
                "Not allowed · floating status, approvals and the phone-task window are unavailable");
    m.outside =
        UiText.choose(
            "Android 11+ 进入的是“显示在其他应用上层”列表：找到 DSH-A-Su 并允许，然后返回。",
            "Android 11+ opens the Display over other apps list: find DSH-A-Su, allow it, then return.");
    m.note =
        UiText.choose(
            "允许系统权限不会自动打开悬浮条；悬浮条开关仍在 设置 → 悬浮条。",
            "Granting the permission does not turn on the floating status; that switch stays in Settings → Floating status.");
    m.actions =
        new View[] {
          ok
              ? kit.ghost(
                  UiText.choose("系统设置", "System settings"), v -> PermissionActions.openOverlay(a))
              : kit.small(
                  UiText.choose("去允许悬浮窗", "Allow overlay"),
                  true,
                  v -> PermissionActions.openOverlay(a))
        };
    return m;
  }
}
