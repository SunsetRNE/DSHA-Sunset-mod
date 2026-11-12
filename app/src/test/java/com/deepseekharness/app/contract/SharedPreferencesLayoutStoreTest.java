package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** SharedPreferences 布局后端：命名空间、持久化、未知键保留、坏值 fail-closed。 */
public class SharedPreferencesLayoutStoreTest {
  private static SharedPreferencesLayoutStore store(
      ContractTestSupport.FakeSharedPreferences preferences, List<DiagnosticsSink.Event> events) {
    return new SharedPreferencesLayoutStore(preferences, ContractTestSupport.sink(events));
  }

  @Test
  public void namespaceIsDedicatedAndRoundTripsLayout() {
    assertEquals("dsha_module_layout", SharedPreferencesLayoutStore.NAMESPACE);
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    SharedPreferencesLayoutStore store = store(preferences, new ArrayList<>());
    LayoutSnapshot snapshot =
        new LayoutSnapshot(List.of("demo.a#b", "demo.a#a"), Set.of("demo.a#a"));

    store.save(snapshot);

    assertEquals(snapshot, store.load());
  }

  @Test
  public void emptyPreferencesLoadDefaultLayout() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    assertEquals(LayoutSnapshot.empty(), store(preferences, new ArrayList<>()).load());
  }

  @Test
  public void corruptOrderFallsBackToEmptyAndDiagnoses() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    preferences.putRaw(SharedPreferencesLayoutStore.PREF_ORDER, "not-versioned");
    List<DiagnosticsSink.Event> events = new ArrayList<>();

    assertEquals(LayoutSnapshot.empty(), store(preferences, events).load());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.LAYOUT_CORRUPT));
  }

  @Test
  public void corruptOrderWithInvalidKeyOrTrailingEntryFallsBackToEmpty() {
    ContractTestSupport.FakeSharedPreferences invalidKey =
        new ContractTestSupport.FakeSharedPreferences();
    invalidKey.putRaw(SharedPreferencesLayoutStore.PREF_ORDER, "v1:bad key");
    assertEquals(LayoutSnapshot.empty(), store(invalidKey, new ArrayList<>()).load());

    ContractTestSupport.FakeSharedPreferences trailing =
        new ContractTestSupport.FakeSharedPreferences();
    trailing.putRaw(SharedPreferencesLayoutStore.PREF_ORDER, "v1:demo.a#a\n");
    assertEquals(LayoutSnapshot.empty(), store(trailing, new ArrayList<>()).load());
  }

  @Test
  public void corruptHiddenValueFallsBackToEmpty() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    preferences.putRaw(SharedPreferencesLayoutStore.PREF_HIDDEN, "not-a-string-set");
    List<DiagnosticsSink.Event> events = new ArrayList<>();

    assertEquals(LayoutSnapshot.empty(), store(preferences, events).load());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.LAYOUT_CORRUPT));
  }

  @Test
  public void unknownButValidKeysSurviveRoundTrip() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    preferences.putRaw(SharedPreferencesLayoutStore.PREF_ORDER, "v1:legacy#old\ndemo.a#current");
    preferences.putRaw(
        SharedPreferencesLayoutStore.PREF_HIDDEN, new LinkedHashSet<>(List.of("legacy#old")));
    SharedPreferencesLayoutStore store = store(preferences, new ArrayList<>());

    LayoutSnapshot loaded = store.load();
    assertEquals(List.of("legacy#old", "demo.a#current"), loaded.order());
    assertTrue(loaded.isHidden("legacy#old"));

    store.save(loaded);
    assertEquals(loaded, store.load());
  }
}
