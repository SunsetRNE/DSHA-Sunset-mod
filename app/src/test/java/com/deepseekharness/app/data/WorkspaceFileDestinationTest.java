package com.deepseekharness.app.data;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.transfer.TransferBehaviorTestSupport.HostFiles;
import com.deepseekharness.app.util.RuntimeWorkPort;
import java.io.IOException;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, shadows = HostFiles.class)
public final class WorkspaceFileDestinationTest {
  private static final String DOWNLOADS = "com.android.providers.downloads.documents";
  private static final String OWNER = "dsh.android.client";
  private static final int READ_WRITE =
      Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
  private final DestinationContext context =
      new DestinationContext(RuntimeEnvironment.getApplication());

  @Test
  public void scopedUserAuthorityUsesTheUnchangedUriForBothGrants() {
    register(DOWNLOADS, "com.android.providers.downloads", true, true, true);
    Uri result = qualified("0", DOWNLOADS, "raw%3A%2Fstorage%2FDownload%2Fqa.txt");
    assertFalse(DocumentsContract.isDocumentUri(context, result));
    assertTrue(
        DocumentsContract.isDocumentUri(context, result.buildUpon().authority(DOWNLOADS).build()));

    context.granted = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    assertFalse(WorkspaceFileExports.isSaveDestination(context, result));
    context.granted = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    assertFalse(WorkspaceFileExports.isSaveDestination(context, result));
    context.granted = READ_WRITE;
    assertTrue(WorkspaceFileExports.isSaveDestination(context, result));
    assertSame(result, context.checked);
    assertEquals(READ_WRITE, context.checkedGrants);
    assertEquals(Process.myUid(), context.checkedUid);
  }

  @Test
  public void acceptsPlatformProviderWhenCurrentUserDoesNotAdvertiseTheDocumentsAction() {
    register(DOWNLOADS, "com.android.providers.downloads", true, true, false);
    context.granted = READ_WRITE;
    assertTrue(
        WorkspaceFileExports.isSaveDestination(context, qualified("0", DOWNLOADS, "msf%3A42")));
  }

  @Test
  public void preservesSameUserThirdPartyDocuments() {
    register("example.files.documents", "example.files", false, true, true);
    assertTrue(
        WorkspaceFileExports.isSaveDestination(
            context, Uri.parse("content://example.files.documents/document/synthetic")));
    assertNull(context.checked);
  }

  @Test
  public void refusesOwnersPrivateProvidersAndUnrecognizedPrivateProviders() {
    context.granted = READ_WRITE;
    register("example.files.private", "example.files", false, false, true);
    register(DOWNLOADS, "example.imposter", false, true, false);
    for (String local : List.of(OWNER + ".documents", OWNER + ".updates", OWNER + ".shizuku")) {
      for (String prefix : List.of("", "0@", "999@")) {
        assertFalse(
            WorkspaceFileExports.isSaveDestination(
                context, Uri.parse("content://" + prefix + local + "/document/protected")));
      }
    }
    assertFalse(
        WorkspaceFileExports.isSaveDestination(
            context, qualified("0", "example.files.private", "protected")));
    assertFalse(
        WorkspaceFileExports.isSaveDestination(context, qualified("0", DOWNLOADS, "synthetic")));
  }

  @Test
  public void refusesMalformedCrossProfileDestinationBeforeCheckingGrants() {
    context.granted = READ_WRITE;
    register(DOWNLOADS, "com.android.providers.downloads", true, true, true);
    for (String malformed :
        List.of(
            "",
            "content://invalid@" + DOWNLOADS + "/document/test",
            "content://0@999@" + DOWNLOADS + "/document/test",
            "content://2147483648@" + DOWNLOADS + "/document/test",
            "content://0@" + DOWNLOADS + "/tree/test/document/test",
            "content://0@" + DOWNLOADS + "/document//test",
            "content://0@" + DOWNLOADS + "/document/test?alt=other",
            "file:///public/test")) {
      assertFalse(malformed, WorkspaceFileExports.isSaveDestination(context, Uri.parse(malformed)));
    }
    assertNull(context.checked);
  }

  @Test
  public void preservesAnExistingDocumentWhenTheSourceChangesBeforeWriting() throws Exception {
    ProviderInfo destination =
        register("example.files.documents", "example.files", false, true, true);
    byte[] existing = {10, 20, 30};
    TargetProvider target = new TargetProvider(existing);
    target.attachInfo(RuntimeEnvironment.getApplication(), destination);
    ShadowContentResolver.registerProviderInternal("example.files.documents", target);
    ChangedSourceProvider source = new ChangedSourceProvider();
    ProviderInfo sourceInfo = new ProviderInfo();
    sourceInfo.authority = OWNER + ".documents";
    sourceInfo.packageName = OWNER;
    sourceInfo.name = ChangedSourceProvider.class.getName();
    sourceInfo.applicationInfo = context.getApplicationInfo();
    sourceInfo.exported = true;
    source.attachInfo(RuntimeEnvironment.getApplication(), sourceInfo);
    ShadowContentResolver.registerProviderInternal(OWNER + ".documents", source);
    RuntimeWorkPort.install(TestWork.PROVIDER);
    WorkspaceFileExports.Source selected =
        new WorkspaceFileExports.Source("linux/ubuntu/root/test", "test", "text/plain", 3, 5);
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                WorkspaceFileExports.save(
                    context,
                    selected,
                    Uri.parse("content://example.files.documents/document/existing"),
                    new BackupControl(null)));
    assertEquals("WORKSPACE_SOURCE_CHANGED", failure.getMessage());
    assertEquals(0, target.calls);
    assertArrayEquals(new byte[] {10, 20, 30}, target.content);
    assertEquals(1, context.resolverRequests);
  }

  @Test
  public void fixtureDetectsAnActualSyntheticDeletion() throws Exception {
    ProviderInfo destination =
        register("example.files.documents", "example.files", false, true, true);
    TargetProvider target = new TargetProvider(new byte[] {10, 20, 30});
    Application app = RuntimeEnvironment.getApplication();
    target.attachInfo(app, destination);
    ShadowContentResolver.registerProviderInternal("example.files.documents", target);
    assertTrue(
        DocumentsContract.deleteDocument(
            app.getContentResolver(),
            Uri.parse("content://example.files.documents/document/existing")));
    assertEquals(1, target.calls);
    assertEquals(0, target.content.length);
  }

  private ProviderInfo register(
      String authority,
      String packageName,
      boolean system,
      boolean protectedProvider,
      boolean visibleAsDocumentsProvider) {
    ApplicationInfo app = new ApplicationInfo();
    app.packageName = packageName;
    app.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
    ProviderInfo provider = new ProviderInfo();
    provider.authority = authority;
    provider.name = packageName + ".Provider";
    provider.packageName = packageName;
    provider.applicationInfo = app;
    provider.exported = protectedProvider;
    provider.grantUriPermissions = true;
    provider.readPermission = protectedProvider ? "android.permission.MANAGE_DOCUMENTS" : null;
    provider.writePermission = provider.readPermission;
    PackageInfo info = new PackageInfo();
    info.packageName = packageName;
    info.applicationInfo = app;
    info.providers = new ProviderInfo[] {provider};
    ShadowPackageManager manager = Shadow.extract(context.getPackageManager());
    manager.installPackage(info);
    if (visibleAsDocumentsProvider) {
      ResolveInfo resolved = new ResolveInfo();
      resolved.providerInfo = provider;
      manager.addResolveInfoForIntent(
          new Intent(DocumentsContract.PROVIDER_INTERFACE), List.of(resolved));
    }
    return provider;
  }

  private static Uri qualified(String user, String provider, String document) {
    return Uri.parse("content://" + user + "@" + provider + "/document/" + document);
  }

  private static final class DestinationContext extends ContextWrapper {
    Uri checked;
    int checkedGrants;
    int checkedUid;
    int granted;
    int resolverRequests;

    DestinationContext(Context base) {
      super(base);
    }

    @Override
    public String getPackageName() {
      return OWNER;
    }

    @Override
    public ContentResolver getContentResolver() {
      resolverRequests++;
      return super.getContentResolver();
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
      checked = uri;
      checkedGrants = modeFlags;
      checkedUid = uid;
      return (granted & modeFlags) == modeFlags
          ? PackageManager.PERMISSION_GRANTED
          : PackageManager.PERMISSION_DENIED;
    }
  }

  private abstract static class TestProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public String getType(Uri uri) {
      return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class ChangedSourceProvider extends TestProvider {
    @Override
    public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
      MatrixCursor cursor = new MatrixCursor(columns);
      cursor.addRow(new Object[] {"linux/ubuntu/root/test", "test", "text/plain", 4, 5});
      return cursor;
    }
  }

  private static final class TargetProvider extends TestProvider {
    byte[] content;
    int calls;

    TargetProvider(byte[] content) {
      this.content = content;
    }

    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
      calls++;
      content = new byte[0];
      return new android.os.Bundle();
    }

    @Override
    public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
      calls++;
      throw new AssertionError("Destination unexpectedly queried");
    }
  }

  private static final class FileProviderFixture extends TestProvider {
    File file;
    String id = "linux/ubuntu/root/test", name = "test";
    long reported = -1;
    boolean source, onlyW, ignoresTruncate;
    final List<String> opens = new ArrayList<>();

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
      opens.add(mode);
      if (onlyW && mode.equals("wt")) throw new FileNotFoundException("unsupported mode");
      int flags =
          mode.equals("r")
              ? ParcelFileDescriptor.MODE_READ_ONLY
              : ParcelFileDescriptor.MODE_WRITE_ONLY;
      if (mode.equals("wt") && !ignoresTruncate) flags |= ParcelFileDescriptor.MODE_TRUNCATE;
      return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
      MatrixCursor cursor = new MatrixCursor(columns);
      cursor.addRow(
          source
              ? new Object[] {id, name, "text/plain", file.length(), 5L}
              : new Object[] {
                columns[0].equals(android.provider.OpenableColumns.SIZE)
                    ? (reported >= 0 ? reported : file.length())
                    : "chosen.txt"
              });
      return cursor;
    }
  }

  private FileProviderFixture fileProvider(String authority, String name, byte[] data)
      throws Exception {
    FileProviderFixture fixture = new FileProviderFixture();
    fixture.file = new File(context.getCacheDir(), name);
    Files.write(fixture.file.toPath(), data);
    fixture.source = authority.equals(OWNER + ".documents");
    ProviderInfo info =
        fixture.source
            ? new ProviderInfo()
            : register(authority, "example.fixture", false, true, true);
    info.authority = authority;
    fixture.attachInfo(RuntimeEnvironment.getApplication(), info);
    ShadowContentResolver.registerProviderInternal(authority, fixture);
    return fixture;
  }

  private WorkspaceFileExports.Result saveFile(FileProviderFixture source) throws Exception {
    RuntimeWorkPort.install(TestWork.PROVIDER);
    return WorkspaceFileExports.save(
        context,
        new WorkspaceFileExports.Source(
            source.id, source.name, "text/plain", source.file.length(), 5L),
        Uri.parse("content://example.files.documents/document/existing"),
        new BackupControl(null));
  }

  @Test
  public void replacesLongerChosenDocumentWithVerifiedShorterWorkspaceFile() throws Exception {
    byte[] data = {4, 5, 6};
    FileProviderFixture target =
        fileProvider("example.files.documents", "existing.txt", new byte[128]);
    FileProviderFixture source = fileProvider(OWNER + ".documents", "workspace.txt", data);
    assertEquals("chosen.txt", saveFile(source).name);
    assertArrayEquals(data, Files.readAllBytes(target.file.toPath()));
    assertArrayEquals(data, Files.readAllBytes(source.file.toPath()));
  }

  @Test
  public void providerAcceptingOnlyBasicWriteStillSavesFreshDocument() throws Exception {
    byte[] data = {4, 5, 6};
    FileProviderFixture target =
        fileProvider("example.files.documents", "existing.txt", new byte[0]);
    target.onlyW = true;
    FileProviderFixture source = fileProvider(OWNER + ".documents", "workspace.txt", data);
    assertEquals(3, saveFile(source).integrity.size);
    assertArrayEquals(data, Files.readAllBytes(target.file.toPath()));
  }

  @Test
  public void ignoredTruncationFailsVerificationAndLeavesBothDocumentsAvailable() throws Exception {
    byte[] data = {4, 5, 6};
    FileProviderFixture target =
        fileProvider("example.files.documents", "existing.txt", new byte[128]);
    target.ignoresTruncate = true;
    FileProviderFixture source = fileProvider(OWNER + ".documents", "workspace.txt", data);
    IOException failure = assertThrows(IOException.class, () -> saveFile(source));
    assertEquals(128, Files.size(target.file.toPath()));
    assertArrayEquals(data, Files.readAllBytes(source.file.toPath()));
    File dir = new File(context.getFilesDir(), "save-recovery");
    File[] originals = dir.listFiles((parent, name) -> name.endsWith(".bin"));
    assertNotNull(originals);
    assertEquals(1, originals.length);
    assertEquals(
        "FILE_ORIGINAL_COPY_" + originals[0].getName().substring(0, 32), failure.getMessage());
    assertArrayEquals(new byte[128], Files.readAllBytes(originals[0].toPath()));
    // The plain private backup is selectable from the regular Work files export path.
    source.file = originals[0];
    source.name = source.file.getName();
    source.id = "save-recovery/" + source.name;
    target.file = new File(context.getCacheDir(), "fresh-restore.txt");
    Files.write(target.file.toPath(), new byte[0]);
    assertEquals(128, saveFile(source).integrity.size);
    assertArrayEquals(new byte[128], Files.readAllBytes(target.file.toPath()));
  }

  @Test
  public void truncatedOriginalReadCannotAuthorizeOverwritingTheDocument() throws Exception {
    FileProviderFixture target =
        fileProvider("example.files.documents", "existing.txt", new byte[12]);
    target.reported = 20;
    FileProviderFixture source =
        fileProvider(OWNER + ".documents", "workspace.txt", new byte[] {4, 5, 6});
    assertThrows(IOException.class, () -> saveFile(source));
    assertFalse(target.opens.contains("wt"));
    assertFalse(target.opens.contains("w"));
    assertArrayEquals(new byte[12], Files.readAllBytes(target.file.toPath()));
  }

  private static final class TestWork implements RuntimeWorkPort.Work {
    static final RuntimeWorkPort.Provider PROVIDER =
        new RuntimeWorkPort.Provider() {
          @Override
          public RuntimeWorkPort.Work begin(boolean detached, String kind) {
            return new TestWork();
          }

          @Override
          public boolean hasOtherTasks() {
            return false;
          }
        };

    @Override
    public void retainUntilExit(java.lang.Process process) {}

    @Override
    public void close() {}
  }
}
