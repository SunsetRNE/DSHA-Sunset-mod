package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

/** 从真实 Java 调用值类型，锁住迁移前的校验顺序、record 值语义与集合边界。 */
public class ContractValueInteropTest {
  private static final ModuleId OWNER = new ModuleId("demo.values");
  private static final String KEY = "demo.values#card";
  private static final String OTHER_KEY = "legacy#other";
  private static final ModuleDescriptor.Enablement OPTIONAL =
      ModuleDescriptor.Enablement.OPTIONAL_USER;

  @Test
  public void moduleIdRetainsConstructorFactoryAndValueSemantics() {
    ModuleId id = ModuleId.of("demo.values");
    assertEquals(OWNER, id);
    assertEquals(id, OWNER);
    assertEquals("demo.values", id.value());
    assertEquals("demo.values", id.toString());
    assertEquals("demo.values".hashCode(), id.hashCode());
    assertFalse(id.equals(null));
    assertFalse(id.equals("demo.values"));
    assertNotEquals(id, ModuleId.of("demo.other"));
    assertEquals("a".repeat(64), new ModuleId("a".repeat(64)).value());
  }

  @Test
  public void moduleIdRejectsNullAndInvalidValuesWithOriginalMessage() {
    for (String invalid : Arrays.asList(null, "", "A", "a b", "a#b", "a".repeat(65))) {
      expect(IllegalArgumentException.class, "MODULE_ID_INVALID", () -> new ModuleId(invalid));
      expect(IllegalArgumentException.class, "MODULE_ID_INVALID", () -> ModuleId.of(invalid));
    }
  }

  @Test
  public void descriptorValidationOrderAndMessagesMatchJava() {
    expect(NullPointerException.class, "id", () -> new ModuleDescriptor(null, 0, null, null, null));
    expect(
        IllegalArgumentException.class,
        "MODULE_VERSION_INVALID",
        () -> new ModuleDescriptor(OWNER, 0, null, null, null));
    for (String invalid : Arrays.asList(null, "", " \t\n", "\u2003", "x".repeat(201))) {
      expect(
          IllegalArgumentException.class,
          "MODULE_NAME_INVALID",
          () -> new ModuleDescriptor(OWNER, 1, invalid, null, null));
    }
    expect(
        NullPointerException.class,
        "capabilities",
        () -> new ModuleDescriptor(OWNER, 1, "模块", null, null));
    for (String invalid : Arrays.asList(null, "", "\t", "\u2003", "x".repeat(129))) {
      Set<String> capabilities = new LinkedHashSet<>(Arrays.asList("read", invalid));
      expect(
          IllegalArgumentException.class,
          "MODULE_CAPABILITY_INVALID",
          () -> new ModuleDescriptor(OWNER, 1, "模块", capabilities, null));
    }
    expect(
        NullPointerException.class,
        "enablement",
        () -> new ModuleDescriptor(OWNER, 1, "模块", Set.of(), null));
  }

  @Test
  public void descriptorCopiesCapabilitiesBeforeCheckingEnablement() {
    Set<String> failsOnCopy =
        new AbstractSet<>() {
          private int iterations;

          @Override
          public Iterator<String> iterator() {
            if (iterations++ > 0) throw new IllegalStateException("copy first");
            return List.of("read").iterator();
          }

          @Override
          public int size() {
            return 1;
          }
        };
    expect(
        IllegalStateException.class,
        "copy first",
        () -> new ModuleDescriptor(OWNER, 1, "模块", failsOnCopy, null));
  }

  @Test
  public void descriptorRetainsRecordValuesAndReadOnlyDefensiveCopy() {
    Set<String> source = new LinkedHashSet<>(List.of("read", "write"));
    ModuleDescriptor descriptor = new ModuleDescriptor(OWNER, 7, "模块", source, OPTIONAL);
    source.clear();
    assertEquals(OWNER, descriptor.id());
    assertEquals(7, descriptor.version());
    assertEquals("模块", descriptor.name());
    assertEquals(Set.of("read", "write"), descriptor.capabilities());
    assertSame(OPTIONAL, descriptor.enablement());
    assertFalse(descriptor.alwaysOn());
    assertTrue(
        new ModuleDescriptor(OWNER, 1, "模块", Set.of(), ModuleDescriptor.Enablement.ALWAYS_ON)
            .alwaysOn());
    assertThrows(UnsupportedOperationException.class, () -> descriptor.capabilities().add("new"));
    assertThrows(
        UnsupportedOperationException.class, () -> descriptor.capabilities().remove("read"));
    assertThrows(NullPointerException.class, () -> descriptor.capabilities().contains(null));
    assertRecordValue(
        descriptor,
        new ModuleDescriptor(OWNER, 7, "模块", Set.of("write", "read"), OPTIONAL),
        new DescriptorRecord(OWNER, 7, "模块", descriptor.capabilities(), OPTIONAL));
    assertNotEquals(
        descriptor,
        new ModuleDescriptor(ModuleId.of("other"), 7, "模块", Set.of("read", "write"), OPTIONAL));
    assertNotEquals(
        descriptor, new ModuleDescriptor(OWNER, 8, "模块", Set.of("read", "write"), OPTIONAL));
    assertNotEquals(
        descriptor, new ModuleDescriptor(OWNER, 7, "其他", Set.of("read", "write"), OPTIONAL));
    assertNotEquals(descriptor, new ModuleDescriptor(OWNER, 7, "模块", Set.of("read"), OPTIONAL));
    assertNotEquals(
        descriptor,
        new ModuleDescriptor(
            OWNER, 7, "模块", Set.of("read", "write"), ModuleDescriptor.Enablement.ALWAYS_ON));
  }

  @Test
  public void javaWhitespaceSemanticsAcceptNbspAndUseUtf16LengthLimits() {
    for (String nonBlank : List.of("\u00a0", "\u2007", "\u202f")) {
      ModuleDescriptor descriptor =
          new ModuleDescriptor(OWNER, 1, nonBlank, Set.of(nonBlank), OPTIONAL);
      assertEquals(nonBlank, descriptor.name());
      assertEquals(Set.of(nonBlank), descriptor.capabilities());
      Contribution contribution = Contribution.card(OWNER, "card", nonBlank, nonBlank, 0);
      assertEquals(nonBlank, contribution.title());
      assertEquals(nonBlank, contribution.titleEn());
    }
    String exactLimit = "\ud83d\ude00".repeat(100);
    assertEquals(
        exactLimit,
        new ModuleDescriptor(OWNER, 1, exactLimit, Set.of("x".repeat(128)), OPTIONAL).name());
    assertEquals(exactLimit, Contribution.card(OWNER, "card", exactLimit, exactLimit, 0).title());
    expect(
        IllegalArgumentException.class,
        "MODULE_NAME_INVALID",
        () -> new ModuleDescriptor(OWNER, 1, exactLimit + "x", Set.of(), OPTIONAL));
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_TITLE_INVALID",
        () -> Contribution.card(OWNER, "card", exactLimit + "x", "Card", 0));
  }

  @Test
  public void contractResultRetainsJavaFactoriesAndRecordValuesForBothBooleans() {
    ContractResult ok = ContractResult.ok();
    assertTrue(ok.accepted());
    assertFalse(ok.rejected());
    assertEquals(ContractCodes.OK, ok.code());
    assertRecordValue(ok, new ContractResult(true, "OK"), new ResultRecord(true, "OK"));
    ContractResult rejected = ContractResult.rejected("REJECTED_1");
    assertFalse(rejected.accepted());
    assertTrue(rejected.rejected());
    assertRecordValue(
        rejected, new ContractResult(false, "REJECTED_1"), new ResultRecord(false, "REJECTED_1"));
    assertNotEquals(ok, new ContractResult(false, "OK"));
    assertNotEquals(ok, new ContractResult(true, "OTHER"));
    assertEquals("A".repeat(64), ContractResult.rejected("A".repeat(64)).code());
    for (String invalid : Arrays.asList(null, "", "bad", "A-B", "A".repeat(65))) {
      expect(
          IllegalArgumentException.class,
          "CONTRACT_RESULT_CODE_INVALID",
          () -> new ContractResult(true, invalid));
      expect(
          IllegalArgumentException.class,
          "CONTRACT_RESULT_CODE_INVALID",
          () -> ContractResult.rejected(invalid));
    }
  }

  @Test
  public void contributionValidationOrderMatchesOriginalExplicitChecks() {
    expect(
        NullPointerException.class,
        "kind",
        () -> new Contribution(null, null, null, null, null, 0, false, null));
    expect(
        NullPointerException.class,
        "owner",
        () -> new Contribution(Contribution.Kind.ROUTE, null, null, null, null, 0, false, null));
    for (String invalid : Arrays.asList(null, "", "A", "a b", "a".repeat(65))) {
      expect(
          IllegalArgumentException.class,
          "CONTRIBUTION_ID_INVALID",
          () ->
              new Contribution(
                  Contribution.Kind.ROUTE, OWNER, invalid, null, null, 0, false, null));
    }
    for (String invalid : Arrays.asList(null, "", " \t\n", "\u2003", "x".repeat(201))) {
      expect(
          IllegalArgumentException.class,
          "CONTRIBUTION_TITLE_INVALID",
          () ->
              new Contribution(
                  Contribution.Kind.ROUTE, OWNER, "card", invalid, null, 0, false, null));
      expect(
          IllegalArgumentException.class,
          "CONTRIBUTION_TITLE_EN_INVALID",
          () ->
              new Contribution(
                  Contribution.Kind.ROUTE, OWNER, "card", "标题", invalid, 0, false, null));
    }
    for (String invalid :
        Arrays.asList(null, "", "target with space", "/target", "x".repeat(129))) {
      expect(
          IllegalArgumentException.class,
          "CONTRIBUTION_ROUTE_TARGET_INVALID",
          () -> Contribution.route(OWNER, "page", "页面", "Page", 0, invalid));
    }
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_ROUTE_TARGET_UNEXPECTED",
        () ->
            new Contribution(
                Contribution.Kind.CARD, OWNER, "card", "标题", "Card", 0, false, "target"));
  }

  @Test
  public void contributionStaticOverloadsAndAccessorsRemainCallableFromJava() {
    Contribution card = Contribution.card(OWNER, "card", "标题", "Card", Integer.MIN_VALUE);
    assertEquals(Contribution.Kind.CARD, card.kind());
    assertSame(OWNER, card.owner());
    assertEquals("card", card.id());
    assertEquals("标题", card.title());
    assertEquals("Card", card.titleEn());
    assertEquals(Integer.MIN_VALUE, card.sortOrder());
    assertFalse(card.hidden());
    assertNull(card.routeTarget());
    assertEquals(KEY, card.globalKey());
    assertRecordValue(
        card,
        Contribution.card(OWNER, "card", "标题", "Card", Integer.MIN_VALUE, false),
        new ContributionRecord(
            card.kind(), OWNER, "card", "标题", "Card", Integer.MIN_VALUE, false, null));
    Contribution route =
        Contribution.route(OWNER, "page", "页面", "Page", Integer.MAX_VALUE, "app:page/path", true);
    assertTrue(route.hidden());
    assertEquals("app:page/path", route.routeTarget());
    assertRecordValue(
        route,
        new Contribution(
            Contribution.Kind.ROUTE,
            OWNER,
            "page",
            "页面",
            "Page",
            Integer.MAX_VALUE,
            true,
            "app:page/path"),
        new ContributionRecord(
            Contribution.Kind.ROUTE,
            OWNER,
            "page",
            "页面",
            "Page",
            Integer.MAX_VALUE,
            true,
            "app:page/path"));
    assertFalse(Contribution.route(OWNER, "page", "页面", "Page", 1, "app.page").hidden());
    assertTrue(Contribution.card(OWNER, "card", "标题", "Card", 1, true).hidden());
    assertNotEquals(
        card, Contribution.card(ModuleId.of("other"), "card", "标题", "Card", Integer.MIN_VALUE));
    assertNotEquals(card, Contribution.card(OWNER, "other", "标题", "Card", Integer.MIN_VALUE));
    assertNotEquals(card, Contribution.card(OWNER, "card", "其他", "Card", Integer.MIN_VALUE));
    assertNotEquals(card, Contribution.card(OWNER, "card", "标题", "Other", Integer.MIN_VALUE));
    assertNotEquals(card, Contribution.card(OWNER, "card", "标题", "Card", 0));
    assertNotEquals(card, Contribution.card(OWNER, "card", "标题", "Card", Integer.MIN_VALUE, true));
    assertNotEquals(
        route, Contribution.route(OWNER, "page", "页面", "Page", Integer.MAX_VALUE, "other", true));
  }

  @Test
  public void staticFactoriesPreserveNullValidationInsteadOfKotlinParameterGuards() {
    expect(NullPointerException.class, "owner", () -> Contribution.card(null, null, null, null, 0));
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_ID_INVALID",
        () -> Contribution.card(OWNER, null, null, null, 0, true));
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_TITLE_INVALID",
        () -> Contribution.route(OWNER, "page", null, null, 0, null));
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_TITLE_EN_INVALID",
        () -> Contribution.route(OWNER, "page", "页面", null, 0, null, true));
  }

  @Test
  public void contributionViewPreservesNullableFieldsAndJavaRecordValues() {
    ContributionView view =
        new ContributionView(
            KEY, Contribution.Kind.ROUTE, OWNER, null, null, -3, false, 0x1_0000_0002L, null);
    assertEquals(KEY, view.key());
    assertEquals(Contribution.Kind.ROUTE, view.kind());
    assertSame(OWNER, view.owner());
    assertNull(view.title());
    assertNull(view.titleEn());
    assertEquals(-3, view.sortOrder());
    assertFalse(view.hidden());
    assertEquals(0x1_0000_0002L, view.generation());
    assertNull(view.routeTarget());
    assertRecordValue(
        view,
        new ContributionView(
            KEY, Contribution.Kind.ROUTE, OWNER, null, null, -3, false, 0x1_0000_0002L, null),
        new ViewRecord(
            KEY, Contribution.Kind.ROUTE, OWNER, null, null, -3, false, 0x1_0000_0002L, null));
    ContributionView populated =
        new ContributionView(
            KEY, Contribution.Kind.CARD, OWNER, "", " ", 0, true, Long.MAX_VALUE, "not validated");
    assertRecordValue(
        populated,
        new ContributionView(
            KEY, Contribution.Kind.CARD, OWNER, "", " ", 0, true, Long.MAX_VALUE, "not validated"),
        new ViewRecord(
            KEY, Contribution.Kind.CARD, OWNER, "", " ", 0, true, Long.MAX_VALUE, "not validated"));
    assertNotEquals(
        view,
        new ContributionView(
            OTHER_KEY,
            Contribution.Kind.ROUTE,
            OWNER,
            null,
            null,
            -3,
            false,
            0x1_0000_0002L,
            null));
    assertNotEquals(
        view,
        new ContributionView(
            KEY, Contribution.Kind.CARD, OWNER, null, null, -3, false, 0x1_0000_0002L, null));
    assertNotEquals(
        view,
        new ContributionView(
            KEY,
            Contribution.Kind.ROUTE,
            ModuleId.of("other"),
            null,
            null,
            -3,
            false,
            0x1_0000_0002L,
            null));
    assertNotEquals(
        view,
        new ContributionView(
            KEY, Contribution.Kind.ROUTE, OWNER, "title", null, -3, false, 0x1_0000_0002L, null));
    assertNotEquals(
        view,
        new ContributionView(
            KEY, Contribution.Kind.ROUTE, OWNER, null, "title", -3, false, 0x1_0000_0002L, null));
    assertNotEquals(
        view,
        new ContributionView(
            KEY, Contribution.Kind.ROUTE, OWNER, null, null, 0, false, 0x1_0000_0002L, null));
    assertNotEquals(
        view,
        new ContributionView(
            KEY, Contribution.Kind.ROUTE, OWNER, null, null, -3, true, 0x1_0000_0002L, null));
    assertNotEquals(
        view,
        new ContributionView(KEY, Contribution.Kind.ROUTE, OWNER, null, null, -3, false, 2, null));
    assertNotEquals(
        view,
        new ContributionView(
            KEY, Contribution.Kind.ROUTE, OWNER, null, null, -3, false, 0x1_0000_0002L, "target"));
  }

  @Test
  public void contributionViewChecksKeyThenKindAndOwnerThenGeneration() {
    for (String invalid : Arrays.asList(null, "", "bad key", "a#b#c")) {
      expect(
          IllegalArgumentException.class,
          "CONTRIBUTION_KEY_INVALID",
          () -> new ContributionView(invalid, null, null, null, null, 0, false, 0, null));
    }
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_VIEW_INVALID",
        () -> new ContributionView(KEY, null, null, null, null, 0, false, 0, null));
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_VIEW_INVALID",
        () ->
            new ContributionView(KEY, Contribution.Kind.CARD, null, null, null, 0, false, 0, null));
    expect(
        IllegalArgumentException.class,
        "CONTRIBUTION_GENERATION_INVALID",
        () ->
            new ContributionView(
                KEY, Contribution.Kind.CARD, OWNER, null, null, 0, false, 0, null));
  }

  @Test
  public void originalPackageHelpersKeepUnmangledJavaNames() {
    Contribution card = Contribution.card(OWNER, "card", "标题", "Card", 3);
    ContributionView view = ContributionView.of(card, true, 8);
    assertEquals(KEY, view.key());
    assertEquals(card.kind(), view.kind());
    assertEquals(card.owner(), view.owner());
    assertEquals(card.title(), view.title());
    assertEquals(card.titleEn(), view.titleEn());
    assertEquals(card.sortOrder(), view.sortOrder());
    assertTrue(view.hidden());
    assertEquals(8, view.generation());
    assertNull(view.routeTarget());
    assertSame(KEY, LayoutSnapshot.validateKey(KEY));
    expect(
        IllegalArgumentException.class,
        "LAYOUT_KEY_INVALID",
        () -> LayoutSnapshot.validateKey(null));
    assertThrows(NullPointerException.class, () -> ContributionView.of(null, false, 0));
  }

  @Test
  public void layoutValidationOrderAndNullElementsKeepOriginalExceptions() {
    expect(NullPointerException.class, "order", () -> new LayoutSnapshot(null, null));
    expect(NullPointerException.class, "hidden", () -> new LayoutSnapshot(List.of("bad"), null));
    expect(
        IllegalArgumentException.class,
        "LAYOUT_ORDER_DUPLICATE",
        () -> new LayoutSnapshot(List.of(KEY, KEY), Set.of("bad")));
    for (String invalid : Arrays.asList(null, "", "bad", "A#b", "a#b#c", "a".repeat(65) + "#b")) {
      expect(
          IllegalArgumentException.class,
          "LAYOUT_KEY_INVALID",
          () -> new LayoutSnapshot(Arrays.asList(KEY, invalid), Set.of()));
      expect(
          IllegalArgumentException.class,
          "LAYOUT_KEY_INVALID",
          () -> new LayoutSnapshot(List.of(KEY), new LinkedHashSet<>(Arrays.asList(KEY, invalid))));
      assertFalse(LayoutSnapshot.validKey(invalid));
    }
    assertTrue(LayoutSnapshot.validKey("a".repeat(64) + "#" + "b".repeat(64)));
  }

  @Test
  public void layoutRetainsRecordValuesAndDefensiveReadOnlyCollections() {
    List<String> order = new ArrayList<>(List.of(KEY, OTHER_KEY));
    Set<String> hidden = new LinkedHashSet<>(Set.of(KEY));
    LayoutSnapshot snapshot = new LayoutSnapshot(order, hidden);
    order.clear();
    hidden.clear();
    assertEquals(List.of(KEY, OTHER_KEY), snapshot.order());
    assertEquals(Set.of(KEY), snapshot.hidden());
    assertTrue(snapshot.isHidden(KEY));
    assertFalse(snapshot.isHidden(OTHER_KEY));
    assertRecordValue(
        snapshot,
        new LayoutSnapshot(List.of(KEY, OTHER_KEY), Set.of(KEY)),
        new LayoutRecord(snapshot.order(), snapshot.hidden()));
    assertNotEquals(snapshot, new LayoutSnapshot(List.of(OTHER_KEY, KEY), Set.of(KEY)));
    assertNotEquals(snapshot, new LayoutSnapshot(List.of(KEY, OTHER_KEY), Set.of(OTHER_KEY)));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.order().add("new#key"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.order().set(0, OTHER_KEY));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.hidden().add(OTHER_KEY));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.hidden().remove(KEY));
  }

  @Test
  public void immutableJavaCollectionNullQueriesThrowEvenWhenEmpty() {
    for (LayoutSnapshot snapshot :
        List.of(
            LayoutSnapshot.empty(),
            new LayoutSnapshot(List.of(KEY), Set.of(KEY)),
            new LayoutSnapshot(List.of(KEY, OTHER_KEY), Set.of(KEY, OTHER_KEY)))) {
      assertThrows(NullPointerException.class, () -> snapshot.order().contains(null));
      assertThrows(NullPointerException.class, () -> snapshot.hidden().contains(null));
      assertThrows(NullPointerException.class, () -> snapshot.isHidden(null));
    }
    for (Set<String> capabilities :
        List.of(Set.<String>of(), Set.of("one"), Set.of("one", "two"))) {
      ModuleDescriptor descriptor = new ModuleDescriptor(OWNER, 1, "模块", capabilities, OPTIONAL);
      assertThrows(NullPointerException.class, () -> descriptor.capabilities().contains(null));
    }
  }

  @Test
  public void layoutUpdatesKeepUnknownKeysAndLeaveOriginalUnchanged() {
    LayoutSnapshot snapshot = new LayoutSnapshot(List.of(KEY, OTHER_KEY), Set.of(OTHER_KEY));
    LayoutSnapshot hidden = snapshot.withHidden(KEY, true);
    assertNotSame(snapshot, hidden);
    assertEquals(Set.of(KEY, OTHER_KEY), hidden.hidden());
    assertEquals(Set.of(OTHER_KEY), snapshot.hidden());
    assertEquals(Set.of(KEY), hidden.withHidden(OTHER_KEY, false).hidden());
    assertEquals(
        List.of("new#key", KEY, OTHER_KEY), snapshot.mergedOrder(List.of("new#key", KEY)).order());
    assertEquals(snapshot, snapshot.mergedOrder(List.of()));
    assertEquals(Set.of(OTHER_KEY), snapshot.mergedOrder(List.of(OTHER_KEY)).hidden());
    expect(NullPointerException.class, "requested", () -> snapshot.mergedOrder(null));
    expect(
        IllegalArgumentException.class,
        "LAYOUT_ORDER_DUPLICATE",
        () -> snapshot.mergedOrder(List.of(KEY, KEY, "bad")));
    expect(
        IllegalArgumentException.class,
        "LAYOUT_KEY_INVALID",
        () -> snapshot.mergedOrder(Arrays.asList(KEY, null)));
    expect(
        IllegalArgumentException.class,
        "LAYOUT_KEY_INVALID",
        () -> snapshot.withHidden(null, true));
    expect(
        IllegalArgumentException.class,
        "LAYOUT_KEY_INVALID",
        () -> snapshot.withHidden("bad", false));
    assertEquals(new LayoutSnapshot(List.of(), Set.of()), LayoutSnapshot.empty());
  }

  @Test
  public void valueClassesStayFinalWithPrivateFinalFieldsAndNoBeanOrDataClassMethods() {
    for (Class<?> type :
        List.of(
            ModuleId.class,
            ModuleDescriptor.class,
            ContractResult.class,
            Contribution.class,
            ContributionView.class,
            LayoutSnapshot.class)) {
      assertTrue(type.getName(), Modifier.isFinal(type.getModifiers()));
      for (Field field : type.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) continue;
        assertTrue(field.toString(), Modifier.isPrivate(field.getModifiers()));
        assertTrue(field.toString(), Modifier.isFinal(field.getModifiers()));
      }
      for (Method method : type.getDeclaredMethods()) {
        if (method.isSynthetic()) continue;
        String name = method.getName();
        assertFalse(
            method.toString(),
            name.startsWith("get")
                || name.startsWith("set")
                || name.startsWith("component")
                || name.startsWith("copy"));
      }
    }
  }

  private static <T extends Throwable> void expect(
      Class<T> type, String message, ThrowingRunnable operation) {
    assertEquals(message, assertThrows(type, operation).getMessage());
  }

  private static void assertRecordValue(Object value, Object equalValue, Record reference) {
    assertEquals(value, equalValue);
    assertEquals(equalValue, value);
    assertEquals(value, value);
    assertFalse(value.equals(null));
    assertFalse(value.equals(reference));
    assertEquals(reference.hashCode(), value.hashCode());
    assertEquals(value.hashCode(), equalValue.hashCode());
    String suffix = reference.toString().substring(reference.getClass().getSimpleName().length());
    assertEquals(value.getClass().getSimpleName() + suffix, value.toString());
  }

  private record DescriptorRecord(
      ModuleId id,
      int version,
      String name,
      Set<String> capabilities,
      ModuleDescriptor.Enablement enablement) {}

  private record ResultRecord(boolean accepted, String code) {}

  private record ContributionRecord(
      Contribution.Kind kind,
      ModuleId owner,
      String id,
      String title,
      String titleEn,
      int sortOrder,
      boolean hidden,
      String routeTarget) {}

  private record ViewRecord(
      String key,
      Contribution.Kind kind,
      ModuleId owner,
      String title,
      String titleEn,
      int sortOrder,
      boolean hidden,
      long generation,
      String routeTarget) {}

  private record LayoutRecord(List<String> order, Set<String> hidden) {}
}
