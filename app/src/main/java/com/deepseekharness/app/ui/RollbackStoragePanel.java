package com.deepseekharness.app.ui;

import android.content.Context;
import android.text.BidiFormatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.RollbackStorageInventory;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.util.Fmt;
import com.deepseekharness.app.util.MaintenanceErrorText;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Shows the fast logical subtotal and loads per-copy details only on expansion. */
final class RollbackStoragePanel {
  private static final int COPIES_PER_PAGE = 16;
  private final CardPage page;
  private final Context context;
  private final View entry;
  private final TextView summary;
  private final LinearLayout details;
  private final BidiFormatter bidi;
  private final NumberFormat numbers;
  private final Consumer<String> measure;
  private final Consumer<RollbackStorageInventory.Copy> expand;
  private final List<CopyRow> rows = new ArrayList<>();
  private StoragePageWork.RollbackLoad load;
  private StoragePageWork.RollbackResult shown;
  private RollbackStorageInventory.Report displayReport;
  private int displayLimit;

  private static final class CopyRow {
    RollbackStorageInventory.Copy initial;
    final LinearLayout view;
    Button button;
    StoragePageWork.CopyLoad measurement;
    StoragePageWork.CopyResult measured;

    CopyRow(RollbackStorageInventory.Copy initial, LinearLayout view) {
      this.initial = initial;
      this.view = view;
    }
  }

  RollbackStoragePanel(
      CardPage page,
      Runnable toggle,
      Consumer<String> measure,
      Consumer<RollbackStorageInventory.Copy> expand) {
    this.page = page;
    this.measure = measure;
    this.expand = expand;
    context = page.context;
    var locale = context.getResources().getConfiguration().getLocales().get(0);
    bidi = BidiFormatter.getInstance(locale);
    numbers = NumberFormat.getIntegerInstance(locale);
    var card = page.card();
    entry =
        page.entry(card, text(R.string.storage_rollback_title), "", R.drawable.ic_ui2_box, toggle);
    entry.setId(R.id.storage_rollback_button);
    summary = page.text(text(R.string.storage_rollback_hint), 13, R.color.shell_text_secondary);
    summary.setId(R.id.storage_rollback_summary);
    card.addView(summary);
    details = page.column();
    details.setId(R.id.storage_rollback_details);
    details.setSaveFromParentEnabled(false);
    card.addView(details);
    close();
  }

  void summarize(StorageMaintenance.Inventory inventory) {
    long logical = 0;
    long proofs = 0;
    boolean incomplete = false;
    for (String key : new String[] {"host-runtime-operations", "host-environment-operations"}) {
      var size = inventory.sizes.get(key);
      if (size == null) continue;
      logical += size.rollbackBytes;
      proofs += size.candidateBytes;
      incomplete |= size.unreadable != 0;
    }
    summary.setText(
        text(R.string.storage_rollback_usage_summary, bytes(logical))
            + (proofs > 0 ? "\n" + text(R.string.storage_pending_proof, bytes(proofs)) : "")
            + (incomplete ? "\n" + text(R.string.storage_rollback_usage_pending) : ""));
  }

  boolean expanded() {
    return load != null;
  }

  void setEnabled(boolean enabled) {
    entry.setEnabled(enabled);
  }

  void open(StoragePageWork.RollbackLoad next) {
    if (next == null) return;
    load = next;
    shown = null;
    displayReport = null;
    displayLimit = COPIES_PER_PAGE;
    details.setVisibility(View.VISIBLE);
    resetDetails();
    entry.setStateDescription(text(R.string.storage_rollback_expanded));
    if (next.result == null) loading();
  }

  private void loading() {
    var label = paragraph(text(R.string.storage_rollback_loading));
    label.setId(R.id.storage_rollback_loading_label);
    label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    var spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    spinner.setIndeterminate(true);
    spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    details.addView(spinner, new LinearLayout.LayoutParams(-1, page.dp(8)));
  }

  private void resetDetails() {
    details.removeAllViews();
    rows.clear();
  }

  void close() {
    load = null;
    shown = null;
    displayReport = null;
    details.setVisibility(View.GONE);
    resetDetails();
    entry.setStateDescription(text(R.string.storage_rollback_collapsed));
  }

  /** True while the foreground Activity should continue sampling. */
  boolean render() {
    if (!expanded()) return false;
    var result = load.result;
    if (result == null) {
      var preview = load.preview;
      if (preview != null && preview != displayReport) {
        var previous = displayReport;
        displayReport = preview;
        if (previous == null) {
          resetDetails();
          loading();
          renderReport(preview);
        } else {
          updatePreviewRows(preview.copies());
        }
      }
      return true;
    }
    if (result != shown) {
      shown = result;
      resetDetails();
      if (result.report() == null) {
        var error =
            paragraph(
                text(
                    R.string.storage_rollback_failed, MaintenanceErrorText.render(result.error())));
        error.setId(R.id.storage_rollback_error);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      } else {
        displayReport = result.report();
        renderReport(result.report());
      }
    }
    for (CopyRow row : rows) refreshRow(row);
    return load.measuring();
  }

  private void updatePreviewRows(List<RollbackStorageInventory.Copy> copies) {
    for (int i = 0; i < rows.size() && i < copies.size(); i++) {
      var row = rows.get(i);
      var copy = copies.get(i);
      if (copy.relativePath().equals(row.initial.relativePath()) && !copy.equals(row.initial)) {
        row.initial = copy;
        drawCopy(row, copy);
      }
    }
  }

  private void renderReport(RollbackStorageInventory.Report report) {
    if (report.active() != null) {
      heading(R.string.storage_rollback_active);
      identity(report.active());
      if (report.activeHealthRecorded()) paragraph(text(R.string.storage_rollback_active_healthy));
    }
    if (report.pendingOperation()) paragraph(text(R.string.storage_rollback_pending_operation));
    if (shown != null && report.incomplete()) paragraph(text(R.string.storage_rollback_incomplete));
    paragraph(
        text(
            R.string.storage_rollback_operation_counts,
            numbers.format(report.runtimeOperations()),
            numbers.format(report.environmentOperations())));
    paragraph(
        text(R.string.storage_rollback_metadata_usage, bytes(report.operationMetadataBytes())));
    if (report.copies().isEmpty()) {
      if (shown != null && !report.incomplete()) paragraph(text(R.string.storage_rollback_empty));
      return;
    }
    paragraph(text(R.string.storage_rollback_assessment_note));
    value(
        R.string.storage_rollback_distinct_allocated,
        allocation(report.allocationComplete(), report.distinctAllocatedBytes()));
    paragraph(text(R.string.storage_rollback_allocated_note));
    if (shown != null
        && report.copies().stream().anyMatch(RollbackStorageInventory.Copy::incomplete))
      paragraph(text(R.string.storage_rollback_totals_initial));
    if (report.copies().stream().anyMatch(copy -> copy.runtimeMarkerFiles() > 0))
      paragraph(text(R.string.storage_rollback_runtime_markers_note));
    appendCopies(report.copies(), 0);
  }

  private void appendCopies(List<RollbackStorageInventory.Copy> copies, int start) {
    int end = Math.min(copies.size(), displayLimit);
    for (int i = start; i < end; i++) {
      var row = new CopyRow(copies.get(i), page.column());
      rows.add(row);
      details.addView(row.view);
      drawCopy(row, row.initial);
      refreshRow(row);
    }
    if (end < copies.size()) {
      var more =
          page.button(
              details,
              text(R.string.storage_rollback_more),
              false,
              () -> {
                details.removeViewAt(details.getChildCount() - 1);
                displayLimit += COPIES_PER_PAGE;
                appendCopies(displayReport.copies(), end);
              });
      more.setId(R.id.storage_rollback_more_button);
    }
  }

  private void refreshRow(CopyRow row) {
    var measurement = load.measurements.get(row.initial.relativePath());
    var result = measurement == null ? null : measurement.result;
    if (measurement != row.measurement || result != row.measured) {
      row.measurement = measurement;
      row.measured = result;
      drawCopy(row, result != null && result.copy() != null ? result.copy() : row.initial);
    }
    if (row.button != null) row.button.setEnabled(!load.measuring());
  }

  private void drawCopy(CopyRow row, RollbackStorageInventory.Copy copy) {
    var view = row.view;
    view.removeAllViews();
    row.button = null;
    heading(
        view,
        switch (copy.kind()) {
          case RUNTIME -> R.string.storage_rollback_runtime;
          case COMPRESSED_RUNTIME -> R.string.storage_rollback_compressed_runtime;
          case ENVIRONMENT -> R.string.storage_rollback_environment;
          case FAILED_RUNTIME -> R.string.storage_rollback_failed_runtime;
          case FAILED_ENVIRONMENT -> R.string.storage_rollback_failed_environment;
          case REBUILD_BACKUP -> R.string.storage_rollback_rebuild_backup;
        });
    var path = paragraph(view, bidi.unicodeWrap(copy.relativePath()));
    path.setTextIsSelectable(true);
    path.setId(R.id.storage_rollback_path);
    identity(view, copy.identity());
    if (copy.replacedByActive()) paragraph(view, text(R.string.storage_rollback_direct_previous));
    value(
        view,
        R.string.storage_rollback_logical,
        exactBytes(copy.logicalBytes())
            + (copy.incomplete() && copy.logicalBytes() >= 0
                ? text(R.string.storage_partial)
                : ""));
    value(
        view,
        R.string.storage_rollback_allocated,
        allocation(copy.allocationComplete(), copy.allocatedBytes()));
    if (copy.managedDshLogicalBytes() >= 0)
      value(view, R.string.storage_rollback_managed_dsh, bytes(copy.managedDshLogicalBytes()));
    if (copy.kind() == RollbackStorageInventory.Kind.COMPRESSED_RUNTIME) {
      if (copy.compressedOriginalBytes() >= 0)
        value(
            view, R.string.storage_cleanup_compress_source, bytes(copy.compressedOriginalBytes()));
      paragraph(
          view,
          text(
              copy.compressedProofRecorded()
                  ? R.string.storage_rollback_compressed_proof
                  : R.string.storage_rollback_compressed_unverified));
    }
    if (copy.runtimeMarkerFiles() > 0)
      paragraph(
          view,
          text(
              R.string.storage_rollback_runtime_markers,
              numbers.format(copy.runtimeMarkerFiles()),
              bytes(copy.runtimeMarkerBytes())));
    paragraph(
        view,
        text(
            switch (copy.state()) {
              case RETAINED_HISTORY -> R.string.storage_rollback_retained;
              case PROOF_PENDING -> R.string.storage_rollback_verify;
              case ARCHIVE_PROOF_ONLY -> R.string.storage_rollback_runtime_health_unknown;
              case NO_PROOF -> R.string.storage_rollback_no_proof;
              case PENDING_OPERATION -> R.string.storage_rollback_pending;
              case FAILED_ARTIFACT -> R.string.storage_rollback_failed_artifact;
              case UNRECOGNIZED -> R.string.storage_rollback_unknown;
            }));
    if (copy.kind() == RollbackStorageInventory.Kind.COMPRESSED_RUNTIME) {
      if (!copy.runtimeHealthRecorded()
          && copy.state() != RollbackStorageInventory.State.ARCHIVE_PROOF_ONLY)
        paragraph(view, text(R.string.storage_rollback_runtime_health_unknown));
      if (shown != null && copy.compressedProofRecorded() && copy.expansionTarget() != null) {
        var action =
            page.button(
                view,
                text(R.string.storage_rollback_expand_action),
                false,
                () -> expand.accept(copy));
        action.setId(R.id.storage_rollback_expand_button);
      }
    }
    if (row.measurement != null && row.measured == null) {
      var progress = paragraph(view, text(R.string.storage_rollback_measuring_copy));
      progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    } else if (shown != null && copy.incomplete()) {
      if (row.measured != null && row.measured.copy() == null) {
        var error =
            paragraph(
                view,
                text(
                    R.string.storage_rollback_measure_failed,
                    MaintenanceErrorText.render(row.measured.error())));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      }
      row.button =
          page.button(
              view,
              text(R.string.storage_rollback_measure_copy),
              false,
              () -> measure.accept(copy.relativePath()));
      row.button.setId(R.id.storage_rollback_measure_button);
    }
  }

  private void value(int label, String size) {
    value(details, label, size);
  }

  private void value(LinearLayout group, int label, String size) {
    paragraph(group, text(label) + "\n" + size);
  }

  private void identity(RollbackStorageInventory.Identity id) {
    identity(details, id);
  }

  private void identity(LinearLayout group, RollbackStorageInventory.Identity id) {
    if (id == null) return;
    var versions =
        paragraph(
            group,
            text(
                R.string.storage_rollback_identity,
                identityPart(id.dshVersion()),
                identityPart(id.baseVersion()),
                identityPart(id.apkVersion())));
    versions.setId(R.id.storage_rollback_versions);
    if (!id.runtimeId().isEmpty()) paragraph(group, bidi.unicodeWrap(id.runtimeId()));
  }

  private String identityPart(String value) {
    return value.isEmpty() ? text(R.string.storage_unknown) : bidi.unicodeWrap(value);
  }

  private String allocation(boolean complete, long size) {
    return complete ? exactBytes(size) : text(R.string.storage_unknown);
  }

  private String bytes(long size) {
    return size < 0 ? text(R.string.storage_unknown) : bidi.unicodeWrap(Fmt.binaryBytes(size));
  }

  private String exactBytes(long size) {
    return size < 1024
        ? bytes(size)
        : bidi.unicodeWrap(Fmt.binaryBytes(size) + " (" + size + " B)");
  }

  private void heading(int resource) {
    heading(details, resource);
  }

  private void heading(LinearLayout group, int resource) {
    var view = paragraph(group, text(resource));
    view.setTextColor(context.getColor(R.color.shell_text));
    view.setTypeface(null, android.graphics.Typeface.BOLD);
    view.setAccessibilityHeading(true);
  }

  private TextView paragraph(String value) {
    return paragraph(details, value);
  }

  private TextView paragraph(LinearLayout group, String value) {
    var view = page.text(value, 13, R.color.shell_text_secondary);
    view.setPadding(0, page.dp(8), 0, 0);
    group.addView(view);
    return view;
  }

  private String text(int resource, Object... args) {
    return args.length == 0 ? context.getString(resource) : context.getString(resource, args);
  }
}
