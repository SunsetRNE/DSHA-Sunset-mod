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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  @Test
  public void closeRevokesContributionsAndRoutesWhenInstanceCloseThrows() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    AtomicBoolean closeCalled = new AtomicBoolean();
    ModuleFactory factory =
        () ->
            new ModuleInstance() {
              private final List<Contribution> contributions =
                  List.of(
                      Contribution.card(CARDS_ID, "beta", "卡片 B", "Card B", 10),
                      Contribution.route(CARDS_ID, "page", "页面", "Page", 20, "app.page"));

              @Override
              public List<Contribution> contributions() {
                return contributions;
              }

              @Override
              public void close(CloseReason reason) {
                closeCalled.set(true);
                throw new IllegalStateException("close boom");
              }
            };

    assertTrue(registry.register(cardsDescriptor(), factory).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);

    ContractResult closed = registry.close(CARDS_ID, CloseReason.HOST_CLOSE);

    assertTrue(closeCalled.get());
    assertTrue(closed.rejected());
    assertEquals(ContractCodes.MODULE_CLOSE_FAILED, closed.code());
    assertFalse(lease.active());
    assertTrue(registry.contributions().isEmpty());
    assertTrue(registry.resolveRoute("demo.cards#page").hostFallback());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_CLOSE_FAILED));
    assertTrue(registry.close(CARDS_ID, CloseReason.HOST_CLOSE).accepted());
  }

  @Test
  public void closeCleansUpThenRethrowsErrorFromInstanceClose() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    AtomicBoolean closeCalled = new AtomicBoolean();
    ModuleFactory factory =
        () ->
            new ModuleInstance() {
              private final List<Contribution> contributions =
                  List.of(Contribution.card(CARDS_ID, "beta", "卡片 B", "Card B", 10));

              @Override
              public List<Contribution> contributions() {
                return contributions;
              }

              @Override
              public void close(CloseReason reason) {
                closeCalled.set(true);
                throw new AssertionError("module close error");
              }
            };

    assertTrue(registry.register(cardsDescriptor(), factory).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);

    AssertionError thrown = null;
    try {
      registry.close(CARDS_ID, CloseReason.HOST_CLOSE);
    } catch (AssertionError error) {
      thrown = error;
    }

    assertNotNull(thrown);
    assertTrue(closeCalled.get());
    assertFalse(lease.active());
    assertTrue(registry.contributions().isEmpty());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_CLOSE_FAILED));
  }

  @Test
  public void leaseCallbackRuntimeExceptionIsDiagnosedAndDoesNotEscape() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    assertTrue(registry.register(cardsDescriptor(), cardsFactory(new AtomicBoolean())).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);

    AtomicBoolean ran = new AtomicBoolean();
    boolean delivered =
        lease.runIfActive(
            () -> {
              ran.set(true);
              throw new IllegalStateException("callback boom");
            });

    assertTrue(ran.get());
    assertFalse(delivered);
    assertTrue(lease.active());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_CALLBACK_FAILED));

    ContractResult dispatched =
        registry.dispatch(
            CARDS_ID,
            lease.generation(),
            () -> {
              throw new IllegalStateException("dispatch boom");
            });
    assertTrue(dispatched.rejected());
    assertEquals(ContractCodes.MODULE_CALLBACK_FAILED, dispatched.code());
  }

  @Test
  public void leaseInvalidationWaitsForRunningCallbackAndStaleCallbacksAreDropped()
      throws Exception {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    assertTrue(registry.register(cardsDescriptor(), cardsFactory(new AtomicBoolean())).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);

    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch invalidatorStarted = new CountDownLatch(1);
    CountDownLatch invalidated = new CountDownLatch(1);
    AtomicBoolean callbackRan = new AtomicBoolean();
    Thread runner =
        new Thread(
            () ->
                lease.runIfActive(
                    () -> {
                      callbackRan.set(true);
                      entered.countDown();
                      await(release);
                    }));
    runner.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));

    Thread invalidator =
        new Thread(
            () -> {
              invalidatorStarted.countDown();
              lease.invalidate();
              invalidated.countDown();
            });
    invalidator.start();
    assertTrue(invalidatorStarted.await(2, TimeUnit.SECONDS));
    assertFalse(invalidated.await(100, TimeUnit.MILLISECONDS));

    release.countDown();
    runner.join(2_000);
    assertTrue(invalidated.await(2, TimeUnit.SECONDS));
    invalidator.join(2_000);

    assertTrue(callbackRan.get());
    assertFalse(lease.active());
    assertFalse(runner.isAlive());
    assertFalse(invalidator.isAlive());
    AtomicBoolean staleRan = new AtomicBoolean();
    assertFalse(lease.runIfActive(() -> staleRan.set(true)));
    assertFalse(staleRan.get());
  }

  @Test
  public void dispatchDoesNotHoldRegistryLockWhileEnteringLease() throws Exception {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    CountDownLatch saving = new CountDownLatch(1);
    CountDownLatch releaseSave = new CountDownLatch(1);
    LayoutStore blockingLayout =
        new LayoutStore() {
          @Override
          public LayoutSnapshot load() {
            return LayoutSnapshot.empty();
          }

          @Override
          public void save(LayoutSnapshot snapshot) {
            saving.countDown();
            await(releaseSave);
          }
        };
    DiagnosticsSink sink = DiagnosticsSink.forwarding(events::add);
    ModuleRegistry registry = new ModuleRegistry(blockingLayout, new SafeModeGate(), sink);
    assertTrue(registry.register(cardsDescriptor(), cardsFactory(new AtomicBoolean())).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);
    long generation = lease.generation();

    ExecutorService pool = Executors.newCachedThreadPool();
    CountDownLatch callbackHoldsLease = new CountDownLatch(1);
    CountDownLatch dispatchStarted = new CountDownLatch(1);
    try {
      // 线程 C：持 registry 锁阻塞在布局保存（setContributionHidden 是 synchronized）。
      Future<?> holder =
          pool.submit(
              () -> registry.setContributionHidden("demo.cards#beta", true));
      assertTrue(saving.await(2, TimeUnit.SECONDS));

      // 线程 B 先排队等 registry 锁。
      Future<?> dispatcher =
          pool.submit(
              () -> {
                dispatchStarted.countDown();
                return registry.dispatch(CARDS_ID, generation, () -> {});
              });
      assertTrue(dispatchStarted.await(2, TimeUnit.SECONDS));
      Thread.sleep(150);
      assertFalse(dispatcher.isDone());

      // 线程 A：拿到租约（空闲）后在回调里回头调用 registry，被 C 的锁挡住。
      // 旧实现（dispatch 持 registry 锁再等 lease）在派发者拿到锁后会与 A 形成死锁。
      Future<?> callback =
          pool.submit(
              () ->
                  lease.runIfActive(
                      () -> {
                        callbackHoldsLease.countDown();
                        registry.contributions();
                      }));
      assertTrue(callbackHoldsLease.await(2, TimeUnit.SECONDS));

      releaseSave.countDown();
      holder.get(5, TimeUnit.SECONDS);
      dispatcher.get(5, TimeUnit.SECONDS);
      callback.get(5, TimeUnit.SECONDS);
      assertTrue(lease.active());
    } finally {
      releaseSave.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  public void closeCleanupContinuesWhenDiagnosticsThrowsDuringCloseFailure() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    DiagnosticsSink throwing =
        DiagnosticsSink.forwarding(
            event -> {
              if (ContractCodes.MODULE_CLOSE_FAILED.equals(event.code()))
                throw new AssertionError("diagnostic downstream boom");
              events.add(event);
            });
    ModuleRegistry registry =
        new ModuleRegistry(
            new SharedPreferencesLayoutStore(preferences, DiagnosticsSink.discarding()),
            new SafeModeGate(),
            throwing);
    ModuleFactory factory =
        () ->
            new ModuleInstance() {
              private final List<Contribution> contributions =
                  List.of(
                      Contribution.card(CARDS_ID, "beta", "卡片 B", "Card B", 10),
                      Contribution.route(CARDS_ID, "page", "页面", "Page", 20, "app.page"));

              @Override
              public List<Contribution> contributions() {
                return contributions;
              }

              @Override
              public void close(CloseReason reason) {
                throw new IllegalStateException("close boom");
              }
            };

    assertTrue(registry.register(cardsDescriptor(), factory).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());

    // 诊断下游的二次异常不得中断贡献/路由撤销，也不得掩盖 close 的类型化结果。
    ContractResult closed = registry.close(CARDS_ID, CloseReason.HOST_CLOSE);
    assertTrue(closed.rejected());
    assertEquals(ContractCodes.MODULE_CLOSE_FAILED, closed.code());
    assertTrue(registry.contributions().isEmpty());
    assertTrue(registry.resolveRoute("demo.cards#page").hostFallback());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.MODULE_DISABLED));
    assertTrue(registry.close(CARDS_ID, CloseReason.HOST_CLOSE).accepted());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", error);
    }
  }
}
