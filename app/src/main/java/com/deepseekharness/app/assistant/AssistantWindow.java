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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.control.OwnedScreenOverlay;
import com.deepseekharness.app.util.ScreenWindowRegion;

/** Measured owned geometry is excluded only while collapsed. Expanding fences first. */
final class AssistantWindow implements AutoCloseable {
  private final Context context;
  private final AssistantSessionController controller;
  private final WindowManager manager;
  private LinearLayout root, panel;
  private TextView status, messages;
  private EditText input;
  private WindowManager.LayoutParams params;
  private volatile boolean added;
  private boolean renderedExpanded;
  private long draftRevision = -1;
  private final Runnable changed = this::render;
  boolean visible() { return added; }
  AssistantWindow(Context context, AssistantSessionController controller) {
    this.context = context; this.controller = controller; manager = context.getSystemService(WindowManager.class);
  }
  private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
  void show() {
    if (!Settings.canDrawOverlays(context)) return;
    root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(10), dp(6), dp(10), dp(6));
    GradientDrawable background = new GradientDrawable(); background.setColor(0xF51A2432); background.setCornerRadius(dp(16)); root.setBackground(background);
    status = new TextView(context); status.setTextColor(Color.WHITE); status.setTextSize(13); status.setMaxLines(2);
    status.setMinHeight(dp(44)); status.setGravity(Gravity.CENTER_VERTICAL); root.addView(status);
    status.setOnClickListener(v -> { if (controller.snapshot().expanded()) controller.collapse(); else controller.expand(); });
    panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL); root.addView(panel);
    ScrollView scroll = new ScrollView(context); messages = new TextView(context); messages.setTextColor(Color.WHITE);
    messages.setTextSize(14); messages.setTextIsSelectable(true); scroll.addView(messages);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(190)));
    input = new EditText(context); input.setTextColor(Color.WHITE); input.setHintTextColor(0xFFCBD5E1);
    input.setHint(UiText.choose("向绑定的会话发送消息", "Message the bound conversation")); input.setMinLines(1); input.setMaxLines(4); input.setTextSize(14); panel.addView(input);
    input.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
      public void onTextChanged(CharSequence s,int start,int before,int count) { controller.setDraft(s.toString()); }
      public void afterTextChanged(android.text.Editable value) {}
    });
    input.setOnFocusChangeListener((view, focused) -> { if (focused) controller.pause("ASSISTANT_IME"); });
    LinearLayout row = new LinearLayout(context); panel.addView(row);
    button(row, UiText.choose("发送", "Send"), () -> { controller.send(input.getText().toString()); input.clearFocus(); });
    button(row, UiText.choose("语音", "Voice"), () -> {
      controller.pause("ASSISTANT_VOICE"); controller.setDraft(input.getText().toString());
      context.startActivity(new Intent(context, AssistantVoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    });
    button(row, UiText.choose("收起", "Collapse"), controller::collapse);
    LinearLayout actions = new LinearLayout(context); root.addView(actions);
    button(actions, UiText.choose("暂停", "Pause"), () -> controller.pause("USER_PAUSED"));
    button(actions, UiText.choose("继续", "Continue"), controller::resume);
    button(actions, UiText.choose("停止", "Stop"), controller::stop);
    button(actions, UiText.choose("会话", "Chat"), controller::returnConversation);
    params = new WindowManager.LayoutParams();
    params.type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    params.format = PixelFormat.TRANSLUCENT; params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; params.y = dp(38);
    params.width = Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(24), dp(440)); params.height = -2;
    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> publish());
    try { manager.addView(root, params); added = true; controller.addListener(changed); render(); }
    catch (RuntimeException unavailable) { controller.pause("OVERLAY_UNAVAILABLE"); OwnedScreenOverlay.clear("assistant"); }
  }
  private void button(LinearLayout row, String text, Runnable action) {
    Button button = new Button(context); button.setText(text); button.setTextSize(12); button.setMinWidth(0);
    button.setPadding(dp(4), 0, dp(4), 0); row.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1));
    button.setOnClickListener(v -> action.run());
  }
  private void render() {
    if (!added) return;
    var state = controller.snapshot();
    String target = state.binding().target() == AssistantBinding.Target.LOCAL ? UiText.choose("本机 DSH", "Local DSH") : UiText.choose("电脑 DSH", "PC DSH");
    String progress = state.text().replaceAll("\\s+", " ");
    if (progress.length() > 90) progress = progress.substring(progress.length() - 90);
    status.setText(target + " · " + label(state.phase()) + "  ▾" + (progress.isEmpty() ? "" : "\n" + progress));
    status.setContentDescription(String.format(UiText.choose("%s，会话 %s，%s。点按展开聊天", "%s, conversation %s, %s. Tap to expand chat"), target, state.binding().sessionId(), label(state.phase())));
    messages.setText(state.text() + (state.detail().isEmpty() ? "" : "\n\n" + state.detail()));
    panel.setVisibility(state.expanded() ? View.VISIBLE : View.GONE);
    if (draftRevision != state.draftRevision()) {
      draftRevision = state.draftRevision();
      if (!state.draft().contentEquals(input.getText())) { input.setText(state.draft()); input.setSelection(input.length()); }
    }
    if (renderedExpanded != state.expanded()) {
      renderedExpanded = state.expanded();
      if (renderedExpanded) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      else { params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; input.clearFocus(); }
      try { manager.updateViewLayout(root, params); } catch (RuntimeException failure) { controller.pause("OVERLAY_REMOVED"); }
    }
    publish();
  }
  static String label(AssistantSessionState.Phase phase) {
    return switch (phase) {
      case CONNECTING -> UiText.choose("连接中", "Connecting"); case IDLE -> UiText.choose("就绪", "Ready"); case SENDING -> UiText.choose("发送中", "Sending"); case RUNNING -> UiText.choose("执行中", "Running");
      case PAUSING -> UiText.choose("正在暂停", "Pausing"); case PAUSED -> UiText.choose("已暂停", "Paused"); case STOPPING -> UiText.choose("正在停止", "Stopping");
      case STOPPED -> UiText.choose("已停止", "Stopped"); case UNKNOWN -> UiText.choose("状态待核对", "Status unknown"); case DISCONNECTED -> UiText.choose("连接已断开", "Disconnected");
    };
  }
  private void publish() { publish(root, "assistant", controller.snapshot().expanded()); }
  static void publish(View view, String owner, boolean expanded) {
    if (view == null || !view.isAttachedToWindow() || view.getVisibility() != View.VISIBLE) { OwnedScreenOverlay.clear(owner); return; }
    int[] position = new int[2]; view.getLocationOnScreen(position);
    var node = view.createAccessibilityNodeInfo();
    try { OwnedScreenOverlay.publish(owner, node.getWindowId(), new ScreenWindowRegion.Bounds(position[0], position[1],
        position[0] + view.getWidth(), position[1] + view.getHeight()), expanded); }
    finally { node.recycle(); }
  }
  @Override public void close() {
    controller.removeListener(changed); OwnedScreenOverlay.clear("assistant");
    if (added) try { manager.removeViewImmediate(root); } catch (RuntimeException ignored) { }
    added = false;
  }
}
