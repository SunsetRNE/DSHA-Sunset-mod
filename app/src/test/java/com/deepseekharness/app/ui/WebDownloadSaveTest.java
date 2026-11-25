package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest.NoApkRunner;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.transfer.TransferBehaviorTestSupport.HostFiles;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

@RunWith(NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {HostFiles.class, WebDownloadSaveTest.Streams.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class WebDownloadSaveTest {
  private static final String AUTHORITY = "test.web-download.documents";
  private static final Uri TARGET = Uri.parse("content://" + AUTHORITY + "/document/existing");
  private static final byte[] EXISTING = {10, 20, 30, 40};
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();
  private final byte[] payload = new byte[2 * 65536 + 1];
  private Application app;
  private WebDownloadModel model;
  private Provider provider;
  private final List<Boolean> replies = new ArrayList<>();

  @Implements(value = DocumentStreams.class, isInAndroidSdk = false)
  public static final class Streams {
    @Implementation
    public static InputStream input(ContentResolver resolver, Uri uri, BackupControl control)
        throws IOException {
      control.check();
      return resolver.openInputStream(uri);
    }

    @Implementation
    public static OutputStream replacementOutput(
        ContentResolver resolver, Uri uri, BackupControl control) throws IOException {
      control.check();
      return resolver.openOutputStream(uri, "wt");
    }
  }

  public static final class Provider extends ContentProvider {
    File document, wrongReadback;
    final List<String> opens = new ArrayList<>();
    int deletes;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public String getType(Uri uri) {
      return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String where, String[] args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
      if (!"android:deleteDocument".equals(method)) throw new UnsupportedOperationException(method);
      deletes++;
      assertTrue(document.delete());
      return new Bundle();
    }

    void bindStreams(Application app, Uri uri) {
      ShadowContentResolver resolver = Shadow.extract(app.getContentResolver());
      resolver.registerInputStreamSupplier(
          uri,
          () -> {
            opens.add("r");
            try {
              return new FileInputStream(
                  wrongReadback != null && opens.contains("w") ? wrongReadback : document);
            } catch (IOException error) {
              throw new UncheckedIOException(error);
            }
          });
      resolver.registerOutputStreamSupplier(
          uri,
          () -> {
            opens.add("w");
            try {
              return new FileOutputStream(document);
            } catch (IOException error) {
              throw new UncheckedIOException(error);
            }
          });
    }
  }

  @Before
  public void setup() throws Exception {
    new Random(377L).nextBytes(payload);
    app = RuntimeEnvironment.getApplication();
    model = new WebDownloadModel(app);
    provider = new Provider();
    provider.document = temporary.newFile("existing.bin");
    Files.write(provider.document.toPath(), EXISTING);
    ProviderInfo info = new ProviderInfo();
    info.authority = AUTHORITY;
    info.exported = true;
    info.grantUriPermissions = true;
    provider.attachInfo(app, info);
    ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
    provider.bindStreams(app, TARGET);
  }

  private void awaitWorker() throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (model.isBusy() && System.nanoTime() < end) {
      Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
      Thread.sleep(5);
    }
    assertFalse("worker did not finish", model.isBusy());
  }

  private Bundle stage(byte[] content) throws Exception {
    String base = "http://127.0.0.1:3210/";
    assertTrue(
        model.download(
            base,
            "blob:" + base + "fixture",
            null,
            "fixture.bin",
            content.length,
            new ByteArrayInputStream(content),
            false,
            "application/octet-stream",
            (success, detail) -> replies.add(success)));
    awaitWorker();
    assertEquals("ready", model.state.getValue().phase());
    Bundle saved = new Bundle();
    model.saveState(saved);
    return saved;
  }

  private File cache(Bundle saved) {
    return new File(
        new File(app.getCacheDir(), "web-downloads"), saved.getString("web-file-stage"));
  }

  private void save() throws Exception {
    model.save(TARGET);
    awaitWorker();
  }

  @Test
  public void missingCacheOnPickerReturnPreservesExistingDocument() throws Exception {
    save();
    assertEquals("error", model.state.getValue().phase());
    assertEquals(0, provider.deletes);
    assertTrue(provider.opens.isEmpty());
    assertArrayEquals(EXISTING, Files.readAllBytes(provider.document.toPath()));
  }

  @Test
  public void fullDestinationPreservesPartialDocumentAndOriginalUntilOfflineRetry()
      throws Exception {
    Bundle saved = stage(payload);
    Shadow.<ShadowContentResolver>extract(app.getContentResolver())
        .registerOutputStream(
            TARGET,
            new OutputStream() {
              boolean full;

              @Override
              public void write(int value) throws IOException {
                throw new IOException("ENOSPC");
              }

              @Override
              public void write(byte[] data, int offset, int length) throws IOException {
                if (!full)
                  Files.write(
                      provider.document.toPath(), Arrays.copyOfRange(data, offset, offset + 17));
                full = true;
                throw new IOException("ENOSPC");
              }
            });
    save();
    assertEquals("error", model.state.getValue().phase());
    assertEquals(0, provider.deletes);
    assertArrayEquals(Arrays.copyOf(payload, 17), Files.readAllBytes(provider.document.toPath()));
    File[] originals =
        new File(app.getFilesDir(), "save-recovery")
            .listFiles((dir, name) -> name.endsWith(".bin"));
    assertNotNull(originals);
    assertEquals(1, originals.length);
    assertArrayEquals(EXISTING, Files.readAllBytes(originals[0].toPath()));
    assertTrue(model.state.getValue().message().contains(originals[0].getName()));
    assertArrayEquals(payload, Files.readAllBytes(cache(saved).toPath()));
    assertTrue(model.canRetrySave());
    assertTrue(replies.isEmpty());
    model.retrySave();
    Uri another = Uri.parse("content://" + AUTHORITY + "/document/retry");
    File failedDocument = provider.document;
    provider.document = temporary.newFile("retry.bin");
    provider.bindStreams(app, another);
    model.save(another);
    awaitWorker();
    assertEquals("done", model.state.getValue().phase());
    assertEquals(another, model.lastSavedUri());
    assertEquals(List.of(true), replies);
    assertArrayEquals(payload, Files.readAllBytes(provider.document.toPath()));
    assertArrayEquals(Arrays.copyOf(payload, 17), Files.readAllBytes(failedDocument.toPath()));
    assertFalse(cache(saved).exists());
  }

  @Test
  public void transientPartialWriteRestoresExistingBytesBeforeReportingFailure() throws Exception {
    Bundle saved = stage(payload);
    Shadow.<ShadowContentResolver>extract(app.getContentResolver())
        .registerOutputStreamSupplier(
            TARGET,
            new java.util.function.Supplier<OutputStream>() {
              int opens;

              public OutputStream get() {
                if (++opens > 1) {
                  try {
                    return new FileOutputStream(provider.document);
                  } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                  }
                }
                return new OutputStream() {
                  public void write(int value) throws IOException {
                    throw new IOException("interrupted write");
                  }

                  public void write(byte[] data, int offset, int length) throws IOException {
                    Files.write(
                        provider.document.toPath(), Arrays.copyOfRange(data, offset, offset + 17));
                    throw new IOException("interrupted write");
                  }
                };
              }
            });
    save();
    assertEquals("error", model.state.getValue().phase());
    assertEquals(0, provider.deletes);
    assertArrayEquals(EXISTING, Files.readAllBytes(provider.document.toPath()));
    assertArrayEquals(payload, Files.readAllBytes(cache(saved).toPath()));
    assertEquals(0, new File(app.getFilesDir(), "save-recovery").listFiles().length);
    assertTrue(replies.isEmpty());
  }

  @Test
  public void wrongReadbackKeepsCacheAndWaitsForExplicitAbandon() throws Exception {
    Bundle saved = stage(payload);
    provider.wrongReadback = temporary.newFile("wrong.bin");
    byte[] wrong = payload.clone();
    wrong[wrong.length - 1] ^= 1;
    Files.write(provider.wrongReadback.toPath(), wrong);
    save();
    assertEquals("error", model.state.getValue().phase());
    assertEquals(0, provider.deletes);
    assertNull(model.lastSavedUri());
    assertArrayEquals(payload, Files.readAllBytes(cache(saved).toPath()));
    assertTrue(replies.isEmpty());
    model.acknowledge();
    assertEquals(List.of(false), replies);
    assertFalse(cache(saved).exists());
  }

  @Test
  public void shortenedCacheCannotReplaceSelectedFileOrReportSuccess() throws Exception {
    Bundle saved = stage(payload);
    Files.write(cache(saved).toPath(), Arrays.copyOf(payload, 23));
    save();
    assertEquals("error", model.state.getValue().phase());
    assertNull(model.lastSavedUri());
    assertFalse(model.canRetrySave());
    assertEquals(0, provider.deletes);
    assertTrue(provider.opens.isEmpty());
    assertArrayEquals(EXISTING, Files.readAllBytes(provider.document.toPath()));
  }

  @Test
  public void changedSameSizeCacheAfterRecreationCannotReportSuccess() throws Exception {
    Bundle saved = stage(payload);
    byte[] changed = payload.clone();
    changed[65536] ^= 1;
    Files.write(cache(saved).toPath(), changed);
    model = new WebDownloadModel(app);
    model.restoreState(saved);
    save();
    assertEquals("error", model.state.getValue().phase());
    assertNull(model.lastSavedUri());
    assertFalse(model.canRetrySave());
    assertEquals(0, provider.deletes);
    assertTrue(provider.opens.isEmpty());
    assertArrayEquals(EXISTING, Files.readAllBytes(provider.document.toPath()));
  }

  @Test
  public void emptyDownloadReplacesLongerDocumentAndReportsVerifiedSuccess() throws Exception {
    stage(new byte[0]);
    save();
    assertEquals("done", model.state.getValue().phase());
    assertEquals(0, model.state.getValue().bytes());
    assertEquals(List.of(true), replies);
    assertEquals(0, provider.deletes);
    assertArrayEquals(new byte[0], Files.readAllBytes(provider.document.toPath()));
  }

  @Test
  public void olderSavedStateWithSizeOnlyStillExportsOffline() throws Exception {
    Bundle saved = stage(payload);
    saved.remove("web-file-digest");
    model = new WebDownloadModel(app);
    model.restoreState(saved);
    save();
    assertEquals("done", model.state.getValue().phase());
    assertArrayEquals(payload, Files.readAllBytes(provider.document.toPath()));
    assertEquals(0, provider.deletes);
  }
}
