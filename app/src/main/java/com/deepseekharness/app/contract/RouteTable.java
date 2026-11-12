package com.deepseekharness.app.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 模块路由表；任何未命中都只返回宿主 fallback，不崩溃、不回退到其他模块。 */
public final class RouteTable {
  public record Target(String key, boolean hostFallback) {
    private static final Pattern TARGET_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    public Target {
      if (key == null || !TARGET_KEY.matcher(key).matches())
        throw new IllegalArgumentException("ROUTE_TARGET_INVALID");
    }

    public static Target host() {
      return new Target(HOST_FALLBACK_KEY, true);
    }

    public static Target module(String key) {
      return new Target(key, false);
    }
  }

  public static final String HOST_FALLBACK_KEY = "host.fallback";

  private final DiagnosticsSink diagnostics;
  private final Map<String, Target> routes = new LinkedHashMap<>();

  public RouteTable(DiagnosticsSink diagnostics) {
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public synchronized ContractResult register(String routeKey, Target target) {
    if (!LayoutSnapshot.validKey(routeKey) || target == null) {
      diagnostics.report(ContractCodes.INVALID_ROUTE, "invalid route registration");
      return ContractResult.rejected(ContractCodes.INVALID_ROUTE);
    }
    if (routes.containsKey(routeKey)) {
      diagnostics.report(ContractCodes.DUPLICATE_ROUTE, "duplicate route: " + routeKey);
      return ContractResult.rejected(ContractCodes.DUPLICATE_ROUTE);
    }
    routes.put(routeKey, target);
    return ContractResult.ok();
  }

  public synchronized ContractResult revoke(String routeKey) {
    if (routeKey == null || !routes.containsKey(routeKey)) {
      diagnostics.report(ContractCodes.UNKNOWN_CONTRIBUTION, "unknown route revoke");
      return ContractResult.rejected(ContractCodes.UNKNOWN_CONTRIBUTION);
    }
    routes.remove(routeKey);
    return ContractResult.ok();
  }

  public synchronized boolean contains(String routeKey) {
    return routeKey != null && routes.containsKey(routeKey);
  }

  public synchronized Target resolve(String routeKey) {
    Target target = routeKey == null ? null : routes.get(routeKey);
    if (target == null) {
      diagnostics.report(
          ContractCodes.ROUTE_MISSING,
          "route not registered: " + (routeKey == null ? "" : routeKey));
      return Target.host();
    }
    return target;
  }

  public synchronized int size() {
    return routes.size();
  }
}
