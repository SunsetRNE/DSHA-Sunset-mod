package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.PlanViewCodec;
import com.deepseekharness.app.util.PlanViewValidator;
import com.deepseekharness.app.util.RustBackends;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * A3 slice2/3 投影器与提交期闸门（A3 任务基线 §3、§4）。
 *
 * <p>覆盖：确定性编码、{@code planDigest} 口径、根数上限不投影（Q1-A）、清单类型/范围不符
 * 不伪造默认值（P-4）、映射 2×2 编码、选择位图位序、旧包/维护快照的插件节点数、以及
 * 「INVALID/分歧/单侧失败必须中止」的提交期 fail-closed（P-5、T32）。
 */
public final class RestorePlanProjectionTest {
  private static final long NOW = 1_760_000_000_500L;

  @After
  public void resetSwitch() {
    RustBackends.setEnabled(false);
  }

  private static Map<String, Object> manifest() {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("formatVersion", 5L);
    manifest.put("operation", "EXPORT");
    manifest.put("integrity", "QUIESCENT");
    manifest.put("createdAt", 1_760_000_000_000L);
    manifest.put("appVersion", "0.2.1-rc.5-20261113.1340-dsh0.2.2-rc.1");
    manifest.put("dataFormat", "UNINSPECTED");
    manifest.put("sensitivePolicy", "UNENCRYPTED");
    manifest.put("entries", 42L);
    manifest.put("bytes", 1_024L);
    manifest.put(
        "plugins",
        Map.of("version", 2L, "nodes", Map.of("package-aaaaaaaaaaaaaaaaaaaa", Map.of())));
    return manifest;
  }

  private static byte[] digest() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) value[index] = (byte) (index * 7);
    return value;
  }

  private static RestorePlanProjection.Receipt project(
      Map<String, Object> manifest, int rootCount, Set<String> selected) {
    return RestorePlanProjection.project(
        manifest, rootCount, digest(), selected, PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE, NOW);
  }

  private static RestorePlanProjection.Receipt project(
      Map<String, Object> manifest, int rootCount) {
    return project(manifest, rootCount, Set.of("application"));
  }

  @Test
  public void validPlanProducesDeterministicValidView() {
    RestorePlanProjection.Receipt receipt = project(manifest(), 3);
    assertTrue(receipt.projected());
    assertEquals(RestorePlanProjection.VERDICT_VALID, receipt.verdict());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_READY, receipt.diagnostic());
    assertEquals(RestorePlanProjection.BACKEND_JVM_ONLY, receipt.backend());
    assertEquals("SWITCH_OFF", receipt.selection());
    assertEquals(RestorePlanProjection.CHECK_VALID, receipt.jvmState());
    assertEquals(RestorePlanProjection.CHECK_NOT_ATTEMPTED, receipt.rustState());
    assertEquals(0, receipt.jvmReasons().length);
    assertEquals(0, receipt.rustReasons().length);
    RestorePlanProjection.Receipt again = project(manifest(), 3);
    assertArrayEquals(receipt.view(), again.view());
    assertArrayEquals(receipt.planDigest(), again.planDigest());
    // 视图事实与 preview.json 既有字段自洽（P-6）。
    PlanViewCodec.View view = PlanViewCodec.decode(receipt.view());
    assertEquals(PlanViewCodec.POLICY_VERSION, view.policyVersion);
    assertFalse(view.hasExpiry);
    assertEquals(5, view.formatVersion);
    assertEquals(PlanViewCodec.OPERATION_EXPORT, view.operation);
    assertEquals(PlanViewCodec.INTEGRITY_QUIESCENT, view.integrity);
    assertEquals(3, view.rootCount);
    assertEquals(42, view.entryCount);
    assertEquals(1_024L, view.byteCount);
    assertEquals(1, view.pluginCount);
    assertEquals(1L, view.selectedMask);
    assertEquals(PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE, view.targetMappingKind);
    assertEquals(1_760_000_000_000L, view.createdAtMs);
    assertEquals("UNINSPECTED", view.dataFormat);
    assertEquals("UNENCRYPTED", view.sensitivePolicy);
    assertEquals(0, PlanViewValidator.validate(receipt.view()).reasons().length);
  }

  @Test
  public void planDigestIsSha256OfTheZeroedDigestField() throws Exception {
    RestorePlanProjection.Receipt receipt = project(manifest(), 3);
    byte[] expected =
        MessageDigest.getInstance("SHA-256")
            .digest(PlanViewCodec.withZeroPlanDigest(receipt.view()));
    assertArrayEquals(expected, receipt.planDigest());
    assertArrayEquals(expected, PlanViewCodec.decode(receipt.view()).planDigest);
    Map<String, Object> facts = receipt.facts();
    assertEquals(64, ((String) facts.get("planDigest")).length());
    assertTrue(((String) facts.get("planDigest")).matches("[0-9a-f]{64}"));
  }

  @Test
  public void submitTimeReencodesWithNewNowMsAndNewDigest() {
    RestorePlanProjection.Receipt preview = project(manifest(), 2);
    RestorePlanProjection.Receipt submit =
        RestorePlanProjection.project(
            manifest(),
            2,
            digest(),
            Set.of("application"),
            PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE,
            NOW + 1_000);
    assertEquals(RestorePlanProjection.VERDICT_VALID, submit.verdict());
    assertFalse(Arrays.equals(preview.view(), submit.view()));
    assertFalse(Arrays.equals(preview.planDigest(), submit.planDigest()));
    assertEquals(NOW, PlanViewCodec.decode(preview.view()).nowMs);
    assertEquals(NOW + 1_000, PlanViewCodec.decode(submit.view()).nowMs);
  }

  @Test
  public void planRootLimitSkipsProjectionButKeepsRestorePath() throws IOException {
    RestorePlanProjection.Receipt receipt = project(manifest(), 65);
    assertFalse(receipt.projected());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_ROOT_LIMIT, receipt.diagnostic());
    assertEquals(RestorePlanProjection.VERDICT_UNVERIFIED, receipt.verdict());
    assertEquals(RestorePlanProjection.BACKEND_JVM_ONLY, receipt.backend());
    assertNull(receipt.view());
    assertNull(receipt.planDigest());
    assertFalse(receipt.facts().containsKey("planDigest"));
    // Q1-A：不投影、不调 Rust，JVM 权威恢复照常继续（提交期闸门不中止）。
    RestorePlanProjection.requireSubmitSafe(receipt);
    assertEquals(RestorePlanProjection.DIAGNOSTIC_COUNTS, project(manifest(), 0).diagnostic());
  }

  @Test
  public void manifestRangeViolationsNeverFabricateDefaults() {
    Map<String, Object> wrongFormat = manifest();
    wrongFormat.put("formatVersion", 4L);
    Map<String, Object> wrongOperation = manifest();
    wrongOperation.put("operation", "SNAPSHOT");
    Map<String, Object> wrongIntegrity = manifest();
    wrongIntegrity.put("integrity", "GUESSED");
    Map<String, Object> wrongEntries = manifest();
    wrongEntries.put("entries", 0L);
    Map<String, Object> wrongBytes = manifest();
    wrongBytes.put("bytes", -1L);
    Map<String, Object> oversizedAppVersion = manifest();
    oversizedAppVersion.put("appVersion", "x".repeat(257));
    Map<String, Object> emptyDataFormat = manifest();
    emptyDataFormat.put("dataFormat", "");
    Map<String, Object> unknownPolicy = manifest();
    unknownPolicy.put("sensitivePolicy", "FUTURE_POLICY");
    Map<String, Object> tooManyPlugins = manifest();
    Map<String, Object> nodes = new LinkedHashMap<>();
    for (int index = 0; index <= PlanViewCodec.MAX_PLUGIN_COUNT; index++)
      nodes.put("package-" + index, Map.of());
    tooManyPlugins.put("plugins", Map.of("version", 2L, "nodes", nodes));
    assertFails(RestorePlanProjection.DIAGNOSTIC_FORMAT_VERSION, wrongFormat);
    assertFails(RestorePlanProjection.DIAGNOSTIC_OPERATION, wrongOperation);
    assertFails(RestorePlanProjection.DIAGNOSTIC_INTEGRITY, wrongIntegrity);
    assertFails(RestorePlanProjection.DIAGNOSTIC_COUNTS, wrongEntries);
    assertFails(RestorePlanProjection.DIAGNOSTIC_COUNTS, wrongBytes);
    assertFails(RestorePlanProjection.DIAGNOSTIC_APP_VERSION, oversizedAppVersion);
    assertFails(RestorePlanProjection.DIAGNOSTIC_DATA_FORMAT, emptyDataFormat);
    assertFails(RestorePlanProjection.DIAGNOSTIC_SENSITIVE_POLICY, unknownPolicy);
    assertFails(RestorePlanProjection.DIAGNOSTIC_PLUGIN_COUNT, tooManyPlugins);
    // 类型不符（P-4）：V5 读者只查键存在，投影器必须拒绝，不能编造默认值。
    Map<String, Object> stringTimestamp = manifest();
    stringTimestamp.put("createdAt", "yesterday");
    assertFails(RestorePlanProjection.DIAGNOSTIC_TIMESTAMP, stringTimestamp);
    Map<String, Object> fractionalTimestamp = manifest();
    fractionalTimestamp.put("createdAt", new BigDecimal("123.5"));
    assertFails(RestorePlanProjection.DIAGNOSTIC_TIMESTAMP, fractionalTimestamp);
    Map<String, Object> listPlugins = manifest();
    listPlugins.put("plugins", List.of("not-a-graph"));
    assertFails(RestorePlanProjection.DIAGNOSTIC_PLUGIN_COUNT, listPlugins);
    assertFails(RestorePlanProjection.DIAGNOSTIC_MANIFEST, null);
  }

  private static void assertFails(String diagnostic, Map<String, Object> manifest) {
    RestorePlanProjection.Receipt receipt = project(manifest, 1);
    assertFalse(receipt.projected());
    assertEquals(diagnostic, receipt.diagnostic());
    assertEquals(RestorePlanProjection.VERDICT_UNVERIFIED, receipt.verdict());
  }

  @Test
  public void targetMappingKindFollowsFrozenTwoByTwoEncoding() {
    assertEquals(1, RestorePlanProjection.targetMappingKind("STABLE", "PRIVATE"));
    assertEquals(2, RestorePlanProjection.targetMappingKind("LEGACY", "PRIVATE"));
    assertEquals(3, RestorePlanProjection.targetMappingKind("STABLE", "GUEST_HOME"));
    assertEquals(4, RestorePlanProjection.targetMappingKind("LEGACY", "GUEST_HOME"));
    assertEquals(0, RestorePlanProjection.targetMappingKind("OTHER", "PRIVATE"));
    assertEquals(0, RestorePlanProjection.targetMappingKind("STABLE", "OTHER"));
    RestorePlanProjection.Receipt receipt =
        RestorePlanProjection.project(manifest(), 1, digest(), Set.of("application"), 0, NOW);
    assertFalse(receipt.projected());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_MAPPING_KIND, receipt.diagnostic());
  }

  @Test
  public void selectedMaskUsesFrozenBitOrder() {
    RestorePlanProjection.Receipt plugins = project(manifest(), 1, Set.of("plugins"));
    assertEquals(1L << 3, PlanViewCodec.decode(plugins.view()).selectedMask);
    RestorePlanProjection.Receipt everything =
        project(
            manifest(), 1, Set.of("application", "sessions", "settings", "plugins", "projects"));
    assertEquals(31L, PlanViewCodec.decode(everything.view()).selectedMask);
    RestorePlanProjection.Receipt unknownScope = project(manifest(), 1, Set.of("camera"));
    assertFalse(unknownScope.projected());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_SELECTED_MASK, unknownScope.diagnostic());
    RestorePlanProjection.Receipt empty = project(manifest(), 1, Set.of());
    assertFalse(empty.projected());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_SELECTED_MASK, empty.diagnostic());
  }

  @Test
  public void pluginCountTreatsLegacyAndMaintenanceGraphsAsZero() {
    Map<String, Object> legacy = manifest();
    legacy.put(
        "plugins",
        Map.of(
            "legacyVersion", 3L, "declarations", List.of("example"), "dependencyGraph", Map.of()));
    assertEquals(0, PlanViewCodec.decode(project(legacy, 1).view()).pluginCount);
    Map<String, Object> maintenance = manifest();
    maintenance.put("plugins", Map.of("layout", "original-tree", "executed", false));
    assertEquals(0, PlanViewCodec.decode(project(maintenance, 1).view()).pluginCount);
    Map<String, Object> graph = manifest();
    graph.put(
        "plugins",
        Map.of("version", 2L, "nodes", Map.of("a", Map.of(), "b", Map.of(), "c", Map.of())));
    assertEquals(3, PlanViewCodec.decode(project(graph, 1).view()).pluginCount);
  }

  @Test
  public void sourceDigestMustBeThirtyTwoBytes() {
    RestorePlanProjection.Receipt shortDigest =
        RestorePlanProjection.project(
            manifest(),
            1,
            new byte[31],
            Set.of("application"),
            PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE,
            NOW);
    assertFalse(shortDigest.projected());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_SOURCE_DIGEST, shortDigest.diagnostic());
    assertNull(RestorePlanProjection.digestBytes("0"));
    assertNull(RestorePlanProjection.digestBytes("g".repeat(64)));
    assertArrayEquals(digest(), RestorePlanProjection.digestBytes(hex(digest())));
  }

  @Test
  public void submitGateStopsInvalidDivergentAndUnverifiedButAllowsValidImport() throws Exception {
    RestorePlanProjection.requireSubmitSafe(
        RestorePlanProjection.simulated(
            true, RestorePlanProjection.VERDICT_VALID, RestorePlanProjection.DIAGNOSTIC_READY));
    // 未投影（根数 > 64 / 清单不符）不中止：JVM 权威路径继续。
    RestorePlanProjection.requireSubmitSafe(
        RestorePlanProjection.simulated(
            false,
            RestorePlanProjection.VERDICT_UNVERIFIED,
            RestorePlanProjection.DIAGNOSTIC_ROOT_LIMIT));
    assertSubmitStops(RestorePlanProjection.VERDICT_INVALID, RestorePlanProjection.SUBMIT_INVALID);
    assertSubmitStops(
        RestorePlanProjection.VERDICT_DIVERGENT, RestorePlanProjection.SUBMIT_DIVERGENT);
    assertSubmitStops(
        RestorePlanProjection.VERDICT_UNVERIFIED, RestorePlanProjection.SUBMIT_UNVERIFIED);
    IOException missing =
        assertThrows(IOException.class, () -> RestorePlanProjection.requireSubmitSafe(null));
    assertEquals(RestorePlanProjection.SUBMIT_UNVERIFIED, missing.getMessage());
  }

  private static void assertSubmitStops(String verdict, String expectedCode) {
    RestorePlanProjection.Receipt receipt =
        RestorePlanProjection.simulated(true, verdict, RestorePlanProjection.DIAGNOSTIC_INVALID);
    IOException error =
        assertThrows(IOException.class, () -> RestorePlanProjection.requireSubmitSafe(receipt));
    assertEquals(expectedCode, error.getMessage());
  }

  @Test
  public void receiptFactsUseFixedLabelsAndCarryNoFreeText() {
    RestorePlanProjection.Receipt receipt =
        RestorePlanProjection.project(
            manifest(),
            2,
            digest(),
            Set.of("application"),
            PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE,
            NOW);
    Map<String, Object> facts = receipt.facts();
    assertTrue(facts.size() <= 24);
    for (Map.Entry<String, Object> entry : facts.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof String) {
        String text = (String) value;
        assertTrue(entry.getKey() + "=" + text, text.matches("[A-Za-z0-9_.-]{1,128}"));
      }
    }
    assertEquals("VALID", facts.get("verdict"));
    assertEquals("JVM_ONLY", facts.get("backend"));
    assertEquals("SWITCH_OFF", facts.get("selection"));
    assertFalse(facts.toString().contains("/"));
    assertFalse(facts.toString().contains("\n"));
    RestorePlanProjection.Receipt failed = project(manifest(), 65);
    assertEquals("PLAN_VIEW_ROOT_LIMIT", failed.facts().get("diagnostic"));
  }

  @Test
  public void switchStaysOffByDefaultSoRustIsNeverAttempted() {
    assertFalse(RustBackends.enabled());
    RestorePlanProjection.Receipt receipt = project(manifest(), 1);
    assertEquals("SWITCH_OFF", receipt.selection());
    assertEquals(RestorePlanProjection.CHECK_NOT_ATTEMPTED, receipt.rustState());
    assertEquals(RestorePlanProjection.BACKEND_JVM_ONLY, receipt.backend());
  }

  private static String hex(byte[] value) {
    StringBuilder result = new StringBuilder(value.length * 2);
    for (byte element : value) result.append(String.format("%02x", element));
    return result.toString();
  }

  @Test
  public void projectionDoesNotMutateCallerManifest() {
    // NativeRestorePlan.projectionFacts 提供不可变快照；投影器本身也只读，不改调用方清单。
    Map<String, Object> manifest = manifest();
    Map<String, Object> copy = new LinkedHashMap<>(manifest);
    project(manifest, 1);
    assertEquals(copy, manifest);
  }
}
