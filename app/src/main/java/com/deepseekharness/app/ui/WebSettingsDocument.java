package com.deepseekharness.app.ui;

import android.graphics.Typeface;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One app-private, read-only document opened from the active ordinary Web page. */
final class WebSettingsDocument {
  static final int PREVIEW_BYTES = 64 * 1024;

  record Preview(String text, boolean truncated) {}

  enum Result {
    OPENED,
    MISSING,
    CANCELLED,
    FAILED
  }

  private AlertDialog dialog;
  private Consumer<Result> pending;

  void request(WebPreviewActivity activity, BooleanSupplier current, Consumer<Result> reply) {
    if (!current.getAsBoolean() || pending != null || dialog != null) {
      reply.accept(Result.FAILED);
      return;
    }
    pending = reply;
    // WebMessage supplies origin and document identity, but no platform-verified user gesture.
    // Consent here keeps same-origin plugins from opening a private document on their own.
    var confirmation =
        new DshaDialogBuilder(activity)
            .setTitle(title())
            .setMessage(
                UiText.choose(
                    "在应用内只读查看当前工作台的配置文件。",
                    "View the current workbench configuration as read-only in this app."))
            .setNegativeButton(UiText.text("取消"), (view, action) -> finish(reply, Result.CANCELLED))
            .setPositiveButton(UiText.text("继续"), (view, action) -> read(activity, current, reply))
            .create();
    confirmation.setOnCancelListener(view -> finish(reply, Result.CANCELLED));
    confirmation.setOnDismissListener(
        view -> {
          if (dialog == confirmation) dialog = null;
        });
    dialog = confirmation;
    try {
      confirmation.show();
    } catch (RuntimeException unavailable) {
      dialog = null;
      finish(reply, Result.FAILED);
    }
  }

  private void read(WebPreviewActivity activity, BooleanSupplier current, Consumer<Result> reply) {
    if (pending != reply || !current.getAsBoolean()) {
      finish(reply, Result.CANCELLED);
      return;
    }
    File files = activity.getFilesDir();
    new Thread(
            () -> {
              Preview contents;
              try (var lease = EnvironmentTaskGate.tryAcquireShared("web-settings-document")) {
                if (lease == null || MaintenanceCoordinator.pending(files))
                  throw new IOException("CONFIG_DOCUMENT_BUSY");
                contents = readCurrent(new AndroidBackupFileSystem(), files);
              } catch (IOException | RuntimeException unavailable) {
                activity.runOnUiThread(() -> finish(reply, Result.FAILED));
                return;
              }
              activity.runOnUiThread(
                  () -> {
                    if (pending != reply || !current.getAsBoolean()) {
                      finish(reply, Result.CANCELLED);
                      return;
                    }
                    try {
                      show(activity, contents);
                      finish(reply, contents == null ? Result.MISSING : Result.OPENED);
                    } catch (RuntimeException unavailable) {
                      dialog = null;
                      finish(reply, Result.FAILED);
                    }
                  });
            },
            "dsha-web-settings-document")
        .start();
  }

  /** Read only the canonical active web profile. A missing path stays missing. */
  static Preview readCurrent(BackupFileSystem fs, File files) throws IOException {
    var layout = new UserDataLayout(fs, files);
    File home = layout.current();
    File profiles = fs.child(home, "profiles");
    if (fs.stat(profiles).type.equals("MISSING")) return null;
    File web = fs.child(profiles, "web");
    if (fs.stat(web).type.equals("MISSING")) return null;
    File document = fs.child(web, "cordis.patch.yml");
    var before = fs.stat(document);
    if (before.type.equals("MISSING")) return null;
    if (!before.type.equals("FILE") || before.links > 1)
      throw new IOException("CONFIG_DOCUMENT_TYPE");
    byte[] bytes;
    try (var input = fs.read(document, before);
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int remaining = PREVIEW_BYTES + 1;
      while (remaining > 0) {
        int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
        if (count < 0) break;
        output.write(buffer, 0, count);
        remaining -= count;
      }
      bytes = output.toByteArray();
    }
    if (!before.same(fs.stat(document)) || !home.equals(layout.current()))
      throw new IOException("CONFIG_DOCUMENT_CHANGED");
    boolean truncated = bytes.length > PREVIEW_BYTES;
    int size = Math.min(PREVIEW_BYTES, bytes.length);
    var decoded = CharBuffer.allocate(size);
    var status =
        StandardCharsets.UTF_8
            .newDecoder()
            .decode(ByteBuffer.wrap(bytes, 0, size), decoded, !truncated);
    if (status.isError()) status.throwException();
    return new Preview(decoded.flip().toString(), truncated);
  }

  private void show(WebPreviewActivity activity, Preview contents) {
    var builder = new DshaDialogBuilder(activity).setTitle(title());
    builder.setPositiveButton(UiText.text("关闭"), null);
    if (contents == null) {
      builder.setMessage(
          UiText.choose(
              "当前工作台还没有配置文件。", "The current workbench does not have a configuration file yet."));
    } else {
      var text = new TextView(activity);
      text.setTextColor(activity.getColor(R.color.shell_text));
      text.setTypeface(Typeface.MONOSPACE);
      text.setTextSize(14);
      text.setText(
          contents.truncated()
              ? UiText.format(
                  UiText.choose("%s\n\n（已截断到 64 KB）", "%s\n\n(truncated at 64 KB)"),
                  contents.text())
              : contents.text());
      var view = new ScrollView(activity);
      int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
      view.setPadding(padding, padding / 2, padding, padding / 2);
      view.addView(text);
      builder.setView(view);
    }
    var preview = builder.create();
    preview.setOnDismissListener(
        view -> {
          if (dialog == preview) dialog = null;
        });
    dialog = preview;
    // The document stays on this app surface and is never placed on the clipboard or in recents.
    if (preview.getWindow() != null)
      preview.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    preview.show();
    android.util.Log.i(
        "DSHA", contents == null ? "WEB_CONFIG_DOCUMENT_MISSING" : "WEB_CONFIG_DOCUMENT_OPENED");
  }

  private static String title() {
    return UiText.text("当前工作台配置 · 只读");
  }

  private void finish(Consumer<Result> reply, Result result) {
    if (pending != reply) return;
    pending = null;
    reply.accept(result);
  }

  void close() {
    if (dialog != null) {
      AlertDialog previous = dialog;
      dialog = null;
      previous.dismiss();
    }
    if (pending != null) finish(pending, Result.CANCELLED);
  }
}
