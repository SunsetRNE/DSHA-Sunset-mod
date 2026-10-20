package com.deepseekharness.app.util;

/** Product identity for the production application. */
public final class AppIdentity {
  private AppIdentity() {}

  public static String applicationId() {
    return "com.dsh.client";
  }

  public static String displayName() {
    return "DSHA";
  }

  public static String publicDirectoryName() {
    return "DSHA";
  }

  public static String defaultUpdateFeed() {
    return "";
  }

  public static boolean isExperimental() {
    return false;
  }
}
