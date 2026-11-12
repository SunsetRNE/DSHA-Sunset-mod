package com.deepseekharness.app.contract;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** contract 单测的纯 JVM 辅助：假 SharedPreferences 与常用断言/构造助手，不依赖 Robolectric。 */
final class ContractTestSupport {
  private ContractTestSupport() {}

  static ModuleDescriptor descriptor(String id, ModuleDescriptor.Enablement enablement) {
    return new ModuleDescriptor(ModuleId.of(id), 1, "测试模块 " + id, Set.of(), enablement);
  }

  static DiagnosticsSink sink(List<DiagnosticsSink.Event> events) {
    return DiagnosticsSink.forwarding(events::add);
  }

  static ModuleRegistry registry(
      FakeSharedPreferences preferences, SafeModeGate gate, List<DiagnosticsSink.Event> events) {
    DiagnosticsSink sink = sink(events);
    return new ModuleRegistry(new SharedPreferencesLayoutStore(preferences, sink), gate, sink);
  }

  static ContributionView view(List<ContributionView> views, String key) {
    for (ContributionView view : views) if (view.key().equals(key)) return view;
    return null;
  }

  static List<String> keys(List<ContributionView> views) {
    List<String> keys = new ArrayList<>(views.size());
    for (ContributionView view : views) keys.add(view.key());
    return keys;
  }

  static boolean hasEvent(List<DiagnosticsSink.Event> events, String code) {
    for (DiagnosticsSink.Event event : events) if (code.equals(event.code())) return true;
    return false;
  }

  /** 纯内存 SharedPreferences；可故意写入错误类型以覆盖“布局坏值”路径。 */
  static final class FakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();

    void putRaw(String key, Object value) {
      values.put(key, value);
    }

    @Override
    public Map<String, ?> getAll() {
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
      if (value == null) return defValue;
      if (!(value instanceof Integer)) throw new ClassCastException(key);
      return (Integer) value;
    }

    @Override
    public long getLong(String key, long defValue) {
      Object value = values.get(key);
      if (value == null) return defValue;
      if (!(value instanceof Long)) throw new ClassCastException(key);
      return (Long) value;
    }

    @Override
    public float getFloat(String key, float defValue) {
      Object value = values.get(key);
      if (value == null) return defValue;
      if (!(value instanceof Float)) throw new ClassCastException(key);
      return (Float) value;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
      Object value = values.get(key);
      if (value == null) return defValue;
      if (!(value instanceof Boolean)) throw new ClassCastException(key);
      return (Boolean) value;
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
