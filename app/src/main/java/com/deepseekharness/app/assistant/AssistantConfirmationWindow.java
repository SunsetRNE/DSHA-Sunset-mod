package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.control.OwnedScreenOverlay;
import com.deepseekharness.app.util.ScreenTarget;
import java.util.function.Consumer;

/** Separate permission prompt; closing it precedes the broker's exact target revalidation. */
final class AssistantConfirmationWindow implements AutoCloseable {
  private final Context context;
  private final WindowManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private LinearLayout view;
  private Consumer<Boolean> completion;
  private long sequence;
  private volatile boolean closed;

  AssistantConfirmationWindow(Context context) {
    this.context = context;
    manager = context.getSystemService(WindowManager.class);
  }

  void show(CapabilityRequest request, ScreenTarget target, Consumer<Boolean> answer) {
    main.post(
        () -> {
          finish(false);
          if (closed
              || !Settings.canDrawOverlays(context)
              || request.deadlineElapsedRealtimeMillis <= SystemClock.elapsedRealtime()) {
            answer.accept(false);
            return;
          }
          completion = answer;
          long stamp = ++sequence;
          view = new LinearLayout(context);
          view.setOrientation(LinearLayout.VERTICAL);
          view.setPadding(22, 18, 22, 18);
          view.setBackgroundColor(0xFF192435);
          TextView text = new TextView(context);
          text.setTextColor(Color.WHITE);
          text.setMaxLines(8);
          text.setText(
              String.format(
                  UiText.choose("允许本次手机操作？\n%s\n%s", "Allow this phone action?\n%s\n%s"),
                  target.packageName,
                  com.deepseekharness.app.util.SensitiveData.redact(request.action)));
          view.addView(text);
          Button allow = new Button(context);
          allow.setText(UiText.choose("允许本次", "Allow once"));
          view.addView(allow);
          allow.setOnClickListener(v -> finish(true));
          Button deny = new Button(context);
          deny.setText(UiText.choose("拒绝", "Deny"));
          view.addView(deny);
          deny.setOnClickListener(v -> finish(false));
          WindowManager.LayoutParams params =
              new WindowManager.LayoutParams(
                  -1,
                  -2,
                  Build.VERSION.SDK_INT >= 26
                      ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                      : WindowManager.LayoutParams.TYPE_PHONE,
                  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                      | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                  PixelFormat.TRANSLUCENT);
          params.gravity = Gravity.TOP;
          params.y = 90;
          view.addOnLayoutChangeListener(
              (v, l, t, r, b, ol, ot, or, ob) ->
                  AssistantWindow.publish(view, "assistant-confirm", false));
          try {
            manager.addView(view, params);
            view.post(() -> AssistantWindow.publish(view, "assistant-confirm", false));
          } catch (RuntimeException denied) {
            finish(false);
            return;
          }
          main.postDelayed(
              () -> {
                if (sequence == stamp) finish(false);
              },
              Math.min(
                  60000, request.deadlineElapsedRealtimeMillis - SystemClock.elapsedRealtime()));
        });
  }

  private void finish(boolean approved) {
    ++sequence;
    Consumer<Boolean> callback = completion;
    completion = null;
    OwnedScreenOverlay.clear("assistant-confirm");
    if (view != null)
      try {
        manager.removeViewImmediate(view);
      } catch (RuntimeException ignored) {
      }
    view = null;
    if (callback != null) callback.accept(approved);
  }

  @Override
  public void close() {
    closed = true;
    main.post(() -> finish(false));
  }
}
