package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 环境重建报告：只按证据归类，未知保持未知；正文有界且可整段复制。 */
public final class RebuildDiagnosisTextTest {
  private static RebuildDiagnosisText.Facts facts() {
    RebuildDiagnosisText.Facts facts = new RebuildDiagnosisText.Facts();
    facts.generated = "2026-11-11 12:00:00";
    facts.versionName = "0.2.1-rc.5";
    facts.versionCode = "358";
    facts.device = "vivo X300s";
    facts.api = "Android 15 (API 35)";
    facts.abi = "arm64-v8a";
    facts.status = "FAILED";
    facts.stage = "解压内置环境";
    facts.substage = "解压 Ubuntu 与 Node";
    return facts;
  }

  @Test
  public void classifyUsesErrnoAndSpaceEvidence() {
    RebuildDiagnosisText.Facts facts = facts();
    facts.errno = "ENOSPC";
    assertEquals(RebuildDiagnosisText.Cause.NO_SPACE, RebuildDiagnosisText.classify(facts));

    RebuildDiagnosisText.Facts numbered = facts();
    numbered.required = 10L * 1024 * 1024 * 1024;
    numbered.available = 1024L * 1024 * 1024;
    assertEquals(RebuildDiagnosisText.Cause.NO_SPACE, RebuildDiagnosisText.classify(numbered));

    RebuildDiagnosisText.Facts unknownSpace = facts();
    unknownSpace.required = -1;
    assertNotEquals(
        RebuildDiagnosisText.Cause.NO_SPACE, RebuildDiagnosisText.classify(unknownSpace));
  }

  @Test
  public void classifySeparatesPermissionPackageRuntimeAndEnvironment() {
    RebuildDiagnosisText.Facts facts = facts();
    facts.errno = "EACCES";
    assertEquals(RebuildDiagnosisText.Cause.PERMISSION, RebuildDiagnosisText.classify(facts));

    facts = facts();
    facts.code = "COLD_CANDIDATE_OWNERSHIP";
    assertEquals(RebuildDiagnosisText.Cause.PERMISSION, RebuildDiagnosisText.classify(facts));

    facts = facts();
    facts.code = "RUNTIME_READINESS_FAILED";
    assertEquals(RebuildDiagnosisText.Cause.RUNTIME, RebuildDiagnosisText.classify(facts));

    facts = facts();
    facts.code = "ENVIRONMENT_INSTALL_INCOMPLETE";
    assertEquals(RebuildDiagnosisText.Cause.ENVIRONMENT, RebuildDiagnosisText.classify(facts));

    facts = facts();
    facts.code = "COLD_BUNDLE_CRC";
    assertEquals(RebuildDiagnosisText.Cause.PACKAGE, RebuildDiagnosisText.classify(facts));

    facts = facts();
    facts.bytesRead = 10;
    facts.bytesTotal = 100;
    assertEquals(RebuildDiagnosisText.Cause.PACKAGE, RebuildDiagnosisText.classify(facts));

    assertEquals(RebuildDiagnosisText.Cause.UNKNOWN, RebuildDiagnosisText.classify(facts()));
  }

  @Test
  public void buildCarriesStageAndFailureFacts() {
    RebuildDiagnosisText.Facts facts = facts();
    facts.code = "ENVIRONMENT_INSTALL_INCOMPLETE";
    facts.errno = "ENOSPC";
    facts.path = "/data/data/dsh.android.client/files/host-environment-operations/df3f";
    facts.bytesRead = 512L * 1024 * 1024;
    facts.bytesTotal = 2L * 1024 * 1024 * 1024;
    facts.required = 9L * 1024 * 1024 * 1024;
    facts.available = 3L * 1024 * 1024 * 1024;
    facts.spaceAt = "SNAPSHOT_READY";
    String report = RebuildDiagnosisText.build(facts);
    assertTrue(report.contains("substage=解压 Ubuntu 与 Node"));
    assertTrue(report.contains("code=ENVIRONMENT_INSTALL_INCOMPLETE"));
    assertTrue(report.contains("errno=ENOSPC"));
    assertTrue(report.contains("/host-environment-operations/df3f"));
    assertTrue(report.contains("required="));
    assertTrue(report.contains("采样=SNAPSHOT_READY"));
    assertTrue(
        report.contains(
            "结论: " + RebuildDiagnosisText.causeLabel(RebuildDiagnosisText.Cause.NO_SPACE)));
    assertTrue(report.contains("建议: "));
  }

  @Test
  public void buildIsBoundedEvenWithHugeEvidence() {
    RebuildDiagnosisText.Facts facts = facts();
    StringBuilder noise = new StringBuilder();
    for (int i = 0; i < 600; i++) noise.append('x');
    List<String> items = new ArrayList<>();
    for (int i = 0; i < 60; i++) items.add("stage-" + i + " " + noise);
    facts.stages = items;
    facts.spaces = items;
    String report = RebuildDiagnosisText.build(facts);
    assertTrue(report.length() <= RebuildDiagnosisText.LIMIT);
    // 列表只保留最近 MAX_ITEMS 条。
    assertTrue(report.contains("stage-59"));
    assertFalse(report.contains("stage-0 "));
  }

  @Test
  public void adviceNeverEmpty() {
    for (RebuildDiagnosisText.Cause cause : RebuildDiagnosisText.Cause.values()) {
      assertFalse(RebuildDiagnosisText.causeLabel(cause).isEmpty());
      assertFalse(RebuildDiagnosisText.advice(cause).isEmpty());
    }
  }

  @Test
  public void keyValuesIgnoresMalformedTokens() {
    Map<String, String> values =
        RebuildDiagnosisText.keyValues(
            "code=NO_SPACE errno=ENOSPC read=10 total=100 dir=/tmp/x =bad bad= | detail text");
    assertEquals("NO_SPACE", values.get("code"));
    assertEquals("ENOSPC", values.get("errno"));
    assertEquals("10", values.get("read"));
    assertEquals("/tmp/x", values.get("dir"));
    assertFalse(values.containsKey("bad"));
    assertEquals(5, values.size());
  }

  @Test
  public void errnoOfOnlyMapsKnownSystemText() {
    assertEquals("ENOSPC", RebuildDiagnosisText.errnoOf("No space left on device"));
    assertEquals("EACCES", RebuildDiagnosisText.errnoOf("Permission denied"));
    assertEquals("EROFS", RebuildDiagnosisText.errnoOf("Read-only file system"));
    assertEquals("", RebuildDiagnosisText.errnoOf("ENVIRONMENT_INSTALL_INCOMPLETE: something"));
    assertEquals("", RebuildDiagnosisText.errnoOf(null));
  }

  @Test
  public void clipFlattensControlCharacters() {
    assertEquals("a b c", RebuildDiagnosisText.clip("a\nb\tc", 40));
    assertEquals(5, RebuildDiagnosisText.clip("1234567890", 5).length());
    assertEquals("", RebuildDiagnosisText.clip(null, 10));
  }
}
