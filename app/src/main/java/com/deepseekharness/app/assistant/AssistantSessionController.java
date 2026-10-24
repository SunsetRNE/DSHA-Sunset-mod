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
  public record Snapshot(
      AssistantBinding binding,
      AssistantSessionState.Phase phase,
      String turnId,
      String taskId,
      String text,
      String detail,
      String draft,
      long draftRevision,
      boolean expanded,
      boolean terminalKnown,
      boolean paused) {}

  private static volatile AssistantSessionController active;

  public static AssistantSessionController active() {
    return active;
  }

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
  private final java.util.function.Consumer<
          com.deepseekharness.app.control.ControlTaskAuthority.Binding>
      cancelled = this::cancelTurn;

  public AssistantSessionController(
      Context context, AssistantBinding binding, AssistantBackend backend) {
    this.binding = binding;
    this.backend = backend;
    fence = PhoneControlCoordinator.get(context);
    AssistantSessionController previous = active;
    if (previous != null) previous.close();
    active = this;
    fence.setTurnCancellation(cancelled);
    worker.scheduleWithFixedDelay(this::poll, 0, 900, TimeUnit.MILLISECONDS);
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(
        binding,
        state.phase(),
        state.turnId(),
        state.taskId(),
        text,
        state.detail(),
        draft,
        draftRevision,
        expanded,
        state.terminalKnown(),
        state.paused());
  }

  public void addListener(Runnable listener) {
    listeners.addIfAbsent(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  public synchronized void setDraft(String value) {
    String next = value == null ? "" : value;
    if (next.equals(draft)) return;
    draft = next;
    draftRevision++;
    changed();
  }

  private synchronized void admittedDraft(String submitted) {
    // A later edit belongs to the next prompt, even if this admission arrived late.
    if (draft.equals(submitted)) setDraft("");
  }

  public void expand() {
    // Fence synchronously before a focusable window/IME or recognizer can appear.
    if (!expanded) {
      expanded = true;
      pause("ASSISTANT_EXPANDED");
    }
    changed();
  }

  public void collapse() {
    expanded = false;
    changed();
  }

  public void pause(String reason) {
    state.pause(reason);
    suppressCancellation = true;
    try {
      fence.pause(reason);
    } finally {
      suppressCancellation = false;
    }
    requestCancellation(state.turnId());
    changed();
  }

  /** Editing fences physical input without sending a model cancellation in the background. */
  public void beginEditing() {
    state.pause("ASSISTANT_IME");
    suppressCancellation = true;
    try {
      fence.pause("ASSISTANT_IME");
    } finally {
      suppressCancellation = false;
    }
    changed();
  }

  public void stop() {
    state.stop();
    suppressCancellation = true;
    try {
      fence.stop("ASSISTANT_STOPPED");
    } finally {
      suppressCancellation = false;
    }
    requestCancellation(state.turnId());
    changed();
  }

  private boolean owns(com.deepseekharness.app.control.ControlTaskAuthority.Binding control) {
    return backend.ownsControlBinding(control)
        && binding.sessionId().equals(control.taskSessionId());
  }

  private void cancelTurn(com.deepseekharness.app.control.ControlTaskAuthority.Binding previous) {
    // The native fence has already invalidated every queued screen operation.
    if (closed || suppressCancellation || !owns(previous)) return;
    var authority = fence.snapshot();
    if ("REMOTE_STOP".equals(authority.reason())) state.stop();
    else if (authority.paused()
        && state.phase() != AssistantSessionState.Phase.UNKNOWN
        && state.phase() != AssistantSessionState.Phase.DISCONNECTED)
      state.pause(authority.reason());
    changed();
    requestCancellation(previous.turnId());
  }

  private void requestCancellation(String turn) {
    if (closed) return;
    if (!cancelling.compareAndSet(false, true)) return;
    String identity = turn + "\0" + binding.sessionId();
    if (identity.equals(cancelAttempt)) {
      cancelling.set(false);
      return;
    }
    cancelAttempt = identity;
    worker.execute(
        () -> {
          try {
            backend.call("sessions.cancel", args().put("turnId", turn));
          } catch (Exception unknown) {
            state.unknown(
                UiText.choose(
                    "取消结果未知；正在核对会话状态", "Cancellation result unknown; checking conversation state"));
          } finally {
            cancelling.set(false);
            changed();
          }
        });
  }

  public boolean resume() {
    return sendInternal(
        UiText.choose(
            "继续当前任务。操作手机前必须重新观察当前屏幕，再决定下一步；不要重放结果未知的操作。",
            "Continue the current task. Observe the current phone screen again before deciding the next action. Do not replay operations with unknown results."),
        true);
  }

  public boolean send(String message) {
    return sendInternal(message, false);
  }

  private boolean sendInternal(String message, boolean continuation) {
    if (message == null || message.isBlank() || message.length() > 32768 || closed) return false;
    if (!state.terminalKnown() || cancelling.get()) {
      changed();
      return false;
    }
    var old = fence.snapshot().binding();
    if (old != null && (!owns(old) || !old.turnId().equals(state.turnId()))) {
      state.failed(
          UiText.choose(
              "另一项手机任务仍占用控制权。请先结束该任务，再发送或继续。",
              "Another phone task still owns control. End that task before sending or continuing."));
      changed();
      return false;
    }
    try {
      if (continuation) state.resume();
      else state.newTask();
      // Retire only the old selected conversation's screen ownership, after real idle.
      suppressCancellation = true;
      try {
        if (old != null && owns(old)) fence.stop("CONTINUATION");
        fence.resume();
      } finally {
        suppressCancellation = false;
      }
      expanded = false;
      cancelAttempt = null;
      state.sending();
    } catch (IllegalStateException blocked) {
      changed();
      return false;
    }
    String requestId = UUID.randomUUID().toString();
    changed();
    worker.execute(
        () -> {
          try {
            if (!backend.current() || closed) throw new java.io.IOException("CONNECTION_CHANGED");
            JSONObject request = args().put("text", message).put("requestId", requestId);
            JSONObject result = backend.call("sessions.send", request);
            if (!result.optBoolean("accepted"))
              throw new java.io.IOException("NO_ADMISSION_RECEIPT");
            state.accepted();
            if (!continuation) admittedDraft(message);
          } catch (AssistantBackend.Rejected rejected) {
            // Only the Host's explicit pre-admission errors get this path. Transport
            // loss still remains UNKNOWN; no prompt is retried automatically.
            suppressCancellation = true;
            try {
              fence.pause("ASSISTANT_PROMPT_REJECTED");
            } finally {
              suppressCancellation = false;
            }
            state.rejected(
                "session/model-unavailable".equals(rejected.getMessage())
                    ? UiText.choose(
                        "消息尚未发送：请打开完整 DSH 会话设置可用模型和 API Key，然后重新发送。草稿已保留。",
                        "Message not sent. Open the full DSH conversation to configure an available model and API key, then send again. Your draft is retained.")
                    : UiText.choose(
                        "消息未被接收。请打开完整会话检查模型或会话设置；草稿已保留。",
                        "Message was not admitted. Open the full conversation to check its model or settings. Your draft is retained."));
          } catch (Exception failure) {
            state.unknown(
                UiText.choose(
                    "发送结果未知；请先核对会话，勿重复发送",
                    "Send result unknown; check the conversation before sending again"));
          }
          changed();
        });
    return true;
  }

  private JSONObject args() throws org.json.JSONException {
    return new JSONObject()
        .put("sessionId", binding.sessionId())
        .put("hostInstanceId", binding.hostInstanceId())
        .put("turnId", state.turnId())
        .put("taskId", state.taskId());
  }

  private void poll() {
    if (closed) return;
    if (state.phase() == AssistantSessionState.Phase.DISCONNECTED) return;
    if (!backend.current()) {
      state.disconnected();
      fence.pause("ASSISTANT_DISCONNECTED");
      changed();
      return;
    }
    try {
      JSONObject value = backend.call("sessions.status", args());
      if (!binding.sessionId().equals(value.optString("sessionId")) || !value.has("running"))
        throw new java.io.IOException("STATUS_IDENTITY_OR_STATE_MISSING");
      String host = value.optString("hostInstanceId", binding.hostInstanceId());
      if (!binding.hostInstanceId().equals(host))
        throw new java.io.IOException("HOST_INSTANCE_CHANGED");
      var transcript = value.optJSONArray("messages");
      if (transcript == null) text = SensitiveData.redact(value.optString("text"));
      else {
        StringBuilder rendered = new StringBuilder();
        for (int index = Math.max(0, transcript.length() - 8);
            index < transcript.length();
            index++) {
          var message = transcript.optJSONObject(index);
          if (message == null) continue;
          String body = message.optString("text");
          if (body.isBlank()) continue;
          if (rendered.length() > 0) rendered.append("\n\n");
          rendered
              .append(
                  "user".equals(message.optString("role"))
                      ? UiText.choose("你：\n", "You:\n")
                      : UiText.choose("DSH：\n", "DSH:\n"))
              .append(body);
        }
        String stream = value.optString("streamText");
        if (!stream.isBlank())
          rendered
              .append(UiText.choose("\n\nDSH（生成中）：\n", "\n\nDSH (generating):\n"))
              .append(stream);
        text = SensitiveData.redact(rendered.toString());
      }
      state.observed(
          value.getBoolean("running"), value.optString("turnId"), value.optString("taskId"));
      if (value.optBoolean("failed"))
        state.failed(
            UiText.choose(
                "本轮执行失败。请打开完整 DSH 会话查看错误，并检查模型、密钥或网络设置。",
                "This turn failed. Open the full DSH conversation to review the error and check your model, key, or network settings."));
    } catch (Exception failure) {
      state.unknown(UiText.choose("状态暂不可确认；手机操作已暂停", "Status unavailable; phone actions paused"));
      fence.pause("ASSISTANT_STATUS_UNKNOWN");
    }
    changed();
  }

  public void returnConversation() {
    pause("RETURN_CONVERSATION");
    worker.execute(
        () -> {
          try {
            backend.returnConversation(binding);
          } catch (Exception failure) {
            state.unknown(
                UiText.choose(
                    "无法打开绑定会话；连接可能已改变",
                    "Unable to open the bound conversation; the connection may have changed"));
            changed();
          }
        });
  }

  private void changed() {
    if (closed) return;
    main.post(
        () -> {
          if (!closed) for (Runnable listener : listeners) listener.run();
        });
  }

  @Override
  public void close() {
    if (closed) return;
    // Invalidate screen work before stopping the subscription. Never stop the whole DSH.
    state.stop();
    suppressCancellation = true;
    try {
      fence.stop("ASSISTANT_CLOSED");
    } finally {
      suppressCancellation = false;
    }
    requestCancellation(state.turnId());
    closed = true;
    worker.shutdown();
    listeners.clear();
    if (active == this) {
      active = null;
      fence.setTurnCancellation(null);
    }
  }
}
