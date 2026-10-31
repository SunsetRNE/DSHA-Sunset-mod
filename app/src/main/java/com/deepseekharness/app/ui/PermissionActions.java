package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.util.UiText;

/**
 * 授权卡片的只读状态与外跳动作。外跳只由用户点击触发；状态都在返回 DSHA 后重新读取，
 * “已打开设置页”不等于“已授权”。Intent 与回退顺序沿用 DeviceGrantsFragment / OverlayFragment /
 * AccessibilitySetupActivity 的现有入口。
 */
final class PermissionActions {
  private PermissionActions() {}

  // ---------------------------------------------------------------- 只读状态

  static boolean storage() {
    try {
      return Environment.isExternalStorageManager();
    } catch (RuntimeException unknown) {
      return false;
    }
  }

  static boolean overlay(Context context) {
    try {
      return Settings.canDrawOverlays(context);
    } catch (RuntimeException unknown) {
      return false;
    }
  }

  /** API 33+ 需要运行时权限且通知总开关打开；更低版本只看总开关。 */
  static boolean notifications(Context context) {
    try {
      if (Build.VERSION.SDK_INT >= 33
          && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
              != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
      return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled();
    } catch (RuntimeException unknown) {
      return false;
    }
  }

  /** 新增的只读检测：是否已不受电池优化限制。读取失败按“未设置”显示，不据此改动任何设置。 */
  static boolean batteryUnrestricted(Context context) {
    try {
      android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
      return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    } catch (RuntimeException unknown) {
      return false;
    }
  }

  /** 无障碍三态：YES / NO / UNKNOWN；UNKNOWN 不等于未开启。 */
  static String accessibilityState(Context context) {
    return DshaAccessibilityService.enabledState(context);
  }

  static boolean accessibilityConnected(Context context) {
    return "YES".equals(accessibilityState(context)) && DshaAccessibilityService.connected();
  }

  // ---------------------------------------------------------------- 外跳

  static void openAllFilesAccess(AppCompatActivity activity) {
    try {
      activity.startActivity(
          new Intent(
              Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
              Uri.parse("package:" + activity.getPackageName())));
    } catch (Throwable e) {
      try {
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      } catch (Throwable e2) {
        toast(activity, UiText.format("打开设置失败：%s", e2.getMessage()));
      }
    }
  }

  static void openBattery(AppCompatActivity activity) {
    DeviceGrantsFragment.openBatterySettings(
        activity, activity::startActivity, () -> toast(activity, UiText.text("无法打开电池优化设置")));
  }

  static void openOverlay(AppCompatActivity activity) {
    try {
      activity.startActivity(
          new Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              Uri.parse("package:" + activity.getPackageName())));
    } catch (RuntimeException unavailable) {
      try {
        activity.startActivity(
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
      } catch (RuntimeException ignored) {
        toast(
            activity,
            UiText.choose(
                "请在系统设置中允许 DSHA 显示在其他应用上层。",
                "Allow DSHA to display over other apps in system settings."));
      }
    }
  }

  static void openNotificationSettings(AppCompatActivity activity) {
    try {
      activity.startActivity(
          com.deepseekharness.app.assistant.AssistantService.notificationSettingsIntent(activity));
    } catch (RuntimeException unavailable) {
      toast(activity, UiText.choose("无法打开系统通知设置", "Could not open notification settings"));
    }
  }

  static void openAccessibility(AppCompatActivity activity) {
    AccessibilitySetupActivity.openSettings(activity);
  }

  static void openShizukuManager(AppCompatActivity activity) {
    Intent launch = com.deepseekharness.app.ShizukuManagerCompat.launchIntent(activity);
    if (launch == null) {
      toast(
          activity,
          UiText.choose(
              "尚未安装 Shizuku 或 Stellar，请先安装并在管理器中启动服务",
              "Shizuku or Stellar is not installed; install one and start its service first."));
      return;
    }
    try {
      activity.startActivity(launch);
    } catch (RuntimeException failure) {
      toast(activity, UiText.format("无法打开 Shizuku：%s", failure.getMessage()));
    }
  }

  static void toast(Context context, CharSequence text) {
    try {
      Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    } catch (RuntimeException ignored) {
    }
  }

  /**
   * 在后台执行一次设备通道验证（id），结果用对话框原样显示；与 DeviceGrantsFragment.verify 同语义。
   * 验证会触发外部授权，只能由用户点击调用。
   */
  static void verify(
      AppCompatActivity activity,
      android.view.View button,
      java.util.function.Supplier<String> probe,
      Runnable after) {
    button.setEnabled(false);
    new Thread(
            () -> {
              String output;
              try {
                output = probe.get();
              } catch (Throwable error) {
                output = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error));
              }
              final String result = output;
              activity.runOnUiThread(
                  () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    button.setEnabled(true);
                    new DshaDialogBuilder(activity)
                        .setTitle(UiText.text("设备通道验证"))
                        .setMessage(UiText.raw(result))
                        .setPositiveButton(UiText.text("知道了"), null)
                        .show();
                    if (after != null) after.run();
                  });
            },
            "onboarding-channel-probe")
        .start();
  }
}
