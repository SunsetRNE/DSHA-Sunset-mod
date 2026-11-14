package com.deepseekharness.app.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.saf.SafGrants;
import com.deepseekharness.app.saf.SafTree;
import com.deepseekharness.app.saf.SafWorkbench;
import com.deepseekharness.app.transfer.TransferArtifact;
import com.deepseekharness.app.util.UiText;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 授权目录浏览与工作台；每次操作都重新解析授权短 id，不跨页面持有 SafTree。 */
public final class SafBrowseFragment extends Fragment {
  private static final String ARG_ID = "saf_id";
  private static final String ARG_TITLE = "saf_title";
  private static final String SAVED_PATH = "saf_path";
  private static final int PREVIEW_LIMIT = 64 * 1024;
  private String treeId;
  private String current = "";
  private final List<SafTree.Entry> entries = new ArrayList<>();
  private BaseAdapter adapter;
  private TextView pathView, statusView;
  private Button upButton, exportButton;
  private ListView list;
  private ExecutorService executor;
  private SafWorkbenchModel model;
  private AlertDialog operationDialog;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private long loadGeneration, previewGeneration, operationGeneration;

  public static SafBrowseFragment forGrant(String id, String title) {
    SafBrowseFragment fragment = new SafBrowseFragment();
    Bundle args = new Bundle();
    args.putString(ARG_ID, id);
    args.putString(ARG_TITLE, title);
    fragment.setArguments(args);
    return fragment;
  }

  /** 兼容已有宿主调用，只把短 id 交给页面，不把 URI 作为操作凭据。 */
  public static SafBrowseFragment forTree(Uri tree, String title) {
    return forGrant(SafGrants.id(tree), title);
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    View root = inflater.inflate(R.layout.fragment_saf_browse, container, false);
    Context ctx = requireContext();
    model = new ViewModelProvider(this).get(SafWorkbenchModel.class);
    executor = Executors.newSingleThreadExecutor();
    Bundle args = requireArguments();
    treeId = args.getString(ARG_ID, "");
    // 覆盖更新可能恢复旧页面参数；仍须 lookup，原 URI 本身不能授予访问权。
    if (treeId.isEmpty()) treeId = SafGrants.id(Uri.parse(args.getString("saf_tree", "")));
    current = saved == null ? "" : saved.getString(SAVED_PATH, "");
    pathView = root.findViewById(R.id.saf_browse_path);
    statusView = root.findViewById(R.id.saf_browse_status);
    upButton = root.findViewById(R.id.saf_browse_up);
    upButton.setText(UiText.choose("上一级", "Up"));
    upButton.setOnClickListener(button -> load(parentOf(current)));
    ((Button) root.findViewById(R.id.saf_browse_refresh)).setText(UiText.choose("刷新", "Refresh"));
    root.findViewById(R.id.saf_browse_refresh).setOnClickListener(button -> load(current));
    exportButton = root.findViewById(R.id.saf_browse_export);
    exportButton.setText(UiText.choose("导出到此目录", "Export here"));
    exportButton.setOnClickListener(button -> prepareTransfer(null));
    ((Button) root.findViewById(R.id.saf_browse_history))
        .setText(UiText.choose("传输记录", "Transfer history"));
    root.findViewById(R.id.saf_browse_history)
        .setOnClickListener(button -> SafWorkbenchUi.history(requireActivity()));
    PressFeedback.attachButtons(root);
    list = root.findViewById(R.id.saf_browse_list);
    adapter =
        new BaseAdapter() {
          @Override
          public int getCount() {
            return entries.size();
          }

          @Override
          public SafTree.Entry getItem(int position) {
            return entries.get(position);
          }

          @Override
          public long getItemId(int position) {
            return position;
          }

          @Override
          public View getView(int position, View recycled, ViewGroup parent) {
            View row =
                recycled != null
                    ? recycled
                    : LayoutInflater.from(ctx).inflate(R.layout.item_saf_document, parent, false);
            SafTree.Entry entry = getItem(position);
            ((TextView) row.findViewById(R.id.saf_doc_name)).setText(entry.name);
            ((TextView) row.findViewById(R.id.saf_doc_meta)).setText(meta(ctx, entry));
            ((ImageView) row.findViewById(R.id.saf_doc_icon))
                .setImageResource(
                    entry.directory ? R.drawable.ic_ui2_folder : R.drawable.ic_transfer_document);
            row.findViewById(R.id.saf_doc_chevron)
                .setVisibility(entry.directory ? View.VISIBLE : View.GONE);
            return row;
          }
        };
    list.setAdapter(adapter);
    list.setOnItemClickListener((parent, view, position, id) -> open(entries.get(position)));
    load(current);
    return root;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
    super.onViewCreated(view, saved);
    model.outcome.observe(
        getViewLifecycleOwner(),
        result -> {
          if (result == null) return;
          controls();
          if (result.running) statusView.setText(UiText.choose("正在传输文件…", "Transferring file…"));
          else if ("OK".equals(result.code))
            statusView.setText(
                UiText.format(
                    UiText.choose("已传输 %s。", "Transferred %s."),
                    Formatter.formatShortFileSize(requireContext(), result.bytes)));
          else if ("OK_HISTORY_UNAVAILABLE".equals(result.code))
            statusView.setText(
                UiText.choose(
                    "文件已传输，记录未能保存。",
                    "The file was transferred, but its record could not be saved."));
          else statusView.setText(SafWorkbenchUi.problem(result.code));
        });
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle saved) {
    saved.putString(SAVED_PATH, current);
    super.onSaveInstanceState(saved);
  }

  private void controls() {
    boolean busy = model.working.get();
    upButton.setEnabled(!busy && !current.isEmpty());
    exportButton.setVisibility(SafWorkbenchUi.enabled(requireContext()) ? View.VISIBLE : View.GONE);
    exportButton.setEnabled(!busy);
    list.setEnabled(!busy);
  }

  /** 捕获请求路径，不从后台线程读取会被下一次导航改变的 current。 */
  private void load(String path) {
    if (model.working.get()) return;
    final String requested = path == null ? "" : path;
    current = requested;
    long generation = ++loadGeneration;
    previewGeneration++;
    pathView.setText(
        requireArguments().getString(ARG_TITLE, "")
            + (requested.isEmpty() ? "" : " / " + requested));
    entries.clear();
    adapter.notifyDataSetChanged();
    controls();
    statusView.setText(UiText.choose("读取中…", "Loading…"));
    ExecutorService running = executor;
    if (running == null) return;
    running.execute(
        () -> {
          try {
            SafTree.Page page = model.workbench.list(treeId, requested);
            main.post(
                () -> {
                  if (generation != loadGeneration || getView() == null) return;
                  entries.addAll(page.entries);
                  adapter.notifyDataSetChanged();
                  if (!model.working.get())
                    statusView.setText(
                        page.truncated
                            ? UiText.choose(
                                "已显示前 500 项，请缩小目录范围。",
                                "Showing the first 500 items. Choose a smaller folder.")
                            : UiText.format(
                                UiText.choose(
                                    "共 %d 项；文本预览上限 64 KB。",
                                    "%d items; text previews are limited to 64 KB."),
                                entries.size()));
                });
          } catch (SafTree.Failure failure) {
            main.post(
                () -> {
                  if (generation == loadGeneration && getView() != null)
                    statusView.setText(SafWorkbenchUi.problem(failure.code));
                });
          }
        });
  }

  private String child(String name) {
    return current.isEmpty() ? name : current + "/" + name;
  }

  private void open(SafTree.Entry entry) {
    if (entry.directory) {
      load(child(entry.name));
      return;
    }
    if (!SafWorkbenchUi.enabled(requireContext())) {
      preview(entry);
      return;
    }
    new DshaDialogBuilder(requireContext())
        .setTitle(entry.name)
        .setItems(
            new String[] {
              UiText.choose("预览", "Preview"), UiText.choose("导入到工作区", "Import to workspace")
            },
            (dialog, which) -> {
              if (which == 0) preview(entry);
              else prepareTransfer(entry);
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  private void preview(SafTree.Entry entry) {
    String path = child(entry.name);
    long generation = ++previewGeneration;
    statusView.setText(UiText.choose("读取中…", "Loading…"));
    ExecutorService running = executor;
    if (running == null) return;
    running.execute(
        () -> {
          try {
            byte[] bytes = model.workbench.read(treeId, path, PREVIEW_LIMIT + 1);
            main.post(
                () -> {
                  if (generation != previewGeneration || getView() == null) return;
                  boolean truncated = bytes.length > PREVIEW_LIMIT;
                  byte[] data = truncated ? java.util.Arrays.copyOf(bytes, PREVIEW_LIMIT) : bytes;
                  if (!SafTree.looksText(data)) {
                    statusView.setText(
                        UiText.choose(
                            "这是二进制文件，页面不预览；助手可以用 /app/saf/pull 取走。",
                            "Binary file: not previewed here. The assistant can take it with /app/saf/pull."));
                    return;
                  }
                  String text = new String(data, StandardCharsets.UTF_8);
                  if (truncated)
                    text =
                        UiText.format(
                            UiText.choose("%s\n\n（已截断到 64 KB）", "%s\n\n(truncated at 64 KB)"),
                            text);
                  new DshaDialogBuilder(requireContext())
                      .setTitle(entry.name)
                      .setMessage(text)
                      .setPositiveButton(UiText.choose("关闭", "Close"), null)
                      .show();
                });
          } catch (SafTree.Failure failure) {
            main.post(
                () -> {
                  if (generation == previewGeneration && getView() != null)
                    statusView.setText(SafWorkbenchUi.problem(failure.code));
                });
          }
        });
  }

  /** 面板打开时签发选择能力，超过 TTL、重授权或撤销后不能用旧面板继续传输。 */
  private void prepareTransfer(@Nullable SafTree.Entry entry) {
    if (model.working.get() || !SafWorkbenchUi.enabled(requireContext())) return;
    boolean importing = entry != null;
    String selectedPath = importing ? child(entry.name) : current;
    long generation = ++operationGeneration;
    ExecutorService running = executor;
    if (running == null) return;
    running.execute(
        () -> {
          try {
            SafWorkbench.Ticket ticket = model.workbench.prepare(treeId, selectedPath, importing);
            main.post(
                () -> {
                  if (generation != operationGeneration || getView() == null) {
                    model.workbench.cancel(ticket);
                    return;
                  }
                  transferForm(ticket, importing, importing ? entry.name : "");
                });
          } catch (SafTree.Failure failure) {
            main.post(
                () -> {
                  if (generation == operationGeneration && getView() != null)
                    statusView.setText(SafWorkbenchUi.problem(failure.code));
                });
          }
        });
  }

  private void transferForm(SafWorkbench.Ticket ticket, boolean importing, String name) {
    Context ctx = requireContext();
    if (operationDialog != null) operationDialog.dismiss();
    LinearLayout form = new LinearLayout(ctx);
    form.setOrientation(LinearLayout.VERTICAL);
    int pad = Math.round(16 * getResources().getDisplayMetrics().density);
    form.setPadding(pad, pad, pad, pad);
    TextView note = new TextView(ctx);
    note.setText(
        UiText.choose(
            "最多 32 MiB。同名目标会被替换；导出到外部应用失败时可能留下不完整文件。",
            "Up to 32 MiB. An existing destination is replaced; an export failure may leave an incomplete external file."));
    form.addView(note);
    EditText local =
        input(
            form,
            importing
                ? UiText.choose("工作区目标文件", "Workspace destination file")
                : UiText.choose("工作区源文件", "Workspace source file"));
    local.setHint("/root/report.md");
    if (importing) {
      String directory = new ConfigStore(ctx).getWorkdir();
      local.setText(directory + (directory.endsWith("/") ? "" : "/") + name);
    }
    EditText targetName =
        importing ? null : input(form, UiText.choose("目标文件名", "Destination file name"));
    if (targetName != null) targetName.setHint("report.md");
    ScrollView scroll = new ScrollView(ctx);
    scroll.addView(form);
    boolean[] dispatched = {false};
    operationDialog =
        new DshaDialogBuilder(ctx)
            .setTitle(
                importing
                    ? UiText.choose("导入到工作区", "Import to workspace")
                    : UiText.choose("导出到授权目录", "Export to granted folder"))
            .setView(scroll)
            .setPositiveButton(
                UiText.choose("开始传输", "Start transfer"),
                (dialog, which) -> {
                  String remote = ticket.path;
                  if (!importing) {
                    String filename = targetName.getText().toString().trim();
                    if (!TransferArtifact.safeName(filename)) {
                      statusView.setText(SafWorkbenchUi.problem("BAD_NAME"));
                      return;
                    }
                    remote = ticket.path.isEmpty() ? filename : ticket.path + "/" + filename;
                  }
                  dispatched[0] = true;
                  model.transfer(ticket, remote, local.getText().toString(), importing);
                })
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .show();
    operationDialog.setOnDismissListener(
        dialog -> {
          if (!dispatched[0]) model.workbench.cancel(ticket);
        });
  }

  private EditText input(LinearLayout form, String title) {
    TextView label = new TextView(requireContext());
    label.setText(title);
    EditText field = new EditText(requireContext());
    field.setId(View.generateViewId());
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    label.setLabelFor(field.getId());
    form.addView(label);
    form.addView(field, new LinearLayout.LayoutParams(-1, -2));
    return field;
  }

  private String meta(Context ctx, SafTree.Entry entry) {
    if (entry.directory) return UiText.choose("文件夹", "Folder");
    StringBuilder out = new StringBuilder();
    if (entry.size >= 0) out.append(Formatter.formatShortFileSize(ctx, entry.size)).append("  ");
    if (entry.modified > 0)
      out.append(
          new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
              .format(new Date(entry.modified)));
    return out.toString().trim();
  }

  private static String parentOf(String path) {
    int cut = path == null ? -1 : path.lastIndexOf('/');
    return cut <= 0 ? "" : path.substring(0, cut);
  }

  @Override
  public void onDestroyView() {
    loadGeneration++;
    previewGeneration++;
    operationGeneration++;
    if (operationDialog != null) operationDialog.dismiss();
    operationDialog = null;
    ExecutorService running = executor;
    executor = null;
    if (running != null) running.shutdownNow();
    pathView = null;
    statusView = null;
    upButton = null;
    exportButton = null;
    list = null;
    adapter = null;
    entries.clear();
    super.onDestroyView();
  }
}
