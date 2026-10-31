package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 首次引导第 2 步“用途”到第 3 步授权卡片的推荐映射。纯逻辑：只决定推荐标记与分组顺序，
 * 不申请、不打开任何权限。卡片 id 与分组 id 为界面常量，不写入任何权限状态。
 */
public final class PermissionGoals {
  private PermissionGoals() {}

  public static final String BASE = "base",
      FILES = "files",
      SCREEN = "screen",
      SHELL = "shell",
      BACKGROUND = "bg";

  /** 卡片 id。 */
  public static final String STORAGE = "storage",
      A11Y = "a11y",
      OVERLAY = "overlay",
      NOTIFY = "notify",
      CHANNEL = "channel",
      BATTERY = "battery";

  /** 分组 id，默认顺序即原型顺序。 */
  public static final String GROUP_BASIC = "basic",
      GROUP_FILES = "files",
      GROUP_SCREEN = "screen",
      GROUP_SHELL = "shell";

  private static final List<String> GROUPS =
      Collections.unmodifiableList(List.of(GROUP_BASIC, GROUP_FILES, GROUP_SCREEN, GROUP_SHELL));

  /** 可选用途（BASE 始终视为已选，不带推荐授权）。 */
  public static List<String> goals() {
    return List.of(BASE, FILES, SCREEN, SHELL, BACKGROUND);
  }

  /** 用途对应的推荐卡片；未知用途忽略。 */
  public static List<String> cardsFor(String goal) {
    if (goal == null) return List.of();
    switch (goal) {
      case FILES:
        return List.of(STORAGE);
      case SCREEN:
        return List.of(A11Y, OVERLAY, NOTIFY);
      case SHELL:
        return List.of(CHANNEL);
      case BACKGROUND:
        return List.of(BATTERY, NOTIFY);
      default:
        return List.of();
    }
  }

  public static Set<String> recommended(Set<String> goals) {
    Set<String> result = new LinkedHashSet<>();
    if (goals == null) return result;
    for (String goal : goals()) if (goals.contains(goal)) result.addAll(cardsFor(goal));
    return result;
  }

  /** 卡片所属分组。 */
  public static String groupOf(String card) {
    if (card == null) return "";
    switch (card) {
      case NOTIFY:
      case BATTERY:
        return GROUP_BASIC;
      case STORAGE:
        return GROUP_FILES;
      case A11Y:
      case OVERLAY:
        return GROUP_SCREEN;
      case CHANNEL:
        return GROUP_SHELL;
      default:
        return "";
    }
  }

  /** 分组内的卡片，按原型顺序。 */
  public static List<String> cardsIn(String group) {
    if (group == null) return List.of();
    switch (group) {
      case GROUP_BASIC:
        return List.of(NOTIFY, BATTERY);
      case GROUP_FILES:
        return List.of(STORAGE);
      case GROUP_SCREEN:
        return List.of(A11Y, OVERLAY);
      case GROUP_SHELL:
        return List.of(CHANNEL);
      default:
        return List.of();
    }
  }

  /** 有推荐项的分组排前，其余保持默认顺序（稳定排序）。 */
  public static List<String> groupOrder(Set<String> goals) {
    Set<String> recommended = recommended(goals);
    List<String> first = new ArrayList<>(), rest = new ArrayList<>();
    for (String group : GROUPS) {
      boolean hit = false;
      for (String card : cardsIn(group)) hit |= recommended.contains(card);
      (hit ? first : rest).add(group);
    }
    first.addAll(rest);
    return Collections.unmodifiableList(first);
  }

  /** 推荐授权卡片数（第 2 步按钮文案用）。 */
  public static int recommendedCount(Set<String> goals) {
    return recommended(goals).size();
  }

  /** 有推荐项的分组（展开显示），按 groupOrder 顺序。 */
  public static List<String> recommendedGroups(Set<String> goals) {
    Set<String> rec = recommended(goals);
    List<String> result = new ArrayList<>();
    for (String group : groupOrder(goals)) {
      boolean hit = false;
      for (String card : cardsIn(group)) hit |= rec.contains(card);
      if (hit) result.add(group);
    }
    return Collections.unmodifiableList(result);
  }

  /** 没有推荐项的分组（折叠到“其他授权（可选）”）。 */
  public static List<String> otherGroups(Set<String> goals) {
    List<String> rec = recommendedGroups(goals);
    List<String> result = new ArrayList<>();
    for (String group : groupOrder(goals)) if (!rec.contains(group)) result.add(group);
    return Collections.unmodifiableList(result);
  }

  /**
   * 卡片是否已满足。states 顺序固定：存储、Shizuku / Stellar、Root、无障碍、通知、悬浮窗、电池优化；
   * 设备命令通道任选其一（Shizuku / Stellar 或 Root）。
   */
  public static boolean satisfied(String card, boolean[] states) {
    if (card == null || states == null || states.length < 7) return false;
    switch (card) {
      case STORAGE:
        return states[0];
      case CHANNEL:
        return states[1] || states[2];
      case A11Y:
        return states[3];
      case NOTIFY:
        return states[4];
      case OVERLAY:
        return states[5];
      case BATTERY:
        return states[6];
      default:
        return false;
    }
  }

  /** 推荐卡片中已满足的数量。 */
  public static int recommendedDone(Set<String> goals, boolean[] states) {
    int done = 0;
    for (String card : recommended(goals)) if (satisfied(card, states)) done++;
    return done;
  }

  /** 推荐卡片中尚未满足的卡片 id（完成页提示用）。 */
  public static List<String> recommendedMissing(Set<String> goals, boolean[] states) {
    List<String> missing = new ArrayList<>();
    for (String card : recommended(goals)) if (!satisfied(card, states)) missing.add(card);
    return Collections.unmodifiableList(missing);
  }
}
