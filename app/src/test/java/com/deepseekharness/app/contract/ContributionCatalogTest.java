package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.Test;

/** C02 贡献目录：稳定排序、隐藏叠加、撤销、未知 contribution、布局坏值与未知键保留。 */
public class ContributionCatalogTest {
  private static final ModuleId OWNER = new ModuleId("demo.a");

  private static Contribution card(String id, int sortOrder) {
    return Contribution.card(OWNER, id, "卡片 " + id, "Card " + id, sortOrder);
  }

  private static ContributionCatalog catalog(
      ContractTestSupport.FakeSharedPreferences preferences, List<DiagnosticsSink.Event> events) {
    DiagnosticsSink sink = ContractTestSupport.sink(events);
    return new ContributionCatalog(new SharedPreferencesLayoutStore(preferences, sink), sink);
  }

  @Test
  public void stableOrderMatchesLayoutAndByKindQueries() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ContributionCatalog catalog = catalog(preferences, events);

    assertTrue(catalog.add(card("z", 5), 1).accepted());
    assertTrue(catalog.add(card("a", 5), 1).accepted());
    assertTrue(catalog.add(card("first", 1), 1).accepted());
    assertTrue(
        catalog
            .add(Contribution.route(OWNER, "page", "页面", "Page", 30, "demo.page"), 1)
            .accepted());

    assertEquals(
        List.of("demo.a#first", "demo.a#a", "demo.a#z", "demo.a#page"),
        ContractTestSupport.keys(catalog.contributions()));
    assertEquals(3, catalog.byKind(Contribution.Kind.CARD).size());
    assertEquals(1, catalog.byKind(Contribution.Kind.ROUTE).size());

    assertTrue(catalog.setOrder(List.of("demo.a#z", "demo.a#first", "demo.a#a")).accepted());
    assertEquals(
        List.of("demo.a#z", "demo.a#first", "demo.a#a", "demo.a#page"),
        ContractTestSupport.keys(catalog.contributions()));

    assertTrue(catalog.setHidden("demo.a#a", true).accepted());
    ContributionView hidden = catalog.find("demo.a#a");
    assertNotNull(hidden);
    assertTrue(hidden.hidden());
    assertNull(ContractTestSupport.view(catalog.visibleContributions(), "demo.a#a"));
    assertEquals(
        List.of("demo.a#z", "demo.a#first", "demo.a#page"),
        ContractTestSupport.keys(catalog.visibleContributions()));
  }

  @Test
  public void unknownContributionIsRejectedAndDiagnosed() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ContributionCatalog catalog = catalog(preferences, events);

    assertEquals(ContractCodes.UNKNOWN_CONTRIBUTION, catalog.revoke("demo.a#missing").code());
    assertEquals(
        ContractCodes.UNKNOWN_CONTRIBUTION, catalog.setHidden("demo.a#missing", true).code());
    assertNull(catalog.find("demo.a#missing"));
    assertEquals(ContractCodes.INVALID_LAYOUT, catalog.setOrder(List.of("bad key")).code());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.UNKNOWN_CONTRIBUTION));
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.INVALID_LAYOUT));
  }

  @Test
  public void duplicateContributionIsRejectedWithoutOverwrite() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ContributionCatalog catalog = catalog(preferences, events);

    assertTrue(catalog.add(card("card", 1), 1).accepted());
    ContractResult duplicate = catalog.add(card("card", 2), 2);
    assertTrue(duplicate.rejected());
    assertEquals(ContractCodes.DUPLICATE_CONTRIBUTION, duplicate.code());
    assertEquals(1, catalog.size());
    assertEquals(1, catalog.find("demo.a#card").sortOrder());
  }

  @Test
  public void unknownLayoutKeysAreKeptAcrossCatalogMutation() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    preferences.putRaw(SharedPreferencesLayoutStore.PREF_ORDER, "v1:legacy#old\ndemo.a#card");
    preferences.putRaw(
        SharedPreferencesLayoutStore.PREF_HIDDEN, new LinkedHashSet<>(List.of("legacy#old")));
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ContributionCatalog catalog = catalog(preferences, events);
    assertTrue(catalog.add(card("card", 1), 1).accepted());

    assertTrue(catalog.setHidden("demo.a#card", true).accepted());

    LayoutSnapshot reloaded =
        new SharedPreferencesLayoutStore(preferences, ContractTestSupport.sink(events)).load();
    assertEquals(List.of("legacy#old", "demo.a#card"), reloaded.order());
    assertTrue(reloaded.hidden().contains("legacy#old"));
    assertTrue(reloaded.hidden().contains("demo.a#card"));
  }

  @Test
  public void layoutLoadAndSaveFailuresAreFailClosed() {
    List<DiagnosticsSink.Event> loadEvents = new ArrayList<>();
    LayoutStore failingLoad =
        new LayoutStore() {
          @Override
          public LayoutSnapshot load() {
            throw new IllegalStateException("load");
          }

          @Override
          public void save(LayoutSnapshot snapshot) {}
        };
    ContributionCatalog loaded =
        new ContributionCatalog(failingLoad, ContractTestSupport.sink(loadEvents));
    assertEquals(LayoutSnapshot.empty(), loaded.layout());
    assertTrue(ContractTestSupport.hasEvent(loadEvents, ContractCodes.LAYOUT_LOAD_FAILED));

    List<DiagnosticsSink.Event> saveEvents = new ArrayList<>();
    LayoutStore failingSave =
        new LayoutStore() {
          @Override
          public LayoutSnapshot load() {
            return LayoutSnapshot.empty();
          }

          @Override
          public void save(LayoutSnapshot snapshot) {
            throw new IllegalStateException("save");
          }
        };
    ContributionCatalog catalog =
        new ContributionCatalog(failingSave, ContractTestSupport.sink(saveEvents));
    assertTrue(catalog.add(card("card", 1), 1).accepted());
    ContractResult result = catalog.setHidden("demo.a#card", true);
    assertTrue(result.rejected());
    assertEquals(ContractCodes.LAYOUT_SAVE_FAILED, result.code());
    assertFalse(catalog.layout().isHidden("demo.a#card"));
    assertTrue(ContractTestSupport.hasEvent(saveEvents, ContractCodes.LAYOUT_SAVE_FAILED));
  }

  @Test
  public void declaredAndLayoutHiddenCombineWithoutDroppingEntries() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ContributionCatalog catalog = catalog(preferences, events);
    assertTrue(
        catalog
            .add(Contribution.card(OWNER, "declared", "声明隐藏", "Declared hidden", 1, true), 1)
            .accepted());
    assertTrue(catalog.add(card("visible", 2), 1).accepted());
    assertTrue(catalog.setHidden("demo.a#visible", true).accepted());

    assertEquals(2, catalog.contributions().size());
    assertTrue(catalog.find("demo.a#declared").hidden());
    assertTrue(catalog.find("demo.a#visible").hidden());
    assertTrue(catalog.visibleContributions().isEmpty());
  }
}
