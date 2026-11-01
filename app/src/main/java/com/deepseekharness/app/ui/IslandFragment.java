package com.deepseekharness.app.ui;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.IslandSupport;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 「任务状态上岛」设置子页。
 *
 * <p>版式沿用设置 / 插件子页的 CardPage 语言，不另起一套样式：
 * 26sp 标题 + 一句话副标题；12sp 分组标签；71% 玻璃卡片（18dp 圆角、1dp 描边、16/12 内边距、卡间距 14）；
 * 入口行 {@link CardPage#entry} 56dp（32dp 图标底 + 14sp 标题 + 12sp 当前值 + 箭头）；
 * 开关只带一行 12sp 提示；长解释收进弹层，不在页面上摊开。
 *
 * <p>状态直接读系统（{@link IslandSupport#state}），不缓存、不猜测；状态色只用 shell_good / warn / bad。
 */
public class IslandFragment extends Fragment {

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    CardPage ui =
        new CardPage(
            requireContext(),
            UiText.choose("任务状态上岛", "Task live status"),
            UiText.choose(
                "运行中的任务显示在状态栏胶囊上。", "Running tasks show in the status bar capsule."));
    ConfigStore config = HarnessController.get(requireContext()).config();

    // ---- 系统状态：一条入口行承载当前值，点开直接去系统设置 ----
    ui.label(UiText.choose("系统状态", "System status"));
    LinearLayout status = ui.card();
    int state = IslandSupport.state(requireContext());
    statusRow(
        ui, status, UiText.choose("实时活动", "Live activity"), statusValue(state), toneColor(state));
    TextView scope =
        ui.text(
            state == IslandSupport.STATE_UNSUPPORTED
                ? UiText.choose("需要 Android 16 及以上。", "Requires Android 16 or later.")
                : UiText.choose(
                    "跟随系统开关，随时可以关闭。", "Follows the system switch; you can turn it off anytime."),
            13,
            R.color.shell_text_secondary);
    scope.setLineSpacing(ui.dp(3), 1f);
    scope.setPadding(0, ui.dp(10), 0, ui.dp(4));
    status.addView(scope);
    ui.button(
        status, UiText.choose("打开系统实时活动设置", "Open system live activity settings"), false,
        this::openSystemSettings);

    // ---- 行为：开关 + 一行提示 ----
    ui.label(UiText.choose("行为", "Behavior"));
    LinearLayout behaviors = ui.card();
    MaterialSwitch enabled =
        switchRow(
            behaviors,
            UiText.choose("任务状态上岛", "Task live status"),
            config.isTaskIslandEnabled(),
            config::setTaskIslandEnabled);
    enabled.setContentDescription(UiText.choose("任务状态上岛", "Task live status"));
    hint(behaviors, UiText.choose("只上岛优先级最高的一个任务", "Only the highest-priority task goes on the island"));
    behaviors.addView(divider());
    MaterialSwitch chronometer =
        switchRow(
            behaviors,
            UiText.choose("用系统计时器显示耗时", "Use the system timer for elapsed time"),
            config.isTaskIslandChronometer(),
            config::setTaskIslandChronometer);
    chronometer.setContentDescription(
        UiText.choose("用系统计时器显示耗时", "Use the system timer for elapsed time"));
    hint(behaviors, UiText.choose("关闭时显示开始时间", "Shows the start time when off"));

    // ---- 兼容性：一条入口行，正文进弹层 ----
    ui.label(UiText.choose("兼容性", "Compatibility"));
    LinearLayout support = ui.card();
    ui.entry(
        support,
        UiText.choose("支持范围", "Supported devices"),
        UiText.choose("Android 16 · 澎湃 3.0.300+ · ColorOS 16", "Android 16 · HyperOS 3.0.300+ · ColorOS 16"),
        R.drawable.ic_onb_alert,
        () ->
            CardSheet.show(
                requireContext(),
                UiText.choose("支持范围", "Supported devices"),
                UiText.choose(
                    "Android 16 及以上的原生系统、小米澎湃 OS 3.0.300+ / 4、ColorOS 16、OriginOS 6，"
                        + "以及后续跟进同一接口的厂商。\n\n"
                        + "更老的系统与鸿蒙不显示岛，只显示普通通知 —— 这是系统能力差异，不是开关失效。\n\n"
                        + "小米的官方超级岛大岛样式需要向小米申请白名单；本应用不依赖它，"
                        + "未申请时自动使用系统生成的胶囊。",
                    "Android 16 or later on stock Android, HyperOS 3.0.300+ / 4, ColorOS 16, "
                        + "OriginOS 6, and vendors that follow the same API later.\n\n"
                        + "Older systems and HarmonyOS show a normal notification instead of the island; "
                        + "that is a system capability gap, not a broken switch.\n\n"
                        + "Xiaomi's official large island style needs a vendor allowlist. This app does not "
                        + "depend on it and falls back to the system capsule.")));

    ui.footer.setVisibility(View.GONE);
    return ui.root;
  }

  /** 状态行：与 {@link CardPage#kv} 同一套排版（左 12sp 标签 / 右 13sp 值），值按状态取 good / warn / bad。 */
  private void statusRow(CardPage ui, LinearLayout card, String label, String value, int color) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(44));
    TextView name = ui.text(label, 12, R.color.shell_text_secondary);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView current = ui.text(value, 13, color);
    current.setGravity(android.view.Gravity.END);
    row.addView(current);
    card.addView(row);
  }

  /** 状态色只用 shell_good / shell_warn / shell_bad，未知退回次要文字色。 */
  private static int toneColor(int state) {
    return switch (state) {
      case IslandSupport.STATE_ALLOWED -> R.color.shell_good;
      case IslandSupport.STATE_DENIED -> R.color.shell_warn;
      case IslandSupport.STATE_UNSUPPORTED -> R.color.shell_bad;
      default -> R.color.shell_text_muted;
    };
  }

  /** 当前值：与「备份时机 / 每天指定时间」同一种写法。 */
  private static String statusValue(int state) {
    String api = "API " + Build.VERSION.SDK_INT;
    return switch (state) {
      case IslandSupport.STATE_ALLOWED ->
          String.format(java.util.Locale.ROOT, UiText.choose("已允许 · %s", "Allowed · %s"), api);
      case IslandSupport.STATE_DENIED ->
          String.format(
              java.util.Locale.ROOT, UiText.choose("系统支持但未允许 · %s", "Supported, not allowed · %s"), api);
      case IslandSupport.STATE_UNSUPPORTED ->
          String.format(
              java.util.Locale.ROOT, UiText.choose("不支持 · %s", "Not supported · %s"), api);
      default ->
          String.format(java.util.Locale.ROOT, UiText.choose("无法确认 · %s", "Unknown · %s"), api);
    };
  }

  private void openSystemSettings() {
    try {
      startActivity(IslandSupport.settingsIntent(requireContext()));
    } catch (Exception unavailable) {
      // 系统没有对应设置页时停在本页，不阻塞用户。
    }
  }

  /** 开关行：与子页一致，只放标签，说明单独一行 12sp。 */
  private MaterialSwitch switchRow(
      LinearLayout parent,
      String label,
      boolean checked,
      java.util.function.Consumer<Boolean> onChange) {
    MaterialSwitch view = new MaterialSwitch(requireContext());
    view.setText(label);
    view.setTextSize(14);
    view.setTextColor(requireContext().getColor(R.color.shell_text));
    view.setMinHeight(dp(48));
    view.setMinimumHeight(dp(48));
    view.setChecked(checked);
    view.setOnCheckedChangeListener((button, value) -> onChange.accept(value));
    parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    return view;
  }

  private void hint(LinearLayout parent, String text) {
    TextView view = new TextView(requireContext());
    view.setText(text);
    view.setTextSize(12);
    view.setTextColor(requireContext().getColor(R.color.shell_text_muted));
    view.setLineSpacing(dp(2), 1f);
    view.setPadding(0, dp(2), 0, dp(10));
    parent.addView(view);
  }

  private View divider() {
    View line = new View(requireContext());
    line.setBackgroundColor(requireContext().getColor(R.color.shell_line));
    line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
    return line;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
