package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.BidiFormatter;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.util.Fmt;
import java.text.NumberFormat;

/** The stage is a live region; changing counters can also be read on demand. */
final class StorageProgressCard {
  final LinearLayout root;
  final LinearLayout results;
  final TextView stage;
  private final Context context;
  private final TextView category;
  private final TextView counts;
  private final TextView targets;
  private final ProgressBar bar;
  private final NumberFormat numbers;
  private final BidiFormatter bidi;

  StorageProgressCard(CardPage page) {
    context = page.context;
    var locale = context.getResources().getConfiguration().getLocales().get(0);
    numbers = NumberFormat.getIntegerInstance(locale);
    bidi = BidiFormatter.getInstance(locale);
    root = page.card(CardTokens.Tier.FILLED);
    root.setId(R.id.storage_progress_report);
    root.setSaveFromParentEnabled(false);
    stage = page.text("", 14, R.color.shell_text);
    stage.setId(R.id.storage_progress_stage);
    stage.setTypeface(null, android.graphics.Typeface.BOLD);
    stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(stage);
    bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    bar.setId(R.id.storage_progress_bar);
    bar.setMax(1000);
    bar.setProgressTintList(ColorStateList.valueOf(context.getColor(R.color.shell_accent)));
    bar.setIndeterminateTintList(ColorStateList.valueOf(context.getColor(R.color.shell_accent)));
    bar.setProgressBackgroundTintList(
        ColorStateList.valueOf(context.getColor(R.color.shell_raised)));
    bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    var barParams = new LinearLayout.LayoutParams(-1, page.dp(8));
    barParams.topMargin = page.dp(10);
    root.addView(bar, barParams);
    category = detail(page, R.id.storage_progress_category);
    counts = detail(page, R.id.storage_progress_counts);
    targets = detail(page, R.id.storage_progress_targets);
    results = page.column();
    root.addView(results);
    root.setVisibility(View.GONE);
  }

  private TextView detail(CardPage page, int id) {
    var detail = page.text("", 13, R.color.shell_text_secondary);
    detail.setId(id);
    detail.setPadding(0, page.dp(6), 0, 0);
    root.addView(detail);
    return detail;
  }

  void show(StoragePageWork.Job job, StoragePageWork.Frame frame) {
    var kind = job.kind;
    var progress = frame.progress();
    boolean expansion = kind == StoragePageWork.Kind.EXPAND && !frame.rescanning();
    boolean scan = (kind != StoragePageWork.Kind.CLEANUP && !expansion) || frame.rescanning();
    boolean compressing =
        !scan && progress != null && progress.phase() == StorageMaintenance.Phase.COMPRESSING;
    int title;
    if (scan) {
      title =
          kind == StoragePageWork.Kind.INITIAL_SCAN
              ? R.string.storage_scan_running
              : R.string.storage_scan_refreshing;
    } else if (expansion) {
      title =
          progress == null
              ? R.string.storage_rollback_expand_preparing
              : switch (progress.compressionStage()) {
                case "ROLLBACK_ARCHIVE_VERIFYING", "ROLLBACK_ARCHIVE_RESTORE_DIGEST" ->
                    R.string.storage_rollback_expand_verify;
                case "ROLLBACK_ARCHIVE_RESTORING" -> R.string.storage_rollback_expand_extract;
                case "ROLLBACK_ARCHIVE_PUBLISHING", "ROLLBACK_ARCHIVE_REPLACING" ->
                    R.string.storage_rollback_expand_store;
                default -> R.string.storage_rollback_expand_preparing;
              };
    } else if (compressing) {
      title =
          switch (progress.compressionStage()) {
            case "ROLLBACK_ARCHIVE_VERIFYING", "ROLLBACK_ARCHIVE_RESTORE_DIGEST" ->
                R.string.storage_clean_compress_verify;
            case "ROLLBACK_ARCHIVE_SOURCE_DIGEST" -> R.string.storage_clean_verifying;
            case "ROLLBACK_ARCHIVE_RESTORING" -> R.string.storage_clean_compress_restore;
            case "ROLLBACK_ARCHIVE_REPLACING" -> R.string.storage_clean_compress_replace;
            default -> R.string.storage_clean_compressing;
          };
    } else if (progress == null || progress.category().isEmpty()) {
      title = R.string.storage_clean_preparing;
    } else {
      title =
          progress.phase() == StorageMaintenance.Phase.REMOVING
              ? R.string.storage_clean_removing
              : R.string.storage_clean_verifying;
    }

    root.setVisibility(View.VISIBLE);
    if (results.getChildCount() > 0) results.removeAllViews();
    showText(stage, context.getString(title));
    showText(
        category,
        expansion
            ? bidi.unicodeWrap(job.expansion.path())
            : progress == null || progress.category().isEmpty()
                ? ""
                : StorageActivity.category(context, progress.category()));
    if (compressing || expansion && progress != null) {
      showArchiveProgress(progress);
      return;
    }
    String count = "";
    if (progress != null) {
      boolean checking = scan || progress.phase() == StorageMaintenance.Phase.VERIFYING;
      count =
          checking
              ? context.getString(
                  R.string.storage_scan_count,
                  numbers.format(scan ? progress.visitedEntries() : progress.verifiedEntries()),
                  bidi.unicodeWrap(
                      Fmt.binaryBytes(scan ? progress.knownBytes() : progress.verifiedBytes())))
              : context.getString(
                  R.string.storage_clean_count,
                  numbers.format(progress.removedEntries()),
                  bidi.unicodeWrap(Fmt.binaryBytes(progress.removedBytes())));
    }
    showText(counts, count);
    boolean known = progress != null && progress.totalTargets() > 0;
    if (known) {
      showBar(progress.completedTargets(), progress.totalTargets());
      showText(
          targets,
          context.getString(
              R.string.storage_clean_targets,
              numbers.format(progress.completedTargets()),
              numbers.format(progress.totalTargets())));
    } else {
      showBar(0, -1);
      showText(targets, "");
    }
  }

  private void showArchiveProgress(StorageMaintenance.Progress progress) {
    long done = progress.compressionBytes();
    long total = progress.compressionTotalBytes();
    showBar(done, total);
    showText(targets, "");
    if (total > 0) {
      showText(
          counts,
          context.getString(
              R.string.storage_clean_compressing_total,
              bidi.unicodeWrap(Fmt.binaryBytes(done)),
              bidi.unicodeWrap(Fmt.binaryBytes(total))));
    } else {
      showText(
          counts,
          done == 0
              ? ""
              : context.getString(
                  R.string.storage_clean_compressing_count,
                  bidi.unicodeWrap(Fmt.binaryBytes(done))));
    }
  }

  private void showBar(long done, long total) {
    boolean known = total > 0;
    bar.setVisibility(View.VISIBLE);
    if (bar.isIndeterminate() == known) bar.setIndeterminate(!known);
    // Set the current phase's ratio immediately, even when its denominator changed.
    if (known) bar.setProgress(progressTicks(done, total));
  }

  private static int progressTicks(long completed, long total) {
    if (completed >= total) return 1000;
    return (int) Math.min(999, Math.max(0, 1000d * completed / total));
  }

  void finish(CharSequence message) {
    bar.setVisibility(View.GONE);
    bar.setIndeterminate(false);
    showText(category, "");
    showText(counts, "");
    showText(targets, "");
    showText(stage, message);
    root.setVisibility(message.length() == 0 ? View.GONE : View.VISIBLE);
  }

  private static void showText(TextView view, CharSequence text) {
    if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    int visibility = text.length() == 0 ? View.GONE : View.VISIBLE;
    if (view.getVisibility() != visibility) view.setVisibility(visibility);
  }
}
