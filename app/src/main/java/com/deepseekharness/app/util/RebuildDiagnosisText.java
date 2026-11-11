package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境重建失败诊断报告：把阶段、失败码、errno、空间采样与包摘要拼成一段可直接复制粘贴的纯文本。
 *
 * <p>只做纯推导：不读文件、不访问 Android、不推测未采集的值。未知项写 {@code -}，单行有上限，整份报告
 * 不超过 {@link #LIMIT}，保证剪贴板与分享文件里的内容始终有界。结论由 {@link #classify} 按失败码与
 * 数字证据给出，不把自由文本升级为结论。
 */
public final class RebuildDiagnosisText {
  private RebuildDiagnosisText() {}

  /** 报告正文上限；与前端分享文件的接受上限同一量级。 */
  public static final int LIMIT = 8000;

  /** 单个列表段最多保留的条目数，超出的只保留最近的。 */
  public static final int MAX_ITEMS = 8;

  /** 单行上限：异常原文可能很长，报告需要能整段复制。 */
  public static final int MAX_LINE = 400;

  /** 失败归类：决定“建议”一行怎么读，避免用户把系统拒绝当成包损坏。 */
  public enum Cause {
    NO_SPACE,
    PERMISSION,
    PACKAGE,
    RUNTIME,
    ENVIRONMENT,
    UNKNOWN
  }

  /** 采集结果。字段缺省用 null/空串/-1；{@link #build} 负责渲染成 {@code -}。 */
  public static final class Facts {
    public String generated = "",
        versionName = "",
        versionCode = "",
        device = "",
        api = "",
        abi = "";
    public String runtime = "", layout = "", status = "", stage = "", substage = "";
    public String code = "", errno = "", path = "", detail = "", rootfs = "", probe = "";

    /** {@code spaceAt} 是 required/available 取自哪一次空间采样的阶段名，防止把预检数字读成解压需求。 */
    public String spaceAt = "";

    public long bytesRead = -1, bytesTotal = -1, required = -1, available = -1;
    public List<String> assets = new ArrayList<>();
    public List<String> spaces = new ArrayList<>();
    public List<String> stages = new ArrayList<>();
  }

  /**
   * 归类顺序：先看 errno 与空间数字（宿主/适配），再看权限类错误码，再看包与运行时。
   * 只有“空间要求已知且大于可用”时才按数字判空间不足，避免预检阶段的 -1 误判。
   */
  public static Cause classify(Facts facts) {
    if (facts == null) return Cause.UNKNOWN;
    String code = up(facts.code), errno = up(facts.errno);
    if (errno.contains("ENOSPC") || errno.contains("EDQUOT") || code.contains("NO_SPACE"))
      return Cause.NO_SPACE;
    if (facts.required > 0 && facts.available >= 0 && facts.required > facts.available)
      return Cause.NO_SPACE;
    if (errno.equals("EACCES")
        || errno.equals("EPERM")
        || errno.equals("EROFS")
        || code.contains("PERMISSION")
        || code.contains("OWNERSHIP")
        || code.contains("SELINUX")
        || code.contains("UNREADABLE")) return Cause.PERMISSION;
    if (code.contains("CRC")
        || code.contains("ZIP")
        || code.contains("GZIP")
        || code.contains("DIGEST")
        || code.contains("MISMATCH")
        || code.contains("COLD_BUNDLE")
        || (facts.bytesTotal > 0 && facts.bytesRead >= 0 && facts.bytesRead < facts.bytesTotal))
      return Cause.PACKAGE;
    if (code.contains("RUNTIME")
        || code.contains("ELF")
        || code.contains("LOADER")
        || code.contains("PROOT")
        || code.contains("HEALTH")
        || code.contains("TRIAL")) return Cause.RUNTIME;
    if (code.contains("ENVIRONMENT_INSTALL")) return Cause.ENVIRONMENT;
    return Cause.UNKNOWN;
  }

  /** 结论标题：一句话说明卡在哪一类问题，不重复报告正文。 */
  public static String causeLabel(Cause cause) {
    return switch (cause == null ? Cause.UNKNOWN : cause) {
      case NO_SPACE -> "磁盘空间不足（解压中途写满）";
      case PERMISSION -> "系统拒绝访问（权限 / SELinux / 目录身份）";
      case PACKAGE -> "内置包读取不完整或校验失败";
      case RUNTIME -> "解压后运行时未通过核验（ELF / loader / 健康回执）";
      case ENVIRONMENT -> "解压链路在离线安装阶段中断";
      case UNKNOWN -> "证据不足以归类";
    };
  }

  /** 处理建议：可执行、短句，不复述数字（数字已在正文各行）。 */
  public static String advice(Cause cause) {
    return switch (cause == null ? Cause.UNKNOWN : cause) {
      case NO_SPACE -> "清理 Download、旧日志或可再生缓存后重试；先「导出可读副本」保存数据。";
      case PERMISSION -> "保持 DSHA 前台，确认私有目录未被清理工具改动，授予存储权限后重试。";
      case PACKAGE -> "用同签名 APK 覆盖安装一次再重试；持续失败时先导出可读副本。";
      case RUNTIME -> "查看最近阶段与 runtimeId，确认 APK 受管运行时完整后重试或更新 APK。";
      case ENVIRONMENT -> "按「最后子阶段」定位：Python/pnpm、离线 curl·git·证书最容易失败。";
      case UNKNOWN -> "把整份报告发给支持；不要重复点击重建。";
    };
  }

  /** 生成报告正文。所有输入都按单行渲染，超出上限时优先截断列表段。 */
  public static String build(Facts facts) {
    Facts f = facts == null ? new Facts() : facts;
    Cause cause = classify(f);
    StringBuilder out = new StringBuilder(2048);
    line(out, "DSHA 环境重建诊断 / environment rebuild diagnosis");
    line(out, "生成时间: " + value(f.generated));
    line(out, "版本: " + value(f.versionName) + " (" + value(f.versionCode) + ")");
    line(out, "设备: " + value(f.device) + " / " + value(f.api) + " / " + value(f.abi));
    line(out, "运行时: runtimeId=" + value(f.runtime) + " layout=" + value(f.layout));
    line(
        out,
        "任务: status="
            + value(f.status)
            + " stage="
            + value(f.stage)
            + " substage="
            + value(f.substage));
    line(out, "失败码: code=" + value(f.code) + " errno=" + value(f.errno));
    line(out, "路径: " + value(f.path));
    line(out, "数据量: read=" + bytes(f.bytesRead) + " total=" + bytes(f.bytesTotal));
    line(
        out,
        "空间: required="
            + bytes(f.required)
            + " available="
            + bytes(f.available)
            + (isEmpty(f.spaceAt) ? "" : " 采样=" + value(f.spaceAt)));
    line(out, "环境目录: " + value(f.rootfs));
    line(out, "内置包: " + value(join(f.assets)));
    line(out, "设备探针: " + value(f.probe));
    section(out, "空间采样", f.spaces);
    section(out, "最近阶段", f.stages);
    if (!isEmpty(f.detail)) line(out, "原始错误: " + clip(f.detail, MAX_LINE));
    line(out, "结论: " + causeLabel(cause));
    line(out, "建议: " + advice(cause));
    String report = out.toString();
    if (report.length() <= LIMIT) return report;
    return report.substring(0, LIMIT - 12) + "\n…（已截断）\n";
  }

  /** 解析 {@code "stage=EXTRACTION_STARTED availableBytes=123 requiredBytes=456"} 形式的事实行。 */
  public static Map<String, String> keyValues(String line) {
    Map<String, String> values = new LinkedHashMap<>();
    if (line == null) return values;
    for (String token : line.trim().split("\\s+")) {
      int split = token.indexOf('=');
      if (split <= 0 || split == token.length() - 1) continue;
      String key = token.substring(0, split);
      if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,40}")) continue;
      values.put(key, token.substring(split + 1));
    }
    return values;
  }

  /** 把系统的 strerror 文本还原成 errno 名；认不出就返回空串，不猜。 */
  public static String errnoOf(String message) {
    if (message == null || message.isEmpty()) return "";
    String text = message;
    if (text.contains("ENOSPC") || text.contains("No space left")) return "ENOSPC";
    if (text.contains("EDQUOT") || text.contains("Disk quota")) return "EDQUOT";
    if (text.contains("EACCES") || text.contains("Permission denied")) return "EACCES";
    if (text.contains("EPERM") || text.contains("Operation not permitted")) return "EPERM";
    if (text.contains("EROFS") || text.contains("Read-only file system")) return "EROFS";
    if (text.contains("ENOENT") || text.contains("No such file")) return "ENOENT";
    if (text.contains("EIO") || text.contains("Input/output error")) return "EIO";
    if (text.contains("EMFILE") || text.contains("Too many open files")) return "EMFILE";
    if (text.contains("ENOMEM") || text.contains("Cannot allocate memory")) return "ENOMEM";
    return "";
  }

  /** 事实行只保留最近的 {@link #MAX_ITEMS} 条；顺序保持采集顺序。 */
  public static List<String> recent(List<String> values) {
    List<String> result = new ArrayList<>();
    if (values == null) return result;
    int start = Math.max(0, values.size() - MAX_ITEMS);
    for (int index = start; index < values.size(); index++)
      if (values.get(index) != null && !values.get(index).isEmpty())
        result.add(clip(values.get(index), MAX_LINE));
    return result;
  }

  /** 单行化并限长：换行、制表符和其余控制字符统一成空格，防止伪造成多行报告。 */
  public static String clip(String text, int limit) {
    if (text == null) return "";
    StringBuilder out = new StringBuilder(Math.min(text.length(), limit));
    for (int index = 0; index < text.length() && out.length() < limit; index++) {
      char c = text.charAt(index);
      out.append(c < ' ' || c == '\u007f' ? ' ' : c);
    }
    return out.toString().trim();
  }

  private static void line(StringBuilder out, String text) {
    out.append(clip(text, MAX_LINE + 120)).append('\n');
  }

  private static String join(List<String> values) {
    List<String> recent = recent(values);
    return recent.isEmpty() ? "-" : String.join(" | ", recent);
  }

  /** 列表段：一行一条，保留采集顺序；单条按 160 字符收口，接近上限就停止追加。 */
  private static void section(StringBuilder out, String title, List<String> values) {
    List<String> recent = recent(values);
    line(out, title + (recent.isEmpty() ? ": -" : ":"));
    for (String value : recent) {
      if (out.length() >= LIMIT - MAX_LINE - 200) return;
      line(out, "  - " + clip(value, 160));
    }
  }

  private static String value(String text) {
    String clipped = clip(text, MAX_LINE);
    return clipped.isEmpty() ? "-" : clipped;
  }

  private static boolean isEmpty(String text) {
    return text == null || text.trim().isEmpty();
  }

  private static String bytes(long value) {
    if (value < 0) return "-";
    return Fmt.bytes(value) + "(" + value + ")";
  }

  private static String up(String text) {
    return text == null ? "" : text.toUpperCase(java.util.Locale.ROOT);
  }
}
