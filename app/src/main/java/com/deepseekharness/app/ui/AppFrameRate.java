package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** 仅在页面可交互期间订阅显示与省电变化；不持有已暂停的 Activity。 */
final class AppFrameRate {
  private final Set<Activity> resumed = Collections.newSetFromMap(new WeakHashMap<>());
  private final Handler main = new Handler(Looper.getMainLooper());
  private final java.util.Map<Activity, ViewTreeObserver.OnWindowFocusChangeListener> focus =
      new WeakHashMap<>();
  private Context application;
  private DisplayManager displays;
  private boolean registered;
  private final ContentObserver settings =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
          refreshAll();
        }
      };
  private final BroadcastReceiver power =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refreshAll();
        }
      };
  private final DisplayManager.DisplayListener display =
      new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
          refreshAll();
        }

        @Override
        public void onDisplayChanged(int id) {
          refreshAll();
        }

        @Override
        public void onDisplayRemoved(int id) {
          refreshAll();
        }
      };

  void resume(Activity activity) {
    if (!resumed.add(activity)) {
      apply(activity);
      return;
    }
    if (application == null) application = activity.getApplicationContext();
    View decor = activity.getWindow().getDecorView();
    ViewTreeObserver.OnWindowFocusChangeListener listener =
        focused -> {
          if (focused && resumed.contains(activity)) apply(activity);
        };
    focus.put(activity, listener);
    decor.getViewTreeObserver().addOnWindowFocusChangeListener(listener);
    register();
    apply(activity);
  }

  void pause(Activity activity) {
    resumed.remove(activity);
    var listener = focus.remove(activity);
    var tree = activity.getWindow().getDecorView().getViewTreeObserver();
    if (listener != null && tree.isAlive()) tree.removeOnWindowFocusChangeListener(listener);
    // Web 的暂停/画中画换代由其现有宿主处理，不能在全局回调中覆盖。
    if (!(activity instanceof WebPreviewActivity))
      WebFrameRate.clear(activity, activity.findViewById(android.R.id.content));
    if (resumed.isEmpty()) unregister();
  }

  private void apply(Activity activity) {
    if (activity.isFinishing() || activity.isDestroyed() || activity.isInPictureInPictureMode())
      return;
    View content = activity.findViewById(android.R.id.content);
    if (content != null) WebFrameRate.apply(activity, content);
  }

  private void refreshAll() {
    for (Activity activity : resumed.toArray(new Activity[0])) apply(activity);
  }

  private void register() {
    if (registered || application == null) return;
    registered = true;
    try {
      displays = application.getSystemService(DisplayManager.class);
      if (displays != null) displays.registerDisplayListener(display, main);
      application
          .getContentResolver()
          .registerContentObserver(Settings.System.getUriFor("peak_refresh_rate"), false, settings);
      application
          .getContentResolver()
          .registerContentObserver(Settings.System.getUriFor("min_refresh_rate"), false, settings);
      androidx.core.content.ContextCompat.registerReceiver(
          application,
          power,
          new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
          androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    } catch (RuntimeException unavailable) {
      // 订阅失败不影响页面创建；下次 resume/focus 仍重新读取系统策略。
      unregister();
    }
  }

  private void unregister() {
    if (!registered) return;
    registered = false;
    if (displays != null) {
      try {
        displays.unregisterDisplayListener(display);
      } catch (RuntimeException ignored) {
      }
      displays = null;
    }
    try {
      application.getContentResolver().unregisterContentObserver(settings);
    } catch (RuntimeException ignored) {
    }
    try {
      application.unregisterReceiver(power);
    } catch (RuntimeException ignored) {
    }
  }
}
