package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** C02 路由：命中返回模块目标，任何未命中都只回宿主 fallback。 */
public class RouteTableTest {
  private static final ModuleId ROUTE_ID = new ModuleId("demo.route");

  @Test
  public void missingRouteFallsBackToHostAndDiagnoses() {
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    RouteTable routes = new RouteTable(ContractTestSupport.sink(events));

    RouteTable.Target target = routes.resolve("demo.a#missing");

    assertTrue(target.hostFallback());
    assertEquals(RouteTable.HOST_FALLBACK_KEY, target.key());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.ROUTE_MISSING));
  }

  @Test
  public void registeredRouteResolvesThenRevokeFallsBack() {
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    RouteTable routes = new RouteTable(ContractTestSupport.sink(events));

    assertTrue(routes.register("demo.a#page", RouteTable.Target.module("app.page")).accepted());
    RouteTable.Target target = routes.resolve("demo.a#page");
    assertFalse(target.hostFallback());
    assertEquals("app.page", target.key());

    ContractResult duplicate =
        routes.register("demo.a#page", RouteTable.Target.module("app.other"));
    assertTrue(duplicate.rejected());
    assertEquals(ContractCodes.DUPLICATE_ROUTE, duplicate.code());

    assertTrue(routes.revoke("demo.a#page").accepted());
    assertTrue(routes.resolve("demo.a#page").hostFallback());
    assertEquals(
        ContractCodes.INVALID_ROUTE,
        routes.register("bad key", RouteTable.Target.module("app.page")).code());
  }

  @Test
  public void registryRemovesRouteWithDisabledContribution() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);
    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            ROUTE_ID, 1, "路由模块", Set.of(), ModuleDescriptor.Enablement.OPTIONAL_USER);
    ModuleFactory factory =
        () ->
            new ModuleInstance() {
              private final List<Contribution> contributions =
                  List.of(Contribution.route(ROUTE_ID, "page", "页面", "Page", 1, "app.page"));

              @Override
              public List<Contribution> contributions() {
                return contributions;
              }
            };

    assertTrue(registry.register(descriptor, factory).accepted());
    assertTrue(registry.install(ROUTE_ID).accepted());
    RouteTable.Target registered = registry.resolveRoute("demo.route#page");
    assertFalse(registered.hostFallback());
    assertEquals("app.page", registered.key());

    assertTrue(registry.setEnabled(ROUTE_ID, false).accepted());
    RouteTable.Target fallback = registry.resolveRoute("demo.route#page");
    assertTrue(fallback.hostFallback());
    assertTrue(ContractTestSupport.hasEvent(events, ContractCodes.ROUTE_MISSING));
  }
}
