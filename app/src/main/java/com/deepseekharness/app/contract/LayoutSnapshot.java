package com.deepseekharness.app.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 宿主布局快照：顺序 + 隐藏集合。未知但仍合法的键必须原样保留，避免降级后丢布局。
 *
 * <p>键格式固定为模块全局键 {@code module-id#contribution-id}；坏键由存储后端回默认布局。
 */
public record LayoutSnapshot(List<String> order, Set<String> hidden) {
  private static final Pattern KEY =
      Pattern.compile("[a-z][a-z0-9._-]{0,63}#[a-z][a-z0-9._-]{0,63}");

  public LayoutSnapshot {
    Objects.requireNonNull(order, "order");
    Objects.requireNonNull(hidden, "hidden");
    List<String> orderCopy = new ArrayList<>(order.size());
    Set<String> seen = new HashSet<>();
    for (String key : order) {
      validateKey(key);
      if (!seen.add(key)) throw new IllegalArgumentException("LAYOUT_ORDER_DUPLICATE");
      orderCopy.add(key);
    }
    Set<String> hiddenCopy = new LinkedHashSet<>();
    for (String key : hidden) hiddenCopy.add(validateKey(key));
    order = List.copyOf(orderCopy);
    hidden = Set.copyOf(hiddenCopy);
  }

  public static LayoutSnapshot empty() {
    return new LayoutSnapshot(List.of(), Set.of());
  }

  public static boolean validKey(String key) {
    return key != null && KEY.matcher(key).matches();
  }

  static String validateKey(String key) {
    if (!validKey(key)) throw new IllegalArgumentException("LAYOUT_KEY_INVALID");
    return key;
  }

  public boolean isHidden(String key) {
    return hidden.contains(key);
  }

  public LayoutSnapshot withHidden(String key, boolean hidden) {
    validateKey(key);
    Set<String> next = new LinkedHashSet<>(this.hidden);
    if (hidden) next.add(key);
    else next.remove(key);
    return new LayoutSnapshot(order, next);
  }

  /** 用给定顺序替换，但把原有键追加在末尾，保留未知/暂不可达的布局记录。 */
  public LayoutSnapshot mergedOrder(List<String> requested) {
    Objects.requireNonNull(requested, "requested");
    List<String> merged = new ArrayList<>(requested.size() + order.size());
    Set<String> seen = new HashSet<>();
    for (String key : requested) {
      validateKey(key);
      if (!seen.add(key)) throw new IllegalArgumentException("LAYOUT_ORDER_DUPLICATE");
      merged.add(key);
    }
    for (String key : order) if (seen.add(key)) merged.add(key);
    return new LayoutSnapshot(merged, hidden);
  }
}
