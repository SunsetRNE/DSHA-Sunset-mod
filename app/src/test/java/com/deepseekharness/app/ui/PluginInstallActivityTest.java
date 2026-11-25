package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.plugins.PluginOperationQueue;
import com.deepseekharness.app.plugins.PluginSourceIdentity;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en-rUS", application = PluginInstallActivityTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PluginInstallActivityTest {
  private static final String QUERY =
      "?url=%40scope%2Ffixture%401.2.3&name=%40scope%2Ffixture&version=1.2.3";
  private static final String DEEP_LINK = "dsha://install" + QUERY;

  public static final class App extends DshaApp {
    private HarnessController controller;
    private PluginManagementEngine engine;

    @Override
    public void onCreate() {}

    @Override
    public HarnessController harnessController() {
      return controller;
    }

    @Override
    public PluginManagementEngine pluginManagementEngine() {
      return engine;
    }
  }

  private final List<Runnable> heldWork = new ArrayList<>();
  private App app;
  private PluginRepository repository;
  private ActivityController<PluginInstallActivity> activity;
  private boolean ready = true;
  private String previousLanguage;

  @Before
  public void configure() throws Exception {
    previousLanguage = UiText.language();
    UiText.setLanguage("en");
    app = (App) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    app.controller =
        new HarnessController(app) {
          @Override
          public boolean isEnvironmentReady() {
            return ready;
          }
        };
    var constructor =
        PluginRepository.class.getDeclaredConstructor(Application.class, Executor.class);
    constructor.setAccessible(true);
    repository = constructor.newInstance(app, (Executor) heldWork::add);
    app.engine = new PluginManagementEngine(app, repository);
  }

  @After
  public void close() {
    if (activity != null) activity.close();
    if (app != null && app.engine != null) app.engine.close();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void publicLinkRequiresOneExplicitInstallAfterResumeAndRecreation() {
    observeSource();
    open(DEEP_LINK, null);
    assertAwaitingInstall("@scope/fixture@1.2.3");
    activity.pause().resume().postResume();
    activity.recreate();
    settle();
    assertAwaitingInstall("@scope/fixture@1.2.3");
    assertTrue(install().performClick());
    var operation = onlyInstall();
    assertTrue(operation.pending());
    assertFalse(install().isEnabled());
    install().performClick();
    assertEquals(operation.operationId, onlyInstall().operationId);
  }

  @Test
  public void officialHttpsInstallLinkAlsoWaitsForConfirmation() {
    observeSource();
    open("https://dsha.cc/install" + QUERY, null);
    assertAwaitingInstall("@scope/fixture@1.2.3");
    assertTrue(install().performClick());
    assertTrue(onlyInstall().pending());
    assertFalse(install().isEnabled());
  }

  @Test
  public void replacementIntentAndRecreationKeepOnlyTheDisplayedSelection() throws Exception {
    observeSource();
    open(DEEP_LINK, null);
    Button previous = install();
    String origin = "https://fixture.invalid/second.zip";
    String link = "dsha://install?url=" + Uri.encode("shared package " + origin) + "&version=2.0";
    activity
        .pause()
        .newIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        .resume()
        .postResume();
    previous.performClick();
    assertAwaitingInstall(origin);
    activity.recreate();
    settle();
    assertAwaitingInstall(origin);
    assertFalse(text(R.id.link_install_details).contains("@scope/fixture"));
    assertFalse(text(R.id.link_install_details).contains("shared package"));
    assertTrue(install().performClick());
    var operation = onlyInstall();
    byte[] command = PluginSource.parse(origin).command().getBytes(StandardCharsets.UTF_8);
    assertEquals(
        "input:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(command)),
        operation.targetKey);
    assertEquals("2.0", operation.expectedVersion);
  }

  @Test
  public void installedEnvironmentWithoutAnObservedSourceWaitsForAnExplicitResolve() {
    open(DEEP_LINK, null);
    assertStatusResource(R.string.plugin_catalog_reading);
    Button retry = prepare();
    assertEquals(activity.get().getString(R.string.ui_m0212), retry.getText().toString());
    assertEquals(View.VISIBLE, retry.getVisibility());
    assertFalse(install().isEnabled());
    assertTrue(operations().isEmpty());

    repository.registry().failSource("fixture: source unreadable");
    repository.publishCatalogState();
    settle();
    assertStatusResource(R.string.plugin_catalog_failed);
    assertTrue(retry.performClick());
    settle();
    assertTrue(operations().isEmpty());

    activity.recreate();
    settle();
    assertTrue(operations().isEmpty());
    retry = prepare();
    observeSource();
    settle();
    assertEquals("", text(R.id.link_install_status));
    retry.performClick();
    assertAwaitingInstall("@scope/fixture@1.2.3");
    assertTrue(install().performClick());
    assertTrue(onlyInstall().pending());
  }

  @Test
  public void completingSetupOnlyReturnsToThePendingConfirmation() {
    observeSource();
    ready = false;
    open(DEEP_LINK, null);
    app.engine.selectionMessage("unrelated catalog message");
    settle();
    assertEquals(UiText.text("请先完成首次初始化"), text(R.id.link_install_status));
    assertEquals(View.VISIBLE, prepare().getVisibility());
    assertFalse(install().isEnabled());
    assertTrue(operations().isEmpty());
    // 初始化按钮已呈现；即使后台刚好就绪，这次点击仍只导航。
    ready = true;
    assertTrue(prepare().performClick());
    ShadowActivity actual = Shadow.extract(activity.get());
    Intent main = actual.getNextStartedActivity();
    assertEquals(MainActivity.class.getName(), main.getComponent().getClassName());
    String pending = app.getSharedPreferences("dsha-install-link", 0).getString("pending", "");
    assertEquals(DEEP_LINK, pending);
    assertTrue(activity.get().isFinishing());
    assertTrue(operations().isEmpty());
    activity.close();
    open(pending, null);
    assertAwaitingInstall("@scope/fixture@1.2.3");
    assertTrue(install().performClick());
    assertTrue(onlyInstall().pending());
  }

  @Test
  public void restoredBlankIdIsIdleAndAllowsExplicitRetry() {
    observeSource();
    Bundle saved = new Bundle();
    saved.putString("pluginOperationId", "  ");
    open(DEEP_LINK, saved);
    assertTrue(operations().isEmpty());
    assertEquals("", text(R.id.link_install_status));
    assertTrue(install().isEnabled());
    assertTrue(install().performClick());
    assertTrue(onlyInstall().pending());
  }

  @Test
  public void expiredRestoredIdWarnsAndNeverAutomaticallyReplays() {
    Bundle saved = new Bundle();
    saved.putString("pluginOperationId", "unknown-prior-install");
    open(DEEP_LINK, saved);
    assertTrue(text(R.id.link_install_status).contains("previous result"));
    assertFalse(install().isEnabled());
    assertTrue(operations().isEmpty());
    activity.recreate();
    settle();
    assertTrue(operations().isEmpty());
    assertTrue(text(R.id.link_install_status).contains("previous result"));
    assertTrue(prepare().performClick());
    settle();
    assertTrue(operations().isEmpty());
    assertTrue(text(R.id.link_install_status).contains("previous result"));
    observeSource();
    settle();
    assertTrue(operations().isEmpty());
    assertTrue(install().performClick());
    assertTrue(onlyInstall().pending());
  }

  @Test
  public void recreatedActivityTracksTheExistingOperationPreviewAndFailure() throws Exception {
    observeSource();
    open(DEEP_LINK, null);
    assertTrue(install().performClick());
    String id = onlyInstall().operationId;
    var preview = fixturePreview(id);
    assertSame(preview, app.engine.operationPreview(id));
    app.engine.selectionMessage("unrelated directory refresh");
    observeSource("fixture-next-runtime-root");
    settle();
    activity.recreate();
    settle();
    assertEquals(id, onlyInstall().operationId);
    assertTrue(text(R.id.link_install_details).contains("@scope/fixture · 1.2.3"));
    assertFalse(install().isEnabled());

    var field = PluginRepository.class.getDeclaredField("operations");
    field.setAccessible(true);
    var live = (PluginOperationQueue.Queue<?>) field.get(repository);
    assertEquals(id, live.poll().operationId);
    assertTrue(live.complete(id, PluginOperationQueue.Phase.FAILED, "Fixture download failed"));
    var publish =
        PluginRepository.class.getDeclaredMethod(
            "publish", String.class, boolean.class, int.class, int.class);
    publish.setAccessible(true);
    publish.invoke(
        repository, "unrelated catalog message", false, -1, PluginRepository.State.RESULT_FAILURE);
    settle();
    assertEquals("Fixture download failed", text(R.id.link_install_status));
    assertTrue(install().isEnabled());
    assertTrue(operations().stream().noneMatch(PluginOperationQueue.Operation::pending));
    assertTrue(install().performClick());
    assertTrue(operations().stream().anyMatch(row -> row.pending() && !row.operationId.equals(id)));
  }

  @Test
  public void invalidLinkKeepsButtonDisabledAndDoesNotQueue() {
    open("dsha://install?unexpected=ignored", null);
    assertFalse(install().isEnabled());
    assertFalse(text(R.id.link_install_status).isEmpty());
    assertTrue(operations().isEmpty());
  }

  @Test
  public void optionalOperationLookupReturnsNoOperationAndNoPreview() {
    for (String id : new String[] {null, "", "  ", "unknown"}) {
      assertNull(app.engine.operation(id));
      assertNull(app.engine.operationPreview(id));
    }
  }

  private PluginRepository.Preview fixturePreview(String id) throws Exception {
    var constructor = PluginRepository.Preview.class.getDeclaredConstructor(JSONObject.class);
    constructor.setAccessible(true);
    var item = new JSONObject().put("name", "@scope/fixture").put("version", "1.2.3");
    var preview =
        constructor.newInstance(
            new JSONObject()
                .put("previewId", "1".repeat(32))
                .put("confirmationSha256", "2".repeat(64))
                .put("items", new JSONArray().put(item)));
    var remember =
        PluginRepository.class.getDeclaredMethod(
            "rememberPreview", String.class, PluginRepository.Preview.class);
    remember.setAccessible(true);
    remember.invoke(repository, id, preview);
    return preview;
  }

  private void observeSource() {
    observeSource("fixture-runtime-root");
  }

  private void observeSource(String runtimeRootKey) {
    var files = app.getFilesDir();
    repository
        .registry()
        .updateSource(
            new PluginSourceIdentity(
                files,
                "fixture-data-root",
                1,
                new File(files, "linux/ubuntu"),
                runtimeRootKey,
                1,
                "fixture-runtime-marker",
                app.controller.getWebGeneration()));
    repository.publishCatalogState();
  }

  private void open(String url, Bundle saved) {
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    activity = Robolectric.buildActivity(PluginInstallActivity.class, intent);
    if (saved == null) activity.setup();
    else activity.setup(saved);
    activity.visible();
    settle();
  }

  private List<PluginOperationQueue.Operation> operations() {
    return app.engine.state().getValue().operations;
  }

  private void assertAwaitingInstall(String source) {
    assertTrue(operations().isEmpty());
    assertEquals(View.GONE, prepare().getVisibility());
    assertEquals(View.VISIBLE, install().getVisibility());
    assertTrue(install().isEnabled());
    assertEquals(activity.get().getString(R.string.ui_m0106), install().getText().toString());
    assertTrue(text(R.id.link_install_details).contains(source));
  }

  private PluginOperationQueue.Operation onlyInstall() {
    assertEquals(1, operations().size());
    var result = operations().get(0);
    assertEquals(PluginOperationQueue.Type.INSTALL, result.type);
    return result;
  }

  private static void settle() {
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(300));
  }

  private Button install() {
    return activity.get().findViewById(R.id.link_install_confirm);
  }

  private Button prepare() {
    return activity.get().findViewById(R.id.link_install_prepare);
  }

  private void assertStatusResource(int id) {
    assertEquals(activity.get().getString(id), text(R.id.link_install_status));
  }

  private String text(int id) {
    return ((TextView) activity.get().findViewById(id)).getText().toString();
  }
}
