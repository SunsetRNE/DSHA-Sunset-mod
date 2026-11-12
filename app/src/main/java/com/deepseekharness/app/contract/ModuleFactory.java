package com.deepseekharness.app.contract;

/** 编译期已存在的模块类型工厂；不得从字节流装载代码。 */
@FunctionalInterface
public interface ModuleFactory {
  ModuleInstance create();
}
