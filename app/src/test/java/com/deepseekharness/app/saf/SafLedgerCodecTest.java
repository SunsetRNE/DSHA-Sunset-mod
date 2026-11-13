package com.deepseekharness.app.saf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 授权簿持久化编解码：坏值回默认、未知条目保留、结构损坏 fail-closed。 */
public class SafLedgerCodecTest {
  private static final String URI = SafTestSupport.DOWNLOADS;

  private static SafGrantLedger oneGrant(int flags) {
    return SafTestSupport.ledger(SafTestSupport.active(URI, flags, 3));
  }

  @Test
  public void encodeDecodeRoundTripKeepsRecordsAndMetadata() {
    SafGrantLedger ledger = oneGrant(SafGrantRecord.READ | SafGrantRecord.WRITE);

    Map<String, Object> encoded = SafLedgerCodec.encode(ledger);
    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(encoded);

    assertTrue(decoded.warnings.isEmpty());
    assertTrue(decoded.preserved.isEmpty());
    assertEquals(ledger.revision(), decoded.ledger.revision());
    assertEquals(ledger.nextEpoch(), decoded.ledger.nextEpoch());
    assertEquals(1, decoded.ledger.size());
    SafGrantRecord record = decoded.ledger.findByUri(URI);
    assertNotNull(record);
    assertEquals(SafGrantRecord.idOf(URI), record.id());
    assertTrue(record.canRead());
    assertTrue(record.canWrite());
    assertTrue(record.userWritable());
    assertEquals(SafGrantRecord.READ | SafGrantRecord.WRITE, record.acquired());
    assertEquals(3, record.epoch());
    assertEquals(1_000L, record.takenAt());
  }

  @Test
  public void pendingRecordFieldsRoundTrip() {
    SafGrantLedger ledger =
        SafGrantLedger.empty()
            .beginTake(
                URI,
                "测试应用",
                "测试目录",
                SafGrantRecord.READ,
                SafGrantRecord.READ | SafGrantRecord.WRITE,
                4_000L)
            .ledger;

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(SafLedgerCodec.encode(ledger));
    SafGrantRecord record = decoded.ledger.findByUri(URI);
    assertNotNull(record);
    assertEquals(SafGrantRecord.State.PENDING, record.state());
    assertEquals(SafGrantRecord.READ, record.pendingBefore());
    assertEquals(SafGrantRecord.READ | SafGrantRecord.WRITE, record.pendingIntent());
    assertEquals(4_000L, record.takenAt());
  }

  @Test
  public void previousSnapshotOfRetakeRoundTrips() {
    SafGrantLedger active = oneGrant(SafGrantRecord.READ | SafGrantRecord.WRITE);
    SafGrantLedger pending =
        active.beginTake(URI, "app", "label", SafGrantRecord.READ, SafGrantRecord.READ, 2_000L)
            .ledger;

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(SafLedgerCodec.encode(pending));

    SafGrantRecord record = decoded.ledger.findByUri(URI);
    assertNotNull(record);
    assertTrue(record.hasPrevious());
    assertEquals("测试应用", record.previous().app());
    assertEquals("测试目录", record.previous().label());
    assertTrue(record.previous().readable());
    assertTrue(record.previous().writable());
    assertTrue(decoded.warnings.isEmpty());
  }

  @Test
  public void releasingStateRoundTripsAndStaysWithoutPendingFields() {
    SafGrantLedger releasing =
        SafTestSupport.single(URI, SafGrantRecord.READ | SafGrantRecord.WRITE)
            .beginRelease(URI)
            .ledger;

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(SafLedgerCodec.encode(releasing));

    SafGrantRecord record = decoded.ledger.findByUri(URI);
    assertNotNull(record);
    assertEquals(SafGrantRecord.State.RELEASING, record.state());
    assertEquals(SafGrantRecord.READ | SafGrantRecord.WRITE, record.acquired());
    assertFalse(record.valid());
  }

  @Test
  public void unknownKeysAndFutureFieldsArePreserved() {
    Map<String, Object> raw =
        new LinkedHashMap<>(SafLedgerCodec.encode(oneGrant(SafGrantRecord.READ)));
    raw.put("future.top", "value");
    String id = SafGrantRecord.idOf(URI);
    raw.put("grant." + id + ".futureField", "value2");

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(raw);

    assertEquals(1, decoded.ledger.size());
    assertEquals("value", decoded.preserved.get("future.top"));
    assertEquals("value2", decoded.preserved.get("grant." + id + ".futureField"));
    assertFalse(SafLedgerCodec.encode(decoded.ledger).containsKey("future.top"));
  }

  @Test
  public void unsupportedSchemaLeavesLedgerEmptyAndPreservesEverything() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put(SafLedgerCodec.PREF_SCHEMA, "2");
    raw.put("ids", Set.of(SafGrantRecord.idOf(URI)));
    raw.put("future.top", "value");

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(raw);

    assertEquals(0, decoded.ledger.size());
    assertTrue(decoded.warnings.contains("SCHEMA_UNSUPPORTED"));
    assertEquals("2", decoded.preserved.get(SafLedgerCodec.PREF_SCHEMA));
    assertEquals("value", decoded.preserved.get("future.top"));
  }

  @Test
  public void corruptScalarsFallBackToSafeDefaults() {
    Map<String, Object> raw =
        new LinkedHashMap<>(SafLedgerCodec.encode(oneGrant(SafGrantRecord.READ)));
    raw.put(SafLedgerCodec.PREF_REVISION, "not-a-number");
    raw.put(SafLedgerCodec.PREF_NEXT_EPOCH, "-5");
    String prefix = SafLedgerCodec.PREF_GRANT_PREFIX + SafGrantRecord.idOf(URI) + ".";
    raw.put(prefix + SafLedgerCodec.FIELD_READ, "2");
    raw.put(prefix + SafLedgerCodec.FIELD_WRITE, "no");
    raw.put(prefix + SafLedgerCodec.FIELD_USER_WRITE, "maybe");
    raw.put(prefix + SafLedgerCodec.FIELD_ACQUIRED, "zz");
    raw.put(prefix + SafLedgerCodec.FIELD_EPOCH, "-3");

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(raw);

    assertEquals(1, decoded.ledger.size());
    assertEquals(0, decoded.ledger.revision());
    assertTrue(decoded.ledger.nextEpoch() >= 1);
    SafGrantRecord record = decoded.ledger.findByUri(URI);
    assertFalse(record.readable());
    assertFalse(record.writable());
    assertTrue("写入偏好默认沿用既有授权页的 true", record.userWritable());
    assertEquals(0, record.acquired());
    assertEquals(0, record.epoch());
    assertTrue(record.state() == SafGrantRecord.State.ACTIVE);
    assertTrue(decoded.warnings.contains("REVISION_DEFAULTED"));
    assertTrue(decoded.warnings.contains("RECORD_EPOCH_DEFAULTED:" + record.id()));
  }

  @Test
  public void structurallyCorruptRecordsArePreservedButNotActive() {
    String invalidUri = SafGrantRecord.idOf("content://x.documents/tree/valid");
    String mismatched = "aaaaaaaaaaaa";
    String documentsId = SafGrantRecord.idOf(SafTestSupport.DOCUMENTS);
    String badState = "bbbbbbbbbbbb";
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put(SafLedgerCodec.PREF_SCHEMA, "1");
    raw.put(
        SafLedgerCodec.PREF_IDS,
        new LinkedHashSet<>(
            List.of(invalidUri, mismatched, documentsId, badState, "cccccccccccc")));
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + invalidUri + "." + SafLedgerCodec.FIELD_URI,
        "https://example.com/tree/x");
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + invalidUri + "." + SafLedgerCodec.FIELD_STATE, "ACTIVE");
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + mismatched + "." + SafLedgerCodec.FIELD_URI,
        SafTestSupport.DOCUMENTS);
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + mismatched + "." + SafLedgerCodec.FIELD_STATE, "ACTIVE");
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + documentsId + "." + SafLedgerCodec.FIELD_URI,
        SafTestSupport.DOCUMENTS);
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + documentsId + "." + SafLedgerCodec.FIELD_STATE,
        "BROKEN");
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + badState + "." + SafLedgerCodec.FIELD_URI,
        "content://x.documents/tree/badstate");
    raw.put(
        SafLedgerCodec.PREF_GRANT_PREFIX + badState + "." + SafLedgerCodec.FIELD_STATE, "ACTIVE");

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(raw);

    assertEquals(0, decoded.ledger.size());
    assertTrue(decoded.warnings.contains("RECORD_INVALID_URI:" + invalidUri));
    assertTrue(decoded.warnings.contains("RECORD_ID_MISMATCH:" + mismatched));
    assertTrue(decoded.warnings.contains("RECORD_INVALID_STATE:" + documentsId));
    assertTrue(decoded.warnings.contains("RECORD_MISSING:cccccccccccc"));
    assertFalse(decoded.preserved.isEmpty());
  }

  @Test
  public void invalidIdsIndexIsRecoveredFromRecordKeys() {
    Map<String, Object> raw =
        new LinkedHashMap<>(SafLedgerCodec.encode(oneGrant(SafGrantRecord.READ)));
    raw.put(SafLedgerCodec.PREF_IDS, 42);

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(raw);

    assertEquals(1, decoded.ledger.size());
    assertTrue(decoded.warnings.contains("IDS_INVALID"));
    assertTrue(decoded.warnings.contains("IDS_RECOVERED"));
  }

  @Test
  public void tooManyRecordsKeepFirstEightAndPreserveTheRest() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put(SafLedgerCodec.PREF_SCHEMA, "1");
    Set<String> ids = new LinkedHashSet<>();
    for (int index = 0; index < SafCapabilityLimits.MAX_TREES + 1; index++) {
      String uri = SafTestSupport.treeUri(index);
      String id = SafGrantRecord.idOf(uri);
      ids.add(id);
      String prefix = SafLedgerCodec.PREF_GRANT_PREFIX + id + ".";
      raw.put(prefix + SafLedgerCodec.FIELD_URI, uri);
      raw.put(prefix + SafLedgerCodec.FIELD_STATE, "ACTIVE");
      raw.put(prefix + SafLedgerCodec.FIELD_EPOCH, "1");
      raw.put(prefix + SafLedgerCodec.FIELD_READ, "1");
      raw.put(prefix + SafLedgerCodec.FIELD_WRITE, "0");
    }
    raw.put(SafLedgerCodec.PREF_IDS, ids);

    SafLedgerCodec.Decoded decoded = SafLedgerCodec.decode(raw);

    assertEquals(SafCapabilityLimits.MAX_TREES, decoded.ledger.size());
    assertTrue(decoded.warnings.contains("RECORD_LIMIT"));
    assertFalse(decoded.preserved.isEmpty());
  }

  @Test
  public void emptySnapshotDecodesToEmptyLedger() {
    assertTrue(SafLedgerCodec.decode(null).ledger.records().isEmpty());
    assertTrue(SafLedgerCodec.decode(Map.of()).ledger.records().isEmpty());
    assertEquals(0, SafLedgerCodec.decode(Map.of()).ledger.revision());
  }

  @Test
  public void encodeOnlyProducesStringAndStringSetValues() {
    Map<String, Object> encoded = SafLedgerCodec.encode(oneGrant(SafGrantRecord.READ));
    for (Map.Entry<String, Object> entry : encoded.entrySet()) {
      assertTrue(
          "非字符串值会让 SharedPreferences 编辑类型漂移：" + entry.getKey(),
          entry.getValue() instanceof String || entry.getValue() instanceof Set<?>);
    }
    assertNotNull(encoded.get(SafLedgerCodec.PREF_IDS));
    assertNull(encoded.get("unknown"));
  }
}
