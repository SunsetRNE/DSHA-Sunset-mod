package com.deepseekharness.app.runtime;

/** 运行方式端口：runtime 不直接触碰配置存储，由组合层供给实现。 */
public interface RuntimeModePolicy {
  /** 用户当前选择的运行方式（root / proroot / proot）。 */
  String containerRuntimeMode();

  /** root 授权失效时的持久化回落：清除授权并回到 proot。 */
  void resetToProot();
}
