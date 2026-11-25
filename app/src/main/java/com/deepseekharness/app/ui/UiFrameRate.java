package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.core.content.ContextCompat;

import com.deepseekharness.app.DshaApp;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/** 所有原生/网页 Activity 共用一个高刷请求所有者；只响应事件，不制造绘制或轮询。 */
public final class UiFrameRate implements Application.ActivityLifecycleCallbacks {
  private final Application application;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final IdentityHashMap<Activity, State> states = new IdentityHashMap<>();
  private DisplayManager displays;
  private boolean installed;
  private boolean displayRegistered;
  private boolean peakRegistered;
  private boolean powerRegistered;

  public UiFrameRate(Application application) {
    this.application = application;
  }

  public void install() {
    if (installed) return;
    installed = true;
    application.registerActivityLifecycleCallbacks(this);
  }

  /** 保留 WebView 只迁移高刷请求归属，不重建网页或更改网页生命周期。 */
  static void bind(Activity activity, View content) {
    if (activity.getApplication() instanceof DshaApp)
      ((DshaApp) activity.getApplication()).uiFrameRate().bindContent(activity, content);
  }

  static void unbind(Activity activity, View content) {
    if (activity.getApplication() instanceof DshaApp)
      ((DshaApp) activity.getApplication()).uiFrameRate().unbindContent(activity, content);
  }

  static void refresh(Activity activity) {
    if (activity.getApplication() instanceof DshaApp) {
      UiFrameRate owner = ((DshaApp) activity.getApplication()).uiFrameRate();
      State state = owner.states.get(activity);
      if (state != null) owner.apply(state);
    }
  }

  void bindContent(Activity activity, View content) {
    State state = state(activity);
    if (state.content != content) WebFrameRate.setViewRate(state.content, 0f);
    // 新宿主先绑定、旧宿主随后销毁时，旧宿主不能清掉同一个保留 View 的新请求。
    for (State other : states.values())
      if (other != state && other.content == content) other.content = null;
    state.content = content;
    apply(state);
  }

  void unbindContent(Activity activity, View content) {
    State state = states.get(activity);
    if (state == null || state.content != content) return;
    WebFrameRate.setViewRate(content, 0f);
    state.content = null;
  }

  private final class State
      implements View.OnAttachStateChangeListener,
          ViewTreeObserver.OnWindowFocusChangeListener,
          View.OnLayoutChangeListener {
    final Activity activity;
    final View decor;
    View content;
    ViewTreeObserver tree;
    boolean resumed;
    boolean detached;
    Display selectedDisplay;
    float selectedRate;
    boolean selectionValid;

    State(Activity activity) {
      this.activity = activity;
      decor = activity.getWindow().getDecorView();
      decor.addOnAttachStateChangeListener(this);
      decor.addOnLayoutChangeListener(this);
      listenForFocus();
    }

    void listenForFocus() {
      ViewTreeObserver current = decor.getViewTreeObserver();
      if (tree == current) return;
      if (tree != null && tree.isAlive()) tree.removeOnWindowFocusChangeListener(this);
      tree = current;
      tree.addOnWindowFocusChangeListener(this);
    }

    void dispose() {
      decor.removeOnAttachStateChangeListener(this);
      decor.removeOnLayoutChangeListener(this);
      if (tree != null && tree.isAlive()) tree.removeOnWindowFocusChangeListener(this);
      tree = null;
    }

    @Override
    public void onViewAttachedToWindow(View view) {
      detached = false;
      listenForFocus();
      apply(this);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
      detached = true;
      selectionValid = false;
      selectedDisplay = null;
      WebFrameRate.apply(activity, decor, content, 0f);
    }

    @Override
    public void onLayoutChange(
        View view,
        int left,
        int top,
        int right,
        int bottom,
        int oldLeft,
        int oldTop,
        int oldRight,
        int oldBottom) {
      // IME/分屏动画可连续改变窗口尺寸，但不会改变刷新率策略。相同显示器沿用事件
      // 已核验的偏好，避免每帧重新读取系统设置、功耗及支持模式；窗口迁移仍立即重算。
      if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)
        apply(this, false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
      // 多窗口允许多个 resumed；系统自行仲裁。失焦不覆盖其他窗口，也不抢焦点。
      if (hasFocus) apply(this);
    }
  }

  private State state(Activity activity) {
    State state = states.get(activity);
    if (state == null) {
      state = new State(activity);
      states.put(activity, state);
    }
    return state;
  }

  private void apply(State state) {
    apply(state, true);
  }

  private void apply(State state, boolean refreshSelection) {
    float rate = 0f;
    if (state.resumed
        && !state.detached
        && !state.activity.isFinishing()
        && !state.activity.isDestroyed()
        && !state.activity.isInPictureInPictureMode()) {
      try {
        Display display = state.decor.getDisplay();
        if (display != null && display.isValid()) {
          if (refreshSelection || !state.selectionValid || state.selectedDisplay != display) {
            state.selectedRate = WebFrameRate.select(state.activity, state.decor);
            state.selectedDisplay = display;
            state.selectionValid = true;
          }
          rate = state.selectedRate;
        } else {
          state.selectionValid = false;
          state.selectedDisplay = null;
        }
      } catch (IllegalStateException
          | UnsupportedOperationException
          | SecurityException unavailable) {
        // 显示器退出/迁移或 OEM 不提供读取能力时，撤销旧请求交还系统。
        state.selectionValid = false;
        state.selectedDisplay = null;
      }
    } else {
      state.selectionValid = false;
      state.selectedDisplay = null;
    }
    WebFrameRate.apply(state.activity, state.decor, state.content, rate);
  }

  private void refreshAll() {
    for (State state : new ArrayList<>(states.values())) if (state.resumed) apply(state);
  }

  private final ContentObserver peakObserver =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
          refreshAll();
        }
      };

  private final BroadcastReceiver powerReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) refreshAll();
        }
      };

  private final DisplayManager.DisplayListener displayListener =
      new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
          refreshAll();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
          refreshAll();
        }

        @Override
        public void onDisplayChanged(int displayId) {
          refreshAll();
        }
      };

  private void updateObservers() {
    boolean active = false;
    for (State state : states.values()) active |= state.resumed;
    if (active) {
      if (!displayRegistered) {
        displays = application.getSystemService(DisplayManager.class);
        if (displays != null) {
          displays.registerDisplayListener(displayListener, main);
          displayRegistered = true;
        }
      }
      if (!peakRegistered) {
        try {
          application
              .getContentResolver()
              .registerContentObserver(
                  Settings.System.getUriFor("peak_refresh_rate"), false, peakObserver);
          peakRegistered = true;
        } catch (SecurityException unavailable) {
          // 不申请 WRITE_SETTINGS；恢复前台和窗口焦点时仍会重新读取。
        }
      }
      if (!powerRegistered) {
        ContextCompat.registerReceiver(
            application,
            powerReceiver,
            new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED);
        powerRegistered = true;
      }
    } else {
      if (displayRegistered) displays.unregisterDisplayListener(displayListener);
      if (peakRegistered) application.getContentResolver().unregisterContentObserver(peakObserver);
      if (powerRegistered) application.unregisterReceiver(powerReceiver);
      displayRegistered = peakRegistered = powerRegistered = false;
    }
  }

  @Override
  public void onActivityPostCreated(Activity activity, Bundle saved) {
    state(activity);
  }

  @Override
  public void onActivityPostResumed(Activity activity) {
    State state = state(activity);
    state.resumed = true;
    updateObservers();
    apply(state);
  }

  @Override
  public void onActivityPaused(Activity activity) {
    suspend(activity);
  }

  @Override
  public void onActivityStopped(Activity activity) {
    suspend(activity);
  }

  private void suspend(Activity activity) {
    State state = states.get(activity);
    if (state == null) return;
    state.resumed = false;
    apply(state);
    updateObservers();
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    State state = states.remove(activity);
    if (state == null) return;
    state.resumed = false;
    apply(state);
    state.dispose();
    updateObservers();
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle saved) {}

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityResumed(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
}
