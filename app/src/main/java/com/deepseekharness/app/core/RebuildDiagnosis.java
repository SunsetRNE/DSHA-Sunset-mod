package com.deepseekharness.app.core;

import android.content.Context;
import android.os.Build;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.GuestPaths;
import com.deepseekharness.app.util.RebuildDiagnosisText;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 维护页的只读诊断采集：把环境重建现场拼成一段可复制 / 可分享的纯文本报告。
 *
 * <p>只读：不解压、不启动 guest、不改事务、不写就绪标记。所有读取都单独降级为 {@code -}，因此环境
 * 完全不可用时也能给出身份、阶段与最近失败事实。报告正文由纯逻辑 {@link RebuildDiagnosisText} 生成。
 */
public final class RebuildDiagnosis {
  private RebuildDiagnosis() {}

  /** 日志里与重建有关的标签；其它维护 / 网页事件不进这份报告。 */
  static final String[] TAGS = {"REBUILD_BEGIN", "REBUILD_STAGE", "REBUILD_SPACE", "REBUILD_FAIL"};

  public static String build(Context context) {
    Context app = context.getApplicationContext();
    RebuildDiagnosisText.Facts facts = new RebuildDiagnosisText.Facts();
    facts.generated =
        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .format(new java.util.Date());
    facts.versionName = BuildConfig.VERSION_NAME;
    facts.versionCode = String.valueOf(BuildConfig.VERSION_CODE);
    facts.device = device();
    facts.api = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    facts.abi = firstAbi();
    facts.layout = layout(app);
    facts.runtime = runtimeId(app);
    facts.probe = probe(app);
    File rootfs = null;
    try {
      rootfs = GuestPaths.rootfs(app.getFilesDir());
    } catch (RuntimeException unavailable) {
      // 保持 null：目录行显示 -。
    }
    facts.rootfs = rootfsState(rootfs);
    readLog(app, facts);
    readSnapshot(app, facts);
    readAssets(app, facts);
    return com.deepseekharness.app.util.SensitiveData.redact(RebuildDiagnosisText.build(facts));
  }

  /** 解析日志尾部：最近子阶段、空间采样与失败事实。 */
  static void readLog(Context app, RebuildDiagnosisText.Facts facts) {
    List<String> stages = new ArrayList<>(), spaces = new ArrayList<>();
    String failureLine = "";
    try {
      String tail = DiagnosticLog.readExportTail(app);
      for (String line : tail.split("\r?\n")) {
        if (line.contains("[REBUILD_STAGE]")) stages.add(tagValue(line, "REBUILD_STAGE"));
        else if (line.contains("[REBUILD_SPACE]")) spaces.add(tagValue(line, "REBUILD_SPACE"));
        else if (line.contains("[REBUILD_FAIL]")) failureLine = tagValue(line, "REBUILD_FAIL");
        else if (line.contains("[REBUILD_BEGIN]") && failureLine.isEmpty()) {
          Map<String, String> begin =
              RebuildDiagnosisText.keyValues(tagValue(line, "REBUILD_BEGIN"));
          facts.available = number(begin.get("available"), facts.available);
        }
      }
    } catch (Exception | LinkageError unavailable) {
      // 记录读不到时报告仍给身份与阶段。
    }
    facts.stages = RebuildDiagnosisText.recent(stages);
    facts.spaces = RebuildDiagnosisText.recent(spaces);
    if (!failureLine.isEmpty()) {
      Map<String, String> values = RebuildDiagnosisText.keyValues(failureLine);
      facts.code = text(values.get("code"));
      facts.errno = text(values.get("errno"));
      facts.path = text(values.get("dir"));
      facts.bytesRead = number(values.get("read"), -1);
      facts.bytesTotal = number(values.get("total"), -1);
      String free =
          failureLine.contains("|") ? failureLine.substring(failureLine.indexOf('|') + 1) : "";
      facts.detail = free.trim();
    }
    // 空间要求取最近一次“要求大于零”的采样：FAILED 采样的要求固定为 0。
    for (int index = spaces.size() - 1; index >= 0; index--) {
      Map<String, String> sample = RebuildDiagnosisText.keyValues(spaces.get(index));
      long required = number(sample.get("requiredBytes"), 0);
      long available = number(sample.get("availableBytes"), -1);
      if (required > 0) {
        facts.required = required;
        facts.spaceAt = text(sample.get("stage"));
        if (facts.available < 0) facts.available = available;
        break;
      }
    }
    if (facts.available < 0) {
      try {
        facts.available = app.getFilesDir().getUsableSpace();
      } catch (RuntimeException unavailable) {
        // 保持 -。
      }
    }
    if (facts.substage.isEmpty() && !facts.stages.isEmpty())
      facts.substage = facts.stages.get(facts.stages.size() - 1);
  }

  /** 任务快照给出界面上的阶段与状态；日志缺失时它是唯一的来源。 */
  static void readSnapshot(Context app, RebuildDiagnosisText.Facts facts) {
    try {
      var snapshot = BackupTask.get(app).snapshot();
      if (snapshot == null) return;
      facts.status = snapshot.status == null ? "" : snapshot.status.name();
      facts.stage = snapshot.lastStage == null ? "" : snapshot.lastStage;
      if (facts.detail.isEmpty() && snapshot.detail != null) facts.detail = snapshot.detail;
    } catch (RuntimeException | LinkageError unavailable) {
      // 维护状态读不到不影响其它行。
    }
  }

  /** 内置包两个入口的声明大小与 CRC：只读 zip 目录，不解压、不哈希整包。 */
  static void readAssets(Context app, RebuildDiagnosisText.Facts facts) {
    List<String> assets = new ArrayList<>();
    try (ZipFile apk = new ZipFile(app.getPackageCodePath())) {
      for (String name : new String[] {"assets/offline-rootfs.bin", "assets/dsh-runtime.bin"}) {
        ZipEntry entry = apk.getEntry(name);
        if (entry == null) {
          assets.add(name.substring(name.lastIndexOf('/') + 1) + "=missing");
          continue;
        }
        assets.add(
            name.substring(name.lastIndexOf('/') + 1)
                + " size="
                + entry.getSize()
                + " crc="
                + Long.toHexString(entry.getCrc()));
      }
    } catch (Exception | LinkageError unavailable) {
      assets.add("unreadable");
    }
    facts.assets = assets;
  }

  /** 目录状态只做一次 listFiles 计数，避免在维护页上遍历整棵树。 */
  static String rootfsState(File rootfs) {
    if (rootfs == null) return "-";
    try {
      if (!rootfs.exists()) return "exists=no";
      String[] entries = rootfs.list();
      int count = entries == null ? -1 : Math.min(entries.length, 9999);
      return "exists=yes entries="
          + count
          + " usable="
          + com.deepseekharness.app.util.Fmt.bytes(rootfs.getUsableSpace());
    } catch (RuntimeException unreadable) {
      return "exists=unreadable";
    }
  }

  static String runtimeId(Context app) {
    try {
      var installed = HarnessController.get(app).proot().installedRuntimeDescriptor();
      if (installed != null && installed.id() != null && !installed.id().isEmpty())
        return installed.id();
    } catch (Exception | LinkageError unavailable) {
      // 维护或未装机时读不到：保持 -。
    }
    return "";
  }

  static String layout(Context app) {
    try (InputStream input = app.getAssets().open("offline-rootfs.layout")) {
      byte[] buffer = new byte[64];
      int read = input.read(buffer);
      return read <= 0
          ? ""
          : new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8).trim();
    } catch (Exception | LinkageError unavailable) {
      return "";
    }
  }

  /** SELinux 域与 /proc 可读性：区分“宿主策略拒绝”和“包/内核问题”的最短证据。 */
  static String probe(Context app) {
    String selinux = readFirstLine("/proc/self/attr/current");
    boolean proc = new File("/proc/self/stat").canRead();
    return "selinux="
        + (selinux.isEmpty() ? "-" : selinux)
        + " proc="
        + (proc ? "readable" : "unreadable");
  }

  static String device() {
    String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
    String model = Build.MODEL == null ? "" : Build.MODEL.trim();
    if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT)))
      return model;
    return (manufacturer + " " + model).trim();
  }

  static String firstAbi() {
    String[] abis = Build.SUPPORTED_ABIS;
    return abis == null || abis.length == 0 ? "" : abis[0];
  }

  /** 取「[TAG] 」之后的正文。 */
  static String tagValue(String line, String tag) {
    int index = line.indexOf("[" + tag + "]");
    if (index < 0) return "";
    return line.substring(index + tag.length() + 2).trim();
  }

  static long number(String value, long fallback) {
    if (value == null || value.isEmpty()) return fallback;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException invalid) {
      return fallback;
    }
  }

  static String text(String value) {
    return value == null ? "" : value.trim();
  }

  static String readFirstLine(String path) {
    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(path))) {
      String line = reader.readLine();
      return line == null ? "" : line.trim();
    } catch (Exception | LinkageError unavailable) {
      return "";
    }
  }
}
