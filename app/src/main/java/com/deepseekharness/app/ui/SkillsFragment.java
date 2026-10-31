package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.skills.SkillImports;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.SkillDocument;
import com.deepseekharness.app.util.UiText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用户全局技能（设置子页，方案 A）。使用官方 rc2 全局技能目录，不执行导入正文。
 *
 * <p>布局按本页实际内容宽度选择，不按设备类型：
 *
 * <ul>
 *   <li>≥ {@link #SPLIT_DP}dp（平板横屏的设置详情窗格、手机横屏、平板竖屏）：左列表、右详情；
 *   <li>更窄（手机竖屏、分屏小窗）：单列列表，点行以底部弹层查看详情。
 * </ul>
 */
public final class SkillsFragment extends Fragment {
  /** 列表 + 详情并排所需的最小内容宽度。 */
  static final int SPLIT_DP = 600;

  private static final String STATE_SELECTED = "skills.selected";

  static final class Result {
    final String code, name, path;

    Result(String code, String name, String path) {
      this.code = code;
      this.name = name;
      this.path = path;
    }
  }

  public static final class Model extends ViewModel {
    final MutableLiveData<SkillImports.Catalogue> catalogue = new MutableLiveData<>();
    final MutableLiveData<Result> result = new MutableLiveData<>(new Result("READY", "", ""));
    final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    final AtomicBoolean working = new AtomicBoolean();
    BackupControl control;
    boolean committing;

    synchronized void cancel() {
      if (control != null && !committing) control.cancel();
    }

    @Override
    protected void onCleared() {
      cancel();
    }
  }

  private Model model;
  private CardPage ui;
  private LinearLayout split, listColumn, detailColumn, progressRow;
  private TextView status, progressText;
  private Button importButton, refreshButton, cancelButton;
  private ShellActivityTrack progressTrack;
  @Nullable private String selected;
  private boolean wide;
  private boolean autoLoad = true;

  private final ActivityResultLauncher<String[]> picker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) start(uri);
          });

  private static String t(String chinese, String english) {
    return UiText.choose(chinese, english);
  }

  /** 仅测试用：跳过自动读取目录，由测试直接渲染固定目录。 */
  void disableAutoLoadForTest() {
    autoLoad = false;
  }

  /** 仅测试用：直接提供目录。 */
  void showCatalogueForTest(SkillImports.Catalogue catalogue) {
    model.catalogue.setValue(catalogue);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    model = new ViewModelProvider(this).get(Model.class);
    if (saved != null) selected = saved.getString(STATE_SELECTED);
    Context ctx = requireContext();
    ui = new CardPage(ctx, t("技能", "Skills"), "");
    // 页首一句话，导入规则与存放位置收进 ⓘ。
    TextView hint = ui.text("", 13, R.color.shell_text_secondary);
    hint.setPadding(0, 0, 0, ui.dp(14));
    ui.content.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    InfoHint.bind(
        hint,
        t("技能", "Skills"),
        UiText.format(
            "导入 SKILL.md，让 DSH 按需读取可复用的任务说明。\n\n这里管理用户全局技能，无需 Root。导入仅复制 SKILL.md，正文中的命令不会自动执行；引用的脚本和资料需用文件入口另行放入同一技能目录。\n\n存放位置：%s",
            SkillImports.GUEST_ROOT));

    importButton = new androidx.appcompat.widget.AppCompatButton(ctx);
    importButton.setText(t("导入 SKILL.md", "Import SKILL.md"));
    styleButton(importButton, true);
    importButton.setOnClickListener(v -> picker.launch(new String[] {"*/*"}));
    ui.content.addView(importButton, new LinearLayout.LayoutParams(-1, -2));

    // 进度行：只在读取 / 导入时出现，带取消。
    progressRow = new LinearLayout(ctx);
    progressRow.setOrientation(LinearLayout.VERTICAL);
    progressRow.setVisibility(View.GONE);
    LinearLayout progressHead = new LinearLayout(ctx);
    progressHead.setGravity(Gravity.CENTER_VERTICAL);
    progressText = ui.text("", 13, R.color.shell_text_secondary);
    progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    progressHead.addView(progressText, new LinearLayout.LayoutParams(0, -2, 1));
    cancelButton = textButton(ctx, t("取消", "Cancel"));
    cancelButton.setOnClickListener(v -> model.cancel());
    progressHead.addView(cancelButton);
    progressRow.addView(progressHead);
    progressTrack = new ShellActivityTrack(ctx);
    progressTrack.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    progressRow.addView(progressTrack, new LinearLayout.LayoutParams(-1, ui.dp(16)));
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, -2);
    progressParams.topMargin = ui.dp(10);
    ui.content.addView(progressRow, progressParams);

    // 结果条：成功 / 出错时出现，点 ⓘ 看完整说明。
    status = ui.text("", 13, R.color.shell_text);
    status.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
    status.setTextIsSelectable(false);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    status.setVisibility(View.GONE);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.topMargin = ui.dp(10);
    ui.content.addView(status, statusParams);

    // 列表标题行：「已安装 · N」+ 刷新。
    LinearLayout listHead = new LinearLayout(ctx);
    listHead.setGravity(Gravity.CENTER_VERTICAL);
    listHead.setTag("skills.head");
    TextView headTitle = ui.text("", 12, R.color.shell_text_secondary);
    headTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    headTitle.setTag("skills.count");
    listHead.addView(headTitle, new LinearLayout.LayoutParams(0, -2, 1));
    refreshButton = textButton(ctx, t("刷新", "Refresh"));
    refreshButton.setOnClickListener(v -> start(null));
    listHead.addView(refreshButton);
    LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(-1, -2);
    headParams.topMargin = ui.dp(18);
    ui.content.addView(listHead, headParams);

    split = new LinearLayout(ctx);
    split.setOrientation(LinearLayout.HORIZONTAL);
    listColumn = ui.column();
    detailColumn = ui.column();
    split.addView(listColumn, new LinearLayout.LayoutParams(0, -2, 1f));
    LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, -2, 1f);
    detailParams.setMarginStart(ui.dp(14));
    split.addView(detailColumn, detailParams);
    ui.content.addView(split, new LinearLayout.LayoutParams(-1, -2));
    ui.footer.setVisibility(View.GONE);

    // 按本页实际宽度切换单列 / 并排；只在跨越阈值时重排，避免布局循环。
    ui.root.addOnLayoutChangeListener(
        (v, l, top, r, b, ol, ot, oR, ob) -> {
          boolean next = (r - l) >= ui.dp(SPLIT_DP);
          if (next != wide) {
            wide = next;
            v.post(() -> render(model.catalogue.getValue()));
          }
        });

    model.catalogue.observe(getViewLifecycleOwner(), this::render);
    model.result.observe(getViewLifecycleOwner(), this::renderResult);
    model.busy.observe(getViewLifecycleOwner(), busy -> renderBusy(Boolean.TRUE.equals(busy)));
    if (autoLoad && model.catalogue.getValue() == null && !model.working.get()) start(null);
    return ui.root;
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (selected != null) out.putString(STATE_SELECTED, selected);
  }

  private Button textButton(Context ctx, String label) {
    Button button = new androidx.appcompat.widget.AppCompatButton(ctx);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(13);
    button.setTextColor(ctx.getColor(R.color.shell_accent));
    button.setBackgroundResource(R.drawable.bg_action_plain);
    button.setMinHeight(ui.dp(44));
    button.setMinimumHeight(ui.dp(44));
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(ui.dp(12), 0, ui.dp(12), 0);
    return button;
  }

  private void styleButton(Button button, boolean primary) {
    Context ctx = requireContext();
    button.setAllCaps(false);
    button.setTextSize(14);
    button.setTypeface(null, android.graphics.Typeface.BOLD);
    button.setIncludeFontPadding(false);
    button.setMinHeight(ui.dp(primary ? 50 : 44));
    button.setMinimumHeight(ui.dp(primary ? 50 : 44));
    button.setPadding(ui.dp(22), 0, ui.dp(22), 0);
    button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn);
    button.setTextColor(
        ctx.getColorStateList(primary ? R.color.button_primary_text : R.color.button_text));
  }

  private void renderBusy(boolean active) {
    if (importButton == null) return;
    importButton.setEnabled(!active);
    refreshButton.setEnabled(!active);
    cancelButton.setEnabled(active && !model.committing);
    progressRow.setVisibility(active ? View.VISIBLE : View.GONE);
    progressTrack.setState(active ? ShellActivityTrack.RUNNING : ShellActivityTrack.IDLE);
  }

  /** 渲染目录：标题计数、列表行、格式未通过提示；并排时右侧显示选中技能详情。 */
  void render(@Nullable SkillImports.Catalogue catalogue) {
    if (listColumn == null || getContext() == null) return;
    Context ctx = requireContext();
    listColumn.removeAllViews();
    detailColumn.removeAllViews();
    // 窄屏主按钮铺满；并排时按内容宽度靠起始侧。
    LinearLayout.LayoutParams importParams =
        (LinearLayout.LayoutParams) importButton.getLayoutParams();
    importParams.width = wide ? ViewGroup.LayoutParams.WRAP_CONTENT : -1;
    importParams.gravity = Gravity.START;
    importButton.setLayoutParams(importParams);
    View head = ui.content.findViewWithTag("skills.head");
    TextView count = ui.content.findViewWithTag("skills.count");
    if (catalogue == null) {
      head.setVisibility(View.GONE);
      detailColumn.setVisibility(View.GONE);
      return;
    }
    head.setVisibility(View.VISIBLE);
    count.setText(UiText.format("已安装 · %s", String.valueOf(catalogue.entries.size())));
    if (catalogue.unreadable > 0) {
      TextView notice = ui.text("", 12, R.color.shell_warn);
      notice.setBackgroundResource(R.drawable.bg_shell_status);
      notice.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
      notice.setText(UiText.format("另有 %s 个文件格式不符，原件已保留。", String.valueOf(catalogue.unreadable)));
      LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(-1, -2);
      noticeParams.bottomMargin = ui.dp(10);
      listColumn.addView(notice, noticeParams);
    }
    if (catalogue.entries.isEmpty()) {
      selected = null;
      detailColumn.setVisibility(View.GONE);
      listColumn.addView(emptyState(ctx), new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    SkillImports.Entry current = null;
    for (SkillImports.Entry entry : catalogue.entries)
      if (entry.document.name.equals(selected)) current = entry;
    if (wide && current == null) {
      current = catalogue.entries.get(0);
      selected = current.document.name;
    }
    LinearLayout group = ui.column();
    group.setBackgroundResource(R.drawable.bg_shell_card);
    group.setPadding(0, ui.dp(4), 0, ui.dp(4));
    for (int i = 0; i < catalogue.entries.size(); i++) {
      SkillImports.Entry entry = catalogue.entries.get(i);
      if (i > 0) {
        View line = new View(ctx);
        line.setBackgroundColor(ctx.getColor(R.color.shell_line));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, 1);
        lineParams.setMarginStart(ui.dp(66));
        group.addView(line, lineParams);
      }
      boolean isSelected = wide && entry == current;
      View row = row(ctx, entry, isSelected);
      row.setOnClickListener(
          v -> {
            if (wide) {
              selected = entry.document.name;
              render(model.catalogue.getValue());
            } else showSheet(entry);
          });
      group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    listColumn.addView(group, new LinearLayout.LayoutParams(-1, -2));
    detailColumn.setVisibility(wide ? View.VISIBLE : View.GONE);
    if (wide && current != null) {
      LinearLayout card = ui.column();
      card.setTag("skills.detail");
      card.setBackgroundResource(R.drawable.bg_shell_card);
      card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16));
      detail(ctx, card, current);
      detailColumn.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }
  }

  private View emptyState(Context ctx) {
    LinearLayout empty = ui.column();
    empty.setGravity(Gravity.CENTER_HORIZONTAL);
    empty.setBackgroundResource(R.drawable.bg_shell_card);
    empty.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(20));
    empty.addView(iconTile(ctx, 52, 26), new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
    TextView title = ui.text(t("还没有技能", "No skills yet"), 16, R.color.shell_text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, ui.dp(14), 0, ui.dp(6));
    empty.addView(title);
    TextView sub =
        ui.text(
            t(
                "导入 SKILL.md 后会出现在这里，DSH 会按需读取。",
                "Imported SKILL.md files appear here; DSH loads them as needed."),
            13,
            R.color.shell_text_secondary);
    sub.setGravity(Gravity.CENTER);
    sub.setLineSpacing(ui.dp(3), 1f);
    empty.addView(sub);
    Button format = textButton(ctx, t("格式要求", "Format requirements"));
    format.setOnClickListener(
        v ->
            InfoHint.show(
                ctx,
                t("格式要求", "Format requirements"),
                t(
                    "· 文件名必须是 SKILL.md，UTF-8 文本，不超过 1 MiB。\n· 以 --- 开头的 YAML 头，包含小写短横线 name 和非空 description。\n· 可选 disable-model-invocation / user-invocable 控制调用方式。\n· 只复制 SKILL.md，不执行其中命令；同名技能不会被覆盖。",
                    "· The file must be named SKILL.md, UTF-8 text, at most 1 MiB.\n· Start with --- YAML frontmatter containing a lowercase kebab-case name and a non-empty description.\n· Optional disable-model-invocation / user-invocable control how it is invoked.\n· Only SKILL.md is copied and its commands never run; a skill with the same name is not overwritten.")));
    LinearLayout.LayoutParams formatParams = new LinearLayout.LayoutParams(-2, -2);
    formatParams.topMargin = ui.dp(6);
    empty.addView(format, formatParams);
    return empty;
  }

  private View iconTile(Context ctx, int sizeDp, int iconDp) {
    FrameLayout tile = new FrameLayout(ctx);
    tile.setBackgroundResource(R.drawable.bg_launch_tile);
    tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    ImageView icon = new ImageView(ctx);
    icon.setImageResource(R.drawable.ic_ui_tasks);
    icon.setColorFilter(ctx.getColor(R.color.shell_accent));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp));
    params.gravity = Gravity.CENTER;
    tile.addView(icon, params);
    return tile;
  }

  /** 列表行：图标块 + 名称 + 两行描述 + 调用标签；窄屏带箭头，并排时选中项高亮。 */
  private View row(Context ctx, SkillImports.Entry entry, boolean isSelected) {
    LinearLayout row = new LinearLayout(ctx);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ui.dp(68));
    row.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
    row.setClickable(true);
    row.setFocusable(true);
    row.setTag("skill:" + entry.document.name);
    row.setBackgroundResource(isSelected ? R.drawable.bg_settings_row : 0);
    row.setSelected(isSelected);
    row.setForeground(ctx.getDrawable(R.drawable.bg_action_plain));
    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
    iconParams.setMarginEnd(ui.dp(14));
    row.addView(iconTile(ctx, 40, 20), iconParams);
    LinearLayout copy = ui.column();
    TextView name = ui.text(entry.document.name, 15, R.color.shell_text);
    name.setTypeface(null, android.graphics.Typeface.BOLD);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    copy.addView(name);
    TextView description = ui.text(entry.document.description, 12, R.color.shell_text_secondary);
    description.setMaxLines(2);
    description.setEllipsize(android.text.TextUtils.TruncateAt.END);
    description.setLineSpacing(ui.dp(2), 1f);
    description.setPadding(0, ui.dp(4), 0, 0);
    copy.addView(description);
    View badges = badges(ctx, entry);
    if (badges != null) {
      LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
      badgeParams.topMargin = ui.dp(6);
      copy.addView(badges, badgeParams);
    }
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
    if (!wide) {
      ImageView chevron = new ImageView(ctx);
      chevron.setImageResource(R.drawable.ic_ui2_chevron);
      chevron.setColorFilter(ctx.getColor(R.color.shell_text_muted));
      chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      // RTL 下箭头随阅读方向。
      if (chevron.getDrawable() != null) chevron.getDrawable().setAutoMirrored(true);
      row.addView(chevron, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
    }
    row.setContentDescription(UiText.raw(entry.document.name + ", " + entry.document.description));
    return row;
  }

  @Nullable
  private View badges(Context ctx, SkillImports.Entry entry) {
    if (!entry.document.userInvocable && !entry.document.modelInvocable) return null;
    LinearLayout line = new LinearLayout(ctx);
    line.setGravity(Gravity.CENTER_VERTICAL);
    if (entry.document.userInvocable) line.addView(pill(ctx, "/" + entry.document.name, true));
    if (entry.document.modelInvocable) {
      View pill = pill(ctx, t("模型可调用", "Model can invoke"), false);
      if (line.getChildCount() > 0) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMarginStart(ui.dp(6));
        line.addView(pill, p);
      } else line.addView(pill);
    }
    return line;
  }

  private TextView pill(Context ctx, String label, boolean accent) {
    TextView pill =
        ui.text(label, 11, accent ? R.color.shell_accent : R.color.shell_text_secondary);
    pill.setSingleLine(true);
    pill.setEllipsize(android.text.TextUtils.TruncateAt.END);
    pill.setMaxWidth(ui.dp(180));
    pill.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(ui.dp(999));
    shape.setColor(
        accent ? ctx.getColor(R.color.shell_accent_soft) : ctx.getColor(R.color.shell_line));
    pill.setBackground(shape);
    return pill;
  }

  /** 详情内容（并排时右侧卡片与窄屏底部弹层共用）。 */
  private void detail(Context ctx, LinearLayout body, SkillImports.Entry entry) {
    TextView name = ui.text(entry.document.name, 18, R.color.shell_text);
    name.setTypeface(null, android.graphics.Typeface.BOLD);
    body.addView(name);
    TextView description = ui.text(entry.document.description, 13, R.color.shell_text_secondary);
    description.setLineSpacing(ui.dp(3), 1f);
    description.setPadding(0, ui.dp(6), 0, ui.dp(10));
    body.addView(description);
    TextView availability =
        ui.text(
            entry.document.modelInvocable
                ? t("模型可按需调用 skill 工具读取。", "The model can load this with the skill tool as needed.")
                : t("此技能禁止模型自主调用。", "This skill disables autonomous model invocation."),
            12,
            R.color.shell_text_muted);
    body.addView(availability);
    if (entry.document.userInvocable) {
      LinearLayout invoke = new LinearLayout(ctx);
      invoke.setGravity(Gravity.CENTER_VERTICAL);
      invoke.setBackgroundResource(R.drawable.bg_shell_status);
      invoke.setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4));
      TextView command = ui.text("/" + entry.document.name, 14, R.color.shell_text);
      command.setTypeface(android.graphics.Typeface.MONOSPACE);
      command.setSingleLine(true);
      command.setEllipsize(android.text.TextUtils.TruncateAt.END);
      invoke.addView(command, new LinearLayout.LayoutParams(0, -2, 1f));
      Button copy = textButton(ctx, t("复制调用命令", "Copy invocation"));
      copy.setOnClickListener(
          v -> {
            ClipboardManager clipboard =
                (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
              clipboard.setPrimaryClip(ClipData.newPlainText("skill", "/" + entry.document.name));
              Toast.makeText(ctx, UiText.text("已复制"), Toast.LENGTH_SHORT).show();
            }
          });
      invoke.addView(copy);
      LinearLayout.LayoutParams invokeParams = new LinearLayout.LayoutParams(-1, -2);
      invokeParams.topMargin = ui.dp(10);
      body.addView(invoke, invokeParams);
    } else {
      TextView none =
          ui.text(
              t("此技能禁止用户直接调用。", "This skill disables direct user invocation."),
              12,
              R.color.shell_text_muted);
      none.setPadding(0, ui.dp(4), 0, 0);
      body.addView(none);
    }
    TextView path = ui.text(entry.guestPath(), 11, R.color.shell_text_muted);
    path.setTypeface(android.graphics.Typeface.MONOSPACE);
    path.setTextIsSelectable(true);
    path.setPadding(0, ui.dp(12), 0, ui.dp(6));
    body.addView(path);
    TextView source = ui.text(entry.document.text, 12, R.color.shell_text);
    source.setTypeface(android.graphics.Typeface.MONOSPACE);
    source.setTextIsSelectable(true);
    source.setLineSpacing(ui.dp(3), 1f);
    source.setBackgroundResource(R.drawable.bg_shell_status);
    source.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
    source.setTextDirection(View.TEXT_DIRECTION_LTR);
    body.addView(source, new LinearLayout.LayoutParams(-1, -2));
  }

  private void showSheet(SkillImports.Entry entry) {
    Context ctx = requireContext();
    CardPage page = new CardPage(ctx, "", "", true);
    detail(ctx, page.content, entry);
    var dialog = CardSheet.create(ctx, page);
    page.button(page.footer, t("关闭", "Close"), false, dialog::dismiss);
    CardSheet.show(dialog, ctx);
  }

  private void renderResult(Result result) {
    if (status == null) return;
    String code = result.code;
    if ("READY".equals(code)) {
      status.setVisibility(View.GONE);
      progressText.setText("");
      return;
    }
    if ("READING".equals(code) || "IMPORTING".equals(code)) {
      progressText.setText(
          "READING".equals(code)
              ? t("正在读取用户技能…", "Reading user skills…")
              : t("正在读取并检查 SKILL.md…", "Reading and checking SKILL.md…"));
      status.setVisibility(View.GONE);
      return;
    }
    boolean ok = "IMPORTED".equals(code);
    boolean cancelled = "CANCELLED".equals(code);
    int tone =
        ok ? R.color.shell_good : cancelled ? R.color.shell_text_secondary : R.color.shell_warn;
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(ui.dp(12));
    int color = requireContext().getColor(tone);
    shape.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x1F));
    shape.setStroke(ui.dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x66));
    status.setTextColor(
        requireContext().getColor(cancelled ? R.color.shell_text_secondary : R.color.shell_text));
    InfoHint.bind(status, t("技能", "Skills"), resultText(result));
    status.setBackground(shape);
    // InfoHint 会把整宽改成自适应；结果条保持整宽。
    ViewGroup.LayoutParams statusParams = status.getLayoutParams();
    statusParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
    status.setLayoutParams(statusParams);
    status.setVisibility(View.VISIBLE);
  }

  private void start(@Nullable Uri uri) {
    if (!model.working.compareAndSet(false, true)) return;
    if (uri != null && !"content".equals(uri.getScheme())) {
      model.working.set(false);
      model.result.setValue(new Result("SKILL_URI", "", ""));
      return;
    }
    Context app = requireContext().getApplicationContext();
    Model owner = model;
    BackupControl control = new BackupControl(null);
    synchronized (owner) {
      owner.control = control;
      owner.committing = false;
    }
    owner.busy.setValue(true);
    owner.result.setValue(new Result(uri == null ? "READING" : "IMPORTING", "", ""));
    new Thread(
            () -> {
              try {
                if (uri == null) {
                  try (RuntimeTasks task = RuntimeTasks.begin("技能读取")) {
                    owner.catalogue.postValue(repository(app).list(control));
                  }
                  owner.result.postValue(new Result("READY", "", ""));
                } else {
                  byte[] bytes = read(app, uri, control);
                  SkillDocument.parse(bytes);
                  synchronized (owner) {
                    control.check();
                    owner.committing = true;
                  }
                  owner.busy.postValue(true);
                  EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("技能导入");
                  if (lease == null) throw new IOException("SKILL_BUSY");
                  SkillImports.Entry installed;
                  try (lease) {
                    installed =
                        lease.run(
                            () -> {
                              if (MaintenanceCoordinator.pending(app.getFilesDir()))
                                throw new IOException("SKILL_BUSY");
                              synchronized (MaintenanceCoordinator.archiveLock()) {
                                try (RuntimeTasks task = RuntimeTasks.begin("技能导入")) {
                                  return repository(app)
                                      .install("SKILL.md", bytes, new BackupControl(null));
                                }
                              }
                            });
                  }
                  owner.result.postValue(
                      new Result("IMPORTED", installed.document.name, installed.guestPath()));
                  try (RuntimeTasks task = RuntimeTasks.begin("技能读取")) {
                    owner.catalogue.postValue(repository(app).list(new BackupControl(null)));
                  } catch (IOException | RuntimeException refreshFailed) {
                    // 发布已完成；只读刷新失败不能把成功导入报告成未完成或重复执行导入。
                  }
                }
              } catch (Exception failure) {
                String code =
                    failure instanceof java.io.InterruptedIOException || control.isCancelled()
                        ? "CANCELLED"
                        : failure.getMessage();
                owner.result.postValue(new Result(code == null ? "SKILL_IO" : code, "", ""));
              } finally {
                synchronized (owner) {
                  owner.control = null;
                  owner.committing = false;
                }
                owner.working.set(false);
                owner.busy.postValue(false);
              }
            },
            "dsha-skills")
        .start();
  }

  private static SkillImports repository(Context context) throws IOException {
    return new SkillImports(
        new AndroidBackupFileSystem(), context.getFilesDir().getCanonicalFile());
  }

  private static byte[] read(Context context, Uri uri, BackupControl control) throws IOException {
    String filename;
    try (Cursor cursor =
        DocumentStreams.query(
            context.getContentResolver(),
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
            control)) {
      if (!cursor.moveToFirst()) throw new IOException("SKILL_FILENAME");
      int name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
      if (name < 0 || cursor.isNull(name)) throw new IOException("SKILL_FILENAME");
      filename = cursor.getString(name);
      SkillDocument.filename(filename);
      int size = cursor.getColumnIndex(OpenableColumns.SIZE);
      if (size >= 0 && !cursor.isNull(size) && cursor.getLong(size) > SkillDocument.MAX_BYTES)
        throw new IOException("SKILL_SIZE");
    }
    try (InputStream input = DocumentStreams.input(context.getContentResolver(), uri, control);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        control.check();
        if (output.size() + count > SkillDocument.MAX_BYTES) throw new IOException("SKILL_SIZE");
        output.write(buffer, 0, count);
      }
      control.check();
      return output.toByteArray();
    }
  }

  private String resultText(Result result) {
    return switch (result.code) {
      case "IMPORTED" ->
          UiText.format(
              "已导入技能：%s\n%s\nDSH 会在后续请求中发现此技能；若目录尚未刷新，可重启 Web。", result.name, result.path);
      case "CANCELLED" -> t("已取消读取，未导入技能。", "Reading cancelled. No skill was imported.");
      case "SKILL_FILENAME", "SKILL_URI" ->
          t(
              "请选择名为 SKILL.md 的文件，不能导入目录或压缩包。",
              "Choose a file named SKILL.md. Folders and archives cannot be imported.");
      case "SKILL_SIZE" ->
          t("SKILL.md 必须为非空文件，且不超过 1 MiB。", "SKILL.md must be non-empty and at most 1 MiB.");
      case "SKILL_ENCODING" ->
          t("SKILL.md 必须是有效 UTF-8 文本。", "SKILL.md must contain valid UTF-8 text.");
      case "SKILL_FRONTMATTER", "SKILL_REQUIRED_FIELDS", "SKILL_NAME", "SKILL_INVOCATION" ->
          t(
              "技能格式无效。文件需以 --- YAML 开头，包含小写短横线 name 和非空 description；调用开关使用 disable-model-invocation / user-invocable。未导入文件。",
              "Invalid skill format. Start with --- YAML frontmatter, include a lowercase kebab-case name and non-empty description, and use disable-model-invocation / user-invocable for invocation controls. No file was imported.");
      case "SKILL_EXISTS" ->
          t(
              "已有同名技能或目录，未覆盖原件。请先在源文件中使用不同的 name。",
              "A skill or folder with this name already exists. The original was retained. Use a different name in the source file first.");
      case "SKILL_BUSY" ->
          t(
              "有安装、备份、恢复或维护任务正在进行，请完成后重试。",
              "An install, backup, restore or maintenance operation is active. Retry after it finishes.");
      case "SKILL_HOME_UNAVAILABLE", "PARENT_LINK_OR_MISSING", "PARENT_LINK" ->
          t(
              "当前 DSH 用户目录不可安全读取，请先完成环境安装或修复。未创建替代目录。",
              "The current DSH user folder cannot be read safely. Complete environment setup or repair first. No replacement folder was created.");
      case "SKILL_DIRECTORY", "SKILL_ENTRY_LIMIT" ->
          t(
              "技能目录不是可安全读取的普通目录，或条目超过 512 项。原件已保留。",
              "The skills folder is not a safely readable regular directory, or it has more than 512 entries. Originals were retained.");
      default ->
          t(
              "技能操作未完成。请检查文件授权、可用空间和目录状态；已有技能未覆盖。",
              "The skill operation did not finish. Check file access, free space and the folder state. Existing skills were retained.");
    };
  }
}
