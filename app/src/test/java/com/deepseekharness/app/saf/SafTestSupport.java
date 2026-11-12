package com.deepseekharness.app.saf;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SAF 能力层单测的纯 JVM 辅助：内存 SharedPreferences、授权记录与句柄 token。 */
final class SafTestSupport {
  static final String DOWNLOADS =
      "content://com.android.externalstorage.documents/tree/primary%3ADownload";
  static final String DOCUMENTS =
      "content://com.android.externalstorage.documents/tree/primary%3ADocuments";
  static final String OWN_PROVIDER = "content://com.deepseekharness.app.documents/tree/root";
  static final String OWN_AUTHORITY = "com.deepseekharness.app.documents";
  static final String ROOTFS = "/data/user/0/com.deepseekharness.app/files/linux/ubuntu";

  private SafTestSupport() {}

  static String treeUri(int index) {
    return "content://test.documents/tree/folder" + index;
  }

  static SafGrantRecord active(String uri, int flags, long epoch) {
    return new SafGrantRecord(
        SafGrantRecord.idOf(uri),
        uri,
        "测试应用",
        "测试目录",
        (flags & SafGrantRecord.READ) != 0,
        (flags & SafGrantRecord.WRITE) != 0,
        true,
        flags,
        SafGrantRecord.State.ACTIVE,
        epoch,
        1_000L,
        0,
        SafGrantRecord.READ);
  }

  static SafGrantRecord pending(String uri, long epoch) {
    return new SafGrantRecord(
        SafGrantRecord.idOf(uri),
        uri,
        "测试应用",
        "测试目录",
        false,
        false,
        true,
        0,
        SafGrantRecord.State.PENDING,
        epoch,
        1_000L,
        SafGrantRecord.READ,
        SafGrantRecord.READ | SafGrantRecord.WRITE);
  }

  static SafGrantLedger ledger(SafGrantRecord... records) {
    long maxEpoch = 0;
    for (SafGrantRecord record : records) maxEpoch = Math.max(maxEpoch, record.epoch());
    return SafGrantLedger.of(List.of(records), 1, maxEpoch + 1);
  }

  static SafGrantLedger single(String uri, int flags) {
    return ledger(active(uri, flags, 1));
  }

  /** 单调递增的测试 token 生成器；避免随机值让断言不可复盘。 */
  static SafCapabilityHandles fixedHandles() {
    int[] counter = {0};
    return new SafCapabilityHandles(() -> "token-" + (++counter[0]));
  }

  /** 纯内存 SharedPreferences；可注入提交失败与 getAll 失败，覆盖持久化降级路径。 */
  static final class FakePreferences implements SharedPreferences {
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();
    boolean commitResult = true;
    boolean unreadable;

    void putRaw(String key, Object value) {
      values.put(key, value);
    }

    void clearRaw() {
      values.clear();
    }

    @Override
    public Map<String, ?> getAll() {
      if (unreadable) throw new IllegalStateException("prefs unreadable");
      return new LinkedHashMap<>(values);
    }

    @Override
    public String getString(String key, String defValue) {
      Object value = values.get(key);
      if (value == null) return defValue;
      if (!(value instanceof String)) throw new ClassCastException(key);
      return (String) value;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
      Object value = values.get(key);
      if (value == null) return defValues;
      if (!(value instanceof Set<?>)) throw new ClassCastException(key);
      Set<String> copy = new LinkedHashSet<>();
      for (Object item : (Set<?>) value) {
        if (!(item instanceof String)) throw new ClassCastException(key);
        copy.add((String) item);
      }
      return copy;
    }

    @Override
    public int getInt(String key, int defValue) {
      Object value = values.get(key);
      return value instanceof Integer integer ? integer : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
      Object value = values.get(key);
      return value instanceof Long number ? number : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
      Object value = values.get(key);
      return value instanceof Float number ? number : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
      Object value = values.get(key);
      return value instanceof Boolean bool ? bool : defValue;
    }

    @Override
    public boolean contains(String key) {
      return values.containsKey(key);
    }

    @Override
    public Editor edit() {
      return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {
      listeners.add(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {
      listeners.remove(listener);
    }

    private final class FakeEditor implements Editor {
      private final Map<String, Object> updates = new HashMap<>();
      private final Set<String> removals = new HashSet<>();
      private boolean clear;

      @Override
      public Editor putString(String key, String value) {
        updates.put(key, value);
        return this;
      }

      @Override
      public Editor putStringSet(String key, Set<String> values) {
        updates.put(key, values == null ? null : new LinkedHashSet<>(values));
        return this;
      }

      @Override
      public Editor putInt(String key, int value) {
        updates.put(key, value);
        return this;
      }

      @Override
      public Editor putLong(String key, long value) {
        updates.put(key, value);
        return this;
      }

      @Override
      public Editor putFloat(String key, float value) {
        updates.put(key, value);
        return this;
      }

      @Override
      public Editor putBoolean(String key, boolean value) {
        updates.put(key, value);
        return this;
      }

      @Override
      public Editor remove(String key) {
        removals.add(key);
        return this;
      }

      @Override
      public Editor clear() {
        clear = true;
        return this;
      }

      @Override
      public boolean commit() {
        if (!commitResult) return false;
        apply();
        return true;
      }

      @Override
      public void apply() {
        if (clear) values.clear();
        for (String key : removals) values.remove(key);
        values.putAll(updates);
      }
    }
  }
}
