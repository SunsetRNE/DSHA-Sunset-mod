package com.deepseekharness.app.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编译期模块清单的最小运行时登记表：注册 → 安装 → 贡献 → 可选启停 → 停用清理。
 *
 * <p>本类不加载外部代码、不做模块市场/签名/网络下载；只接受编译期已存在的 {@link ModuleFactory}。
 * 停用或关闭会提升 generation，旧租约立即失效；布局记录由 {@link ContributionCatalog} 保留。
 *
 * <p>DEFERRED(A1)：{@code contractVersion}/{@code describe()} 生命周期校验、{@code setEnabled} 意图持久化
 * （归 M01）、ModuleHost 资源 scope 不在本轮；本类只做进程内最小登记。
 */
public final class ModuleRegistry {
  private final DiagnosticsSink diagnostics;
  private final SafeModeGate safeMode;
  private final ContributionCatalog catalog;
  private final RouteTable routes;
  private static final AtomicLong GENERATION = new AtomicLong();

  private final Map<ModuleId, Entry> modules = new LinkedHashMap<>();

  public ModuleRegistry(
      LayoutStore layoutStore, SafeModeGate safeMode, DiagnosticsSink diagnostics) {
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    this.safeMode = Objects.requireNonNull(safeMode, "safeMode");
    this.catalog =
        new ContributionCatalog(Objects.requireNonNull(layoutStore, "layoutStore"), diagnostics);
    this.routes = new RouteTable(diagnostics);
  }

  /** 声明并登记模块；重复 id 拒绝且不留部分登记。 */
  public synchronized ContractResult register(ModuleDescriptor descriptor, ModuleFactory factory) {
    if (descriptor == null || factory == null) {
      diagnostics.report(ContractCodes.MODULE_INVALID, "register requires descriptor and factory");
      return ContractResult.rejected(ContractCodes.MODULE_INVALID);
    }
    if (modules.containsKey(descriptor.id())) {
      diagnostics.report(
          ContractCodes.MODULE_DUPLICATE_ID, "module already registered: " + descriptor.id());
      return ContractResult.rejected(ContractCodes.MODULE_DUPLICATE_ID);
    }
    modules.put(descriptor.id(), new Entry(descriptor, factory));
    return ContractResult.ok();
  }

  /** 创建实例并登记贡献；重复调用幂等。安全模式下 OPTIONAL_USER 模块拒绝安装。 */
  public synchronized ContractResult install(ModuleId id) {
    Entry entry = modules.get(id);
    if (entry == null) {
      diagnostics.report(ContractCodes.MODULE_UNKNOWN, "install unknown module: " + id);
      return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN);
    }
    if (entry.instance != null) return ContractResult.ok();
    if (safeMode.forcedSafeMode() && !entry.descriptor.alwaysOn()) {
      diagnostics.report(
          ContractCodes.SAFE_MODE_RESTRICTED, "safe mode rejects optional module: " + id);
      return ContractResult.rejected(ContractCodes.SAFE_MODE_RESTRICTED);
    }

    ModuleInstance instance;
    try {
      instance = entry.factory.create();
    } catch (RuntimeException error) {
      diagnostics.report(
          ContractCodes.MODULE_INSTALL_FAILED,
          "module factory failed: " + id + " (" + error.getClass().getSimpleName() + ")");
      return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED);
    }
    if (instance == null) {
      diagnostics.report(
          ContractCodes.MODULE_INSTALL_FAILED, "module factory returned null: " + id);
      return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED);
    }

    List<Contribution> contributions;
    try {
      contributions = instance.contributions();
    } catch (RuntimeException error) {
      closeQuietly(instance);
      diagnostics.report(
          ContractCodes.MODULE_INSTALL_FAILED,
          "module contributions failed: " + id + " (" + error.getClass().getSimpleName() + ")");
      return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED);
    }
    if (contributions == null) contributions = List.of();

    Set<String> keys = new HashSet<>();
    for (Contribution contribution : contributions) {
      if (contribution == null
          || !id.equals(contribution.owner())
          || !keys.add(contribution.globalKey())
          || catalog.contains(contribution.globalKey())) {
        closeQuietly(instance);
        diagnostics.report(
            ContractCodes.MODULE_INVALID_CONTRIBUTION, "invalid contribution for module: " + id);
        return ContractResult.rejected(ContractCodes.MODULE_INVALID_CONTRIBUTION);
      }
    }

    long nextGeneration = GENERATION.incrementAndGet();
    GenerationLease lease = new GenerationLease(id, nextGeneration, diagnostics);
    List<String> addedKeys = new ArrayList<>(contributions.size());
    for (Contribution contribution : contributions) {
      ContractResult added = catalog.add(contribution, nextGeneration);
      if (added.rejected()) {
        revokeKeys(addedKeys);
        closeQuietly(instance);
        diagnostics.report(added.code(), "catalog add rejected: " + contribution.globalKey());
        return added;
      }
      addedKeys.add(contribution.globalKey());
      if (contribution.kind() == Contribution.Kind.ROUTE) {
        ContractResult routed =
            routes.register(
                contribution.globalKey(), RouteTable.Target.module(contribution.routeTarget()));
        if (routed.rejected()) {
          revokeKeys(addedKeys);
          closeQuietly(instance);
          diagnostics.report(
              routed.code(), "route registration rejected: " + contribution.globalKey());
          return routed;
        }
      }
    }

    entry.instance = instance;
    entry.lease = lease;
    entry.contributions = List.copyOf(contributions);
    return ContractResult.ok();
  }

  /**
   * 可选启停：只有 OPTIONAL_USER 可停用；ALWAYS_ON 拒绝停用。
   *
   * <p>停用顺序：提升 generation → close → 强制撤销贡献 → 保留布局记录 → 诊断。
   */
  public ContractResult setEnabled(ModuleId id, boolean enabled) {
    synchronized (this) {
      Entry entry = modules.get(id);
      if (entry == null) {
        diagnostics.report(ContractCodes.MODULE_UNKNOWN, "setEnabled unknown module: " + id);
        return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN);
      }
      if (!enabled) {
        if (entry.descriptor.alwaysOn()) {
          diagnostics.report(
              ContractCodes.MODULE_ALWAYS_ON, "always-on module cannot be disabled: " + id);
          return ContractResult.rejected(ContractCodes.MODULE_ALWAYS_ON);
        }
      } else {
        if (safeMode.forcedSafeMode() && !entry.descriptor.alwaysOn()) {
          diagnostics.report(
              ContractCodes.SAFE_MODE_RESTRICTED, "safe mode rejects optional module: " + id);
          return ContractResult.rejected(ContractCodes.SAFE_MODE_RESTRICTED);
        }
        return install(id);
      }
    }
    // 停用不能在 registry 锁内走到 close：close 需要 invalidate 租约，而回调可能回头调用
    // registry；锁内 invalidate 会重新形成 registry→lease ABBA。
    return close(id, ModuleInstance.CloseReason.USER_DISABLED);
  }

  /**
   * 强制关闭模块实例并撤销全部贡献；幂等。
   *
   * <p>registry 锁内只取/置空 instance、lease 与贡献清单，出锁后才 invalidate 租约并清理：
   * 租约回调可能回头调用 registry，持锁 invalidate 会形成 registry→lease ABBA。
   */
  public ContractResult close(ModuleId id, ModuleInstance.CloseReason reason) {
    ModuleInstance instance;
    GenerationLease lease;
    List<Contribution> contributions;
    synchronized (this) {
      Entry entry = modules.get(id);
      if (entry == null) {
        diagnostics.report(ContractCodes.MODULE_UNKNOWN, "close unknown module: " + id);
        return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN);
      }
      instance = entry.instance;
      if (instance == null) return ContractResult.ok();
      GENERATION.incrementAndGet();
      lease = entry.lease;
      entry.lease = null;
      entry.instance = null;
      contributions = entry.contributions;
      entry.contributions = List.of();
    }
    if (lease != null) lease.invalidate();

    ModuleInstance.CloseReason actualReason =
        reason == null ? ModuleInstance.CloseReason.HOST_CLOSE : reason;
    // 清理不可逃避：close 抛异常/Error、或某个贡献撤销失败，都必须继续撤销其余贡献。
    // 每个失败各自捕获并保留第一个；诊断是尽力而为，不得中断清理。
    Throwable failure = null;
    try {
      instance.close(actualReason);
    } catch (Throwable error) {
      failure = error;
      reportQuietly(
          ContractCodes.MODULE_CLOSE_FAILED,
          "module close failed: " + id + " (" + error.getClass().getSimpleName() + ")");
    }
    for (Contribution contribution : contributions) {
      try {
        revokeContribution(contribution);
      } catch (Throwable error) {
        if (failure == null) failure = error;
        reportQuietly(
            ContractCodes.MODULE_CLOSE_FAILED,
            "module contribution cleanup failed: "
                + id
                + " ("
                + error.getClass().getSimpleName()
                + ")");
      }
    }
    reportQuietly(
        ContractCodes.MODULE_DISABLED, "module closed: " + id + " (" + actualReason + ")");
    if (failure instanceof Error error) throw error;
    return failure == null
        ? ContractResult.ok()
        : ContractResult.rejected(ContractCodes.MODULE_CLOSE_FAILED);
  }

  /** 进入安全模式：关闭全部 OPTIONAL_USER 模块，仅保留 ALWAYS_ON 贡献与宿主兜底入口。 */
  public ContractResult enterSafeMode() {
    // 先置安全模式标志：install() 在 registry 锁内检查它，标志之后不会再装入 optional 模块。
    safeMode.enterSafeMode();
    List<ModuleId> optionalInstalled = new ArrayList<>();
    synchronized (this) {
      for (Entry entry : modules.values()) {
        if (!entry.descriptor.alwaysOn() && entry.instance != null)
          optionalInstalled.add(entry.descriptor.id());
      }
    }
    for (ModuleId id : optionalInstalled) close(id, ModuleInstance.CloseReason.SAFE_MODE);
    diagnostics.report(ContractCodes.SAFE_MODE_ENTERED, "safe mode entered");
    return ContractResult.ok();
  }

  public synchronized void exitSafeMode() {
    safeMode.exitSafeMode();
  }

  /** 宿主撤销单个贡献；未知键拒绝并诊断。 */
  public synchronized ContractResult revoke(String contributionKey) {
    ContractResult revoked = catalog.revoke(contributionKey);
    if (revoked.accepted() && routes.contains(contributionKey)) routes.revoke(contributionKey);
    return revoked;
  }

  /** 稳定排序的全部贡献（含隐藏项）；安全模式下只保留 ALWAYS_ON 模块的贡献。 */
  public synchronized List<ContributionView> contributions() {
    List<ContributionView> all = catalog.contributions();
    if (!safeMode.forcedSafeMode()) return all;
    List<ContributionView> filtered = new ArrayList<>();
    for (ContributionView view : all) {
      Entry entry = modules.get(view.owner());
      if (entry != null && entry.descriptor.alwaysOn()) filtered.add(view);
    }
    return List.copyOf(filtered);
  }

  public synchronized List<ContributionView> visibleContributions() {
    List<ContributionView> visible = new ArrayList<>();
    for (ContributionView view : contributions()) if (!view.hidden()) visible.add(view);
    return List.copyOf(visible);
  }

  public synchronized LayoutSnapshot layout() {
    return catalog.layout();
  }

  public synchronized ContractResult setContributionHidden(String contributionKey, boolean hidden) {
    return catalog.setHidden(contributionKey, hidden);
  }

  public synchronized ContractResult setContributionOrder(List<String> contributionKeys) {
    return catalog.setOrder(contributionKeys);
  }

  public synchronized RouteTable.Target resolveRoute(String routeKey) {
    if (safeMode.forcedSafeMode()) {
      ModuleId owner = routeOwner(routeKey);
      Entry entry = owner == null ? null : modules.get(owner);
      if (entry == null || !entry.descriptor.alwaysOn()) {
        diagnostics.report(
            ContractCodes.SAFE_MODE_RESTRICTED,
            "safe mode route fallback: " + (routeKey == null ? "" : routeKey));
        return RouteTable.Target.host();
      }
    }
    return routes.resolve(routeKey);
  }

  private static ModuleId routeOwner(String routeKey) {
    if (routeKey == null) return null;
    int separator = routeKey.indexOf('#');
    if (separator <= 0) return null;
    try {
      return new ModuleId(routeKey.substring(0, separator));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  public synchronized GenerationLease lease(ModuleId id) {
    Entry entry = modules.get(id);
    return entry == null ? null : entry.lease;
  }

  public synchronized long generation(ModuleId id) {
    GenerationLease lease = lease(id);
    return lease == null ? 0 : lease.generation();
  }

  /** 宿主回调入口；旧 generation 一律丢弃并诊断，不补发、不重放。 */
  public ContractResult dispatch(ModuleId id, long callbackGeneration, Runnable callback) {
    GenerationLease lease;
    synchronized (this) {
      Entry entry = modules.get(id);
      if (entry == null) {
        diagnostics.report(ContractCodes.MODULE_UNKNOWN, "dispatch unknown module: " + id);
        return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN);
      }
      if (callback == null) {
        diagnostics.report(ContractCodes.INVALID_CALLBACK, "dispatch requires callback: " + id);
        return ContractResult.rejected(ContractCodes.INVALID_CALLBACK);
      }
      lease = entry.lease;
      if (lease == null || !lease.active() || lease.generation() != callbackGeneration) {
        diagnostics.report(
            ContractCodes.STALE_GENERATION,
            "stale callback generation for " + id + ": " + callbackGeneration);
        return ContractResult.rejected(ContractCodes.STALE_GENERATION);
      }
    }
    // 必须在 registry 锁外进入租约：runIfActive 会拿 lease 锁并执行模块回调，而回调可能
    // 回头调用 registry。若持 registry 锁等待 lease，就与「lease→registry」的路径形成环。
    // 释放锁后 close 可能已使租约失效，runIfActive 的 active 复检会把这次回调按旧代次拒绝。
    if (!lease.runIfActive(callback)) {
      if (!lease.active()) {
        diagnostics.report(
            ContractCodes.STALE_GENERATION,
            "stale callback generation for " + id + ": " + callbackGeneration);
        return ContractResult.rejected(ContractCodes.STALE_GENERATION);
      }
      // runIfActive 已把回调 RuntimeException 写入诊断。
      return ContractResult.rejected(ContractCodes.MODULE_CALLBACK_FAILED);
    }
    return ContractResult.ok();
  }

  public synchronized boolean isRegistered(ModuleId id) {
    return id != null && modules.containsKey(id);
  }

  public synchronized boolean isInstalled(ModuleId id) {
    Entry entry = modules.get(id);
    return entry != null && entry.instance != null;
  }

  public synchronized ModuleDescriptor descriptor(ModuleId id) {
    Entry entry = modules.get(id);
    return entry == null ? null : entry.descriptor;
  }

  public SafeModeGate safeModeGate() {
    return safeMode;
  }

  private void revokeContributions(List<Contribution> contributions) {
    for (Contribution contribution : contributions) revokeContribution(contribution);
  }

  private void revokeContribution(Contribution contribution) {
    String key = contribution.globalKey();
    if (catalog.contains(key)) catalog.revoke(key);
    if (routes.contains(key)) routes.revoke(key);
  }

  private void revokeKeys(List<String> keys) {
    for (String key : keys) {
      if (catalog.contains(key)) catalog.revoke(key);
      if (routes.contains(key)) routes.revoke(key);
    }
  }

  /**
   * 诊断是脱敏出口，不是生命周期步骤：下游故障（含 Error）不得中断关闭清理。
   * 这里吞掉所有异常；调用方仍保留并上报真正的 close/cleanup 失败。
   */
  private void reportQuietly(String code, String detail) {
    try {
      diagnostics.report(code, detail);
    } catch (Throwable ignored) {
      // DiagnosticsSink 已自行吞掉下游 RuntimeException；这里只兜住 Error。
    }
  }

  private static void closeQuietly(ModuleInstance instance) {
    try {
      instance.close(ModuleInstance.CloseReason.HOST_CLOSE);
    } catch (RuntimeException ignored) {
      // 安装失败的兜底清理；异常不能冒泡，也不重放。
    }
  }

  private static final class Entry {
    final ModuleDescriptor descriptor;
    final ModuleFactory factory;
    ModuleInstance instance;
    GenerationLease lease;
    List<Contribution> contributions = List.of();

    Entry(ModuleDescriptor descriptor, ModuleFactory factory) {
      this.descriptor = descriptor;
      this.factory = factory;
    }
  }
}
