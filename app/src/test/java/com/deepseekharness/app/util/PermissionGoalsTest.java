package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class PermissionGoalsTest {
  @Test
  public void noGoalsRecommendsNothingAndKeepsDefaultOrder() {
    assertTrue(PermissionGoals.recommended(Set.of()).isEmpty());
    assertTrue(PermissionGoals.recommended(null).isEmpty());
    assertEquals(
        List.of("basic", "files", "screen", "shell"), PermissionGoals.groupOrder(Set.of()));
    assertEquals(0, PermissionGoals.recommendedCount(Set.of(PermissionGoals.BASE)));
  }

  @Test
  public void screenGoalRecommendsAllThreePhoneTaskPrerequisites() {
    Set<String> rec = PermissionGoals.recommended(Set.of(PermissionGoals.SCREEN));
    assertEquals(Set.of("a11y", "overlay", "notify"), rec);
    // 屏幕组与基础组（通知）都含推荐项，二者排前且保持默认相对顺序。
    assertEquals(
        List.of("basic", "screen", "files", "shell"),
        PermissionGoals.groupOrder(Set.of(PermissionGoals.SCREEN)));
  }

  @Test
  public void overlappingGoalsDeduplicateRecommendations() {
    Set<String> goals = Set.of(PermissionGoals.SCREEN, PermissionGoals.BACKGROUND);
    assertEquals(4, PermissionGoals.recommendedCount(goals));
    assertTrue(PermissionGoals.recommended(goals).contains(PermissionGoals.BATTERY));
  }

  @Test
  public void shellAndFilesMoveTheirGroupsFirst() {
    assertEquals(
        List.of("files", "shell", "basic", "screen"),
        PermissionGoals.groupOrder(Set.of(PermissionGoals.SHELL, PermissionGoals.FILES)));
  }

  @Test
  public void everyCardBelongsToExactlyOneGroup() {
    for (String group : List.of("basic", "files", "screen", "shell"))
      for (String card : PermissionGoals.cardsIn(group))
        assertEquals(group, PermissionGoals.groupOf(card));
    assertEquals("", PermissionGoals.groupOf("unknown"));
    assertTrue(PermissionGoals.cardsFor("unknown").isEmpty());
    assertTrue(PermissionGoals.cardsFor(PermissionGoals.BASE).isEmpty());
  }

  @Test
  public void goalsSplitGroupsIntoRecommendedAndCollapsed() {
    Set<String> files = Set.of(PermissionGoals.FILES);
    assertEquals(List.of("files"), PermissionGoals.recommendedGroups(files));
    assertEquals(List.of("basic", "screen", "shell"), PermissionGoals.otherGroups(files));
    // 只选“对话、写代码”：没有推荐分组，全部折叠为可选。
    assertTrue(PermissionGoals.recommendedGroups(Set.of(PermissionGoals.BASE)).isEmpty());
    assertEquals(4, PermissionGoals.otherGroups(Set.of(PermissionGoals.BASE)).size());
  }

  @Test
  public void recommendedProgressCountsChannelAsEitherShizukuOrRoot() {
    Set<String> goals = Set.of(PermissionGoals.SHELL, PermissionGoals.SCREEN);
    // 存储、Shizuku、Root、无障碍、通知、悬浮窗、电池
    boolean[] none = new boolean[7];
    assertEquals(0, PermissionGoals.recommendedDone(goals, none));
    assertEquals(4, PermissionGoals.recommendedMissing(goals, none).size());
    boolean[] rootAndA11y = {false, false, true, true, false, false, false};
    assertEquals(2, PermissionGoals.recommendedDone(goals, rootAndA11y));
    assertEquals(
        List.of("overlay", "notify"),
        PermissionGoals.recommendedMissing(goals, rootAndA11y).stream()
            .filter(c -> !c.equals("a11y") && !c.equals("channel"))
            .toList());
    assertFalse(PermissionGoals.satisfied("unknown", rootAndA11y));
    assertFalse(PermissionGoals.satisfied(PermissionGoals.STORAGE, null));
  }
}
