package com.deepseekharness.app.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 贡献目录：登记/撤销贡献，叠加宿主布局记录，并始终按稳定顺序输出。
 *
 * <p>顺序先看布局记录；未记录的键按 {@code sortOrder}、全局键字典序兜底，绝不依赖注册顺序。
 * 未知但仍合法的布局键原样保留，未知 contribution 查询/撤销则 fail-closed 并诊断。
 */
public final class ContributionCatalog {
  private final LayoutStore layoutStore;
  private final DiagnosticsSink diagnostics;
  private final Map<String, Entry> entries = new LinkedHashMap<>();
  private LayoutSnapshot layout;

  public ContributionCatalog(LayoutStore layoutStore, DiagnosticsSink diagnostics) {
    this.layoutStore = Objects.requireNonNull(layoutStore, "layoutStore");
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    LayoutSnapshot loaded;
    try {
      loaded = layoutStore.load();
    } catch (RuntimeException error) {
      diagnostics.report(
          ContractCodes.LAYOUT_LOAD_FAILED,
          "layout load failed: " + error.getClass().getSimpleName());
      loaded = null;
    }
    if (loaded == null) {
      diagnostics.report(ContractCodes.LAYOUT_LOAD_FAILED, "layout store returned no snapshot");
      layout = LayoutSnapshot.empty();
    } else {
      layout = loaded;
    }
  }

  public synchronized ContractResult add(Contribution contribution, long generation) {
    if (contribution == null || generation < 1) {
      diagnostics.report(ContractCodes.MODULE_INVALID_CONTRIBUTION, "catalog add rejected");
      return ContractResult.rejected(ContractCodes.MODULE_INVALID_CONTRIBUTION);
    }
    String key = contribution.globalKey();
    if (entries.containsKey(key)) {
      diagnostics.report(ContractCodes.DUPLICATE_CONTRIBUTION, "duplicate contribution: " + key);
      return ContractResult.rejected(ContractCodes.DUPLICATE_CONTRIBUTION);
    }
    entries.put(key, new Entry(contribution, generation));
    return ContractResult.ok();
  }

  public synchronized ContractResult revoke(String contributionKey) {
    if (contributionKey == null || !entries.containsKey(contributionKey)) {
      diagnostics.report(
          ContractCodes.UNKNOWN_CONTRIBUTION,
          "unknown contribution revoke: " + (contributionKey == null ? "" : contributionKey));
      return ContractResult.rejected(ContractCodes.UNKNOWN_CONTRIBUTION);
    }
    entries.remove(contributionKey);
    return ContractResult.ok();
  }

  public synchronized boolean contains(String contributionKey) {
    return contributionKey != null && entries.containsKey(contributionKey);
  }

  public synchronized int size() {
    return entries.size();
  }

  /** 全部登记贡献（含隐藏项），稳定排序。 */
  public synchronized List<ContributionView> contributions() {
    List<Entry> sorted = sortedEntries();
    List<ContributionView> views = new ArrayList<>(sorted.size());
    for (Entry entry : sorted) views.add(view(entry));
    return List.copyOf(views);
  }

  /** 可见贡献（过滤布局/声明隐藏项），稳定排序。 */
  public synchronized List<ContributionView> visibleContributions() {
    List<ContributionView> visible = new ArrayList<>();
    for (ContributionView view : contributions()) if (!view.hidden()) visible.add(view);
    return List.copyOf(visible);
  }

  public synchronized List<ContributionView> byKind(Contribution.Kind kind) {
    if (kind == null) return List.of();
    List<ContributionView> matching = new ArrayList<>();
    for (ContributionView view : contributions()) if (view.kind() == kind) matching.add(view);
    return List.copyOf(matching);
  }

  /** 未知 contribution 返回 null 并诊断，不猜测近义键。 */
  public synchronized ContributionView find(String contributionKey) {
    Entry entry = entries.get(contributionKey);
    if (entry == null) {
      diagnostics.report(
          ContractCodes.UNKNOWN_CONTRIBUTION,
          "unknown contribution lookup: " + (contributionKey == null ? "" : contributionKey));
      return null;
    }
    return view(entry);
  }

  public synchronized LayoutSnapshot layout() {
    return layout;
  }

  public synchronized ContractResult setHidden(String contributionKey, boolean hidden) {
    if (contributionKey == null || !entries.containsKey(contributionKey)) {
      diagnostics.report(
          ContractCodes.UNKNOWN_CONTRIBUTION,
          "unknown contribution hide: " + (contributionKey == null ? "" : contributionKey));
      return ContractResult.rejected(ContractCodes.UNKNOWN_CONTRIBUTION);
    }
    return persist(layout.withHidden(contributionKey, hidden), "setHidden");
  }

  public synchronized ContractResult setOrder(List<String> contributionKeys) {
    if (contributionKeys == null) {
      diagnostics.report(ContractCodes.INVALID_LAYOUT, "setOrder requires keys");
      return ContractResult.rejected(ContractCodes.INVALID_LAYOUT);
    }
    LayoutSnapshot next;
    try {
      next = layout.mergedOrder(contributionKeys);
    } catch (IllegalArgumentException error) {
      diagnostics.report(ContractCodes.INVALID_LAYOUT, "setOrder rejected: " + error.getMessage());
      return ContractResult.rejected(ContractCodes.INVALID_LAYOUT);
    }
    return persist(next, "setOrder");
  }

  private ContractResult persist(LayoutSnapshot next, String operation) {
    try {
      layoutStore.save(next);
    } catch (RuntimeException error) {
      diagnostics.report(
          ContractCodes.LAYOUT_SAVE_FAILED,
          "layout save failed during " + operation + ": " + error.getClass().getSimpleName());
      return ContractResult.rejected(ContractCodes.LAYOUT_SAVE_FAILED);
    }
    layout = next;
    return ContractResult.ok();
  }

  private List<Entry> sortedEntries() {
    List<Entry> sorted = new ArrayList<>(entries.values());
    Map<String, Integer> positions = new HashMap<>();
    for (int position = 0; position < layout.order().size(); position++)
      positions.put(layout.order().get(position), position);
    sorted.sort(
        (left, right) -> {
          String leftKey = left.contribution.globalKey();
          String rightKey = right.contribution.globalKey();
          Integer leftPosition = positions.get(leftKey);
          Integer rightPosition = positions.get(rightKey);
          if (leftPosition != null && rightPosition != null)
            return Integer.compare(leftPosition, rightPosition);
          if (leftPosition != null) return -1;
          if (rightPosition != null) return 1;
          int bySortOrder =
              Integer.compare(left.contribution.sortOrder(), right.contribution.sortOrder());
          return bySortOrder != 0 ? bySortOrder : leftKey.compareTo(rightKey);
        });
    return sorted;
  }

  private ContributionView view(Entry entry) {
    String key = entry.contribution.globalKey();
    boolean hidden = entry.contribution.hidden() || layout.isHidden(key);
    return ContributionView.of(entry.contribution, hidden, entry.generation);
  }

  private static final class Entry {
    final Contribution contribution;
    final long generation;

    Entry(Contribution contribution, long generation) {
      this.contribution = contribution;
      this.generation = generation;
    }
  }
}
