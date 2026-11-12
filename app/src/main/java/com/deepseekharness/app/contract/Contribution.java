package com.deepseekharness.app.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模块贡献声明。CARD 是界面卡片，ROUTE 是页面路由；全局键固定为 {@code owner#id}，用于跨重启的布局记录。
 *
 * <p>{@code title} / {@code titleEn} 是宿主呈现用的中英文字面值；不做自动翻译，也不含协议/用户原文。
 */
public record Contribution(
    Kind kind,
    ModuleId owner,
    String id,
    String title,
    String titleEn,
    int sortOrder,
    boolean hidden,
    String routeTarget) {
  public enum Kind {
    CARD,
    ROUTE
  }

  private static final Pattern LOCAL_ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
  private static final Pattern ROUTE_TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

  public Contribution {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(owner, "owner");
    if (id == null || !LOCAL_ID.matcher(id).matches())
      throw new IllegalArgumentException("CONTRIBUTION_ID_INVALID");
    if (title == null || title.isBlank() || title.length() > 200)
      throw new IllegalArgumentException("CONTRIBUTION_TITLE_INVALID");
    if (titleEn == null || titleEn.isBlank() || titleEn.length() > 200)
      throw new IllegalArgumentException("CONTRIBUTION_TITLE_EN_INVALID");
    if (kind == Kind.ROUTE) {
      if (routeTarget == null || !ROUTE_TARGET.matcher(routeTarget).matches())
        throw new IllegalArgumentException("CONTRIBUTION_ROUTE_TARGET_INVALID");
    } else if (routeTarget != null) {
      throw new IllegalArgumentException("CONTRIBUTION_ROUTE_TARGET_UNEXPECTED");
    }
  }

  public static Contribution card(
      ModuleId owner, String id, String title, String titleEn, int sortOrder) {
    return new Contribution(Kind.CARD, owner, id, title, titleEn, sortOrder, false, null);
  }

  public static Contribution card(
      ModuleId owner, String id, String title, String titleEn, int sortOrder, boolean hidden) {
    return new Contribution(Kind.CARD, owner, id, title, titleEn, sortOrder, hidden, null);
  }

  public static Contribution route(
      ModuleId owner, String id, String title, String titleEn, int sortOrder, String routeTarget) {
    return new Contribution(Kind.ROUTE, owner, id, title, titleEn, sortOrder, false, routeTarget);
  }

  public static Contribution route(
      ModuleId owner,
      String id,
      String title,
      String titleEn,
      int sortOrder,
      String routeTarget,
      boolean hidden) {
    return new Contribution(Kind.ROUTE, owner, id, title, titleEn, sortOrder, hidden, routeTarget);
  }

  public String globalKey() {
    return owner.value() + "#" + id;
  }
}
