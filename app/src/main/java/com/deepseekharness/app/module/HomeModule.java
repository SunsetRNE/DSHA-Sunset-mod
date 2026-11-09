package com.deepseekharness.app.module;

import com.deepseekharness.app.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主页模块注册表：主页上每一块可编辑的区域都是一个模块。
 *
 * <p>这里只登记「显示什么、叫什么、能怎么改」，不持有 View、不执行代码。
 * 主页按这张表渲染，用户拖动／缩放／收起的是模块的位置和尺寸，不是业务状态。
 * 以后新增一个主页模块只需在这里加一条并在 {@code HomeCanvas} 提供对应视图。
 */
public final class HomeModule {
  /** 全宽一行；默认尺寸。 */
  public static final String SIZE_FULL = "full";

  /** 半宽，与另一个半宽模块并排一行。 */
  public static final String SIZE_HALF = "half";

  public final String id;
  public final int icon;
  public final String titleZh;
  public final String titleEn;
  public final String detailZh;
  public final String detailEn;

  private HomeModule(
      String id, int icon, String titleZh, String titleEn, String detailZh, String detailEn) {
    this.id = id;
    this.icon = icon;
    this.titleZh = titleZh;
    this.titleEn = titleEn;
    this.detailZh = detailZh;
    this.detailEn = detailEn;
  }

  /** 词库统一出文案：zh 键进目录，十语言一致；english 参数保留给既有调用方。 */
  public String title(boolean english) {
    return com.deepseekharness.app.util.UiText.text(titleZh);
  }

  public String detail(boolean english) {
    return com.deepseekharness.app.util.UiText.text(detailZh);
  }

  private static final Map<String, HomeModule> MODULES;
  private static final List<String> DEFAULT_ORDER;

  static {
    Map<String, HomeModule> modules = new LinkedHashMap<>();
    put(
        modules,
        new HomeModule(
            "actions",
            R.drawable.ic_launch,
            "工作台",
            "Workspace",
            "启动、重启与停止，以及当前运行状态",
            "Start, restart, stop and the current run state"));
    // 三格状态是三个独立便签：可以各自拖动、改宽高、收起。
    put(
        modules,
        new HomeModule(
            "metric-environment",
            R.drawable.ic_ui2_box,
            "运行环境",
            "Runtime environment",
            "环境是否就绪、是否需要恢复",
            "Whether the environment is ready or needs repair"));
    put(
        modules,
        new HomeModule(
            "metric-workbench",
            R.drawable.ic_terminal,
            "工作台状态",
            "Workspace status",
            "DSH 是否可进入，以及当前阶段",
            "Whether DSH is ready to open and its current stage"));
    put(
        modules,
        new HomeModule(
            "metric-port",
            R.drawable.ic_ui_link,
            "Web 端口",
            "Web port",
            "本轮实际监听的端口",
            "The port actually listening for this run"));
    put(
        modules,
        new HomeModule(
            "shortcuts-title",
            R.drawable.ic_ui2_prompt,
            "快捷标题",
            "Shortcut heading",
            "「快捷入口」小标题",
            "The small heading above shortcuts"));
    put(
        modules,
        new HomeModule(
            "shortcuts",
            R.drawable.ic_ui2_folder,
            "快捷入口",
            "Shortcuts",
            "终端、运行环境、访问地址与 SSH 调试",
            "Terminal, runtime, access address and SSH debugging"));
    put(
        modules,
        new HomeModule(
            "logs",
            R.drawable.ic_terminal_display,
            "运行日志",
            "Runtime log",
            "最近日志、复制与导出",
            "Recent log with copy and export"));
    MODULES = Collections.unmodifiableMap(modules);
    DEFAULT_ORDER = Collections.unmodifiableList(new ArrayList<>(modules.keySet()));
  }

  private static void put(Map<String, HomeModule> target, HomeModule module) {
    target.put(module.id, module);
  }

  public static List<String> defaultOrder() {
    return DEFAULT_ORDER;
  }

  public static HomeModule of(String id) {
    return MODULES.get(id);
  }

  public static boolean known(String id) {
    return MODULES.containsKey(id);
  }

  public static List<HomeModule> all() {
    return new ArrayList<>(MODULES.values());
  }
}
