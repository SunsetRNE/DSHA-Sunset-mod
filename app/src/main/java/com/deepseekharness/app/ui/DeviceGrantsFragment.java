package com.deepseekharness.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.GrantSummary;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 设备通道、系统权限与能力预授权的唯一设置页；沿用历史偏好键。 */
public final class DeviceGrantsFragment extends Fragment {
  private MaterialSwitch sms;
  private TextView smsStatus;
  private TextView screenPolicyStatus;
  private MaterialSwitch screenPause;
  private android.widget.RadioGroup screenSegment;
  private android.widget.RadioButton[] screenModeButtons;
  private TextView screenGlobalBanner, screenRulesRow;
  private android.widget.RadioGroup rootModes;
  private android.widget.RadioButton[] rootModeButtons;
  private View[] pillDots;
  private TextView[] pillTitles, pillValues;
  private View[] pills;
  private int columns = 1;
  private MaterialSwitch externalDevice;
  private TextView externalDeviceStatus;
  private boolean updating;
  private int adbStatusRequest;
  private FullPermissionControls fullPermissions;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshConnections =
      new Runnable() {
        @Override
        public void run() {
          if (!isResumed() || getView() == null) return;
          refreshChannelLabels();
          // refreshChannelLabels → refreshRootPolicy 已同步刷新概览。
          if (fullPermissions != null) fullPermissions.refresh();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    View view = inflater.inflate(R.layout.fragment_device_grants, container, false);
    Context ctx = requireContext();
    columns = 1;
    ((TextView) view.findViewById(R.id.grants_global_label))
        .setText(UiText.choose("全局权限", "Global permission"));
    fullPermissions =
        new FullPermissionControls(
            this,
            view.findViewById(R.id.grants_global_slot),
            () -> {
              refreshNativePolicyControls();
              refreshSummary();
            });
    for (int id :
        new int[] {
          R.id.device_root_status,
          R.id.workspace_shizuku_status,
          R.id.config_adb_status,
          R.id.device_root_policy_warning
        }) {
      TextView detail = view.findViewById(id);
      detail.setOnClickListener(
          v ->
              CardSheet.show(
                  requireContext(),
                  com.deepseekharness.app.util.UiText.choose("设备通道状态", "Device channel status"),
                  detail.getTag(R.id.device_root_policy_warning) instanceof String full
                      ? full
                      : detail.getText().toString()));
    }
    bindLabelInfo(
        ctx,
        view.findViewById(R.id.grants_screen_label),
        getString(R.string.computer_use_title),
        () ->
            com.deepseekharness.app.util.UiText.choose(
                "让助手读屏、点击和输入。先完成屏幕操作设置，再在对话中描述任务。",
                "Let the assistant observe, tap and type. Set up screen control, then describe the task in a conversation."));
    View.OnClickListener setup =
        button -> startActivity(new Intent(requireContext(), AccessibilitySetupActivity.class));
    android.widget.Button setupButton = view.findViewById(R.id.computer_use_accessibility);
    // 行尾短按钮只写「设置」；完整入口名「设置屏幕操作权限」交给读屏。
    setupButton.setText(UiText.text("设置"));
    setupButton.setContentDescription(getString(R.string.ui134_screen_setup));
    setupButton.setOnClickListener(setup);
    android.widget.Button resetup = view.findViewById(R.id.screen_resetup);
    resetup.setText(com.deepseekharness.app.util.UiText.choose("重新设置", "Set up again"));
    resetup.setOnClickListener(setup);
    addExternalDeviceControls(ctx, view);
    addScreenModeControls(ctx, view);
    addScreenPolicyControls(ctx, view);
    // 虚拟屏是另一项能力：单独一行，点进虚拟屏页（页内有创建与使用说明）。
    if (com.deepseekharness.app.vscreen.VirtualScreenManager.supported(ctx)) {
      view.findViewById(R.id.screen_virtual_group).setVisibility(View.VISIBLE);
      ((TextView) view.findViewById(R.id.screen_virtual_title))
          .setText(com.deepseekharness.app.util.UiText.choose("虚拟识屏", "Virtual screen"));
      ((TextView) view.findViewById(R.id.screen_virtual_sub))
          .setText(
              com.deepseekharness.app.util.UiText.choose(
                  "在独立屏幕里操作应用 · 需要已连接的设备通道",
                  "Operate apps on a separate screen · needs a connected device channel"));
      view.findViewById(R.id.screen_virtual_row)
          .setOnClickListener(
              row ->
                  startActivity(
                      new Intent(
                          ctx, com.deepseekharness.app.vscreen.VirtualScreenActivity.class)));
    }

    CompoundButton root = view.findViewById(R.id.config_root_shell);
    root.setContentDescription(getString(R.string.ui_m0062));
    view.findViewById(R.id.device_root_title).setOnClickListener(v -> root.toggle());
    root.setChecked(new ConfigStore(ctx).isRootShellAllowed());
    root.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          new ConfigStore(ctx).setRootShellAllowed(enabled);
          if (!enabled)
            com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                .revokeFor("ROOT_DISABLED");
          syncSettings();
          refreshChannelLabels();
        });
    addRootModeControls(ctx, view);
    refreshRootPolicy();
    android.widget.Button rootVerify = view.findViewById(R.id.device_root_verify);
    rootVerify.setText(UiText.choose("验证", "Verify"));
    rootVerify.setContentDescription(getString(R.string.ui_m0170));
    rootVerify.setOnClickListener(
        button -> {
          if (!RootShell.enabled(ctx)) {
            toast(com.deepseekharness.app.util.UiText.text("请先启用「允许 Root Shell」"));
            return;
          }
          if (!RootShell.present()) {
            toast(
                com.deepseekharness.app.util.UiText.text(
                    "未找到 su；请确认手机已 root，且 root 管理器允许 DSHA 使用"));
            return;
          }
          verify(
              button,
              view.findViewById(R.id.device_root_status),
              () -> RootShell.exec(ctx.getApplicationContext(), "id", -1));
        });
    view.findViewById(R.id.device_shizuku_open).setOnClickListener(button -> openShizuku());
    view.findViewById(R.id.workspace_shizuku_auth)
        .setOnClickListener(
            button -> {
              if (!ShizukuShell.isAvailable()) {
                button.setEnabled(false);
                com.deepseekharness.app.ShizukuManagerCompat.reconnect(
                    requireActivity(),
                    () -> {
                      if (!isAdded()) return;
                      button.setEnabled(true);
                      if (ShizukuShell.isAvailable())
                        ShizukuShell.requestPermission(
                            (code, result) -> {
                              if (isAdded()) refreshChannelLabels();
                            });
                      else toast(ShizukuShell.userStatus(ctx));
                      refreshChannelLabels();
                    });
                return;
              }
              if (ShizukuShell.hasPermission()) {
                verify(
                    button,
                    view.findViewById(R.id.workspace_shizuku_status),
                    () -> ShizukuShell.exec("id"));
              } else
                ShizukuShell.requestPermission(
                    (code, result) -> {
                      if (isAdded()) refreshChannelLabels();
                    });
            });
    CompoundButton sensors = view.findViewById(R.id.config_cap_sensors);
    sensors.setChecked(pref(ctx, "cap_sensors", false));
    sensors.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (!updating) savePreference("cap_sensors", enabled);
        });
    CompoundButton location = view.findViewById(R.id.config_cap_location);
    location.setChecked(pref(ctx, "cap_location", false));
    location.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          if (fullPermissions != null) fullPermissions.cancelForLocationChange();
          savePreference("cap_location", enabled);
          if (enabled
              && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(
                new String[] {
                  android.Manifest.permission.ACCESS_FINE_LOCATION,
                  android.Manifest.permission.ACCESS_COARSE_LOCATION
                },
                104);
        });
    CompoundButton adb = view.findViewById(R.id.config_adb_enable);
    TextView adbTitle = view.findViewById(R.id.config_adb_title);
    adbTitle.setText(UiText.choose("ADB 设备通道", "ADB device channel"));
    adbTitle.setOnClickListener(v -> adb.toggle());
    adb.setContentDescription(getString(R.string.ui_m0090));
    adb.setChecked(pref(ctx, "adb_enabled", false));
    adb.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          com.deepseekharness.app.HttpShellService.smsPolicyChanged(ctx);
          DeviceBridgeService.adbIntentChanging();
          savePreference("adb_enabled", enabled);
          if (!enabled)
            com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                .revokeFor("ADB_DISABLED");
          if (enabled) {
            DeviceBridgeService.apply(ctx);
            if (getActivity() instanceof MainActivity)
              ((MainActivity) getActivity()).requestLocalNetwork();
          } else ctx.stopService(new Intent(ctx, DeviceBridgeService.class));
          syncSettings();
          refreshAdbStatus(view.findViewById(R.id.config_adb_status));
        });
    android.widget.Button pair = view.findViewById(R.id.config_adb_pair);
    pair.setText(UiText.choose("配对", "Pair"));
    pair.setContentDescription(getString(R.string.ui_m0031));
    pair.setOnClickListener(
        button -> {
          if (android.os.Build.VERSION.SDK_INT < 30) {
            new com.deepseekharness.app.ui.DshaDialogBuilder(ctx)
                .setTitle(com.deepseekharness.app.util.UiText.text("当前系统没有配对码接口"))
                .setMessage(
                    com.deepseekharness.app.util.UiText.text(
                        "无线调试配对码需要 Android 11+。Android 6—10 可使用 Shizuku、已授权的 root，或由电脑开启 ADB TCP 通道。"))
                .setPositiveButton(com.deepseekharness.app.util.UiText.text("知道了"), null)
                .show();
          } else startActivity(new Intent(ctx, AdbPairActivity.class));
        });
    view.findViewById(R.id.config_all_files).setOnClickListener(button -> openAllFilesAccess(ctx));
    view.findViewById(R.id.config_battery_opt).setOnClickListener(button -> openBatteryOpt(ctx));
    sms = view.findViewById(R.id.device_sms);
    smsStatus = view.findViewById(R.id.device_sms_status);
    sms.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          updateSms();
          if (!enabled) {
            saveSms(false);
            return;
          }
          new com.deepseekharness.app.ui.DshaDialogBuilder(ctx, true)
              .setTitle(com.deepseekharness.app.util.UiText.text("持续允许读取短信？"))
              .setMessage(
                  com.deepseekharness.app.util.UiText.text(
                      "当前 DSHA 环境内的助手和插件将能通过已授权的 root 或 ADB 查询短信，包括正文、号码和可能存在的验证码。\n\n后续短信查询不再逐条询问。关闭后立即阻止新查询。"))
              .setPositiveButton(
                  com.deepseekharness.app.util.UiText.text("允许短信读取"),
                  (dialog, which) -> saveSms(true))
              .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
              .show();
        });
    updateSms();
    // 压缩版：各通道的长说明合并到「设备执行通道」标签旁的 ⓘ；页面说明在概览头部的 ⓘ。
    bindChannelInfo(ctx, view);
    buildSummaryHeader(ctx, view);
    adaptColumns(view);
    refreshSummary();
    return view;
  }

  /** 独立外接目标能力：默认关闭、不继承本机 ADB/全局授权，首版只开放 USB root 调试与只读预检。 */
  private void addExternalDeviceControls(Context ctx, View page) {
    // 外接设备属于设备通道：作为通道卡的最后一行，与 Root / Shizuku / ADB 同一行式；长说明收进标题 ⓘ。
    android.widget.LinearLayout card = page.findViewById(R.id.channels_card);
    View divider = new View(ctx);
    android.widget.LinearLayout.LayoutParams dividerParams =
        new android.widget.LinearLayout.LayoutParams(-1, dp(1));
    dividerParams.topMargin = dp(6);
    divider.setBackgroundColor(ctx.getColor(R.color.shell_line));
    card.addView(divider, dividerParams);
    android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.grants_channel_row));
    android.widget.LinearLayout text = new android.widget.LinearLayout(ctx);
    text.setOrientation(android.widget.LinearLayout.VERTICAL);
    text.setPadding(0, dp(6), 0, dp(6));
    TextView title = new TextView(ctx, null, 0, R.style.ShellRowTitle);
    String titleText =
        com.deepseekharness.app.util.UiText.choose("外接设备（实验性）", "External device (experimental)");
    title.setText(titleText);
    title.setMaxLines(1);
    text.addView(title, new android.widget.LinearLayout.LayoutParams(-2, -2));
    bindLabelInfo(
        ctx,
        title,
        titleText,
        () ->
            com.deepseekharness.app.util.UiText.choose(
                "实验性，未经真机验收。独立授权另一台手机（USB 或无线调试），绑定后可受控调试、重启和确认后安装；刷机只做只读预检，不执行刷写。",
                "Experimental, not yet device-verified. Authorize another phone (USB or wireless debugging); once bound it allows controlled debugging, reboot and confirmed installs. Flashing is read-only preflight only."));
    title.setMinHeight(0);
    externalDeviceStatus = new TextView(ctx, null, 0, R.style.ShellRowSub);
    android.widget.LinearLayout.LayoutParams statusParams =
        new android.widget.LinearLayout.LayoutParams(-1, -2);
    statusParams.topMargin = dp(3);
    text.addView(externalDeviceStatus, statusParams);
    row.addView(text, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
    externalDevice = new MaterialSwitch(ctx);
    externalDevice.setContentDescription(
        com.deepseekharness.app.util.UiText.choose("允许外接设备能力", "Allow external-device access"));
    externalDevice.setChecked(new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx).enabled());
    android.widget.LinearLayout.LayoutParams switchParams =
        new android.widget.LinearLayout.LayoutParams(-2, -2);
    switchParams.setMarginStart(dp(4));
    row.addView(externalDevice, switchParams);
    card.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
    externalDevice.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          if (!enabled) {
            new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx).setEnabled(false);
            refreshExternalDeviceStatus();
            return;
          }
          new DshaDialogBuilder(ctx)
              .setTitle(com.deepseekharness.app.util.UiText.text("开启外接设备能力？"))
              .setMessage(
                  com.deepseekharness.app.util.UiText.choose(
                      "实验性功能。这会允许 DSHA 通过已授权的 root 访问 USB 或无线调试目标。它不会开启本机刷机，也不会自动授权或刷写另一台手机。",
                      "Experimental. This allows DSHA to access a USB or wireless-debugging target through authorized root. It does not enable host flashing or automatically authorize or flash the target."))
              .setPositiveButton(
                  com.deepseekharness.app.util.UiText.choose("开启", "Enable"),
                  (dialog, which) -> {
                    new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx).setEnabled(true);
                    refreshExternalDeviceStatus();
                  })
              .setNegativeButton(
                  com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
                  (dialog, which) -> {
                    updating = true;
                    externalDevice.setChecked(false);
                    updating = false;
                  })
              .show();
        });
    refreshExternalDeviceStatus();
  }

  private void refreshExternalDeviceStatus() {
    if (externalDeviceStatus == null || !isAdded()) return;
    final Context ctx = requireContext().getApplicationContext();
    new Thread(
            () -> {
              String text;
              try {
                org.json.JSONObject state =
                    new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx).status();
                text =
                    state.optBoolean("enabled", false)
                        ? state.optBoolean("rootVerified", false)
                            ? state.optBoolean("bound", false)
                                ? com.deepseekharness.app.util.UiText.format(
                                    "已开启 · 已绑定 %s · 刷机执行关闭", state.optString("serial", ""))
                                : com.deepseekharness.app.util.UiText.text("已开启 · 尚未绑定目标 · 刷机执行关闭")
                            : com.deepseekharness.app.util.UiText.text("已开启 · root 尚未验证 · 不会执行目标操作")
                        : com.deepseekharness.app.util.UiText.text("已关闭 · 不读取或操作外接目标");
              } catch (Throwable error) {
                text =
                    com.deepseekharness.app.util.UiText.format(
                        "状态不可用：%s", error.getClass().getSimpleName());
              }
              final String displayText = text;
              main.post(
                  () -> {
                    if (isAdded() && externalDeviceStatus != null)
                      externalDeviceStatus.setText(displayText);
                  });
            },
            "external-device-status")
        .start();
  }

  private static final com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode[] SCREEN_MODES =
      com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.values();

  /**
   * 压缩版屏幕操作：暂停开关 + 确认方式三段选择（全部手动 / 按应用 / 全部允许）。点一段即保存；
   * 全部允许先经红色确认，确认前分段保持原值。选中「按应用」时下方出现「已配置 N 个应用 ›」。
   */
  private TextView screenModeStatus;

  /**
   * 识屏模式（关闭 / 实体 / 虚拟，互斥）：卡片顶部一行显示当前模式与缺项，点按选择。确认方式、暂停与撤销仍是全局设置。
   */
  private void addScreenModeControls(Context ctx, View page) {
    android.widget.LinearLayout card = page.findViewById(R.id.screen_card);
    android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(44));
    row.setBackgroundResource(R.drawable.bg_action_plain);
    TextView label = new TextView(ctx, null, 0, R.style.ShellRowTitle);
    label.setText(UiText.choose("识屏模式", "Screen mode"));
    label.setTextSize(13);
    row.addView(label, new android.widget.LinearLayout.LayoutParams(-2, -2));
    screenModeStatus = new TextView(ctx, null, 0, R.style.ShellStatusText);
    screenModeStatus.setGravity(android.view.Gravity.END);
    screenModeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    android.widget.LinearLayout.LayoutParams statusParams =
        new android.widget.LinearLayout.LayoutParams(0, -2, 1);
    statusParams.setMarginStart(dp(12));
    row.addView(screenModeStatus, statusParams);
    row.setOnClickListener(v -> chooseScreenMode(ctx));
    card.addView(
        row,
        card.indexOfChild(page.findViewById(R.id.screen_status_row)) + 1,
        new android.widget.LinearLayout.LayoutParams(-1, -2));
    refreshScreenMode();
  }

  private static String modeName(com.deepseekharness.app.util.ScreenModePolicy.Mode mode) {
    switch (mode) {
      case PHYSICAL:
        return UiText.choose("实体识屏", "Physical screen");
      case VIRTUAL:
        return UiText.choose("虚拟识屏", "Virtual screen");
      default:
        return UiText.choose("关闭", "Off");
    }
  }

  private static String missingText(java.util.List<String> missing) {
    java.util.List<String> parts = new java.util.ArrayList<>();
    for (String code : missing) {
      switch (code) {
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_ACCESSIBILITY:
          parts.add(UiText.choose("无障碍", "accessibility"));
          break;
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_DEVICE_CHANNEL:
          parts.add(
              UiText.choose(
                  "可用的 Root / Shizuku / ADB 通道", "a usable Root / Shizuku / ADB channel"));
          break;
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_API:
          parts.add("Android 11+");
          break;
        case com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_OVERLAY:
          parts.add(UiText.choose("悬浮窗", "overlay"));
          break;
        default:
          parts.add(code);
      }
    }
    return String.join(UiText.choose("、", ", "), parts);
  }

  /** 某模式的一行说明：可开启 / 缺什么 / 将用哪个通道。 */
  private static String verdictLine(
      Context ctx, com.deepseekharness.app.util.ScreenModePolicy.Mode mode) {
    var verdict = com.deepseekharness.app.screen.ScreenMode.verdict(ctx, mode);
    if (!verdict.allowed)
      return String.format(UiText.choose("需要：%s", "Needs: %s"), missingText(verdict.missing));
    String line =
        verdict.channel.isEmpty()
            ? UiText.choose("可开启", "Ready")
            : String.format(UiText.choose("可开启 · 通道 %s", "Ready · channel %s"), verdict.channel);
    if (mode == com.deepseekharness.app.util.ScreenModePolicy.Mode.VIRTUAL
        && verdict.degraded.contains(
            com.deepseekharness.app.util.ScreenCapabilityGate.MISSING_ACCESSIBILITY))
      line += UiText.choose(" · 无无障碍时节点与中文输入不可用", " · nodes and Unicode input need accessibility");
    return line;
  }

  private void refreshScreenMode() {
    if (screenModeStatus == null || getContext() == null) return;
    Context ctx = requireContext();
    var mode = com.deepseekharness.app.screen.ScreenMode.current(ctx);
    String text = modeName(mode);
    String reason = com.deepseekharness.app.screen.ScreenMode.lastReason(ctx);
    if (mode == com.deepseekharness.app.util.ScreenModePolicy.Mode.OFF && !reason.isEmpty())
      text += UiText.choose(" · 授权丢失已自动关闭", " · turned off after a permission was lost");
    screenModeStatus.setText(text);
    View page = getView();
    if (page != null
        && page.findViewById(R.id.screen_virtual_sub) != null
        && com.deepseekharness.app.vscreen.VirtualScreenManager.supported(ctx))
      ((TextView) page.findViewById(R.id.screen_virtual_sub))
          .setText(verdictLine(ctx, com.deepseekharness.app.util.ScreenModePolicy.Mode.VIRTUAL));
  }

  private void chooseScreenMode(Context ctx) {
    var modes = com.deepseekharness.app.util.ScreenModePolicy.Mode.values();
    var current = com.deepseekharness.app.screen.ScreenMode.current(ctx);
    CharSequence[] items = new CharSequence[modes.length];
    for (int i = 0; i < modes.length; i++)
      items[i] =
          modes[i] == com.deepseekharness.app.util.ScreenModePolicy.Mode.OFF
              ? modeName(modes[i])
              : modeName(modes[i]) + "\n" + verdictLine(ctx, modes[i]);
    new DshaDialogBuilder(ctx)
        .setTitle(UiText.choose("识屏模式", "Screen mode"))
        .setSingleChoiceItems(
            items,
            current.ordinal(),
            (dialog, which) -> {
              dialog.dismiss();
              applyScreenMode(ctx, modes[which]);
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  private void applyScreenMode(
      Context ctx, com.deepseekharness.app.util.ScreenModePolicy.Mode target) {
    Context app = ctx.getApplicationContext();
    String orientation =
        getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ? "landscape"
            : "portrait";
    if (screenModeStatus != null) screenModeStatus.setText(UiText.choose("切换中…", "Switching…"));
    new Thread(
            () -> {
              com.deepseekharness.app.screen.ScreenMode.Result result;
              try {
                result =
                    com.deepseekharness.app.screen.ScreenMode.request(app, target, orientation);
              } catch (RuntimeException failed) {
                result = null;
              }
              final com.deepseekharness.app.screen.ScreenMode.Result done = result;
              main.post(
                  () -> {
                    refreshScreenMode();
                    refreshSummary();
                    if (getContext() == null) return;
                    String message = screenModeMessage(target, done);
                    if (!message.isEmpty())
                      android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG)
                          .show();
                  });
            },
            "screen-mode")
        .start();
  }

  private static String screenModeMessage(
      com.deepseekharness.app.util.ScreenModePolicy.Mode target,
      com.deepseekharness.app.screen.ScreenMode.Result result) {
    if (result == null) return UiText.choose("切换失败，请重试", "Could not switch. Try again.");
    switch (result.outcome) {
      case NOT_ADMITTED:
        return String.format(
            UiText.choose("条件未满足，无法开启：%s", "Requirements not met: %s"), modeName(target));
      case CLEANUP_PENDING:
        return UiText.choose(
            "虚拟屏仍在清理，暂不能切换；请到虚拟识屏页查看",
            "The virtual screen is still being cleaned up; check the virtual screen page");
      default:
        if (!result.code.isEmpty())
          return String.format(
              UiText.choose(
                  "已开启虚拟识屏，但虚拟屏未创建：%s", "Virtual mode is on but the screen was not created: %s"),
              result.code);
        return "";
    }
  }

  private void addScreenPolicyControls(Context ctx, View page) {
    screenPause = page.findViewById(R.id.screen_pause);
    screenPause.setText(UiText.choose("暂停助手屏幕操作", "Pause assistant screen actions"));
    screenPause.setOnCheckedChangeListener(
        (button, paused) -> {
          if (updating) return;
          com.deepseekharness.app.HttpShellService.setScreenPaused(ctx, paused);
          refreshScreenPolicy();
          refreshSummary();
        });
    ((TextView) page.findViewById(R.id.screen_policy_label))
        .setText(UiText.choose("确认方式", "Confirmation"));
    screenPolicyStatus = page.findViewById(R.id.screen_policy_status);
    android.widget.LinearLayout host = page.findViewById(R.id.screen_policy_cards);
    screenGlobalBanner = page.findViewById(R.id.screen_policy_global);
    String[] names = {
      UiText.choose("全部手动", "Ask always"),
      UiText.choose("按应用", "By app"),
      UiText.choose("全部允许", "Allow all")
    };
    String[] spoken = new String[SCREEN_MODES.length];
    for (int i = 0; i < SCREEN_MODES.length; i++)
      spoken[i] = UiText.format("%s, %s", names[i], screenModeDescription(i));
    screenSegment = new android.widget.RadioGroup(ctx);
    // 「确认方式」不再占一行标签：作为分段的读屏标题。
    screenSegment.setContentDescription(UiText.choose("确认方式", "Confirmation"));
    screenSegment.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    screenSegment.setBackgroundResource(R.drawable.bg_plugin_segment_track);
    screenSegment.setPadding(dp(3), dp(3), dp(3), dp(3));
    screenModeButtons =
        buildSegment(
            ctx,
            screenSegment,
            names,
            spoken,
            new int[] {screenTone(0), screenTone(1), screenTone(2)},
            index -> pickScreenMode(SCREEN_MODES[index]));
    host.addView(screenSegment, new android.widget.LinearLayout.LayoutParams(-1, -2));
    screenRulesRow = new TextView(ctx);
    screenRulesRow.setTextSize(13);
    screenRulesRow.setTextColor(ctx.getColor(R.color.shell_accent));
    screenRulesRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    screenRulesRow.setMinHeight(dp(40));
    screenRulesRow.setPadding(dp(4), 0, dp(8), 0);
    screenRulesRow.setBackgroundResource(R.drawable.bg_action_plain);
    screenRulesRow.setOnClickListener(v -> showScreenRules());
    host.addView(screenRulesRow, new android.widget.LinearLayout.LayoutParams(-1, -2));
    android.widget.Button revoke = page.findViewById(R.id.screen_revoke);
    revoke.setText(UiText.choose("撤销预授权", "Revoke"));
    revoke.setContentDescription(
        UiText.choose("暂停并撤销全部预授权", "Pause and revoke all preauthorizations"));
    revoke.setOnClickListener(button -> confirmScreenRevoke());
    refreshScreenPolicy();
  }

  private static String screenModeDescription(int index) {
    return switch (index) {
      case 0 -> UiText.choose("每次读屏和操作前都询问你", "Ask before every observation and action");
      case 1 -> UiText.choose("指定应用免确认，其他应用仍询问", "Chosen apps skip confirmation; others still ask");
      default ->
          UiText.choose(
              "所有应用免确认，含敏感界面", "No confirmation for any app, including sensitive screens");
    };
  }

  private static String screenModeBadge(int index) {
    return switch (index) {
      case 0 -> UiText.choose("推荐", "Recommended");
      case 2 -> UiText.choose("高风险", "High risk");
      default -> "";
    };
  }

  /** 屏幕确认方式：未变化不处理；全部允许必须先过红色确认。分段始终按已保存值重画。 */
  private void pickScreenMode(com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode mode) {
    if (!isAdded()) return;
    var current = new DeviceGrants(requireContext()).screenPolicy().mode;
    refreshScreenPolicy();
    if (current == mode) return;
    if (mode == com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL)
      confirmScreenAllowAll();
    else saveScreenMode(mode);
  }

  private static int screenTone(int index) {
    return index == 0 ? R.color.shell_good : index == 2 ? R.color.shell_warn : R.color.shell_accent;
  }

  private void saveScreenMode(com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode mode) {
    if (!isAdded()) return;
    var grants = new DeviceGrants(requireContext());
    if (!grants.setScreenPolicy(mode, grants.screenPolicy().rules))
      toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
    refreshScreenPolicy();
    refreshSummary();
  }

  /** 红色警告确认（无倒计时）：点空白不关闭，「取消」在最右，确认按钮为红色。 */
  private void showRedConfirm(
      String titleText,
      String warningText,
      String noteText,
      String confirmText,
      Runnable onConfirm) {
    final Context ctx = requireContext();
    int bad = ctx.getColor(R.color.shell_bad);
    android.text.SpannableString title = new android.text.SpannableString(titleText);
    title.setSpan(
        new android.text.style.ForegroundColorSpan(bad),
        0,
        title.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView warning = new TextView(ctx);
    warning.setBackgroundResource(R.drawable.bg_root_policy_warning);
    warning.setTextColor(bad);
    warning.setTextSize(14);
    warning.setTypeface(warning.getTypeface(), android.graphics.Typeface.BOLD);
    warning.setLineSpacing(dp(3), 1f);
    warning.setPadding(dp(14), dp(12), dp(14), dp(12));
    warning.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_section_warning, 0, 0, 0);
    warning.setCompoundDrawablePadding(dp(10));
    warning.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(bad));
    warning.setText(warningText);
    android.widget.LinearLayout body = new android.widget.LinearLayout(ctx);
    body.setOrientation(android.widget.LinearLayout.VERTICAL);
    body.setPadding(dp(24), dp(8), dp(24), 0);
    body.addView(warning, new android.widget.LinearLayout.LayoutParams(-1, -2));
    if (noteText != null) {
      TextView note = new TextView(ctx);
      note.setTextSize(12);
      note.setTextColor(ctx.getColor(R.color.shell_text_secondary));
      note.setText(noteText);
      android.widget.LinearLayout.LayoutParams noteParams =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      noteParams.topMargin = dp(10);
      body.addView(note, noteParams);
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.addView(body);
    androidx.appcompat.app.AlertDialog dialog =
        new DshaDialogBuilder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(com.deepseekharness.app.util.UiText.text("取消"), null)
            .setNegativeButton(
                confirmText,
                (d, which) -> {
                  if (isAdded()) onConfirm.run();
                })
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
    android.widget.Button confirm =
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
    if (confirm != null) confirm.setTextColor(bad);
  }

  private void confirmScreenAllowAll() {
    showRedConfirm(
        com.deepseekharness.app.util.UiText.choose("全部允许屏幕操作？", "Allow all screen actions?"),
        com.deepseekharness.app.util.UiText.choose(
            "助手将可在任何应用里读屏、点击和输入且不再询问，包括支付、密码和聊天等敏感界面。",
            "The assistant can observe, tap and type in any app without asking, including sensitive screens such as payments, passwords and chats."),
        com.deepseekharness.app.util.UiText.choose(
            "可随时改回全部手动，或用暂停开关立即停止。",
            "You can switch back to confirming every action, or pause at any time."),
        com.deepseekharness.app.util.UiText.choose("仍要全部允许", "Allow all anyway"),
        () -> saveScreenMode(com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL));
  }

  private void confirmScreenRevoke() {
    final Context ctx = requireContext();
    showRedConfirm(
        com.deepseekharness.app.util.UiText.choose(
            "暂停并撤销全部预授权？", "Pause and revoke all preauthorizations?"),
        com.deepseekharness.app.util.UiText.choose(
            "立即暂停助手屏幕操作，确认方式改回全部手动，并清空已保存的应用规则。",
            "Screen actions pause now, confirmation returns to every action, and saved app rules are cleared."),
        com.deepseekharness.app.util.UiText.choose(
            "无障碍等系统权限不受影响；之后可关闭暂停开关继续。",
            "Accessibility and other Android permissions are unaffected. Turn off the pause switch to continue later."),
        com.deepseekharness.app.util.UiText.choose("撤销", "Revoke"),
        () -> {
          boolean persisted =
              new DeviceGrants(ctx)
                  .setScreenPolicy(
                      com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.MANUAL,
                      java.util.Map.of());
          com.deepseekharness.app.HttpShellService.setScreenPaused(ctx, true);
          com.deepseekharness.app.HttpShellService.revokeScreenGrant(ctx);
          refreshScreenPolicy();
          toast(
              persisted
                  ? com.deepseekharness.app.util.UiText.choose(
                      "已暂停屏幕操作并撤销预授权；可点恢复后继续",
                      "Screen actions are paused and preauthorizations revoked. Use Resume to continue")
                  : com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
        });
  }

  private void refreshScreenPolicy() {
    if (screenModeButtons == null || !isAdded()) return;
    Context ctx = requireContext();
    var policy = new DeviceGrants(ctx).screenPolicy();
    boolean global = new DeviceGrants(ctx).consentPolicy().globalAllow();
    int selected = 0;
    updating = true;
    for (int i = 0; i < screenModeButtons.length; i++) {
      boolean on = SCREEN_MODES[i] == policy.mode;
      if (on) selected = i;
      screenModeButtons[i].setChecked(on);
    }
    updating = false;
    // 全局权限开启时规则暂不生效：分段变淡但仍可编辑。
    screenSegment.setAlpha(global ? 0.55f : 1f);
    boolean custom =
        policy.mode == com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.CUSTOM;
    screenRulesRow.setVisibility(custom ? View.VISIBLE : View.GONE);
    screenRulesRow.setText(
        policy.rules.isEmpty()
            ? UiText.choose("添加免确认的应用 ›", "Add apps that skip confirmation ›")
            : UiText.format("已配置 %s 个应用 ›", String.valueOf(policy.rules.size())));
    String badge = screenModeBadge(selected);
    String summary =
        badge.isEmpty()
            ? screenModeDescription(selected)
            : UiText.format("%s · %s", screenModeDescription(selected), badge);
    // 全局权限开启时规则暂不生效：说明行首句换成提示，原规则说明仍在 ⓘ 全文里。
    if (global)
      summary =
          UiText.format(
              "%s\n%s",
              UiText.choose("全局权限开启中，以下规则暂不生效", "Global permission is on; these rules are paused"),
              summary);
    InfoHint.bind(
        screenPolicyStatus,
        UiText.choose("屏幕确认规则", "Screen confirmation rules"),
        UiText.format(
            "%s\n%s",
            summary,
            UiText.choose(
                "仅设置 DSHA 的屏幕确认；不会授予系统权限。全部允许包含敏感界面；自定义中未配置的应用仍询问。规则保存在本机，不随备份迁移。",
                "These rules only control DSHA confirmations and do not grant system permissions. Allow all includes sensitive screens. Unconfigured apps still ask in custom mode. Rules stay on this device and are excluded from backups.")));
    screenPolicyStatus.setTextColor(
        ctx.getColor(global ? R.color.shell_warn : R.color.shell_text_muted));
    updating = true;
    screenPause.setChecked(com.deepseekharness.app.HttpShellService.screenPaused());
    updating = false;
    View page = getView();
    if (page != null) refreshA11yStatus(page.findViewById(R.id.config_a11y_status));
  }

  private void refreshNativePolicyControls() {
    refreshScreenPolicy();
    View page = getView();
    if (page == null || !isAdded()) return;
    updating = true;
    ((CompoundButton) page.findViewById(R.id.config_root_shell))
        .setChecked(new ConfigStore(requireContext()).isRootShellAllowed());
    ((CompoundButton) page.findViewById(R.id.config_adb_enable))
        .setChecked(pref(requireContext(), "adb_enabled", false));
    ((CompoundButton) page.findViewById(R.id.config_cap_sensors))
        .setChecked(pref(requireContext(), "cap_sensors", false));
    ((CompoundButton) page.findViewById(R.id.config_cap_location))
        .setChecked(pref(requireContext(), "cap_location", false));

    updating = false;
    updateSms();
    refreshRootPolicy();
  }

  private String screenRuleLabel(com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule rule) {
    return rule == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
        ? com.deepseekharness.app.util.UiText.choose("允许", "Allow")
        : com.deepseekharness.app.util.UiText.choose("询问", "Ask");
  }

  /** 已配置的应用规则：图标 + 名称 + 包名 + 当前规则；点行编辑，「添加应用」打开应用选择器。 */
  private void showScreenRules() {
    final Context ctx = requireContext();
    var policy = new DeviceGrants(ctx).screenPolicy();
    android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
    list.setOrientation(android.widget.LinearLayout.VERTICAL);
    list.setPadding(dp(20), dp(4), dp(20), dp(4));
    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    if (policy.rules.isEmpty()) {
      TextView empty = new TextView(ctx);
      empty.setTextSize(13);
      empty.setTextColor(ctx.getColor(R.color.shell_text_secondary));
      empty.setPadding(0, dp(8), 0, dp(8));
      empty.setText(
          com.deepseekharness.app.util.UiText.choose(
              "还没有应用规则。添加后，这些应用按规则处理，其他应用仍询问。",
              "No app rules yet. Added apps follow their rule; other apps still ask."));
      list.addView(empty);
    }
    for (var entry : policy.rules.entrySet()) {
      String pkg = entry.getKey();
      View row =
          ScreenAppPicker.row(
              ctx, ScreenAppPicker.describe(ctx, pkg), screenRuleLabel(entry.getValue()), this::dp);
      row.setOnClickListener(
          v -> {
            if (holder[0] != null) holder[0].dismiss();
            editScreenRule(pkg, false);
          });
      list.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.addView(list);
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(
                com.deepseekharness.app.util.UiText.choose("应用确认规则", "App confirmation rules"))
            .setView(scroll)
            .setNegativeButton(
                com.deepseekharness.app.util.UiText.choose("添加应用", "Add app"),
                (dialog, which) -> pickScreenApp())
            .setPositiveButton(com.deepseekharness.app.util.UiText.text("知道了"), null)
            .show();
  }

  private void pickScreenApp() {
    if (!isAdded()) return;
    var policy = new DeviceGrants(requireContext()).screenPolicy();
    java.util.Map<String, String> badges = new java.util.HashMap<>();
    for (var entry : policy.rules.entrySet())
      badges.put(entry.getKey(), screenRuleLabel(entry.getValue()));
    ScreenAppPicker.show(
        requireContext(),
        badges,
        pkg -> {
          if (isAdded()) editScreenRule(pkg, !badges.containsKey(pkg));
        },
        () -> {
          if (isAdded()) editScreenRule("", true);
        });
  }

  /**
   * 编辑单个应用规则。pkg 为空时手动输入包名；从选择器新加的应用默认「允许」（加规则的目的就是免确认）。
   */
  private void editScreenRule(String existing, boolean fresh) {
    var ctx = requireContext();
    var content = new android.widget.LinearLayout(ctx);
    content.setOrientation(android.widget.LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(4), dp(20), 0);
    var pkg = new androidx.appcompat.widget.AppCompatEditText(ctx);
    pkg.setSingleLine(true);
    pkg.setHint(com.deepseekharness.app.util.UiText.choose("精确应用包名", "Exact app package name"));
    pkg.setText(existing);
    if (existing.isEmpty()) content.addView(pkg);
    else {
      View header =
          ScreenAppPicker.row(ctx, ScreenAppPicker.describe(ctx, existing), null, this::dp);
      header.setClickable(false);
      header.setFocusable(false);
      header.setBackground(null);
      content.addView(header);
    }
    var allow = new MaterialSwitch(ctx);
    allow.setText(
        com.deepseekharness.app.util.UiText.choose(
            "允许该应用免确认（含敏感界面）", "Allow this app without confirmation, including sensitive screens"));
    allow.setChecked(
        fresh
            || new DeviceGrants(ctx).screenPolicy().rules.get(existing)
                == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW);
    content.addView(allow);
    var scroll = new android.widget.ScrollView(ctx);
    scroll.setFillViewport(true);
    scroll.addView(content);
    var builder =
        new DshaDialogBuilder(ctx)
            .setTitle(
                com.deepseekharness.app.util.UiText.choose(
                    "编辑应用确认规则", "Edit app confirmation rules"))
            .setView(scroll)
            .setPositiveButton(
                com.deepseekharness.app.util.UiText.text("保存"),
                (dialog, which) -> {
                  String key = pkg.getText().toString().trim();
                  if (!com.deepseekharness.app.util.ScreenConfirmationPolicy.validPackage(key)) {
                    toast(
                        com.deepseekharness.app.util.UiText.choose(
                            "应用包名无效，规则未保存", "Invalid package name. Rule was not saved"));
                    return;
                  }
                  var grants = new DeviceGrants(ctx);
                  var current = grants.screenPolicy();
                  var rules = new java.util.LinkedHashMap<>(current.rules);
                  rules.put(
                      key,
                      allow.isChecked()
                          ? com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
                          : com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ASK);
                  if (!grants.setScreenPolicy(current.mode, rules))
                    toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
                  refreshScreenPolicy();
                })
            .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null);
    if (!fresh && !existing.isEmpty())
      builder.setNeutralButton(
          com.deepseekharness.app.util.UiText.choose("删除规则", "Remove rule"),
          (dialog, which) -> {
            var grants = new DeviceGrants(ctx);
            var current = grants.screenPolicy();
            var rules = new java.util.LinkedHashMap<>(current.rules);
            rules.remove(existing);
            if (!grants.setScreenPolicy(current.mode, rules))
              toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
            refreshScreenPolicy();
          });
    builder.show();
  }

  private void savePreference(String key, boolean enabled) {
    boolean success =
        requireContext()
            .getSharedPreferences(Constants.PREFS, 0)
            .edit()
            .putBoolean(key, enabled)
            .commit();
    if (success && !enabled) com.deepseekharness.app.DeviceSense.revoke(requireContext(), key);
    if (!success) toast(com.deepseekharness.app.util.UiText.text("设置保存失败，请重试"));
  }

  private void syncSettings() {
    final Context app = requireContext().getApplicationContext();
    final View page = getView();
    new Thread(
            () -> {
              String result = AdbBridge.applySettings(app, HarnessController.get(app).proot());
              main.post(
                  () -> {
                    if (!isAdded() || page == null || getView() != page) return;
                    TextView status = page.findViewById(R.id.config_guard_status);
                    status.setVisibility(View.VISIBLE);
                    status.setText(
                        com.deepseekharness.app.util.UiText.format(
                            "原生授权即时生效；%s", result.replaceFirst("^SETTINGS_[A-Z]+: ", "")));
                  });
            },
            "device-settings")
        .start();
  }

  private void verify(View button, TextView status, java.util.function.Supplier<String> probe) {
    final View page = getView();
    button.setEnabled(false);
    status.setText(com.deepseekharness.app.util.UiText.text("正在验证设备通道…"));
    status.setTag(Boolean.TRUE);
    new Thread(
            () -> {
              String output;
              try {
                output = probe.get();
              } catch (Throwable error) {
                output = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error));
              }
              final String result = output;
              main.post(
                  () -> {
                    if (!isAdded() || getView() != page) return;
                    button.setEnabled(true);
                    status.setTag(null);
                    new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                        .setTitle(com.deepseekharness.app.util.UiText.text("设备通道验证"))
                        .setMessage(com.deepseekharness.app.util.UiText.raw(result))
                        .setPositiveButton(com.deepseekharness.app.util.UiText.text("知道了"), null)
                        .show();
                    refreshChannelLabels();
                  });
            },
            "device-channel-probe")
        .start();
  }

  private void openShizuku() {
    Intent launch = com.deepseekharness.app.ShizukuManagerCompat.launchIntent(requireContext());
    if (launch == null) {
      toast(
          com.deepseekharness.app.util.UiText.choose(
              "尚未安装 Shizuku 或 Stellar，请先安装并在管理器中启动服务",
              "Shizuku or Stellar is not installed; install one and start its service first."));
      return;
    }
    try {
      startActivity(launch);
    } catch (RuntimeException e) {
      toast(
          com.deepseekharness.app.util.UiText.format(
              "无法打开 Shizuku：%s", e.getClass().getSimpleName()));
    }
  }

  private void refreshChannelLabels() {
    View page = getView();
    if (page == null) return;
    TextView shizuku = page.findViewById(R.id.workspace_shizuku_status);
    TextView root = page.findViewById(R.id.device_root_status);
    if (shizuku.getTag() == null)
      shizuku.setText(
          com.deepseekharness.app.util.UiStateText.render(
              ShizukuShell.userStatus(requireContext())));
    // 识别到 Stellar（Shizuku 兼容管理器）时按实际管理器命名；协议仍是 Shizuku 兼容层。
    boolean stellar = com.deepseekharness.app.ShizukuManagerCompat.isStellar(requireContext());
    // 行内按钮只放短动词；完整含义（验证 Stellar 连接 / 授权 Shizuku …）交给读屏。
    TextView auth = page.findViewById(R.id.workspace_shizuku_auth);
    boolean granted = ShizukuShell.hasPermission();
    auth.setText(granted ? UiText.choose("验证", "Verify") : UiText.choose("授权", "Authorize"));
    auth.setContentDescription(
        granted
            ? stellar
                ? UiText.choose("验证 Stellar 连接", "Verify Stellar connection")
                : UiText.text("验证 Shizuku 连接")
            : stellar
                ? UiText.choose("授权 Stellar", "Authorize Stellar")
                : UiText.text("授权 Shizuku"));
    TextView open = page.findViewById(R.id.device_shizuku_open);
    open.setText(UiText.choose("打开", "Open"));
    open.setContentDescription(
        stellar ? UiText.choose("打开 Stellar", "Open Stellar") : getString(R.string.ui_m0125));
    TextView title = page.findViewById(R.id.device_shizuku_title);
    if (title != null)
      title.setText(stellar ? "Shizuku / Stellar" : getString(R.string.ui_brand_shizuku));
    if (root.getTag() == null) root.setText(rootStatusWithoutPolicy(requireContext()));
    refreshRootPolicy();
  }

  /**
   * RootShell.status 末行是拦截档位；本页档位已单独显示，这里去掉重复的那一行。紧凑行只显示一行，
   * 剩余各行用「 · 」接成一行，避免「su 路径」占满后把授权状态截掉；点按仍看全文。
   */
  private static String rootStatusWithoutPolicy(Context ctx) {
    String status = RootShell.status(ctx);
    int cut = status.lastIndexOf('\n');
    String shown = RootShell.enabled(ctx) && cut > 0 ? status.substring(0, cut) : status;
    return shown.trim().replaceAll("\\s*\\n\\s*", " · ");
  }

  private static final DeviceShellPolicy.Mode[] ROOT_MODES = {
    DeviceShellPolicy.Mode.STRICT, DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.FULL
  };

  /** Root 命令拦截三段：严格 / 放宽 / 完全放宽。点放宽档直接进入红色确认（勾选三项 + 10 秒）。 */
  private void addRootModeControls(Context ctx, View page) {
    rootModes = page.findViewById(R.id.device_root_modes);
    String[] names = {
      UiText.choose("严格", "Strict"),
      UiText.choose("放宽", "Relaxed"),
      UiText.choose("完全放宽", "Fully relaxed")
    };
    String[] spoken = {
      UiText.format(
          "%s, %s",
          UiText.text("严格（默认）"), UiText.choose("仅放行白名单内的命令", "Only allowlisted commands run")),
      UiText.format(
          "%s, %s",
          UiText.choose("放宽", "Relaxed"),
          UiText.choose("允许任意命令，仍拦截不可逆操作", "Any command; irreversible operations still blocked")),
      UiText.format(
          "%s, %s",
          UiText.choose("完全放宽", "Fully relaxed"),
          UiText.choose("不按命令内容拦截", "No command-content checks"))
    };
    rootModeButtons =
        buildSegment(
            ctx,
            rootModes,
            names,
            spoken,
            new int[] {R.color.shell_good, R.color.shell_warn, R.color.shell_bad},
            index -> pickRootMode(ROOT_MODES[index]));
  }

  /** 档位分段始终显示已保存值；只有确认保存后才会切换。 */
  private void pickRootMode(DeviceShellPolicy.Mode mode) {
    if (!isAdded()) return;
    final Context ctx = requireContext();
    DeviceShellPolicy.Mode current = new ConfigStore(ctx).rootPolicyMode();
    refreshRootPolicy();
    if (!RootShell.enabled(ctx)) {
      toast(UiText.text("请先启用「允许 Root Shell」"));
      return;
    }
    if (mode != current) confirmRootPolicy(mode);
  }

  /**
   * 分段选择：放在 bg_plugin_segment_track 轨道里的等宽 RadioButton；选中段用对应风险色的文字。
   * 点击由 onPick 处理，调用方负责按已保存值重画（RadioButton 自带的选中切换会被覆盖）。
   */
  private android.widget.RadioButton[] buildSegment(
      Context ctx,
      android.widget.RadioGroup group,
      String[] labels,
      String[] descriptions,
      int[] tones,
      java.util.function.IntConsumer onPick) {
    android.widget.RadioButton[] buttons = new android.widget.RadioButton[labels.length];
    int muted = ctx.getColor(R.color.shell_text_secondary);
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      android.widget.RadioButton button = new android.widget.RadioButton(ctx);
      button.setId(View.generateViewId());
      button.setButtonDrawable(null);
      button.setBackgroundResource(R.drawable.bg_update_channel_tab);
      button.setGravity(android.view.Gravity.CENTER);
      button.setIncludeFontPadding(false);
      button.setSingleLine(true);
      button.setEllipsize(android.text.TextUtils.TruncateAt.END);
      button.setTextSize(13);
      button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
      button.setText(labels[i]);
      button.setContentDescription(descriptions[i]);
      button.setTextColor(
          new android.content.res.ColorStateList(
              new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
              new int[] {ctx.getColor(tones[i]), muted}));
      button.setMinHeight(dp(36));
      button.setMinimumHeight(dp(36));
      button.setPadding(dp(6), 0, dp(6), 0);
      button.setOnClickListener(v -> onPick.accept(index));
      android.widget.RadioGroup.LayoutParams params =
          new android.widget.RadioGroup.LayoutParams(0, -2, 1f);
      if (i > 0) params.setMarginStart(dp(2));
      group.addView(button, params);
      buttons[i] = button;
    }
    return buttons;
  }

  /** 当前档位按已保存值选中；放宽 / 完全放宽时显示红色警告框。Root 未启用时分段变淡。 */
  private void refreshRootPolicy() {
    if (rootModeButtons == null || !isAdded()) return;
    Context ctx = requireContext();
    DeviceShellPolicy.Mode mode = new ConfigStore(ctx).rootPolicyMode();
    boolean enabled = RootShell.enabled(ctx);
    updating = true;
    for (int i = 0; i < rootModeButtons.length; i++)
      rootModeButtons[i].setChecked(ROOT_MODES[i] == mode);
    updating = false;
    rootModes.setAlpha(enabled ? 1f : 0.5f);
    View page = getView();
    TextView warning = page == null ? null : page.findViewById(R.id.device_root_policy_warning);
    if (warning == null) return;
    boolean relaxed = enabled && mode != DeviceShellPolicy.Mode.STRICT;
    warning.setVisibility(relaxed ? View.VISIBLE : View.GONE);
    refreshSummary();
    if (!relaxed) return;
    boolean full = mode == DeviceShellPolicy.Mode.FULL;
    String detail =
        full
            ? UiText.choose(
                "严重风险：已完全放宽。助手和插件可用 root 执行任意命令，包括格式化、刷机、清除数据等不可逆操作，可能导致数据丢失或系统损坏。不用时请改回严格。",
                "Severe risk: fully relaxed. The assistant and plugins can run any root command, including irreversible ones such as formatting, flashing or wiping data. This may cause data loss or system damage. Switch back to strict when not needed.")
            : UiText.choose(
                "高风险：已放宽。助手和插件可用 root 执行白名单以外的任意命令；仅拦截格式化、刷机、清除数据等不可逆操作。不用时请改回严格。",
                "High risk: relaxed. The assistant and plugins can run root commands beyond the allowlist; only irreversible operations such as formatting, flashing or wiping data are blocked. Switch back to strict when not needed.");
    // 行内只留一句（含「严重风险 / 高风险」与后果），点按看完整原文；读屏直接读完整原文。
    warning.setText(
        full
            ? UiText.choose(
                "严重风险：root 可执行任意命令，含格式化、刷机等不可逆操作",
                "Severe risk: root can run any command, including irreversible ones")
            : UiText.choose(
                "高风险：root 可执行白名单外命令，仅拦截不可逆操作",
                "High risk: root can run commands beyond the allowlist"));
    warning.setTag(R.id.device_root_policy_warning, detail);
    warning.setContentDescription(detail);
  }

  /** 「设备执行通道」标签末尾加 ⓘ：Root / Shizuku / ADB 的长说明与拦截档位作用范围合在一处。 */
  private void bindChannelInfo(Context ctx, View page) {
    bindLabelInfo(
        ctx,
        page.findViewById(R.id.grants_channels_label),
        getString(R.string.ui2_device_channels),
        () ->
            UiText.format(
                "Root: %s\n\n%s\n\nShizuku: %s\n\nADB: %s",
                getString(R.string.ui134_root_hint),
                UiText.choose(
                    "命令拦截档位仅对 root 通道生效；Shizuku/ADB 通用执行器仍按严格档。",
                    "Command filtering levels apply only to the root channel; Shizuku/ADB general executors stay strict."),
                com.deepseekharness.app.ShizukuManagerCompat.isStellar(ctx)
                    ? UiText.choose(
                        "已识别 Stellar（Shizuku 兼容管理器）。请在 Stellar 中启动服务并开启「Shizuku 兼容层」，再返回授权；手机重启后通常需要重新启动服务。",
                        "Stellar (a Shizuku-compatible manager) is detected. Start its service and enable the Shizuku compatibility layer, then authorize here. Restart the service after reboot.")
                    : getString(R.string.ui134_shizuku_hint),
                getString(R.string.ui134_adb_hint)));
  }

  /** 标签末尾加 ⓘ，点按打开完整说明；保留标签原有的起始图标。说明在点按时生成，跟随当前状态。 */
  private void bindLabelInfo(
      Context ctx, TextView label, String title, java.util.function.Supplier<String> full) {
    android.graphics.drawable.Drawable icon = ctx.getDrawable(R.drawable.ic_onb_info);
    if (icon != null) {
      icon = icon.mutate();
      icon.setBounds(0, 0, dp(16), dp(16));
      icon.setTintList(
          android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.shell_accent)));
    }
    android.graphics.drawable.Drawable start = label.getCompoundDrawablesRelative()[0];
    label.setCompoundDrawablesRelative(start, null, icon, null);
    label.setCompoundDrawablePadding(dp(6));
    label.setBackgroundResource(R.drawable.bg_action_plain);
    label.setMinHeight(dp(32));
    label.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
    label.setPaddingRelative(label.getPaddingStart(), 0, dp(6), 0);
    label.setContentDescription(
        UiText.format("%s %s", title, UiText.choose("（点按查看完整说明）", "(tap for details)")));
    label.setOnClickListener(v -> InfoHint.show(ctx, v, title, full.get()));
  }

  // ===== 固定概览头部 =====

  private static final int PILL_GLOBAL = 0, PILL_ROOT = 1, PILL_SHIZUKU = 2, PILL_SCREEN = 3;

  /** 四个概览胶囊（全局 / Root / Shizuku / 屏幕）+ 页面说明 ⓘ；点胶囊滚到对应分组。 */
  private void buildSummaryHeader(Context ctx, View page) {
    android.widget.LinearLayout header = page.findViewById(R.id.grants_header);
    int[] targets = {
      R.id.grants_section_global,
      R.id.grants_section_channels,
      R.id.grants_section_channels,
      R.id.grants_section_screen
    };
    pills = new View[4];
    pillDots = new View[4];
    pillTitles = new TextView[4];
    pillValues = new TextView[4];
    for (int i = 0; i < 4; i++) {
      android.widget.LinearLayout pill = new android.widget.LinearLayout(ctx);
      pill.setOrientation(android.widget.LinearLayout.VERTICAL);
      pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
      pill.setBackgroundResource(R.drawable.bg_grants_pill);
      pill.setMinimumHeight(dp(44));
      pill.setPadding(dp(8), dp(5), dp(6), dp(5));
      pill.setClickable(true);
      pill.setFocusable(true);
      android.widget.LinearLayout top = new android.widget.LinearLayout(ctx);
      top.setGravity(android.view.Gravity.CENTER_VERTICAL);
      View dot = new View(ctx);
      dot.setBackgroundResource(R.drawable.bg_update_dot);
      android.widget.LinearLayout.LayoutParams dotParams =
          new android.widget.LinearLayout.LayoutParams(dp(7), dp(7));
      dotParams.setMarginEnd(dp(5));
      top.addView(dot, dotParams);
      TextView title = new TextView(ctx);
      title.setTextSize(12);
      title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
      title.setTextColor(ctx.getColor(R.color.shell_text));
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setIncludeFontPadding(false);
      top.addView(title, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
      pill.addView(top, new android.widget.LinearLayout.LayoutParams(-1, -2));
      TextView value = new TextView(ctx);
      value.setTextSize(11);
      value.setTextColor(ctx.getColor(R.color.shell_text_secondary));
      value.setSingleLine(true);
      value.setEllipsize(android.text.TextUtils.TruncateAt.END);
      value.setIncludeFontPadding(false);
      android.widget.LinearLayout.LayoutParams valueParams =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      valueParams.topMargin = dp(3);
      valueParams.setMarginStart(dp(12));
      pill.addView(value, valueParams);
      final int target = targets[i];
      pill.setOnClickListener(v -> scrollToSection(target));
      android.widget.LinearLayout.LayoutParams params =
          new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
      if (i > 0) params.setMarginStart(dp(6));
      header.addView(pill, params);
      pills[i] = pill;
      pillDots[i] = dot;
      pillTitles[i] = title;
      pillValues[i] = value;
    }
    android.widget.ImageButton info = new android.widget.ImageButton(ctx);
    info.setImageResource(R.drawable.ic_onb_info);
    info.setImageTintList(
        android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.shell_accent)));
    info.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
    info.setPadding(dp(12), dp(12), dp(12), dp(12));
    info.setBackgroundResource(R.drawable.bg_action_plain);
    info.setContentDescription(UiText.choose("页面说明", "About this page"));
    info.setOnClickListener(
        v ->
            InfoHint.show(
                ctx, getString(R.string.ui2_device_grants), getString(R.string.ui2_grants_sub)));
    header.addView(info, new android.widget.LinearLayout.LayoutParams(dp(44), dp(44)));
  }

  private void scrollToSection(int id) {
    View page = getView();
    if (page == null) return;
    android.widget.ScrollView scroll = page.findViewById(R.id.grants_scroll);
    View target = page.findViewById(id);
    View content = page.findViewById(R.id.grants_content);
    if (scroll == null || target == null || content == null) return;
    android.graphics.Rect rect = new android.graphics.Rect();
    target.getDrawingRect(rect);
    ((android.view.ViewGroup) content).offsetDescendantRectToMyCoords(target, rect);
    scroll.smoothScrollTo(0, Math.max(0, rect.top - dp(6)));
  }

  /** 概览只读：状态来自已有授权与通道判据，映射规则在 GrantSummary（有单测）。 */
  private void refreshSummary() {
    if (pillValues == null || !isAdded()) return;
    Context ctx = requireContext();
    GrantSummary.Item[] items = {
      GrantSummary.global(new DeviceGrants(ctx).consentSnapshot().readState()),
      GrantSummary.root(
          RootShell.enabled(ctx), RootShell.present(), new ConfigStore(ctx).rootPolicyMode()),
      GrantSummary.shizuku(ShizukuShell.step(ctx)),
      summaryScreen(ctx)
    };
    boolean stellar = com.deepseekharness.app.ShizukuManagerCompat.isStellar(ctx);
    String[] titles = {
      UiText.choose("全局", "Global"),
      "Root",
      stellar ? "Stellar" : "Shizuku",
      UiText.choose("屏幕", "Screen")
    };
    for (int i = 0; i < items.length; i++) {
      String value = summaryText(items[i].label);
      pillTitles[i].setText(UiText.raw(titles[i]));
      pillValues[i].setText(value);
      pillDots[i].setBackgroundTintList(
          android.content.res.ColorStateList.valueOf(ctx.getColor(summaryTone(items[i].tone))));
      pills[i].setContentDescription(UiText.format("%s: %s", titles[i], value));
    }
  }

  private static GrantSummary.Item summaryScreen(Context ctx) {
    boolean enabled = "YES".equals(DshaAccessibilityService.enabledState(ctx));
    return GrantSummary.screen(
        enabled,
        enabled && DshaAccessibilityService.connected(),
        com.deepseekharness.app.HttpShellService.screenPaused(),
        new DeviceGrants(ctx).screenPolicy().mode);
  }

  private static int summaryTone(GrantSummary.Tone tone) {
    return switch (tone) {
      case GOOD -> R.color.shell_good;
      case WARN -> R.color.shell_warn;
      case BAD -> R.color.shell_bad;
      case NEUTRAL -> R.color.shell_text_muted;
    };
  }

  private static String summaryText(GrantSummary.Label label) {
    return switch (label) {
      case GLOBAL_ON -> UiText.choose("已开启", "On");
      case GLOBAL_OFF -> UiText.choose("关闭", "Off");
      case GLOBAL_ATTENTION -> UiText.choose("需处理", "Check");
      case GLOBAL_UPDATING -> UiText.choose("更新中", "Updating");
      case ROOT_OFF -> UiText.choose("未启用", "Off");
      case ROOT_NO_SU -> UiText.choose("未找到 su", "No su");
      case ROOT_STRICT -> UiText.choose("严格", "Strict");
      case ROOT_RELAXED -> UiText.choose("放宽", "Relaxed");
      case ROOT_FULL -> UiText.choose("完全放宽", "Fully relaxed");
      case SHIZUKU_READY -> UiText.choose("已连接", "Connected");
      case SHIZUKU_NOT_AUTHORIZED -> UiText.choose("未授权", "Not authorized");
      case SHIZUKU_BINDING -> UiText.choose("连接中", "Connecting");
      case SHIZUKU_FAILED -> UiText.choose("连接失败", "Failed");
      case SHIZUKU_NOT_RUNNING -> UiText.choose("未运行", "Not running");
      case SCREEN_NOT_SET -> UiText.choose("未设置", "Not set up");
      case SCREEN_CONNECTING -> UiText.choose("连接中", "Connecting");
      case SCREEN_PAUSED -> UiText.choose("已暂停", "Paused");
      case SCREEN_MANUAL -> UiText.choose("全部手动", "Ask always");
      case SCREEN_CUSTOM -> UiText.choose("按应用", "By app");
      case SCREEN_ALLOW_ALL -> UiText.choose("全部允许", "Allow all");
    };
  }

  // ===== 平板 / 宽窗格：按实际宽度分栏 =====

  /**
   * 内容宽 ≥640dp 时把屏幕操作与敏感能力移到右栏，并把内容收进上限宽度居中；回到窄宽度时
   * 按手机顺序（全局 / 通道 / 屏幕 / 系统 / 敏感）放回左栏。只在列数变化时搬动视图。
   */
  private void adaptColumns(View page) {
    View scroll = page.findViewById(R.id.grants_scroll);
    View content = page.findViewById(R.id.grants_content);
    int base = getResources().getDimensionPixelSize(R.dimen.grants_page_pad);
    scroll.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (r - l == or - ol) return;
          float density = getResources().getDisplayMetrics().density;
          int widthDp = Math.round((r - l) / density);
          int baseDp = Math.round(base / density);
          int side = Math.round(GrantSummary.sidePadding(widthDp, baseDp) * density);
          int wanted = GrantSummary.columns(widthDp - 2 * baseDp);
          v.post(
              () -> {
                if (getView() != page) return;
                if (side != content.getPaddingStart())
                  content.setPaddingRelative(
                      side, content.getPaddingTop(), side, content.getPaddingBottom());
                if (wanted != columns) applyColumns(page, wanted);
              });
        });
  }

  private void applyColumns(View page, int count) {
    columns = count;
    android.view.ViewGroup start = page.findViewById(R.id.grants_col_start);
    android.view.ViewGroup end = page.findViewById(R.id.grants_col_end);
    int[] order =
        count == 2
            ? new int[] {
              R.id.grants_section_global, R.id.grants_section_channels, R.id.grants_section_system
            }
            : new int[] {
              R.id.grants_section_global,
              R.id.grants_section_channels,
              R.id.grants_section_screen,
              R.id.grants_section_system,
              R.id.grants_section_sensitive
            };
    int[] right =
        count == 2
            ? new int[] {R.id.grants_section_screen, R.id.grants_section_sensitive}
            : new int[0];
    place(page, start, order);
    place(page, end, right);
    end.setVisibility(count == 2 ? View.VISIBLE : View.GONE);
  }

  /** 按给定顺序把分组放进栏内；每栏第一个分组不留顶部间距，保证两栏顶端对齐。 */
  private void place(View page, android.view.ViewGroup column, int[] ids) {
    int gap = getResources().getDimensionPixelSize(R.dimen.grants_section_gap);
    for (int i = 0; i < ids.length; i++) {
      View section = page.findViewById(ids[i]);
      if (section.getParent() != null)
        ((android.view.ViewGroup) section.getParent()).removeView(section);
      android.widget.LinearLayout.LayoutParams params =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      params.topMargin = i == 0 ? 0 : gap;
      column.addView(section, params);
    }
  }

  /** 放宽档位会扩大助手与插件的 root 权限，保存前必须再确认一次风险。 */
  private void confirmRootPolicy(DeviceShellPolicy.Mode mode) {
    final Context ctx = requireContext();
    if (mode == DeviceShellPolicy.Mode.STRICT) {
      saveRootPolicy(mode);
      return;
    }
    boolean full = mode == DeviceShellPolicy.Mode.FULL;
    int bad = ctx.getColor(R.color.shell_bad);
    // 高危确认：红色标题与警告框；点空白处不关闭，只能明确点「取消」或返回键。
    android.text.SpannableString title =
        new android.text.SpannableString(
            com.deepseekharness.app.util.UiText.text("确认放宽 root 命令拦截？"));
    title.setSpan(
        new android.text.style.ForegroundColorSpan(bad),
        0,
        title.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView warning = new TextView(ctx);
    warning.setBackgroundResource(R.drawable.bg_root_policy_warning);
    warning.setTextColor(bad);
    warning.setTextSize(14);
    warning.setTypeface(warning.getTypeface(), android.graphics.Typeface.BOLD);
    warning.setLineSpacing(dp(3), 1f);
    warning.setPadding(dp(14), dp(12), dp(14), dp(12));
    warning.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_section_warning, 0, 0, 0);
    warning.setCompoundDrawablePadding(dp(10));
    warning.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(bad));
    warning.setText(
        full
            ? com.deepseekharness.app.util.UiText.choose(
                "严重风险：完全放宽后不按命令内容拦截。助手和插件可用 root 执行格式化、刷机、清除数据等不可逆操作，可能导致数据丢失或系统损坏。",
                "Severe risk: fully relaxed mode does not inspect command content. The assistant and plugins can run irreversible root operations such as formatting, flashing or wiping data, which may cause data loss or system damage.")
            : com.deepseekharness.app.util.UiText.choose(
                "高风险：放宽后助手和插件可用 root 执行白名单以外的任意命令；仍拦截格式化、刷机、清除数据等不可逆操作。",
                "High risk: relaxed mode lets the assistant and plugins run root commands beyond the allowlist; irreversible operations such as formatting, flashing or wiping data stay blocked."));
    TextView scope = new TextView(ctx);
    scope.setTextSize(12);
    scope.setTextColor(ctx.getColor(R.color.shell_text_secondary));
    scope.setText(
        com.deepseekharness.app.util.UiText.choose(
            "仅对 root 通道生效；Shizuku/ADB 仍按严格档。可随时改回严格。",
            "Applies to the root channel only; Shizuku/ADB stay strict. You can switch back to strict anytime."));
    android.widget.LinearLayout body = new android.widget.LinearLayout(ctx);
    body.setOrientation(android.widget.LinearLayout.VERTICAL);
    body.setPadding(dp(24), dp(8), dp(24), 0);
    body.addView(warning, new android.widget.LinearLayout.LayoutParams(-1, -2));
    android.widget.LinearLayout.LayoutParams scopeParams =
        new android.widget.LinearLayout.LayoutParams(-1, -2);
    scopeParams.topMargin = dp(10);
    body.addView(scope, scopeParams);
    // 免责确认：三项都勾选，且十秒等待结束，确认按钮才可用。
    String[] acknowledgements = {
      com.deepseekharness.app.util.UiText.choose(
          "我了解：放宽后由助手或插件执行的 root 命令可能损坏数据或系统，且可能无法恢复。",
          "I understand that root commands run by the assistant or plugins may damage data or the system, possibly beyond recovery."),
      com.deepseekharness.app.util.UiText.choose(
          "我已备份重要数据，并会在不需要时改回严格。",
          "I have backed up important data and will switch back to strict when it is no longer needed."),
      com.deepseekharness.app.util.UiText.choose(
          "由此产生的后果由我自行承担，DSHA 及其开发者不承担责任。",
          "I accept the consequences myself; DSHA and its developers are not responsible.")
    };
    java.util.List<android.widget.CheckBox> checks = new java.util.ArrayList<>();
    for (String acknowledgement : acknowledgements) {
      android.widget.CheckBox check = new androidx.appcompat.widget.AppCompatCheckBox(ctx);
      check.setText(acknowledgement);
      check.setTextSize(13);
      check.setTextColor(ctx.getColor(R.color.shell_text));
      check.setButtonTintList(android.content.res.ColorStateList.valueOf(bad));
      check.setMinHeight(dp(44));
      android.widget.LinearLayout.LayoutParams checkParams =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      checkParams.topMargin = dp(checks.isEmpty() ? 12 : 4);
      body.addView(check, checkParams);
      checks.add(check);
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.addView(body);
    String confirmLabel =
        full
            ? com.deepseekharness.app.util.UiText.choose("仍要完全放宽", "Fully relax anyway")
            : com.deepseekharness.app.util.UiText.choose("仍要放宽", "Relax anyway");
    long startedAt = android.os.SystemClock.elapsedRealtime();
    int waitSeconds = com.deepseekharness.app.util.ConfirmCountdown.ROOT_RELAX_SECONDS;
    java.util.function.BooleanSupplier allChecked =
        () -> {
          for (android.widget.CheckBox check : checks) if (!check.isChecked()) return false;
          return true;
        };
    androidx.appcompat.app.AlertDialog dialog =
        new DshaDialogBuilder(ctx)
            .setTitle(title)
            .setView(scroll)
            // 取消放在最右（拇指位、默认安全）；高危确认放在左侧，需要刻意去点。
            .setPositiveButton(com.deepseekharness.app.util.UiText.text("取消"), null)
            .setNegativeButton(
                confirmLabel,
                (d, which) -> {
                  // 双保险：保存前再核对等待与勾选，不只依赖按钮可用状态。
                  if (allChecked.getAsBoolean()
                      && com.deepseekharness.app.util.ConfirmCountdown.remainingSeconds(
                              startedAt, android.os.SystemClock.elapsedRealtime(), waitSeconds)
                          == 0) saveRootPolicy(mode);
                })
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
    android.widget.Button confirm =
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
    if (confirm == null) return;
    confirm.setTextColor(bad);
    // 十秒强制等待：倒计时显示在确认按钮上；「取消」与返回键始终可用。
    Runnable tick =
        new Runnable() {
          @Override
          public void run() {
            if (!dialog.isShowing()) return;
            int left =
                com.deepseekharness.app.util.ConfirmCountdown.remainingSeconds(
                    startedAt, android.os.SystemClock.elapsedRealtime(), waitSeconds);
            boolean ready = left == 0 && allChecked.getAsBoolean();
            confirm.setEnabled(ready);
            confirm.setAlpha(ready ? 1f : 0.45f);
            confirm.setText(
                left > 0
                    ? com.deepseekharness.app.util.UiText.raw(confirmLabel + " (" + left + ")")
                    : confirmLabel);
            // 勾选变化也会调用本方法：先清掉已排队的一次，保证只有一个倒计时循环。
            confirm.removeCallbacks(this);
            if (left > 0) confirm.postDelayed(this, 250);
          }
        };
    for (android.widget.CheckBox check : checks)
      check.setOnCheckedChangeListener((button, checked) -> tick.run());
    tick.run();
    dialog.setOnDismissListener(d -> confirm.removeCallbacks(tick));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void saveRootPolicy(DeviceShellPolicy.Mode mode) {
    if (!isAdded()) return;
    final Context ctx = requireContext();
    new ConfigStore(ctx).setRootPolicyMode(mode);
    refreshRootPolicy();
    refreshChannelLabels();
    toast(com.deepseekharness.app.util.UiText.format("已保存拦截档位：%s", RootShell.policyLabel(ctx)));
  }

  private void saveSms(boolean enabled) {
    boolean saved = new DeviceGrants(requireContext()).setSmsReadAllowed(enabled);
    updateSms();
    toast(
        !saved
            ? com.deepseekharness.app.util.UiText.text("授权保存失败，请重试")
            : enabled
                ? com.deepseekharness.app.util.UiText.text("短信查询已预授权")
                : com.deepseekharness.app.util.UiText.choose("已关闭短信读取", "SMS reading disabled"));
  }

  private void updateSms() {
    if (sms == null || !isAdded()) return;
    boolean allowed = new DeviceGrants(requireContext()).smsReadAllowed();
    updating = true;
    sms.setChecked(allowed);
    updating = false;
    smsStatus.setText(
        allowed
            ? com.deepseekharness.app.util.UiText.text("已预授权 · 关闭可撤销")
            : com.deepseekharness.app.util.UiText.choose(
                "已关闭 · 查询会被阻止", "Off · queries are blocked"));
    smsStatus.setTextColor(
        requireContext().getColor(allowed ? R.color.primary : R.color.text_muted));
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request != 104 || !isAdded()) return;
    boolean granted =
        requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            || requireContext()
                    .checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    if (!granted) {
      savePreference("cap_location", false);
      if (getView() != null)
        ((CompoundButton) getView().findViewById(R.id.config_cap_location)).setChecked(false);
      new DshaDialogBuilder(requireContext())
          .setTitle(
              com.deepseekharness.app.util.UiText.choose(
                  "需要系统定位权限", "Location permission required"))
          .setMessage(
              com.deepseekharness.app.util.UiText.choose(
                  "位置读取保持关闭。请在系统应用权限中允许定位，再返回这里开启；如果系统定位服务关闭，也需要先开启系统定位。",
                  "Location reading remains off. Allow location in system app permissions, then return and enable it here. System location services must also be enabled."))
          .setNegativeButton(com.deepseekharness.app.util.UiText.choose("暂不开启", "Not now"), null)
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.choose("打开系统权限设置", "Open app permissions"),
              (dialog, which) -> {
                try {
                  startActivity(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:" + requireContext().getPackageName())));
                } catch (RuntimeException missing) {
                  toast(
                      com.deepseekharness.app.util.UiText.choose(
                          "请在系统设置中打开 DSHA 应用权限", "Open DSHA permissions in system settings"));
                }
              })
          .show();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshNativePolicyControls();
    if (fullPermissions != null) fullPermissions.resume();
    ShizukuShell.ensureBound(requireContext());
    updateSms();
    main.removeCallbacks(refreshConnections);
    main.post(refreshConnections);
    View page = getView();
    refreshExternalDeviceStatus();
    if (page != null) {
      refreshAllFilesStatus(page.findViewById(R.id.config_all_files_status));
      refreshA11yStatus(page.findViewById(R.id.config_a11y_status));
      refreshAdbStatus(page.findViewById(R.id.config_adb_status));
    }
    refreshScreenMode();
  }

  @Override
  public void onPause() {
    if (fullPermissions != null) fullPermissions.pause();
    main.removeCallbacks(refreshConnections);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    if (fullPermissions != null) {
      fullPermissions.close();
      fullPermissions = null;
    }
    adbStatusRequest++;
    sms = null;
    smsStatus = null;
    rootModes = null;
    rootModeButtons = null;
    screenModeButtons = null;
    pills = null;
    pillDots = null;
    pillTitles = null;
    pillValues = null;
    externalDevice = null;
    externalDeviceStatus = null;
    super.onDestroyView();
  }

  private boolean pref(Context ctx, String key, boolean fallback) {
    return ctx.getSharedPreferences(Constants.PREFS, 0).getBoolean(key, fallback);
  }

  private void toast(String value) {
    if (getContext() != null) Toast.makeText(getContext(), value, Toast.LENGTH_LONG).show();
  }

  /** 后台读 ADB 通道真实状态（key/deps/端口 + 保活服务的连接状态），刷到状态栏。 */
  private void refreshAdbStatus(final TextView status) {
    final Context app = requireContext().getApplicationContext();
    final View page = getView();
    final android.app.Activity activity = getActivity();
    if (activity == null) return;
    final int request = ++adbStatusRequest;
    new Thread(
            () -> {
              final String text = computeAdbStatus(app);
              activity.runOnUiThread(
                  () -> {
                    if (request != adbStatusRequest
                        || status == null
                        || getView() != page
                        || !isAdded()) return;
                    status.setText(com.deepseekharness.app.util.UiText.status(text));
                    if (text.startsWith("环境任务进行中") && isResumed())
                      status.postDelayed(
                          () -> {
                            if (request == adbStatusRequest && getView() == page && isResumed())
                              refreshAdbStatus(status);
                          },
                          1500);
                  });
            },
            "adb-status")
        .start();
  }

  private String computeAdbStatus(Context app) {
    try {
      if (!pref(app, "adb_enabled", false))
        return com.deepseekharness.app.util.UiText.text("ADB 已关闭。开启后才会保持连接。");
      String bridge = DeviceBridgeService.adbState;
      String detail = DeviceBridgeService.adbDetail == null ? "" : DeviceBridgeService.adbDetail;
      HarnessController hc = HarnessController.get(app);
      String st = hc.proot().isEnvironmentReady() ? AdbBridge.status(hc.proot()) : "env:not_ready";
      if (st.startsWith("ENVIRONMENT_BUSY") || "environment_busy".equals(bridge))
        return "环境任务进行中，ADB 暂停检查，完成后自动重试。" + (detail.isEmpty() ? "" : "\n" + detail);
      boolean key = st.contains("key=YES");
      String port = "?";
      int p = st.indexOf("port=");
      if (p >= 0) port = st.substring(p + 5).trim();
      if ("need_pair".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.text("配对已失效，请重新配对。");
      } else if ("reconnecting".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.format(
            "正在重连无线调试…%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else if ("connected".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.format(
            "连接已验证 · 端口 %s%s", port, (detail.isEmpty() ? "" : "\n" + detail));
      } else if (key) {
        return com.deepseekharness.app.util.UiText.format(
            "配对密钥已保存，连接尚未验证。%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else {
        return UiText.choose(
            "尚未配对：点「配对」，配对后还会验证连接。",
            "Not paired: tap Pair; the connection is verified after pairing.");
      }
    } catch (Throwable e) {
      return com.deepseekharness.app.util.UiText.format("ADB 状态读取失败：%s", e.getMessage());
    }
  }

  private void openBatteryOpt(Context ctx) {
    openBatterySettings(
        ctx,
        this::startActivity,
        () -> toast(com.deepseekharness.app.util.UiText.text("无法打开电池优化设置")));
  }

  /**
   * 只在用户点击后导航到系统设置；不申请或自动修改电池优化白名单。
   *
   * <p>ColorOS 16 的「耗电管理」页（PowerControlActivity）不对外导出，只能先打开本应用「关于 /
   * 应用详情」，再由用户点「耗电管理」。AOSP 的应用电池使用情况页作为次选。
   */
  static void openBatterySettings(
      Context context, java.util.function.Consumer<Intent> open, Runnable failed) {
    Uri app = Uri.parse("package:" + context.getPackageName());
    try {
      open.accept(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
      return;
    } catch (RuntimeException unavailable) {
      // 部分 ROM 拦截应用详情。
    }
    try {
      open.accept(new Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", app));
      return;
    } catch (RuntimeException unavailable) {
      // 没有应用电池页时再退回总名单。
    }
    try {
      open.accept(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    } catch (RuntimeException unavailable) {
      failed.run();
    }
  }

  /** 「所有文件访问」入口：Android 11+ 跳系统 MANAGE 设置；Android 6-10 请求 WRITE_EXTERNAL_STORAGE。 */
  private void openAllFilesAccess(Context ctx) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      try {
        Intent i =
            new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        startActivity(i);
      } catch (Throwable e) {
        try {
          startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable e2) {
          toast(com.deepseekharness.app.util.UiText.format("打开设置失败：%s", e2.getMessage()));
        }
      }
      return;
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      // Android 6-10：运行时请求 WRITE_EXTERNAL_STORAGE（Android 10 作用域存储下尽力而为）
      if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 501);
      } else {
        toast(com.deepseekharness.app.util.UiText.text("存储权限已授予，容器可访问手机存储"));
      }
      return;
    }
    toast(com.deepseekharness.app.util.UiText.text("当前系统无需存储权限"));
  }

  /** 刷新「所有文件访问权限」状态行（含从系统设置返回后的更新）。 */
  private void refreshAllFilesStatus(TextView status) {
    if (status == null) return;
    boolean granted;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      granted = Environment.isExternalStorageManager();
    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      granted =
          requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
    } else {
      granted = true;
    }
    status.setText(
        granted
            ? com.deepseekharness.app.util.UiText.choose(
                "已开启：可读写系统允许的共享存储；其他应用私有目录仍受系统限制",
                "Allowed: shared storage permitted by Android; other apps' private directories remain restricted")
            : com.deepseekharness.app.util.UiText.text("未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件"));
    try {
      status.setTextColor(
          granted
              // 调色板色经主题属性引用：必须带页面主题取色，不能传 null。
              ? requireContext().getColor(R.color.primary)
              : requireContext().getColor(R.color.err));
    } catch (Throwable ignored) {
    }
  }

  /**
   * 状态行：分别显示系统开关、实际连接与暂停。已连接时设置入口降为「重新设置」文字按钮，
   * 未连接时显示主按钮「设置屏幕操作 · 两步完成」。
   */
  private void refreshA11yStatus(TextView status) {
    if (status == null || !isAdded()) return;
    String st = DshaAccessibilityService.enabledState(requireContext());
    boolean enabled = "YES".equals(st);
    boolean connected = enabled && DshaAccessibilityService.connected();
    boolean paused = com.deepseekharness.app.HttpShellService.screenPaused();
    if (connected && paused) {
      status.setText(
          com.deepseekharness.app.util.UiText.choose(
              "已暂停：助手暂时不能读屏和操作", "Paused: the assistant cannot observe or act for now"));
    } else if (enabled) {
      status.setText(
          connected
              ? com.deepseekharness.app.util.UiText.choose(
                  "已连接，可以在对话中使用 Computer Use。",
                  "Connected. Computer Use is available in conversations.")
              : com.deepseekharness.app.util.UiText.choose(
                  "开关已开启，正在等待系统连接；可重新开关服务。",
                  "Enabled, waiting for the system to connect. Try toggling the service."));
    } else if ("NO".equals(st)) {
      status.setText(com.deepseekharness.app.util.UiText.choose("未开启", "Not set up"));
    } else {
      status.setText(com.deepseekharness.app.util.UiText.choose("尚未连接", "Not connected"));
    }
    int color =
        connected && !paused
            ? R.color.shell_accent
            : connected
                ? R.color.shell_warn
                : enabled ? R.color.shell_text_muted : R.color.shell_bad;
    status.setTextColor(requireContext().getColor(color));
    View page = getView();
    if (page == null) return;
    page.findViewById(R.id.computer_use_accessibility)
        .setVisibility(connected ? View.GONE : View.VISIBLE);
    page.findViewById(R.id.screen_resetup).setVisibility(connected ? View.VISIBLE : View.GONE);
  }
}
