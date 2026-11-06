package com.deepseekharness.app.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

/**
 * 技能文档（写入容器的 SKILL.md / HOW-TO.md）里的定时任务章节：
 * schedule.set/list/clear 与 dsh.start/stop 的真实参数、逐卡授权要求、
 * 非精确闹钟语义都要写清；不虚报程序包 / ARM ELF 能力，也不把容器命令说成强沙箱。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleSkillScheduleDocTest {

  @Test
  public void documentsAllScheduleServices() {
    String skill = ModuleCard.SKILL;
    assertTrue(skill.contains("schedule.set"));
    assertTrue(skill.contains("schedule.list"));
    assertTrue(skill.contains("schedule.clear"));
    assertTrue(skill.contains("dsh.start"));
    assertTrue(skill.contains("dsh.stop"));
  }

  @Test
  public void documentsRealParameters() {
    String skill = ModuleCard.SKILL;
    // 小时 / 分钟范围、exec 的 value 上限、label 上限、每卡条数上限
    assertTrue(skill, skill.contains("`hour` 0-23、`minute` 0-59"));
    assertTrue(skill, skill.contains("≤500 字"));
    assertTrue(skill, skill.contains("最长 24 字"));
    assertTrue(skill, skill.contains("每张卡片最多 8 条"));
    // kind 三种取值与「后两种不带 value」都要写明
    assertTrue(skill, skill.contains("`dsh.start`（启动容器）或 `dsh.stop`（停止容器）"));
    assertTrue(skill, skill.contains("后两种不带 `value`"));
    // 同一「时间 + 动作」重复 set 是更新，不是新增
    assertTrue(skill, skill.contains("再 `set` 一次是改这条任务"));
  }

  @Test
  public void documentsPerCardAuthorizationAndSkipOnRevoke() {
    String skill = ModuleCard.SKILL;
    assertTrue(skill, skill.contains("登记与到点执行都走这张卡片的运行授权"));
    assertTrue(skill, skill.contains("没授权时 `schedule.set` 直接被拒"));
    assertTrue(skill, skill.contains("被跳过"));
    assertTrue(skill, skill.contains("不会执行"));
    // 授权能力清单里也要列全（schedule 与容器启停都需要授权）
    assertTrue(skill, skill.contains("`exec.run` / `schedule.set` /"));
    assertTrue(skill, skill.contains("`schedule.clear` / `dsh.start` / `dsh.stop`"));
  }

  @Test
  public void documentsInexactAlarmAndDozeDelay() {
    String skill = ModuleCard.SKILL;
    assertTrue(skill, skill.contains("闹钟是非精确的"));
    assertTrue(skill, skill.contains("Doze"));
    assertTrue(skill, skill.contains("推迟"));
    // 重启后自动重排、移除模块连带撤销任务，也如实写
    assertTrue(skill, skill.contains("重新登记"));
    assertTrue(skill, skill.contains("一并撤销"));
  }

  @Test
  public void doesNotClaimArbitraryProgramPackagesOrStrongSandbox() {
    String skill = ModuleCard.SKILL;
    // 受管程序能力要如实描述：只能随模块包导入、过 SHA-256/ELF ABI，不能从网络下载。
    assertFalse(skill, skill.contains("支持程序包"));
    assertFalse(skill, skill.contains("支持 ARM"));
    assertTrue(skill, skill.contains("受管程序只能随模块包导入并过 SHA-256/ELF ABI 校验"));
    assertTrue(skill, skill.contains("不能从网络下载"));
    // 不把容器命令当强沙箱：如实写授权挡的是静默执行，不是安全沙箱
    assertFalse(skill, skill.contains("强沙箱"));
    assertTrue(skill, skill.contains("不是安全沙箱"));
  }
}
