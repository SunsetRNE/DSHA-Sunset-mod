package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.control.OwnedScreenOverlay;
import com.deepseekharness.app.util.OverlayPlacement;
import com.deepseekharness.app.util.OverlayTouchGuard;
import com.deepseekharness.app.util.ScreenWindowRegion;

/** Measured owned geometry is excluded only while collapsed. Expanding fences first. */
final class AssistantWindow implements AutoCloseable {
  private final Context context;
  private final AssistantSessionController controller;
  private final WindowManager manager;
  private LinearLayout root, panel, actions;
  private TextView status, messages;
  private Button input;
  private Button sendButton, resumeButton, stopButton, closeButton;
  private Button notificationSettingsButton;
  private WindowManager.LayoutParams params;
  private volatile boolean added;
  private boolean renderedExpanded;
  private long draftRevision = -1;
  private boolean editorVisible;
  private boolean hiddenForScreen;
  private boolean stopControlsRequired;
  private final Runnable changed = this::render;
  private final OverlayTouchGuard guard =
      new OverlayTouchGuard(android.os.SystemClock::elapsedRealtime);
  private final Runnable stopExpired = this::renderStopButton;
  private int renderedWidth = -1;
  // 用户拖到的位置在进程内保留，下次出现时沿用；-1 表示默认右下角。
  private static int savedX = -1, savedY = -1;
  private float downRawX, downRawY;
  private int downX, downY;
  private boolean dragging;

  /** 展开的面板正被用户查看：读屏前不收走它（它不可聚焦，按自有窗口整窗排除）。 */
  boolean keptVisibleForUser() {
    return added && !hiddenForScreen && !editorVisible && controller.snapshot().expanded();
  }

  boolean visible() {
    return added;
  }

  boolean taskUiAvailable() {
    return added || hiddenForScreen;
  }

  /** Remove the actual input window before observing pixels; polling never recreates it. */
  boolean hideForScreen() {
    if (controller.snapshot().expanded() || editorVisible)
      throw new IllegalStateException("ASSISTANT_LOCAL_RESUME_REQUIRED");
    if (hiddenForScreen) return false;
    if (!added) throw new IllegalStateException("ASSISTANT_UI_UNAVAILABLE");
    manager.removeViewImmediate(root);
    added = false;
    hiddenForScreen = true;
    stopControlsRequired = false;
    OwnedScreenOverlay.clear("assistant");
    return true;
  }

  /** Losing the stop notification restores explicit native controls while all input stays paused. */
  void restoreStopControls() {
    stopControlsRequired = true;
    if (!added && hiddenForScreen && !editorVisible && Settings.canDrawOverlays(context)) {
      try {
        manager.addView(root, params);
        added = true;
        hiddenForScreen = false;
        guard.layoutChanged();
      } catch (RuntimeException unavailable) {
        return;
      }
    }
    render();
  }

  AssistantWindow(Context context, AssistantSessionController controller) {
    this.context = context;
    this.controller = controller;
    manager = context.getSystemService(WindowManager.class);
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  void show() {
    if (!Settings.canDrawOverlays(context)) return;
    hiddenForScreen = false;
    root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(10), dp(6), dp(10), dp(6));
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xF51A2432);
    background.setCornerRadius(dp(16));
    root.setBackground(background);
    status = new TextView(context);
    status.setTextColor(Color.WHITE);
    status.setTextSize(13);
    status.setMaxLines(2);
    status.setEllipsize(TextUtils.TruncateAt.END);
    status.setGravity(Gravity.CENTER_VERTICAL);
    // 状态行只负责展开，不再来回切换；收起统一用右上角 ✕，避免同一位置连点误收、误触。
    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    root.addView(header, new LinearLayout.LayoutParams(-1, dp(50)));
    header.addView(status, new LinearLayout.LayoutParams(0, -1, 1));
    status.setOnClickListener(
        v -> {
          if (!controller.snapshot().expanded() && guard.accept()) controller.expand();
        });
    // 状态行同时是拖动把手：移动不超过触摸阈值仍算点按（展开），超过则移动整个悬浮窗。
    status.setOnTouchListener(this::dragTouch);
    closeButton = new Button(context);
    closeButton.setText("✕");
    closeButton.setTextSize(16);
    closeButton.setMinWidth(0);
    closeButton.setMinimumWidth(0);
    closeButton.setPadding(0, 0, 0, 0);
    closeButton.setContentDescription(UiText.choose("收起面板", "Collapse panel"));
    LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
    closeParams.setMarginStart(dp(8));
    header.addView(closeButton, closeParams);
    closeButton.setOnClickListener(
        v -> {
          if (guard.accept()) controller.collapse();
        });
    panel = new LinearLayout(context);
    panel.setOrientation(LinearLayout.VERTICAL);
    root.addView(panel);
    ScrollView scroll = new ScrollView(context);
    messages = new TextView(context);
    messages.setTextColor(Color.WHITE);
    messages.setTextSize(14);
    scroll.addView(messages);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(190)));
    // A service overlay is not a reliable IME editor on every ROM. Use a normal
    // Activity for explicit text editing instead of claiming focus from flags alone.
    input = new Button(context);
    input.setTextColor(Color.WHITE);
    input.setMaxLines(2);
    input.setEllipsize(TextUtils.TruncateAt.END);
    input.setTextSize(14);
    panel.addView(input);
    input.setOnClickListener(
        v -> {
          if (guard.accept()) openEditor();
        });
    LinearLayout row = new LinearLayout(context);
    panel.addView(row);
    sendButton =
        button(
            row,
            UiText.choose("发送", "Send"),
            () -> {
              controller.send(controller.snapshot().draft());
            });
    button(
        row,
        UiText.choose("语音", "Voice"),
        () -> {
          controller.beginComposing("voice");
          context.startActivity(
              new Intent(context, AssistantVoiceActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    actions = new LinearLayout(context);
    root.addView(actions);
    // 「停止」放在最左、离右上角 ✕ 最远，并与其它按钮隔开；需要二次确认。
    stopButton = button(actions, "", null);
    stopButton.setOnClickListener(v -> stopTapped());
    ((LinearLayout.LayoutParams) stopButton.getLayoutParams()).setMarginEnd(dp(16));
    renderStopButton();
    button(actions, UiText.choose("暂停", "Pause"), () -> controller.pause("USER_PAUSED"));
    resumeButton = button(actions, UiText.choose("继续", "Continue"), controller::resume);
    button(actions, UiText.choose("会话", "Chat"), controller::returnConversation);
    notificationSettingsButton = new Button(context);
    notificationSettingsButton.setText(
        UiText.choose("打开系统通知设置", "Open Android notification settings"));
    notificationSettingsButton.setOnClickListener(
        v -> context.startActivity(AssistantService.notificationSettingsIntent(context)));
    root.addView(notificationSettingsButton);
    params = new WindowManager.LayoutParams();
    params.type =
        Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    params.format = PixelFormat.TRANSLUCENT;
    params.flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    params.gravity = Gravity.BOTTOM | Gravity.END;
    params.y = savedY >= 0 ? savedY : dp(24);
    params.x = savedX >= 0 ? savedX : dp(12);
    params.width =
        Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(24), dp(210));
    params.height = -2;
    // 悬浮助手从不是输入法目标（编辑走 AssistantActivity）。ADJUST_RESIZE 会被
    // ViewRootImpl 兼容换算为 fitInsetsTypes|=ime()，使底部窗口随其他应用的输入法上弹；
    // 显式声明只避让系统栏，与他人输入法隔离。
    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
    params.setFitInsetsTypes(android.view.WindowInsets.Type.systemBars());
    root.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          // 展开变宽变高、转屏后仍要整窗留在屏幕内。
          keepOnScreen();
          publish();
        });
    try {
      manager.addView(root, params);
      added = true;
      guard.layoutChanged();
      controller.addListener(changed);
      render();
    } catch (RuntimeException unavailable) {
      controller.pause("OVERLAY_UNAVAILABLE");
      OwnedScreenOverlay.clear("assistant");
    }
  }

  private boolean dragTouch(View view, android.view.MotionEvent event) {
    switch (event.getActionMasked()) {
      case android.view.MotionEvent.ACTION_DOWN -> {
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        downX = params.x;
        downY = params.y;
        dragging = false;
        return false;
      }
      case android.view.MotionEvent.ACTION_MOVE -> {
        float dx = event.getRawX() - downRawX, dy = event.getRawY() - downRawY;
        if (!dragging
            && OverlayPlacement.isDrag(
                dx, dy, android.view.ViewConfiguration.get(context).getScaledTouchSlop())) {
          dragging = true;
          // 交给状态行一次 CANCEL，清掉按下/长按/点按回调，这次手势不会再触发展开。
          android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
          cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
          view.onTouchEvent(cancel);
          cancel.recycle();
        }
        if (!dragging) return false;
        moveTo(
            OverlayPlacement.offset(downX, downRawX, event.getRawX()),
            OverlayPlacement.offset(downY, downRawY, event.getRawY()));
        return true;
      }
      case android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
        if (!dragging) return false;
        dragging = false;
        savedX = params.x;
        savedY = params.y;
        // 松手后新位置附近的按钮短暂不响应；旧观察作废，下一次手机操作先重新观察。
        guard.layoutChanged();
        controller.panelMoved();
        publish();
        return true;
      }
      default -> {
        return dragging;
      }
    }
  }

  private void moveTo(int x, int y) {
    var metrics = context.getResources().getDisplayMetrics();
    int width = root.getWidth() > 0 ? root.getWidth() : params.width;
    int height = root.getHeight() > 0 ? root.getHeight() : dp(62);
    int nextX = OverlayPlacement.clamp(x, width, metrics.widthPixels, dp(8));
    int nextY = OverlayPlacement.clamp(y, height, metrics.heightPixels, dp(8));
    if (nextX == params.x && nextY == params.y) return;
    params.x = nextX;
    params.y = nextY;
    try {
      manager.updateViewLayout(root, params);
    } catch (RuntimeException removed) {
      // 窗口已被移除：下一次 show 重新建立。
    }
  }

  private void keepOnScreen() {
    if (!added || dragging) return;
    int beforeX = params.x, beforeY = params.y;
    moveTo(params.x, params.y);
    if ((params.x != beforeX || params.y != beforeY) && savedX >= 0) {
      savedX = params.x;
      savedY = params.y;
    }
  }

  private Button button(LinearLayout row, String text, Runnable action) {
    Button button = new Button(context);
    button.setText(text);
    button.setTextSize(12);
    button.setMinWidth(0);
    button.setPadding(dp(4), 0, dp(4), 0);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
    params.setMargins(dp(4), dp(4), dp(4), dp(4));
    row.addView(button, params);
    if (action != null)
      button.setOnClickListener(
          v -> {
            if (guard.accept()) action.run();
          });
    return button;
  }

  private void stopTapped() {
    switch (guard.stop()) {
      case IGNORED -> {}
      case ARMED -> {
        renderStopButton();
        stopButton.removeCallbacks(stopExpired);
        stopButton.postDelayed(stopExpired, OverlayTouchGuard.CONFIRM_MILLIS + 50);
      }
      case CONFIRMED -> {
        stopButton.removeCallbacks(stopExpired);
        renderStopButton();
        controller.stop();
      }
    }
  }

  private void renderStopButton() {
    if (stopButton == null) return;
    boolean armed = guard.stopArmed();
    stopButton.setText(armed ? UiText.choose("再点停止", "Tap again") : UiText.choose("停止", "Stop"));
    stopButton.setContentDescription(
        armed
            ? UiText.choose("再点一次停止当前任务", "Tap again to stop the current task")
            : UiText.choose("停止当前任务，需要点两次", "Stop the current task; requires two taps"));
  }

  private void render() {
    if (!added) return;
    var state = controller.snapshot();
    String target = UiText.choose("本机 DSH", "Local DSH");
    String progress = state.text().replaceAll("\\s+", " ").strip();
    if (progress.length() > 90) progress = progress.substring(progress.length() - 90);
    status.setText(
        target
            + " · "
            + label(state.phase())
            + "  ▾"
            + (state.expanded() || progress.isEmpty() ? "" : "\n" + progress));
    status.setContentDescription(
        String.format(
            UiText.choose(
                "%s，会话 %s，%s。点按展开聊天，按住拖动可移动",
                "%s, conversation %s, %s. Tap to expand chat; drag to move"),
            target,
            state.binding().sessionId(),
            label(state.phase())));
    messages.setText(state.text() + "\n\n" + detail(state));
    sendButton.setEnabled(state.terminalKnown() && !state.draft().isBlank());
    resumeButton.setEnabled(
        state.terminalKnown()
            && state.paused()
            && state.phase() != AssistantSessionState.Phase.STOPPED);
    panel.setVisibility(state.expanded() ? View.VISIBLE : View.GONE);
    closeButton.setVisibility(state.expanded() ? View.VISIBLE : View.GONE);
    actions.setVisibility(state.expanded() || stopControlsRequired ? View.VISIBLE : View.GONE);
    renderStopButton();
    notificationSettingsButton.setVisibility(stopControlsRequired ? View.VISIBLE : View.GONE);
    if (draftRevision != state.draftRevision()) {
      draftRevision = state.draftRevision();
      input.setText(
          state.draft().isBlank()
              ? UiText.choose("点此输入文字", "Tap to type a message")
              : String.format(UiText.choose("编辑草稿：%s", "Edit draft: %s"), state.draft()));
    }
    if (renderedExpanded != state.expanded() || stopControlsRequired) {
      boolean expansionChanged = renderedExpanded != state.expanded();
      renderedExpanded = state.expanded();
      params.width =
          state.expanded() || stopControlsRequired
              ? Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(24), dp(440))
              : Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(24), dp(210));
      // 只有真实的展开/收起或改宽才进入静置期；轮询重绘不会吞掉点按。
      if (expansionChanged || params.width != renderedWidth) guard.layoutChanged();
      renderedWidth = params.width;
      // This window has buttons and a transcript, never an IME target.
      params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      try {
        manager.updateViewLayout(root, params);
      } catch (RuntimeException failure) {
        controller.pause("OVERLAY_REMOVED");
      }
    }
    publish();
  }

  static Intent editorIntent(Context context) {
    return new Intent(context, AssistantActivity.class)
        .putExtra("bound", true)
        .putExtra("focus_input", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
  }

  private void openEditor() {
    controller.beginComposing("editor");
    controller.collapse();
    setEditorVisible(true);
    context.startActivity(editorIntent(context));
  }

  void setEditorVisible(boolean visible) {
    editorVisible = visible;
    if (!added) return;
    root.clearFocus();
    params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    manager.updateViewLayout(root, params);
    root.setVisibility(visible ? View.GONE : View.VISIBLE);
    if (!visible) guard.layoutChanged();
    publish();
  }

  static String label(AssistantSessionState.Phase phase) {
    return switch (phase) {
      case CONNECTING -> UiText.choose("连接中", "Connecting");
      case IDLE -> UiText.choose("就绪", "Ready");
      case SENDING -> UiText.choose("发送中", "Sending");
      case RUNNING -> UiText.choose("执行中", "Running");
      case PAUSING -> UiText.choose("正在暂停", "Pausing");
      case PAUSED -> UiText.choose("已暂停", "Paused");
      case STOPPING -> UiText.choose("正在停止", "Stopping");
      case STOPPED -> UiText.choose("已停止", "Stopped");
      case UNKNOWN -> UiText.choose("状态待核对", "Status unknown");
      case DISCONNECTED -> UiText.choose("连接已断开", "Disconnected");
    };
  }

  static String detail(AssistantSessionController.Snapshot state) {
    return switch (state.detail()) {
      case "ASSISTANT_IME", "ASSISTANT_VOICE" ->
          UiText.choose(
              "正在输入，手机操作暂时拦住，对话不会被取消。发送或返回后自动放开。",
              "Phone actions are held while you type; the conversation is not cancelled. They resume when you send or go back.");
      case "ASSISTANT_STOP_NOTIFICATION_REQUIRED" ->
          AssistantService.taskGuidance("ASSISTANT_STOP_NOTIFICATION_REQUIRED");
      case "USER_PAUSED" ->
          UiText.choose(
              "任务已暂停。确认停止后，点继续会先重新观察屏幕。",
              "Task paused. Continue after cancellation is confirmed to observe the screen again.");
      case "OBSERVE_REQUIRED" ->
          UiText.choose(
              "下一次手机操作前会重新观察屏幕。",
              "The screen must be observed again before the next phone action.");
      case "CONNECTION_CHANGED" ->
          UiText.choose(
              "连接已改变。返回悬浮助手重新选择会话。",
              "The connection changed. Choose a conversation again in the assistant.");
      case "" ->
          state.phase() == AssistantSessionState.Phase.STOPPED
              ? UiText.choose(
                  "当前任务已停止。输入新消息可在此会话开始新任务。",
                  "Task stopped. Send a new message to begin another task in this conversation.")
              : "";
      default ->
          state.detail().matches("[A-Z][A-Z0-9_]+")
              ? UiText.choose(
                  "手机操作已暂停；确认会话状态后继续。",
                  "Phone actions paused; check the conversation before continuing.")
              : state.detail();
    };
  }

  private void publish() {
    if (editorVisible) {
      OwnedScreenOverlay.clear("assistant");
      return;
    }
    // 助手面板从不可聚焦、不是输入法目标；展开时也按自有窗口整窗排除，
    // 不作为“展开即暂停”的信号（那会让查看面板就停掉手机操作和对话）。
    publish(root, "assistant", false);
  }

  static void publish(View view, String owner, boolean expanded) {
    if (view == null || !view.isAttachedToWindow() || view.getVisibility() != View.VISIBLE) {
      OwnedScreenOverlay.clear(owner);
      return;
    }
    int[] position = new int[2];
    view.getLocationOnScreen(position);
    var node = view.createAccessibilityNodeInfo();
    try {
      OwnedScreenOverlay.publish(
          owner,
          node.getWindowId(),
          new ScreenWindowRegion.Bounds(
              position[0],
              position[1],
              position[0] + view.getWidth(),
              position[1] + view.getHeight()),
          expanded);
    } finally {
      node.recycle();
    }
  }

  @Override
  public void close() {
    controller.removeListener(changed);
    OwnedScreenOverlay.clear("assistant");
    if (added)
      try {
        manager.removeViewImmediate(root);
      } catch (RuntimeException ignored) {
      }
    added = false;
    hiddenForScreen = false;
  }
}
