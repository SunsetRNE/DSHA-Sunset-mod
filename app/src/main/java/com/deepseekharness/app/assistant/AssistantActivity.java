package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.MainActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native view of an existing local DSH conversation, without hidden runtime bootstrap. */
public final class AssistantActivity extends AppCompatActivity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout content;
  private LinearLayout buttonContainer;
  private ScrollView pageScroll;
  private TextView status;
  private AssistantBackend backend;
  private String hostInstanceId;
  private long loadRevision;
  private AssistantSessionController bound;
  private TextView messages;
  private EditText input;
  private Button sendButton, resumeButton;
  private boolean resumed, loading, creating;
  private boolean pendingInput;
  private boolean controlPermissionMode;
  private boolean notificationPermissionMode;
  private boolean readyListenerAdded;
  private long editorRevision;
  private long boundViewRevision;
  private final Object editorToken = new Object();
  private final String editorSurface = "editor-" + java.util.UUID.randomUUID();
  private final Runnable localReady =
      () -> {
        if (resumed && bound == null && !controlPermissionMode && !loading) selectLocal();
      };
  private long draftRevision = -1;
  private final Runnable changed = this::renderBound;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    ScrollView scroll = new ScrollView(this);
    pageScroll = scroll;
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setFocusableInTouchMode(true);
    content.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
    int padding = Math.round(16 * getResources().getDisplayMetrics().density);
    content.setPadding(padding, padding, padding, padding);
    scroll.addView(content);
    setContentView(scroll);
    status = new TextView(this);
    status.setTextSize(20);
    status.setText(UiText.choose("DSHA 悬浮助手", "DSHA floating assistant"));
    content.addView(status);
    if (AssistantNotificationNavigation.requested(getIntent())) {
      showNotification(getIntent());
      return;
    }
    if (AssistantService.CONTROL_PERMISSION.equals(getIntent().getAction())) {
      showControlPermission();
      return;
    }
    if (getIntent().getBooleanExtra("bound", false)
        && AssistantSessionController.active() != null) {
      showBound();
      return;
    }
    selectLocal();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (AssistantNotificationNavigation.requested(intent)) {
      showNotification(intent);
      return;
    }
    if (AssistantService.CONTROL_PERMISSION.equals(intent.getAction())) {
      showControlPermission();
      return;
    }
    controlPermissionMode = false;
    if (intent.getBooleanExtra("bound", false)) showBound();
    else selectLocal();
  }

  private void showControlPermission() {
    controlPermissionMode = true;
    notificationPermissionMode = getIntent().getBooleanExtra("notification_permission", false);
    loadRevision++;
    loading = false;
    if (bound != null) {
      bound.removeListener(changed);
      AssistantService.nativeEditorVisible(bound, editorToken, false);
      bound.endComposing(editorSurface);
    }
    bound = null;
    input = null;
    pendingInput = false;
    editorRevision++;
    content.removeAllViews();
    content.addView(status);
    status.setText(
        notificationPermissionMode
            ? UiText.choose("手机任务状态", "Phone task status")
            : UiText.choose("手机任务状态与悬浮窗权限", "Phone task status and overlay permission"));
    TextView detail = new TextView(this);
    detail.setText(
        AssistantService.taskGuidance(
            notificationPermissionMode
                ? "ASSISTANT_STOP_NOTIFICATION_REQUIRED"
                : "ASSISTANT_OVERLAY_PERMISSION_REQUIRED"));
    content.addView(detail);
    buttonContainer = null;
    if (notificationPermissionMode)
      button(
          UiText.choose("打开系统通知设置", "Open Android notification settings"),
          () -> startActivity(AssistantService.notificationSettingsIntent(this)));
    else
      button(
          UiText.choose("打开系统悬浮窗权限", "Open Android overlay permission"),
          () ->
              startActivity(
                  new Intent(
                      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                      Uri.parse("package:" + getPackageName()))));
    button(UiText.choose("返回原对话继续", "Return to the original conversation"), this::finish);
  }

  private void selectLocal() {
    // 只有用户明确选择其它会话才离开通知地址；旧通知拒绝路径不会调用这里。
    getIntent().setData(null);
    getIntent().removeExtra("bound");
    getIntent().removeExtra("focus_input");
    boundViewRevision++;
    if (bound != null) {
      bound.removeListener(changed);
      AssistantService.nativeEditorVisible(bound, editorToken, false);
      bound.endComposing(editorSurface);
      bound = null;
    }
    input = null;
    pendingInput = false;
    editorRevision++;
    if (buttonContainer != null) {
      ((android.view.ViewGroup) status.getParent()).removeView(status);
      content = new LinearLayout(this);
      content.setOrientation(LinearLayout.VERTICAL);
      content.setFocusableInTouchMode(true);
      int padding = Math.round(16 * getResources().getDisplayMetrics().density);
      content.setPadding(padding, padding, padding, padding);
      status.setMaxLines(Integer.MAX_VALUE);
      content.addView(status);
      pageScroll = new ScrollView(this);
      pageScroll.addView(content);
      setContentView(pageScroll);
      buttonContainer = null;
    }
    long revision = ++loadRevision;
    backend = null;
    hostInstanceId = null;
    loading = true;
    creating = false;
    while (content.getChildCount() > 1) content.removeViewAt(1);
    status.setText(UiText.choose("正在读取真实会话…", "Reading conversations\u2026"));
    // A notification/assistant can be the first Activity after process recreation.
    // Re-adopt the verified running instance before asking for its auth identity.
    // This is read-only recovery: no guest start and no business request replay.
    HarnessController.get(this)
        .restoreRunningWebSession(() -> runOnUiThread(() -> loadLocal(revision)));
  }

  private void loadLocal(long revision) {
    if (revision != loadRevision || isDestroyed()) return;
    worker.execute(
        () -> {
          try {
            AssistantBackend candidate = new LocalAssistantBackend(this);
            JSONObject result = candidate.call("sessions.list", new JSONObject());
            String host = result.getString("hostInstanceId");
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed() || revision != loadRevision) return;
                  if (!candidate.current()) {
                    loading = false;
                    showUnavailable(new java.io.IOException("CONNECTION_CHANGED"));
                    return;
                  }
                  loading = false;
                  backend = candidate;
                  hostInstanceId = host;
                  showSessions(result.optJSONArray("items"));
                });
          } catch (Exception failure) {
            runOnUiThread(
                () -> {
                  if (revision == loadRevision && !isDestroyed()) {
                    loading = false;
                    showUnavailable(failure);
                  }
                });
          }
        });
  }

  private void localWorkspace() {
    startActivity(MainActivity.homeIntent(this));
  }

  private void overlayPermission() {
    if (!Settings.canDrawOverlays(this))
      button(
          UiText.choose("允许悬浮窗（离开应用后仍可聊天和停止）", "Allow overlay to chat and stop from other apps"),
          () ->
              startActivity(
                  new Intent(
                      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                      Uri.parse("package:" + getPackageName()))));
  }

  private void showUnavailable(Exception failure) {
    while (content.getChildCount() > 1) content.removeViewAt(1);
    var local = HarnessController.get(this);
    String code = failure.getMessage();
    if (local.isRestoringWebSession() || local.isStarting())
      status.setText(
          UiText.choose(
              "本机 DSH 正在启动，准备好后会自动显示会话。",
              "Local DSH is starting. Conversations will appear when it is ready."));
    else if (local.hasUnresolvedWebSession())
      status.setText(
          UiText.choose(
              "本机运行状态待核对。请在本机工作台查看诊断后继续。",
              "Local runtime status needs checking. Open the local workspace to review diagnostics."));
    else if (local.getReadyWebPageIdentity() == null)
      status.setText(
          UiText.choose(
              "本机 DSH 尚未运行。先在本机工作台启动，再返回这里选择会话。",
              "Local DSH is stopped. Start it in the local workspace, then return to choose a conversation."));
    else if ("LOCAL_ASSISTANT_API_UNAVAILABLE".equals(code))
      status.setText(
          UiText.choose(
              "DSH 已运行，但悬浮助手接口尚未加载。请正常停止并重新启动本机 DSH，再刷新。",
              "DSH is running, but its assistant interface is unavailable. Stop and restart local DSH normally, then refresh."));
    else
      status.setText(
          UiText.choose(
              "暂时无法连接本机 DSH 会话。请刷新，或打开本机工作台检查运行状态。",
              "Unable to reach local conversations. Refresh or open the local workspace to check its status."));
    button(UiText.choose("打开本机工作台", "Open local workspace"), this::localWorkspace);
    button(UiText.choose("刷新会话", "Refresh conversations"), this::selectLocal);
    overlayPermission();
  }

  private void showSessions(JSONArray sessions) {
    while (content.getChildCount() > 1) content.removeViewAt(1);
    status.setText(UiText.choose("本机 DSH 会话", "Local DSH conversations"));
    overlayPermission();
    button(
        UiText.choose("新建会话", "New conversation"),
        () -> {
          AssistantBackend expected = backend;
          String host = hostInstanceId;
          long revision = loadRevision;
          if (creating || expected == null || !expected.current()) return;
          creating = true;
          status.setText(UiText.choose("正在新建会话…", "Creating conversation\u2026"));
          worker.execute(
              () -> {
                try {
                  JSONObject result =
                      expected.call(
                          "sessions.create", new JSONObject().put("hostInstanceId", host));
                  runOnUiThread(
                      () -> {
                        if (revision == loadRevision
                            && backend == expected
                            && expected.current()
                            && !isDestroyed()) bind(result.optString("sessionId"));
                      });
                } catch (Exception failure) {
                  runOnUiThread(
                      () -> {
                        if (revision == loadRevision && backend == expected && !isDestroyed())
                          status.setText(
                              UiText.choose(
                                  "新建结果待核对，请刷新会话列表",
                                  "Creation result unknown; refresh conversations before retrying"));
                      });
                }
              });
        });
    if (sessions == null) {
      status.setText(
          UiText.choose(
              "目标未提供有效会话列表，请刷新或打开完整 DSH。",
              "The target did not provide a valid conversation list. Refresh or open the full DSH interface."));
      button(UiText.choose("刷新会话", "Refresh conversations"), this::selectLocal);
      return;
    }
    if (sessions.length() == 0) {
      TextView empty = new TextView(this);
      empty.setText(
          UiText.choose(
              "还没有会话。新建后即可在这里输入文字、语音，并从悬浮条查看回复、暂停或停止任务。模型与密钥在 DSH 网页中设置。",
              "No conversations yet. Create one to type or dictate a message, read replies, pause, or stop from the floating strip. Configure your model and key in DSH."));
      content.addView(empty);
    }
    String selected = getIntent().getStringExtra("sessionId");
    for (int index = 0; index < sessions.length(); index++) {
      JSONObject session = sessions.optJSONObject(index);
      if (session == null) continue;
      String sessionId = session.optString("sessionId");
      if (sessionId.isBlank()) continue;
      button(
          String.format(
              UiText.choose("%s · %s", "%s · %s"),
              session.optString("title", sessionId),
              session.optBoolean("running")
                  ? UiText.choose("执行中", "Running")
                  : UiText.choose("就绪", "Ready")),
          () -> bind(sessionId));
      if (sessionId.equals(selected)) {
        getIntent().removeExtra("sessionId");
        bind(sessionId);
      }
    }
    button(UiText.choose("刷新会话", "Refresh conversations"), this::selectLocal);
  }

  private void bind(String sessionId) {
    try {
      if (backend == null || !backend.current())
        throw new IllegalStateException("ASSISTANT_TARGET_CHANGED");
      var local = (LocalAssistantBackend) backend;
      AssistantBinding binding =
          new AssistantBinding(
              hostInstanceId,
              sessionId,
              "local-" + local.generation + "-" + hostInstanceId,
              local.generation);
      status.setText(
          String.format(
              UiText.choose(
                  "已选择会话：%s。悬浮条或通知可展开聊天。",
                  "Selected conversation: %s. Open chat from the floating strip or notification."),
              sessionId));
      long revision = loadRevision;
      AssistantService.bind(
          this,
          binding,
          backend,
          () -> {
            var active = AssistantSessionController.active();
            if (revision == loadRevision
                && !isFinishing()
                && !isDestroyed()
                && active != null
                && active.snapshot().binding().equals(binding)) showBound();
          });
    } catch (RuntimeException failure) {
      status.setText(
          String.format(
              UiText.choose("无法启动悬浮助手：%s", "Unable to start assistant: %s"), failure.getMessage()));
    }
  }

  private void showNotification(Intent intent) {
    AssistantSessionController selected = AssistantNotificationNavigation.resolve(this, intent);
    if (selected == null) {
      finish();
      return;
    }
    controlPermissionMode = false;
    showBound(selected);
  }

  private boolean boundCurrent() {
    if (bound == null || AssistantWindowOwner.from(this).current() != bound) return false;
    return !AssistantNotificationNavigation.requested(getIntent())
        || AssistantNotificationNavigation.resolve(this, getIntent()) == bound;
  }

  private boolean editorCurrent() {
    return boundCurrent() && AssistantService.ownsNativeEditor(bound, editorToken);
  }

  private void showBound() {
    showBound(AssistantWindowOwner.from(this).current());
  }

  private void showBound(AssistantSessionController selected) {
    boundViewRevision++;
    loadRevision++;
    loading = false;
    creating = false;
    editorRevision++;
    if (bound != null) bound.removeListener(changed);
    bound = selected;
    if (!boundCurrent()) {
      finish();
      return;
    }
    ((android.view.ViewGroup) status.getParent()).removeView(status);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setFocusableInTouchMode(true);
    content.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
    int padding = Math.round(16 * getResources().getDisplayMetrics().density);
    content.setPadding(padding, padding, padding, padding);
    status.setMaxLines(2);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    content.addView(status);
    messages = new TextView(this);
    // A selectable transcript becomes a TextView input client and can steal IME
    // focus when periodic status updates set its text. Only the composer edits.
    messages.setTextIsSelectable(false);
    messages.setFocusable(false);
    messages.setTextSize(15);
    // Only the history scrolls. The composer remains in the resized Activity
    // viewport regardless of how many turns have accumulated above it.
    pageScroll = new ScrollView(this);
    pageScroll.addView(messages);
    content.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    input = new EditText(this);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setFocusableInTouchMode(true);
    input.setShowSoftInputOnFocus(true);
    input.setHint(UiText.choose("向当前绑定会话发送消息", "Message the bound conversation"));
    input.setMinLines(2);
    input.setMaxLines(4);
    content.addView(input);
    android.widget.HorizontalScrollView actions = new android.widget.HorizontalScrollView(this);
    buttonContainer = new LinearLayout(this);
    buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
    actions.addView(buttonContainer);
    content.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    setContentView(content);
    draftRevision = -1;
    long composingRevision = boundViewRevision;
    input.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            synchronized (selected) {
              if (composingRevision == boundViewRevision && bound == selected && editorCurrent())
                selected.setDraft(s.toString());
            }
          }

          public void afterTextChanged(android.text.Editable value) {}
        });
    synchronized (selected) {
      if (bound != selected || !boundCurrent()) {
        finish();
        return;
      }
      bound.collapse();
      bound.beginComposing(editorSurface);
      AssistantService.nativeEditorVisible(bound, editorToken, true);
    }
    pendingInput = getIntent().getBooleanExtra("focus_input", false);
    input.setOnFocusChangeListener(
        (v, focus) -> {
          synchronized (selected) {
            if (focus
                && composingRevision == boundViewRevision
                && bound == selected
                && editorCurrent()) selected.beginComposing(editorSurface);
          }
        });
    sendButton =
        button(
            UiText.choose("发送", "Send"),
            () -> {
              if (AssistantNotificationNavigation.send(
                  this, getIntent(), bound, input.getText().toString())) finish();
            });
    button(
        UiText.choose("语音输入", "Voice input"),
        () -> {
          startActivity(AssistantNotificationNavigation.voiceIntent(this, getIntent()));
        });
    button(UiText.choose("暂停", "Pause"), () -> bound.pause("USER_PAUSED"));
    resumeButton =
        button(
            UiText.choose("继续", "Continue"),
            () -> {
              if (AssistantNotificationNavigation.resume(this, getIntent(), bound)) finish();
            });
    button(UiText.choose("停止当前任务", "Stop current task"), bound::stop);
    button(
        UiText.choose("打开完整 DSH 会话 / 模型设置", "Open full DSH conversation / model settings"),
        bound::returnConversation);
    button(UiText.choose("选择其他会话", "Choose another conversation"), this::selectLocal);
    button(
        UiText.choose("收起并返回其他应用", "Collapse and return to other apps"),
        () -> {
          bound.collapse();
          finish();
        });
    button(
        UiText.choose("结束悬浮助手", "Close assistant"),
        () -> {
          stopService(new Intent(this, AssistantService.class));
          finish();
        });
    bound.addListener(changed);
    renderBound();
    if (pendingInput) input.post(this::focusComposer);
  }

  private void focusComposer() {
    if (!pendingInput || !resumed || input == null || !hasWindowFocus() || isFinishing()) return;
    if (!input.requestFocus()) return;
    input.requestRectangleOnScreen(
        new android.graphics.Rect(0, 0, input.getWidth(), input.getHeight()), true);
    EditText expected = input;
    long revision = editorRevision;
    input.post(
        () -> {
          if (!resumed
              || revision != editorRevision
              || input != expected
              || !hasWindowFocus()
              || !expected.hasFocus()
              || !expected.isAttachedToWindow()) return;
          var ime =
              (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          ime.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
          pendingInput = false;
          getIntent().removeExtra("focus_input");
        });
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && pendingInput && input != null) input.post(this::focusComposer);
  }

  private void renderBound() {
    if (bound == null || messages == null || isDestroyed()) return;
    if (!boundCurrent()) {
      finish();
      return;
    }
    var state = bound.snapshot();
    status.setText(
        UiText.format("%s · %s", UiText.choose("本机", "Local"), AssistantWindow.label(state.phase()))
            + "\n"
            + state.binding().sessionId());
    String transcript =
        (state.text().isEmpty()
                ? UiText.choose(
                    "在下方输入消息开始对话。模型和 API Key 请先在完整 DSH 会话中设置。",
                    "Type below to begin. Configure your model and API key in the full DSH conversation first.")
                : state.text())
            + "\n\n"
            + AssistantWindow.detail(state);
    if (!transcript.contentEquals(messages.getText())) messages.setText(transcript);
    sendButton.setEnabled(state.terminalKnown());
    resumeButton.setEnabled(
        state.terminalKnown()
            && state.paused()
            && state.phase() != AssistantSessionState.Phase.STOPPED);
    if (draftRevision != state.draftRevision()) {
      draftRevision = state.draftRevision();
      if (!state.draft().contentEquals(input.getText())) input.setText(state.draft());
    }
  }

  private Button button(String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    (buttonContainer == null ? content : buttonContainer).addView(button);
    AssistantSessionController expected = bound;
    long expectedRevision = boundViewRevision;
    button.setOnClickListener(
        v -> {
          if (expected == null) {
            action.run();
            return;
          }
          synchronized (expected) {
            if (bound != expected || boundViewRevision != expectedRevision || !editorCurrent())
              return;
            action.run();
          }
        });
    return button;
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (!controlPermissionMode && !isFinishing()) {
      HarnessController.get(this).addReadyWebPageListener(localReady);
      readyListenerAdded = true;
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (isFinishing()) return;
    if ((bound != null && !boundCurrent())
        || (AssistantNotificationNavigation.requested(getIntent())
            && AssistantNotificationNavigation.resolve(this, getIntent()) != bound)) {
      finish();
      return;
    }
    resumed = true;
    content.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
    if (controlPermissionMode) {
      if (notificationPermissionMode) {
        status.setText(
            AssistantService.stopNotificationAvailable(this)
                ? UiText.choose("返回原对话继续", "Return to the original conversation")
                : UiText.choose("手机任务状态", "Phone task status"));
        return;
      }
      status.setText(
          Settings.canDrawOverlays(this)
              ? UiText.choose(
                  "已允许悬浮窗。请返回原对话明确继续；不会自动执行。",
                  "Overlay permission allowed. Return to the original conversation and explicitly continue; execution is not automatic.")
              : UiText.choose("手机任务需要悬浮窗权限", "Phone tasks require overlay permission"));
      return;
    }
    if (input != null) {
      input.setFocusableInTouchMode(true);
      input.setShowSoftInputOnFocus(true);
    }
    if (bound != null) {
      synchronized (bound) {
        if (!boundCurrent()) {
          finish();
          return;
        }
        // 从语音或其他界面回到编辑器时，在同轮复核后重新取得呈现。
        bound.beginComposing(editorSurface);
        AssistantService.nativeEditorVisible(bound, editorToken, true);
      }
      if (pendingInput && input != null) input.post(this::focusComposer);
    }
    if (bound == null && !loading) selectLocal();
  }

  @Override
  protected void onPause() {
    resumed = false;
    pendingInput = false;
    editorRevision++;
    // clearFocus alone lets Android immediately choose the same EditText again.
    // Park focus on the container and block descendants until the next resume.
    content.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    if (input != null) {
      input.setShowSoftInputOnFocus(false);
      input.setFocusable(false);
      input.clearFocus();
      content.requestFocus();
      var ime =
          (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }
    if (bound != null) {
      synchronized (bound) {
        if (editorCurrent()) bound.collapse();
        AssistantService.nativeEditorVisible(bound, editorToken, false);
        // 仅释放本 Activity 的输入来源；旧 onPause 不碰后继的输入闸。
        bound.endComposing(editorSurface);
      }
    }
    super.onPause();
  }

  @Override
  protected void onStop() {
    if (readyListenerAdded) {
      HarnessController.get(this).removeReadyWebPageListener(localReady);
      readyListenerAdded = false;
    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (bound != null) bound.removeListener(changed);
    worker.shutdown();
    super.onDestroy();
  }
}
