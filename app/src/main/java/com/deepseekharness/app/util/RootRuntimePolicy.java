package com.deepseekharness.app.util;

/** Root 容器运行模式的纯逻辑策略；授权失败不得静默提升或伪装成 root 成功。 */
public final class RootRuntimePolicy {
  public static final String MODE_PROOT = "proot";
  public static final String MODE_PROROOT = "proroot";
  public static final String MODE_ROOT = "root";

  private RootRuntimePolicy() {}

  /**
   * Direct chroot is fail-closed until mount/user namespaces, read-only system binds and cleanup
   * proofs are implemented and verified on a rooted device. Root must never be inferred from su
   * authorization alone: a guest process would otherwise receive device-wide uid 0.
   */
  public static boolean directContainerAllowed() {
    return false;
  }

  public static String directContainerUnavailableReason() {
    return "ROOT_CONTAINER_ISOLATION_UNAVAILABLE";
  }

  public static boolean validMode(String mode) {
    return MODE_PROOT.equals(mode) || MODE_PROROOT.equals(mode) || MODE_ROOT.equals(mode);
  }

  public static String normalize(String mode) {
    return validMode(mode) ? mode : MODE_PROOT;
  }

  public static boolean authorizedIdentity(String output) {
    if (output == null) return false;
    for (String line : output.split("\\R")) if ("uid=0".equals(line.trim())) return true;
    return false;
  }

  public static String fallback(String requested, boolean authorized) {
    if (MODE_ROOT.equals(requested) && (!authorized || !directContainerAllowed()))
      return MODE_PROOT;
    return normalize(requested);
  }

  public static String unavailableReason(boolean present, boolean authorized) {
    if (!present) return "ROOT_NOT_FOUND";
    if (!authorized) return "ROOT_PERMISSION_REQUIRED";
    return "";
  }
}
