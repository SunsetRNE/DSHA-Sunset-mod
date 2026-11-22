package com.deepseekharness.app.transfer;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** 以真实 Java 直接字段、静态入口、同包构造和 record 消费生产类。 */
public final class TransferJavaApiTest {
  private static TransferArtifact artifact() throws IOException {
    return new TransferArtifact("id-1", "报告.txt", "TEXT/PLAIN", 7, "AB".repeat(32), "v1");
  }

  @Test
  public void directFieldsStaticCallsAndCheckedExceptionsRetainJavaShape() throws Exception {
    TransferArtifact value = artifact();
    assertEquals("id-1", value.id);
    assertEquals("报告.txt", value.name);
    assertEquals("text/plain", value.mime);
    assertEquals("ab".repeat(32), value.sha256);
    assertEquals("v1", value.version);
    assertEquals(7, value.bytes);
    assertEquals(5_000_000_000L, TransferArtifact.MAX_BYTES);
    assertNotNull(TransferFiles.FS);
    for (String name : new String[] {"id", "name", "mime", "sha256", "version", "bytes"}) {
      int flags = TransferArtifact.class.getField(name).getModifiers();
      assertTrue(Modifier.isPublic(flags));
      assertTrue(Modifier.isFinal(flags));
      assertFalse(Modifier.isStatic(flags));
    }
    assertArrayEquals(
        new Class<?>[] {IOException.class},
        TransferArtifact.class
            .getConstructor(
                String.class, String.class, String.class, long.class, String.class, String.class)
            .getExceptionTypes());
    assertArrayEquals(
        new Class<?>[] {IOException.class},
        TransferArtifact.class
            .getMethod("requireSame", TransferArtifact.class)
            .getExceptionTypes());
    assertArrayEquals(
        new Class<?>[] {Exception.class},
        TransferArtifact.class
            .getMethod("fromJson", org.json.JSONObject.class)
            .getExceptionTypes());
    assertArrayEquals(
        new Class<?>[] {Exception.class},
        TransferArtifact.class.getMethod("toJson").getExceptionTypes());
    assertTrue(
        Modifier.isStatic(
            TransferArtifact.class.getMethod("safeName", String.class).getModifiers()));
  }

  @Test
  public void constructorNullsKeepDomainExceptionsAndValidationOrder() throws Exception {
    String[] valid = {"id", "a", "text/plain", "ab".repeat(32), "v1"};
    String[] codes = {
      "FILE_ARTIFACT_ID",
      "FILE_NAME_INVALID",
      "FILE_MIME_INVALID",
      "FILE_SHA256_INVALID",
      "FILE_VERSION_INVALID"
    };
    for (int index = 0; index < valid.length; index++) {
      String[] fields = valid.clone();
      fields[index] = null;
      assertEquals(
          codes[index],
          assertThrows(
                  IOException.class,
                  () ->
                      new TransferArtifact(
                          fields[0], fields[1], fields[2], 0, fields[3], fields[4]))
              .getMessage());
    }
    assertEquals(
        "FILE_ARTIFACT_ID",
        assertThrows(
                IOException.class, () -> new TransferArtifact(null, null, null, -1, null, null))
            .getMessage());
    assertEquals(
        "FILE_SOURCE_CHANGED",
        assertThrows(IOException.class, () -> artifact().requireSame(null)).getMessage());
    assertEquals(
        "FILE_ARTIFACT_MISSING",
        assertThrows(IOException.class, () -> TransferArtifact.fromJson(null)).getMessage());
  }

  @Test
  public void safeNameUsesJavaWhitespaceAndUtf8Limits() {
    for (String value :
        new String[] {
          null,
          "",
          " ",
          "\t",
          "\u2003",
          "\u00a0",
          "\u2007",
          "\u202f",
          "x\u007f",
          "x/y",
          "x\\y",
          ".",
          "..",
          "图".repeat(80),
          "图".repeat(81),
          "a".repeat(240),
          "a".repeat(241)
        }) {
      boolean expected =
          value != null
              && !value.isBlank()
              && value.length() <= 240
              && value.getBytes(StandardCharsets.UTF_8).length <= 240
              && !value.equals(".")
              && !value.equals("..")
              && value.chars().noneMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\');
      assertEquals(String.valueOf(value), expected, TransferArtifact.safeName(value));
    }
  }

  @Test
  public void snapshotKeepsNullableFieldsAndReferenceIdentity() throws Exception {
    TransferArtifact artifact = artifact();
    DownloadTask task =
        new DownloadTask(null, artifact, null, null, 3, 9, null, null, null, null, null, 42);
    assertNull(task.id);
    assertNull(task.destination);
    assertNull(task.status);
    assertNull(task.message);
    assertNull(task.uri);
    assertNull(task.displayName);
    assertNull(task.location);
    assertNull(task.mime);
    assertSame(artifact, task.artifact);
    assertEquals(7, task.total);
    assertEquals(3, task.downloaded);
    assertEquals(9, task.speedBytesPerSecond);
    assertEquals(42, task.createdAt);
    assertFalse(
        task.equals(
            new DownloadTask(null, artifact, null, null, 3, 9, null, null, null, null, null, 42)));
    assertArrayEquals(
        new String[] {
          "QUEUED",
          "DOWNLOADING",
          "VERIFYING",
          "PUBLISHING",
          "WAITING_DESTINATION",
          "PAUSED",
          "FAILED",
          "COMPLETE",
          "CANCELLED"
        },
        Arrays.stream(DownloadTask.Status.values()).map(Enum::name).toArray(String[]::new));
  }

  @Test
  public void stateWrapsOriginalListWithoutCopyingOrAllowingMutation() throws Exception {
    List<DownloadTask> tasks = new ArrayList<>();
    DownloadEngine.State value = new DownloadEngine.State(tasks, true, null);
    assertTrue(value.busy);
    assertNull(value.activeId);
    assertThrows(UnsupportedOperationException.class, () -> value.tasks.add(null));
    tasks.add(
        new DownloadTask(
            "task",
            artifact(),
            "share",
            DownloadTask.Status.QUEUED,
            0,
            0,
            "",
            "",
            "a",
            "",
            "text/plain",
            1));
    assertEquals(1, value.tasks.size());
  }

  @Test
  public void publicationResultRetainsRecordComponentsAndValueSemantics() {
    TransferPublisher.Result first = new TransferPublisher.Result(null, "a", null, "where");
    TransferPublisher.Result same = new TransferPublisher.Result(null, "a", null, "where");
    assertTrue(TransferPublisher.Result.class.isRecord());
    assertEquals(java.lang.Record.class, TransferPublisher.Result.class.getSuperclass());
    assertArrayEquals(
        new String[] {"uri", "name", "mime", "location"},
        Arrays.stream(TransferPublisher.Result.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toArray(String[]::new));
    assertNull(first.uri());
    assertEquals("a", first.name());
    assertNull(first.mime());
    assertEquals("where", first.location());
    assertEquals(first, same);
    assertEquals(first.hashCode(), same.hashCode());
    assertEquals("Result[uri=null, name=a, mime=null, location=where]", first.toString());
    assertNotEquals(first, new TransferPublisher.Result(null, "b", null, "where"));
  }
}
