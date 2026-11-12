package com.deepseekharness.app.contract;

/** 贡献的只读呈现快照；{@code hidden} 已叠加宿主布局记录。 */
public record ContributionView(
    String key,
    Contribution.Kind kind,
    ModuleId owner,
    String title,
    String titleEn,
    int sortOrder,
    boolean hidden,
    long generation,
    String routeTarget) {

  public ContributionView {
    if (!LayoutSnapshot.validKey(key))
      throw new IllegalArgumentException("CONTRIBUTION_KEY_INVALID");
    if (kind == null || owner == null)
      throw new IllegalArgumentException("CONTRIBUTION_VIEW_INVALID");
    if (generation < 1) throw new IllegalArgumentException("CONTRIBUTION_GENERATION_INVALID");
  }

  static ContributionView of(Contribution contribution, boolean hidden, long generation) {
    return new ContributionView(
        contribution.globalKey(),
        contribution.kind(),
        contribution.owner(),
        contribution.title(),
        contribution.titleEn(),
        contribution.sortOrder(),
        hidden,
        generation,
        contribution.routeTarget());
  }
}
