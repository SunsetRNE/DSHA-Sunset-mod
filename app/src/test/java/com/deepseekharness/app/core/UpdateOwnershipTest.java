package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContextWrapper;
import android.os.Looper;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.UpdatePolicy;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/** 运行真实 owner/仓库；仅 HTTP 和耗时校验由可控端口提供，绝不打开系统安装器。 */
@RunWith(UpdateEngineCheckTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = UpdateOwnershipTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UpdateOwnershipTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private Application app;
  private UpdateEngine engine;
  private Queue queue;
  private File apk;
  private UpdatePolicy.Release release;

  @Before
  public void prepare() throws Exception {
    app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences("dsha-updates", 0)
        .edit()
        .clear()
        .putString("channel", "preview")
        .commit();
    UiText.setLanguage("zh");
    queue = new Queue();
    engine =
        new UpdateEngine(
            app,
            (url, offset) -> {
              throw new IOException("测试不访问网络");
            },
            queue);
    release = release();
    apk = new File(app.getFilesDir(), "owned-test.apk");
    set("candidate", release);
    set("candidateChannel", "preview");
    set("verifiedApk", apk);
    Method publish =
        UpdateEngine.class.getDeclaredMethod("finishState", String.class, String.class);
    publish.setAccessible(true);
    publish.invoke(engine, "ready", "测试候选");
  }

  @After
  public void restoreLanguage() {
    UiText.setLanguage("zh");
  }

  private static UpdatePolicy.Release release() {
    return new UpdatePolicy.Release(
        BuildConfig.VERSION_CODE + 1,
        "0.2.2-preview",
        "preview",
        "standard",
        30,
        "arm64-v8a",
        "https://dsha.cc/downloads/owned.apk",
        "a".repeat(64),
        100,
        "测试",
        "https://dsha.cc/download/");
  }

  private void set(String name, Object value) throws Exception {
    Field field = UpdateEngine.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private UpdateRepository repository() {
    return new UpdateRepository(app, engine, queue, () -> apk);
  }

  private void finishVerification() {
    queue.runNext();
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
  }

  @Test
  public void applicationFacadesShareExactlyOneEngineAndQueue() {
    UpdateEngine first = UpdateEngine.get(app);
    UpdateEngine second = UpdateEngine.get(new ContextWrapper(new ContextWrapper(app)));
    assertSame(first, second);
    assertSame(first, ((DshaApp) app).updateEngine());
    assertSame(first.verificationExecutor(), second.verificationExecutor());
    assertFalse(first.state().getValue().busy);
  }

  @Test
  public void legacyJavaNullInputsKeepTheirOriginalShapeAndBehavior() {
    UpdateEngine legacy = new UpdateEngine(app, null);
    assertEquals("preview", legacy.channel());
    engine.setChannel(null);
    repository().setChannel(null);
    assertEquals("preview", engine.channel());
    assertEquals(0, queue.size());
    UpdateRepository.State unknown =
        new UpdateRepository.State(
            null, (UpdateRepository.Stage) null, 0, 0, null, null, null, null);
    assertNull(unknown.message);
    assertNull(unknown.stage);
    assertTrue(unknown.busy);
  }

  @Test
  public void dispatchedInstallerWithUnknownOutcomeIsNotClaimedAgain() {
    UpdateRepository repository = repository();
    repository.requestInstall();
    finishVerification();
    assertSame(apk, repository.takeInstallReady());
    repository.restoreInterruptedInstall(false);
    assertNull(repository.takeInstallReady());
    assertEquals(0, queue.size());
    UpdateRepository rebuilt = repository();
    rebuilt.restoreInterruptedInstall(false);
    assertNull(rebuilt.takeInstallReady());
    assertEquals(0, queue.size());
  }

  @Test
  public void retainedStoreAndLanguageChangeDoNotRequeueOrReclaimInstallation() {
    ViewModelStore store = new ViewModelStore();
    ViewModelProvider.Factory factory =
        new ViewModelProvider.Factory() {
          @Override
          @SuppressWarnings("unchecked")
          public <T extends ViewModel> T create(Class<T> type) {
            return (T) repository();
          }
        };
    UpdateRepository old = new ViewModelProvider(store, factory).get(UpdateRepository.class);
    old.requestInstall();
    old.requestInstall();
    assertEquals(1, queue.size());
    UiText.setLanguage("en");
    UpdateRepository rebuilt = new ViewModelProvider(store, factory).get(UpdateRepository.class);
    assertSame(old, rebuilt);
    rebuilt.requestInstall();
    assertEquals(1, queue.size());
    finishVerification();
    assertSame(apk, rebuilt.takeInstallReady());
    assertNull(old.takeInstallReady());
    assertNull(rebuilt.takeInstallReady());
    assertEquals(0, queue.size());
    store.clear();
  }

  @Test
  public void clearedPageCannotPublishOrClaimAnOldCallback() {
    UpdateRepository repository = repository();
    repository.requestInstall();
    repository.onCleared();
    finishVerification();
    assertNull(repository.takeInstallReady());
    assertNull(repository.installation().getValue().apk);
  }

  @Test
  public void replacementCandidateWithSameDigestDoesNotInheritOldClaim() throws Exception {
    UpdateRepository repository = repository();
    repository.requestInstall();
    finishVerification();
    set("candidate", release());
    assertNull(repository.takeInstallReady());
    assertNotNull(repository.installation().getValue().error);
  }

  @Test
  public void channelChangeAfterVerificationInvalidatesClaim() throws Exception {
    UpdateRepository repository = repository();
    repository.requestInstall();
    finishVerification();
    set("channel", "stable");
    assertNull(repository.takeInstallReady());
    assertNotNull(repository.installation().getValue().error);
  }

  @Test
  public void pendingInstallationBlocksAllNewPageWork() {
    UpdateRepository repository = repository();
    repository.requestInstall();
    repository.check();
    repository.download();
    repository.cancel();
    repository.setChannel("stable");
    assertEquals("preview", engine.channel());
    assertEquals(1, queue.size());
    finishVerification();
    assertSame(apk, repository.takeInstallReady());
  }

  @Test
  public void processRebuildReportsInterruptedVerificationWithoutReplayingIt() {
    UpdateRepository recreated = repository();
    recreated.restoreInterruptedInstall(true);
    assertFalse(recreated.installationPending());
    assertNotNull(recreated.installation().getValue().error);
    assertNull(recreated.takeInstallReady());
    assertEquals(0, queue.size());
  }

  @Test
  public void serviceRedeliveryQueuesOneDownloadAndCancelPreventsFurtherResume() throws Exception {
    set("phase", "downloading");
    engine.runDownload();
    engine.runDownload();
    assertEquals(1, queue.size());
    engine.cancel();
    assertFalse(engine.shouldResume());
    engine.runDownload();
    assertEquals(1, queue.size());
    finishVerification();
    assertFalse(engine.state().getValue().busy);
    assertNull(engine.state().getValue().apk);
    engine.runDownload();
    assertEquals(0, queue.size());
  }

  @Test
  public void permissionCallbacksConsumeOnlyTheirOwnGenerationAndKind() {
    UpdateRepository repository = repository();
    int first = repository.prepareDownloadPermission();
    int second = repository.prepareDownloadPermission();
    assertTrue(first >= 0x4000);
    assertTrue(second > first);
    assertFalse(repository.downloadAfterPermission(first));
    assertFalse(repository.finishInstallPermission(second, true));
    assertTrue(repository.hasDownloadPermissionRequest());
    assertTrue(repository.downloadAfterPermission(second));
    assertFalse(repository.downloadAfterPermission(second));
    assertTrue(engine.state().getValue().busy);
    assertEquals(0, queue.size());
    engine.cancel();
  }

  @Test
  public void unknownPermissionReceiptRetiresItsTicketWithoutStartingAnything() {
    UpdateRepository repository = repository();
    int request = repository.prepareDownloadPermission();
    assertTrue(repository.discardDownloadPermission(request));
    assertFalse(repository.downloadAfterPermission(request));
    assertFalse(repository.discardDownloadPermission(request));
    assertFalse(engine.state().getValue().busy);
    assertEquals(0, queue.size());
  }

  @Test
  public void serviceCancelInvalidatesPermissionEvenWhenCandidateAndApkAreUnchanged() {
    UpdateRepository repository = repository();
    int request = repository.prepareInstallPermission();
    engine.cancel();
    assertSame(release, engine.state().getValue().release);
    assertSame(apk, engine.state().getValue().apk);
    assertTrue(repository.finishInstallPermission(request, true));
    assertEquals(0, queue.size());
    assertNull(repository.takeInstallReady());
  }

  @Test
  public void oldInstallPermissionResultCannotConsumeOrCancelANewerSameCandidateRequest() {
    UpdateRepository repository = repository();
    int first = repository.prepareInstallPermission();
    assertTrue(repository.finishInstallPermission(first, false));
    int second = repository.prepareInstallPermission();
    assertTrue(second > first);
    assertFalse(repository.finishInstallPermission(first, true));
    assertTrue(repository.hasInstallPermissionRequest());
    assertTrue(repository.finishInstallPermission(second, true));
    assertFalse(repository.finishInstallPermission(second, false));
    assertTrue(repository.installation().getValue().verifying);
    assertEquals(1, queue.size());
    finishVerification();
    assertSame(apk, repository.takeInstallReady());
    assertNull(repository.takeInstallReady());
  }

  @Test
  public void rebuiltPageKeepsTheOriginalPermissionButAProcessMarkerCannotRecreateIt() {
    UpdateRepository retained = repository();
    int request = retained.prepareInstallPermission();
    UiText.setLanguage("en");
    retained.restoreInterruptedInstall(true);
    assertNull(retained.installation().getValue().error);
    assertTrue(retained.finishInstallPermission(request, true));
    finishVerification();
    assertSame(apk, retained.takeInstallReady());
    retained.onCleared();
    UpdateRepository recreated = repository();
    recreated.restoreInterruptedInstall(true);
    assertNotNull(recreated.installation().getValue().error);
    assertFalse(recreated.finishInstallPermission(request, true));
    assertEquals(0, queue.size());
  }

  @Test
  public void processRebuildReportsInterruptedDownloadPermissionWithoutReplayingIt() {
    UpdateRepository old = repository();
    int request = old.prepareDownloadPermission();
    old.onCleared();
    UpdateRepository recreated = repository();
    recreated.restoreInterruptedDownloadPermission(true);
    assertNotNull(recreated.installation().getValue().error);
    assertFalse(recreated.downloadAfterPermission(request));
    assertFalse(old.downloadAfterPermission(request));
    assertFalse(engine.state().getValue().busy);
    assertEquals(0, queue.size());
  }

  @Test
  public void oldVerificationCannotOverwriteFailureOrANewerExplicitRequest() {
    UpdateRepository repository = repository();
    repository.requestInstall();
    repository.installFailed("执行结果未知");
    repository.requestInstall();
    assertEquals(2, queue.size());
    finishVerification();
    assertTrue(repository.installation().getValue().verifying);
    assertNull(repository.installation().getValue().apk);
    finishVerification();
    assertSame(apk, repository.takeInstallReady());
    assertNull(repository.takeInstallReady());
  }

  @Test
  public void exhaustedOrCorruptPermissionCounterNeverReusesAnOldSystemRequestCode()
      throws Exception {
    var preferences = app.getSharedPreferences("dsha-updates", 0);
    preferences.edit().putString("permission_request_code", "65534").commit();
    assertEquals(-1, repository().prepareDownloadPermission());
    preferences.edit().putString("permission_request_code", "unknown").commit();
    assertThrows(IOException.class, engine::nextPermissionRequestCode);
    assertEquals(0, queue.size());
  }

  @Test
  public void lostReadyReceiptObservesExistingApkWithoutRepeatingDownloadOrSkippingVerification()
      throws Exception {
    Method save = UpdateEngine.class.getDeclaredMethod("save", String.class, String.class);
    save.setAccessible(true);
    save.invoke(engine, "downloading", "旧下载仍在执行");
    File retained = new File(app.getFilesDir(), "updates/" + release.sha256 + ".apk");
    assertTrue(retained.getParentFile().isDirectory() || retained.getParentFile().mkdirs());
    try {
      try (var output = new java.io.FileOutputStream(retained)) {
        output.write(new byte[100]);
      }
      UpdateEngine restored =
          new UpdateEngine(
              app,
              (url, offset) -> {
                throw new AssertionError("已命名的包不能重下");
              },
              queue);
      assertFalse(restored.shouldResume());
      restored.runDownload();
      assertEquals(0, queue.size());
      assertEquals(retained, restored.state().getValue().apk);
      assertThrows(IOException.class, restored::installableApk);
      Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
      assertNull(restored.state().getValue().apk);
      assertFalse(restored.shouldResume());
    } finally {
      assertTrue(!retained.exists() || retained.delete());
    }
  }

  private static final class Queue implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    int size() {
      return tasks.size();
    }

    void runNext() {
      tasks.remove().run();
    }
  }
}
