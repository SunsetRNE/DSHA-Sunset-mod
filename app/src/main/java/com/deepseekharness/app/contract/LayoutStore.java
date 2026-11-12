package com.deepseekharness.app.contract;

/** 布局持久化接口；具体命名空间由后端实现决定（A1 为 dsha_module_layout）。 */
public interface LayoutStore {
  LayoutSnapshot load();

  void save(LayoutSnapshot snapshot);
}
