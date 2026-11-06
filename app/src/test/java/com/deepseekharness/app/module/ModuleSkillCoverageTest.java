package com.deepseekharness.app.module;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

/**
 * 技能文档必须覆盖容器 dsh 需要知道的全部通道：卡片皮肤（home.cards）、
 * 容器工具登记（~/.dsha-launch/）以及容器→壳的模块安装路径（~/.dsha-modules/）。
 * 容器里的 dsh 只靠这份文档理解模块格式，缺一节它就会写错或不知道能写。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleSkillCoverageTest {

  @Test
  public void documentsContainerToAppModuleChannel() {
    String skill = ModuleCard.SKILL;
    assertTrue(skill, skill.contains("~/.dsha-modules/"));
    // 同 id 重写即更新；写完用户回主页就能看到
    assertTrue(skill, skill.contains("宿主按 `id` 去重"));
  }

  @Test
  public void documentsHomeCardSkinTokens() {
    String skill = ModuleCard.SKILL;
    assertTrue(skill, skill.contains("ui.skin.home.cards"));
    assertTrue(skill, skill.contains("metric-environment"));
    assertTrue(skill, skill.contains("iconTint"));
    assertTrue(skill, skill.contains("`\"*\"` 是全部卡的默认") || skill.contains("\"*\" 是全部卡的默认"));
  }

  @Test
  public void documentsContainerToolRegistration() {
    String skill = ModuleCard.SKILL;
    assertTrue(skill, skill.contains("~/.dsha-launch/"));
    assertTrue(skill, skill.contains("dsha-tool-<id>.log"));
    assertTrue(skill, skill.contains("port"));
    // 生命周期语义要写对：宿主持有进程、nohup 在容器会话里无效、重复启动=重启
    assertTrue(skill, skill.contains("宿主进程活着程序就活着"));
    assertTrue(skill, skill.contains("proot 退出会连带杀掉后台进程"));
    assertTrue(skill, skill.contains("先停掉旧实例"));
    // 如实说明：用户显式登记的执行入口，不走模块授权弹窗
    assertTrue(skill, skill.contains("不走模块授权弹窗"));
    // 壳内也能新建/编辑/删除，写回的是同一份容器 JSON
    assertTrue(skill, skill.contains("壳内也能新建/编辑/删除"));
    assertTrue(skill, skill.contains("新建容器程序"));
  }

  @Test
  public void documentsLaunchPickerTargets() {
    String skill = ModuleCard.SKILL;
    // 启动按钮只启动容器里的东西：容器工作台，或容器里登记的程序（tool:<id>）。
    assertTrue(skill, skill.contains("launch.set"));
    assertTrue(skill, skill.contains("启动入口"));
    assertTrue(skill, skill.contains("tool:my-tool"));
    assertTrue(skill, skill.contains("启动按钮不负责壳内导航"));
  }
}
