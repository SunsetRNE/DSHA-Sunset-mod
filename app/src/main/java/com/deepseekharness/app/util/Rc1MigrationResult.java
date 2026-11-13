package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 迁移输出只接受一条完整结构化回执；错误文字中的 status 不能授予启动许可。
 *
 * <p>v3 回执把“本次核验/历史完成/写入结果”分开：只有本次内容副本读回（current +
 * verified）才能授予迁移写入；历史完成只允许普通无迁移启动；未知枚举、缺字段和旧
 * v2 回执一律不默认成功。
 */
public final class Rc1MigrationResult {
  private Rc1MigrationResult() {}

  private static final String PREFIX = "DSHA_RC1_MIGRATION=";

  public static Map<String, Object> parse(String output) throws IOException {
    Map<String, Object> result = null;
    if (output != null)
      for (String line : output.split("\\r?\\n"))
        if (line.startsWith(PREFIX)) {
          if (result != null) throw new IOException("RC1_MIGRATION_DUPLICATE_RESULT");
          result =
              BackupJson.read(
                  line.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8), 65536);
        }
    if (result == null) throw new IOException("RC1_MIGRATION_RESULT_MISSING");
    return result;
  }

  /** 回执契约版本；未知、缺失或非整数一律不视为当前契约。 */
  public static boolean versionThree(Map<String, Object> value) {
    if (value == null) return false;
    Object version = value.get("resultVersion");
    return Integer.valueOf(3).equals(version) || Long.valueOf(3).equals(version);
  }

  /** 回执必须绑定当前请求的 startupId；历史证据不能跨启动复用为本次结果。 */
  public static boolean boundTo(Map<String, Object> value, String startupId) {
    return versionThree(value)
        && startupId != null
        && !startupId.isEmpty()
        && startupId.equals(text(value, "startupId"));
  }

  public static boolean isHistorical(Map<String, Object> value) {
    return versionThree(value)
        && "historical-complete".equals(text(value, "status"))
        && "historical".equals(text(value, "verificationScope"))
        && "verified".equals(text(value, "contentCopyStatus"));
  }

  /** 普通启动门禁：历史完成只授予无迁移写入的启动，且旧设置导入必须已完成。 */
  public static boolean allowsStart(Map<String, Object> value) {
    if (!versionThree(value)) return false;
    String status = text(value, "status");
    if ("skipped".equals(status) && "DSH_MISSING".equals(text(value, "reason"))) return true;
    if ("prepared".equals(status)) return currentCopyVerified(value);
    return isHistorical(value) && historicalSettingsComplete(value);
  }

  /** 常规回执保持静默；历史完成没有本次核验、或设置/写入未知时必须可见。 */
  public static boolean isRoutineReceipt(Map<String, Object> value) {
    if (!versionThree(value)) return false;
    String status = text(value, "status");
    if ("skipped".equals(status) && "DSH_MISSING".equals(text(value, "reason"))) return true;
    String settings = text(value, "settingsImportStatus");
    boolean settingsOk =
        "verified-current".equals(settings)
            || "verified-historical".equals(settings)
            || "not-required".equals(settings);
    if ("prepared".equals(status)) return currentCopyVerified(value);
    // committed 允许 verified-historical：设置由更早启动完成导入、本次只重读内容副本；
    // 字段本身仍明确标注历史，不能当成本次写入。其余历史结果必须是历史/not-required。
    if ("committed".equals(status)) return currentCopyVerified(value) && settingsOk;
    return isHistorical(value) && historicalSettingsComplete(value);
  }

  private static boolean historicalSettingsComplete(Map<String, Object> value) {
    String settings = text(value, "settingsImportStatus");
    return "verified-historical".equals(settings) || "not-required".equals(settings);
  }

  public static boolean needsAttention(Map<String, Object> value) {
    return !isRoutineReceipt(value);
  }

  private static boolean currentCopyVerified(Map<String, Object> value) {
    return "current".equals(text(value, "verificationScope"))
        && "verified".equals(text(value, "contentCopyStatus"));
  }

  private static String text(Map<String, Object> value, String key) {
    if (value == null) return "";
    Object found = value.get(key);
    return found instanceof String ? (String) found : "";
  }
}
