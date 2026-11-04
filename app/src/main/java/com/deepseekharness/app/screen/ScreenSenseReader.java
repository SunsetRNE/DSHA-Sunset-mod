package com.deepseekharness.app.screen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.util.AccessibilityDumpBounds;
import com.deepseekharness.app.util.ScreenSenseCodec;
import com.deepseekharness.app.util.ScreenSenseMetadata;
import com.deepseekharness.app.util.ScreenSenseState;
import com.deepseekharness.app.util.ScreenSenseStateStore;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 紧凑识屏读取路径。当前只提供只读预览；紧凑 id 不是动作凭据，动作仍必须走旧 tree/node 租约。
 */
public final class ScreenSenseReader {
  private static final int DEFAULT_MAX = 200;
  private static final int MAX_LABEL = 160;

  public JSONObject read(
      ScreenRequestContext context,
      ScreenSenseStateStore states,
      boolean includeBounds,
      int requestedMax,
      boolean full)
      throws Exception {
    int max = requestedMax <= 0 ? DEFAULT_MAX : Math.min(requestedMax, DEFAULT_MAX);
    if (requestedMax < 0) return ScreenReply.refused("SCREEN_SENSE_MAX_INVALID");
    try (var resolved =
        ScreenTargetResolver.resolve(context.service(), context.target().displayId)) {
      context.validateRoot(context.target().displayId, resolved.root);
      String scopeId =
          states.scopeId(context.ownerIdentity(), context.serviceEpoch(), resolved.target);
      List<ScreenSenseState.Element> elements = new ArrayList<>();
      long startedAt = android.os.SystemClock.elapsedRealtime();
      boolean[] maxReached = new boolean[1];
      ScreenReadBudget budget =
          new ScreenReadBudget(android.os.SystemClock::elapsedRealtime, context::current);
      collect(resolved.root, elements, budget, 0, max, includeBounds, maxReached);
      context.validateCurrentTarget();
      long capturedAt = android.os.SystemClock.elapsedRealtime();
      android.util.DisplayMetrics metrics = context.service().getResources().getDisplayMetrics();
      long sid = states.nextSid();
      ScreenSenseState state =
          new ScreenSenseState(
              sid,
              scopeId,
              resolved.target,
              metrics.widthPixels,
              metrics.heightPixels,
              false,
              -1,
              "tree",
              budget.visits(),
              budget.exhausted() || maxReached[0],
              budget.timedOut()
                  ? "time"
                  : budget.exhausted() ? "nodes" : maxReached[0] ? "max" : "",
              elements);
      states.record(context.ownerIdentity(), context.serviceEpoch(), resolved.target, state);
      ScreenSenseMetadata meta =
          ScreenSenseMetadata.of(
              state, startedAt, capturedAt, android.os.SystemClock.elapsedRealtime());
      // Compact ids are preview-only until a current-tree resolver exists. Never emit a
      // delta that could make a caller associate a new node with an old position.
      JSONObject result =
          ScreenReply.completed()
              .put("kind", "dsha-screen-sense-v1")
              .put("format", "v4-preview-2")
              .put("mode", "full")
              .put("fullRequested", full)
              .put("deltaAvailable", false)
              .put("readOnlyPreview", true)
              .put("actionCredential", "LEGACY_TREE_NODE_ONLY")
              .put("sid", sid)
              .put("treeId", scopeId)
              .put("visited", state.visited)
              .put("elements", state.elements.size())
              .put("truncated", state.truncated)
              .put("source", meta.source)
              .put("quality", meta.quality)
              .put("partial", meta.partial)
              .put("readMs", meta.readMs)
              .put("ageMs", meta.ageMs)
              .put("encodedState", ScreenSenseCodec.encode(state))
              .put("target", ScreenSnapshotReader.target(resolved.target));
      if (state.truncated) result.put("truncationReason", state.truncationReason);
      return result;
    } catch (Exception failure) {
      states.clearOwner(context.ownerIdentity());
      throw failure;
    }
  }

  private void collect(
      AccessibilityNodeInfo node,
      List<ScreenSenseState.Element> elements,
      ScreenReadBudget budget,
      int depth,
      int max,
      boolean includeBounds,
      boolean[] maxReached)
      throws Exception {
    if (node == null) return;
    if (elements.size() >= max) {
      maxReached[0] = true;
      return;
    }
    if (!budget.enter(depth, elements.size())) return;
    boolean password = node.isPassword();
    String label = password ? "[PASSWORD]" : ScreenSnapshotReader.value(node.getText());
    String description = password ? "" : ScreenSnapshotReader.value(node.getContentDescription());
    if (label.isEmpty()) label = description;
    ScreenNodeActions.ActionSnapshot actionSnapshot = ScreenNodeActions.snapshot(node);
    boolean actionable =
        node.isClickable() || node.isEditable() || node.isScrollable() || actionSnapshot.hasAny();
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (AccessibilityDumpBounds.reportable(
        !label.isEmpty(), actionable, bounds.left, bounds.top, bounds.right, bounds.bottom)) {
      String actions = actionCodes(actionSnapshot);
      elements.add(
          new ScreenSenseState.Element(
              "k" + base62(elements.size()),
              role(node, actionable),
              clip(label.isEmpty() ? "?" : label),
              flags(node, password),
              actions,
              includeBounds && bounds.width() > 0 && bounds.height() > 0,
              bounds.left,
              bounds.top,
              bounds.right,
              bounds.bottom));
    }
    if (password) return;
    int childCount = node.getChildCount();
    for (int i = 0; i < childCount; i++) {
      if (elements.size() >= max) {
        maxReached[0] = true;
        return;
      }
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      try {
        collect(child, elements, budget, depth + 1, max, includeBounds, maxReached);
      } finally {
        child.recycle();
      }
    }
  }

  private static String role(AccessibilityNodeInfo node, boolean actionable) {
    if (node.isEditable()) return "I";
    if (node.isScrollable()) return "L";
    if (node.isCheckable()) return "C";
    if (node.isClickable() || actionable) return "B";
    return "T";
  }

  private static String flags(AccessibilityNodeInfo node, boolean password) {
    StringBuilder result = new StringBuilder();
    if (!node.isEnabled()) result.append('!');
    if (node.isChecked()) result.append('*');
    if (node.isFocused()) result.append('%');
    if (password) result.append('~');
    return result.toString();
  }

  private static String actionCodes(ScreenNodeActions.ActionSnapshot actions) {
    StringBuilder result = new StringBuilder();
    if (actions.supports(AccessibilityNodeInfo.ACTION_CLICK)) result.append('c');
    if (actions.supports(AccessibilityNodeInfo.ACTION_LONG_CLICK)) result.append('l');
    if (actions.supports(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) result.append('f');
    if (actions.supports(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) result.append('b');
    if (actions.supports(AccessibilityNodeInfo.ACTION_FOCUS)) result.append('o');
    if (actions.supports(AccessibilityNodeInfo.ACTION_SET_TEXT)) result.append('t');
    return result.toString();
  }

  private static String clip(String value) {
    return value.length() <= MAX_LABEL ? value : value.substring(0, MAX_LABEL) + "…";
  }

  private static String base62(int value) {
    final char[] alphabet =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    StringBuilder result = new StringBuilder();
    do {
      result.append(alphabet[value % alphabet.length]);
      value /= alphabet.length;
    } while (value > 0);
    while (result.length() < 3) result.append('0');
    return result.reverse().toString();
  }
}
