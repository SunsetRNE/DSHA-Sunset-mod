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
}
