package com.deepseekharness.app.contract;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A1 布局后端：独立 SharedPreferences 命名空间，不读写 ConfigStore，也不改历史键名。
 *
 * <p>存储格式带 {@code v1:} 版本前缀；未知但合法的键原样保留，坏值整体回默认布局并诊断一次。
 * Android prefs 持久化集中在本后端类；契约核心与测试均不依赖 Android 实现。
 */
public final class SharedPreferencesLayoutStore implements LayoutStore {
  public static final String NAMESPACE = "dsha_module_layout";
  public static final String PREF_ORDER = "order";
  public static final String PREF_HIDDEN = "hidden";

  private static final String ORDER_PREFIX = "v1:";

  private final SharedPreferences preferences;
  private final DiagnosticsSink diagnostics;

  public SharedPreferencesLayoutStore(SharedPreferences preferences) {
    this(preferences, DiagnosticsSink.discarding());
  }

  public SharedPreferencesLayoutStore(SharedPreferences preferences, DiagnosticsSink diagnostics) {
    this.preferences = Objects.requireNonNull(preferences, "preferences");
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public static SharedPreferencesLayoutStore open(Context context) {
    if (context == null) throw new IllegalArgumentException("CONTEXT_REQUIRED");
    return new SharedPreferencesLayoutStore(
        context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE));
  }

  @Override
  public LayoutSnapshot load() {
    try {
      List<String> order = decodeOrder(preferences.getString(PREF_ORDER, ""));
      Set<String> hidden = decodeHidden(preferences.getStringSet(PREF_HIDDEN, Set.of()));
      return new LayoutSnapshot(order, hidden);
    } catch (RuntimeException error) {
      diagnostics.report(
          ContractCodes.LAYOUT_CORRUPT,
          "layout preferences unreadable: " + error.getClass().getSimpleName());
      return LayoutSnapshot.empty();
    }
  }

  @Override
  public void save(LayoutSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    preferences
        .edit()
        .putString(PREF_ORDER, encodeOrder(snapshot.order()))
        .putStringSet(PREF_HIDDEN, new LinkedHashSet<>(snapshot.hidden()))
        .apply();
  }

  private static List<String> decodeOrder(String encoded) {
    if (encoded == null || encoded.isEmpty()) return List.of();
    if (!encoded.startsWith(ORDER_PREFIX))
      throw new IllegalArgumentException("LAYOUT_ORDER_INVALID");
    String body = encoded.substring(ORDER_PREFIX.length());
    if (body.isEmpty()) return List.of();
    String[] parts = body.split("\n", -1);
    List<String> order = new ArrayList<>(parts.length);
    for (String part : parts) order.add(LayoutSnapshot.validateKey(part));
    return order;
  }

  private static Set<String> decodeHidden(Set<String> stored) {
    if (stored == null || stored.isEmpty()) return Set.of();
    Set<String> hidden = new LinkedHashSet<>();
    for (String key : stored) hidden.add(LayoutSnapshot.validateKey(key));
    return hidden;
  }

  private static String encodeOrder(List<String> order) {
    StringBuilder encoded = new StringBuilder(ORDER_PREFIX);
    for (int index = 0; index < order.size(); index++) {
      if (index > 0) encoded.append('\n');
      encoded.append(order.get(index));
    }
    return encoded.toString();
  }
}
