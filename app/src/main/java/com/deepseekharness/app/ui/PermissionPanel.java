package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 首次引导第 3 步（及后续可复用）的授权卡片组：7 类授权 + ADB，按第 2 步用途标推荐并排序。
 *
 * <p>全部默认关闭、可跳过；外跳只由点击触发；宿主 onResume 调 refresh() 重读系统状态。
 * Root / Shizuku 状态在异步回调后可能稍晚变化，面板在可见期间每秒复查一次，宿主暂停即停止。
 */
final class PermissionPanel {
  static final String PREFS = "dsha-onboarding";
  private static final String NOTIFY_REQUESTS = "notify_requests";
  private static final String NOTIFY_DENIALS = "notify_denials";

  private final AppCompatActivity activity;
  private final OnboardingKit kit;
  private final Set<String> goals;
  private final LinearLayout root;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private boolean environmentReady;
  private String channelTab = PermissionChannelCard.SHIZUKU;
  private String lastKey = "";
  private androidx.activity.result.ActivityResultLauncher<String> notificationRequest;

  PermissionPanel(AppCompatActivity activity, Set<String> goals, boolean environmentReady) {
    this.activity = activity;
    this.kit = new OnboardingKit(activity);
    this.goals = goals == null ? Set.of() : goals;
    this.environmentReady = environmentReady;
    root = kit.column();
    registerNotificationRequest();
    activity
        .getLifecycle()
        .addObserver(
            new androidx.lifecycle.DefaultLifecycleObserver() {
              @Override
              public void onResume(androidx.lifecycle.LifecycleOwner owner) {
                refresh();
                main.removeCallbacks(poll);
                main.postDelayed(poll, 1000);
              }

              @Override
              public void onPause(androidx.lifecycle.LifecycleOwner owner) {
                main.removeCallbacks(poll);
              }

              @Override
              public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                main.removeCallbacks(poll);
                if (notificationRequest != null) notificationRequest.unregister();
              }
            });
    render(true);
  }

  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (activity.isFinishing() || activity.isDestroyed()) return;
          render(false);
          main.postDelayed(this, 1000);
        }
      };

  private void registerNotificationRequest() {
    if (Build.VERSION.SDK_INT < 33) return;
    notificationRequest =
        activity
            .getActivityResultRegistry()
            .register(
                "dsha-onboarding-notifications-" + System.identityHashCode(this),
                new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                this::onNotificationResult);
  }

  /** 返回面板根视图；每次调用都按 goals 的当前内容（宿主可能就地增删）重排推荐与分组。 */
  View view() {
    render(true);
    return root;
  }

  void refresh() {
    render(true);
  }

  void setEnvironmentReady(boolean ready) {
    if (environmentReady == ready) return;
    environmentReady = ready;
    render(true);
  }

  /** 7 项状态：存储、Shizuku / Stellar、Root、无障碍（已连接）、通知、悬浮窗、电池优化。 */
  private boolean[] states() {
    Context c = activity;
    return new boolean[] {
      PermissionActions.storage(),
      PermissionChannelCard.shizukuReady(),
      PermissionChannelCard.rootReady(c),
      PermissionActions.accessibilityConnected(c),
      PermissionActions.notifications(c),
      PermissionActions.overlay(c),
      PermissionActions.batteryUnrestricted(c)
    };
  }

  private static String[] names() {
    return new String[] {
      UiText.choose("存储读写", "Storage"),
      UiText.choose("Shizuku / Stellar", "Shizuku / Stellar"),
      "Root",
      UiText.choose("无障碍", "Accessibility"),
      UiText.choose("通知", "Notifications"),
      UiText.choose("悬浮窗", "Overlay"),
      UiText.choose("电池优化", "Battery optimization")
    };
  }

  int grantedCount() {
    int n = 0;
    for (boolean b : states()) if (b) n++;
    return n;
  }

  List<String[]> summary() {
    boolean[] s = states();
    String[] n = names();
    List<String[]> out = new ArrayList<>();
    for (int i = 0; i < s.length; i++) out.add(new String[] {n[i], s[i] ? "1" : "0"});
    return out;
  }

  /** force=false 时只在状态变化后重绘，避免轮询打断滚动与焦点。 */
  private void render(boolean force) {
    boolean[] s = states();
    String a11y = PermissionActions.accessibilityState(activity);
    String key =
        java.util.Arrays.toString(s)
            + a11y
            + com.deepseekharness.app.ShizukuShell.userStatus(activity)
            + com.deepseekharness.app.RootShell.status(activity)
            + channelTab
            + environmentReady
            + UiText.language()
            + notifyRequests()
            + prefs().getInt(NOTIFY_DENIALS, 0)
            + new java.util.TreeSet<>(goals);
    if (!force && key.equals(lastKey)) return;
    lastKey = key;
    root.removeAllViews();
    root.addView(summaryBar(s));
    Set<String> rec = PermissionGoals.recommended(goals);
    java.util.List<String> recommendedGroups = PermissionGoals.recommendedGroups(goals);
    java.util.List<String> otherGroups = PermissionGoals.otherGroups(goals);
    // 用途决定页面结构：推荐分组展开在前；其余折叠为“其他授权（可选）”，需要时再展开。
    if (recommendedGroups.isEmpty())
      root.addView(
          kit.hint(
              UiText.choose(
                  "你选的用途不需要设备授权，可以直接继续。下面的授权都是可选的，需要时再开。",
                  "Your choices need no device permissions, so you can continue. Everything below is optional."),
              OnboardingKit.Tone.ACCENT),
          kit.topMargin(14));
    for (String group : recommendedGroups) root.addView(group(group, rec, s), kit.topMargin(22));
    if (otherGroups.isEmpty()) return;
    boolean expanded = othersExpanded || recommendedGroups.isEmpty();
    if (!recommendedGroups.isEmpty())
      root.addView(othersToggle(otherGroups.size(), expanded), kit.topMargin(22));
    if (expanded)
      for (String group : otherGroups) root.addView(group(group, rec, s), kit.topMargin(18));
  }

  /** 是否展开“其他授权（可选）”；只影响本次显示，不记忆、不申请任何权限。 */
  private boolean othersExpanded;

  private View othersToggle(int groups, boolean expanded) {
    android.widget.Button toggle =
        kit.ghost(
            expanded
                ? UiText.choose("收起其他授权", "Hide other permissions")
                : String.format(
                    java.util.Locale.ROOT,
                    UiText.choose("其他授权（可选）· %d 组", "Other permissions (optional) · %d groups"),
                    groups),
            v -> {
              othersExpanded = !othersExpanded;
              render(true);
            });
    toggle.setContentDescription(toggle.getText());
    return toggle;
  }

  /** 第 3 / 4 步用：推荐授权中已开启 / 总数，以及尚未开启的推荐卡片名。 */
  int recommendedDone() {
    return PermissionGoals.recommendedDone(goals, states());
  }

  int recommendedTotal() {
    return PermissionGoals.recommendedCount(goals);
  }

  java.util.List<String> recommendedMissingNames() {
    java.util.List<String> names = new java.util.ArrayList<>();
    for (String card : PermissionGoals.recommendedMissing(goals, states()))
      names.add(cardName(card));
    return names;
  }

  private static String cardName(String card) {
    switch (card) {
      case PermissionGoals.STORAGE:
        return UiText.choose("存储读写", "Storage");
      case PermissionGoals.CHANNEL:
        return UiText.choose("设备命令通道", "Device command channel");
      case PermissionGoals.A11Y:
        return UiText.choose("无障碍", "Accessibility");
      case PermissionGoals.NOTIFY:
        return UiText.choose("通知", "Notifications");
      case PermissionGoals.OVERLAY:
        return UiText.choose("悬浮窗", "Overlay");
      default:
        return UiText.choose("电池优化", "Battery optimization");
    }
  }

  private View summaryBar(boolean[] s) {
    LinearLayout bar = kit.row();
    bar.setBackgroundResource(R.drawable.bg_onb_perm);
    bar.setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12));
    int n = 0;
    for (boolean b : s) if (b) n++;
    int total = PermissionGoals.recommendedCount(goals);
    int done = PermissionGoals.recommendedDone(goals, s);
    TextView label =
        kit.bold(
            total > 0
                ? String.format(
                    java.util.Locale.ROOT,
                    UiText.choose(
                        "推荐授权已开启 %1$d / %2$d · 全部 %3$d / 7",
                        "Recommended %1$d of %2$d · all %3$d of 7"),
                    done,
                    total,
                    n)
                : String.format(
                    java.util.Locale.ROOT, UiText.choose("已开启 %d / 7", "%d of 7 enabled"), n),
            15,
            R.color.shell_text);
    bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    LinearLayout dots = kit.row();
    for (boolean b : s) {
      View dot = new View(activity);
      dot.setBackgroundResource(b ? R.drawable.bg_onb_dot_done : R.drawable.bg_onb_step_todo);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(kit.dp(9), kit.dp(9));
      p.leftMargin = kit.dp(5);
      dots.addView(dot, p);
    }
    dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    bar.addView(dots);
    bar.setContentDescription(label.getText());
    return bar;
  }

  private View group(String group, Set<String> rec, boolean[] s) {
    LinearLayout box = kit.column();
    LinearLayout head = kit.row();
    TextView title = kit.section(groupTitle(group));
    head.addView(title);
    boolean hit = false;
    for (String card : PermissionGoals.cardsIn(group)) hit |= rec.contains(card);
    if (hit) {
      LinearLayout.LayoutParams p =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      p.leftMargin = kit.dp(8);
      head.addView(kit.pill(UiText.choose("推荐", "Recommended"), OnboardingKit.Tone.ACCENT), p);
    }
    box.addView(head);
    String description = groupDescription(group, s);
    if (!description.isEmpty())
      kit.add(box, kit.text(description, 12.5f, R.color.shell_text_muted), 4);
    OnboardingKit.Grid grid = new OnboardingKit.Grid(activity, kit.dp(10), kit.dp(330));
    for (String card : PermissionGoals.cardsIn(group)) {
      View v = card(card, rec.contains(card), s);
      if (PermissionGoals.CHANNEL.equals(card)) OnboardingKit.Grid.setWide(v, true);
      grid.addView(v);
    }
    kit.add(box, grid, 10);
    return box;
  }

  private static String groupTitle(String group) {
    switch (group) {
      case PermissionGoals.GROUP_BASIC:
        return UiText.choose("基础", "Basics");
      case PermissionGoals.GROUP_FILES:
        return UiText.choose("文件", "Files");
      case PermissionGoals.GROUP_SCREEN:
        return UiText.choose("屏幕操作（手机任务）", "Screen control (phone tasks)");
      default:
        return UiText.choose("设备命令通道", "Device command channel");
    }
  }

  private static String mark(boolean ok) {
    return ok ? "✓" : "○";
  }

  private String groupDescription(String group, boolean[] s) {
    switch (group) {
      case PermissionGoals.GROUP_BASIC:
        return UiText.choose(
            "让部署进度和后台任务可见，少被系统中断。",
            "Keep deployment progress and background work visible and less likely to be interrupted.");
      case PermissionGoals.GROUP_SCREEN:
        return String.format(
            java.util.Locale.ROOT,
            UiText.choose(
                "手机任务需要：%s 无障碍　%s 悬浮窗　%s 通知",
                "Phone tasks need: %s accessibility  %s overlay  %s notifications"),
            mark(s[3]),
            mark(s[5]),
            mark(s[4]));
      case PermissionGoals.GROUP_SHELL:
        return UiText.choose(
            "普通对话和终端不依赖设备控制授权；设备命令白名单始终开启。",
            "Chat and the terminal work without device-control access; the device command allowlist is always on.");
      default:
        return "";
    }
  }

  private int notifyRequests() {
    return prefs().getInt(NOTIFY_REQUESTS, 0);
  }

  private SharedPreferences prefs() {
    return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private View card(String id, boolean recommended, boolean[] s) {
    if (PermissionGoals.CHANNEL.equals(id))
      return PermissionCard.build(
          kit,
          PermissionChannelCard.model(
              activity,
              kit,
              channelTab,
              environmentReady,
              recommended,
              tab -> {
                channelTab = tab;
                render(true);
              },
              this::refresh));
    PermissionCard.Model m = PermissionCards.model(this, id, s);
    m.recommended = recommended;
    return PermissionCard.build(kit, m);
  }

  // ---------------------------------------------------------------- 卡片回调（PermissionCards 使用）

  AppCompatActivity activity() {
    return activity;
  }

  OnboardingKit kit() {
    return kit;
  }

  /** Android 13 以下没有运行时通知申请框：只能在系统通知设置中打开总开关。 */
  static boolean notificationPromptAvailable() {
    return Build.VERSION.SDK_INT >= 33;
  }

  /**
   * 系统已不再弹出申请框：至少有一次申请明确返回“拒绝”，且系统不再建议说明理由（再次拒绝或“不再询问”）。
   * 只取消、未作选择的申请不计入，避免把仍会弹框的情况误判成已拒绝。
   */
  boolean notificationRequestExhausted() {
    if (!notificationPromptAvailable()) return false;
    return prefs().getInt(NOTIFY_DENIALS, 0) >= 1
        && !activity.shouldShowRequestPermissionRationale(
            android.Manifest.permission.POST_NOTIFICATIONS);
  }

  /** 申请框回调：只有明确拒绝才计数（取消对话框时系统同样回传 false，但随后仍可再弹）。 */
  private void onNotificationResult(boolean granted) {
    if (!granted && notificationPromptAvailable() && !PermissionActions.notifications(activity)) {
      boolean rationale =
          activity.shouldShowRequestPermissionRationale(
              android.Manifest.permission.POST_NOTIFICATIONS);
      // 第一次拒绝后系统建议说明理由（rationale=true）；第二次拒绝或“不再询问”后为 false。
      // 二者都属于明确拒绝；首次请求被取消时 rationale 仍为 false 且系统下次还会弹框，
      // 因此只有在之前已请求过至少一次的情况下，rationale=false 才计为拒绝。
      if (rationale || notifyRequests() >= 2)
        prefs().edit().putInt(NOTIFY_DENIALS, prefs().getInt(NOTIFY_DENIALS, 0) + 1).apply();
    }
    refresh();
  }

  void requestNotifications() {
    if (!notificationPromptAvailable()
        || notificationRequest == null
        || notificationRequestExhausted()) {
      PermissionActions.openNotificationSettings(activity);
      return;
    }
    prefs().edit().putInt(NOTIFY_REQUESTS, notifyRequests() + 1).apply();
    try {
      notificationRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS);
    } catch (RuntimeException unavailable) {
      PermissionActions.openNotificationSettings(activity);
    }
  }
}
