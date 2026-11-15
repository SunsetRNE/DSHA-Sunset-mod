package com.deepseekharness.app.util;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** F07 的封闭只读候选与有限版本指纹；此类不读文件、不执行命令，也不授予 Root。 */
public final class RootModulePolicy {
  private RootModulePolicy() {}

  public static final String MODULE_ROOT = "/data/adb/modules";
  public static final int MAX_ENTRIES = 256;
  public static final int MAX_PROP_BYTES = 16 * 1024;
  public static final int MAX_TOTAL_BYTES = 256 * 1024;
  public static final int MAX_FIELD_CODEPOINTS = 256;
  public static final int MAX_PROBE_BYTES = 4 * 1024;
  public static final long DEADLINE_MS = 10_000L;

  public enum Manager {
    MAGISK,
    KERNELSU,
    APATCH
  }

  public enum Probe {
    LOCAL_VERSION,
    DAEMON_VERSION,
    DAEMON_CODE,
    VERSION
  }

  /** 机器码只描述事实；UNKNOWN 与未获 Root 不能投影成完整空清单。 */
  public enum Code {
    MANAGER_MATCHED,
    ROOT_DISABLED,
    SU_UNAVAILABLE,
    ROOT_NOT_GRANTED,
    ROOT_IDENTITY_UNVERIFIED,
    UNSUPPORTED_MANAGER_VERSION,
    MANAGER_AMBIGUOUS,
    UNKNOWN,
    LIST_COMPLETE,
    LIST_PARTIAL,
    MODULE_ROOT_UNREADABLE,
    MODULE_SOURCE_UNSAFE,
    MODULE_SOURCE_CHANGED,
    MODULE_ID_INVALID,
    MODULE_ID_MISMATCH,
    MODULE_DUPLICATE_ID,
    MODULE_PROP_INVALID,
    MODULE_PROP_DUPLICATE,
    MODULE_PROP_UTF8,
    MODULE_ENTRY_LIMIT,
    MODULE_PROP_LIMIT,
    MODULE_TOTAL_LIMIT,
    MODULE_FIELD_LIMIT,
    MODULE_DEADLINE_EXCEEDED
  }

  public enum RootAccess {
    DISABLED,
    SU_UNAVAILABLE,
    NOT_GRANTED,
    IDENTITY_UNVERIFIED,
    ROOT_VERIFIED
  }

  /** output 必须是独立有界收集的原始 stdout；complete 不代表实际 UID 已核验。 */
  public record ProbeResult(int exitCode, boolean complete, String output) {}

  public record Selection(Code code, Manager manager) {
    public Selection {
      Objects.requireNonNull(code, "code");
      if ((code == Code.MANAGER_MATCHED) != (manager != null))
        throw new IllegalArgumentException("ROOT_MODULE_SELECTION_INVALID");
    }

    public boolean supported() {
      return code == Code.MANAGER_MATCHED;
    }
  }

  public static String candidate(Manager manager) {
    return switch (Objects.requireNonNull(manager, "manager")) {
      case MAGISK -> "/system/bin/magisk";
      case KERNELSU -> "/data/adb/ksud";
      case APATCH -> "/data/adb/apd";
    };
  }

  public static List<Probe> probes(Manager manager) {
    return switch (Objects.requireNonNull(manager, "manager")) {
      case MAGISK -> List.of(Probe.LOCAL_VERSION, Probe.DAEMON_VERSION, Probe.DAEMON_CODE);
      case KERNELSU, APATCH -> List.of(Probe.VERSION);
    };
  }

  public static String flag(Manager manager, Probe probe) {
    if (!probes(manager).contains(Objects.requireNonNull(probe, "probe")))
      throw new IllegalArgumentException("ROOT_MODULE_PROBE_INVALID");
    return switch (probe) {
      case LOCAL_VERSION -> "-c";
      case DAEMON_VERSION -> "-v";
      case DAEMON_CODE, VERSION -> "-V";
    };
  }

  /** argv 全部来自内部枚举，不接收路径、命令或调用方追加参数。 */
  public static List<String> argv(Manager manager, Probe probe) {
    return List.of(candidate(manager), flag(manager, probe));
  }

  public static boolean probeMatches(Manager manager, Map<Probe, ProbeResult> results) {
    List<Probe> required = probes(manager);
    if (results == null
        || results.size() != required.size()
        || !results.keySet().containsAll(required)) return false;
    for (Probe probe : required) {
      ProbeResult result = results.get(probe);
      if (result == null || result.exitCode() != 0 || !result.complete()) return false;
      String output = oneLine(result.output());
      if (output == null || !expected(manager, probe).equals(output)) return false;
    }
    return true;
  }

  public static Selection select(
      RootAccess root, Map<Manager, ? extends Map<Probe, ProbeResult>> results) {
    if (root == null) return new Selection(Code.ROOT_IDENTITY_UNVERIFIED, null);
    Code unavailable =
        switch (root) {
          case DISABLED -> Code.ROOT_DISABLED;
          case SU_UNAVAILABLE -> Code.SU_UNAVAILABLE;
          case NOT_GRANTED -> Code.ROOT_NOT_GRANTED;
          case IDENTITY_UNVERIFIED -> Code.ROOT_IDENTITY_UNVERIFIED;
          case ROOT_VERIFIED -> null;
        };
    if (unavailable != null) return new Selection(unavailable, null);
    if (results == null) return new Selection(Code.UNKNOWN, null);
    Manager selected = null;
    for (Manager manager : Manager.values()) {
      if (!probeMatches(manager, results.get(manager))) continue;
      if (selected != null) return new Selection(Code.MANAGER_AMBIGUOUS, null);
      selected = manager;
    }
    return selected == null
        ? new Selection(Code.UNSUPPORTED_MANAGER_VERSION, null)
        : new Selection(Code.MANAGER_MATCHED, selected);
  }

  private static String expected(Manager manager, Probe probe) {
    if (manager == Manager.MAGISK) {
      return switch (probe) {
        case LOCAL_VERSION -> "af8c2566:MAGISK:R (31000)";
        case DAEMON_VERSION -> "af8c2566:MAGISK:R";
        case DAEMON_CODE -> "31000";
        default -> throw new IllegalArgumentException("ROOT_MODULE_PROBE_INVALID");
      };
    }
    return manager == Manager.KERNELSU ? "ksud 3.3.1-22-g6d31a613 (uapi: 6)" : "apd 11282";
  }

  /** 只有具体失败码能作为 partial 原因；LIST_COMPLETE/LIST_PARTIAL/MANAGER_MATCHED 不是失败。 */
  public static boolean failureReason(Code code) {
    return code != null
        && code != Code.LIST_COMPLETE
        && code != Code.LIST_PARTIAL
        && code != Code.MANAGER_MATCHED;
  }

  /** 允许 0 或 1 个终止 LF/CRLF；不 trim、不接受额外行、控制字或非 ASCII。 */
  private static String oneLine(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > MAX_PROBE_BYTES) return null;
    String value =
        raw.endsWith("\r\n")
            ? raw.substring(0, raw.length() - 2)
            : raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch < 0x20 || ch > 0x7e) return null;
    }
    return value;
  }
}
