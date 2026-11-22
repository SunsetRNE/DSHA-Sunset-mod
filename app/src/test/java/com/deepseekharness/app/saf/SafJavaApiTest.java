package com.deepseekharness.app.saf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.transfer.DownloadTask;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** 用同一 Java 测试在迁移前后验证 record、静态 ABI、锁与集合契约。 */
public class SafJavaApiTest {
  private static final String URI = "content://test.documents/tree/root";

  private static SafGrantRecord record(String app, String label) {
    return new SafGrantRecord(
        SafGrantRecord.idOf(URI),
        URI,
        app,
        label,
        true,
        true,
        false,
        2,
        SafGrantRecord.State.ACTIVE,
        7,
        19,
        0,
        0,
        null);
  }

  @Test
  public void recordShapeAndComponentOrderRemainJavaRecords() {
    assertEquals(java.lang.Record.class, SafGrantRecord.class.getSuperclass());
    assertTrue(SafGrantRecord.class.isRecord());
    assertTrue(SafGrantRecord.Previous.class.isRecord());
    assertEquals(
        List.of(
            "id",
            "uri",
            "app",
            "label",
            "readable",
            "writable",
            "userWritable",
            "acquired",
            "state",
            "epoch",
            "takenAt",
            "pendingBefore",
            "pendingIntent",
            "previous"),
        Arrays.stream(SafGrantRecord.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());
    assertEquals(
        List.of("app", "label", "readable", "writable", "acquired", "takenAt"),
        Arrays.stream(SafGrantRecord.Previous.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());
  }

  @Test
  public void canonicalNormalizationAndValueSemanticsRemainUnchanged() {
    SafGrantRecord left = record(" \t应用\n", " 目录 ");
    SafGrantRecord right = record("应用", "目录");
    assertEquals(right, left);
    assertEquals(right.hashCode(), left.hashCode());
    int expected = 0;
    for (Object value :
        List.of(
            left.id(),
            URI,
            "应用",
            "目录",
            true,
            true,
            false,
            2,
            SafGrantRecord.State.ACTIVE,
            7L,
            19L,
            0,
            0)) expected = 31 * expected + value.hashCode();
    expected *= 31; // previous=null
    assertEquals(expected, left.hashCode());
    assertEquals(
        "SafGrantRecord[id="
            + left.id()
            + ", uri="
            + URI
            + ", app=应用, label=目录, readable=true, writable=true, userWritable=false, acquired=2, state=ACTIVE, epoch=7, takenAt=19, pendingBefore=0, pendingIntent=0, previous=null]",
        left.toString());
    assertEquals("", record(null, null).app());
    assertEquals("", record(null, null).label());
    assertEquals(300, record("x".repeat(301), "").app().length());
    assertEquals("\u00a0应用\u00a0", record("\u00a0应用\u00a0", "").app());
  }

  @Test
  public void previousSnapshotDoesNotGainMainRecordTrimming() {
    SafGrantRecord.Previous previous =
        new SafGrantRecord.Previous(" app ", null, true, false, 1, 3);
    assertEquals(" app ", previous.app());
    assertEquals("", previous.label());
    assertEquals(
        "Previous[app= app , label=, readable=true, writable=false, acquired=1, takenAt=3]",
        previous.toString());
    assertEquals(previous, new SafGrantRecord.Previous(" app ", "", true, false, 1, 3));
  }

  @Test
  public void explicitConstructorNullChecksKeepTheirOrderAndMessages() {
    NullPointerException uri =
        assertThrows(
            NullPointerException.class,
            () ->
                new SafGrantRecord(
                    null, null, null, null, false, false, false, 0, null, 0, 0, 0, 0, null));
    assertEquals("uri", uri.getMessage());
    NullPointerException id =
        assertThrows(
            NullPointerException.class,
            () ->
                new SafGrantRecord(
                    null, URI, null, null, false, false, false, 0, null, 0, 0, 0, 0, null));
    assertEquals("id", id.getMessage());
    NullPointerException state =
        assertThrows(
            NullPointerException.class,
            () ->
                new SafGrantRecord(
                    SafGrantRecord.idOf(URI),
                    URI,
                    null,
                    null,
                    false,
                    false,
                    false,
                    0,
                    null,
                    0,
                    0,
                    0,
                    0,
                    null));
    assertEquals("state", state.getMessage());
    assertEquals(
        "records",
        assertThrows(NullPointerException.class, () -> SafGrantLedger.of(null, 0, 1)).getMessage());
  }

  @Test
  public void snapshotsStayReadOnlyWhileEncodedMapsStayMutable() {
    SafGrantLedger ledger = SafGrantLedger.of(List.of(record("app", "label")), 1, 8);
    assertThrows(UnsupportedOperationException.class, () -> ledger.records().clear());
    Map<String, Object> encoded = SafLedgerCodec.encode(ledger);
    encoded.put("future", "kept");
    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(encoded);
    assertThrows(UnsupportedOperationException.class, () -> decoded.preserved.clear());
    assertThrows(UnsupportedOperationException.class, () -> decoded.warnings.add("extra"));
    assertEquals("kept", decoded.preserved.get("future"));
  }

  @Test
  public void javaStaticFieldsDefaultMethodAndCheckedExceptionsRemainUsable() throws Exception {
    assertTrue(Modifier.isStatic(SafTransferGate.class.getField("TRANSFERS").getModifiers()));
    assertTrue(Modifier.isFinal(SafTransferGate.class.getField("TRANSFERS").getModifiers()));
    assertEquals(
        DownloadTask.Status.class, SafTransferHistory.Record.class.getField("status").getType());
    assertTrue(SafTransferJob.Ports.class.getMethod("failureCode", Exception.class).isDefault());
    assertEquals(
        List.of(IOException.class),
        Arrays.asList(SafLocalFiles.class.getMethod("openRead", File.class).getExceptionTypes()));
    assertEquals(
        List.of(SafTree.Failure.class),
        Arrays.asList(
            SafTree.class
                .getMethod("read", String.class, long.class, int.class)
                .getExceptionTypes()));
    assertEquals(
        List.of(Exception.class),
        Arrays.asList(
            SafWorkbench.class
                .getMethod("transfer", SafWorkbench.Ticket.class, String.class, boolean.class)
                .getExceptionTypes()));
    for (String name :
        List.of("issue", "check", "consume", "discard", "revokeTree", "purgeExpired", "size")) {
      assertTrue(
          Arrays.stream(SafCapabilityHandles.class.getDeclaredMethods())
              .anyMatch(
                  method ->
                      method.getName().equals(name)
                          && Modifier.isSynchronized(method.getModifiers())));
    }
  }

  @Test
  public void oneTimeConsumptionRemainsAtomicAcrossThreads() throws Exception {
    SafGrantLedger ledger =
        SafGrantLedger.of(
            List.of(
                new SafGrantRecord(
                    SafGrantRecord.idOf(URI),
                    URI,
                    "",
                    "",
                    true,
                    true,
                    true,
                    3,
                    SafGrantRecord.State.ACTIVE,
                    1,
                    0,
                    0,
                    0,
                    null)),
            0,
            2);
    SafCapabilityHandles handles = new SafCapabilityHandles(() -> "one-token");
    String token =
        handles.issue(
                ledger, SafGrantRecord.idOf(URI), SafCapabilityHandles.Operation.DELETE, "file", 0)
            .token;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(8);
    AtomicInteger accepted = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(8);
    try {
      for (int index = 0; index < 8; index++)
        executor.execute(
            () -> {
              try {
                start.await();
                if (handles.consume(ledger, token, SafCapabilityHandles.Operation.DELETE, "file", 1)
                    == SafCapabilityHandles.Result.OK) accepted.incrementAndGet();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      start.countDown();
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertEquals(1, accepted.get());
      assertEquals(0, handles.size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void tokenSourceReentryKeepsTheOriginalInstanceMonitor() {
    SafCapabilityHandles[] holder = new SafCapabilityHandles[1];
    holder[0] =
        new SafCapabilityHandles(
            () -> {
              assertTrue(Thread.holdsLock(holder[0]));
              assertEquals(0, holder[0].size());
              return "reentrant";
            });
    SafGrantLedger ledger = SafGrantLedger.of(List.of(record("", "")), 0, 8);
    assertTrue(
        holder[0]
            .issue(ledger, SafGrantRecord.idOf(URI), SafCapabilityHandles.Operation.READ, "file", 0)
            .ok());
  }

  @Test
  public void javaWhitespaceAndNullPathsKeepTheirOldMeaning() {
    assertEquals("", SafPaths.normalize(null));
    assertEquals("\u00a0file\u00a0", SafPaths.normalize("\u00a0file\u00a0"));
    assertNull(SafCapabilityLimits.uriProblem("\u00a0"));
    assertEquals(SafCapabilityLimits.URI_MISSING, SafCapabilityLimits.uriProblem("\u2000"));
    assertFalse(SafPaths.looksText(null));
    assertTrue(SafPaths.looksText(new byte[0]));
    assertSame(SafGrantRecord.State.ACTIVE, record("", "").state());
  }
}
