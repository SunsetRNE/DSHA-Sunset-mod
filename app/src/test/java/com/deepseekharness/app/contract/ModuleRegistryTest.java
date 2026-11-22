package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
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
import java.util.concurrent.atomic.AtomicInteger;
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
      Future<?> holder = pool.submit(() -> registry.setContributionHidden("demo.cards#beta", true));
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

  @Test
  public void closeDoesNotHoldRegistryLockWhileInvalidatingLease() throws Exception {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    assertTrue(registry.register(cardsDescriptor(), cardsFactory(new AtomicBoolean())).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);

    ExecutorService pool = Executors.newCachedThreadPool();
    CountDownLatch callbackHoldsLease = new CountDownLatch(1);
    CountDownLatch callbackDone = new CountDownLatch(1);
    try {
      Future<?> callback =
          pool.submit(
              () ->
                  lease.runIfActive(
                      () -> {
                        callbackHoldsLease.countDown();
                        // 回调回头调用 registry：close 若持 registry 锁 invalidate 会死锁。
                        registry.contributions();
                        callbackDone.countDown();
                      }));
      assertTrue(callbackHoldsLease.await(2, TimeUnit.SECONDS));

      Future<?> closer = pool.submit(() -> registry.close(CARDS_ID, CloseReason.USER_DISABLED));
      ContractResult closed = (ContractResult) closer.get(5, TimeUnit.SECONDS);
      assertTrue(closed.accepted());
      callback.get(5, TimeUnit.SECONDS);
      assertEquals("回调必须完整跑完", 0, callbackDone.getCount());
      assertTrue(registry.contributions().isEmpty());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void setEnabledDisableDoesNotHoldRegistryLockWhileInvalidatingLease() throws Exception {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    assertTrue(registry.register(cardsDescriptor(), cardsFactory(new AtomicBoolean())).accepted());
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease lease = registry.lease(CARDS_ID);
    assertNotNull(lease);

    ExecutorService pool = Executors.newCachedThreadPool();
    CountDownLatch callbackHoldsLease = new CountDownLatch(1);
    try {
      Future<?> callback =
          pool.submit(
              () ->
                  lease.runIfActive(
                      () -> {
                        callbackHoldsLease.countDown();
                        registry.contributions();
                      }));
      assertTrue(callbackHoldsLease.await(2, TimeUnit.SECONDS));

      Future<?> closer = pool.submit(() -> registry.setEnabled(CARDS_ID, false));
      ContractResult disabled = (ContractResult) closer.get(5, TimeUnit.SECONDS);
      assertTrue(disabled.accepted());
      callback.get(5, TimeUnit.SECONDS);
      assertTrue(registry.contributions().isEmpty());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void slowFactoryDoesNotBlockCloseOrPublishItsLateInstance() throws Exception {
    assertPendingInstallInvalidated(false, false, CloseReason.HOST_CLOSE);
  }

  @Test
  public void setEnabledInstallDoesNotKeepAnOuterRegistryLock() throws Exception {
    assertPendingInstallInvalidated(true, false, CloseReason.USER_DISABLED);
  }

  @Test
  public void slowContributionsDoNotBlockDisableOrPublishAfterIt() throws Exception {
    assertPendingInstallInvalidated(false, true, CloseReason.USER_DISABLED);
  }

  @Test
  public void safeModeEnterAndExitStillInvalidatePendingFactory() throws Exception {
    assertPendingInstallInvalidated(false, false, CloseReason.SAFE_MODE);
  }

  @Test
  public void safeModeEnterAndExitStillInvalidatePendingContributions() throws Exception {
    assertPendingInstallInvalidated(true, true, CloseReason.SAFE_MODE);
  }

  @Test
  public void cancelledFactoryCannotClearANewerReservation() throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    CountDownLatch releaseSecond = new CountDownLatch(1);
    AtomicInteger created = new AtomicInteger();
    TrackedInstance first = new TrackedInstance("app.first");
    TrackedInstance second = new TrackedInstance("app.second");
    registry.register(
        cardsDescriptor(),
        () -> {
          int number = created.incrementAndGet();
          if (number == 1) {
            firstEntered.countDown();
            await(releaseFirst);
            return first;
          }
          assertEquals(2, number);
          secondEntered.countDown();
          await(releaseSecond);
          return second;
        });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ContractResult> old = pool.submit(() -> registry.install(CARDS_ID));
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
      assertTrue(registry.close(CARDS_ID, null).accepted());
      Future<ContractResult> fresh = pool.submit(() -> registry.setEnabled(CARDS_ID, true));
      assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
      releaseFirst.countDown();
      assertEquals(ContractCodes.STALE_GENERATION, old.get(5, TimeUnit.SECONDS).code());
      assertEquals(1, first.closed.get());
      // 旧 finally 若无条件清预约，会让这次调用重入第三个 factory。
      assertEquals(ContractCodes.MODULE_INSTALL_FAILED, registry.install(CARDS_ID).code());
      assertEquals(2, created.get());
      assertNull(registry.lease(CARDS_ID));
      releaseSecond.countDown();
      assertTrue(fresh.get(5, TimeUnit.SECONDS).accepted());
      GenerationLease lease = registry.lease(CARDS_ID);
      assertNotNull(lease);
      assertTrue(registry.install(CARDS_ID).accepted());
      assertSame(lease, registry.lease(CARDS_ID));
      assertEquals(2, registry.contributions().size());
      assertEquals("app.second", registry.resolveRoute("demo.cards#page").key());
      assertEquals(0, second.closed.get());
      assertEquals(2, created.get());
    } finally {
      releaseFirst.countDown();
      releaseSecond.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void lateContributionsCannotRevokeAnAlreadyPublishedReplacement() throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger created = new AtomicInteger();
    TrackedInstance oldInstance = new TrackedInstance("app.old");
    TrackedInstance replacement = new TrackedInstance("app.new");
    registry.register(
        cardsDescriptor(),
        () -> {
          if (created.incrementAndGet() != 1) return replacement;
          return new ModuleInstance() {
            @Override
            public List<Contribution> contributions() {
              entered.countDown();
              await(release);
              return oldInstance.contributions();
            }

            @Override
            public void close(CloseReason reason) {
              assertFalse(Thread.holdsLock(registry));
              oldInstance.close(reason);
            }
          };
        });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<ContractResult> old = pool.submit(() -> registry.install(CARDS_ID));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(registry.setEnabled(CARDS_ID, false).accepted());
      assertTrue(registry.install(CARDS_ID).accepted());
      GenerationLease current = registry.lease(CARDS_ID);
      assertNotNull(current);
      release.countDown();
      assertEquals(ContractCodes.STALE_GENERATION, old.get(5, TimeUnit.SECONDS).code());
      assertEquals(1, oldInstance.closed.get());
      assertEquals(CloseReason.USER_DISABLED, oldInstance.reason.get());
      assertEquals(0, replacement.closed.get());
      assertSame(current, registry.lease(CARDS_ID));
      assertTrue(current.active());
      assertTrue(registry.dispatch(CARDS_ID, current.generation(), () -> {}).accepted());
      assertEquals(2, registry.contributions().size());
      for (ContributionView view : registry.contributions())
        assertEquals(current.generation(), view.generation());
      assertEquals("app.new", registry.resolveRoute("demo.cards#page").key());
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void concurrentInstallPublishesOnceAndOtherModulesDoNotCancelItsGeneration()
      throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger created = new AtomicInteger();
    registry.register(
        cardsDescriptor(),
        () -> {
          assertFalse(Thread.holdsLock(registry));
          created.incrementAndGet();
          entered.countDown();
          await(release);
          return new TrackedInstance("app.only");
        });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ContractResult> installing = pool.submit(() -> registry.install(CARDS_ID));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Future<ContractResult> duplicate = pool.submit(() -> registry.setEnabled(CARDS_ID, true));
      assertEquals(ContractCodes.MODULE_INSTALL_FAILED, duplicate.get(2, TimeUnit.SECONDS).code());
      assertTrue(BuiltinModules.ensureRegistered(registry).accepted());
      GenerationLease about = registry.lease(BuiltinModules.ABOUT_MODULE_ID);
      assertNotNull(about);
      assertEquals(1, created.get());
      release.countDown();
      assertTrue(installing.get(5, TimeUnit.SECONDS).accepted());
      GenerationLease cards = registry.lease(CARDS_ID);
      assertNotNull(cards);
      assertTrue(cards.active());
      assertTrue(about.active());
      assertTrue(registry.install(CARDS_ID).accepted());
      assertSame(cards, registry.lease(CARDS_ID));
      assertEquals(1, created.get());
      assertEquals(3, registry.contributions().size());
      assertEquals("app.only", registry.resolveRoute("demo.cards#page").key());
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void pendingAlwaysOnInstallSurvivesDisableAndSafeMode() throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TrackedInstance instance = new TrackedInstance("app.always");
    registry.register(
        new ModuleDescriptor(CARDS_ID, 1, "常开", Set.of(), Enablement.ALWAYS_ON),
        () -> {
          entered.countDown();
          await(release);
          return instance;
        });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<ContractResult> installing = pool.submit(() -> registry.setEnabled(CARDS_ID, true));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(ContractCodes.MODULE_ALWAYS_ON, registry.setEnabled(CARDS_ID, false).code());
      assertTrue(registry.enterSafeMode().accepted());
      release.countDown();
      assertTrue(installing.get(5, TimeUnit.SECONDS).accepted());
      assertTrue(registry.safeModeGate().forcedSafeMode());
      assertTrue(registry.lease(CARDS_ID).active());
      assertEquals(2, registry.contributions().size());
      assertEquals("app.always", registry.resolveRoute("demo.cards#page").key());
      assertEquals(0, instance.closed.get());
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void reentrantFactoryCanCloseItsOwnAttemptWithoutPublishing() {
    ModuleRegistry registry = concurrentRegistry();
    TrackedInstance instance = new TrackedInstance("app.cancelled");
    registry.register(
        cardsDescriptor(),
        () -> {
          assertFalse(Thread.holdsLock(registry));
          assertEquals(ContractCodes.MODULE_INSTALL_FAILED, registry.install(CARDS_ID).code());
          assertTrue(registry.close(CARDS_ID, null).accepted());
          return instance;
        });
    assertEquals(ContractCodes.STALE_GENERATION, registry.setEnabled(CARDS_ID, true).code());
    assertEquals(1, instance.closed.get());
    assertEquals(CloseReason.HOST_CLOSE, instance.reason.get());
    assertFalse(registry.isInstalled(CARDS_ID));
    assertNull(registry.lease(CARDS_ID));
    assertTrue(registry.contributions().isEmpty());
  }

  @Test
  public void factoryRuntimeExceptionAndErrorBothReleaseTheirReservation() {
    ModuleRegistry registry = concurrentRegistry();
    AtomicInteger attempts = new AtomicInteger();
    AssertionError failure = new AssertionError("factory");
    registry.register(
        cardsDescriptor(),
        () -> {
          assertFalse(Thread.holdsLock(registry));
          int number = attempts.incrementAndGet();
          if (number == 1) throw new IllegalStateException("factory");
          if (number == 2) throw failure;
          return new TrackedInstance("app.retry");
        });
    assertEquals(ContractCodes.MODULE_INSTALL_FAILED, registry.install(CARDS_ID).code());
    assertSame(failure, assertThrows(AssertionError.class, () -> registry.install(CARDS_ID)));
    assertFalse(registry.isInstalled(CARDS_ID));
    assertNull(registry.lease(CARDS_ID));
    assertTrue(registry.install(CARDS_ID).accepted());
    assertEquals(3, attempts.get());
  }

  @Test
  public void contributionFailuresCleanCandidatesOutsideTheLockAndAllowRetry() {
    ModuleRegistry registry = concurrentRegistry();
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    AssertionError failure = new AssertionError("contributions");
    registry.register(
        cardsDescriptor(),
        () -> {
          int number = attempts.incrementAndGet();
          return new ModuleInstance() {
            @Override
            public List<Contribution> contributions() {
              assertFalse(Thread.holdsLock(registry));
              if (number == 1) throw new IllegalStateException("contributions");
              if (number == 2) throw failure;
              return List.of();
            }

            @Override
            public void close(CloseReason reason) {
              assertFalse(Thread.holdsLock(registry));
              assertEquals(CloseReason.HOST_CLOSE, reason);
              closed.incrementAndGet();
            }
          };
        });
    assertEquals(ContractCodes.MODULE_INSTALL_FAILED, registry.install(CARDS_ID).code());
    assertSame(failure, assertThrows(AssertionError.class, () -> registry.install(CARDS_ID)));
    assertEquals(2, closed.get());
    assertNull(registry.lease(CARDS_ID));
    assertTrue(registry.contributions().isEmpty());
    assertTrue(registry.install(CARDS_ID).accepted());
    assertEquals(3, attempts.get());
    assertEquals(2, closed.get());
  }

  @Test
  public void installedCloseRejectsSameModuleReinstallAndReleasesGateAfterError() throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch closing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger created = new AtomicInteger();
    AssertionError failure = new AssertionError("close");
    registry.register(
        cardsDescriptor(),
        () -> {
          if (created.incrementAndGet() != 1) return new TrackedInstance("app.new");
          return new ModuleInstance() {
            @Override
            public List<Contribution> contributions() {
              return new TrackedInstance("app.old").contributions();
            }

            @Override
            public void close(CloseReason reason) {
              assertFalse(Thread.holdsLock(registry));
              closing.countDown();
              await(release);
              throw failure;
            }
          };
        });
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease oldLease = registry.lease(CARDS_ID);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<ContractResult> oldClose = pool.submit(() -> registry.close(CARDS_ID, null));
      assertTrue(closing.await(5, TimeUnit.SECONDS));
      assertFalse(oldLease.active());
      // 即使调用者先手动 revoke 旧 key，同模块在旧清理结束前也不能发布新贡献。
      assertTrue(registry.revoke("demo.cards#page").accepted());
      assertEquals(ContractCodes.MODULE_INSTALL_FAILED, registry.install(CARDS_ID).code());
      assertEquals(1, created.get());
      assertTrue(BuiltinModules.ensureRegistered(registry).accepted());
      release.countDown();
      assertSame(
          failure,
          assertThrows(
                  java.util.concurrent.ExecutionException.class,
                  () -> oldClose.get(5, TimeUnit.SECONDS))
              .getCause());
      assertNull(registry.lease(CARDS_ID));
      assertTrue(registry.resolveRoute("demo.cards#page").hostFallback());
      assertTrue(registry.install(CARDS_ID).accepted());
      assertTrue(registry.lease(CARDS_ID).active());
      assertEquals("app.new", registry.resolveRoute("demo.cards#page").key());
      assertEquals(2, created.get());
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void candidateCleanupErrorCannotHideTheFirstContributionError() {
    ModuleRegistry registry = concurrentRegistry();
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    AssertionError first = new AssertionError("contributions first");
    AssertionError cleanup = new AssertionError("cleanup second");
    registry.register(
        cardsDescriptor(),
        () -> {
          if (attempts.incrementAndGet() != 1) return new TrackedInstance("app.retry");
          return new ModuleInstance() {
            @Override
            public List<Contribution> contributions() {
              throw first;
            }

            @Override
            public void close(CloseReason reason) {
              assertFalse(Thread.holdsLock(registry));
              closed.incrementAndGet();
              throw cleanup;
            }
          };
        });
    assertSame(first, assertThrows(AssertionError.class, () -> registry.install(CARDS_ID)));
    assertEquals(1, closed.get());
    assertFalse(registry.isInstalled(CARDS_ID));
    assertNull(registry.lease(CARDS_ID));
    assertTrue(registry.contributions().isEmpty());
    assertTrue(registry.install(CARDS_ID).accepted());
  }

  @Test
  public void slowContributionIteratorDoesNotHoldRegistryLockOrPublishAfterClose()
      throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TrackedInstance instance = new TrackedInstance("app.iterator");
    registry.register(
        cardsDescriptor(),
        () ->
            new ModuleInstance() {
              @Override
              public List<Contribution> contributions() {
                return new java.util.AbstractList<>() {
                  @Override
                  public java.util.Iterator<Contribution> iterator() {
                    assertFalse(Thread.holdsLock(registry));
                    entered.countDown();
                    await(release);
                    return instance.contributions().iterator();
                  }

                  @Override
                  public Contribution get(int index) {
                    assertFalse(Thread.holdsLock(registry));
                    return instance.contributions().get(index);
                  }

                  @Override
                  public int size() {
                    assertFalse(Thread.holdsLock(registry));
                    return 2;
                  }
                };
              }

              @Override
              public void close(CloseReason reason) {
                assertFalse(Thread.holdsLock(registry));
                instance.close(reason);
              }
            });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ContractResult> installing = pool.submit(() -> registry.install(CARDS_ID));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(
          pool.submit(() -> registry.close(CARDS_ID, null)).get(2, TimeUnit.SECONDS).accepted());
      release.countDown();
      assertEquals(ContractCodes.STALE_GENERATION, installing.get(5, TimeUnit.SECONDS).code());
      assertEquals(1, instance.closed.get());
      assertNull(registry.lease(CARDS_ID));
      assertTrue(registry.contributions().isEmpty());
      assertTrue(registry.resolveRoute("demo.cards#page").hostFallback());
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void safeModeInvalidatesEveryPendingInstallBeforeAnySlowClose() throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch closing = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);
    CountDownLatch creating = new CountDownLatch(1);
    CountDownLatch releaseFactory = new CountDownLatch(1);
    ModuleId pendingId = new ModuleId("demo.pending");
    AtomicInteger created = new AtomicInteger();
    AtomicInteger lateClosed = new AtomicInteger();
    AtomicReference<CloseReason> lateReason = new AtomicReference<>();
    AtomicInteger replacementClosed = new AtomicInteger();
    registry.register(
        cardsDescriptor(),
        () ->
            new ModuleInstance() {
              @Override
              public List<Contribution> contributions() {
                return List.of();
              }

              @Override
              public void close(CloseReason reason) {
                assertFalse(Thread.holdsLock(registry));
                closing.countDown();
                await(releaseClose);
              }
            });
    registry.register(
        ContractTestSupport.descriptor("demo.pending", Enablement.OPTIONAL_USER),
        () -> {
          boolean old = created.incrementAndGet() == 1;
          if (old) {
            creating.countDown();
            await(releaseFactory);
          }
          return new ModuleInstance() {
            @Override
            public List<Contribution> contributions() {
              return List.of(
                  Contribution.route(
                      pendingId, "page", "页面", "Page", 0, old ? "app.old" : "app.new"));
            }

            @Override
            public void close(CloseReason reason) {
              if (old) {
                lateClosed.incrementAndGet();
                lateReason.set(reason);
              } else replacementClosed.incrementAndGet();
            }
          };
        });
    assertTrue(registry.install(CARDS_ID).accepted());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ContractResult> old = pool.submit(() -> registry.install(pendingId));
      assertTrue(creating.await(5, TimeUnit.SECONDS));
      Future<ContractResult> safe = pool.submit(registry::enterSafeMode);
      assertTrue(closing.await(5, TimeUnit.SECONDS));
      registry.exitSafeMode();
      assertTrue(registry.install(pendingId).accepted());
      GenerationLease replacement = registry.lease(pendingId);
      assertNotNull(replacement);
      releaseClose.countDown();
      assertTrue(safe.get(5, TimeUnit.SECONDS).accepted());
      releaseFactory.countDown();
      assertEquals(ContractCodes.STALE_GENERATION, old.get(5, TimeUnit.SECONDS).code());
      assertEquals(1, lateClosed.get());
      assertEquals(CloseReason.SAFE_MODE, lateReason.get());
      assertEquals(0, replacementClosed.get());
      assertSame(replacement, registry.lease(pendingId));
      assertTrue(replacement.active());
      assertEquals("app.new", registry.resolveRoute("demo.pending#page").key());
    } finally {
      releaseClose.countDown();
      releaseFactory.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void safeModeCleansAllDetachedModulesBeforeRethrowingFirstCloseError() {
    ModuleRegistry registry = concurrentRegistry();
    AssertionError failure = new AssertionError("first close");
    AtomicInteger closed = new AtomicInteger();
    ModuleId secondId = new ModuleId("demo.second");
    registry.register(
        cardsDescriptor(),
        () ->
            new ModuleInstance() {
              @Override
              public List<Contribution> contributions() {
                return new TrackedInstance("app.first").contributions();
              }

              @Override
              public void close(CloseReason reason) {
                throw failure;
              }
            });
    registry.register(
        ContractTestSupport.descriptor("demo.second", Enablement.OPTIONAL_USER),
        () ->
            new ModuleInstance() {
              @Override
              public List<Contribution> contributions() {
                return List.of(Contribution.route(secondId, "page", "页面", "Page", 0, "app.second"));
              }

              @Override
              public void close(CloseReason reason) {
                closed.incrementAndGet();
              }
            });
    assertTrue(registry.install(CARDS_ID).accepted());
    assertTrue(registry.install(secondId).accepted());
    assertTrue(BuiltinModules.ensureRegistered(registry).accepted());
    GenerationLease firstLease = registry.lease(CARDS_ID);
    GenerationLease secondLease = registry.lease(secondId);
    assertSame(failure, assertThrows(AssertionError.class, registry::enterSafeMode));
    assertFalse(firstLease.active());
    assertFalse(secondLease.active());
    assertEquals(1, closed.get());
    assertNull(registry.lease(CARDS_ID));
    assertNull(registry.lease(secondId));
    assertEquals(
        List.of("builtin.about#about"), ContractTestSupport.keys(registry.contributions()));
    registry.exitSafeMode();
    assertTrue(registry.resolveRoute("demo.cards#page").hostFallback());
    assertTrue(registry.resolveRoute("demo.second#page").hostFallback());
    assertTrue(registry.install(secondId).accepted());
  }

  @Test
  public void staleFactoryMustNotCloseAnAliasAlreadyPublishedByANewerInstall() throws Exception {
    assertStaleFactoryDoesNotOwnSharedCandidate(true);
  }

  @Test
  public void staleFactoryMustNotCloseAnAliasStillInANewerContributionCallback() throws Exception {
    assertStaleFactoryDoesNotOwnSharedCandidate(false);
  }

  private static void assertStaleFactoryDoesNotOwnSharedCandidate(boolean published)
      throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch creating = new CountDownLatch(1);
    CountDownLatch releaseFactory = new CountDownLatch(1);
    CountDownLatch contributing = new CountDownLatch(1);
    CountDownLatch releaseContributions = new CountDownLatch(published ? 0 : 1);
    AtomicInteger created = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    ModuleInstance shared =
        new ModuleInstance() {
          @Override
          public List<Contribution> contributions() {
            contributing.countDown();
            await(releaseContributions);
            return new TrackedInstance("app.shared").contributions();
          }

          @Override
          public void close(CloseReason reason) {
            closed.incrementAndGet();
          }
        };
    registry.register(
        cardsDescriptor(),
        () -> {
          if (created.incrementAndGet() == 1) {
            creating.countDown();
            await(releaseFactory);
          }
          return shared;
        });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ContractResult> old = pool.submit(() -> registry.install(CARDS_ID));
      assertTrue(creating.await(5, TimeUnit.SECONDS));
      assertTrue(registry.close(CARDS_ID, null).accepted());
      Future<ContractResult> replacement = pool.submit(() -> registry.install(CARDS_ID));
      assertTrue(contributing.await(5, TimeUnit.SECONDS));
      if (published) assertTrue(replacement.get(5, TimeUnit.SECONDS).accepted());
      releaseFactory.countDown();
      assertEquals(ContractCodes.STALE_GENERATION, old.get(5, TimeUnit.SECONDS).code());
      assertEquals("旧安装没有这个共享实例的清理权", 0, closed.get());
      releaseContributions.countDown();
      assertTrue(replacement.get(5, TimeUnit.SECONDS).accepted());
      assertTrue(registry.lease(CARDS_ID).active());
      assertEquals("app.shared", registry.resolveRoute("demo.cards#page").key());
      assertEquals(2, registry.contributions().size());
      assertTrue(registry.close(CARDS_ID, null).accepted());
      assertEquals(1, closed.get());
    } finally {
      releaseFactory.countDown();
      releaseContributions.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void newerInstallCannotPublishAnAliasWhileOldCandidateCleanupIsRunning() throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch contributing = new CountDownLatch(1);
    CountDownLatch releaseContributions = new CountDownLatch(1);
    CountDownLatch closing = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);
    AtomicInteger created = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    ModuleInstance shared =
        new ModuleInstance() {
          @Override
          public List<Contribution> contributions() {
            contributing.countDown();
            await(releaseContributions);
            return List.of();
          }

          @Override
          public void close(CloseReason reason) {
            closed.incrementAndGet();
            closing.countDown();
            await(releaseClose);
          }
        };
    registry.register(
        cardsDescriptor(),
        () -> created.incrementAndGet() <= 2 ? shared : new TrackedInstance("app.new"));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<ContractResult> old = pool.submit(() -> registry.install(CARDS_ID));
      assertTrue(contributing.await(5, TimeUnit.SECONDS));
      assertTrue(registry.close(CARDS_ID, null).accepted());
      releaseContributions.countDown();
      assertTrue(closing.await(5, TimeUnit.SECONDS));
      assertEquals(ContractCodes.MODULE_INSTALL_FAILED, registry.install(CARDS_ID).code());
      assertFalse(registry.isInstalled(CARDS_ID));
      assertNull(registry.lease(CARDS_ID));
      assertEquals(1, closed.get());
      releaseClose.countDown();
      assertEquals(ContractCodes.STALE_GENERATION, old.get(5, TimeUnit.SECONDS).code());
      assertTrue(registry.install(CARDS_ID).accepted());
      assertEquals("app.new", registry.resolveRoute("demo.cards#page").key());
    } finally {
      releaseContributions.countDown();
      releaseClose.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void instanceIdentityIsOwnedAcrossModulesWithoutCallingModuleEqualsOrHashCode() {
    ModuleRegistry registry = concurrentRegistry();
    AtomicInteger closed = new AtomicInteger();
    ModuleId other = new ModuleId("demo.alias");
    ModuleInstance shared =
        new ModuleInstance() {
          @Override
          public List<Contribution> contributions() {
            return List.of();
          }

          @Override
          public void close(CloseReason reason) {
            closed.incrementAndGet();
          }

          @Override
          public boolean equals(Object otherInstance) {
            throw new AssertionError("模块 equals 不得在 registry 锁内运行");
          }

          @Override
          public int hashCode() {
            throw new AssertionError("模块 hashCode 不得在 registry 锁内运行");
          }
        };
    registry.register(cardsDescriptor(), () -> shared);
    registry.register(
        ContractTestSupport.descriptor("demo.alias", Enablement.OPTIONAL_USER), () -> shared);
    assertTrue(registry.install(CARDS_ID).accepted());
    GenerationLease original = registry.lease(CARDS_ID);
    assertEquals(ContractCodes.MODULE_INSTALL_FAILED, registry.install(other).code());
    assertEquals(0, closed.get());
    assertSame(original, registry.lease(CARDS_ID));
    assertTrue(original.active());
    assertFalse(registry.isInstalled(other));
    assertTrue(registry.close(CARDS_ID, null).accepted());
    assertEquals(1, closed.get());
    assertTrue(registry.install(other).accepted());
    assertTrue(registry.lease(other).active());
  }

  private static void assertPendingInstallInvalidated(
      boolean throughEnable, boolean blockContributions, CloseReason reason) throws Exception {
    ModuleRegistry registry = concurrentRegistry();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TrackedInstance instance = new TrackedInstance("app.late");
    ModuleFactory factory =
        () -> {
          if (!blockContributions) {
            entered.countDown();
            await(release);
          }
          return new ModuleInstance() {
            @Override
            public List<Contribution> contributions() {
              if (blockContributions) {
                entered.countDown();
                await(release);
              }
              return instance.contributions();
            }

            @Override
            public void close(CloseReason actualReason) {
              assertFalse("迟到实例清理不得持 registry 锁", Thread.holdsLock(registry));
              instance.close(actualReason);
            }
          };
        };
    assertTrue(registry.register(cardsDescriptor(), factory).accepted());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ContractResult> installing =
          pool.submit(
              () ->
                  throughEnable ? registry.setEnabled(CARDS_ID, true) : registry.install(CARDS_ID));
      assertTrue("必须先进入受控阻塞点", entered.await(5, TimeUnit.SECONDS));
      Future<ContractResult> closing =
          pool.submit(
              () -> {
                if (reason == CloseReason.USER_DISABLED)
                  return registry.setEnabled(CARDS_ID, false);
                if (reason == CloseReason.SAFE_MODE) return registry.enterSafeMode();
                return registry.close(CARDS_ID, reason);
              });
      // release 尚未放行；旧实现会在此确定性超时，finally 仍释放线程。
      assertTrue("停止不能等待 factory/contributions", closing.get(2, TimeUnit.SECONDS).accepted());
      if (reason == CloseReason.SAFE_MODE) registry.exitSafeMode();
      assertFalse(installing.isDone());
      assertFalse(registry.isInstalled(CARDS_ID));
      assertNull(registry.lease(CARDS_ID));
      assertTrue(registry.contributions().isEmpty());
      release.countDown();
      assertEquals(ContractCodes.STALE_GENERATION, installing.get(5, TimeUnit.SECONDS).code());
      assertEquals(1, instance.closed.get());
      assertEquals(reason, instance.reason.get());
      assertFalse(registry.isInstalled(CARDS_ID));
      assertNull(registry.lease(CARDS_ID));
      assertTrue(registry.contributions().isEmpty());
      assertTrue(registry.resolveRoute("demo.cards#page").hostFallback());
      assertTrue(registry.close(CARDS_ID, CloseReason.HOST_CLOSE).accepted());
      assertEquals(1, instance.closed.get());
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue("测试线程必须归还", pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static ModuleRegistry concurrentRegistry() {
    return new ModuleRegistry(
        new LayoutStore() {
          @Override
          public LayoutSnapshot load() {
            return LayoutSnapshot.empty();
          }

          @Override
          public void save(LayoutSnapshot snapshot) {}
        },
        new SafeModeGate(),
        DiagnosticsSink.discarding());
  }

  private static final class TrackedInstance implements ModuleInstance {
    final AtomicInteger closed = new AtomicInteger();
    final AtomicReference<CloseReason> reason = new AtomicReference<>();
    final String route;

    TrackedInstance(String route) {
      this.route = route;
    }

    @Override
    public List<Contribution> contributions() {
      return List.of(
          Contribution.card(CARDS_ID, "beta", "卡片", "Card", 0),
          Contribution.route(CARDS_ID, "page", "页面", "Page", 1, route));
    }

    @Override
    public void close(CloseReason actualReason) {
      closed.incrementAndGet();
      reason.set(actualReason);
    }
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
