package com.deepseekharness.app.contract;

/** 契约层的稳定机器码；界面/日志只做展示，不参与安全判定。 */
public final class ContractCodes {
  private ContractCodes() {}

  public static final String OK = "OK";
  public static final String MODULE_INVALID = "MODULE_INVALID";
  public static final String MODULE_DUPLICATE_ID = "MODULE_DUPLICATE_ID";
  public static final String MODULE_UNKNOWN = "MODULE_UNKNOWN";
  public static final String MODULE_ALWAYS_ON = "MODULE_ALWAYS_ON";
  public static final String MODULE_INSTALL_FAILED = "MODULE_INSTALL_FAILED";
  public static final String MODULE_INVALID_CONTRIBUTION = "MODULE_INVALID_CONTRIBUTION";
  public static final String MODULE_CLOSE_FAILED = "MODULE_CLOSE_FAILED";
  public static final String MODULE_CALLBACK_FAILED = "MODULE_CALLBACK_FAILED";
  public static final String MODULE_DISABLED = "MODULE_DISABLED";
  public static final String SAFE_MODE_RESTRICTED = "SAFE_MODE_RESTRICTED";
  public static final String SAFE_MODE_ENTERED = "SAFE_MODE_ENTERED";
  public static final String UNKNOWN_CONTRIBUTION = "UNKNOWN_CONTRIBUTION";
  public static final String DUPLICATE_CONTRIBUTION = "DUPLICATE_CONTRIBUTION";
  public static final String INVALID_LAYOUT = "INVALID_LAYOUT";
  public static final String LAYOUT_LOAD_FAILED = "LAYOUT_LOAD_FAILED";
  public static final String LAYOUT_SAVE_FAILED = "LAYOUT_SAVE_FAILED";
  public static final String LAYOUT_CORRUPT = "LAYOUT_CORRUPT";
  public static final String STALE_GENERATION = "STALE_GENERATION";
  public static final String INVALID_CALLBACK = "INVALID_CALLBACK";
  public static final String ROUTE_MISSING = "ROUTE_MISSING";
  public static final String INVALID_ROUTE = "INVALID_ROUTE";
  public static final String DUPLICATE_ROUTE = "DUPLICATE_ROUTE";
  public static final String INVALID_DIAGNOSTIC = "INVALID_DIAGNOSTIC";
}
