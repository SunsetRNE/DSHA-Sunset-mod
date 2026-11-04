package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.UiText;
import org.junit.Before;
import org.junit.Test;

/**
 * 模块中心定时任务区的纯格式化：时间补零、动作命名、命令摘要与授权 / Doze 说明。
 * 不创建 Android 页面、不读写 ModuleScheduler 存储，只验界面会写什么。
 */
public final class ModuleCenterSchedulePresenterTest {

  @Before
  public void pinLanguage() {
    // 固定中文，断言具体文案；presenter 自身仍按 UiText.choose 出中英文。
    UiText.setLanguage("zh");
  }

  @Test
  public void timeLabelPadsTwoDigits() {
    assertEquals("07:05", ModuleCenterActivity.SchedulePresenter.timeLabel(7, 5));
    assertEquals("00:00", ModuleCenterActivity.SchedulePresenter.timeLabel(0, 0));
    assertEquals("23:59", ModuleCenterActivity.SchedulePresenter.timeLabel(23, 59));
  }

  @Test
  public void kindLabelMapsKnownKindsAndPassesUnknownThrough() {
    assertEquals("执行命令", ModuleCenterActivity.SchedulePresenter.kindLabel("exec"));
    assertEquals("启动容器", ModuleCenterActivity.SchedulePresenter.kindLabel("dsh.start"));
    assertEquals("停止容器", ModuleCenterActivity.SchedulePresenter.kindLabel("dsh.stop"));
    // 未知类型原样返回，不冒充已认识
    assertEquals("rsync", ModuleCenterActivity.SchedulePresenter.kindLabel("rsync"));
    assertEquals("", ModuleCenterActivity.SchedulePresenter.kindLabel(null));
  }

  @Test
  public void commandSummaryCollapsesWhitespaceAndTruncates() {
    assertEquals(
        "git pull && df -h",
        ModuleCenterActivity.SchedulePresenter.commandSummary("git pull\n  &&\tdf -h ", 60));
    String longValue = "a".repeat(61);
    assertEquals(
        "a".repeat(60) + "…", ModuleCenterActivity.SchedulePresenter.commandSummary(longValue, 60));
    assertEquals(
        "60 字以内原样",
        longValue.substring(0, 60),
        ModuleCenterActivity.SchedulePresenter.commandSummary(longValue.substring(0, 60), 60));
    assertEquals("", ModuleCenterActivity.SchedulePresenter.commandSummary("", 60));
    assertEquals("", ModuleCenterActivity.SchedulePresenter.commandSummary(null, 60));
    assertEquals("", ModuleCenterActivity.SchedulePresenter.commandSummary("x", 0));
  }

  @Test
  public void entryLineShowsLabelTimeKindAndExecSummary() {
    String exec = ModuleCenterActivity.SchedulePresenter.entryLine("晨检", 7, 30, "exec", "uname -m");
    assertTrue(exec, exec.contains("晨检"));
    assertTrue(exec, exec.contains("每天 07:30"));
    assertTrue(exec, exec.contains("执行命令"));
    assertTrue(exec, exec.contains("uname -m"));

    String start = ModuleCenterActivity.SchedulePresenter.entryLine("开机", 8, 0, "dsh.start", "");
    assertTrue(start, start.contains("每天 08:00"));
    assertTrue(start, start.contains("启动容器"));
    assertFalse(start, start.contains("·  ·"));

    // 空标签如实叫「未命名任务」，不编名字
    String unnamed = ModuleCenterActivity.SchedulePresenter.entryLine("", 9, 0, "exec", "echo hi");
    assertTrue(unnamed, unnamed.startsWith("未命名任务 · "));
    assertTrue(unnamed, unnamed.contains("echo hi"));
  }

  @Test
  public void sectionHeaderStatesCountIncludingZero() {
    assertTrue(ModuleCenterActivity.SchedulePresenter.sectionHeader(2).contains("定时任务 · 2 条"));
    assertTrue(ModuleCenterActivity.SchedulePresenter.sectionHeader(0).contains("定时任务 · 0 条"));
  }

  @Test
  public void authStatusReflectsGrantAndSkipWithoutExecuting() {
    String allowed = ModuleCenterActivity.SchedulePresenter.authStatus(true);
    assertTrue(allowed, allowed.contains("已授权运行"));

    String blocked = ModuleCenterActivity.SchedulePresenter.authStatus(false);
    assertTrue(blocked, blocked.contains("未授权运行"));
    assertTrue(blocked, blocked.contains("被跳过"));
    assertTrue(blocked, blocked.contains("不会执行"));
  }

  @Test
  public void delayNoteCoversDozePostponeAndLaterRevoke() {
    String note = ModuleCenterActivity.SchedulePresenter.delayNote();
    assertTrue(note, note.contains("非精确"));
    assertTrue(note, note.contains("Doze"));
    assertTrue(note, note.contains("推迟"));
    assertTrue(note, note.contains("撤销"));
    assertTrue(note, note.contains("跳过"));
  }

  @Test
  public void emptyNoteExplainsWhereTasksComeFrom() {
    String note = ModuleCenterActivity.SchedulePresenter.emptyNote();
    assertTrue(note, note.contains("还没有定时任务"));
    assertTrue(note, note.contains("schedule.set"));
  }
}
