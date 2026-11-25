package com.deepseekharness.app.screen;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import com.deepseekharness.app.control.ControlTaskAuthority;
import com.deepseekharness.app.util.ScreenActivityEvent;
import com.deepseekharness.app.util.ScreenActivityFeed;
import com.deepseekharness.app.util.ScreenActivityPolicy;
import com.deepseekharness.app.util.ScreenActivityQuery;
import com.deepseekharness.app.util.ScreenTarget;
import com.deepseekharness.app.util.SensitiveAppPolicy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 实体主屏活动历史的进程内记录器：把无障碍事件压成有界摘要，只在手机任务活动期间记录。
 *
 * <p>这是对「无障碍服务不持续记录」承诺的有意收窄：记录只在 phone task（本对话的手机任务）期间发生，
 * 约 120 秒 / 64 条；task-end、暂停或服务断开立即清空；不落盘、不进备份/诊断日志、不上传。敏感应用只留
 * 目标切换，密码框只留「已输入」，输入法与自身浮层的事件直接丢弃。助手的派发动作由动作执行器显式记录，
 * 并用派发窗口把随后到达的无障碍事件归到助手一侧。
 */
public final class ScreenActivityRecorder {
  private ScreenActivityRecorder() {}

  /** 每个回执自动附带的最近活动条数上限。 */
  public static final int RECENT_LIMIT = 3;

  private static final ScreenActivityFeed FEED =
      new ScreenActivityFeed(SystemClock::elapsedRealtime);
  private static ControlTaskAuthority.Lease recordingLease;
  private static java.util.function.BooleanSupplier leaseCurrent;
  private static ScreenTarget approvedTarget;
  private static long targetRevision;
  private static java.util.function.BooleanSupplier targetAllowed;
  private static final Set<String> IGNORED = new CopyOnWriteArraySet<>();
  private static volatile long dispatchUntil;
  private static volatile long lastContentAt = -1L;
  private static volatile long lastTargetAt = -1L;
  private static volatile String lastTargetPackage = "";
  private static volatile String lastTargetActivity = "";

  /**
   * 标记本任务的记录开始（幂等）。身份变化时先清空上一个任务的事件，并刷新输入法/自身包名排除表。
   */
  public static synchronized void observeTask(
      Context context,
      ControlTaskAuthority.Lease lease,
      java.util.function.BooleanSupplier current) {
    if (lease == null || !current.getAsBoolean() || lease.equals(recordingLease)) return;
    clear();
    recordingLease = lease;
    leaseCurrent = current;
    FEED.begin(lease.binding().identity());
    refreshIgnored(context);
  }

  public static synchronized void approveTarget(
      ControlTaskAuthority.Lease lease,
      ScreenTarget target,
      java.util.function.BooleanSupplier allowed) {
    if (!liveTask() || !recordingLease.equals(lease) || target == null || !allowed.getAsBoolean())
      return;
    if (!target.matches(approvedTarget)) {
      targetRevision++;
      FEED.discardTargetDetails();
    }
    approvedTarget = target;
    targetAllowed = allowed;
  }

  private static boolean liveTask() {
    if (recordingLease != null && leaseCurrent.getAsBoolean()) return true;
    if (recordingLease != null) clear();
    return false;
  }

  private static void discardApproval() {
    if (approvedTarget != null) targetRevision++;
    approvedTarget = null;
    targetAllowed = null;
    FEED.discardTargetDetails();
  }

  private static boolean approved(ScreenTarget target) {
    if (!liveTask() || approvedTarget == null) return false;
    if (!targetAllowed.getAsBoolean()) {
      discardApproval();
      return false;
    }
    return approvedTarget.matches(target);
  }

  public static synchronized void clear() {
    targetRevision++;
    recordingLease = null;
    leaseCurrent = null;
    approvedTarget = null;
    targetAllowed = null;
    FEED.clear();
    dispatchUntil = 0L;
    lastContentAt = -1L;
    lastTargetAt = -1L;
    lastTargetPackage = "";
    lastTargetActivity = "";
  }

  /** 只清空指定任务的记录；别的会话的 task-end 不能清掉当前任务的活动。 */
  public static synchronized void clearIfTask(String identity) {
    if (identity != null && identity.equals(FEED.taskIdentity())) clear();
  }

  /** 助手动作即将派发：随后 {@link ScreenActivityPolicy#DISPATCH_WINDOW_MILLIS} 内的窗口事件算助手一侧。 */
  public static void beginDispatch() {
    dispatchUntil = SystemClock.elapsedRealtime() + ScreenActivityPolicy.DISPATCH_WINDOW_MILLIS;
  }

  /** 无障碍回调线程调用；未在记录任务时零成本返回，不读节点。 */
  public static synchronized void recordAccessibilityEvent(AccessibilityEvent event) {
    if (event == null || !liveTask()) return;
    ScreenActivityPolicy.EventType type = eventType(event.getEventType());
    if (type == null) return;
    String packageName = text(event.getPackageName());
    if (packageName.length() > 255
        || IGNORED.contains(packageName)
        || !("android".equals(packageName)
            || com.deepseekharness.app.util.SelfPackageIdentity.validName(packageName))) return;
    ScreenActivityEvent.Kind kind = ScreenActivityPolicy.kind(type);
    boolean sensitive = SensitiveAppPolicy.sensitive(packageName);
    if (!ScreenActivityPolicy.recordable(kind, sensitive)) return;
    ScreenTarget eventTarget = new ScreenTarget(0, event.getWindowId(), packageName);
    boolean allowed = approved(eventTarget);
    if (kind != ScreenActivityEvent.Kind.TARGET_CHANGED && !allowed) return;
    if (kind == ScreenActivityEvent.Kind.TARGET_CHANGED && !allowed) discardApproval();
    long now = SystemClock.elapsedRealtime();
    if (kind == ScreenActivityEvent.Kind.CONTENT_CHANGED) {
      if (!ScreenActivityPolicy.contentChangeAllowed(now, lastContentAt)) return;
      lastContentAt = now;
    }
    String activity = "";
    if (kind == ScreenActivityEvent.Kind.TARGET_CHANGED) {
      if (!ScreenActivityPolicy.targetChangeAllowed(
          now, lastTargetAt, lastTargetPackage, lastTargetActivity, packageName, activity)) return;
      lastTargetAt = now;
      lastTargetPackage = packageName;
      lastTargetActivity = activity;
    }
    NodeFacts facts = NodeFacts.EMPTY;
    if (type == ScreenActivityPolicy.EventType.VIEW_CLICKED
        || type == ScreenActivityPolicy.EventType.VIEW_SCROLLED) {
      facts = sourceFacts(event, eventTarget);
      if (!approved(eventTarget)) return;
    }
    FEED.record(
        new ScreenActivityEvent(
            0L,
            now,
            kind,
            ScreenActivityPolicy.source(inDispatchWindow(now)),
            packageName,
            activity,
            event.getWindowId(),
            facts.label(),
            facts.nodeClass(),
            facts.centerX(),
            facts.centerY(),
            facts.password(),
            sensitive,
            ""));
  }

  /** 语义节点动作（点按/长按/滚动/输入）被系统接受后记录助手动作。 */
  public static synchronized void assistantNode(
      String action, ScreenTarget target, AccessibilityNodeInfo node) {
    if (target == null || node == null || SensitiveAppPolicy.sensitive(target.packageName)) return;
    if (!approved(target)) return;
    try {
      if (target.windowId != node.getWindowId()
          || !target.packageName.equals(text(node.getPackageName()))) return;
      boolean password = node.isPassword();
      String label =
          ScreenActivityPolicy.label(node.getText(), node.getContentDescription(), password);
      String nodeClass = ScreenActivityPolicy.nodeClass(node.getClassName());
      Rect bounds = new Rect();
      node.getBoundsInScreen(bounds);
      int x = bounds.width() > 0 ? bounds.centerX() : -1;
      int y = bounds.height() > 0 ? bounds.centerY() : -1;
      recordAssistant(action, target, label, nodeClass, x, y, password);
    } catch (RuntimeException unavailable) {
      // 摘要失败不能影响已经接受的动作；不补记任何内容。
    }
  }

  /** 坐标手势被系统接受后记录助手动作；只记起始点，不保存轨迹。 */
  public static synchronized void assistantGesture(
      String gesture, ScreenTarget target, int x1, int y1, int x2, int y2) {
    if (target == null || SensitiveAppPolicy.sensitive(target.packageName)) return;
    if (!approved(target)) return;
    recordAssistant(gesture, target, "", "", x1, y1, false);
  }

  /** 导航键被系统接受后记录助手动作。 */
  public static synchronized void assistantKey(String key, ScreenTarget target) {
    if (target == null || SensitiveAppPolicy.sensitive(target.packageName)) return;
    if (!approved(target)) return;
    recordAssistant("key:" + key, target, "", "", -1, -1, false);
  }

  private static void recordAssistant(
      String action,
      ScreenTarget target,
      String label,
      String nodeClass,
      int centerX,
      int centerY,
      boolean password) {
    if (!approved(target)) return;
    FEED.record(
        new ScreenActivityEvent(
            0L,
            SystemClock.elapsedRealtime(),
            ScreenActivityEvent.Kind.ASSISTANT_ACTION,
            ScreenActivityEvent.Source.ASSISTANT,
            target.packageName,
            "",
            target.windowId,
            label,
            nodeClass,
            centerX,
            centerY,
            password,
            false,
            action));
  }

  /** Retains only the authorization identity, for the final bridge write. */
  public static synchronized java.util.function.BooleanSupplier deliveryCheck(
      ControlTaskAuthority.Binding task) {
    ControlTaskAuthority.Lease lease = recordingLease;
    ScreenTarget target = approvedTarget;
    long revision = targetRevision;
    return () -> {
      synchronized (ScreenActivityRecorder.class) {
        return liveTask()
            && revision == targetRevision
            && lease != null
            && lease.equals(recordingLease)
            && lease.binding().equals(task)
            && (target == null ? approvedTarget == null : approved(target));
      }
    };
  }

  /** 把最近活动附到 {@code /app/ui/*} 的 JSON 回执里；非 JSON 回执原样返回。 */
  public static synchronized String attach(String reply, ControlTaskAuthority.Binding binding) {
    if (reply == null || reply.isEmpty() || binding == null || reply.charAt(0) != '{') return reply;
    try {
      if (liveTask() && approvedTarget != null) approved(approvedTarget);
      JSONObject value = new JSONObject(reply);
      value.put("recentActivity", recentArray(binding.identity()));
      return value.toString();
    } catch (JSONException notJson) {
      return reply;
    }
  }

  /**
   * {@code /app/ui/history} 回执。{@code identity} 为当前任务身份；不是当前任务（未开始、已结束或暂停）时
   * 返回空 events，不返回别的任务内容。
   */
  public static synchronized String history(String since, String limit, String identity) {
    try {
      ScreenActivityQuery.Parsed query = ScreenActivityQuery.parse(since, limit);
      if (liveTask() && approvedTarget != null) approved(approvedTarget);
      ScreenActivityFeed.History history = FEED.history(query.since(), query.limit(), identity);
      JSONArray events = new JSONArray();
      for (ScreenActivityEvent event : history.events()) events.put(eventValue(event, true));
      return new JSONObject()
          .put("ok", true)
          .put("kind", "dsha-screen-activity-v1")
          .put("recording", history.recording())
          .put("events", events)
          .put("frames", new JSONArray())
          .put("framesAvailable", false)
          .put("truncated", history.truncated())
          .put("since", query.since())
          .put("nextSince", history.nextSince())
          .put("limit", query.limit())
          .put("windowSeconds", ScreenActivityFeed.DEFAULT_WINDOW_MILLIS / 1000L)
          .put("capacity", ScreenActivityFeed.DEFAULT_CAPACITY)
          .toString();
    } catch (IllegalArgumentException invalid) {
      return invalidReply(invalid.getMessage());
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static JSONArray recentArray(String identity) throws JSONException {
    JSONArray result = new JSONArray();
    for (ScreenActivityEvent event : FEED.recent(RECENT_LIMIT, identity)) {
      result.put(eventValue(event, false));
    }
    return result;
  }

  /** 紧凑单行事件：时间相对本任务开始，省略空字段；history 额外带序号供 since 续读。 */
  private static JSONObject eventValue(ScreenActivityEvent event, boolean includeSequence)
      throws JSONException {
    JSONObject value = new JSONObject();
    if (includeSequence) value.put("seq", event.sequence());
    value.put("t", secondsSinceStart(event.atMillis()));
    value.put("kind", event.kind().name());
    value.put("target", event.packageName());
    if (!event.activity().isEmpty()) value.put("activity", event.activity());
    if (!event.label().isEmpty()) value.put("label", event.label());
    if (!event.assistantAction().isEmpty()) value.put("action", event.assistantAction());
    if (event.centerX() >= 0 && event.centerY() >= 0) {
      value.put("at", new JSONArray().put(event.centerX()).put(event.centerY()));
    }
    if (event.password()) value.put("password", true);
    if (event.sensitive()) value.put("sensitive", true);
    if (event.source() == ScreenActivityEvent.Source.ASSISTANT
        && event.kind() != ScreenActivityEvent.Kind.ASSISTANT_ACTION) {
      value.put("source", "assistant");
    }
    return value;
  }

  private static double secondsSinceStart(long atMillis) {
    long started = FEED.startedAtMillis();
    if (started <= 0L) return 0.0;
    long delta = Math.max(0L, atMillis - started);
    return Math.round(delta / 100.0) / 10.0;
  }

  private static String invalidReply(String code) {
    String stable = code == null ? "SCREEN_HISTORY_INVALID" : code;
    try {
      return new JSONObject()
          .put("ok", false)
          .put("state", "NOT_SENT")
          .put("code", stable)
          .put("error", stable)
          .put("nextAction", "CHECK_HISTORY_PARAMETERS")
          .put("automaticRetry", false)
          .toString();
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static boolean inDispatchWindow(long now) {
    return dispatchUntil != 0L && now <= dispatchUntil;
  }

  private static ScreenActivityPolicy.EventType eventType(int type) {
    return switch (type) {
      case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
          ScreenActivityPolicy.EventType.WINDOW_STATE_CHANGED;
      case AccessibilityEvent.TYPE_VIEW_CLICKED -> ScreenActivityPolicy.EventType.VIEW_CLICKED;
      case AccessibilityEvent.TYPE_VIEW_SCROLLED -> ScreenActivityPolicy.EventType.VIEW_SCROLLED;
      case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
          ScreenActivityPolicy.EventType.WINDOW_CONTENT_CHANGED;
      default -> null;
    };
  }

  /** 只读事件的源节点；不遍历子树，异常或空源都退回无节点摘要。 */
  private static NodeFacts sourceFacts(AccessibilityEvent event, ScreenTarget expected) {
    AccessibilityNodeInfo node = null;
    try {
      node = event.getSource();
    } catch (RuntimeException unavailable) {
      node = null;
    }
    if (node == null) return NodeFacts.EMPTY;
    try {
      if (node.getWindowId() != expected.windowId
          || !expected.packageName.equals(text(node.getPackageName()))) return NodeFacts.EMPTY;
      boolean password = node.isPassword();
      String label =
          ScreenActivityPolicy.label(node.getText(), node.getContentDescription(), password);
      String nodeClass = ScreenActivityPolicy.nodeClass(node.getClassName());
      Rect bounds = new Rect();
      node.getBoundsInScreen(bounds);
      return new NodeFacts(
          label,
          nodeClass,
          bounds.width() > 0 ? bounds.centerX() : -1,
          bounds.height() > 0 ? bounds.centerY() : -1,
          password);
    } catch (RuntimeException unavailable) {
      return NodeFacts.EMPTY;
    } finally {
      try {
        node.recycle();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private static void refreshIgnored(Context context) {
    Set<String> names = new HashSet<>();
    if (context != null) {
      names.add(context.getPackageName());
      try {
        InputMethodManager methods =
            (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (methods != null) {
          for (InputMethodInfo info : methods.getEnabledInputMethodList()) {
            names.add(info.getPackageName());
          }
        }
      } catch (RuntimeException unavailable) {
        // 拿不到输入法清单时仍排除自身包名；未知输入法窗口只会多一条目标切换，不记内容。
      }
    }
    IGNORED.clear();
    IGNORED.addAll(names);
  }

  private static String text(CharSequence value) {
    return value == null ? "" : value.toString();
  }

  private record NodeFacts(
      String label, String nodeClass, int centerX, int centerY, boolean password) {
    static final NodeFacts EMPTY = new NodeFacts("", "", -1, -1, false);
  }
}
