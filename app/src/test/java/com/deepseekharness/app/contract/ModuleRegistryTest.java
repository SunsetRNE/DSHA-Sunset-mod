package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.contract.ModuleDescriptor.Enablement;
import com.deepseekharness.app.contract.ModuleInstance.CloseReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** C01/C02 生命周期：7 步闭环 + 重复 id、ALWAYS_ON、安全模式、旧 generation 反例。 */
public class ModuleRegistryTest {
  private static final ModuleId CARDS_ID = new ModuleId("demo.cards");

  private static ModuleDescriptor cardsDescriptor() {
    return new ModuleDescriptor(CARDS_ID, 1, "演示卡片", Set.of(), Enablement.OPTIONAL_USER);
  }

  private static ModuleFactory cardsFactory(AtomicBoolean closed) {
    return () ->
        new ModuleInstance() {
          private final List<Contribution> contributions =
              List.of(
                  Contribution.card(CARDS_ID, "beta", "卡片 B", "Card B", 10),
                  Contribution.card(CARDS_ID, "alpha", "卡片 A", "Card A", 20));

          @Override
          public List<Contribution> contributions() {
            return contributions;
          }

          @Override
          public void close(CloseReason reason) {
            closed.set(true);
          }
        };
  }

  private static ModuleFactory titledFactory(
      ModuleId owner, String title, AtomicReference<String> used) {
    return () -> {
      used.set(title);
      return new ModuleInstance() {
        private final List<Contribution> contributions =
            List.of(Contribution.card(owner, "card", title, "Card " + title, 1));

        @Override
        public List<Contribution> contributions() {
          return contributions;
        }
      };
    };
  }

  @Test
  public void sevenStepLoopRestoresLayoutDisablesCleanlyAndStalesCallbacks() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    SafeModeGate gate = new SafeModeGate();
    ModuleRegistry first = ContractTestSupport.registry(preferences, gate, events);
    AtomicBoolean closed = new AtomicBoolean();

    assertTrue(first.register(cardsDescriptor(), cardsFactory(closed)).accepted());
    assertTrue(BuiltinModules.ensureRegistered(first).accepted());
    assertTrue(first.install(CARDS_ID).accepted());

    assertEquals(
        List.of("builtin.about#about", "demo.cards#beta", "demo.cards#alpha"),
        ContractTestSupport.keys(first.contributions()));
    ContributionView about =
        ContractTestSupport.view(first.contributions(), BuiltinModules.ABOUT_CONTRIBUTION_KEY);
    assertNotNull(about);
    assertEquals(BuiltinModules.ABOUT_TITLE, about.title());
    assertEquals(BuiltinModules.ABOUT_TITLE_EN, about.titleEn());

    assertTrue(
        first
            .setContributionOrder(
                List.of("demo.cards#beta", "builtin.about#about", "demo.cards#alpha"))
            .accepted());
    assertTrue(first.setContributionHidden("demo.cards#beta", true).accepted());
    assertEquals(
        List.of("builtin.about#about", "demo.cards#alpha"),
        ContractTestSupport.keys(first.visibleContributions()));

    GenerationLease firstLease = first.lease(CARDS_ID);
    assertNotNull(firstLease);
    long firstGeneration = firstLease.generation();
    AtomicBoolean callback = new AtomicBoolean();
    assertTrue(first.dispatch(CARDS_ID, firstGeneration, () -> callback.set(true)).accepted());
    assertTrue(callback.get());

    List<DiagnosticsSink.Event> restartedEvents = new ArrayList<>();
    ModuleRegistry second =
        ContractTestSupport.registry(preferences, new SafeModeGate(), restartedEvents);
    assertTrue(second.register(cardsDescriptor(), cardsFactory(closed)).accepted());
    assertTrue(BuiltinModules.ensureRegistered(second).accepted());
    assertTrue(second.install(CARDS_ID).accepted());

    assertEquals(
        List.of("demo.cards#beta", "builtin.about#about", "demo.cards#alpha"),
        ContractTestSupport.keys(second.contributions()));
    assertTrue(ContractTestSupport.view(second.contributions(), "demo.cards#beta").hidden());
    assertFalse(
        ContractTestSupport.view(second.contributions(), BuiltinModules.ABOUT_CONTRIBUTION_KEY)
            .hidden());
    assertEquals(
        List.of("builtin.about#about", "demo.cards#alpha"),
        ContractTestSupport.keys(second.visibleContributions()));

    AtomicBoolean staleAfterRestart = new AtomicBoolean();
    ContractResult staleRestart =
        second.dispatch(CARDS_ID, firstGeneration, () -> staleAfterRestart.set(true));
    assertTrue(staleRestart.rejected());
    assertEquals(ContractCodes.STALE_GENERATION, staleRestart.code());
    assertFalse(staleAfterRestart.get());

    GenerationLease secondLease = second.lease(CARDS_ID);
    assertNotNull(secondLease);
    long secondGeneration = secondLease.generation();
    assertTrue(second.setEnabled(CARDS_ID, false).accepted());
    assertTrue(closed.get());
    assertFalse(secondLease.active());
    assertEquals(List.of("builtin.about#about"), ContractTestSupport.keys(second.contributions()));

    // C01-I3：停用只隐藏贡献，不删布局记录。
    assertEquals(
        List.of("demo.cards#beta", "builtin.about#about", "demo.cards#alpha"),
        second.layout().order());
    assertTrue(second.layout().hidden().contains("demo.cards#beta"));

    AtomicBoolean stale = new AtomicBoolean();
    ContractResult staleResult = second.dispatch(CARDS_ID, secondGeneration, () -> stale.set(true));
    assertTrue(staleResult.rejected());
    assertEquals(ContractCodes.STALE_GENERATION, staleResult.code());
    assertFalse(stale.get());
    assertFalse(secondLease.runIfActive(() -> stale.set(true)));
    assertTrue(ContractTestSupport.hasEvent(restartedEvents, ContractCodes.STALE_GENERATION));

    assertEquals(ContractCodes.UNKNOWN_CONTRIBUTION, second.revoke("demo.cards#beta").code());

    // 重新启用后恢复原位置与隐藏态（布局记录未删除）。
    assertTrue(second.setEnabled(CARDS_ID, true).accepted());
    assertEquals(
        List.of("demo.cards#beta", "builtin.about#about", "demo.cards#alpha"),
        ContractTestSupport.keys(second.contributions()));
    assertTrue(ContractTestSupport.view(second.contributions(), "demo.cards#beta").hidden());
  }

  @Test
  public void duplicateModuleIdIsRejectedWithoutPartialRegistration() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    AtomicReference<String> used = new AtomicReference<>();

    assertTrue(
        registry.register(cardsDescriptor(), titledFactory(CARDS_ID, "first", used)).accepted());
    ContractResult duplicate =
        registry.register(cardsDescriptor(), titledFactory(CARDS_ID, "second", used));
    assertTrue(duplicate.rejected());
    assertEquals(ContractCodes.MODULE_DUPLICATE_ID, duplicate.code());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_DUPLICATE_ID));

    assertTrue(registry.install(CARDS_ID).accepted());
    assertEquals("first", used.get());
    assertEquals(1, registry.contributions().size());
    assertEquals("first", registry.contributions().get(0).title());
  }

  @Test
  public void alwaysOnModuleCannotBeDisabled() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    assertTrue(BuiltinModules.ensureRegistered(registry).accepted());
    GenerationLease lease = registry.lease(BuiltinModules.ABOUT_MODULE_ID);
    assertNotNull(lease);

    ContractResult disabled = registry.setEnabled(BuiltinModules.ABOUT_MODULE_ID, false);
    assertTrue(disabled.rejected());
    assertEquals(ContractCodes.MODULE_ALWAYS_ON, disabled.code());
    assertTrue(lease.active());
    assertEquals(1, registry.contributions().size());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_ALWAYS_ON));
  }

  @Test
  public void safeModeKeepsOnlyAlwaysOnAndHostRecoveryEntry() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    SafeModeGate gate = new SafeModeGate();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, gate, events);
    AtomicBoolean closed = new AtomicBoolean();
    assertTrue(registry.register(cardsDescriptor(), cardsFactory(closed)).accepted());
    assertTrue(BuiltinModules.ensureRegistered(registry).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);
    assertEquals(3, registry.contributions().size());

    assertTrue(registry.enterSafeMode().accepted());
    assertTrue(gate.forcedSafeMode());
    assertTrue(gate.recoveryEntryAvailable());
    assertTrue(closed.get());
    assertFalse(lease.active());
    assertEquals(
        List.of("builtin.about#about"), ContractTestSupport.keys(registry.contributions()));
    assertEquals(ContractCodes.SAFE_MODE_RESTRICTED, registry.setEnabled(CARDS_ID, true).code());
    assertEquals(ContractCodes.SAFE_MODE_RESTRICTED, registry.install(CARDS_ID).code());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.SAFE_MODE_ENTERED));

    registry.exitSafeMode();
    assertFalse(gate.forcedSafeMode());
    assertTrue(registry.setEnabled(CARDS_ID, true).accepted());
    assertEquals(3, registry.contributions().size());
  }

  @Test
  public void closeIsIdempotentAndRevokesContributions() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    AtomicBoolean closed = new AtomicBoolean();
    assertTrue(registry.register(cardsDescriptor(), cardsFactory(closed)).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);

    assertTrue(registry.close(CARDS_ID, CloseReason.HOST_CLOSE).accepted());
    assertTrue(closed.get());
    assertFalse(lease.active());
    assertTrue(registry.contributions().isEmpty());
    assertTrue(registry.close(CARDS_ID, CloseReason.HOST_CLOSE).accepted());
  }

  @Test
  public void unknownModuleIsRejectedForEveryLifecycleAction() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    ModuleId missing = new ModuleId("demo.missing");

    assertEquals(ContractCodes.MODULE_UNKNOWN, registry.install(missing).code());
    assertEquals(ContractCodes.MODULE_UNKNOWN, registry.setEnabled(missing, true).code());
    assertEquals(
        ContractCodes.MODULE_UNKNOWN, registry.close(missing, CloseReason.HOST_CLOSE).code());
    assertEquals(ContractCodes.MODULE_UNKNOWN, registry.dispatch(missing, 1, () -> {}).code());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_UNKNOWN));
  }

  @Test
  public void failedInstallLeavesNoPartialContributionOrLease() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    ModuleFactory broken =
        () -> {
          throw new IllegalStateException("boom");
        };

    assertTrue(registry.register(cardsDescriptor(), broken).accepted());
    ContractResult installed = registry.install(CARDS_ID);
    assertTrue(installed.rejected());
    assertEquals(ContractCodes.MODULE_INSTALL_FAILED, installed.code());
    assertTrue(registry.contributions().isEmpty());
    assertNull(registry.lease(CARDS_ID));
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_INSTALL_FAILED));
  }
}
