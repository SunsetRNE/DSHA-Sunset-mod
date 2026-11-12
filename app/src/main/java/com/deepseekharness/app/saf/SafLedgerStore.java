package com.deepseekharness.app.saf;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 授权簿的 Android 持久化适配器。
 *
 * <p><b>独立命名空间</b>：{@link #NAMESPACE} 只属于 SAF 能力层，不写 {@code ConfigStore}、
 * 不读写历史 {@code saf_grants} 键（旧授权页的命名空间保持原样，迁移在 slice 2 单独做），
 * 也不碰模块布局的 {@code dsha_module_layout}。
 *
 * <p><b>未知条目保留</b>：保存时只删除本版本认识的 key（{@link SafLedgerCodec#knownKey}），
 * 更新版本或第三方写入的未知 key 原样留在 prefs 里，不会被本版本清掉。
 *
 * <p>本类不做业务判定，调用方（slice 2 的授权页/桥）负责串行化自己的写操作；同一进程内建议
 * 只持有一个实例。
 */
public final class SafLedgerStore {
  /** 独立命名空间；与 {@code saf_grants}（旧授权页）和 {@code dsha_module_layout} 分开。 */
  public static final String NAMESPACE = "saf_capability_ledger";

  private final SharedPreferences preferences;

  public SafLedgerStore(SharedPreferences preferences) {
    this.preferences = Objects.requireNonNull(preferences, "preferences");
  }

  public static SafLedgerStore open(Context context) {
    if (context == null) throw new IllegalArgumentException("CONTEXT_REQUIRED");
    return new SafLedgerStore(context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE));
  }

  /** 读取授权簿；prefs 不可读或全为坏值时返回空簿，不抛异常、不误删磁盘内容。 */
  public SafGrantLedger load() {
    return loadDecoded().ledger;
  }

  /** 读取并保留解码警告与未知条目，供迁移/诊断使用（诊断只输出短码，不输出 URI）。 */
  public SafLedgerCodec.Decoded loadDecoded() {
    try {
      return SafLedgerCodec.decode(preferences.getAll());
    } catch (RuntimeException unreadable) {
      return new SafLedgerCodec.Decoded(
          SafGrantLedger.empty(), Map.of(), List.of("STORE_UNREADABLE"));
    }
  }

  /**
   * 原子保存整份授权簿。
   *
   * @return {@code false} 表示读取或提交失败；磁盘上保持旧状态，调用方不得假装已保存。
   */
  public boolean save(SafGrantLedger ledger) {
    Objects.requireNonNull(ledger, "ledger");
    Map<String, Object> desired = SafLedgerCodec.encode(ledger);
    Map<String, ?> current;
    try {
      current = preferences.getAll();
    } catch (RuntimeException unreadable) {
      return false;
    }
    SharedPreferences.Editor editor = preferences.edit();
    try {
      for (String key : current.keySet()) {
        if (SafLedgerCodec.knownKey(key) && !desired.containsKey(key)) editor.remove(key);
      }
      for (Map.Entry<String, Object> entry : desired.entrySet()) {
        Object value = entry.getValue();
        if (same(current.get(entry.getKey()), value)) continue;
        if (value instanceof Set<?> set) {
          Set<String> strings = new LinkedHashSet<>();
          for (Object item : set) {
            if (!(item instanceof String string)) return false;
            strings.add(string);
          }
          editor.putStringSet(entry.getKey(), strings);
        } else if (value instanceof String string) {
          editor.putString(entry.getKey(), string);
        } else {
          // encode 只产出 String/StringSet；其它类型说明内部不变量已坏，拒绝写入半份状态。
          return false;
        }
      }
      return editor.commit();
    } catch (RuntimeException rejected) {
      return false;
    }
  }

  private static boolean same(Object left, Object right) {
    if (left == null || right == null) return left == right;
    if (left instanceof Set<?> leftSet && right instanceof Set<?> rightSet)
      return leftSet.equals(rightSet);
    return left.equals(right);
  }
}
