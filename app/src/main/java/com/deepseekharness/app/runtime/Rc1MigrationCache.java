package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.Rc1MigrationReuse;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 只用本次 NOFOLLOW 小型读取复用已提交的私有迁移，不遍历会话。
 *
 * <p>探测结果区分：<b>历史命中</b>（本代完整可复用，但只是历史证据）、<b>确定新输入</b>
 * （小输入/恢复代次变化，允许新建代次）、<b>无既有迁移</b>与<b>权威不可用</b>（记录、
 * 账本缺失或矛盾；不得折叠成 cache miss 后自动重导）。命中只输出 historical 作用域，
 * 不合成 protectionComplete/sourcePreserved 之类的对象身份或本次核验布尔。
 */
final class Rc1MigrationCache {
  /** 只读探测结果；UNAVAILABLE 不归入 ABSENT，缓存缺失不等于新迁移意图。 */
  static final class Reuse {
    enum Status {
      REUSABLE_HISTORY,
      NEW_INPUT,
      ABSENT,
      UNAVAILABLE
    }

    final Status status;
    final String generation, reason;
    final String settingsImportStatus, settingsSha256, inputDigest, ledgerDigest, dshVersion;
    final Map<String, String> inputs;

    private Reuse(
        Status status,
        String generation,
        String reason,
        String settingsImportStatus,
        String settingsSha256,
        String inputDigest,
        String ledgerDigest,
        String dshVersion,
        Map<String, String> inputs) {
      this.status = status;
      this.generation = generation;
      this.reason = reason;
      this.settingsImportStatus = settingsImportStatus;
      this.settingsSha256 = settingsSha256;
      this.inputDigest = inputDigest;
      this.ledgerDigest = ledgerDigest;
      this.dshVersion = dshVersion;
      this.inputs = inputs;
    }

    static Reuse of(Status status, String reason) {
      return new Reuse(status, null, reason, "unknown", null, null, null, null, Map.of());
    }

    static Reuse unavailable(String reason) {
      return of(Status.UNAVAILABLE, reason);
    }

    static Reuse absent() {
      return of(Status.ABSENT, null);
    }

    static Reuse newInput(Map<String, String> inputs) {
      return new Reuse(Status.NEW_INPUT, null, null, "unknown", null, null, null, null, inputs);
    }

    static Reuse history(
        String generation,
        String settingsImportStatus,
        String settingsSha256,
        String inputDigest,
        String ledgerDigest,
        String dshVersion,
        Map<String, String> inputs) {
      return new Reuse(
          Status.REUSABLE_HISTORY,
          generation,
          null,
          settingsImportStatus,
          settingsSha256,
          inputDigest,
          ledgerDigest,
          dshVersion,
          inputs);
    }
  }

  private static final String HOME = "/root/.dsh";
  private static final int MAX_GENERATIONS = 64;

  private Rc1MigrationCache() {}

  static Reuse reuse(BackupFileSystem fs, File files, File home) {
    try {
      return detect(fs, files, home);
    } catch (IOException | RuntimeException unavailable) {
      return Reuse.unavailable("MIGRATION_REUSE_UNREADABLE");
    }
  }

  private static Reuse detect(BackupFileSystem fs, File files, File home) throws IOException {
    String prefix = files.getAbsolutePath() + File.separator;
    if (!home.getAbsolutePath().startsWith(prefix))
      return Reuse.unavailable("MIGRATION_REUSE_HOME_AUTHORITY");
    // 即使三个配置输入都缺失，仍核对所选根的全部父级。
    home =
        fs.child(
            files,
            home.getAbsolutePath().substring(prefix.length()).replace(File.separatorChar, '/'));
    var root = fs.stat(home);
    File state = fs.child(files, "rc1-migration-state");
    if (root.type.equals("MISSING")) {
      // 数据根不存在：既没有可复用代次，也没有输入意图。
      return fs.stat(state).type.equals("MISSING")
          ? Reuse.absent()
          : Reuse.unavailable("MIGRATION_REUSE_ROOT_MISSING");
    }
    if (!root.type.equals("DIRECTORY")) return Reuse.unavailable("MIGRATION_REUSE_ROOT_TYPE");
    String[] identity = root.key.split(":", -1);
    if (identity.length != 2 || !identity[0].equals(Long.toString(root.device)))
      return Reuse.unavailable("MIGRATION_REUSE_ROOT_IDENTITY");
    var stateStat = fs.stat(state);
    if (stateStat.type.equals("MISSING")) return Reuse.absent();
    if (!stateStat.type.equals("DIRECTORY")) return Reuse.unavailable("MIGRATION_REUSE_STATE_TYPE");
    var currentStat = fs.stat(fs.child(state, "current.json"));
    Map<String, Object> current;
    if (currentStat.type.equals("MISSING")) {
      // current 缺失但已有完成代次：权威索引丢失，不能当首次迁移或普通 miss。
      return completedGeneration(fs, state)
          ? Reuse.unavailable("HOST_STATE_MISSING")
          : Reuse.absent();
    }
    try {
      current = json(fs, state, "current.json");
    } catch (IOException invalid) {
      return Reuse.unavailable("HOST_STATE_INVALID");
    }
    if (!versionTwo(current) || !(current.get("generation") instanceof String))
      return Reuse.unavailable("HOST_STATE_INVALID");
    String generation = (String) current.get("generation");
    if (!Ids.uuid(generation)) return Reuse.unavailable("HOST_STATE_INVALID");
    if (!HOME.equals(current.get("dshHome")))
      return Reuse.unavailable("MIGRATION_REUSE_HOME_RECORD");
    String folder = "generations/" + generation + "/";
    Map<String, String> inputs = inputs(fs, home);
    if (inputs == null) return Reuse.unavailable("MIGRATION_REUSE_INPUT_READ");
    if (!Rc1MigrationReuse.sameInputs(current.get("inputs"), inputs)) return Reuse.newInput(inputs);
    Map<String, Object> prepared, receipt;
    try {
      prepared = json(fs, state, folder + "reuse.json");
      receipt = json(fs, state, folder + "receipt.json");
    } catch (IOException missing) {
      return Reuse.unavailable("MIGRATION_REUSE_RECORD_MISSING");
    }
    // 缓存探测后重新读取全部权威记录；代次替换时不能借不同代次记录放行。
    if (!current.equals(json(fs, state, "current.json"))
        || !prepared.equals(json(fs, state, folder + "reuse.json"))
        || !receipt.equals(json(fs, state, folder + "receipt.json")))
      return Reuse.unavailable("MIGRATION_REUSE_RECORD_CHANGED");
    if (!root.same(fs.stat(home))) return Reuse.unavailable("MIGRATION_REUSE_ROOT_CHANGED");
    if (!Constants.DSH_VERSION.equals(prepared.get("dshVersion")))
      return Reuse.unavailable("MIGRATION_REUSE_CONTRACT_VERSION");
    if (!prepared.containsKey("settingsSha256") || !(prepared.get("inputDigest") instanceof String))
      return Reuse.unavailable("MIGRATION_REUSE_CHECKPOINT_VERSION");
    if (!Rc1MigrationReuse.matches(current, prepared, receipt, identity[0], identity[1], inputs))
      return Reuse.unavailable("MIGRATION_REUSE_RECORD_MISMATCH");
    Object sha = prepared.get("settingsSha256");
    String settingsSha256 = sha instanceof String ? (String) sha : null;
    if (sha != null && settingsSha256 == null)
      return Reuse.unavailable("MIGRATION_REUSE_CHECKPOINT_VERSION");
    String settingsImportStatus = "not-required";
    String ledgerDigest = null;
    if (settingsSha256 != null) {
      Map<String, Object> ledger;
      try {
        ledger = json(fs, state, folder + "settings-result.json");
      } catch (IOException missing) {
        return Reuse.unavailable("SETTINGS_LEDGER_MISSING");
      }
      if (!Rc1MigrationReuse.ledgerBinding(generation, settingsSha256, ledger))
        return Reuse.unavailable("SETTINGS_LEDGER_UNKNOWN");
      String ledgerState = Rc1MigrationReuse.ledgerState(ledger);
      settingsImportStatus =
          "verified".equals(ledgerState)
              ? "verified-historical"
              : ("pending".equals(ledgerState) ? "pending" : "unknown");
      ledgerDigest = digest(ledgerDigestMaterial(generation, settingsSha256, ledgerState, ledger));
    }
    return Reuse.history(
        generation,
        settingsImportStatus,
        settingsSha256,
        (String) prepared.get("inputDigest"),
        ledgerDigest,
        (String) prepared.get("dshVersion"),
        inputs);
  }

  private static boolean completedGeneration(BackupFileSystem fs, File state) throws IOException {
    File generations = fs.child(state, "generations");
    if (!fs.stat(generations).type.equals("DIRECTORY")) return false;
    java.util.List<String> names = fs.list(generations);
    if (names.size() > MAX_GENERATIONS) return true;
    for (String name : names) {
      if (!Ids.uuid(name)) continue;
      File folder = fs.child(generations, name);
      for (String record : new String[] {"prepare.json", "receipt.json"}) {
        Map<String, Object> value;
        try {
          value = json(fs, folder, record);
        } catch (IOException missing) {
          continue;
        }
        if ("prepare.json".equals(record)
            && Boolean.TRUE.equals(value.get("protectionComplete"))
            && "prepared".equals(value.get("status"))) return true;
        if ("receipt.json".equals(record) && "committed".equals(value.get("status"))) return true;
      }
    }
    return false;
  }

  private static Map<String, String> inputs(BackupFileSystem fs, File home) throws IOException {
    Map<String, String> inputs = new LinkedHashMap<>();
    for (String name :
        new String[] {"settings.yaml", "settings.yaml.imported", ".dsha-rc1-restore-generation"}) {
      File file = fs.child(home, name);
      var before = fs.stat(file);
      if (before.type.equals("MISSING")) continue;
      if (!before.type.equals("FILE")) return null;
      int maximum = name.startsWith(".dsha-") ? 4096 : 4 * 1024 * 1024;
      byte[] bytes = fs.small(file, maximum);
      if (!before.same(fs.stat(file))) return null;
      inputs.put(name.startsWith(".dsha-") ? "restore" : name, hash(bytes));
    }
    return inputs;
  }

  private static Map<String, Object> json(BackupFileSystem fs, File state, String name)
      throws IOException {
    File file = fs.child(state, name);
    var before = fs.stat(file);
    if (!before.type.equals("FILE")) throw new IOException("MIGRATION_REUSE_RECORD_TYPE");
    byte[] bytes = fs.small(file, 64 * 1024);
    if (!before.same(fs.stat(file))) throw new IOException("MIGRATION_REUSE_RECORD_CHANGED");
    return BackupJson.read(bytes, 64 * 1024);
  }

  private static boolean versionTwo(Map<String, Object> value) {
    if (value == null) return false;
    Object version = value.get("version");
    return Integer.valueOf(2).equals(version) || Long.valueOf(2).equals(version);
  }

  /** 历史命中只输出历史作用域；不写对象身份、不合成本次核验成功。 */
  static String output(Reuse reuse, boolean finalized, String startupId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("resultVersion", 3);
    result.put("phase", finalized ? "finalize" : "prepare");
    result.put("status", "historical-complete");
    result.put("generation", reuse.generation);
    result.put("startupId", startupId);
    result.put("cached", true);
    result.put("verificationScope", "historical");
    result.put("contentCopyStatus", "verified");
    result.put("sourceObjectIdentity", "unknown");
    result.put("settingsImportStatus", reuse.settingsImportStatus);
    result.put("executionState", "NOT_SENT");
    result.put("dshVersion", reuse.dshVersion);
    result.put("runtimeDshVersion", Constants.DSH_VERSION);
    return line(result);
  }

  /** 本进程内票据失效/证据不可用：明确 unknown，不补派 guest、不沿用旧 startupId。 */
  static String unknown(String phase, String startupId, String reason, String generation) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("resultVersion", 3);
    result.put("phase", phase);
    result.put("status", "unknown");
    result.put("reason", reason);
    result.put("startupId", startupId);
    if (generation != null) result.put("generation", generation);
    result.put("cached", false);
    result.put("verificationScope", "none");
    result.put("contentCopyStatus", "unknown");
    result.put("sourceObjectIdentity", "unknown");
    result.put("settingsImportStatus", "unknown");
    result.put("executionState", "UNKNOWN");
    return line(result);
  }

  private static String line(Map<String, Object> result) {
    try {
      return "DSHA_RC1_MIGRATION="
          + new String(BackupJson.write(result, 4096), StandardCharsets.UTF_8)
          + "\n";
    } catch (IOException impossible) {
      throw new IllegalStateException("RC1_MIGRATION_RESULT_SERIALIZE", impossible);
    }
  }

  private static String ledgerDigestMaterial(
      String generation, String settingsSha256, String state, Map<String, Object> ledger) {
    StringBuilder value =
        new StringBuilder(generation).append('|').append(settingsSha256).append('|').append(state);
    Object sections = ledger.get("sections");
    if (sections instanceof Map) {
      java.util.TreeMap<String, String> ordered = new java.util.TreeMap<>();
      for (var entry : ((Map<?, ?>) sections).entrySet())
        if (entry.getKey() instanceof String && entry.getValue() instanceof Map)
          ordered.put(
              (String) entry.getKey(),
              String.valueOf(((Map<?, ?>) entry.getValue()).get("status")));
      for (var entry : ordered.entrySet())
        value.append('|').append(entry.getKey()).append(':').append(entry.getValue());
    }
    return value.toString();
  }

  private static String digest(String material) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(material.getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder();
      for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b & 255));
      return value.toString();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String hash(byte[] bytes) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder value = new StringBuilder();
      for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b & 255));
      return value.toString();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
