package com.deepseekharness.app.saf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 授权簿状态机：PENDING→ACTIVE、限额、撤销代次与系统位刷新。 */
public class SafGrantLedgerTest {
  private static final String URI = SafTestSupport.DOWNLOADS;

  @Test
  public void beginTakeCreatesPendingRecordWithFreshEpoch() {
    SafGrantLedger empty = SafGrantLedger.empty();

    SafGrantLedger.Change change =
        empty.beginTake(
            URI,
            "Files",
            "Downloads",
            SafGrantRecord.READ,
            SafGrantRecord.READ | SafGrantRecord.WRITE,
            5_000L);

    assertTrue(change.accepted());
    assertEquals(1, change.ledger.revision());
    assertEquals(2, change.ledger.nextEpoch());
    SafGrantRecord record = change.ledger.findByUri(URI);
    assertNotNull(record);
    assertEquals(SafGrantRecord.idOf(URI), record.id());
    assertEquals(SafGrantRecord.State.PENDING, record.state());
    assertEquals(1, record.epoch());
    assertFalse(record.readable());
    assertFalse(record.writable());
    assertTrue(record.userWritable());
    assertEquals(SafGrantRecord.READ, record.pendingBefore());
    assertEquals(SafGrantRecord.READ | SafGrantRecord.WRITE, record.pendingIntent());
  }

  @Test
  public void commitTakeActivatesRecordWithTakenAndAcquiredFlags() {
    SafGrantLedger ledger =
        SafGrantLedger.empty()
            .beginTake(
                URI,
                "Files",
                "Downloads",
                SafGrantRecord.READ,
                SafGrantRecord.READ | SafGrantRecord.WRITE,
                1_000L)
            .ledger;

    SafGrantLedger.Change committed =
        ledger.commitTake(
            URI, SafGrantRecord.READ | SafGrantRecord.WRITE, SafGrantRecord.WRITE, true, 2_000L);

    assertTrue(committed.accepted());
    SafGrantRecord record = committed.ledger.findByUri(URI);
    assertEquals(SafGrantRecord.State.ACTIVE, record.state());
    assertTrue(record.canRead());
    assertTrue(record.canWrite());
    assertTrue(record.usableWrite());
    assertEquals(SafGrantRecord.WRITE, record.acquired());
    assertEquals(2_000L, record.takenAt());
    assertEquals(1, record.epoch());
    assertEquals(0, record.pendingBefore());
    assertEquals(0, record.pendingIntent());
    assertEquals(2, committed.ledger.revision());
  }

  @Test
  public void commitWithoutPendingIsRejectedWithoutStateChange() {
    SafGrantLedger empty = SafGrantLedger.empty();
    SafGrantLedger.Change unknown = empty.commitTake(URI, SafGrantRecord.READ, 0, true, 2_000L);
    assertEquals(SafGrantLedger.UNKNOWN_GRANT, unknown.failure);
    assertEquals(0, unknown.ledger.revision());

    SafGrantLedger ledger = SafTestSupport.single(URI, SafGrantRecord.READ);
    SafGrantLedger.Change committed = ledger.commitTake(URI, SafGrantRecord.READ, 0, true, 2_000L);
    assertEquals(SafGrantLedger.BAD_STATE, committed.failure);
    assertEquals(ledger.revision(), committed.ledger.revision());
    assertEquals(SafGrantRecord.State.ACTIVE, committed.ledger.findByUri(URI).state());
  }

  @Test
  public void beginTakeLimitsTreesButAllowsRetakingAnExistingOne() {
    SafGrantLedger ledger = SafGrantLedger.empty();
    for (int index = 0; index < SafCapabilityLimits.MAX_TREES; index++)
      ledger = ledger.beginTake(SafTestSupport.treeUri(index), "app", "label", 0, 1, 1_000L).ledger;
    long revision = ledger.revision();

    SafGrantLedger.Change ninth =
        ledger.beginTake(SafTestSupport.treeUri(99), "app", "label", 0, 1, 1_000L);
    assertEquals(SafGrantLedger.LIMIT, ninth.failure);
    assertEquals(revision, ninth.ledger.revision());
    assertEquals(SafCapabilityLimits.MAX_TREES, ninth.ledger.size());

    SafGrantLedger.Change retake =
        ledger.beginTake(SafTestSupport.treeUri(0), "app", "label", 0, 1, 1_000L);
    assertTrue(retake.accepted());
    assertEquals(SafCapabilityLimits.MAX_TREES, retake.ledger.size());
    assertTrue(retake.ledger.findByUri(SafTestSupport.treeUri(0)).epoch() > 1);
  }

  @Test
  public void abortTakeRemovesPendingAndIsIdempotent() {
    SafGrantLedger pending =
        SafGrantLedger.empty()
            .beginTake(URI, "app", "label", 0, SafGrantRecord.READ, 1_000L)
            .ledger;

    SafGrantLedger.Change aborted = pending.abortTake(URI);
    assertTrue(aborted.accepted());
    assertNull(aborted.ledger.findByUri(URI));
    assertEquals(pending.revision() + 1, aborted.ledger.revision());

    SafGrantLedger.Change again = aborted.ledger.abortTake(URI);
    assertTrue(again.accepted());
    assertEquals(aborted.ledger.revision(), again.ledger.revision());
  }

  @Test
  public void abortTakeDoesNotRemoveActiveGrant() {
    SafGrantLedger ledger = SafTestSupport.single(URI, SafGrantRecord.READ);

    SafGrantLedger.Change aborted = ledger.abortTake(URI);

    assertEquals(SafGrantLedger.BAD_STATE, aborted.failure);
    assertNotNull(aborted.ledger.findByUri(URI));
  }

  @Test
  public void forgetRemovesRecordAndMakesOldIdUnresolvable() {
    SafGrantLedger ledger = SafTestSupport.single(URI, SafGrantRecord.READ | SafGrantRecord.WRITE);
    SafGrantRecord record = ledger.findByUri(URI);

    SafGrantLedger.Change forgotten = ledger.forget(URI);

    assertTrue(forgotten.accepted());
    assertNull(forgotten.ledger.findByUri(URI));
    assertNull(forgotten.ledger.findById(record.id()));
    assertEquals(ledger.revision() + 1, forgotten.ledger.revision());
    assertEquals(ledger.nextEpoch(), forgotten.ledger.nextEpoch());

    SafGrantLedger.Change again = forgotten.ledger.forget(URI);
    assertTrue(again.accepted());
    assertEquals(forgotten.ledger.revision(), again.ledger.revision());
  }

  @Test
  public void retakenTreeGetsNewEpochSoOldEpochCannotResolve() {
    SafGrantLedger first = SafTestSupport.single(URI, SafGrantRecord.READ);
    long oldEpoch = first.findByUri(URI).epoch();

    SafGrantLedger retaken =
        first.beginTake(URI, "app", "label", SafGrantRecord.READ, SafGrantRecord.READ, 2_000L)
            .ledger;
    SafGrantLedger activated = retaken.commitTake(URI, SafGrantRecord.READ, 0, true, 3_000L).ledger;

    long newEpoch = activated.findByUri(URI).epoch();
    assertTrue("重新授权必须换新代次", newEpoch > oldEpoch);
    assertEquals(SafGrantRecord.State.ACTIVE, activated.findByUri(URI).state());
  }

  @Test
  public void userWritePreferenceIsScopedToTheGrant() {
    SafGrantLedger ledger =
        SafTestSupport.ledger(
            SafTestSupport.active(URI, SafGrantRecord.READ | SafGrantRecord.WRITE, 1),
            SafTestSupport.active(
                SafTestSupport.DOCUMENTS, SafGrantRecord.READ | SafGrantRecord.WRITE, 2));

    SafGrantLedger.Change disabled = ledger.setUserWritable(URI, false);

    assertTrue(disabled.accepted());
    assertFalse(disabled.ledger.findByUri(URI).usableWrite());
    assertTrue(disabled.ledger.findByUri(URI).canWrite());
    assertTrue(disabled.ledger.findByUri(SafTestSupport.DOCUMENTS).usableWrite());
    SafGrantLedger.Change repeated = disabled.ledger.setUserWritable(URI, false);
    assertTrue(repeated.accepted());
    assertEquals(disabled.ledger.revision(), repeated.ledger.revision());
  }

  @Test
  public void refreshSystemFlagsKeepsRecordButMarksItInvalid() {
    SafGrantLedger ledger = SafTestSupport.single(URI, SafGrantRecord.READ | SafGrantRecord.WRITE);

    SafGrantLedger.Change revoked = ledger.refreshSystemFlags(URI, 0);

    assertTrue(revoked.accepted());
    SafGrantRecord record = revoked.ledger.findByUri(URI);
    assertNotNull("系统撤销后记录保留，供用户手动移除", record);
    assertFalse(record.valid());
    assertFalse(record.canRead());
    assertFalse(record.canWrite());
    assertEquals(ledger.findByUri(URI).epoch(), record.epoch());
    assertTrue(revoked.ledger.refreshSystemFlags(URI, 0).accepted());

    SafGrantLedger.Change restored = revoked.ledger.refreshSystemFlags(URI, SafGrantRecord.READ);
    assertTrue(restored.ledger.findByUri(URI).canRead());
    assertFalse(restored.ledger.findByUri(URI).canWrite());
  }

  @Test
  public void invalidArgumentsAreRejectedWithoutRevisionOrEpochChange() {
    SafGrantLedger ledger = SafTestSupport.single(URI, SafGrantRecord.READ);
    long revision = ledger.revision();
    long nextEpoch = ledger.nextEpoch();

    assertEquals(
        SafGrantLedger.INVALID_URI,
        ledger.beginTake("https://example.com/tree/x", "app", "label", 0, 1, 1L).failure);
    assertEquals(
        SafGrantLedger.INVALID_ARGUMENT, ledger.beginTake(URI, "app", "label", 0, 0, 1L).failure);
    assertEquals(
        SafGrantLedger.INVALID_ARGUMENT, ledger.beginTake(URI, "app", "label", 0, 8, 1L).failure);
    assertEquals(SafGrantLedger.BAD_STATE, ledger.commitTake(URI, 1, 0, true, 1L).failure);
    assertEquals(SafGrantLedger.INVALID_ARGUMENT, ledger.refreshSystemFlags(URI, 8).failure);

    assertEquals(revision, ledger.revision());
    assertEquals(nextEpoch, ledger.nextEpoch());
    assertEquals(1, ledger.size());
  }

  @Test
  public void recordsAreSortedByShortIdForStablePersistence() {
    SafGrantLedger ledger =
        SafTestSupport.ledger(
            SafTestSupport.active(SafTestSupport.DOCUMENTS, SafGrantRecord.READ, 2),
            SafTestSupport.active(SafTestSupport.DOWNLOADS, SafGrantRecord.READ, 1));

    String first = ledger.records().get(0).id();
    String second = ledger.records().get(1).id();
    assertTrue(first.compareTo(second) < 0);
    String downloadsId = SafGrantRecord.idOf(SafTestSupport.DOWNLOADS);
    String documentsId = SafGrantRecord.idOf(SafTestSupport.DOCUMENTS);
    assertEquals(downloadsId.compareTo(documentsId) < 0 ? downloadsId : documentsId, first);
  }

  @Test
  public void shortIdMatchesTheExistingBridgeAlgorithm() {
    assertEquals(
        "2a261d4e242d",
        SafGrantRecord.idOf(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload"));
    assertEquals(
        "8794949a00b7",
        SafGrantRecord.idOf(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments"));
    assertTrue(SafGrantRecord.validId("2a261d4e242d"));
    assertFalse(SafGrantRecord.validId("2A261D4E242D"));
    assertFalse(SafGrantRecord.validId("short"));
  }
}
