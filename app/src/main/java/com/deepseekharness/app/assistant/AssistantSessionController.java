package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import com.deepseekharness.app.util.SensitiveData;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One selected full conversation. All mutations serialize and are never retried. */
public final class AssistantSessionController implements AutoCloseable {
  public record Snapshot(AssistantBinding binding, AssistantSessionState.Phase phase,
      String turnId, String taskId, String text, String detail, String draft, long draftRevision, boolean expanded,
      boolean terminalKnown, boolean paused) {}
  private static volatile AssistantSessionController active;
  public static AssistantSessionController active() { return active; }
  private final AssistantBinding binding;
  private final AssistantBackend backend;
  private final PhoneControlCoordinator fence;
  private final AssistantSessionState state = new AssistantSessionState();
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final AtomicBoolean cancelling = new AtomicBoolean();
  private volatile boolean closed, expanded;
  private volatile boolean suppressCancellation;
  private volatile String text = "", draft = "";
  private volatile long draftRevision;
  private volatile String cancelAttempt = null;
  private final Runnable cancelled = this::cancelTurn;
  public AssistantSessionController(Context context, AssistantBinding binding, AssistantBackend backend) {
    this.binding = binding; this.backend = backend; fence = PhoneControlCoordinator.get(context);
    AssistantSessionController previous = active;
    if (previous != null) previous.close();
    active = this; fence.setTurnCancellation(cancelled);
    worker.scheduleWithFixedDelay(this::poll, 0, 900, TimeUnit.MILLISECONDS);
  }
  public synchronized Snapshot snapshot() { return new Snapshot(binding, state.phase(), state.turnId(), state.taskId(),
      text, state.detail(), draft, draftRevision, expanded, state.terminalKnown(), state.paused()); }
  public void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
  public void removeListener(Runnable listener) { listeners.remove(listener); }
  public synchronized void setDraft(String value) {
    String next = value == null ? "" : value;
    if (next.equals(draft)) return;
    draft = next; draftRevision++; changed();
  }
  private synchronized void admittedDraft(String submitted) {
    // A later edit belongs to the next prompt, even if this admission arrived late.
    if (draft.equals(submitted)) setDraft("");
  }
  public void expand() {
    // Fence synchronously before a focusable window/IME or recognizer can appear.
    if (!expanded) { expanded = true; pause("ASSISTANT_EXPANDED"); }
    changed();
  }
  public void collapse() { expanded = false; changed(); }
  public void pause(String reason) { state.pause(reason); fence.pause(reason); changed(); }
  public void stop() { state.stop(); fence.stop("ASSISTANT_STOPPED"); changed(); }
  private void cancelTurn() {
    // The native fence has already invalidated every queued screen operation.
    if (closed || suppressCancellation) return;
    var authority = fence.snapshot();
    if ("REMOTE_STOP".equals(authority.reason())) state.stop();
    else if (authority.paused() && state.phase() != AssistantSessionState.Phase.UNKNOWN
        && state.phase() != AssistantSessionState.Phase.DISCONNECTED) state.pause(authority.reason());
    changed();
    if (!cancelling.compareAndSet(false, true)) return;
    String identity = state.turnId() + "\0" + state.taskId();
    if (identity.equals(cancelAttempt)) { cancelling.set(false); return; }
    cancelAttempt = identity;
    worker.execute(() -> {
      try { backend.call("sessions.cancel", args()); }
      catch (Exception unknown) { state.unknown(UiText.choose("取消结果未知；正在核对会话状态", "Cancellation result unknown; checking conversation state")); }
      finally { cancelling.set(false); changed(); }
    });
  }
  public boolean resume() { return sendInternal(UiText.choose("继续当前任务。操作手机前必须重新观察当前屏幕，再决定下一步；不要重放结果未知的操作。", "Continue the current task. Observe the current phone screen again before deciding the next action. Do not replay operations with unknown results."), true); }
  public boolean send(String message) { return sendInternal(message, false); }
  private boolean sendInternal(String message, boolean continuation) {
    if (message == null || message.isBlank() || message.length() > 32768 || closed) return false;
    if (!state.terminalKnown() || cancelling.get()) { changed(); return false; }
    try {
      if (continuation) state.resume(); else state.newTask();
      // Retire only the old selected conversation's screen ownership, after real idle.
      var old = fence.snapshot().binding();
      suppressCancellation = true;
      try {
        if (old != null && old.taskSessionId().equals(binding.sessionId())) fence.stop("CONTINUATION");
        fence.resume();
      } finally { suppressCancellation = false; }
      expanded = false; cancelAttempt = null; state.sending();
    } catch (IllegalStateException blocked) { changed(); return false; }
    String requestId = UUID.randomUUID().toString();
    changed();
    worker.execute(() -> {
      try {
        if (!backend.current() || closed) throw new java.io.IOException("CONNECTION_CHANGED");
        JSONObject request = args().put("text", message).put("requestId", requestId);
        JSONObject result = backend.call("sessions.send", request);
        if (!result.optBoolean("accepted")) throw new java.io.IOException("NO_ADMISSION_RECEIPT");
        state.accepted(); if (!continuation) admittedDraft(message);
      } catch (Exception failure) { state.unknown(UiText.choose("发送结果未知；请先核对会话，勿重复发送", "Send result unknown; check the conversation before sending again")); }
      changed();
    });
    return true;
  }
  private JSONObject args() throws org.json.JSONException {
    return new JSONObject().put("sessionId", binding.sessionId()).put("hostInstanceId", binding.hostInstanceId())
        .put("turnId", state.turnId()).put("taskId", state.taskId());
  }
  private void poll() {
    if (closed) return;
    if (state.phase() == AssistantSessionState.Phase.DISCONNECTED) return;
    if (!backend.current()) {
      state.disconnected(); fence.pause("ASSISTANT_DISCONNECTED"); changed(); return;
    }
    try {
      JSONObject value = backend.call("sessions.status", args());
      if (!binding.sessionId().equals(value.optString("sessionId")) || !value.has("running"))
        throw new java.io.IOException("STATUS_IDENTITY_OR_STATE_MISSING");
      String host = value.optString("hostInstanceId", binding.hostInstanceId());
      if (!binding.hostInstanceId().equals(host)) throw new java.io.IOException("HOST_INSTANCE_CHANGED");
      text = SensitiveData.redact(value.optString("text"));
      state.observed(value.getBoolean("running"), value.optString("turnId"), value.optString("taskId"));
    } catch (Exception failure) { state.unknown(UiText.choose("状态暂不可确认；手机操作已暂停", "Status unavailable; phone actions paused")); fence.pause("ASSISTANT_STATUS_UNKNOWN"); }
    changed();
  }
  public void returnConversation() {
    pause("RETURN_CONVERSATION");
    worker.execute(() -> { try { backend.returnConversation(binding); } catch (Exception failure) {
      state.unknown(UiText.choose("无法打开绑定会话；连接可能已改变", "Unable to open the bound conversation; the connection may have changed")); changed();
    }});
  }
  private void changed() {
    if (closed) return;
    main.post(() -> { if (!closed) for (Runnable listener : listeners) listener.run(); });
  }
  @Override public void close() {
    if (closed) return;
    // Invalidate screen work before stopping the subscription. Never stop the whole DSH.
    state.stop(); fence.stop("ASSISTANT_CLOSED"); closed = true;
    worker.shutdown(); listeners.clear();
    if (active == this) { active = null; fence.setTurnCancellation(null); }
  }
}
