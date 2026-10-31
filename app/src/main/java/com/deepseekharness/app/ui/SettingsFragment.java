package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;

import java.util.function.Supplier;

/**
 * 设置页：模块入口（安装/配置/数据与备份）+ 其他（更新/自检/重新解压/关于）。
 */
public class SettingsFragment extends Fragment {

  private static final TabOption[] TAB_OPTIONS = {
    new TabOption("安装", "安装与修复运行环境", InstallFragment::new, R.drawable.ic_ui2_box),
    new TabOption("配置", "接口、显示与运行", ConfigFragment::new, R.drawable.ic_settings),
    new TabOption("数据与备份", "备份恢复 · 文件共享", WorkspaceFragment::new, R.drawable.ic_ui2_folder),
    new TabOption(
        "设备能力授权", "Root · Shizuku · ADB · 权限", DeviceGrantsFragment::new, R.drawable.ic_ui_shield),
    new TabOption("任务状态上岛", "状态栏胶囊 · 灵动岛 · 实时活动", IslandFragment::new, R.drawable.ic_ui2_box),
  };

  private static final String STATE_SELECTED = "settings_selected";
  private static final String KEY_OVERLAY = "overlay";
  private static final String KEY_ABOUT = "about";
  private static final String DEFAULT_KEY = "tab0";

  /** 双栏左侧可选中的行：key → 行视图。单栏时不使用选中态。 */
  private final java.util.Map<String, View> selectable = new java.util.LinkedHashMap<>();

  private boolean twoPane;
  private String selectedKey = DEFAULT_KEY;
  private final androidx.fragment.app.FragmentManager.OnBackStackChangedListener detailStack =
      this::notifyHost;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    twoPane = SettingsPanes.twoPane(requireContext());
    if (savedInstanceState != null)
      selectedKey = savedInstanceState.getString(STATE_SELECTED, DEFAULT_KEY);
    // 从双栏旋转/分屏到单栏：上次恢复的详情页不再有可见容器，直接丢弃，回到整页列表。
    if (!twoPane && !getChildFragmentManager().getFragments().isEmpty()) {
      var children = getChildFragmentManager();
      children.popBackStackImmediate(
          null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
      var removal = children.beginTransaction();
      for (Fragment child : children.getFragments()) removal.remove(child);
      removal.commitNow();
    }
    getChildFragmentManager().addOnBackStackChangedListener(detailStack);
  }

  @Override
  public void onDestroy() {
    getChildFragmentManager().removeOnBackStackChangedListener(detailStack);
    super.onDestroy();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(STATE_SELECTED, selectedKey);
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_settings, container, false);
    selectable.clear();

    LinearLayout tabs = v.findViewById(R.id.settings_tabs);
    int optionCount = TAB_OPTIONS.length;
    for (int i = 0; i < optionCount; i++) {
      if (i > 0) {
        View line = divider();
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.leftMargin = dp(44);
        tabs.addView(line, lineParams);
      }
      tabs.addView(buildRow(i), new LinearLayout.LayoutParams(-1, -2));
    }
    com.deepseekharness.app.core.ConfigStore config =
        HarnessController.get(requireContext()).config();
    TextView powerTitle = v.findViewById(R.id.settings_power_title);
    powerTitle.setText(com.deepseekharness.app.util.UiText.text("省电模式"));
    TextView powerHint = v.findViewById(R.id.settings_power_hint);
    com.google.android.material.materialswitch.MaterialSwitch eco =
        v.findViewById(R.id.settings_eco);
    eco.setContentDescription(com.deepseekharness.app.util.UiText.text("省电模式"));
    eco.setChecked(config.isEcoMode());
    java.util.function.Consumer<Boolean> describe =
        enabled ->
            powerHint.setText(
                enabled
                    ? com.deepseekharness.app.util.UiText.text("熄屏空闲 1 分钟后减少保活；有任务时继续运行。")
                    : com.deepseekharness.app.util.UiText.text("持续保持运行，适合长时间任务。"));
    describe.accept(config.isEcoMode());
    eco.setOnCheckedChangeListener(
        (button, checked) -> {
          config.setEcoMode(checked);
          com.deepseekharness.app.HarnessService.refreshPowerMode(requireContext());
          describe.accept(checked);
        });

    String version = "unknown";
    try {
      version =
          requireContext()
              .getPackageManager()
              .getPackageInfo(requireContext().getPackageName(), 0)
              .versionName;
    } catch (Exception ignored) {
    }
    TextView ver = v.findViewById(R.id.settings_ver);
    ver.setText(com.deepseekharness.app.util.UiText.format("DSHA v%s · MIT 许可", version));
    TextView updateSub = v.findViewById(R.id.settings_update_sub);
    updateSub.setText(com.deepseekharness.app.util.UiText.text("稳定版与预览版更新"));

    View about = v.findViewById(R.id.settings_about);
    selectable.put(KEY_ABOUT, about);
    about.setOnClickListener(x -> select(KEY_ABOUT));
    v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
    v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());

    // 语言入口：标题在中文界面下也带英文「Language」（只写「语言 · 简体中文」时，
    // 非中文用户根本认不出这是语言开关）；摘要直接显示当前生效语言。
    // 放在「常用设置」分组最前面，不用滚动就能看到。
    String preference = config.getUiLanguagePreference();
    boolean followSystem =
        com.deepseekharness.app.util.UiLanguagePreference.followsSystem(preference);
    String effective = config.getUiLanguage();
    java.util.List<String> languages = com.deepseekharness.app.util.UiLanguagePreference.LANGUAGES;
    String[] optionValues = new String[languages.size() + 1];
    String[] optionLabels = new String[optionValues.length];
    optionValues[0] = com.deepseekharness.app.util.UiLanguagePreference.SYSTEM;
    optionLabels[0] = com.deepseekharness.app.util.UiText.choose("跟随系统", "Follow system");
    for (int i = 0; i < languages.size(); i++) {
      optionValues[i + 1] = languages.get(i);
      // Native labels (including English) stay recognizable in an unfamiliar locale.
      optionLabels[i + 1] =
          com.deepseekharness.app.util.UiLanguagePreference.nativeName(languages.get(i));
    }
    int checked = followSystem ? 0 : languages.indexOf(preference) + 1;
    // 摘要始终显示当前**生效**语言的名字：跟随系统时补上来由，用户一眼能看出实际结果。
    String currentLabel = com.deepseekharness.app.util.UiLanguagePreference.nativeName(effective);
    String summary =
        followSystem
            ? com.deepseekharness.app.util.UiText.format("跟随系统 · %s", currentLabel)
            : currentLabel;
    LinearLayout appearance = v.findViewById(R.id.settings_appearance);
    TextView languageSummary = v.findViewById(R.id.settings_language);
    languageSummary.setText(summary);
    View.OnClickListener openLanguageDialog =
        x ->
            new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(
                    com.deepseekharness.app.util.UiText.choose(
                        "界面语言 / Interface language", "Interface language"))
                .setSingleChoiceItems(
                    optionLabels,
                    checked,
                    (dialog, which) -> {
                      String selected = optionValues[which];
                      if (dialog instanceof android.app.Dialog)
                        ((android.app.Dialog) dialog)
                            .setOnDismissListener(
                                ignored ->
                                    new android.os.Handler(android.os.Looper.getMainLooper())
                                        .post(
                                            () ->
                                                LanguageController.select(
                                                    requireContext(), selected)));
                      dialog.dismiss();
                    })
                .setNegativeButton(com.deepseekharness.app.util.UiText.choose("取消", "Cancel"), null)
                .show();
    appearance.setOnClickListener(openLanguageDialog);
    languageSummary.setOnClickListener(openLanguageDialog);
    View overlayRow = v.findViewById(R.id.settings_overlay);
    selectable.put(KEY_OVERLAY, overlayRow);
    overlayRow.setOnClickListener(x -> select(KEY_OVERLAY));
    InfoHint.attach(
        (TextView) v.findViewById(R.id.settings_page_hint), getString(R.string.ui2_settings));
    if (!twoPane) return v;
    return twoPaneShell(v, savedInstanceState);
  }

  // ---------------------------------------------------------------- 方案 A 双栏

  boolean isTwoPane() {
    return twoPane && getView() != null && getView().findViewById(R.id.settings_detail) != null;
  }

  /** 左侧列表固定宽度、右侧详情占余下宽度；详情容器 id 固定，子页可跨旋转恢复。 */
  private View twoPaneShell(View list, @Nullable Bundle savedInstanceState) {
    android.content.Context context = requireContext();
    list.setId(R.id.settings_list_scroll);
    for (View row : selectable.values()) {
      row.setBackgroundResource(R.drawable.bg_settings_row);
      row.setForeground(context.getDrawable(R.drawable.bg_action_plain));
    }
    LinearLayout shell = new LinearLayout(context);
    shell.setOrientation(LinearLayout.HORIZONTAL);
    shell.setBackgroundColor(context.getColor(R.color.shell_bg));
    shell.addView(list, new LinearLayout.LayoutParams(dp(LIST_PANE_DP), -1));
    View line = divider();
    shell.addView(line, new LinearLayout.LayoutParams(dp(1), -1));
    android.widget.FrameLayout detail = new android.widget.FrameLayout(context);
    detail.setId(R.id.settings_detail);
    shell.addView(detail, new LinearLayout.LayoutParams(0, -1, 1f));
    // 首次进入：直接给右侧一个默认详情；旋转/恢复时由 FragmentManager 自行恢复原详情页。
    if (getChildFragmentManager().findFragmentById(R.id.settings_detail) == null)
      shell.post(
          () -> {
            if (isAdded() && !getChildFragmentManager().isStateSaved()) showDetail(selectedKey);
          });
    markSelected();
    return shell;
  }

  private static final int LIST_PANE_DP = 320;

  /** 单栏：整页推进（原行为）；双栏：右侧详情替换并清空详情内的下一级返回栈。 */
  private void select(String key) {
    if (!isTwoPane()) {
      Fragment page = create(key);
      if (page == null) return;
      UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
          .replace(R.id.fragment_container, page)
          .addToBackStack("settings")
          .commit();
      return;
    }
    if (key.equals(selectedKey)
        && getChildFragmentManager().getBackStackEntryCount() == 0
        && getChildFragmentManager().findFragmentById(R.id.settings_detail) != null) return;
    showDetail(key);
  }

  private void showDetail(String key) {
    Fragment page = create(key);
    if (page == null) {
      key = DEFAULT_KEY;
      page = create(key);
    }
    selectedKey = key;
    markSelected();
    var children = getChildFragmentManager();
    if (children.isStateSaved()) return;
    children.popBackStackImmediate(
        null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    UiMotion.page(requireContext(), children.beginTransaction())
        .replace(R.id.settings_detail, page)
        .commit();
    notifyHost();
  }

  /** 详情页内再打开下一级页面（由 {@link SettingsPanes#open} 调用）。 */
  void pushDetail(Fragment page) {
    var children = getChildFragmentManager();
    if (children.isStateSaved()) return;
    UiMotion.page(requireContext(), children.beginTransaction())
        .replace(R.id.settings_detail, page)
        .addToBackStack("settings_detail")
        .commit();
  }

  /** 系统返回 / 顶栏返回：先退详情内的下一级页面。返回 true 表示已处理。 */
  boolean popDetail() {
    if (!isTwoPane()) return false;
    var children = getChildFragmentManager();
    if (children.getBackStackEntryCount() == 0 || children.isStateSaved()) return false;
    children.popBackStackImmediate();
    return true;
  }

  boolean hasNestedDetail() {
    return isTwoPane() && getChildFragmentManager().getBackStackEntryCount() > 0;
  }

  @Nullable
  Fragment shownDetail() {
    return isTwoPane() ? getChildFragmentManager().findFragmentById(R.id.settings_detail) : null;
  }

  @Nullable
  private Fragment create(String key) {
    if (KEY_OVERLAY.equals(key)) return new OverlayFragment();
    if (KEY_ABOUT.equals(key)) return new AboutFragment();
    if (key.startsWith("tab")) {
      try {
        int index = Integer.parseInt(key.substring(3));
        if (index >= 0 && index < TAB_OPTIONS.length && TAB_OPTIONS[index].factory != null)
          return TAB_OPTIONS[index].factory.get();
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }

  private void markSelected() {
    // View 的选中态会同步到无障碍节点（TalkBack 读出「已选择」），不另加委托。
    for (java.util.Map.Entry<String, View> entry : selectable.entrySet())
      entry.getValue().setSelected(twoPane && entry.getKey().equals(selectedKey));
  }

  private void notifyHost() {
    if (getActivity() instanceof MainActivity)
      ((MainActivity) getActivity()).onSettingsDetailChanged();
  }

  private void runSelftest() {
    startActivity(new Intent(requireContext(), DiagnosticActivity.class));
  }

  private void checkUpdate() {
    startActivity(new Intent(requireContext(), UpdateActivity.class));
  }

  private LinearLayout buildRow(final int index) {
    TabOption opt = TAB_OPTIONS[index];
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(56));
    row.setPadding(dp(16), dp(12), dp(16), dp(12));
    row.setFocusable(true);
    // 行内按压反馈沿用主题 ripple；卡片圆角由父容器 bg_shell_card 承担。
    row.setBackgroundResource(R.drawable.bg_action_plain);

    LinearLayout body = new LinearLayout(requireContext());
    body.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams bodyParams =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    bodyParams.leftMargin = dp(12);
    bodyParams.rightMargin = dp(8);
    body.setLayoutParams(bodyParams);

    TextView title = new TextView(requireContext());
    title.setText(
        index == 0
            ? getString(R.string.ui2_install_environment)
            : index == 1
                ? getString(R.string.ui2_runtime_config)
                : com.deepseekharness.app.util.UiText.text(opt.title));
    title.setTextSize(14);
    title.setTextColor(requireContext().getColor(R.color.shell_text));
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setIncludeFontPadding(false);

    TextView sub = new TextView(requireContext());
    sub.setText(com.deepseekharness.app.util.UiText.text(opt.sub));
    sub.setTextSize(12);
    sub.setTextColor(requireContext().getColor(R.color.shell_text_muted));
    sub.setIncludeFontPadding(false);
    sub.setMaxLines(2);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams slp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.topMargin = dp(4);
    sub.setLayoutParams(slp);

    body.addView(title);
    body.addView(sub);

    android.widget.ImageView icon = new android.widget.ImageView(requireContext());
    icon.setImageResource(opt.icon);
    icon.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            requireContext().getColor(R.color.shell_accent)));
    icon.setBackgroundResource(R.drawable.bg_logo);
    icon.setPadding(dp(7), dp(7), dp(7), dp(7));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
    row.addView(body);

    android.widget.ImageView chevron = new android.widget.ImageView(requireContext());
    chevron.setImageResource(R.drawable.ic_ui2_chevron);
    chevron.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            requireContext().getColor(R.color.shell_text_muted)));
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(chevron, new LinearLayout.LayoutParams(dp(16), dp(16)));

    if (twoPane) {
      // 双栏左侧列表较窄：说明收成一行，完整含义看右侧详情标题与页内说明。
      sub.setMaxLines(1);
      row.setMinimumHeight(dp(52));
    }
    if (opt.activity == null) selectable.put("tab" + index, row);
    row.setOnClickListener(
        v -> {
          if (opt.activity != null) {
            startActivity(new Intent(requireContext(), opt.activity));
          } else {
            select("tab" + index);
          }
        });
    return row;
  }

  private View divider() {
    View line = new View(requireContext());
    line.setBackgroundColor(requireContext().getColor(R.color.shell_line));
    return line;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private static final class TabOption {
    final String title;
    final String sub;
    final Supplier<Fragment> factory;
    final Class<? extends android.app.Activity> activity;
    final int icon;

    TabOption(String title, String sub, Supplier<Fragment> factory, int icon) {
      this.title = title;
      this.sub = sub;
      this.factory = factory;
      this.activity = null;
      this.icon = icon;
    }

    TabOption(String title, String sub, Class<? extends android.app.Activity> activity, int icon) {
      this.title = title;
      this.sub = sub;
      this.factory = null;
      this.activity = activity;
      this.icon = icon;
    }
  }
}
