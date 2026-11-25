package com.deepseekharness.app.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.transfer.SelectedDocumentSave;
import com.deepseekharness.app.util.DocumentPaths;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.RuntimeWorkPort;
import com.deepseekharness.app.util.WorkspaceDocumentIds;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Plain selected-file export through the existing document provider and checked host file layer.
 */
public final class WorkspaceFileExports {
  private WorkspaceFileExports() {}

  public static final class Source {
    public final String id, name, mime;
    public final long size, modified;

    public Source(String id, String name, String mime, long size, long modified)
        throws IOException {
      this.id = WorkspaceDocumentIds.checked(id);
      DocumentPaths.checkName(name);
      if (size < 0 || mime == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))
        throw new IOException("WORKSPACE_SOURCE_METADATA");
      this.name = name;
      this.mime = mime;
      this.size = size;
      this.modified = modified;
    }

    boolean same(Source other) {
      return other != null
          && id.equals(other.id)
          && name.equals(other.name)
          && mime.equals(other.mime)
          && size == other.size
          && modified == other.modified;
    }
  }

  public static final class Result {
    public final Uri uri;
    public final String name;
    public final FileIntegrity.Result integrity;

    Result(Uri uri, String name, FileIntegrity.Result integrity) {
      this.uri = uri;
      this.name = name;
      this.integrity = integrity;
    }
  }

  public static String authority(Context context) {
    return context.getPackageName() + ".documents";
  }

  public static Uri document(Context context, String id) throws IOException {
    return DocumentsContract.buildDocumentUri(authority(context), WorkspaceDocumentIds.checked(id));
  }

  private static Source source(Context context, Source selected, BackupControl control)
      throws IOException {
    try (Cursor cursor =
        DocumentStreams.query(
            context.getContentResolver(),
            document(context, selected.id),
            new String[] {
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              OpenableColumns.DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              OpenableColumns.SIZE,
              DocumentsContract.Document.COLUMN_LAST_MODIFIED
            },
            control)) {
      if (!cursor.moveToFirst() || cursor.isNull(3))
        throw new IOException("WORKSPACE_SOURCE_METADATA");
      Source actual =
          new Source(
              cursor.getString(0),
              cursor.getString(1),
              cursor.getString(2),
              cursor.getLong(3),
              cursor.isNull(4) ? 0 : cursor.getLong(4));
      if (!selected.same(actual)) throw new IOException("WORKSPACE_SOURCE_CHANGED");
      return actual;
    }
  }

  /** Exact-length copy: a truncated or still-growing work file is never reported as exported. */
  public static FileIntegrity.Result copy(
      InputStream input, OutputStream output, long size, FileIntegrity.Check check)
      throws IOException {
    FileIntegrity.Result result = FileIntegrity.copy(input, output, size, check);
    if (result.size != size) throw new IOException("WORKSPACE_SOURCE_CHANGED");
    return result;
  }

  public static void verify(
      InputStream input, FileIntegrity.Result expected, FileIntegrity.Check check)
      throws IOException {
    if (!expected.matches(copy(input, null, expected.size, check)))
      throw new IOException("WORKSPACE_EXPORT_VERIFY");
  }

  static boolean isSaveDestination(Context context, Uri destination) {
    if (destination == null || !ContentResolver.SCHEME_CONTENT.equals(destination.getScheme()))
      return false;
    String authority = destination.getEncodedAuthority();
    if (authority == null || authority.isEmpty()) return false;
    int userSeparator = authority.indexOf('@');
    String provider = destination.getAuthority();
    if (userSeparator >= 0) {
      String user = authority.substring(0, userSeparator);
      provider = authority.substring(userSeparator + 1);
      if (!user.matches("0|[1-9][0-9]{0,9}") || !provider.matches("[A-Za-z0-9_.-]+")) return false;
      try {
        Integer.parseInt(user);
      } catch (NumberFormatException malformedUser) {
        return false;
      }
    }
    String app = context.getPackageName();
    if (provider.equals(app) || provider.startsWith(app + ".") || provider.contains("@"))
      return false;
    if (userSeparator < 0) return DocumentsContract.isDocumentUri(context, destination);

    String path = destination.getEncodedPath();
    if (path == null
        || !path.matches("/document/[^/]+")
        || destination.getEncodedQuery() != null
        || destination.getEncodedFragment() != null) return false;
    int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    if (context.checkUriPermission(destination, Process.myPid(), Process.myUid(), grants)
        != PackageManager.PERMISSION_GRANTED) return false;

    // PackageManager declares the provider without a user prefix. Keep the original URI
    // for all permission checks and I/O so Android still resolves the destination's user.
    Uri forClassification = destination.buildUpon().authority(provider).build();
    boolean declaredDocument = DocumentsContract.isDocumentUri(context, forClassification);
    ProviderInfo declaration =
        context
            .getPackageManager()
            .resolveContentProvider(provider, PackageManager.MATCH_UNINSTALLED_PACKAGES);
    return declaration != null
        && declaration.exported
        && declaration.grantUriPermissions
        && "android.permission.MANAGE_DOCUMENTS".equals(declaration.readPermission)
        && "android.permission.MANAGE_DOCUMENTS".equals(declaration.writePermission)
        && (declaredDocument || isPlatformStorageProvider(provider, declaration));
  }

  private static boolean isPlatformStorageProvider(String authority, ProviderInfo provider) {
    String platformPackage =
        switch (authority) {
          case "com.android.externalstorage.documents" -> "com.android.externalstorage";
          case "com.android.providers.downloads.documents" -> "com.android.providers.downloads";
          default -> null;
        };
    return platformPackage != null
        && platformPackage.equals(provider.packageName)
        && provider.applicationInfo != null
        && (provider.applicationInfo.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
            != 0;
  }

  /**
   * Export to the document chosen in the system picker. The picker can return an existing document
   * after overwrite confirmation, so failure must never delete that document.
   */
  public static Result save(
      Context context, Source selected, Uri destination, BackupControl control) throws Exception {
    if (!isSaveDestination(context, destination))
      throw new IOException("WORKSPACE_EXPORT_DESTINATION");
    BackupFileSystem fs = new AndroidBackupFileSystem();
    File temporary = null;
    try (RuntimeWorkPort.Work ignored = RuntimeWorkPort.begin("作品文件导出")) {
      control.check();
      Source actual = source(context, selected, control);
      // Only a framework-owned application cache root is used here. Guest source paths are
      // resolved and FD-checked exclusively by DshaDocumentsProvider, including its data alias.
      File cache = context.getCacheDir().getCanonicalFile();
      temporary = fs.child(cache, "workspace-file-export-" + UUID.randomUUID() + ".part");
      FileIntegrity.Result expected;
      try (InputStream in =
              DocumentStreams.input(
                  context.getContentResolver(), document(context, actual.id), control);
          OutputStream out = fs.create(temporary)) {
        expected = copy(in, out, actual.size, control::check);
      }
      source(context, actual, control);
      try (InputStream in =
          DocumentStreams.input(
              context.getContentResolver(), document(context, actual.id), control)) {
        verify(in, expected, control::check);
      }
      source(context, actual, control);
      BackupFileSystem.Node staged = fs.stat(temporary);
      File sourceFile = temporary;
      SelectedDocumentSave.write(
          context,
          destination,
          () -> fs.read(sourceFile, staged),
          expected.size,
          expected.sha256,
          control);
      String name = actual.name;
      try (Cursor cursor =
          DocumentStreams.optionalQuery(
              context.getContentResolver(),
              destination,
              new String[] {OpenableColumns.DISPLAY_NAME},
              control)) {
        if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
      } catch (IOException | RuntimeException unavailable) {
        // The selected document has already been read back successfully.
      }
      return new Result(destination, name, expected);
    } finally {
      if (temporary != null) {
        try {
          fs.delete(temporary);
        } catch (IOException cleanup) {
          android.util.Log.w(
              "DSHA", "WORKSPACE_EXPORT_CACHE_CLEANUP:" + cleanup.getClass().getSimpleName());
        }
      }
    }
  }
}
