package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.shadows.ShadowLooper.shadowMainLooper;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.RollbackStorageInventory;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.backup.StorageMaintenance.Outcome;
import com.deepseekharness.app.backup.StorageMaintenance.Phase;
import com.deepseekharness.app.backup.StorageMaintenance.Progress;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.DeviceGrantsTest;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Fmt;
import com.deepseekharness.app.util.MaintenanceErrorText;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.TextLayoutMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    qualifiers = "en-rUS-w320dp-h640dp",
    application = StorageActivityProgressTest.App.class,
    shadows = StorageActivityProgressTest.Controller.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class StorageActivityProgressTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    @Implementation
    protected static HarnessController get(Context ignored) {
      return Shadow.newInstanceOf(HarnessController.class);
    }

    @Implementation
    protected ConfigStore config() {
      return new ConfigStore(RuntimeEnvironment.getApplication());
    }
  }

  private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
  private Consumer<Runnable> dispatch = queued::add;
  private StoragePageWork.StorageTask<StorageMaintenance.Inventory> scan =
      (context, progress) -> inventory(false);
  private StoragePageWork.StorageTask<StorageMaintenance.CleanupResult> cleanup =
      (context, progress) -> result("");
  private StoragePageWork.ExpansionTask expansion =
      (context, target, progress) -> {
        throw new AssertionError("Unexpected expansion");
      };
  private final StoragePageWork work =
      new StoragePageWork(
          task -> dispatch.accept(task),
          (context, progress) -> {
            assertSame(RuntimeEnvironment.getApplication(), context);
            return scan.run(context, progress);
          },
          (context, progress) -> {
            assertSame(RuntimeEnvironment.getApplication(), context);
            return cleanup.run(context, progress);
          },
          (context, target, progress) -> {
            assertSame(RuntimeEnvironment.getApplication(), context);
            return expansion.run(context, target, progress);
          });

  @Before
  public void isolateApprovalPolicy() throws Exception {
    new DeviceGrantsTest().isolateProcessStateForIndependentPreferenceFixtures();
    Map<?, ?> dialogs = ReflectionHelpers.getStaticField(DshaDialogBuilder.class, "safetyDialogs");
    dialogs.clear();
  }

  @After
  public void restoreFixtureState() throws Exception {
    RuntimeEnvironment.setFontScale(1f);
    isolateApprovalPolicy();
  }

  @Test
  public void initialScanCoalescesFramesAndKeepsUnknownDenominator() {
    try (var host = open(null)) {
      StorageActivity screen = host.get();
      stage(screen, R.string.storage_scan_running);
      assertTrue(bar(screen).isIndeterminate());
      assertFalse(button(screen, R.id.storage_clean_button).isEnabled());
      assertFalse(button(screen, R.id.storage_refresh_button).isEnabled());
      assertEquals(1, queued.size());
      scan =
          (context, onProgress) -> {
            var counts = text(screen, R.id.storage_progress_counts);
            for (int index = 1; index <= 1000; index++) {
              onProgress.accept(
                  new Progress(
                      Phase.SCANNING,
                      "cache",
                      index,
                      index,
                      0,
                      0,
                      0,
                      -1,
                      0,
                      0,
                      "",
                      0,
                      -1,
                      Outcome.RUNNING));
            }
            assertEquals(View.GONE, counts.getVisibility());
            sample();
            assertEquals(
                screen.getString(R.string.storage_scan_count, "1,000", "1000 B"),
                counts.getText().toString());
            assertTrue(bar(screen).isIndeterminate());
            assertEquals(View.GONE, text(screen, R.id.storage_progress_targets).getVisibility());
            return inventory(false);
          };
      runJob();
      assertEquals(View.GONE, button(screen, R.id.storage_progress_report).getVisibility());
      assertTrue(button(screen, R.id.storage_clean_button).isEnabled());
    }
  }

  @Test
  public void rotationAndBackgroundResumeAttachToSameScan() {
    var old = open(null);
    var state = new Bundle();
    old.pause().saveInstanceState(state).stop().destroy();
    try (var rotated = open(state)) {
      var screen = rotated.get();
      stage(screen, R.string.storage_scan_running);
      assertEquals(1, queued.size());
      rotated.pause().stop();
      runJob();
      stage(screen, R.string.storage_scan_running);
      rotated.restart().resume();
      assertEquals(View.GONE, button(screen, R.id.storage_progress_report).getVisibility());
      assertTrue(queued.isEmpty());
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
  public void refreshWithIncompleteInventoryStopsAnimationAndKeepsCountsVisible() {
    RuntimeEnvironment.setFontScale(2f);
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      button(screen, R.id.storage_refresh_button).performClick();
      stage(screen, R.string.storage_scan_refreshing);
      scan = (context, onProgress) -> inventory(true);
      runJob();
      stage(screen, R.string.storage_scan_partial);
      assertEquals(View.GONE, bar(screen).getVisibility());
      assertFalse(bar(screen).isIndeterminate());
      TextView stage = text(screen, R.id.storage_progress_stage);
      int narrow = Math.max(1, (int) stage.getPaint().measureText(stage.getText().toString()) / 2);
      stage.measure(
          View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      assertTrue(stage.getLayout().getLineCount() > 1);
      assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, stage.getAccessibilityLiveRegion());
    }
  }

  @Test
  public void refreshShowsUnknownAmountsOnlyWhenTheZeroHasIncompleteCoverage() {
    scan = (context, progress) -> measuredInventory(false, 0);
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      String zero = Fmt.binaryBytes(0);
      String unknown = screen.getString(R.string.storage_unknown);
      String partial = screen.getString(R.string.storage_partial);
      TextView total = ReflectionHelpers.getField(screen, "total");
      TextView whitelist = text(screen, R.id.storage_whitelist_summary);
      assertEquals(zero, total.getText().toString());
      assertEquals(zero, categoryAmount(screen, "linux"));
      assertEquals(
          screen.getString(R.string.storage_cleanup_whitelist_usage, zero),
          whitelist.getText().toString());

      scan = (context, progress) -> measuredInventory(true, 0);
      button(screen, R.id.storage_refresh_button).performClick();
      runJob();
      stage(screen, R.string.storage_scan_partial);
      assertEquals(unknown, total.getText().toString());
      assertEquals(unknown + partial, categoryAmount(screen, "linux"));
      assertEquals(zero, categoryAmount(screen, "cache"));
      assertTrue(
          categoryDetail(screen, "linux")
              .startsWith(screen.getString(R.string.storage_candidate, unknown) + partial + "\n"));
      assertEquals(
          screen.getString(R.string.storage_cleanup_whitelist_usage, unknown) + partial,
          whitelist.getText().toString());

      scan = (context, progress) -> measuredInventory(true, 5);
      button(screen, R.id.storage_refresh_button).performClick();
      runJob();
      shadowMainLooper().idleFor(Duration.ofSeconds(1));
      assertEquals(Fmt.binaryBytes(5), total.getText().toString());
      assertEquals(Fmt.binaryBytes(5) + partial, categoryAmount(screen, "linux"));
      assertEquals(Fmt.binaryBytes(0), categoryAmount(screen, "cache"));
      assertEquals(
          screen.getString(R.string.storage_cleanup_whitelist_usage, Fmt.binaryBytes(2)) + partial,
          whitelist.getText().toString());
    }
  }

  private static String categoryAmount(StorageActivity screen, String key) {
    LinearLayout rows = ReflectionHelpers.getField(screen, "rows");
    var row = (LinearLayout) rows.getChildAt(categoryRowIndex(screen, key, rows));
    return ((TextView) row.getChildAt(1)).getText().toString();
  }

  private static String categoryDetail(StorageActivity screen, String key) {
    LinearLayout rows = ReflectionHelpers.getField(screen, "rows");
    return ((TextView) rows.getChildAt(categoryRowIndex(screen, key, rows) + 1))
        .getText()
        .toString();
  }

  private static int categoryRowIndex(StorageActivity screen, String key, LinearLayout rows) {
    for (int index = 0; index < rows.getChildCount(); index++) {
      if (!(rows.getChildAt(index) instanceof LinearLayout row)) continue;
      TextView label = (TextView) row.getChildAt(0);
      if (label.getText().toString().equals(StorageActivity.category(screen, key))) return index;
    }
    throw new AssertionError("No storage category row for " + key);
  }

  private static StorageMaintenance.Inventory measuredInventory(boolean partial, int bytes) {
    var linux = new StorageMaintenance.Size();
    linux.bytes = bytes;
    linux.candidateBytes = bytes == 0 ? 0 : 2;
    linux.unreadable = partial ? 1 : 0;
    return ReflectionHelpers.callConstructor(
        StorageMaintenance.Inventory.class,
        ClassParameter.from(
            Map.class, Map.of("linux", linux, "cache", new StorageMaintenance.Size())),
        ClassParameter.from(long.class, 8192L));
  }

  @Test
  public void cleanupRendersVerificationTargetsRemovalAndRescanInOrder() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      confirm(screen);
      stage(screen, R.string.storage_clean_preparing);
      assertTrue(bar(screen).isIndeterminate());
      assertEquals(1, queued.size());
      button(screen, R.id.storage_clean_button).performClick();
      button(screen, R.id.storage_refresh_button).performClick();
      assertEquals(1, queued.size());
      cleanup =
          (context, onProgress) -> {
            onProgress.accept(
                new Progress(
                    Phase.VERIFYING,
                    "host-runtime-operations",
                    71,
                    16384,
                    0,
                    0,
                    0,
                    -1,
                    5,
                    8192,
                    "",
                    0,
                    -1,
                    Outcome.RUNNING));
            sample();
            stage(screen, R.string.storage_clean_verifying);
            assertTrue(bar(screen).isIndeterminate());
            assertEquals(
                screen.getString(R.string.storage_scan_count, "5", "8.00 KiB"),
                text(screen, R.id.storage_progress_counts).getText().toString());
            onProgress.accept(
                new Progress(
                    Phase.REMOVING,
                    "cache",
                    9,
                    6000,
                    7,
                    4096,
                    3,
                    5,
                    0,
                    0,
                    "",
                    0,
                    -1,
                    Outcome.RUNNING));
            sample();
            stage(screen, R.string.storage_clean_removing);
            assertFalse(bar(screen).isIndeterminate());
            assertEquals(600, bar(screen).getProgress());
            assertEquals(
                screen.getString(R.string.storage_clean_targets, "3", "5"),
                text(screen, R.id.storage_progress_targets).getText().toString());
            assertEquals(
                screen.getString(R.string.storage_clean_count, "7", "4.00 KiB"),
                text(screen, R.id.storage_progress_counts).getText().toString());
            onProgress.accept(
                new Progress(
                    Phase.REMOVING,
                    "linux",
                    11,
                    7000,
                    8,
                    4096,
                    1,
                    2,
                    0,
                    0,
                    "",
                    0,
                    -1,
                    Outcome.RUNNING));
            sample();
            assertEquals(500, bar(screen).getProgress());
            onProgress.accept(
                new Progress(
                    Phase.COMPRESSING,
                    "host-runtime-operations",
                    11,
                    7000,
                    8,
                    4096,
                    0,
                    -1,
                    0,
                    0,
                    "ROLLBACK_ARCHIVING",
                    2048,
                    -1,
                    Outcome.RUNNING));
            sample();
            stage(screen, R.string.storage_clean_compressing);
            assertTrue(bar(screen).isIndeterminate());
            assertEquals(
                screen.getString(R.string.storage_clean_compressing_count, "2.00 KiB"),
                text(screen, R.id.storage_progress_counts).getText().toString());
            onProgress.accept(
                new Progress(
                    Phase.COMPRESSING,
                    "host-runtime-operations",
                    11,
                    7000,
                    8,
                    4096,
                    0,
                    -1,
                    0,
                    0,
                    "ROLLBACK_ARCHIVE_RESTORING",
                    2048,
                    8192,
                    Outcome.RUNNING));
            sample();
            stage(screen, R.string.storage_clean_compress_restore);
            assertFalse(bar(screen).isIndeterminate());
            assertEquals(250, bar(screen).getProgress());
            assertEquals(
                screen.getString(R.string.storage_clean_compressing_total, "2.00 KiB", "8.00 KiB"),
                text(screen, R.id.storage_progress_counts).getText().toString());
            return result(
                "", new StorageMaintenance.CompressionResult(1, 8192, 2048, "COMPACTED", ""));
          };
      scan =
          (context, onProgress) -> {
            sample();
            stage(screen, R.string.storage_scan_refreshing);
            assertTrue(bar(screen).isIndeterminate());
            assertEquals(View.GONE, text(screen, R.id.storage_progress_targets).getVisibility());
            return inventory(false);
          };
      runJob();
      stage(screen, R.string.storage_completed);
      assertEquals(View.GONE, bar(screen).getVisibility());
      var report = button(screen, R.id.storage_progress_report);
      assertTrue(hasText(report, screen.getString(R.string.storage_cleanup_compressed)));
      assertTrue(hasText(report, screen.getString(R.string.storage_cleanup_compress_delta)));
      assertTrue(hasText(report, "6.00 KiB"));
    }
  }

  @Test
  public void reentryDisplaysUnseenPartialCleanupEvenIfRescanFails() {
    var opened = open(null);
    runJob();
    confirm(opened.get());
    opened.pause().stop().destroy();
    cleanup = (context, onProgress) -> result("PERMISSION_DENIED");
    scan =
        (context, onProgress) -> {
          throw new IOException("STORAGE_VOLUME_UNAVAILABLE");
        };
    runJob();
    try (var reopened = open(null)) {
      var screen = reopened.get();
      var report = (ViewGroup) button(screen, R.id.storage_progress_report);
      assertEquals(
          screen.getString(
              R.string.storage_incomplete, MaintenanceErrorText.render("PERMISSION_DENIED")),
          text(screen, R.id.storage_progress_stage).getText().toString());
      assertTrue(hasText(report, screen.getString(R.string.storage_refresh_failed)));
      assertTrue(hasText(report, "4.00 KiB"));
      assertEquals(View.GONE, bar(screen).getVisibility());
      assertTrue(queued.isEmpty());
    }
  }

  @Test
  public void failedCleanupCanBeRetriedAfterReservationFailure() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      confirm(screen);
      cleanup =
          (context, onProgress) -> {
            throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
          };
      runJob();
      assertTrue(
          text(screen, R.id.storage_progress_stage)
              .getText()
              .toString()
              .contains("STOP_DSH_AND_TERMINALS_FIRST"));
      assertEquals(View.GONE, bar(screen).getVisibility());
      assertTrue(button(screen, R.id.storage_clean_button).isEnabled());
      assertTrue(button(screen, R.id.storage_refresh_button).isEnabled());
    }
  }

  @Test
  public void rollbackDetailsKeepStorageBasisAndHistoricalHealthDistinct() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      assertEquals(
          screen.getString(R.string.storage_rollback_usage_summary, "2.00 KiB"),
          text(screen, R.id.storage_rollback_summary).getText().toString());
      assertEquals(
          screen.getString(R.string.storage_cleanup_whitelist_usage, "0 B"),
          text(screen, R.id.storage_whitelist_summary).getText().toString());
      var layout = new CardPage(screen, "", "");
      var panel = new RollbackStoragePanel(layout, () -> {}, path -> {}, copy -> {});
      var load = new StoragePageWork.RollbackLoad();
      var identity = new RollbackStorageInventory.Identity("runtime-id", "0.2.2-rc.5", "10", "372");
      var copy =
          new RollbackStorageInventory.Copy(
              RollbackStorageInventory.Kind.RUNTIME,
              "host-runtime-operations/fixture/previous",
              identity,
              RollbackStorageInventory.State.RETAINED_HISTORY,
              2048,
              4096,
              false,
              true,
              1,
              1024,
              1,
              8,
              true,
              -1,
              false,
              true,
              null);
      var copies = new ArrayList<>(Collections.nCopies(17, copy));
      copies.add(
          0,
          new RollbackStorageInventory.Copy(
              RollbackStorageInventory.Kind.COMPRESSED_RUNTIME,
              "host-runtime-operations/fixture/cold/archive.bin",
              identity,
              RollbackStorageInventory.State.RETAINED_HISTORY,
              2048,
              4096,
              false,
              true,
              0,
              -1,
              0,
              0,
              false,
              8192,
              true,
              true,
              null));
      load.result =
          new StoragePageWork.RollbackResult(
              new RollbackStorageInventory.Report(
                  identity, copies, false, 18, 4096, true, 1, false, true, 1, 0, 256, 17),
              null);
      panel.open(load);
      assertFalse(panel.render());
      var details = (ViewGroup) layout.root.findViewById(R.id.storage_rollback_details);
      assertNotNull(details);
      assertTrue(hasText(details, screen.getString(R.string.storage_rollback_retained)));
      assertTrue(hasText(details, screen.getString(R.string.storage_rollback_compressed_proof)));
      assertTrue(
          hasText(
              details, screen.getString(R.string.storage_cleanup_compress_source) + "\n8.00 KiB"));
      assertTrue(
          hasText(details, screen.getString(R.string.storage_rollback_managed_dsh) + "\n1.00 KiB"));
      assertTrue(hasText(details, screen.getString(R.string.storage_rollback_allocated_note)));
      var more = (TextView) details.getChildAt(details.getChildCount() - 1);
      assertEquals(screen.getString(R.string.storage_rollback_more), more.getText().toString());
      assertTrue(more.performClick());
      assertFalse(hasText(details, screen.getString(R.string.storage_rollback_more)));
      panel.close();
      assertEquals(View.GONE, details.getVisibility());
    }
  }

  @Test
  public void rollbackPreviewShowsUnknownSizesThenUpdatesOnlyMeasuredRows() {
    try (var host = open(null)) {
      var screen = host.get();
      var layout = new CardPage(screen, "", "");
      var requested = new AtomicReference<String>();
      var panel = new RollbackStoragePanel(layout, () -> {}, requested::set, copy -> {});
      var load = new StoragePageWork.RollbackLoad();
      var first = copySize("operations/first", -1);
      var second = copySize("operations/second", -1);
      panel.open(load);
      load.preview = copyReport(List.of(first, second));
      assertTrue(panel.render());
      View details = layout.root.findViewById(R.id.storage_rollback_details);
      assertTrue(hasText(details, first.relativePath()));
      assertTrue(hasText(details, second.relativePath()));
      String label = screen.getString(R.string.storage_rollback_logical) + "\n";
      assertTrue(hasText(details, label + screen.getString(R.string.storage_unknown)));
      assertFalse(hasText(details, label + "0 B"));
      first = copySize(first.relativePath(), 2048);
      load.preview = copyReport(List.of(first, second));
      assertTrue(panel.render());
      assertTrue(hasText(details, label + "2.00 KiB (2048 B)"));

      load.result = new StoragePageWork.RollbackResult(load.preview, null);
      assertFalse(panel.render());
      var measure = details.findViewById(R.id.storage_rollback_measure_button);
      assertNotNull(measure);
      assertTrue(measure.performClick());
      assertEquals(second.relativePath(), requested.get());
      var measurement = new StoragePageWork.CopyLoad();
      load.measurements.put(requested.get(), measurement);
      load.activeMeasurement = measurement;
      assertTrue(panel.render());
      assertTrue(hasText(details, screen.getString(R.string.storage_rollback_measuring_copy)));
      measurement.result =
          new StoragePageWork.CopyResult(copySize(second.relativePath(), 8192), null);
      assertFalse(panel.render());
      assertTrue(hasText(details, label + "2.00 KiB (2048 B)"));
      assertTrue(hasText(details, label + "8.00 KiB (8192 B)"));
      assertFalse(hasText(details, screen.getString(R.string.storage_rollback_measuring_copy)));
    }
  }

  @Test
  public void unpackUsesConfirmedArchiveIdentityAndReportsDurableCopy() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      var target =
          new RollbackStorageInventory.ExpansionTarget(
              "11111111-1111-4111-8111-111111111111", "a".repeat(64));
      var unverified = coldCopy("operations/unverified/cold/archive.bin", false, null);
      var eligible = coldCopy("operations/eligible/cold/archive.bin", true, target);
      openRollback(screen, List.of(unverified, eligible));
      var details = button(screen, R.id.storage_rollback_details);
      assertEquals(1, countId(details, R.id.storage_rollback_expand_button));
      assertTrue(
          hasText(details, screen.getString(R.string.storage_rollback_runtime_health_unknown)));
      var action = button(screen, R.id.storage_rollback_expand_button);
      assertTrue(action.performClick());
      var dialog = (AlertDialog) ShadowDialog.getLatestDialog();
      assertTrue(
          hasText(
              dialog.getWindow().getDecorView(),
              screen.getString(R.string.storage_rollback_expand_confirm, eligible.relativePath())));
      dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      shadowMainLooper().idle();
      assertTrue(queued.isEmpty());
      assertTrue(action.performClick());
      clickExpand();
      stage(screen, R.string.storage_rollback_expand_preparing);
      assertEquals(
          eligible.relativePath(),
          text(screen, R.id.storage_progress_category).getText().toString());
      assertFalse(button(screen, R.id.storage_clean_button).isEnabled());
      expansion =
          (context, confirmed, progress) -> {
            assertEquals(target, confirmed);
            progress.accept(
                new Progress(
                    Phase.EXPANDING,
                    "host-runtime-operations",
                    0,
                    0,
                    0,
                    0,
                    0,
                    -1,
                    0,
                    0,
                    "ROLLBACK_ARCHIVE_RESTORING",
                    4096,
                    8192,
                    Outcome.RUNNING));
            sample();
            stage(screen, R.string.storage_rollback_expand_extract);
            assertFalse(bar(screen).isIndeterminate());
            assertEquals(500, bar(screen).getProgress());
            progress.accept(
                new Progress(
                    Phase.EXPANDING,
                    "host-runtime-operations",
                    0,
                    0,
                    0,
                    0,
                    0,
                    -1,
                    0,
                    0,
                    "ROLLBACK_ARCHIVE_RESTORE_DIGEST",
                    4096,
                    -1,
                    Outcome.RUNNING));
            sample();
            stage(screen, R.string.storage_rollback_expand_verify);
            assertTrue(bar(screen).isIndeterminate());
            return new StorageMaintenance.ExpansionResult(
                16384, 8192, 8192, 2048, "RESTORED_ARCHIVE_RETAINED");
          };
      runJob();
      stage(screen, R.string.storage_rollback_expand_archive_retained);
      assertTrue(hasText(button(screen, R.id.storage_progress_report), eligible.relativePath()));
      assertEquals(View.GONE, bar(screen).getVisibility());
      assertTrue(button(screen, R.id.storage_clean_button).isEnabled());
    }
  }

  @Test
  public void unpackRejectionKeepsReportAndDoesNotStartRescan() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      var target =
          new RollbackStorageInventory.ExpansionTarget(
              "22222222-2222-4222-8222-222222222222", "b".repeat(64));
      var copy = coldCopy("operations/cold/archive.bin", true, target);
      openRollback(screen, List.of(copy));
      assertTrue(button(screen, R.id.storage_rollback_expand_button).performClick());
      clickExpand();
      scan =
          (context, progress) -> {
            throw new AssertionError("Rejected expansion must not rescan");
          };
      expansion =
          (context, confirmed, progress) ->
              new StorageMaintenance.ExpansionResult(512, 512, 8192, 2048, "INSUFFICIENT_SPACE");
      runJob();
      stage(screen, R.string.storage_rollback_expand_no_space);
      openRollback(screen, List.of(copy));
      assertTrue(button(screen, R.id.storage_rollback_expand_button).performClick());
      clickExpand();
      expansion =
          (context, confirmed, progress) -> {
            throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
          };
      runJob();
      assertTrue(
          text(screen, R.id.storage_progress_stage)
              .getText()
              .toString()
              .contains("STOP_DSH_AND_TERMINALS_FIRST"));
      assertEquals(View.GONE, bar(screen).getVisibility());
      assertTrue(hasText(button(screen, R.id.storage_progress_report), copy.relativePath()));
    }
  }

  @Test
  public void globalApprovalAllowsRepeatedCleanupInOneActivity() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      enableGlobalApproval(screen);
      for (int i = 0; i < 2; i++) {
        assertTrue(button(screen, R.id.storage_clean_button).performClick());
        assertEquals("cleanup attempt " + (i + 1), 1, queued.size());
        assertNoConfirmationShowing();
        runJob();
        stage(screen, R.string.storage_completed);
      }
    }
  }

  @Test
  public void globalApprovalAllowsRepeatedUnpackingInOneActivity() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      enableGlobalApproval(screen);
      var archived = new ArrayList<RollbackStorageInventory.ExpansionTarget>();
      expansion =
          (context, confirmed, progress) -> {
            archived.add(confirmed);
            return new StorageMaintenance.ExpansionResult(
                512, 512, 8192, 2048, "INSUFFICIENT_SPACE");
          };
      var first =
          new RollbackStorageInventory.ExpansionTarget(
              "11111111-1111-4111-8111-111111111111", "a".repeat(64));
      var second =
          new RollbackStorageInventory.ExpansionTarget(
              "22222222-2222-4222-8222-222222222222", "b".repeat(64));
      for (var target : List.of(first, second)) {
        openRollback(
            screen,
            List.of(
                coldCopy(
                    "operations/" + target.operationId() + "/cold/archive.bin", true, target)));
        assertTrue(button(screen, R.id.storage_rollback_expand_button).performClick());
        assertEquals("archive action " + target.operationId(), 1, queued.size());
        assertNoConfirmationShowing();
        runJob();
        stage(screen, R.string.storage_rollback_expand_no_space);
      }
      assertEquals(List.of(first, second), archived);
    }
  }

  @Test
  public void synchronousApprovalFencesReentryUntilShowReturns() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      enableGlobalApproval(screen);
      var starts = new AtomicInteger();
      cleanup =
          (context, progress) -> {
            starts.incrementAndGet();
            return result("");
          };
      dispatch =
          task -> {
            task.run();
            ReflectionHelpers.callInstanceMethod(screen, "render");
            if (starts.get() == 1) button(screen, R.id.storage_clean_button).performClick();
          };
      assertTrue(button(screen, R.id.storage_clean_button).performClick());
      assertEquals(1, starts.get());
      assertTrue(button(screen, R.id.storage_clean_button).performClick());
      assertEquals(2, starts.get());
      assertNoConfirmationShowing();
    }
  }

  @Test
  public void ordinaryConfirmationClearsOnStopAndAllowsAnotherAction() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      assertTrue(button(screen, R.id.storage_clean_button).performClick());
      var original = (AlertDialog) ShadowDialog.getLatestDialog();
      assertTrue(original.isShowing());
      host.pause().stop();
      assertFalse(original.isShowing());
      assertTrue(queued.isEmpty());
      host.restart().resume();
      confirm(screen);
      runJob();
      stage(screen, R.string.storage_completed);
      confirm(screen);
      runJob();
      stage(screen, R.string.storage_completed);
    }
  }

  @Test
  public void proofFailuresIdentifyActiveAndHistoricalEvidenceWithoutEchoingRawReasons() {
    try (var host = open(null)) {
      var screen = host.get();
      runJob();
      var report = button(screen, R.id.storage_progress_report);
      cleanup =
          (context, progress) ->
              result(
                  "",
                  new StorageMaintenance.CompressionResult(
                      0, -1, -1, "PROOF_UNAVAILABLE", "", "ACTIVE_DSH_DIGEST"));
      confirm(screen);
      runJob();
      assertTrue(hasText(report, screen.getString(R.string.storage_cleanup_compress_no_proof)));
      assertTrue(
          hasText(report, screen.getString(R.string.storage_proof_reason_active_dsh_digest)));

      var target =
          new RollbackStorageInventory.ExpansionTarget(
              "11111111-1111-4111-8111-111111111111", "a".repeat(64));
      var copy = coldCopy("operations/cold/archive.bin", true, target);
      expansion =
          (context, confirmed, progress) ->
              new StorageMaintenance.ExpansionResult(
                  8192, 8192, -1, -1, "PROOF_UNAVAILABLE", "HISTORICAL_SEAL");
      openRollback(screen, List.of(copy));
      assertTrue(button(screen, R.id.storage_rollback_expand_button).performClick());
      clickExpand();
      runJob();
      stage(screen, R.string.storage_rollback_expand_no_proof);
      assertTrue(hasText(report, screen.getString(R.string.storage_proof_reason_historical_seal)));
      assertFalse(
          hasText(report, screen.getString(R.string.storage_proof_reason_active_dsh_digest)));

      String rawReason = "/private/test-fixture/unverified-scratch";
      cleanup =
          (context, progress) ->
              result(
                  "",
                  new StorageMaintenance.CompressionResult(
                      0, -1, -1, "PROOF_UNAVAILABLE", "", rawReason));
      confirm(screen);
      runJob();
      assertTrue(hasText(report, screen.getString(R.string.storage_cleanup_compress_no_proof)));
      assertFalse(hasText(report, rawReason));
    }
  }

  private static void enableGlobalApproval(StorageActivity screen) {
    var grants = new DeviceGrants(screen);
    assertTrue(grants.setGlobalAllowed(true));
    shadowMainLooper().idle();
    var policy = grants.consentPolicy();
    assertTrue(policy.currentGlobal(policy.revision()));
  }

  private static void assertNoConfirmationShowing() {
    var dialog = ShadowDialog.getLatestDialog();
    assertTrue(dialog == null || !dialog.isShowing());
  }

  private void openRollback(StorageActivity screen, List<RollbackStorageInventory.Copy> rows) {
    var load = new StoragePageWork.RollbackLoad();
    load.result = new StoragePageWork.RollbackResult(copyReport(rows), null);
    ReflectionHelpers.setField(work, "rollback", load);
    assertTrue(button(screen, R.id.storage_rollback_button).performClick());
  }

  private static void clickExpand() {
    var dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    shadowMainLooper().idle();
  }

  private static RollbackStorageInventory.Copy coldCopy(
      String path, boolean proof, RollbackStorageInventory.ExpansionTarget target) {
    return new RollbackStorageInventory.Copy(
        RollbackStorageInventory.Kind.COMPRESSED_RUNTIME,
        path,
        null,
        proof
            ? RollbackStorageInventory.State.ARCHIVE_PROOF_ONLY
            : RollbackStorageInventory.State.NO_PROOF,
        2048,
        2048,
        false,
        true,
        0,
        -1,
        0,
        0,
        false,
        8192,
        proof,
        false,
        target);
  }

  private static int countId(View view, int id) {
    int count = view.getId() == id ? 1 : 0;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) count += countId(group.getChildAt(i), id);
    }
    return count;
  }

  private static RollbackStorageInventory.Copy copySize(String path, long size) {
    return new RollbackStorageInventory.Copy(
        RollbackStorageInventory.Kind.RUNTIME,
        path,
        null,
        RollbackStorageInventory.State.NO_PROOF,
        size,
        size,
        size < 0,
        size >= 0,
        0,
        -1,
        0,
        0,
        false,
        -1,
        false,
        false,
        null);
  }

  private static RollbackStorageInventory.Report copyReport(
      List<RollbackStorageInventory.Copy> rows) {
    return new RollbackStorageInventory.Report(
        null, rows, true, 0, -1, false, 0, false, false, rows.size(), 0, 2, 1);
  }

  private ActivityController<StorageActivity> open(Bundle restored) {
    var host = Robolectric.buildActivity(StorageActivity.class);
    ReflectionHelpers.setField(host.get(), "work", work);
    return restored == null ? host.setup() : host.setup(restored);
  }

  private void runJob() {
    queued.removeFirst().run();
    sample();
  }

  private static void confirm(StorageActivity screen) {
    button(screen, R.id.storage_clean_button).performClick();
    var dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    button(screen, R.id.storage_clean_button).performClick();
    assertSame(dialog, ShadowDialog.getLatestDialog());
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    shadowMainLooper().idle();
  }

  private static void sample() {
    shadowMainLooper().idleFor(Duration.ofMillis(StorageActivity.PROGRESS_SAMPLE_MS));
  }

  private static void stage(StorageActivity screen, int expected) {
    assertEquals(
        screen.getString(expected), text(screen, R.id.storage_progress_stage).getText().toString());
  }

  private static ProgressBar bar(StorageActivity screen) {
    return screen.findViewById(R.id.storage_progress_bar);
  }

  private static TextView text(StorageActivity screen, int id) {
    return screen.findViewById(id);
  }

  private static View button(StorageActivity screen, int id) {
    return screen.findViewById(id);
  }

  private static boolean hasText(View view, String expected) {
    if (view instanceof TextView text && text.getText().toString().equals(expected)) return true;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        if (hasText(group.getChildAt(i), expected)) return true;
      }
    }
    return false;
  }

  private static StorageMaintenance.Inventory inventory(boolean partial) {
    var size = new StorageMaintenance.Size();
    size.bytes = 8192;
    size.rollbackBytes = 2048;
    size.unreadable = partial ? 1 : 0;
    return ReflectionHelpers.callConstructor(
        StorageMaintenance.Inventory.class,
        ClassParameter.from(Map.class, Map.of("host-runtime-operations", size)),
        ClassParameter.from(long.class, 8192L));
  }

  private static StorageMaintenance.CleanupResult result(String error) {
    return result(error, null);
  }

  private static StorageMaintenance.CleanupResult result(
      String error, StorageMaintenance.CompressionResult compression) {
    return ReflectionHelpers.callConstructor(
        StorageMaintenance.CleanupResult.class,
        ClassParameter.from(long.class, 4096L),
        ClassParameter.from(long.class, 8192L),
        ClassParameter.from(Map.class, Map.of("cache", 4096L)),
        ClassParameter.from(String.class, error),
        ClassParameter.from(StorageMaintenance.CompressionResult.class, compression));
  }
}
