package com.deepseekharness.app.module;

import com.deepseekharness.app.ui.ConfigFragment;
import com.deepseekharness.app.ui.DeviceGrantsFragment;
import com.deepseekharness.app.ui.InstallFragment;
import com.deepseekharness.app.ui.IslandFragment;
import com.deepseekharness.app.ui.WorkspaceFragment;
import com.deepseekharness.app.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 设置页环境入口目录。目录只保存描述，页面每次点击时才创建目标 Fragment。 */
public final class SettingsModuleCatalog {
  private static final List<UiModuleDescriptor> ALL =
      Collections.unmodifiableList(
          Arrays.asList(
              UiModuleDescriptor.fragmentResourceTitle(
                  "settings.install",
                  R.string.ui2_install_environment,
                  "安装与修复运行环境",
                  R.drawable.ic_ui2_box,
                  InstallFragment::new),
              UiModuleDescriptor.fragmentResourceTitle(
                  "settings.config",
                  R.string.ui2_runtime_config,
                  "接口、显示与运行",
                  R.drawable.ic_settings,
                  ConfigFragment::new),
              UiModuleDescriptor.fragment(
                  "settings.workspace",
                  "数据与备份",
                  "备份恢复 · 文件共享",
                  R.drawable.ic_ui2_folder,
                  WorkspaceFragment::new),
              UiModuleDescriptor.fragment(
                  "settings.device-grants",
                  "设备能力授权",
                  "Root · Shizuku · ADB · 权限",
                  R.drawable.ic_ui_shield,
                  DeviceGrantsFragment::new),
              UiModuleDescriptor.activity(
                  "settings.ssh",
                  "容器 SSH 调试",
                  "电脑连接 · 密码 · 启停",
                  R.drawable.ic_terminal,
                  com.deepseekharness.app.ui.ContainerSshActivity.class),
              UiModuleDescriptor.fragment(
                  "settings.island",
                  "任务状态上岛",
                  "状态栏胶囊 · 灵动岛 · 实时活动",
                  R.drawable.ic_ui2_box,
                  IslandFragment::new,
                  true)));

  private SettingsModuleCatalog() {}

  public static List<UiModuleDescriptor> visible(boolean islandEdition) {
    return Collections.unmodifiableList(
        ALL.stream()
            .filter(module -> islandEdition || !module.islandOnly())
            .collect(Collectors.toList()));
  }
}
