package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.BidiFormatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.RollbackStorageInventory;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.util.Fmt;
import com.deepseekharness.app.util.MaintenanceErrorText;
import com.deepseekharness.app.util.StorageCategoryLabels;
import com.deepseekharness.app.util.StorageUsage;
import com.deepseekharness.app.util.UiText;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StorageActivity extends AppCompatActivity {
  static final long PROGRESS_SAMPLE_MS = 160;
  private static final String STATE_JOB = "storageJob";
  private static final String STATE_ROLLBACK = "storageRollback";

  private CardPage page;
  private LinearLayout rows, usage;
  private TextView total, available;

  /** 分布条色板：主题 token（含夜间），不另造硬编码颜色。 */
  private static final int[] PALETTE = {
    R.color.shell_accent, R.color.shell_good, R.color.shell_warn,
    R.color.shell_bad, R.color.primary_dark, R.color.shell_text_muted
  };

  private StorageProgressCard progress;
  private RollbackStoragePanel rollback;
  private Button clean, refresh;
  private AlertDialog confirm;
  private boolean openingConfirmation;
  private StoragePageWork work = StoragePageWork.PROCESS;
  private StoragePageWork.Job job;
  private StoragePageWork.Frame shown;
  private StorageMaintenance.Inventory shownInventory;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable render = this::render;
  private long savedJob;
  private boolean restoreRollback;
  private boolean started;

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    String title = getString(R.string.ui2_storage_files);
    String subtitle = getString(R.string.storage_page_subtitle);
    page = new CardPage(this, title, subtitle);
    // 摘要进页首，完整说明点开 ⓘ（审计 D-06）。
    InfoHint.bind(page.subtitle, title, subtitle, getString(R.string.storage_scope));
    UiNavigation.addHeader(this, page.root, title, this::finish);
    progress = new StorageProgressCard(page);
    var files = page.card();
    page.kv(
        files,
        t("工作目录", "Workspace"),
        com.deepseekharness.app.core.HarnessController.get(this).config().getWorkdir());
    page.entry(
        files,
        t("文件共享", "File sharing"),
        getString(R.string.storage_file_access_hint),
        R.drawable.ic_ui2_box,
        () ->
            CardSheet.show(
                this, t("文件共享", "File sharing"), getString(R.string.storage_file_access_message)));
    rollback =
        new RollbackStoragePanel(
            page, this::toggleRollback, this::measureRollbackCopy, this::confirmExpansion);
    LinearLayout overview = page.card();
    overview.addView(
        page.text(getString(R.string.storage_logical), 12, R.color.shell_text_secondary));
    total = page.text("", 22, R.color.shell_text);
    total.setTypeface(null, android.graphics.Typeface.BOLD);
    total.setText(R.string.storage_unknown);
    total.setPadding(0, page.dp(6), 0, 0);
    overview.addView(total);
    available = page.text("", 13, R.color.shell_text_secondary);
    available.setText(getString(R.string.storage_available, getString(R.string.storage_unknown)));
    available.setPadding(0, page.dp(8), 0, 0);
    overview.addView(available);
    usage = new LinearLayout(this);
    usage.setOrientation(LinearLayout.HORIZONTAL);
    usage.setBackgroundResource(R.drawable.bg_data_status_tile);
    usage.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(getColor(R.color.shell_raised)));
    usage.setClipToOutline(true);
    LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, page.dp(14));
    bar.topMargin = page.dp(12);
    bar.bottomMargin = page.dp(6);
    overview.addView(usage, bar);
    usage.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    var whitelist = page.text("", 13, R.color.shell_text_secondary);
    whitelist.setId(R.id.storage_whitelist_summary);
    whitelist.setPadding(0, page.dp(6), 0, page.dp(4));
    whitelist.setVisibility(View.GONE);
    overview.addView(whitelist);
    rows = page.column();
    overview.addView(rows);
    clean =
        page.button(
            page.footer, getString(R.string.storage_clean_action), true, this::confirmCleanup);
    clean.setId(R.id.storage_clean_button);
    refresh =
        page.button(
            page.footer,
            getString(R.string.storage_refresh_action),
            false,
            () -> start(StoragePageWork.Kind.REFRESH, job));
    refresh.setId(R.id.storage_refresh_button);
    setContentView(page.root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    if (saved != null) {
      savedJob = saved.getLong(STATE_JOB);
      restoreRollback = saved.getBoolean(STATE_ROLLBACK);
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    started = true;
    attach(job == null ? work.open(this, savedJob) : work.current());
    if (restoreRollback) {
      restoreRollback = false;
      if (job.id == savedJob) rollback.open(work.openRollback(this, job));
    }
    render();
  }

  @Override
  protected void onStop() {
    started = false;
    handler.removeCallbacks(render);
    var dialog = confirm;
    confirm = null;
    if (dialog != null) dialog.dismiss();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (job != null) out.putLong(STATE_JOB, job.id);
    out.putBoolean(STATE_ROLLBACK, rollback.expanded());
    super.onSaveInstanceState(out);
  }

  private void confirmCleanup() {
    var expected = job;
    confirm(
        R.string.storage_clean_title,
        getString(R.string.storage_clean_confirm),
        R.string.storage_clean_start,
        () -> start(StoragePageWork.Kind.CLEANUP, expected));
  }

  private void confirmExpansion(RollbackStorageInventory.Copy copy) {
    var target = copy.expansionTarget();
    if (copy.kind() != RollbackStorageInventory.Kind.COMPRESSED_RUNTIME
        || !copy.compressedProofRecorded()
        || target == null) return;
    var expected = job;
    var expansion = new StoragePageWork.Expansion(target, copy.relativePath());
    confirm(
        R.string.storage_rollback_expand_title,
        getString(R.string.storage_rollback_expand_confirm, wrapPath(expansion.path())),
        R.string.storage_rollback_expand_action,
        () -> showJob(work.startExpansion(this, expected, expansion)));
  }

  private void confirm(int title, String message, int action, Runnable accepted) {
    var expected = job;
    if (!started || expected.frame.running() || openingConfirmation || confirm != null) return;
    if (work.current() != expected) {
      render();
      return;
    }
    openingConfirmation = true;
    try {
      var dialog =
          new DshaDialogBuilder(this, true)
              .setTitle(title)
              .setMessage(message)
              .setPositiveButton(action, (acceptedDialog, which) -> accepted.run())
              .setNegativeButton(android.R.string.cancel, null)
              .show();
      // Prior authorization can run the action during show() without opening a dialog.
      if (!dialog.isShowing()) return;
      if (!started || isFinishing() || isDestroyed()) {
        dialog.dismiss();
        return;
      }
      confirm = dialog;
      dialog.setOnDismissListener(
          dismissed -> {
            if (confirm == dialog) confirm = null;
          });
    } finally {
      openingConfirmation = false;
    }
  }

  private void start(StoragePageWork.Kind kind, StoragePageWork.Job expected) {
    showJob(work.start(this, kind, expected));
  }

  private void showJob(StoragePageWork.Job next) {
    if (!started) return;
    attach(next);
    page.scroll.scrollTo(0, 0);
    render();
  }

  private void toggleRollback() {
    if (rollback.expanded()) rollback.close();
    else rollback.open(work.openRollback(this, job));
    render();
  }

  private void measureRollbackCopy(String path) {
    if (work.measureCopy(this, job, path)) render();
  }

  private void attach(StoragePageWork.Job latest) {
    if (job == latest) return;
    job = latest;
    shown = null;
    rollback.close();
    progress.results.removeAllViews();
  }

  private void render() {
    handler.removeCallbacks(render);
    if (!started || isFinishing() || isDestroyed()) return;
    attach(work.current());
    var frame = job.frame;
    if (frame != shown) {
      shown = frame;
      if (frame.inventory() != null && frame.inventory() != shownInventory) {
        shownInventory = frame.inventory();
        renderUsage(shownInventory);
        rollback.summarize(shownInventory);
      }
      if (frame.running()) progress.show(job, frame);
      else renderResult(frame.result());
      clean.setEnabled(!frame.running());
      refresh.setEnabled(!frame.running());
      rollback.setEnabled(!frame.running());
    }
    boolean loadingRollback = rollback.render();
    if (frame.running() || loadingRollback) handler.postDelayed(render, PROGRESS_SAMPLE_MS);
  }

  private void renderResult(StoragePageWork.Result result) {
    var inventory = shown.inventory();
    boolean partial = result.refreshed() && inventory != null && incompleteUsage(inventory.sizes);
    if (result.cleanup() != null) {
      renderCleanup(result.cleanup(), result.refreshed(), partial);
    } else if (result.expansion() != null) {
      renderExpansion(result.expansion(), result.refreshed(), partial);
    } else if (result.error() != null) {
      String detail =
          result.errorHint() == 0
              ? MaintenanceErrorText.render(result.error())
              : getString(result.errorHint(), result.error());
      progress.finish(
          getString(
              switch (job.kind) {
                case CLEANUP -> R.string.storage_clean_failed;
                case EXPAND -> R.string.storage_rollback_expand_failed;
                default -> R.string.storage_scan_failed;
              },
              detail));
      if (job.kind == StoragePageWork.Kind.EXPAND) showExpandedPath();
    } else {
      progress.finish(partial ? getString(R.string.storage_scan_partial) : "");
    }
    work.markDisplayed(job);
  }

  private String wrapPath(String path) {
    return BidiFormatter.getInstance(getResources().getConfiguration().getLocales().get(0))
        .unicodeWrap(path);
  }

  private void showExpandedPath() {
    var path = page.text(wrapPath(job.expansion.path()), 13, R.color.shell_text_secondary);
    path.setPadding(0, page.dp(6), 0, page.dp(6));
    progress.results.addView(path);
  }

  private void renderExpansion(
      StorageMaintenance.ExpansionResult result, boolean refreshed, boolean partial) {
    int title =
        switch (result.outcome()) {
          case "RESTORED" -> R.string.storage_rollback_expand_success;
          case "RESTORED_ARCHIVE_RETAINED" -> R.string.storage_rollback_expand_archive_retained;
          case "ALREADY_EXPANDED" -> R.string.storage_rollback_expand_already;
          case "RAW_CONFLICT" -> R.string.storage_rollback_expand_conflict;
          case "PROOF_UNAVAILABLE" -> R.string.storage_rollback_expand_no_proof;
          case "INSUFFICIENT_SPACE" -> R.string.storage_rollback_expand_no_space;
          default -> 0;
        };
    progress.finish(
        title != 0
            ? getString(title)
            : getString(
                R.string.storage_rollback_expand_failed,
                MaintenanceErrorText.render(result.outcome())));
    showExpandedPath();
    renderProofReason(progress.results, result.reason());
    if (result.availableBefore() >= 0 || result.availableAfter() >= 0) {
      page.kv(
          progress.results,
          getString(R.string.storage_rollback_expand_before),
          availableText(result.availableBefore()));
      page.kv(
          progress.results,
          getString(R.string.storage_rollback_expand_after),
          availableText(result.availableAfter()));
    }
    if (!StoragePageWork.expansionPublished(result)) return;
    if (result.expandedBytes() >= 0)
      page.kv(
          progress.results,
          getString(R.string.storage_cleanup_compress_source),
          Fmt.binaryBytes(result.expandedBytes()));
    if (!refreshed || partial)
      progress.results.addView(
          page.text(getString(R.string.storage_scan_partial), 12, R.color.shell_text_secondary));
  }

  private void renderUsage(StorageMaintenance.Inventory inventory) {
    var sizes = inventory.sizes;
    Map<String, Long> bytes = new LinkedHashMap<>();
    for (var entry : sizes.entrySet()) bytes.put(entry.getKey(), entry.getValue().bytes);
    boolean partial = incompleteUsage(sizes);
    RollingNumber.value(total, StorageUsage.total(bytes), amount -> measuredUsage(amount, partial));
    available.setText(getString(R.string.storage_available, availableText(inventory.available)));
    usage.removeAllViews();
    rows.removeAllViews();
    long whitelistBytes = 0;
    boolean partialWhitelist = false;
    for (String key : new String[] {"linux", "cache", "webview"}) {
      var size = sizes.get(key);
      if (size == null) continue;
      whitelistBytes += size.candidateBytes;
      partialWhitelist |= size.unreadable > 0;
    }
    TextView whitelist = findViewById(R.id.storage_whitelist_summary);
    whitelist.setText(
        getString(
                R.string.storage_cleanup_whitelist_usage,
                measuredUsage(whitelistBytes, partialWhitelist))
            + (partialWhitelist ? getString(R.string.storage_partial) : ""));
    whitelist.setVisibility(View.VISIBLE);
    var segments = StorageUsage.segments(bytes, PALETTE.length);
    Map<String, Integer> colors = new HashMap<>();
    for (int i = 0; i < segments.size(); i++) {
      var segment = segments.get(i);
      int color = getColor(PALETTE[i]);
      if (segment.key != null) colors.put(segment.key, color);
      android.view.View part = new android.view.View(this);
      part.setBackgroundColor(color);
      usage.addView(
          part, new LinearLayout.LayoutParams(0, -1, (float) Math.max(segment.fraction, 0.004)));
    }
    UiMotion.reveal(usage);
    int other =
        segments.isEmpty() || segments.get(segments.size() - 1).key != null
            ? 0
            : getColor(PALETTE[segments.size() - 1]);
    for (var entry : sizes.entrySet()) {
      int before = rows.getChildCount();
      boolean partialRow = entry.getValue().unreadable > 0;
      String suffix = partialRow ? getString(R.string.storage_partial) : "";
      page.kv(
          rows,
          category(this, entry.getKey()),
          measuredUsage(entry.getValue().bytes, partialRow) + suffix);
      if (rows.getChildCount() == before) continue;
      Integer color = colors.get(entry.getKey());
      if (color == null && entry.getValue().bytes > 0 && other != 0) color = other;
      TextView label =
          (TextView) ((LinearLayout) rows.getChildAt(rows.getChildCount() - 1)).getChildAt(0);
      android.graphics.drawable.GradientDrawable dot =
          new android.graphics.drawable.GradientDrawable();
      dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      dot.setColor(color == null ? getColor(R.color.shell_raised) : color);
      int size = page.dp(10);
      dot.setSize(size, size);
      label.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
      label.setCompoundDrawablePadding(page.dp(8));
      String key = entry.getKey();
      int candidateLabel =
          switch (key) {
            case "linux", "cache", "webview" -> R.string.storage_candidate;
            case "host-runtime-operations", "host-environment-operations" ->
                R.string.storage_pending_proof;
            default -> 0;
          };
      String reason = getString(protectionReason(key));
      if (candidateLabel != 0)
        reason =
            getString(candidateLabel, measuredUsage(entry.getValue().candidateBytes, partialRow))
                + suffix
                + "\n"
                + reason;
      TextView detail = page.text(reason, 12, R.color.shell_text_secondary);
      detail.setPaddingRelative(page.dp(18), 0, 0, page.dp(8));
      rows.addView(detail);
    }
  }

  private static boolean incompleteUsage(Map<String, StorageMaintenance.Size> sizes) {
    return sizes.values().stream().anyMatch(size -> size.unreadable > 0);
  }

  private String measuredUsage(long bytes, boolean partial) {
    return partial && bytes == 0 ? getString(R.string.storage_unknown) : Fmt.binaryBytes(bytes);
  }

  static String category(Context context, String key) {
    return switch (key) {
      case "webview" -> context.getString(R.string.storage_category_webview);
      case "historical-audit-fixtures" -> context.getString(R.string.storage_category_historical);
      case "other-private" -> context.getString(R.string.storage_category_other);
      default -> StorageCategoryLabels.label(key);
    };
  }

  private static int protectionReason(String key) {
    return switch (key) {
      case "linux" -> R.string.storage_keep_linux;
      case "cache" -> R.string.storage_keep_cache;
      case "webview" -> R.string.storage_keep_webview;
      case "host-runtime-operations" -> R.string.storage_keep_runtime;
      case "host-environment-operations" -> R.string.storage_keep_environment;
      case "host-backup-operations" -> R.string.storage_keep_backups;
      case "historical-audit-fixtures" -> R.string.storage_keep_historical;
      case "runtime-updates" -> R.string.storage_keep_updates;
      case "mozilla" -> R.string.storage_keep_mozilla;
      case "user-data-v5" -> R.string.storage_keep_userdata;
      default -> R.string.storage_keep_other;
    };
  }

  private String availableText(long bytes) {
    return bytes < 0 ? getString(R.string.storage_unknown) : Fmt.binaryBytes(bytes);
  }

  private void renderCleanup(
      StorageMaintenance.CleanupResult result, boolean refreshed, boolean partial) {
    progress.finish(
        result.error.isEmpty()
            ? getString(R.string.storage_completed)
            : getString(R.string.storage_incomplete, MaintenanceErrorText.render(result.error)));
    LinearLayout report = progress.results;
    page.kv(report, getString(R.string.storage_before), availableText(result.availableBefore));
    page.kv(report, getString(R.string.storage_after), availableText(result.availableAfter));
    if (result.availableBefore >= 0 && result.availableAfter >= 0) {
      long change = result.availableAfter - result.availableBefore;
      page.kv(
          report,
          getString(R.string.storage_delta),
          (change > 0 ? "+" : "") + Fmt.binaryBytes(change));
    }
    page.kv(report, getString(R.string.storage_removed), Fmt.binaryBytes(result.removedBytes()));
    for (var entry : result.removed.entrySet())
      page.kv(report, category(this, entry.getKey()), Fmt.binaryBytes(entry.getValue()));
    if (result.compression != null) renderCompression(report, result.compression);
    report.addView(
        page.text(getString(R.string.storage_delta_note), 12, R.color.shell_text_secondary));
    if (!refreshed)
      report.addView(
          page.text(getString(R.string.storage_refresh_failed), 12, R.color.shell_text_secondary));
    else if (partial)
      report.addView(
          page.text(getString(R.string.storage_scan_partial), 12, R.color.shell_text_secondary));
  }

  private void renderCompression(LinearLayout report, StorageMaintenance.CompressionResult result) {
    String text =
        switch (result.outcome()) {
          case "COMPACTED" -> getString(R.string.storage_cleanup_compressed);
          case "NO_ELIGIBLE_COPY" -> getString(R.string.storage_cleanup_compress_no_eligible);
          case "INSUFFICIENT_SPACE" -> getString(R.string.storage_cleanup_compress_no_space);
          case "PROOF_UNAVAILABLE" -> getString(R.string.storage_cleanup_compress_no_proof);
          case "CANCELLED" -> getString(R.string.storage_cleanup_compress_cancelled);
          default ->
              getString(
                  R.string.storage_cleanup_compress_failed,
                  MaintenanceErrorText.render(result.error()));
        };
    var summary = page.text(text, 13, R.color.shell_text);
    summary.setPadding(0, page.dp(8), 0, page.dp(8));
    report.addView(summary);
    renderProofReason(report, result.reason());
    if (result.copies() > 0) {
      page.kv(
          report,
          getString(R.string.storage_cleanup_compress_source),
          Fmt.binaryBytes(result.expandedBytes()));
      page.kv(
          report,
          getString(R.string.storage_cleanup_compress_archive),
          Fmt.binaryBytes(result.archiveBytes()));
      page.kv(
          report,
          getString(R.string.storage_cleanup_compress_delta),
          Fmt.binaryBytes(result.expandedBytes() - result.archiveBytes()));
    }
  }

  private void renderProofReason(LinearLayout report, String reason) {
    int message =
        switch (reason) {
          case "ACTIVE_DESCRIPTOR_MISSING" ->
              R.string.storage_proof_reason_active_descriptor_missing;
          case "ACTIVE_HEALTH_INVALID" -> R.string.storage_proof_reason_active_health_invalid;
          case "PENDING_RUNTIME" -> R.string.storage_rollback_pending_operation;
          case "ACTIVE_PROOF_MAP" -> R.string.storage_proof_reason_active_proof_map;
          case "ACTIVE_DSH_DIGEST" -> R.string.storage_proof_reason_active_dsh_digest;
          case "ACTIVE_DESCRIPTOR_DIGEST" -> R.string.storage_proof_reason_active_descriptor_digest;
          case "HISTORICAL_DSH_DIGEST" -> R.string.storage_proof_reason_historical_dsh_digest;
          case "HISTORICAL_SEAL" -> R.string.storage_proof_reason_historical_seal;
          case "HISTORICAL_SCRATCH" -> R.string.storage_proof_reason_historical_scratch;
          case "HISTORICAL_ARCHIVE_SELECTION" ->
              R.string.storage_proof_reason_historical_archive_selection;
          case "HISTORICAL_IDENTITY" -> R.string.storage_proof_reason_historical_identity;
          default -> 0;
        };
    if (message == 0) return;
    var detail = page.text(getString(message), 13, R.color.shell_text_secondary);
    detail.setPadding(0, page.dp(4), 0, page.dp(8));
    report.addView(detail);
  }
}
