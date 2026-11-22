package com.deepseekharness.app.contract;

import static org.junit.Assert.*;

import android.content.Context;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Java 生产调用形态、默认接口、空值失败路径与生命周期锁边界。 */
public class ContractLifecycleInteropTest {
  private static final ModuleId ID = new ModuleId("demo.interop");

  private static ModuleDescriptor descriptor() {
    return new ModuleDescriptor(ID, 1, "互调", Set.of(), ModuleDescriptor.Enablement.OPTIONAL_USER);
  }

  private static ModuleRegistry registry(DiagnosticsSink sink) {
    return new ModuleRegistry(
        new SharedPreferencesLayoutStore(new ContractTestSupport.FakeSharedPreferences(), sink),
        new SafeModeGate(),
        sink);
  }

  @Test
  public void javaSamAndDefaultCloseNeedOnlyTheOriginalAbstractMethods() throws Exception {
    ModuleInstance instance = () -> List.of(Contribution.card(ID, "card", "卡片", "Card", 0));
    ModuleFactory factory = () -> instance;
    assertSame(instance, factory.create());
    instance.close(null);
    instance.close(ModuleInstance.CloseReason.USER_DISABLED);
    assertTrue(
        ModuleInstance.class.getMethod("close", ModuleInstance.CloseReason.class).isDefault());
    assertEquals(
        1,
        Arrays.stream(ModuleInstance.class.getMethods())
            .filter(m -> Modifier.isAbstract(m.getModifiers()))
            .count());
    assertEquals(
        1,
        Arrays.stream(ModuleFactory.class.getMethods())
            .filter(m -> Modifier.isAbstract(m.getModifiers()))
            .count());
    ModuleRegistry registry = registry(DiagnosticsSink.discarding());
    assertTrue(registry.register(descriptor(), factory).accepted());
    assertTrue(registry.install(ID).accepted());
    assertTrue(registry.close(ID, null).accepted());
  }

  @Test
  public void explicitNullChecksRetainOrderMessagesAndExceptionTypes() {
    assertEquals(
        "diagnostics",
        assertThrows(NullPointerException.class, () -> new ModuleRegistry(null, null, null))
            .getMessage());
    DiagnosticsSink sink = DiagnosticsSink.discarding();
    assertEquals(
        "safeMode",
        assertThrows(NullPointerException.class, () -> new ModuleRegistry(null, null, sink))
            .getMessage());
    assertEquals(
        "layoutStore",
        assertThrows(
                NullPointerException.class,
                () -> new ModuleRegistry(null, new SafeModeGate(), sink))
            .getMessage());
    assertEquals(
        "layoutStore",
        assertThrows(NullPointerException.class, () -> new ContributionCatalog(null, null))
            .getMessage());
    assertEquals(
        "diagnostics",
        assertThrows(NullPointerException.class, () -> new RouteTable(null)).getMessage());
    assertEquals(
        "preferences",
        assertThrows(NullPointerException.class, () -> new SharedPreferencesLayoutStore(null, null))
            .getMessage());
    assertEquals(
        "downstream",
        assertThrows(NullPointerException.class, () -> DiagnosticsSink.forwarding(null))
            .getMessage());
    assertEquals(
        "CONTEXT_REQUIRED",
        assertThrows(IllegalArgumentException.class, () -> SharedPreferencesLayoutStore.open(null))
            .getMessage());
    assertEquals(
        "CONTEXT_REQUIRED",
        assertThrows(
                IllegalArgumentException.class,
                () -> BuiltinModules.ensureRegistered((Context) null))
            .getMessage());
    assertEquals(
        "REGISTRY_REQUIRED",
        assertThrows(
                IllegalArgumentException.class,
                () -> BuiltinModules.ensureRegistered((ModuleRegistry) null))
            .getMessage());
  }

  @Test
  public void nullableRegistryInputsReachStableRejections() {
    ModuleRegistry registry = registry(DiagnosticsSink.discarding());
    assertEquals(ContractCodes.MODULE_INVALID, registry.register(null, null).code());
    assertEquals(ContractCodes.MODULE_UNKNOWN, registry.install(null).code());
    assertEquals(ContractCodes.MODULE_UNKNOWN, registry.close(null, null).code());
    assertEquals(ContractCodes.MODULE_UNKNOWN, registry.setEnabled(null, true).code());
    assertEquals(ContractCodes.MODULE_UNKNOWN, registry.dispatch(null, 0, null).code());
    assertFalse(registry.isRegistered(null));
    assertFalse(registry.isInstalled(null));
    assertNull(registry.descriptor(null));
    assertNull(registry.lease(null));
    assertEquals(0, registry.generation(null));
    assertTrue(registry.resolveRoute(null).hostFallback());
    assertEquals(ContractCodes.INVALID_LAYOUT, registry.setContributionOrder(null).code());
    assertEquals(
        ContractCodes.UNKNOWN_CONTRIBUTION, registry.setContributionHidden(null, true).code());
  }

  @Test
  public void nullFactoryResultAndNullContributionElementKeepDifferentFailureCodes() {
    ModuleRegistry empty = registry(DiagnosticsSink.discarding());
    empty.register(descriptor(), () -> null);
    assertEquals(ContractCodes.MODULE_INSTALL_FAILED, empty.install(ID).code());
    AtomicBoolean closed = new AtomicBoolean();
    ModuleRegistry invalid = registry(DiagnosticsSink.discarding());
    invalid.register(
        descriptor(),
        () ->
            new ModuleInstance() {
              @Override
              public List<Contribution> contributions() {
                return Arrays.asList((Contribution) null);
              }

              @Override
              public void close(CloseReason reason) {
                closed.set(true);
              }
            });
    assertEquals(ContractCodes.MODULE_INVALID_CONTRIBUTION, invalid.install(ID).code());
    assertTrue(closed.get());
    assertFalse(invalid.isInstalled(ID));
    assertTrue(invalid.contributions().isEmpty());
  }

  @Test
  public void secondPassNullStillCleansEarlierContributionAndRouteBeforeThrowing() {
    ModuleId owner = new ModuleId("review.cleanup");
    Contribution first = Contribution.route(owner, "first", "页面", "Page", 0, "app.first");
    Contribution second = Contribution.card(owner, "second", "卡片", "Card", 1);
    // 第二轮登记前才替换空项，既不依赖竞态，也不要求模块提供稳定的 List。
    CopyOnWriteArrayList<Contribution> contributions =
        new CopyOnWriteArrayList<>(List.of(first, second)) {
          private int iterations;

          @Override
          public Iterator<Contribution> iterator() {
            if (++iterations == 2) set(1, null);
            return super.iterator();
          }
        };
    AtomicInteger closed = new AtomicInteger();
    ModuleRegistry registry = registry(DiagnosticsSink.discarding());
    ModuleDescriptor descriptor =
        new ModuleDescriptor(owner, 1, "清理互调", Set.of(), ModuleDescriptor.Enablement.OPTIONAL_USER);
    assertTrue(
        registry
            .register(
                descriptor,
                () ->
                    new ModuleInstance() {
                      @Override
                      public List<Contribution> contributions() {
                        return contributions;
                      }

                      @Override
                      public void close(CloseReason reason) {
                        assertSame(CloseReason.HOST_CLOSE, reason);
                        closed.incrementAndGet();
                      }
                    })
            .accepted());

    // 原 Java 在拒绝清理后才因诊断内容解引用 null 抛出 NPE；本批保持该异常行为。
    assertThrows(NullPointerException.class, () -> registry.install(owner));
    assertEquals(
        "closed / catalog size / route fallback / installed",
        List.of(1, 0, true, false),
        List.of(
            closed.get(),
            registry.contributions().size(),
            registry.resolveRoute("review.cleanup#first").hostFallback(),
            registry.isInstalled(owner)));
    assertNull(registry.lease(owner));
    assertTrue(registry.close(owner, ModuleInstance.CloseReason.HOST_CLOSE).accepted());
    assertEquals(1, closed.get());
    assertTrue(registry.contributions().isEmpty());
    assertTrue(registry.resolveRoute("review.cleanup#first").hostFallback());
  }

  @Test
  public void nullContributionListIsAnEmptyInstalledModule() {
    ModuleRegistry registry = registry(DiagnosticsSink.discarding());
    registry.register(descriptor(), () -> () -> null);
    assertTrue(registry.install(ID).accepted());
    assertTrue(registry.isInstalled(ID));
    assertTrue(registry.contributions().isEmpty());
    assertEquals(
        ContractCodes.INVALID_CALLBACK,
        registry.dispatch(ID, registry.generation(ID), null).code());
    assertTrue(registry.close(ID, null).accepted());
  }

  @Test
  public void factoryRunsOutsideRegistryMonitorAndDispatchRunsOnlyUnderLeaseMonitor() {
    ModuleRegistry registry = registry(DiagnosticsSink.discarding());
    registry.register(
        descriptor(),
        () -> {
          assertFalse(Thread.holdsLock(registry));
          return () -> {
            assertFalse(Thread.holdsLock(registry));
            return List.of(Contribution.route(ID, "page", "页面", "Page", 0, "app.page"));
          };
        });
    assertTrue(registry.setEnabled(ID, true).accepted());
    GenerationLease lease = registry.lease(ID);
    assertTrue(
        registry
            .dispatch(
                ID,
                lease.generation(),
                () -> {
                  assertTrue(Thread.holdsLock(lease));
                  assertFalse(Thread.holdsLock(registry));
                })
            .accepted());
    AtomicBoolean ran = new AtomicBoolean();
    assertTrue(registry.close(ID, null).accepted());
    assertFalse(lease.runIfActive(() -> ran.set(true)));
    assertEquals(
        ContractCodes.STALE_GENERATION,
        registry.dispatch(ID, lease.generation(), () -> ran.set(true)).code());
    assertFalse(ran.get());
    assertTrue(registry.resolveRoute("demo.interop#page").hostFallback());
  }

  @Test
  public void closeObservesInvalidatedLeaseBeforeCleanupAndUsesNullReasonFallback() {
    ModuleRegistry registry = registry(DiagnosticsSink.discarding());
    AtomicReference<GenerationLease> lease = new AtomicReference<>();
    AtomicReference<ModuleInstance.CloseReason> actual = new AtomicReference<>();
    registry.register(
        descriptor(),
        () ->
            new ModuleInstance() {
              @Override
              public List<Contribution> contributions() {
                return List.of(Contribution.card(ID, "card", "卡片", "Card", 0));
              }

              @Override
              public void close(CloseReason reason) {
                assertFalse(Thread.holdsLock(registry));
                assertFalse(lease.get().active());
                assertFalse(registry.isInstalled(ID));
                assertNull(registry.lease(ID));
                assertEquals(1, registry.contributions().size());
                actual.set(reason);
              }
            });
    registry.install(ID);
    lease.set(registry.lease(ID));
    assertTrue(registry.close(ID, null).accepted());
    assertSame(ModuleInstance.CloseReason.HOST_CLOSE, actual.get());
    assertTrue(registry.contributions().isEmpty());
  }

  @Test
  public void leaseRetainsPackageConstructorInvalidationAndThrowableBoundaries() {
    DiagnosticsSink sink = DiagnosticsSink.discarding();
    assertEquals(
        "LEASE_INVALID",
        assertThrows(IllegalArgumentException.class, () -> new GenerationLease(null, 0, null))
            .getMessage());
    assertEquals(
        "diagnostics",
        assertThrows(NullPointerException.class, () -> new GenerationLease(ID, 1, null))
            .getMessage());
    GenerationLease lease = new GenerationLease(ID, 1, sink);
    assertFalse(lease.runIfActive(null));
    assertFalse(
        lease.runIfActive(
            () -> {
              throw new IllegalStateException();
            }));
    AssertionError failure = new AssertionError("callback");
    assertSame(
        failure,
        assertThrows(
            AssertionError.class,
            () ->
                lease.runIfActive(
                    () -> {
                      throw failure;
                    })));
    assertTrue(lease.active());
    assertTrue(lease.invalidate());
    assertFalse(lease.invalidate());
    assertFalse(lease.active());
  }

  @Test
  public void routeTargetAndDiagnosticEventKeepRecordValuesIncludingNullableEventFields() {
    for (boolean fallback : List.of(false, true)) {
      RouteTable.Target target = new RouteTable.Target("app.page", fallback);
      TargetRecord reference = new TargetRecord("app.page", fallback);
      assertEquals(reference.hashCode(), target.hashCode());
      assertEquals("Target[key=app.page, hostFallback=" + fallback + "]", target.toString());
      assertEquals(target, new RouteTable.Target("app.page", fallback));
      assertNotEquals(target, new RouteTable.Target("other", fallback));
      assertNotEquals(target, new RouteTable.Target("app.page", !fallback));
    }
    for (String code : Arrays.asList(null, "code")) {
      for (String detail : Arrays.asList(null, "detail")) {
        DiagnosticsSink.Event event = new DiagnosticsSink.Event(code, detail);
        assertEquals(new EventRecord(code, detail).hashCode(), event.hashCode());
        assertEquals("Event[code=" + code + ", detail=" + detail + "]", event.toString());
        assertEquals(event, new DiagnosticsSink.Event(code, detail));
      }
    }
    assertEquals(
        "ROUTE_TARGET_INVALID",
        assertThrows(IllegalArgumentException.class, () -> RouteTable.Target.module(null))
            .getMessage());
    assertEquals(
        "ROUTE_TARGET_INVALID",
        assertThrows(IllegalArgumentException.class, () -> new RouteTable.Target("", false))
            .getMessage());
  }

  @Test
  public void nullLayoutAndDiagnosticFailuresKeepTheirOriginalFallbacks() {
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ContributionCatalog catalog =
        new ContributionCatalog(
            new LayoutStore() {
              @Override
              public LayoutSnapshot load() {
                return null;
              }

              @Override
              public void save(LayoutSnapshot snapshot) {}
            },
            DiagnosticsSink.forwarding(events::add));
    assertEquals(LayoutSnapshot.empty(), catalog.layout());
    assertEquals(1, events.size());
    assertEquals(ContractCodes.LAYOUT_LOAD_FAILED, events.get(0).code());
    assertTrue(catalog.byKind(null).isEmpty());
    assertNull(catalog.find(null));
    assertEquals(ContractCodes.MODULE_INVALID_CONTRIBUTION, catalog.add(null, 0).code());
    assertEquals(ContractCodes.INVALID_LAYOUT, catalog.setOrder(null).code());
    DiagnosticsSink.forwarding(
            event -> {
              throw new IllegalStateException();
            })
        .report(null, null);
    AssertionError failure = new AssertionError("diagnostic");
    assertSame(
        failure,
        assertThrows(
            AssertionError.class,
            () ->
                DiagnosticsSink.forwarding(
                        event -> {
                          throw failure;
                        })
                    .report("OK", null)));
  }

  private record TargetRecord(String key, boolean hostFallback) {}

  private record EventRecord(String code, String detail) {}
}
