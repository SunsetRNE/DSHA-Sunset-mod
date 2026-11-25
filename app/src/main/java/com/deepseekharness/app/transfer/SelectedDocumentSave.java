package com.deepseekharness.app.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.UiText;
import java.io.*;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Retain the old bytes until an overwrite has been read back in full. */
public final class SelectedDocumentSave {
  public static final String RECOVERY_DIRECTORY = "save-recovery";
  private static final String RETAINED = "FILE_ORIGINAL_COPY_";
  private static final Set<Uri> active = ConcurrentHashMap.newKeySet();
  private static final long RESERVE = 16L * 1024 * 1024;

  private SelectedDocumentSave() {}

  public interface Source {
    InputStream open() throws IOException;
  }

  private record Original(File file, BackupFileSystem.Node node, FileIntegrity.Result integrity) {}

  public static boolean retained(String code) {
    return code != null && code.matches(RETAINED + "[A-F0-9]{32}");
  }

  public static String recoveryMessage(String code) {
    if (!retained(code)) return null;
    String name = code.substring(RETAINED.length()) + ".bin";
    return UiText.format("旧文档的原件已保留。打开「作品文件 → DSHA → save-recovery」，选择以下文件可重新导出：%s", name);
  }

  public static void write(
      Context app, Uri uri, Source source, long size, String sha256, BackupControl control)
      throws IOException {
    if (!active.add(uri)) throw new IOException("FILE_SAVE_TARGET_BUSY");
    try {
      ContentResolver resolver = app.getContentResolver();
      BackupFileSystem fs = new AndroidBackupFileSystem();
      Original old = retain(app, resolver, uri, fs, control);
      if (old != null) {
        try {
          if (!matches(resolver, uri, () -> fs.read(old.file, old.node), false, control))
            throw new IOException("FILE_SAVE_TARGET_CHANGED");
        } catch (IOException | RuntimeException changed) {
          clean(fs, old);
          throw changed;
        }
      }
      try {
        replace(resolver, uri, source, size, sha256, control);
      } catch (IOException | RuntimeException failure) {
        if (old != null) {
          try {
            if (!matches(resolver, uri, () -> fs.read(old.file, old.node), false, control)) {
              // Another editor may have replaced the document. Only roll back our own prefix.
              if (!matches(resolver, uri, source, true, control))
                throw new IOException("FILE_SAVE_TARGET_CHANGED");
              replace(
                  resolver,
                  uri,
                  () -> fs.read(old.file, old.node),
                  old.integrity.size,
                  old.integrity.sha256,
                  control);
            }
          } catch (IOException | RuntimeException restoreFailure) {
            String id = old.file.getName().substring(0, 32);
            IOException retained = new IOException(RETAINED + id, failure);
            retained.addSuppressed(restoreFailure);
            throw retained;
          }
          clean(fs, old);
        }
        throw failure;
      }
      if (old != null) clean(fs, old);
    } finally {
      active.remove(uri);
    }
  }

  private static synchronized File directory(Context app, BackupFileSystem fs) throws IOException {
    File dir = fs.child(app.getFilesDir().getCanonicalFile(), RECOVERY_DIRECTORY);
    if (fs.stat(dir).type.equals("MISSING")) fs.directory(dir);
    return dir;
  }

  private static Original retain(
      Context app, ContentResolver resolver, Uri uri, BackupFileSystem fs, BackupControl control)
      throws IOException {
    long reported = -1;
    try (Cursor c =
        DocumentStreams.optionalQuery(
            resolver, uri, new String[] {OpenableColumns.SIZE}, control)) {
      if (c != null && c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        reported = c.getLong(0);
    } catch (IOException | RuntimeException unavailable) {
      control.check();
    }
    try (BufferedInputStream input =
        new BufferedInputStream(DocumentStreams.input(resolver, uri, control))) {
      input.mark(1);
      if (input.read() == -1) {
        if (reported > 0) throw new IOException("FILE_SAVE_TARGET_CHANGED");
        return null;
      }
      input.reset();
      File dir = directory(app, fs);
      String id = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
      File part = fs.child(app.getCacheDir().getCanonicalFile(), id + ".part");
      File file = fs.child(dir, id + ".bin");
      boolean created = false;
      try {
        FileIntegrity.Result original;
        try (OutputStream out = fs.create(part)) {
          created = true;
          original =
              FileIntegrity.copy(
                  input, out, Math.max(0, dir.getUsableSpace() - RESERVE), control::check);
        }
        if (reported >= 0 && original.size != reported)
          throw new IOException("FILE_SAVE_TARGET_CHANGED");
        BackupFileSystem.Node node = fs.stat(part);
        try (InputStream in = fs.read(part, node)) {
          if (!original.matches(FileIntegrity.copy(in, null, original.size, control::check)))
            throw new IOException("FILE_BACKUP_VERIFY_FAILED");
        }
        fs.move(part, file);
        // move syncs the parent directory. A process death from this point leaves a plain,
        // exportable .bin in the app's Work files even if the provider is offline.
        return new Original(file, node, original);
      } finally {
        if (created && fs.stat(part).type.equals("FILE")) fs.delete(part);
      }
    }
  }

  private static void replace(
      ContentResolver resolver,
      Uri uri,
      Source source,
      long size,
      String sha256,
      BackupControl control)
      throws IOException {
    try (InputStream in = source.open();
        OutputStream out = DocumentStreams.replacementOutput(resolver, uri, control)) {
      FileIntegrity.Result copied = FileIntegrity.copy(in, out, size, control::check);
      if (copied.size != size || !sha256.equals(copied.sha256))
        throw new IOException("FILE_SHA256_MISMATCH");
    }
    try (InputStream in = DocumentStreams.input(resolver, uri, control)) {
      FileIntegrity.Result found = FileIntegrity.copy(in, null, size, control::check);
      if (found.size != size || !sha256.equals(found.sha256))
        throw new IOException("FILE_SHA256_MISMATCH");
    }
  }

  private static boolean matches(
      ContentResolver resolver, Uri uri, Source source, boolean prefix, BackupControl control)
      throws IOException {
    try (InputStream document = DocumentStreams.input(resolver, uri, control);
        DataInputStream candidate = new DataInputStream(source.open())) {
      byte[] old = new byte[65536], next = new byte[65536];
      int count;
      while ((count = document.read(old)) != -1) {
        control.check();
        try {
          candidate.readFully(next, 0, count);
        } catch (EOFException longer) {
          return false;
        }
        for (int i = 0; i < count; i++) if (old[i] != next[i]) return false;
      }
      return prefix || candidate.read() == -1;
    }
  }

  private static void clean(BackupFileSystem fs, Original old) {
    try {
      fs.deleteChecked(old.file, old.node);
    } catch (IOException failure) {
      android.util.Log.w("DSHA", "FILE_ORIGINAL_COPY_CLEANUP");
    }
  }
}
