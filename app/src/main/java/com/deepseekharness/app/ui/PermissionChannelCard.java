package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiText;

/**
 * 设备命令通道宽卡片：Shizuku / Stellar · Root · ADB 配对三个分页（任选其一）。
 *
 * <p>按钮语义完全沿用 DeviceGrantsFragment：Shizuku 未连接先重连再申请授权；Root 开关只改 DSHA
 * 本地开关，验证才会触发 root 管理器授权；引导中 Root 档位只读显示，放宽仍须在设备授权页二次确认。
 * ADB 配对依赖运行环境就绪。三种通道共用同一套命令白名单。
 */
final class PermissionChannelCard {
  static final String SHIZUKU = "shizuku", ROOT = "root", ADB = "adb";

  private PermissionChannelCard() {}

  static boolean shizukuReady() {
    return ShizukuShell.isAvailable() && ShizukuShell.hasPermission() && ShizukuShell.isReady();
  }

  /**
   * Root 仅在本地开关已开且最近一次验证已由当前 su 响应时视为就绪。RootShell 属于受管运行时启动输入，
   * 不能为界面新增接口，这里只比对它渲染出的“已响应”整句（当前语言与中文原文两种写法）。
   * 该结果是进程内缓存，只用于显示，不作为执行门禁。
   */
  static boolean rootReady(Context context) {
    if (!RootShell.enabled(context)) return false;
    String status = RootShell.status(context);
    // RootShell 用模板 "su：%s\n<已响应>" 渲染；取当前语言模板换行后的固定部分比对。
    String template = UiText.format("su：%s\nroot 通道已响应 · 设备命令仍受策略保护", "\u0000");
    int split = template.indexOf('\n');
    String responded = split < 0 ? template : template.substring(split + 1);
    return status.contains("root 通道已响应 · 设备命令仍受策略保护")
        || (!responded.isEmpty() && status.contains(responded));
  }

  /** 构建卡片；refresh 在状态可能变化后由面板调用以整体重绘。 */
  static PermissionCard.Model model(
      AppCompatActivity activity,
      OnboardingKit kit,
      String tab,
      boolean environmentReady,
      boolean recommended,
      java.util.function.Consumer<String> selectTab,
      Runnable refresh) {
    Context ctx = activity;
    boolean shizuku = shizukuReady(), root = rootReady(ctx);
    PermissionCard.Model m = new PermissionCard.Model();
    m.icon = R.drawable.ic_terminal;
    m.title = UiText.choose("设备命令通道", "Device command channel");
    m.why =
        UiText.choose(
            "让设备命令经 Shizuku / Stellar、Root 或 ADB 执行，任选其一。三种通道共用同一套命令白名单。",
            "Run device commands through Shizuku / Stellar, Root or ADB — pick one. All channels share the same command allowlist.");
    m.ok = shizuku || root;
    m.pill =
        root
            ? UiText.choose("Root 已就绪", "Root ready")
            : shizuku
                ? UiText.choose("Shizuku 已授权", "Shizuku authorized")
                : UiText.choose("未配置", "Not configured");
    m.tone = m.ok ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.NEUTRAL;
    m.recommended = recommended;

    LinearLayout body = kit.column();
    body.addView(tabs(kit, tab, shizuku, root, selectTab));
    LinearLayout panel = kit.column();
    if (ROOT.equals(tab)) rootPanel(activity, kit, panel, refresh);
    else if (ADB.equals(tab)) adbPanel(activity, kit, panel, environmentReady);
    else shizukuPanel(activity, kit, panel, refresh);
    kit.add(body, panel, 6);
    m.extra = body;
    return m;
  }

  private static View tabs(
      OnboardingKit kit,
      String current,
      boolean shizuku,
      boolean root,
      java.util.function.Consumer<String> select) {
    LinearLayout bar = kit.row();
    bar.setBackgroundResource(R.drawable.bg_onb_tabs);
    bar.setPadding(kit.dp(3), kit.dp(3), kit.dp(3), kit.dp(3));
    String[][] items = {
      {SHIZUKU, UiText.choose("Shizuku / Stellar", "Shizuku / Stellar"), shizuku ? "1" : ""},
      {ROOT, "Root", root ? "1" : ""},
      {ADB, UiText.choose("ADB 配对", "ADB pairing"), ""}
    };
    for (String[] item : items) {
      boolean selected = item[0].equals(current);
      TextView tab =
          kit.bold(
              item[1] + (item[2].isEmpty() ? "" : " ●"),
              12.5f,
              selected ? R.color.shell_text : R.color.shell_text_muted);
      tab.setGravity(android.view.Gravity.CENTER);
      tab.setSingleLine(true);
      tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
      tab.setMinHeight(kit.dp(36));
      tab.setPadding(kit.dp(8), 0, kit.dp(8), 0);
      tab.setBackgroundResource(R.drawable.bg_onb_tab);
      tab.setSelected(selected);
      tab.setClickable(true);
      tab.setFocusable(true);
      tab.setContentDescription(
          item[1]
              + (item[2].isEmpty() ? "" : UiText.choose("，已就绪", ", ready"))
              + (selected ? UiText.choose("，已选中", ", selected") : ""));
      tab.setOnClickListener(v -> select.accept(item[0]));
      bar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }
    return bar;
  }

  private static TextView statusBox(OnboardingKit kit, String text, boolean ok) {
    TextView status = kit.text(text, 12.5f, ok ? R.color.shell_good : R.color.shell_text_secondary);
    status.setBackgroundResource(R.drawable.bg_onb_status_box);
    status.setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    return status;
  }

  private static void shizukuPanel(
      AppCompatActivity activity, OnboardingKit kit, LinearLayout panel, Runnable refresh) {
    boolean ok = shizukuReady();
    panel.addView(statusBox(kit, ShizukuShell.userStatus(activity), ok));
    kit.add(
        panel,
        kit.hint(
            UiText.choose(
                "使用 Stellar 时，请在 Stellar 设置中开启「Shizuku 兼容层」并启动服务；手机重启后服务通常需要重新启动。",
                "With Stellar, enable its Shizuku compatibility layer and start the service. Services usually need restarting after a reboot."),
            OnboardingKit.Tone.ACCENT),
        8);
    Button primary;
    if (!ShizukuShell.isAvailable())
      primary =
          kit.small(
              UiText.text("授权 Shizuku"),
              true,
              v -> {
                v.setEnabled(false);
                com.deepseekharness.app.ShizukuManagerCompat.reconnect(
                    activity,
                    () -> {
                      if (activity.isFinishing() || activity.isDestroyed()) return;
                      v.setEnabled(true);
                      if (ShizukuShell.isAvailable())
                        ShizukuShell.requestPermission(
                            (code, result) -> activity.runOnUiThread(refresh));
                      else PermissionActions.toast(activity, ShizukuShell.userStatus(activity));
                      refresh.run();
                    });
              });
    else if (!ShizukuShell.hasPermission())
      primary =
          kit.small(
              UiText.text("授权 Shizuku"),
              true,
              v ->
                  ShizukuShell.requestPermission(
                      (code, result) -> activity.runOnUiThread(refresh)));
    else
      primary =
          kit.small(
              UiText.text("验证 Shizuku 连接"),
              false,
              v -> PermissionActions.verify(activity, v, () -> ShizukuShell.exec("id"), refresh));
    Button open =
        kit.small(
            UiText.choose("打开 Shizuku / Stellar", "Open Shizuku / Stellar"),
            false,
            v -> PermissionActions.openShizukuManager(activity));
    kit.add(panel, kit.actionRow(primary, open), 10);
  }

  private static void rootPanel(
      AppCompatActivity activity, OnboardingKit kit, LinearLayout panel, Runnable refresh) {
    Context ctx = activity;
    boolean enabled = RootShell.enabled(ctx);
    LinearLayout line = kit.row();
    line.setMinimumHeight(kit.dp(48));
    TextView label = kit.bold(UiText.text("允许 Root Shell"), 14, R.color.shell_text);
    line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    var toggle = new com.google.android.material.materialswitch.MaterialSwitch(ctx);
    toggle.setChecked(enabled);
    toggle.setContentDescription(UiText.text("允许 Root Shell"));
    toggle.setOnCheckedChangeListener(
        (button, on) -> {
          new ConfigStore(ctx).setRootShellAllowed(on);
          if (!on) com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).revoke();
          Context app = ctx.getApplicationContext();
          new Thread(
                  () -> {
                    try {
                      com.deepseekharness.app.bridge.AdbBridge.applySettings(
                          app, com.deepseekharness.app.core.HarnessController.get(app).proot());
                    } catch (RuntimeException ignored) {
                      // 与设备授权页一致：同步结果只用于提示，失败不影响本地开关。
                    }
                  },
                  "onboarding-root-sync")
              .start();
          refresh.run();
        });
    line.addView(toggle);
    panel.addView(line);
    panel.addView(statusBox(kit, RootShell.status(ctx), rootReady(ctx)), kit.topMargin(4));
    kit.add(
        panel,
        kit.text(
            UiText.choose(
                "手机已 root 时可直接使用 su，无需 ADB 配对。首次执行请在 root 管理器中授权 DSHA。开关打开且找到 su 后命令会先走 root，管理器拒绝时不会自动改用 Shizuku；引导中固定为严格档，放宽只在 设置 → 设备能力授权 中调整。",
                "On a rooted phone su works without ADB pairing; approve DSHA in your root manager on first use. Once enabled and su is found, commands go through root first and do not fall back to Shizuku if the manager denies. Onboarding keeps the strict level; relax it only in Settings → Device access."),
            12,
            R.color.shell_text_muted),
        8);
    if (enabled) {
      Button verify =
          kit.small(
              UiText.choose("验证 root 授权", "Verify root access"),
              true,
              v -> {
                if (!RootShell.present()) {
                  PermissionActions.toast(
                      activity, UiText.text("未找到 su；请确认手机已 root，且 root 管理器允许 DSHA 使用"));
                  return;
                }
                PermissionActions.verify(
                    activity,
                    v,
                    () -> RootShell.exec(ctx.getApplicationContext(), "id", -1),
                    () -> {
                      refresh.run();
                      // 开关打开且找到 su 时命令先走 root，管理器拒绝不会自动改用 Shizuku。
                      if (RootShell.enabled(ctx) && !rootReady(ctx))
                        PermissionActions.toast(
                            activity,
                            UiText.choose(
                                "root 未响应：可在 root 管理器中允许 DSHA，或关闭「允许 Root Shell」改用 Shizuku / Stellar。",
                                "Root did not respond: allow DSHA in your root manager, or turn off Allow Root Shell and use Shizuku / Stellar instead."));
                    });
              });
      kit.add(panel, kit.actionRow(verify), 10);
    }
  }

  /** ADB 状态只读：与 DeviceGrantsFragment.computeAdbStatus 同一判据（不发起连接或配对）。 */
  private static String adbStatus(Context app) {
    try {
      if (!com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(app))
        return UiText.text("ADB 已关闭。开启后才会保持连接。");
      String bridge = com.deepseekharness.app.DeviceBridgeService.adbState;
      if ("need_pair".equals(bridge)) return UiText.text("配对已失效，请重新配对。");
      if ("reconnecting".equals(bridge)) return UiText.format("正在重连无线调试…%s", "");
      if ("connected".equals(bridge))
        return UiText.choose("连接已验证 · ADB 通道可用", "Connection verified · ADB channel available");
      return UiText.text("尚未配对：点下方「ADB 无线配对」，配对后还会验证连接。");
    } catch (RuntimeException unknown) {
      return UiText.text("尚未配对：点下方「ADB 无线配对」，配对后还会验证连接。");
    }
  }

  private static void adbPanel(
      AppCompatActivity activity, OnboardingKit kit, LinearLayout panel, boolean environmentReady) {
    boolean connected =
        environmentReady
            && "connected".equals(com.deepseekharness.app.DeviceBridgeService.adbState);
    panel.addView(
        statusBox(
            kit,
            environmentReady
                ? adbStatus(activity.getApplicationContext())
                : UiText.choose(
                    "运行环境就绪后可配对：配对和连接验证依赖内置环境。",
                    "Pairing becomes available when the runtime is ready: pairing and verification use the bundled environment."),
            connected));
    kit.add(
        panel,
        kit.hint(
            UiText.choose(
                "连接验证通过后，DSHA 会给自己授予 WRITE_SECURE_SETTINGS，用于开机后自动打开无线调试；配对与 ADB 通道开关在 设置 → 设备能力授权 中管理。",
                "After the connection is verified, DSHA grants itself WRITE_SECURE_SETTINGS so wireless debugging can be re-enabled after boot. Pairing and the ADB channel switch live in Settings → Device access."),
            OnboardingKit.Tone.ACCENT),
        8);
    Button pair =
        kit.small(
            UiText.choose("ADB 无线配对", "ADB wireless pairing"),
            true,
            v -> {
              try {
                activity.startActivity(new android.content.Intent(activity, AdbPairActivity.class));
              } catch (RuntimeException failure) {
                PermissionActions.toast(activity, UiText.format("打开设置失败：%s", failure.getMessage()));
              }
            });
    pair.setEnabled(environmentReady);
    kit.add(panel, kit.actionRow(pair), 10);
  }
}
