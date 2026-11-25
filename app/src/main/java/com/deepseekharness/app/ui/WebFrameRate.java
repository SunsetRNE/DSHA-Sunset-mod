package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import com.deepseekharness.app.util.FrameRatePolicy;

import java.util.ArrayList;

/** 窗口与已知内容 View 的刷新率偏好；生命周期由 UiFrameRate 唯一管理。 */
final class WebFrameRate {
  private WebFrameRate() {}

  static float select(Activity activity, View decor) {
    // 只使用已关联窗口的显示器。未 attach 时 View.getDisplay() 为 null，
    // Activity 的 Context 此时也可能不关联任何显示器并抛 UnsupportedOperationException。
    // 等待 attach 回调再请求，不能把外屏/移动中窗口退回默认手机屏幕。
    Display display = decor.getDisplay();
    if (display == null || !display.isValid()) return 0f;
    Display.Mode current = display.getMode();
    if (current == null) return 0f;
    ArrayList<Float> matching = new ArrayList<>();
    for (Display.Mode mode : display.getSupportedModes()) {
      // 刷新率请求不能顺带改变面板分辨率。
      if (mode.getPhysicalWidth() == current.getPhysicalWidth()
          && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
        matching.add(mode.getRefreshRate());
      }
    }
    float[] rates = new float[matching.size()];
    for (int i = 0; i < rates.length; i++) rates[i] = matching.get(i);
    float peak = 0f;
    try {
      peak = Settings.System.getFloat(activity.getContentResolver(), "peak_refresh_rate");
    } catch (Settings.SettingNotFoundException | SecurityException ignored) {
    }
    PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
    return FrameRatePolicy.select(power != null && power.isPowerSaveMode(), peak, rates);
  }

  static void apply(Activity activity, View decor, View content, float rate) {
    setWindowRate(activity, rate);
    setViewRate(decor, rate);
    if (content != decor) setViewRate(content, rate);
  }

  static void setViewRate(View view, float rate) {
    if (view != null && Build.VERSION.SDK_INT >= 35) Api35.setFrameRate(view, rate);
  }

  private static void setWindowRate(Activity activity, float rate) {
    WindowManager.LayoutParams params = activity.getWindow().getAttributes();
    if (Math.abs(params.preferredRefreshRate - rate) < 0.01f) return;
    params.preferredRefreshRate = rate;
    activity.getWindow().setAttributes(params);
  }

  @RequiresApi(35)
  private static final class Api35 {
    static void setFrameRate(View view, float rate) {
      // HIGH 是类别，不代表 90/120Hz；显式数值与窗口偏好使用同一个上限。
      // ViewGroup 不向子 View 传播，因此 WebView 作为已知目标单独设置。
      float requested = rate > 0f ? rate : View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE;
      if (Float.compare(view.getRequestedFrameRate(), requested) != 0)
        view.setRequestedFrameRate(requested);
    }
  }
}
