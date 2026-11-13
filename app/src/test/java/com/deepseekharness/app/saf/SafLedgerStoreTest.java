package com.deepseekharness.app.saf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/** 持久化适配器：独立命名空间、坏值降级、未知条目保留、提交失败不假装成功。 */
public class SafLedgerStoreTest {
  private static final String URI = SafTestSupport.DOWNLOADS;

  @Test
  public void namespaceIsIndependentFromLegacyAndModuleLayout() {
    assertEquals("saf_capability_ledger", SafLedgerStore.NAMESPACE);
    assertNotEquals("saf_grants", SafLedgerStore.NAMESPACE);
    assertNotEquals("dsha_module_layout", SafLedgerStore.NAMESPACE);
  }

  @Test
  public void saveThenLoadRoundTrip() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    SafLedgerStore store = new SafLedgerStore(preferences);
    SafGrantLedger ledger =
        SafGrantLedger.empty()
            .beginTake(
                URI,
                "Files",
                "Downloads",
                SafGrantRecord.READ,
                SafGrantRecord.READ | SafGrantRecord.WRITE,
                1_000L)
            .ledger
            .commitTake(
                URI, SafGrantRecord.READ | SafGrantRecord.WRITE, SafGrantRecord.WRITE, true, 2_000L)
            .ledger;

    assertTrue(store.save(ledger));

    SafGrantLedger loaded = new SafLedgerStore(preferences).load();
    assertEquals(ledger.revision(), loaded.revision());
    assertEquals(ledger.nextEpoch(), loaded.nextEpoch());
    SafGrantRecord record = loaded.findByUri(URI);
    assertNotNull(record);
    assertEquals(SafGrantRecord.State.ACTIVE, record.state());
    assertTrue(record.canWrite());
    assertEquals(SafGrantRecord.WRITE, record.acquired());
  }

  @Test
  public void savePreservesUnknownKeysFromNewerVersionsOrOtherWriters() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    SafLedgerStore store = new SafLedgerStore(preferences);
    preferences.putRaw("future.top", "value");
    String id = SafGrantRecord.idOf(URI);
    preferences.putRaw("grant." + id + ".futureField", "value2");

    assertTrue(store.save(SafTestSupport.single(URI, SafGrantRecord.READ)));

    Map<String, ?> raw = preferences.getAll();
    assertEquals("value", raw.get("future.top"));
    assertEquals("value2", raw.get("grant." + id + ".futureField"));
    assertNotNull(raw.get(SafLedgerCodec.PREF_IDS));
  }

  @Test
  public void saveRemovesOnlyKnownStaleFields() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    SafLedgerStore store = new SafLedgerStore(preferences);
    assertTrue(store.save(SafTestSupport.single(URI, SafGrantRecord.READ)));
    String id = SafGrantRecord.idOf(URI);
    preferences.putRaw("grant." + id + ".futureField", "value");

    assertTrue(store.save(SafGrantLedger.empty()));

    Map<String, ?> raw = preferences.getAll();
    assertNull(raw.get("grant." + id + ".uri"));
    assertNull(raw.get("grant." + id + ".state"));
    assertEquals("value", raw.get("grant." + id + ".futureField"));
    assertTrue(idsOf(raw).isEmpty());
  }

  @Test
  public void commitFailureIsReportedAndDiskStateIsNotMisreported() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    SafLedgerStore store = new SafLedgerStore(preferences);
    preferences.commitResult = false;

    assertFalse(store.save(SafTestSupport.single(URI, SafGrantRecord.READ)));
    assertTrue(preferences.getAll().isEmpty());
  }

  @Test
  public void unreadablePreferencesDegradeWithoutThrowing() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    SafLedgerStore store = new SafLedgerStore(preferences);
    preferences.unreadable = true;

    SafGrantLedger loaded = store.load();
    assertTrue(loaded.records().isEmpty());
    assertTrue(store.loadDecoded().warnings.contains("STORE_UNREADABLE"));
    assertFalse(store.save(SafTestSupport.single(URI, SafGrantRecord.READ)));
  }

  @Test
  public void saveRefusesFutureSchemaWithoutTouchingDisk() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    preferences.putRaw(SafLedgerCodec.PREF_SCHEMA, "2");
    preferences.putRaw("future.top", "value");
    preferences.putRaw("grant." + SafGrantRecord.idOf(URI) + ".uri", URI);
    java.util.Map<String, Object> before = new java.util.LinkedHashMap<>(preferences.getAll());
    SafLedgerStore store = new SafLedgerStore(preferences);

    assertFalse("未来 schema 必须拒绝写入，不能覆盖成 1", store.save(SafGrantLedger.empty()));
    assertEquals(before, preferences.getAll());
  }

  @Test
  public void saveKeepsStructurallyCorruptKnownFields() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    String id = SafGrantRecord.idOf(URI);
    preferences.putRaw(SafLedgerCodec.PREF_SCHEMA, "1");
    preferences.putRaw(SafLedgerCodec.PREF_IDS, java.util.Set.of(id));
    preferences.putRaw("grant." + id + ".uri", URI);
    preferences.putRaw("grant." + id + ".state", "BROKEN");
    preferences.putRaw("grant." + id + ".futureField", "keep");
    SafLedgerStore store = new SafLedgerStore(preferences);
    assertTrue("结构损坏记录只保留不激活", store.load().findByUri(URI) == null);

    assertTrue(store.save(SafGrantLedger.empty()));

    java.util.Map<String, ?> raw = preferences.getAll();
    assertEquals("保留未知字段", "keep", raw.get("grant." + id + ".futureField"));
    assertEquals("保留结构损坏记录的已知字段", URI, raw.get("grant." + id + ".uri"));
    assertEquals("保留结构损坏记录的状态", "BROKEN", raw.get("grant." + id + ".state"));
  }

  @Test
  public void wrongValueTypesDoNotThrowAndFallBack() {
    SafTestSupport.FakePreferences preferences = new SafTestSupport.FakePreferences();
    preferences.putRaw(SafLedgerCodec.PREF_SCHEMA, "1");
    preferences.putRaw(SafLedgerCodec.PREF_REVISION, 42);
    preferences.putRaw(SafLedgerCodec.PREF_IDS, 7);

    SafGrantLedger loaded = new SafLedgerStore(preferences).load();

    assertTrue(loaded.records().isEmpty());
    assertEquals(42, loaded.revision());
  }

  private static java.util.Set<String> idsOf(Map<String, ?> raw) {
    Object value = raw.get(SafLedgerCodec.PREF_IDS);
    if (!(value instanceof java.util.Set<?> set)) return java.util.Set.of();
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    for (Object item : set) if (item instanceof String string) ids.add(string);
    return ids;
  }
}
