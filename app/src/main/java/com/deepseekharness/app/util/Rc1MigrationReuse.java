package com.deepseekharness.app.util;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A completed host checkpoint may avoid guest work only for freshly verified small inputs. */
public final class Rc1MigrationReuse {
  private static final String HOME = "/root/.dsh";
  private static final Set<String> INPUTS =
      Set.of("settings.yaml", "settings.yaml.imported", "restore");

  private Rc1MigrationReuse() {}

  /**
   * The caller supplies the selected data root's actual device/inode and hashes freshly read through
   * a no-follow filesystem boundary. An empty input map means all three paths were checked absent;
   * null means unavailable. Links, unsupported mappings and unstable reads must not reach this gate.
   * APK versions and user archive metadata cannot substitute for these private host records.
   */
  public static boolean matches(
      Map<String, Object> current,
      Map<String, Object> prepared,
      Map<String, Object> receipt,
      String device,
      String inode,
      Map<String, String> inputs) {
    if (current == null || prepared == null || receipt == null || !validInputs(inputs))
      return false;
    if (!versionTwo(current) || !versionTwo(prepared) || !versionTwo(receipt)) return false;
    Object generation = current.get("generation");
    if (!(generation instanceof String)
        || !Ids.uuid((String) generation)
        || !generation.equals(prepared.get("generation"))
        || !generation.equals(receipt.get("generation"))) return false;
    if (!HOME.equals(current.get("dshHome"))
        || !HOME.equals(prepared.get("dshHome"))
        || !"prepared".equals(prepared.get("status"))
        || !Boolean.TRUE.equals(prepared.get("protectionComplete"))
        || !"committed".equals(receipt.get("status"))
        || !Boolean.TRUE.equals(receipt.get("protectionComplete"))
        || !Boolean.TRUE.equals(receipt.get("sourcePreserved"))
        || !Boolean.TRUE.equals(receipt.get("settingsImported"))) return false;
    Object runtime = prepared.get("dshVersion");
    if (!(runtime instanceof String)
        || ((String) runtime).isEmpty()
        || !runtime.equals(receipt.get("dshVersion"))) return false;
    if (!identity(current.get("dataRoot"), device, inode)
        || !identity(prepared.get("dataRoot"), device, inode)) return false;
    return sameInputs(current.get("inputs"), inputs) && sameInputs(prepared.get("inputs"), inputs);
  }

  private static boolean versionTwo(Map<String, Object> value) {
    Object version = value.get("version");
    return Long.valueOf(2).equals(version) || Integer.valueOf(2).equals(version);
  }

  private static boolean identity(Object value, String device, String inode) {
    if (!decimal(device) || !decimal(inode) || !(value instanceof Map)) return false;
    Map<?, ?> root = (Map<?, ?>) value;
    return HOME.equals(root.get("path"))
        && device.equals(root.get("device"))
        && inode.equals(root.get("inode"));
  }

  private static boolean decimal(String value) {
    return value != null && value.matches("[0-9]{1,20}");
  }

  private static boolean validInputs(Object value) {
    if (!(value instanceof Map)) return false;
    Map<?, ?> inputs = (Map<?, ?>) value;
    for (Map.Entry<?, ?> entry : inputs.entrySet()) {
      if (!(entry.getKey() instanceof String)
          || !INPUTS.contains(entry.getKey())
          || !(entry.getValue() instanceof String)
          || !((String) entry.getValue()).matches("[a-f0-9]{64}")) return false;
    }
    return true;
  }

  /** 只读证据投影，不持有可变账本，也不授予重新导入、恢复或删除权限。 */
  public static final class LedgerEvidence {
    public final String state, reason;

    private LedgerEvidence(String state, String reason) {
      this.state = state;
      this.reason = reason;
    }

    public static LedgerEvidence unknown(String reason) {
      return new LedgerEvidence("unknown", reason);
    }
  }

  /**
   * 启动门禁和保留清单共用同一证据：身份绑定、完整期望集合、逐节终态。
   * 缺 expectedSections 时仅接受同代同 SHA 且带 provenance 的 checkpoint，
   * 键存在但非法时不可回退；不能从现存 sections 自证集合完整。
   */
  public static LedgerEvidence ledgerEvidence(
      String generation,
      String settingsSha256,
      Map<String, Object> ledger,
      Map<String, Object> checkpoint) {
    if (settingsSha256 == null) return new LedgerEvidence("not-required", null);
    if (ledger == null) return LedgerEvidence.unknown("SETTINGS_LEDGER_MISSING");
    if (generation == null
        || !Ids.uuid(generation)
        || !settingsSha256.matches("[a-f0-9]{64}")
        || !generation.equals(ledger.get("generation"))
        || !settingsSha256.equals(ledger.get("sourceSha256")))
      return LedgerEvidence.unknown("SETTINGS_LEDGER_IDENTITY");
    if ("unknown".equals(ledgerState(ledger)))
      return LedgerEvidence.unknown("SETTINGS_LEDGER_UNKNOWN");
    Set<String> expected;
    if (ledger.containsKey("expectedSections")) {
      expected = sectionNames(ledger.get("expectedSections"));
      if (expected == null) return LedgerEvidence.unknown("SETTINGS_LEDGER_SECTION");
    } else {
      expected = expectedSections(generation, settingsSha256, checkpoint);
      if (expected == null) return LedgerEvidence.unknown("SETTINGS_EXPECTED_SECTIONS_MISSING");
    }
    String state = ledgerState(ledger, expected);
    return new LedgerEvidence(
        state, "verified".equals(state) ? null : "SETTINGS_LEDGER_INCOMPLETE");
  }

  private static Set<String> expectedSections(
      String generation, String settingsSha256, Map<String, Object> checkpoint) {
    if (checkpoint == null
        || !generation.equals(checkpoint.get("generation"))
        || !settingsSha256.equals(checkpoint.get("settingsSha256"))
        || !"ledger-expected".equals(checkpoint.get("settingsSectionsSource"))) return null;
    return sectionNames(checkpoint.get("settingsSections"));
  }

  private static Set<String> sectionNames(Object value) {
    if (!(value instanceof java.util.List)) return null;
    Set<String> names = new java.util.LinkedHashSet<>();
    for (Object name : (java.util.List<?>) value) {
      if (!(name instanceof String) || !names.add((String) name)) return null;
    }
    return names;
  }

  /**
   * 私有 settings 完成账本必须绑定当前代次与同一 sourceSha256；缺字段或未知分节状态
   * 一律 unknown，不能借旧 committed receipt 授予缓存命中。settingsSha256 为 null
   * 表示该代次没有 settings 来源（not-required）。
   */
  public static boolean ledgerBinding(
      String generation, String settingsSha256, Map<String, Object> ledger) {
    if (settingsSha256 == null) return true;
    if (generation == null || ledger == null) return false;
    if (!generation.equals(ledger.get("generation"))) return false;
    if (!settingsSha256.equals(ledger.get("sourceSha256"))) return false;
    return !"unknown".equals(ledgerState(ledger));
  }

  /** 逐节状态投影：verified / pending / unknown；写入中或未知终态不能当 pending。 */
  public static String ledgerState(Map<String, Object> ledger) {
    return ledgerState(ledger, null);
  }

  /**
   * expected 非空时要求分节集合完全一致：新账本记录期望分节数组，避免
   * “只含部分 verified 分节但 overall=verified”被当成完成。
   */
  public static String ledgerState(Map<String, Object> ledger, java.util.Set<String> expected) {
    if (ledger == null) return "unknown";
    Object sections = ledger.get("sections");
    if (!(sections instanceof Map)) return "unknown";
    boolean allVerified = true;
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) sections).entrySet()) {
      if (!(entry.getKey() instanceof String)) return "unknown";
      Object value = entry.getValue();
      if (!(value instanceof Map)) return "unknown";
      Object status = ((Map<?, ?>) value).get("status");
      if (!(status instanceof String)) return "unknown";
      if (Set.of("writing", "unknown").contains(status)) return "unknown";
      if (!Set.of("verified", "pending", "conflict").contains(status)) return "unknown";
      if (!"verified".equals(status)) allVerified = false;
    }
    if (expected != null && !expected.equals(((Map<?, ?>) sections).keySet())) return "unknown";
    Object overall = ledger.get("status");
    if ("unknown".equals(overall) || "writing".equals(overall)) return "unknown";
    return allVerified && "verified".equals(overall) ? "verified" : "pending";
  }

  /** settings.yaml → settings.yaml.imported 的合法 rename 视为同一内容输入。 */
  public static boolean sameInputs(Object value, Map<String, String> inputs) {
    if (!validInputs(value)) return false;
    Map<?, ?> old = (Map<?, ?>) value;
    if (!Objects.equals(old.get("restore"), inputs.get("restore"))) return false;
    // The upstream importer may rename a single settings file. When both inputs exist, neither
    // can mask changes to the other by being preferred during normalization.
    boolean oldBoth = old.containsKey("settings.yaml") && old.containsKey("settings.yaml.imported");
    boolean nowBoth =
        inputs.containsKey("settings.yaml") && inputs.containsKey("settings.yaml.imported");
    if (oldBoth || nowBoth) return old.equals(inputs);
    Object oldSettings =
        old.containsKey("settings.yaml")
            ? old.get("settings.yaml")
            : old.get("settings.yaml.imported");
    Object settings =
        inputs.containsKey("settings.yaml")
            ? inputs.get("settings.yaml")
            : inputs.get("settings.yaml.imported");
    return Objects.equals(oldSettings, settings);
  }
}
