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
 * A3 slice2/3 投影器与提交期闸门（A3 任务基线 §3、§4、§10）。
 *
 * <p>覆盖：确定性编码、{@code planDigest} 口径、根数上限不投影（Q1-A）、清单类型/范围/编码不可表示
 * 时不伪造默认值（P-4）、未知 {@code sensitivePolicy} 必须编码后判 INVALID（Q2-A、评审 P1-1）、
 * 映射 2×2 编码、选择位图位序、旧包/维护快照的插件节点数、u64 负值拒绝（P2-2）、以及
 * 「INVALID/分歧/单侧失败/校验异常必须中止」的提交期 fail-closed（P-5、T32、P2-1/P2-3）。
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

  /** 包内测试缝：注入 Rust 返回，JVM 校验与投影/闸门逻辑仍真实执行。 */
  private static RestorePlanProjection.Receipt projectWithRust(
      Map<String, Object> manifest, RestorePlanProjection.RustResult injected) {
    return RestorePlanProjection.project(
        manifest,
        1,
        digest(),
        Set.of("application"),
        PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE,
        NOW,
        injected);
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
  public void exactlySixtyFourRootsPassesButSixtyFiveIsSkipped() throws IOException {
    RestorePlanProjection.Receipt limit = project(manifest(), 64);
    assertTrue(limit.projected());
    assertEquals(RestorePlanProjection.VERDICT_VALID, limit.verdict());
    assertEquals(64, PlanViewCodec.decode(limit.view()).rootCount);
    assertEquals(64L, limit.facts().get("rootCount"));
    RestorePlanProjection.requireSubmitSafe(limit);

    RestorePlanProjection.Receipt overLimit = project(manifest(), 65);
    assertFalse(overLimit.projected());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_ROOT_LIMIT, overLimit.diagnostic());
    assertEquals(RestorePlanProjection.VERDICT_SKIPPED, overLimit.verdict());
    assertEquals(RestorePlanProjection.BACKEND_JVM_ONLY, overLimit.backend());
    assertNull(overLimit.view());
    assertNull(overLimit.planDigest());
    assertFalse(overLimit.facts().containsKey("planDigest"));
    // Q1-A：根数 > 64 不投影、不调 Rust，JVM 权威恢复继续（提交期闸门放行）。
    RestorePlanProjection.requireSubmitSafe(overLimit);
    assertEquals(RestorePlanProjection.DIAGNOSTIC_COUNTS, project(manifest(), 0).diagnostic());
  }

  @Test
  public void unknownSensitivePolicyIsEncodedThenJudgedInvalidNotSkipped() throws IOException {
    Map<String, Object> manifest = manifest();
    manifest.put("sensitivePolicy", "FUTURE_POLICY");
    RestorePlanProjection.Receipt receipt = project(manifest, 1);
    // Q2-A / P1-1：可编码的未知值必须让 verify_plan 看到 → FIELD_RANGE → INVALID → 提交期中止。
    assertTrue(receipt.projected());
    assertEquals(RestorePlanProjection.VERDICT_INVALID, receipt.verdict());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_INVALID, receipt.diagnostic());
    assertTrue(receipt.jvmReasons().length > 0);
    assertTrue(
        Arrays.stream(receipt.jvmReasons())
            .anyMatch(code -> code == PlanViewValidator.REASON_FIELD_RANGE));
    assertEquals("FUTURE_POLICY", PlanViewCodec.decode(receipt.view()).sensitivePolicy);
    IOException error =
        assertThrows(IOException.class, () -> RestorePlanProjection.requireSubmitSafe(receipt));
    assertEquals(RestorePlanProjection.SUBMIT_INVALID, error.getMessage());
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
    Map<String, Object> negativeTimestamp = manifest();
    negativeTimestamp.put("createdAt", -1L);
    Map<String, Object> oversizedAppVersion = manifest();
    oversizedAppVersion.put("appVersion", "x".repeat(257));
    Map<String, Object> emptyDataFormat = manifest();
    emptyDataFormat.put("dataFormat", "");
    Map<String, Object> nulAppVersion = manifest();
    nulAppVersion.put("appVersion", "a\u0000b");
    Map<String, Object> nulSensitivePolicy = manifest();
    nulSensitivePolicy.put("sensitivePolicy", "UNENCRYPTED\u0000");
    Map<String, Object> tooManyPlugins = manifest();
    Map<String, Object> nodes = new LinkedHashMap<>();
    for (int index = 0; index <= PlanViewCodec.MAX_PLUGIN_COUNT; index++)
      nodes.put("package-" + index, Map.of());
    tooManyPlugins.put("plugins", Map.of("version", 2L, "nodes", nodes));
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_FORMAT_VERSION, wrongFormat);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_OPERATION, wrongOperation);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_INTEGRITY, wrongIntegrity);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_COUNTS, wrongEntries);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_COUNTS, wrongBytes);
    // P2-2：u64 字段拒绝 Java long 负数，不能把补码写成巨大 u64 后被校验器放行。
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_TIMESTAMP, negativeTimestamp);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_APP_VERSION, oversizedAppVersion);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_DATA_FORMAT, emptyDataFormat);
    // 编码不可表示（NUL）→ 不投影继续；可编码的未知 sensitivePolicy 走 INVALID（见上一测试）。
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_APP_VERSION, nulAppVersion);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_SENSITIVE_POLICY, nulSensitivePolicy);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_PLUGIN_COUNT, tooManyPlugins);
    // 类型不符（P-4）：V5 读者只查键存在，投影器必须拒绝，不能编造默认值。
    Map<String, Object> stringTimestamp = manifest();
    stringTimestamp.put("createdAt", "yesterday");
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_TIMESTAMP, stringTimestamp);
    Map<String, Object> fractionalTimestamp = manifest();
    fractionalTimestamp.put("createdAt", new BigDecimal("123.5"));
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_TIMESTAMP, fractionalTimestamp);
    Map<String, Object> listPlugins = manifest();
    listPlugins.put("plugins", List.of("not-a-graph"));
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_PLUGIN_COUNT, listPlugins);
    assertSkipped(RestorePlanProjection.DIAGNOSTIC_MANIFEST, null);
  }

  private static void assertSkipped(String diagnostic, Map<String, Object> manifest) {
    RestorePlanProjection.Receipt receipt = project(manifest, 1);
    assertFalse(receipt.projected());
    assertEquals(diagnostic, receipt.diagnostic());
    assertEquals(RestorePlanProjection.VERDICT_SKIPPED, receipt.verdict());
    try {
      // 编码不可表示/无关计划不中止恢复（Q1-A、P-4）。
      RestorePlanProjection.requireSubmitSafe(receipt);
    } catch (IOException unexpected) {
      fail("skipped receipt must not block submit: " + unexpected.getMessage());
    }
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
    assertEquals(RestorePlanProjection.VERDICT_SKIPPED, receipt.verdict());
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
  public void invalidReceiptStopsSubmitButValidAndSkippedPass() throws Exception {
    RestorePlanProjection.requireSubmitSafe(project(manifest(), 1));
    RestorePlanProjection.requireSubmitSafe(project(manifest(), 65));
    Map<String, Object> unknownPolicy = manifest();
    unknownPolicy.put("sensitivePolicy", "FUTURE_POLICY");
    IOException invalid =
        assertThrows(
            IOException.class,
            () -> RestorePlanProjection.requireSubmitSafe(project(unknownPolicy, 1)));
    assertEquals(RestorePlanProjection.SUBMIT_INVALID, invalid.getMessage());
    IOException missing =
        assertThrows(IOException.class, () -> RestorePlanProjection.requireSubmitSafe(null));
    assertEquals(RestorePlanProjection.SUBMIT_UNVERIFIED, missing.getMessage());
  }

  @Test
  public void injectedRustSeamCoversDivergentAndUnverifiedFailClosedBranches() throws Exception {
    // 双实现一致通过：注入 Rust VALID，证明测试缝没有跳过 JVM 校验。
    RestorePlanProjection.Receipt agreeing =
        projectWithRust(manifest(), new RestorePlanProjection.RustResult(0, new int[0]));
    assertTrue(agreeing.projected());
    assertEquals(RestorePlanProjection.BACKEND_JVM_AND_RUST, agreeing.backend());
    assertEquals(RestorePlanProjection.SELECTION_INJECTED, agreeing.selection());
    assertEquals(RestorePlanProjection.VERDICT_VALID, agreeing.verdict());
    assertTrue(PlanViewValidator.validate(agreeing.view()).valid());

    // 布尔分歧：JVM VALID / Rust INVALID。
    RestorePlanProjection.Receipt divergent =
        projectWithRust(
            manifest(),
            new RestorePlanProjection.RustResult(
                1, new int[] {PlanViewValidator.REASON_FIELD_RANGE}));
    assertEquals(RestorePlanProjection.VERDICT_DIVERGENT, divergent.verdict());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_DIVERGENT, divergent.diagnostic());
    assertEquals(RestorePlanProjection.CHECK_INVALID, divergent.rustState());
    IOException divergentError =
        assertThrows(IOException.class, () -> RestorePlanProjection.requireSubmitSafe(divergent));
    assertEquals(RestorePlanProjection.SUBMIT_DIVERGENT, divergentError.getMessage());

    // 原因集合分歧：都 VALID 但 Rust 带了原因码（集合不等）。
    RestorePlanProjection.Receipt reasonMismatch =
        projectWithRust(
            manifest(),
            new RestorePlanProjection.RustResult(
                0, new int[] {PlanViewValidator.REASON_FIELD_RANGE}));
    assertEquals(RestorePlanProjection.VERDICT_DIVERGENT, reasonMismatch.verdict());

    // Rust 内部错误（PANIC 负码）→ UNVERIFIED → 中止；不得按 §5.4 用 JVM 重做继续。
    RestorePlanProjection.Receipt unavailable =
        projectWithRust(manifest(), new RestorePlanProjection.RustResult(-7, null));
    assertEquals(RestorePlanProjection.VERDICT_UNVERIFIED, unavailable.verdict());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_UNVERIFIED, unavailable.diagnostic());
    assertEquals(RestorePlanProjection.CHECK_FAILED, unavailable.rustState());
    IOException unavailableError =
        assertThrows(IOException.class, () -> RestorePlanProjection.requireSubmitSafe(unavailable));
    assertEquals(RestorePlanProjection.SUBMIT_UNVERIFIED, unavailableError.getMessage());
  }

  @Test
  public void submissionFailureMapsEveryVerdictFailClosed() {
    assertNull(RestorePlanProjection.submissionFailure(RestorePlanProjection.VERDICT_VALID));
    assertNull(RestorePlanProjection.submissionFailure(RestorePlanProjection.VERDICT_SKIPPED));
    assertEquals(
        RestorePlanProjection.SUBMIT_INVALID,
        RestorePlanProjection.submissionFailure(RestorePlanProjection.VERDICT_INVALID));
    assertEquals(
        RestorePlanProjection.SUBMIT_DIVERGENT,
        RestorePlanProjection.submissionFailure(RestorePlanProjection.VERDICT_DIVERGENT));
    assertEquals(
        RestorePlanProjection.SUBMIT_UNVERIFIED,
        RestorePlanProjection.submissionFailure(RestorePlanProjection.VERDICT_UNVERIFIED));
    // 未知/空结论也按 INVALID 拒绝（fail-closed 兜底）。
    assertEquals(
        RestorePlanProjection.SUBMIT_INVALID,
        RestorePlanProjection.submissionFailure("UNEXPECTED"));
    assertEquals(
        RestorePlanProjection.SUBMIT_INVALID, RestorePlanProjection.submissionFailure(null));
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
    assertEquals("ALLOWED", facts.get("submission"));
    assertEquals("JVM_ONLY", facts.get("backend"));
    assertEquals("SWITCH_OFF", facts.get("selection"));
    assertFalse(facts.toString().contains("/"));
    assertFalse(facts.toString().contains("\n"));
    RestorePlanProjection.Receipt skipped = project(manifest(), 65);
    assertEquals("PLAN_VIEW_ROOT_LIMIT", skipped.facts().get("diagnostic"));
    assertEquals("SKIPPED", skipped.facts().get("verdict"));
    assertEquals("ALLOWED", skipped.facts().get("submission"));
    Map<String, Object> unknownPolicy = manifest();
    unknownPolicy.put("sensitivePolicy", "FUTURE_POLICY");
    RestorePlanProjection.Receipt invalid = project(unknownPolicy, 1);
    assertEquals("BLOCKED", invalid.facts().get("submission"));
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
