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
import com.deepseekharness.app.remote.RemoteSessionManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native full conversation and explicit local/PC picker. No hidden local runtime bootstrap. */
public final class AssistantActivity extends AppCompatActivity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout content;
  private TextView status;
  private AssistantBackend backend;
  private AssistantBinding.Target target;
  private String hostInstanceId;
  private long loadRevision;
  private AssistantSessionController bound;
  private TextView messages;
  private EditText input;
  private long draftRevision = -1;
  private final Runnable changed = this::renderBound;
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(16 * getResources().getDisplayMetrics().density); content.setPadding(padding,padding,padding,padding);
    scroll.addView(content); setContentView(scroll);
    status = new TextView(this); status.setTextSize(20); status.setText(UiText.choose("DSHA 悬浮助手", "DSHA floating assistant")); content.addView(status);
    if (getIntent().getBooleanExtra("bound", false) && AssistantSessionController.active() != null) { showBound(); return; }
    button(UiText.choose("本机 DSH", "Local DSH"), () -> selectTarget(AssistantBinding.Target.LOCAL));
    button(UiText.choose("电脑 DSH", "PC DSH"), () -> selectTarget(AssistantBinding.Target.REMOTE));
    if (!Settings.canDrawOverlays(this)) button(UiText.choose("允许悬浮窗", "Allow floating window"), () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
    String requested = getIntent().getStringExtra("target");
    if ("LOCAL".equals(requested)) selectTarget(AssistantBinding.Target.LOCAL);
    if ("REMOTE".equals(requested)) selectTarget(AssistantBinding.Target.REMOTE);
  }
  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent); setIntent(intent);
    if (intent.getBooleanExtra("bound", false)) showBound();
    else if ("REMOTE".equals(intent.getStringExtra("target"))) selectTarget(AssistantBinding.Target.REMOTE);
    else if ("LOCAL".equals(intent.getStringExtra("target"))) selectTarget(AssistantBinding.Target.LOCAL);
  }
  private void selectTarget(AssistantBinding.Target value) {
    target = value; long revision = ++loadRevision; backend = null; hostInstanceId = null;
    while (content.getChildCount() > 1) content.removeViewAt(1);
    button(UiText.choose("切换目标", "Change target"), () -> { finish(); startActivity(new Intent(this, AssistantActivity.class)); });
    status.setText(UiText.choose("正在读取真实会话…", "Reading conversations\u2026"));
    worker.execute(() -> {
      try {
        AssistantBackend candidate;
        if (value == AssistantBinding.Target.LOCAL) candidate = new LocalAssistantBackend(this);
        else {
          var identity = RemoteSessionManager.get(this).current();
          if (identity == null) throw new java.io.IOException(UiText.choose("请先连接并信任电脑端", "Connect and trust a PC first"));
          candidate = new RemoteAssistantBackend(this, identity);
        }
        JSONObject result = candidate.call("sessions.list", new JSONObject());
        String host = candidate instanceof RemoteAssistantBackend remote ? remote.identity.hostInstanceId : result.getString("hostInstanceId");
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed() || target != value || revision != loadRevision || !candidate.current()) return;
          backend = candidate; hostInstanceId = host; showSessions(result.optJSONArray("items"));
        });
      } catch (Exception failure) { runOnUiThread(() -> {
        if (revision == loadRevision && !isDestroyed()) status.setText(String.format(UiText.choose("无法读取会话：%s", "Unable to read conversations: %s"), com.deepseekharness.app.util.SensitiveData.redact(failure.getMessage())));
      }); }
    });
  }
  private void showSessions(JSONArray sessions) {
    while (content.getChildCount() > 1) content.removeViewAt(1);
    status.setText(target == AssistantBinding.Target.LOCAL ? UiText.choose("本机 DSH 会话", "Local DSH conversations") : UiText.choose("电脑 DSH 会话", "PC DSH conversations"));
    button(UiText.choose("切换目标", "Change target"), () -> { finish(); startActivity(new Intent(this, AssistantActivity.class)); });
    button(UiText.choose("新建会话", "New conversation"), () -> {
      AssistantBackend expected = backend; String host = hostInstanceId; long revision = loadRevision;
      if (expected == null || !expected.current()) return;
      status.setText(UiText.choose("正在新建会话…", "Creating conversation\u2026")); worker.execute(() -> {
        try { JSONObject result = expected.call("sessions.create", new JSONObject().put("hostInstanceId", host));
          runOnUiThread(() -> {
            if (revision == loadRevision && backend == expected && expected.current() && !isDestroyed()) bind(result.optString("sessionId"));
          }); }
        catch (Exception failure) { runOnUiThread(() -> {
          if (revision == loadRevision && backend == expected && !isDestroyed()) status.setText(UiText.choose("新建结果待核对，请刷新会话列表", "Creation result unknown; refresh conversations before retrying"));
        }); }
      });
    });
    if (sessions == null) { status.setText(UiText.choose("电脑未提供会话列表", "The PC did not provide a conversation list")); return; }
    String selected = getIntent().getStringExtra("sessionId");
    for (int index = 0; index < sessions.length(); index++) {
      JSONObject session = sessions.optJSONObject(index); if (session == null) continue;
      String sessionId = session.optString("sessionId"); if (sessionId.isBlank()) continue;
      button(String.format(UiText.choose("%s · %s", "%s · %s"), session.optString("title", sessionId),
          session.optBoolean("running") ? UiText.choose("执行中", "Running") : UiText.choose("就绪", "Ready")), () -> bind(sessionId));
      if (sessionId.equals(selected)) { getIntent().removeExtra("sessionId"); bind(sessionId); }
    }
    button(UiText.choose("刷新会话", "Refresh conversations"), () -> selectTarget(target));
  }
  private void bind(String sessionId) {
    try {
      if (backend == null || !backend.current()) throw new IllegalStateException("ASSISTANT_TARGET_CHANGED");
      AssistantBinding binding;
      if (backend instanceof RemoteAssistantBackend remote) binding = new AssistantBinding(target, hostInstanceId, sessionId,
          remote.identity.connectionEpoch, remote.identity.generation, remote.identity);
      else { var local = (LocalAssistantBackend) backend; binding = new AssistantBinding(target, hostInstanceId, sessionId,
          "local-" + local.generation + "-" + hostInstanceId, local.generation, null); }
      AssistantService.bind(this, binding, backend);
      // Service starts asynchronously; the full native view is available via its notification.
      status.setText(String.format(UiText.choose("已选择会话：%s。悬浮条或通知可展开聊天。", "Selected conversation: %s. Open chat from the floating strip or notification."), sessionId));
      content.postDelayed(() -> {
        var active = AssistantSessionController.active();
        if (!isFinishing() && active != null && active.snapshot().binding().equals(binding)) showBound();
      }, 350);
    } catch (RuntimeException failure) { status.setText(String.format(UiText.choose("无法启动悬浮助手：%s", "Unable to start assistant: %s"), failure.getMessage())); }
  }
  private void showBound() {
    if (bound != null) bound.removeListener(changed);
    bound = AssistantSessionController.active(); if (bound == null) return;
    while (content.getChildCount() > 1) content.removeViewAt(1);
    messages = new TextView(this); messages.setTextIsSelectable(true); messages.setTextSize(15); content.addView(messages);
    input = new EditText(this); input.setHint(UiText.choose("向当前绑定会话发送消息", "Message the bound conversation")); input.setMinLines(2); content.addView(input);
    draftRevision = -1;
    input.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
      public void onTextChanged(CharSequence s,int start,int before,int count) { bound.setDraft(s.toString()); }
      public void afterTextChanged(android.text.Editable value) {}
    });
    bound.expand();
    input.setOnFocusChangeListener((v,focus) -> { if (focus) bound.pause("ASSISTANT_IME"); });
    button(UiText.choose("发送", "Send"), () -> { if (bound.send(input.getText().toString())) finish(); });
    button(UiText.choose("语音输入", "Voice input"), () -> { bound.pause("ASSISTANT_VOICE"); startActivity(new Intent(this, AssistantVoiceActivity.class)); });
    button(UiText.choose("暂停", "Pause"), () -> bound.pause("USER_PAUSED")); button(UiText.choose("继续", "Continue"), () -> { if (bound.resume()) finish(); }); button(UiText.choose("停止当前任务", "Stop current task"), bound::stop);
    button(UiText.choose("收起并返回其他应用", "Collapse and return to other apps"), () -> { bound.collapse(); finish(); });
    button(UiText.choose("结束悬浮助手", "Close assistant"), () -> { stopService(new Intent(this, AssistantService.class)); finish(); });
    bound.addListener(changed); renderBound();
  }
  private void renderBound() {
    if (bound == null || messages == null || isDestroyed()) return;
    var state = bound.snapshot();
    status.setText((state.binding().target() == AssistantBinding.Target.LOCAL ? UiText.choose("本机", "Local") : UiText.choose("电脑", "PC")) + " · " + AssistantWindow.label(state.phase())
        + "\n" + state.binding().sessionId());
    messages.setText(state.text() + "\n\n" + state.detail());
    if (draftRevision != state.draftRevision()) {
      draftRevision = state.draftRevision();
      if (!state.draft().contentEquals(input.getText())) input.setText(state.draft());
    }
  }
  private void button(String label, Runnable action) {
    Button button = new Button(this); button.setText(label); content.addView(button); button.setOnClickListener(v -> action.run());
  }
  @Override protected void onDestroy() {
    if (bound != null) bound.removeListener(changed); worker.shutdown(); super.onDestroy();
  }
}
