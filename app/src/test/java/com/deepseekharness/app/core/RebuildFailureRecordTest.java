package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.RebuildDiagnosisText;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;

/** 失败事实必须能被报告按键值解析：错误码、errno、字节进度与目录都不能带空格。 */
public final class RebuildFailureRecordTest {
  @Test
  public void describeKeepsCodeErrnoAndBytes() {
    Throwable failure =
        new IOException(
            "ENVIRONMENT_INSTALL_INCOMPLETE", new IOException("No space left on device"));
    String line =
        RebuildFailureRecord.describe(
            failure,
            new File("/tmp/host-environment-operations/tx"),
            "解压 Ubuntu 与 Node",
            512,
            2048);
    Map<String, String> values = RebuildDiagnosisText.keyValues(line);
    assertEquals("ENVIRONMENT_INSTALL_INCOMPLETE", values.get("code"));
    assertEquals("ENOSPC", values.get("errno"));
    assertEquals("512", values.get("read"));
    assertEquals("2048", values.get("total"));
    assertEquals("/tmp/host-environment-operations/tx", values.get("dir"));
    assertTrue(line.contains("解压 Ubuntu 与 Node"));
    assertTrue(line.length() <= 800);
  }

  @Test
  public void codePrefersAllCapsMessageOverClassNames() {
    assertEquals(
        "COLD_CANDIDATE_OWNERSHIP",
        RebuildFailureRecord.codeOf(new IOException("COLD_CANDIDATE_OWNERSHIP")));
    assertEquals(
        "RUNTIME_READINESS_FAILED",
        RebuildFailureRecord.codeOf(new IOException("RUNTIME_READINESS_FAILED: trial failed")));
    assertEquals("IOException", RebuildFailureRecord.codeOf(new IOException("某个普通错误")));
  }

  @Test
  public void errnoOnlyFromKnownSystemText() {
    assertEquals(
        "EACCES",
        RebuildFailureRecord.errnoOf(
            new IOException("open", new IOException("Permission denied"))));
    assertEquals(
        "", RebuildFailureRecord.errnoOf(new IOException("ENVIRONMENT_INSTALL_INCOMPLETE")));
  }

  @Test
  public void describeToleratesMissingContext() {
    String line = RebuildFailureRecord.describe(new IOException("X_Y"), null, "", -1, -1);
    Map<String, String> values = RebuildDiagnosisText.keyValues(line);
    assertEquals("X_Y", values.get("code"));
    assertFalse(values.containsKey("read"));
    assertFalse(values.containsKey("dir"));
  }
}
