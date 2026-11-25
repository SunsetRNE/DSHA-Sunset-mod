package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.plugins.PluginOperationQueue;
import com.deepseekharness.app.plugins.PluginRegistry;
import com.deepseekharness.app.plugins.PluginSourceIdentity;
import com.deepseekharness.app.util.PluginSort;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.TextLayoutMode;
import org.robolectric.shadows.ShadowDialog;

/** Standard 的真实 XML、Fragment、RecyclerView 和 DiffUtil 回归；只隔离数据与执行入口。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi",
    application = PluginFragmentRenderingTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PluginFragmentRenderingTest {
  private static final int WIDTH = 400, HEIGHT = 800;
  private static final String PAGE_TAG = "plugin-rendering";
  private static final PluginSourceIdentity SOURCE =
      new PluginSourceIdentity(
          new File("/fixture/plugin-rendering/data"),
          "memory-data",
          0,
          new File("/fixture/plugin-rendering/rootfs"),
          "memory-rootfs",
          0,
          "fixture",
          1);

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class FixtureHost extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  public static final class Fixture extends ViewModel {
    final MutableLiveData<PluginRepository.State> states = new MutableLiveData<>();
    PluginManagementEngine updateEngine;
    boolean safeMode;
    int watchStarts, watchStops, refreshes, itemsAtWatchStart;
  }

  /** 与调试 Page 使用相同四个边界，生产布局、render、Adapter 和页面状态均不覆写。 */
  public static final class FixtureFragment extends PluginFragment {
    @Override
    PluginManagementEngine managementEngine() {
      return new ViewModelProvider(requireActivity()).get(Fixture.class).updateEngine;
    }

    @Override
    LiveData<PluginRepository.State> installedState() {
      return new ViewModelProvider(requireActivity()).get(Fixture.class).states;
    }

    @Override
    boolean safeModeActive() {
      return new ViewModelProvider(requireActivity()).get(Fixture.class).safeMode;
    }

    @Override
    Runnable watchInstalledState() {
      var fixture = new ViewModelProvider(requireActivity()).get(Fixture.class);
      fixture.watchStarts++;
      fixture.itemsAtWatchStart =
          ((RecyclerView) find(R.id.pluginList)).getAdapter().getItemCount();
      return () -> fixture.watchStops++;
    }

    @Override
    void refreshInstalledState() {
      new ViewModelProvider(requireActivity()).get(Fixture.class).refreshes++;
    }
  }

  public static final class OtherFragment extends Fragment {
    public OtherFragment() {
      super(android.R.layout.simple_list_item_1);
    }
  }

  private ActivityController<FixtureHost> host;
  private FixtureFragment page;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("en");
    new ConfigStore(RuntimeEnvironment.getApplication()).setPluginSort(PluginSort.Mode.NAME_ASC);
    android.content.pm.ActivityInfo activity = new android.content.pm.ActivityInfo();
    activity.name = FixtureHost.class.getName();
    activity.packageName = RuntimeEnvironment.getApplication().getPackageName();
    activity.applicationInfo = RuntimeEnvironment.getApplication().getApplicationInfo();
    activity.theme = R.style.Theme_DeepseekHarness;
    org.robolectric.shadows.ShadowPackageManager packageManager =
        org.robolectric.shadow.api.Shadow.extract(
            RuntimeEnvironment.getApplication().getPackageManager());
    packageManager.addOrUpdateActivity(activity);
    host = Robolectric.buildActivity(FixtureHost.class).setup().visible();
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void unknownLoadingFailedAndConfirmedEmptyRenderDifferentStates() throws Exception {
    PluginRegistry registry = new PluginRegistry();
    mount(state(registry.snapshot(), List.of(), false, "", -1));
    assertEmptyText("The plugin list is unconfirmed. Refresh to check.");
    assertEquals(View.GONE, page.find(R.id.pluginCount).getVisibility());
    assertEquals("Plugin list unconfirmed", text(page.find(R.id.pluginCatalogPhase)));
    assertEquals(2, adapter().getItemCount());

    PluginRegistry.Query query = registry.beginQuery(SOURCE);
    publish(state(registry.snapshot(), List.of(), false, "", -1));
    assertEmptyText("Reading plugins…");
    assertEquals(View.GONE, page.find(R.id.pluginCount).getVisibility());
    assertEquals("Reading plugin list…", text(page.find(R.id.pluginCatalogPhase)));

    assertTrue(registry.failQuery(query, "fixture read failure"));
    publish(state(registry.snapshot(), List.of(), false, "fixture read failure", -1));
    assertEmptyText("Could not read plugins. Check the status and refresh to retry.");
    assertEquals("Could not verify plugin list", text(page.find(R.id.pluginCatalogPhase)));
    assertEquals("fixture read failure", text(page.find(R.id.statusText)));

    assertTrue(registry.completeQuery(registry.beginQuery(SOURCE), List.of(), List.of(), false));
    publish(state(registry.snapshot(), List.of(), false, "", -1));
    assertEmptyText("No plugins installed");
    assertEquals(
        host.get().getResources().getQuantityString(R.plurals.plugin_count, 0, 0),
        text(page.find(R.id.pluginCount)));
    assertEquals(2, adapter().getItemCount());
    assertEquals(0, adapter().getItemViewType(0));
    assertEquals(2, adapter().getItemViewType(1));
  }

  @Test
  public void checkUpdatesHeaderQueuesAllPluginsThroughTheRealEngine() throws Exception {
    List<Runnable> worker = new ArrayList<>();
    PluginRepository repository = updateCheckRepository(worker);
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      mount(state(catalog(List.of(item("alpha"))), List.of(), false, "", -1));

      View button = page.find(R.id.btnPluginUpdates);
      assertEquals(View.VISIBLE, button.getVisibility());
      assertTrue(button.performClick());

      var operations = repository.state().getValue().operations;
      assertEquals(1, worker.size());
      assertEquals(1, operations.size());
      assertEquals(PluginOperationQueue.Type.CHECK_UPDATES, operations.get(0).type);
      assertEquals("scope:web:CHECK_UPDATES", operations.get(0).targetKey);
      assertEquals(PluginOperationQueue.Phase.QUEUED, operations.get(0).phase);
      assertTrue(page.isResumed());
    }
  }

  @Test
  public void fullUpdateResultRemainsVisibleAfterAutomaticCatalogRefresh() throws Exception {
    PluginRepository repository = updateCheckRepository(new ArrayList<>());
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      var catalog = catalog(List.of(item("alpha")));
      mount(state(catalog, List.of(), false, "", -1));

      assertTrue(page.find(R.id.btnPluginUpdates).performClick());
      var request = engine.state().getValue().operations.get(0);
      String result = "Checked 1 third-party plugin, 1 update available";
      var completed = updateCheckResult(request, PluginOperationQueue.Phase.SUCCEEDED, result);
      publish(updateCheckState(catalog, List.of(completed), result));
      assertTrue(text(updateCheckReceipt()).contains(result));

      publish(
          updateCheckState(
              catalog, List.of(completed), "Automatic plugin catalog refresh completed"));
      assertTrue(text(page.find(R.id.statusText)).contains("catalog refresh completed"));
      assertTrue(
          "a directory result must not replace the manual update check",
          text(updateCheckReceipt()).contains(result));
      assertEquals("All · " + result, text(updateCheckReceipt()));
      assertTrue(updateCheckReceipt().performClick());
      AlertDialog details = (AlertDialog) ShadowDialog.getLatestDialog();
      assertEquals("All\n\n" + result, text(details.findViewById(android.R.id.message)));
      details.dismiss();
    }
  }

  @Test
  public void updateResultSurvivesLayoutCoalescingAndViewRecreation() throws Exception {
    PluginRepository repository = updateCheckRepository(new ArrayList<>());
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      var catalog = catalog(List.of(item("alpha")));
      mount(state(catalog, List.of(), false, "", -1));
      assertTrue(page.find(R.id.btnPluginUpdates).performClick());
      var request = engine.state().getValue().operations.get(0);
      String result = "Checked 1 third-party plugin, 0 updates available";
      var completed = updateCheckResult(request, PluginOperationQueue.Phase.SUCCEEDED, result);
      var terminal = updateCheckState(catalog, List.of(completed), result);
      var catalogOnly = updateCheckState(catalog, List.of(), "Catalog refresh completed");
      runDuringLayout(
          useLayoutCallback(),
          () -> {
            fixture().states.setValue(terminal);
            fixture().states.setValue(catalogOnly);
          });
      layout();
      assertEquals("All · " + result, text(updateCheckReceipt()));

      var previous = page;
      host.recreate();
      page = (FixtureFragment) host.get().getSupportFragmentManager().findFragmentByTag(PAGE_TAG);
      assertNotSame(previous, page);
      layout();
      assertEquals("All · " + result, text(updateCheckReceipt()));
    }
  }

  @Test
  public void laterChecksClearTheEarlierResultAndUseTheirOwnTerminalPhase() throws Exception {
    PluginRepository repository = updateCheckRepository(new ArrayList<>());
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      var alpha =
          new PluginRepository.Item(
              new JSONObject().put("name", "alpha").put("available", true).put("updatable", true));
      var catalog = catalog(List.of(alpha));
      mount(state(catalog, List.of(), false, "", -1));

      assertTrue(page.find(R.id.btnPluginUpdates).performClick());
      var first = engine.state().getValue().operations.get(0);
      var firstResult =
          updateCheckResult(first, PluginOperationQueue.Phase.SUCCEEDED, "One update available");
      publish(updateCheckState(catalog, List.of(firstResult), firstResult.message));
      assertEquals("All · One update available", text(updateCheckReceipt()));

      layout();
      assertTrue(rowAt(1).findViewById(R.id.pluginActions).performClick());
      chooseDrawerEntry(UiText.text("检查插件更新"));
      var single =
          engine.state().getValue().operations.stream()
              .filter(operation -> operation.targetKey.equals(alpha.stableKey))
              .findFirst()
              .orElseThrow();
      assertNotEquals(first.operationId, single.operationId);
      assertEquals(View.GONE, updateCheckReceipt().getVisibility());
      publish(updateCheckState(catalog, List.of(firstResult, single), "Catalog refresh completed"));
      assertEquals(View.GONE, updateCheckReceipt().getVisibility());

      var failure =
          updateCheckResult(single, PluginOperationQueue.Phase.FAILED, "Network request failed");
      publish(
          updateCheckState(catalog, List.of(firstResult, failure), "Catalog refresh completed"));
      assertEquals("alpha · Failed · Network request failed", text(updateCheckReceipt()));

      assertTrue(page.find(R.id.btnPluginUpdates).performClick());
      var last =
          engine.state().getValue().operations.stream()
              .filter(
                  operation ->
                      !operation.operationId.equals(first.operationId)
                          && !operation.operationId.equals(single.operationId))
              .findFirst()
              .orElseThrow();
      assertEquals(View.GONE, updateCheckReceipt().getVisibility());
      var cancelled =
          updateCheckResult(
              last, PluginOperationQueue.Phase.CANCELLED, "PLUGIN_OPERATION_CANCELLED");
      publish(
          updateCheckState(
              catalog, List.of(firstResult, failure, cancelled), "Catalog refresh completed"));
      assertEquals("All · Cancelled", text(updateCheckReceipt()));
    }
  }

  @Test
  public void rejectedCheckImmediatelyReplacesTheEarlierSuccess() throws Exception {
    PluginRepository repository = updateCheckRepository(new ArrayList<>());
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      var catalog = catalog(List.of(item("alpha")));
      mount(state(catalog, List.of(), false, "", -1));
      assertTrue(page.find(R.id.btnPluginUpdates).performClick());
      var first = engine.state().getValue().operations.get(0);
      var firstResult =
          updateCheckResult(first, PluginOperationQueue.Phase.SUCCEEDED, "One update available");
      publish(updateCheckState(catalog, List.of(firstResult), firstResult.message));
      assertEquals("All · One update available", text(updateCheckReceipt()));

      for (int index = 0; index < PluginOperationQueue.DEFAULT_WAITING_CAPACITY; index++) {
        engine.checkAllUpdates();
      }
      assertTrue(page.find(R.id.btnPluginUpdates).performClick());
      assertTrue(text(updateCheckReceipt()).startsWith("All · Rejected · "));
      assertTrue(text(updateCheckReceipt()).endsWith(UiText.text("插件任务队列已满，请等待已有任务完成")));
      assertFalse(text(updateCheckReceipt()).contains(firstResult.message));
    }
  }

  private static PluginRepository updateCheckRepository(List<Runnable> work) throws Exception {
    Application app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    var constructor =
        PluginRepository.class.getDeclaredConstructor(Application.class, Executor.class);
    constructor.setAccessible(true);
    return constructor.newInstance(app, (Executor) work::add);
  }

  @Test
  public void safeModeRestoreConfirmationQueuesRealOperationWithoutCallback() throws Exception {
    PluginRepository repository = updateCheckRepository(new ArrayList<>());
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      fixture().safeMode = true;
      mount(state(catalog(List.of(item("alpha"))), List.of(), false, "", -1));
      String enabled = engine.safeMode(true, () -> {});
      assertEquals(Boolean.TRUE, engine.operation(enabled).desired);
      View restore = page.find(R.id.btnPluginRestore);
      assertEquals(View.VISIBLE, restore.getVisibility());
      assertTrue(restore.performClick());
      AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
      assertTrue(confirmation.isShowing());
      assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
      org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
      var restoreRequest =
          engine.state().getValue().operations.stream()
              .filter(
                  operation ->
                      operation.type == PluginOperationQueue.Type.SAFE_MODE
                          && Boolean.FALSE.equals(operation.desired))
              .findFirst()
              .orElseThrow();
      assertTrue(restoreRequest.pending());
      assertNotEquals(enabled, restoreRequest.operationId);
      assertFalse(confirmation.isShowing());
    }
  }

  @Test
  public void manuallyEnteredLinkQueuesNormalNativePluginInstall() throws Exception {
    PluginRepository repository = updateCheckRepository(new ArrayList<>());
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      mount(state(catalog(List.of()), List.of(), false, "", -1));
      assertTrue(page.find(R.id.btnMarket).performClick());
      layout();
      ((EditText) page.find(R.id.appbar_github_input)).setText("@scope/fixture@1.2.3");
      View install = page.find(R.id.btnPluginInstall);
      assertTrue(install.isEnabled());
      assertTrue(install.performClick());
      var operations = engine.state().getValue().operations;
      assertEquals(1, operations.size());
      assertEquals(PluginOperationQueue.Type.INSTALL, operations.get(0).type);
      assertTrue(operations.get(0).pending());
    }
  }

  private static PluginOperationQueue.Operation updateCheckResult(
      PluginOperationQueue.Operation request, PluginOperationQueue.Phase phase, String message) {
    return new PluginOperationQueue.Operation(
        request.operationId,
        request.type,
        request.targetKey,
        request.desired,
        request.expectedVersion,
        request.source,
        phase,
        message,
        -1,
        null,
        null,
        request.outcome);
  }

  private static PluginRepository.State updateCheckState(
      PluginRegistry.Snapshot catalog,
      List<PluginOperationQueue.Operation> operations,
      String message)
      throws Exception {
    var constructor =
        PluginRepository.State.class.getDeclaredConstructor(
            PluginRegistry.Snapshot.class,
            List.class,
            boolean.class,
            String.class,
            boolean.class,
            int.class,
            int.class,
            com.deepseekharness.app.util.PluginManagementView.class,
            List.class,
            boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        catalog,
        operations,
        false,
        message,
        false,
        -1,
        PluginRepository.State.RESULT_SUCCESS,
        null,
        List.of(),
        false);
  }

  private View updateCheckReceipt() {
    int id;
    try {
      id = R.id.class.getField("pluginUpdateCheckResult").getInt(null);
    } catch (ReflectiveOperationException baseline) {
      return page.find(R.id.statusText);
    }
    View receipt = page.find(id);
    return receipt == null ? page.find(R.id.statusText) : receipt;
  }

  @Test
  public void cachedRowsAppearBeforeWatchAndRemainInteractiveDuringRecheck() throws Exception {
    var items = List.of(item("alpha"), item("beta"));
    var registry = new PluginRegistry();
    assertTrue(registry.completeQuery(registry.beginQuery(SOURCE), items, List.of(), false));
    mount(state(registry.snapshot(), List.of(), false, "", -1));
    assertEquals(4, fixture().itemsAtWatchStart);
    assertEquals(1, fixture().watchStarts);
    assertEquals("Plugin list verified", text(page.find(R.id.pluginCatalogPhase)));
    assertTrue(page.find(R.id.btnMarket).performClick());
    assertTrue(page.find(R.id.btnInstalled).performClick());
    layout();
    assertEquals(1, fixture().watchStarts);
    assertEquals(0, fixture().watchStops);
    Notifications observer = observe();
    assertTrue(page.find(R.id.btnRefresh).performClick());
    assertEquals(1, fixture().refreshes);

    var query = registry.beginQuery(SOURCE);
    publish(configurationState(registry.snapshot(), List.of(), true));
    observer.assertNone();
    assertEquals(
        "Checking plugins… · Showing the previous plugin list",
        text(page.find(R.id.pluginCatalogPhase)));
    assertEquals(View.VISIBLE, page.find(R.id.pluginCount).getVisibility());
    assertTrue(page.find(R.id.btnRefresh).isEnabled());
    assertEquals(View.INVISIBLE, page.find(R.id.pluginBusy).getVisibility());
    assertTrue(rowAt(1).findViewById(R.id.pluginSwitch).isEnabled());
    ((android.widget.EditText) page.find(R.id.pluginSearch)).setText("beta");
    layout();
    assertEquals(List.of("beta"), renderedNames());
    observer.clear();

    assertTrue(registry.failQuery(query, "fixture read failure"));
    publish(state(registry.snapshot(), List.of(), false, "fixture read failure", -1));
    observer.assertNone();
    assertEquals(
        "Could not verify plugin list · Showing the previous plugin list",
        text(page.find(R.id.pluginCatalogPhase)));
    assertEquals(List.of("beta"), renderedNames());
    assertTrue(registry.completeQuery(registry.beginQuery(SOURCE), items, List.of(), false));
    publish(state(registry.snapshot(), List.of(), false, "", -1));
    observer.assertNone();
    assertEquals("Plugin list verified", text(page.find(R.id.pluginCatalogPhase)));

    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new OtherFragment())
        .commitNow();
    assertEquals(1, fixture().watchStops);
  }

  @Test
  public void configurationPreviewHasNoMutationControlsAndYieldsToVerifiedRows() throws Exception {
    var registry = new PluginRegistry();
    var configured =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "alpha")
                .put("available", true)
                .put("enabled", true)
                .put("deletable", true));
    mount(configurationState(registry.snapshot(), List.of(configured), true));
    assertEquals(
        "Plugin check queued · From configuration · verification pending",
        text(page.find(R.id.pluginCatalogPhase)));
    assertEquals(View.GONE, page.find(R.id.pluginCount).getVisibility());
    assertFalse(page.find(R.id.btnPluginUpdates).isEnabled());
    assertEquals(List.of("alpha"), renderedNames());
    View preview = rowAt(1);
    assertNull(preview.findViewById(R.id.pluginSwitch));
    assertNull(preview.findViewById(R.id.pluginDelete));
    assertNull(preview.findViewById(R.id.pluginActions));

    var query = registry.beginQuery(SOURCE);
    publish(configurationState(registry.snapshot(), List.of(configured), true));
    assertEquals(
        "Reading plugin list… · From configuration · verification pending",
        text(page.find(R.id.pluginCatalogPhase)));
    assertEquals(List.of("alpha"), renderedNames());
    assertTrue(registry.completeQuery(query, List.of(configured, item("beta")), List.of(), false));
    publish(state(registry.snapshot(), List.of(), false, "", -1));
    assertEquals("Plugin list verified", text(page.find(R.id.pluginCatalogPhase)));
    assertEquals(View.VISIBLE, page.find(R.id.pluginCount).getVisibility());
    assertTrue(page.find(R.id.btnPluginUpdates).isEnabled());
    assertEquals(List.of("alpha", "beta"), renderedNames());
    assertTrue(rowAt(1).findViewById(R.id.pluginSwitch).isEnabled());

    registry.updateSource(
        new PluginSourceIdentity(
            new File("/changed-data"),
            "changed-key",
            0,
            SOURCE.rootfs,
            "root-key",
            0,
            "marker",
            2));
    publish(state(registry.snapshot(), List.of(), false, "", -1));
    assertEquals(2, adapter().getItemCount());
    assertEquals("Plugin list unconfirmed", text(page.find(R.id.pluginCatalogPhase)));
  }

  @Test
  public void damagedPluginShowsRecoveryGuidanceAndKeepsDeleteAvailable() throws Exception {
    var damaged =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "damaged-plugin")
                .put("available", true)
                .put("deletable", true)
                .put("metadataError", "PLUGIN_METADATA_UNREADABLE"));
    mount(state(catalog(List.of(damaged)), List.of(), false, "", -1));
    assertTrue(text(rowAt(1).findViewById(R.id.pluginDesc)).contains("Metadata read failed"));
    assertTrue(
        String.valueOf(rowAt(1).findViewById(R.id.pluginStatus).getContentDescription())
            .contains("Plugin metadata unavailable"));
    assertEquals(View.VISIBLE, rowAt(1).findViewById(R.id.pluginDelete).getVisibility());
  }

  @Test
  public void temporaryPluginPointsToItsWebSessionAndNeverShowsPackageDeletion() throws Exception {
    var temporary =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "temporary-plugin")
                .put("dynamic", true)
                .put("deletable", true)
                .put("sessionId", "fixture-session")
                .put("pluginId", "fixture-plugin"));
    mount(state(catalog(List.of(temporary)), List.of(), false, "", -1));
    assertTrue(text(rowAt(1).findViewById(R.id.pluginDesc)).contains("Cordis plugin management"));
    assertEquals(View.GONE, rowAt(1).findViewById(R.id.pluginDelete).getVisibility());
  }

  @Test
  public void runtimeEvidenceRefreshKeepsRowsAndHidesUnobservedCurrentState() throws Exception {
    var alpha = item("alpha").withRuntime("loaded", 1);
    mount(state(catalog(List.of(alpha, item("beta"))), List.of(), false, "", -1));
    assertEquals("Loaded", text(rowAt(1).findViewById(R.id.pluginRuntime)));
    publish(
        state(
            catalog(List.of(alpha.withRuntime("loaded", 1, true), item("beta"))),
            List.of(),
            false,
            "",
            -1));
    assertNoRuntimeBadge(rowAt(1));
    for (String runtime :
        List.of("pending", "failed", "loaded", "loading", "unloading", "stopped")) {
      publish(
          state(
              catalog(List.of(alpha.withRuntime(runtime, 1), item("beta"))),
              List.of(),
              false,
              "",
              -1));
      String label =
          switch (runtime) {
            case "pending" -> "Waiting";
            case "failed" -> "Failed";
            case "loading" -> "Loading";
            case "unloading" -> "Unloading";
            case "stopped" -> "Not loaded";
            default -> "Loaded";
          };
      TextView badge = rowAt(1).findViewById(R.id.pluginRuntime);
      assertEquals(View.VISIBLE, badge.getVisibility());
      assertEquals(label, text(badge));
      assertEquals("Current Web: " + label, badge.getContentDescription());
      assertEquals(List.of("alpha", "beta"), renderedNames());
    }
    var historical =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "alpha")
                .put("available", true)
                .put("enabled", true)
                .put("version", "1.0")
                .put("loadState", "loaded"));
    publish(state(catalog(List.of(historical)), List.of(), false, "", -1));
    assertNoRuntimeBadge(rowAt(1));
  }

  @Test
  public void badgesSeparateSourceVersionConfigAndRuntimeTones() throws Exception {
    var loaded =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "a-loaded")
                .put("available", true)
                .put("enabled", true)
                .put("runtimeProvided", true)
                .put("version", "0.2.2-rc.1")
                .put("runtimeState", "loaded")
                .put("runtimeStale", false));
    var disabled =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "b-disabled")
                .put("available", true)
                .put("version", "1.0")
                .put("runtimeState", "stopped")
                .put("runtimeStale", false));
    var unconfirmed =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "c-unconfirmed")
                .put("available", true)
                .put("enabled", true)
                .put("version", "1.0")
                .put("runtimeState", "unknown"));
    mount(state(catalog(List.of(loaded, disabled, unconfirmed)), List.of(), false, "", -1));

    View loadedRow = rowAt(1);
    assertBadge(
        loadedRow,
        R.id.pluginBadge,
        "Provided by DSH",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    assertBadge(
        loadedRow,
        R.id.pluginVersion,
        "0.2.2-rc.1",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    assertBadge(
        loadedRow, R.id.pluginStatus, "Enabled", R.color.shell_good, badgeFill(R.color.shell_good));
    String loadedRuntime = text(loadedRow.findViewById(R.id.pluginRuntime));
    assertEquals("Loaded", loadedRuntime);
    assertBadge(
        loadedRow,
        R.id.pluginRuntime,
        loadedRuntime,
        R.color.shell_good,
        badgeFill(R.color.shell_good));

    View disabledRow = rowAt(2);
    assertBadge(
        disabledRow,
        R.id.pluginBadge,
        "Third-party",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    assertBadge(
        disabledRow,
        R.id.pluginStatus,
        "Disabled",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    String unloadedRuntime = text(disabledRow.findViewById(R.id.pluginRuntime));
    assertEquals("Not loaded", unloadedRuntime);
    assertBadge(
        disabledRow,
        R.id.pluginRuntime,
        unloadedRuntime,
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));

    View unconfirmedRow = rowAt(3);
    assertBadge(
        unconfirmedRow,
        R.id.pluginStatus,
        "Enabled",
        R.color.shell_good,
        badgeFill(R.color.shell_good));
    assertNoRuntimeBadge(unconfirmedRow);
  }

  @Test
  public void restartRequirementRendersWarningChipBesidesTheFourBadges() throws Exception {
    var pending =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "restart-plugin")
                .put("available", true)
                .put("enabled", true)
                .put("version", "1.0")
                .put("runtimeState", "loaded")
                .put("runtimeStale", false)
                .put("restartRequired", true));
    mount(state(catalog(List.of(pending)), List.of(), false, "", -1));
    View row = rowAt(1);
    for (int id :
        new int[] {R.id.pluginBadge, R.id.pluginVersion, R.id.pluginStatus, R.id.pluginRuntime})
      assertEquals(View.VISIBLE, row.findViewById(id).getVisibility());

    TextView chipText = row.findViewById(R.id.pluginChipText);
    assertNotNull("重启待办必须渲染为警示胶囊", chipText);
    assertEquals("Restart Web to apply.", text(chipText));
    assertEquals(View.VISIBLE, row.findViewById(R.id.pluginNotes).getVisibility());
    assertEquals(
        page.requireContext().getColor(R.color.shell_warn), chipText.getCurrentTextColor());
    View chip = (View) chipText.getParent();
    assertNotNull(chip.getBackgroundTintList());
    assertEquals(badgeFill(R.color.shell_warn), chip.getBackgroundTintList().getDefaultColor());
  }

  @Test
  public void headerOnlyStateChangesRenderWithoutAnyAdapterNotification() throws Exception {
    PluginRegistry.Snapshot catalog = catalog(List.of(item("alpha"), item("beta")));
    mount(state(catalog, List.of(), false, "", -1));
    Notifications observer = observe();

    View strip = page.find(R.id.pluginStatusStrip);
    int idleHeight = strip.getHeight();
    assertEquals(View.VISIBLE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(View.VISIBLE, page.find(R.id.pluginCount).getVisibility());

    publish(state(catalog, List.of(), true, "fixture header progress", 37));
    assertEquals(View.VISIBLE, page.find(R.id.pluginBusy).getVisibility());
    PluginWaveProgress progress = page.find(R.id.pluginBusy);
    assertEquals(37, progress.percent());
    assertFalse(progress.indeterminate());
    assertEquals("fixture header progress", text(page.find(R.id.statusText)));
    // 同一条状态条里只有一个动作：忙碌时取消代替检查更新，计数让位。
    assertEquals(View.VISIBLE, page.find(R.id.btnCancelPluginTask).getVisibility());
    assertEquals(View.GONE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(View.GONE, page.find(R.id.pluginCount).getVisibility());
    assertEquals(View.VISIBLE, page.find(R.id.pluginStatusSpinner).getVisibility());
    assertEquals("忙碌与空闲状态条等高，不顶动列表", idleHeight, strip.getHeight());
    observer.assertNone();

    publish(state(catalog, List.of(), false, "fixture header finished", -1));
    // 没有明确结果时直接回到空闲；车道保留（INVISIBLE），状态条高度不变。
    assertEquals(View.INVISIBLE, page.find(R.id.pluginBusy).getVisibility());
    assertEquals("fixture header finished", text(page.find(R.id.statusText)));
    assertEquals(View.GONE, page.find(R.id.btnCancelPluginTask).getVisibility());
    assertEquals(View.VISIBLE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(idleHeight, strip.getHeight());
    observer.assertNone();
    assertEquals(List.of("alpha", "beta"), renderedNames());
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
  public void loadingPanelUsesLogicalStartAndCentersTheEmptyMessageAcrossScreenSettings() {
    for (Locale locale : List.of(Locale.forLanguageTag("ar-SA"), Locale.US)) {
      for (int density : new int[] {160, 320}) {
        for (float scale : new float[] {1f, 2f}) {
          Configuration config = new Configuration();
          config.setLocale(locale);
          config.densityDpi = density;
          config.fontScale = scale;
          var themed =
              new ContextThemeWrapper(
                  host.get().createConfigurationContext(config), R.style.Theme_DeepseekHarness);
          boolean rtl = config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
          var inflater = LayoutInflater.from(themed);
          View header = inflater.inflate(R.layout.plugin_list_header, null);
          header.setLayoutDirection(config.getLayoutDirection());
          View strip = header.findViewById(R.id.pluginStatusStrip);
          TextView phase = header.findViewById(R.id.pluginCatalogPhase);
          phase.setVisibility(View.VISIBLE);
          phase.setText(themed.getString(R.string.plugin_catalog_reading));
          View spinner = header.findViewById(R.id.pluginStatusSpinner);
          spinner.setVisibility(View.VISIBLE);
          TextView status = header.findViewById(R.id.statusText);
          status.setText("DSH: Checking plugin entries from the previous list");
          TextView refresh = header.findViewById(R.id.btnRefresh);
          header.findViewById(R.id.installedControls).setVisibility(View.VISIBLE);
          header.findViewById(R.id.pluginMarketCard).setVisibility(View.GONE);
          View footer = inflater.inflate(R.layout.plugin_list_footer, null);
          footer.setLayoutDirection(config.getLayoutDirection());
          TextView empty = footer.findViewById(R.id.pluginEmpty);
          empty.setVisibility(View.VISIBLE);
          empty.setText(themed.getString(R.string.plugin_catalog_reading));
          int width = 320 * density / 160;
          for (View view : List.of(header, footer)) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            view.layout(0, 0, width, view.getMeasuredHeight());
          }
          android.graphics.Rect phaseBounds = new android.graphics.Rect();
          android.graphics.Rect statusBounds = new android.graphics.Rect();
          phase.getDrawingRect(phaseBounds);
          status.getDrawingRect(statusBounds);
          var stripGroup = (android.view.ViewGroup) strip;
          stripGroup.offsetDescendantRectToMyCoords(phase, phaseBounds);
          stripGroup.offsetDescendantRectToMyCoords(status, statusBounds);
          assertEquals(
              "phase and diagnostic start",
              rtl ? phaseBounds.right : phaseBounds.left,
              rtl ? statusBounds.right : statusBounds.left,
              1);
          var statusLayout = status.getLayout();
          float alignedEdge = rtl ? statusLayout.getLineRight(0) : statusLayout.getLineLeft(0);
          var emptyLayout = empty.getLayout();
          float textCenter = (emptyLayout.getLineLeft(0) + emptyLayout.getLineRight(0)) / 2;
          System.out.printf(
              Locale.ROOT,
              "loadingUI %s density=%d scale=%.1f headingTextPx=%.1f phaseStart=%d statusStart=%d"
                  + " diagnostic=%.1f/%d emptyCenter=%.1f/%d headingLines=%d%n",
              locale.toLanguageTag(),
              density,
              scale,
              phase.getTextSize(),
              rtl ? phaseBounds.right : phaseBounds.left,
              rtl ? statusBounds.right : statusBounds.left,
              alignedEdge,
              statusLayout.getWidth(),
              textCenter,
              emptyLayout.getWidth(),
              phase.getLineCount());
          assertEquals(
              "mixed direction diagnostic", rtl ? statusLayout.getWidth() : 0, alignedEdge, 1);
          assertEquals(
              "empty message horizontal center", emptyLayout.getWidth() / 2f, textCenter, 1);
          assertEquals(
              "empty message vertical center",
              empty.getTotalPaddingTop(),
              empty.getTotalPaddingBottom(),
              1);
          assertEquals(View.TEXT_ALIGNMENT_CENTER, refresh.getTextAlignment());
          assertTrue(phase.getIncludeFontPadding());
          assertTrue(empty.getIncludeFontPadding());
          assertTrue(
              "large font scale is applied",
              scale == 1f || phase.getTextSize() > 13f * density / 160);
        }
      }
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
  public void catalogStatusShowsWordsOnBothLinesAndRetainsTheCompleteMessage() throws Exception {
    PluginRepository repository = updateCheckRepository(new ArrayList<>());
    try (var engine = new PluginManagementEngine(repository.getApplication(), repository)) {
      fixture().updateEngine = engine;
      var catalog = catalog(List.of(item("alpha")));
      var management =
          new com.deepseekharness.app.util.PluginManagementView(
              "unavailable",
              "MANAGEMENT_SERVICE_ABSENT",
              "SERVICE_PRESENCE",
              "unavailable",
              "PATCH_PROVENANCE_UNVERIFIED",
              "STARTUP_METADATA",
              false,
              List.of(),
              10);
      var constructor =
          PluginRepository.State.class.getDeclaredConstructor(
              PluginRegistry.Snapshot.class,
              List.class,
              boolean.class,
              String.class,
              boolean.class,
              int.class,
              int.class,
              com.deepseekharness.app.util.PluginManagementView.class);
      constructor.setAccessible(true);
      String checking = "正在检测 Web、终端和本地安装的插件…";
      for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("ar-SA"))) {
        boolean rtl = "ar".equals(locale.getLanguage());
        UiText.setLanguage(rtl ? "ar" : "zh");
        PluginRepository.State state =
            constructor.newInstance(
                catalog,
                List.of(),
                true,
                checking,
                true,
                -1,
                PluginRepository.State.RESULT_NONE,
                management);
        if (page == null) mount(state);
        else publish(state);
        TextView liveStatus = page.find(R.id.statusText);
        String fullMessage = PluginFragment.stateMessage(state);
        assertTrue(fullMessage.contains("\n\n"));
        for (float scale : new float[] {1f, 1.5f, 2f}) {
          Configuration config = new Configuration();
          config.setLocale(locale);
          config.densityDpi = rtl ? 320 : 160;
          config.fontScale = scale;
          var themed =
              new ContextThemeWrapper(
                  host.get().createConfigurationContext(config), R.style.Theme_DeepseekHarness);
          View header = LayoutInflater.from(themed).inflate(R.layout.plugin_list_header, null);
          header.setLayoutDirection(config.getLayoutDirection());
          TextView status = header.findViewById(R.id.statusText);
          status.setText(liveStatus.getText());
          for (int id :
              new int[] {R.id.pluginCount, R.id.btnCancelPluginTask, R.id.btnPluginUpdates}) {
            TextView copy = header.findViewById(id);
            TextView live = page.find(id);
            copy.setVisibility(live.getVisibility());
            copy.setText(live.getText());
          }
          int width = (scale == 2f ? 320 : 446) * config.densityDpi / 160;
          header.measure(
              View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
          header.layout(0, 0, width, header.getMeasuredHeight());
          android.text.Layout lines = status.getLayout();
          assertEquals(2, lines.getLineCount());
          for (int line = 0; line < lines.getLineCount(); line++) {
            int start = lines.getLineStart(line);
            int end =
                lines.getEllipsisCount(line) == 0
                    ? lines.getLineEnd(line)
                    : start + lines.getEllipsisStart(line);
            String visible = status.getText().subSequence(start, end).toString().strip();
            System.out.printf(
                Locale.ROOT,
                "statusLayout %s scale=%.1f widthPx=%d line=%d ellipsis=%d visible=%s%n",
                locale.toLanguageTag(),
                scale,
                width,
                line + 1,
                lines.getEllipsisCount(line),
                visible);
            assertTrue(
                "Status line must contain text: " + visible,
                visible.codePoints().anyMatch(Character::isLetterOrDigit));
          }
        }
        assertEquals(fullMessage, liveStatus.getContentDescription());
        assertTrue(liveStatus.performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(fullMessage, text(dialog.findViewById(android.R.id.message)));
        dialog.dismiss();
      }
    }
  }

  @Test
  public void compactHeaderMovesSourceChipAndCollapsesIdleMarketStrip() throws Exception {
    mount(state(catalog(List.of(item("alpha"), item("beta"))), List.of(), false, "", -1));
    TextView source = page.find(R.id.btnPluginDownloadSource);
    View manageSlot = page.find(R.id.pluginManageSourceSlot);
    assertSame("管理页：下载源在筛选行", manageSlot, source.getParent());
    assertEquals("Source: auto", text(source));
    assertTrue(source.getContentDescription().toString().contains("Automatic"));
    TextView installed = page.find(R.id.btnInstalled);
    assertTrue("分段带计数徽标", text(installed).startsWith("Plugin manager"));
    assertTrue(installed.getContentDescription().toString().contains("2"));
    assertEquals(View.GONE, page.find(R.id.pluginSafeBanner).getVisibility());

    assertTrue(page.find(R.id.btnMarket).performClick());
    layout();
    assertSame("市场页：下载源与解析安装同行", page.find(R.id.pluginMarketSourceSlot), source.getParent());
    assertTrue(text(source).startsWith("Download source"));
    assertEquals("市场页空闲且无消息时状态条收起", View.GONE, page.find(R.id.pluginStatusStrip).getVisibility());

    publish(
        state(
            catalog(List.of(item("alpha"), item("beta"))),
            List.of(),
            false,
            "fixture market note",
            -1));
    assertEquals(View.VISIBLE, page.find(R.id.pluginStatusStrip).getVisibility());
    assertEquals(View.GONE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(View.GONE, page.find(R.id.pluginCount).getVisibility());

    EditText link = page.find(R.id.appbar_github_input);
    android.widget.ImageButton paste = page.find(R.id.btnPluginPaste);
    assertEquals(host.get().getString(R.string.ui_m0179), paste.getContentDescription());
    link.setText("https://github.com/example/plugin");
    layout();
    assertEquals("Clear link", paste.getContentDescription());
    assertTrue(page.find(R.id.btnPluginInstall).isEnabled());
    assertTrue(paste.performClick());
    assertEquals("", text(link));
    assertFalse(page.find(R.id.btnPluginInstall).isEnabled());
  }

  @Test
  public void unrelatedOperationProgressDoesNotNotifyOrChangePluginRows() throws Exception {
    PluginRegistry.Snapshot catalog = catalog(List.of(item("alpha"), item("beta")));
    mount(state(catalog, List.of(), false, "", -1));
    String alpha = rowStatus(1);
    String beta = rowStatus(2);
    Notifications observer = observe();
    PluginOperationQueue.Queue<String> queue = new PluginOperationQueue.Queue<>();
    var operation =
        queue.enqueue(PluginOperationQueue.Type.CHECK_UPDATES, "*", null, "", SOURCE, "fixture");
    assertSame(operation, queue.poll());

    for (int percent : new int[] {5, 40, 85}) {
      assertTrue(
          queue.progress(
              operation.operationId,
              PluginOperationQueue.Phase.RUNNING,
              "global progress",
              percent,
              null));
      publish(state(catalog, queue.snapshot(), true, "global progress", percent));
      assertEquals(percent, ((PluginWaveProgress) page.find(R.id.pluginBusy)).percent());
      observer.assertNone();
      assertEquals(alpha, rowStatus(1));
      assertEquals(beta, rowStatus(2));
    }
  }

  @Test
  public void literalEntryOverrideAndAncestorBlockRemainSeparateFromBundleAndRuntime()
      throws Exception {
    var configured =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "entry-bundle")
                .put("available", true)
                .put("enabled", true)
                .put("selected", true)
                .put("installed", true)
                .put("entryReadError", "")
                .put(
                    "entries",
                    new org.json.JSONArray()
                        .put(
                            new JSONObject()
                                .put("entryId", "child")
                                .put("moduleHash", "a".repeat(64))
                                .put("parentId", "parent")
                                .put("group", false)
                                .put("patchDisabled", false)
                                .put("patchReason", "ANCESTOR_DISABLED")
                                .put("runtimePhase", "active")
                                .put("runtimeReason", "CONTENT_UNVERIFIED"))
                        .put(
                            new JSONObject()
                                .put("entryId", "known-entry")
                                .put("moduleHash", "b".repeat(64))
                                .put("parentId", "")
                                .put("group", false)
                                .put("patchReason", "PROFILE_UNSET")
                                .put("runtimePhase", "active")
                                .put("runtimeReason", ""))
                        .put(
                            new JSONObject()
                                .put("entryId", "unknown-entry")
                                .put("moduleHash", "c".repeat(64))
                                .put("parentId", "")
                                .put("group", false)
                                .put("patchReason", "ANCESTOR_UNKNOWN"))));
    String description = PluginFragment.entryConfigurationText(configured);
    assertEquals(
        "Entries (current profile)\nchild · Enabled · Parent disabled\n"
            + "known-entry · No override · Loaded\nunknown-entry",
        description);
    assertTrue(configured.enabled);
    var unavailable =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "entry-bundle")
                .put("entryReadError", "ENTRY_READER_UNAVAILABLE")
                .put("entries", new org.json.JSONArray()));
    assertTrue(PluginFragment.entryConfigurationText(unavailable).contains("Unreadable"));
  }

  @Test
  public void compatibilityDetailsSeparateNoConstraintExemptionAndFailureWithoutStrongInstall()
      throws Exception {
    for (String value :
        List.of(
            "compatible",
            "unconstrained",
            "exempted",
            "incompatible",
            "unsupported-platform",
            "check-failed")) {
      PluginRepository.Item item =
          new PluginRepository.Item(
              new JSONObject()
                  .put("name", "sample")
                  .put("compatibilityState", value)
                  .put("versionExempted", "exempted".equals(value)));
      String text = PluginFragment.compatibilityText(item);
      assertFalse(text.isEmpty());
      assertTrue(text.startsWith("Compatibility:"));
      if ("exempted".equals(value)) assertTrue(text.contains("read-only"));
    }
    assertEquals("", PluginFragment.compatibilityText(item("unknown-compatibility")));
    assertEquals(
        "",
        PluginFragment.compatibilityText(
            new PluginRepository.Item(
                new JSONObject().put("name", "sample").put("compatibilityState", "exempted"))));
  }

  @Test
  public void managementReasonsKeepFailureFirstAndDoNotDisableNativeEmergencySwitch()
      throws Exception {
    var view =
        new com.deepseekharness.app.util.PluginManagementView(
                "unavailable",
                "MANAGEMENT_SERVICE_ABSENT",
                "SERVICE_PRESENCE",
                "present-unverified",
                "PATCH_PROVENANCE_UNVERIFIED",
                "STARTUP_METADATA",
                false,
                List.of(),
                10)
            .withWatch(true, List.of("WATCH_DIRECTORY_LIMIT"));
    String notice = PluginFragment.managementNotice(view);
    assertTrue(notice.contains("management is unavailable"));
    assertTrue(notice.contains("unverified"));
    assertTrue(notice.contains("Watch-directory limit"));
    PluginRepository.Item installed =
        item("alpha").withManagementEvidence(true, "MANAGEMENT_REQUIRED", null);
    PluginRegistry.Snapshot catalog = catalog(List.of(installed));
    var constructor =
        PluginRepository.State.class.getDeclaredConstructor(
            PluginRegistry.Snapshot.class,
            List.class,
            boolean.class,
            String.class,
            boolean.class,
            int.class,
            int.class,
            com.deepseekharness.app.util.PluginManagementView.class);
    constructor.setAccessible(true);
    PluginRepository.State value =
        constructor.newInstance(
            catalog,
            List.of(),
            false,
            "fixture failure",
            false,
            -1,
            PluginRepository.State.RESULT_FAILURE,
            view);
    assertTrue(PluginFragment.stateMessage(value).startsWith("fixture failure"));
    mount(value);
    assertTrue(rowAt(1).findViewById(R.id.pluginSwitch).isEnabled());
    assertTrue(installed.enabled);
  }

  @Test
  public void operationResultsRemainVisibleWithoutChangingConfirmedIntent() throws Exception {
    PluginRepository.Item installed =
        item("alpha").withRuntime("loaded", 1).withRestartRequired(true);
    PluginRegistry.Snapshot catalog = catalog(List.of(installed));
    mount(state(catalog, List.of(), false, "", -1));
    PluginOperationQueue.Phase[] phases = {
      PluginOperationQueue.Phase.UNCONFIRMED, PluginOperationQueue.Phase.FAILED,
      PluginOperationQueue.Phase.CANCELLED, PluginOperationQueue.Phase.INVALIDATED,
      PluginOperationQueue.Phase.REJECTED, PluginOperationQueue.Phase.SUPERSEDED
    };
    for (PluginOperationQueue.Phase phase : phases) {
      PluginOperationQueue.Queue<String> queue = new PluginOperationQueue.Queue<>();
      var operation =
          queue.enqueue(
              PluginOperationQueue.Type.SET_ENABLED,
              installed.stableKey,
              false,
              installed.version,
              SOURCE,
              "fixture");
      publish(state(catalog, queue.snapshot(), true, "", -1));
      assertEquals("Queued", text(rowAt(1).findViewById(R.id.pluginStatus)));
      assertSame(operation, queue.poll());
      assertTrue(queue.complete(operation.operationId, phase, "fixture detail"));
      publish(state(catalog, queue.snapshot(), false, "", -1));
      String label = PluginFragment.operationBadgeText(phase.name());
      assertFalse(label.isEmpty());
      assertEquals(label, text(rowAt(1).findViewById(R.id.pluginStatus)));
      android.widget.LinearLayout notes = rowAt(1).findViewById(R.id.pluginNotes);
      assertTrue(text(notes.getChildAt(0).findViewById(R.id.pluginNoteText)).contains(label));
      assertTrue(
          ((android.widget.CompoundButton) rowAt(1).findViewById(R.id.pluginSwitch)).isChecked());
      assertEquals("Loaded", text(rowAt(1).findViewById(R.id.pluginRuntime)));
    }
    assertEquals("", PluginFragment.operationBadgeText("future-result"));
    assertEquals("Result unconfirmed", PluginFragment.operationBadgeText("UNCONFIRMED"));
    assertEquals("Timed out", PluginFragment.operationBadgeText("TIMEOUT"));
  }

  @Test
  public void targetProgressUsesOnlyItsPayloadAndSkipsHeaderAtEveryPosition() throws Exception {
    List<PluginRepository.Item> items = List.of(item("alpha"), item("beta"), item("gamma"));
    PluginRegistry.Snapshot catalog = catalog(items);
    mount(state(catalog, List.of(), false, "", -1));
    Notifications observer = observe();

    // 首插件和非首插件均验证；position 0 始终是真实页头。
    for (int targetPosition : new int[] {1, 2}) {
      PluginOperationQueue.Queue<String> queue = new PluginOperationQueue.Queue<>();
      var operation =
          queue.enqueue(
              PluginOperationQueue.Type.SET_ENABLED,
              items.get(targetPosition - 1).stableKey,
              false,
              "1.0",
              SOURCE,
              "fixture");
      assertSame(operation, queue.poll());
      assertTrue(
          queue.progress(
              operation.operationId,
              PluginOperationQueue.Phase.RUNNING,
              "target progress",
              10,
              null));
      publish(state(catalog, queue.snapshot(), true, "target progress", 10));
      View target = rowAt(targetPosition);
      assertTrue(rowStatus(targetPosition).contains("10%"));
      int otherPosition = targetPosition == 1 ? 2 : 1;
      String otherStatus = rowStatus(otherPosition);
      long targetId = adapter().getItemId(targetPosition);
      observer.clear();

      assertTrue(
          queue.progress(
              operation.operationId,
              PluginOperationQueue.Phase.RUNNING,
              "target progress",
              70,
              null));
      publish(state(catalog, queue.snapshot(), true, "target progress", 70));
      observer.assertSingle("change", targetPosition, 1);
      assertEquals(Integer.valueOf(PluginListPresenter.OPERATION), observer.events.get(0).payload);
      assertTrue(observer.events.get(0).position >= 1);
      assertEquals(targetId, adapter().getItemId(targetPosition));
      assertEquals(
          items.get(targetPosition - 1).name,
          text(rowAt(targetPosition).findViewById(R.id.pluginName)));
      assertTrue(rowStatus(targetPosition).contains("70%"));
      assertEquals(otherStatus, rowStatus(otherPosition));
      assertEquals(0, adapter().getItemViewType(0));
    }
  }

  @Test
  public void insertRemoveAndSortNotificationsAlsoKeepTheHeaderOffset() throws Exception {
    PluginRepository.Item alpha = item("alpha"), beta = item("beta"), first = item("aardvark");
    mount(state(catalog(List.of(alpha, beta)), List.of(), false, "", -1));
    long alphaId = adapter().getItemId(1), betaId = adapter().getItemId(2);
    Notifications observer = observe();

    publish(state(catalog(List.of(alpha, beta, first)), List.of(), false, "", -1));
    observer.assertSingle("insert", 1, 1);
    assertEquals(List.of("aardvark", "alpha", "beta"), renderedNames());
    assertEquals(alphaId, adapter().getItemId(2));
    assertEquals(betaId, adapter().getItemId(3));
    observer.clear();

    publish(state(catalog(List.of(alpha, beta)), List.of(), false, "", -1));
    observer.assertSingle("remove", 1, 1);
    assertEquals(List.of("alpha", "beta"), renderedNames());
    observer.clear();

    chooseSort(PluginSort.Mode.NAME_DESC);
    assertEquals(0, observer.fullChanges);
    assertFalse(observer.events.isEmpty());
    for (Event event : observer.events) {
      assertEquals("move", event.kind);
      assertTrue(event.position >= 1);
      assertTrue(event.destination >= 1);
    }
    assertEquals(List.of("beta", "alpha"), renderedNames());
    assertEquals(betaId, adapter().getItemId(1));
    assertEquals(alphaId, adapter().getItemId(2));
  }

  @Test
  public void layoutCallbackDefersWholeProjectionAndKeepsLatestReceiptAndAnchor() throws Exception {
    List<PluginRepository.Item> items = recoveryItems().subList(0, 12);
    mount(state(catalog(items), List.of(), false, "original footer and header state", -1));
    LayoutCallback manager = useLayoutCallback();
    manager.scrollToPositionWithOffset(5, -13);
    layout();
    long anchorId = adapter().getItemId(5);
    var observer = observe();
    var states = fixture().states;
    var progressItems = new ArrayList<>(items);
    var target = items.get(0);
    progressItems.set(0, target.withRuntime("loaded", 1));
    var intermediate = state(catalog(progressItems), List.of(), true, "intermediate progress", 40);
    var queue = new PluginOperationQueue.Queue<String>();
    var operation =
        queue.enqueue(
            PluginOperationQueue.Type.SET_ENABLED, target.stableKey, false, "1.0", SOURCE, "");
    assertSame(operation, queue.poll());
    assertTrue(
        queue.complete(
            operation.operationId, PluginOperationQueue.Phase.FAILED, "fixture receipt"));
    var finalItems = new ArrayList<>(items);
    finalItems.add(item("000-first"));
    var result = state(catalog(finalItems), queue.snapshot(), false, "latest receipt", -1);
    TextView status = page.find(R.id.statusText);
    TextView empty = page.find(R.id.pluginEmpty);
    EditText search = page.find(R.id.pluginSearch);
    CheckBox filter = page.find(R.id.chkHideBuiltin);

    runDuringLayout(
        manager,
        () -> {
          // 旧实现走与真机崩溃一致的非结构变化 notifyItemRangeChanged。
          states.setValue(intermediate);
          search.setText("not present");
          states.setValue(result);
          filter.setChecked(true);
          search.setText("keep");
          observer.assertNone();
          assertEquals(14, adapter().getItemCount());
          assertEquals("original footer and header state", text(status));
          assertEquals(View.GONE, empty.getVisibility());
        });
    observer.assertNone();
    layout();

    assertEquals(15, adapter().getItemCount());
    assertEquals(anchorId, adapter().getItemId(6));
    assertEquals(6, manager.findFirstVisibleItemPosition());
    assertEquals(
        -13, manager.getDecoratedTop(manager.findViewByPosition(6)) - list().getPaddingTop());
    assertEquals("latest receipt", text(status));
    assertEquals(View.GONE, empty.getVisibility());
    assertEquals(0, observer.fullChanges);
    assertEquals(2, observer.events.size());
    assertTrue(observer.events.stream().anyMatch(e -> e.kind.equals("insert") && e.position == 1));
    int targetPosition = observer.events.get(0).kind.equals("insert") ? 2 : 1;
    assertTrue(
        observer.events.stream()
            .anyMatch(
                e ->
                    e.kind.equals("change")
                        && e.position == targetPosition
                        && Integer.valueOf(PluginListPresenter.OPERATION).equals(e.payload)));
    assertEquals("Failed", text(rowAt(2).findViewById(R.id.pluginStatus)));
    System.out.printf(
        "layout snapshot events=%s,%s anchorOffset=-13 itemCount=15 receipt=Failed%n",
        observer.events.get(0).kind, observer.events.get(1).kind);

    observer.clear();
    runDuringLayout(
        manager,
        () -> {
          search.setText("not present");
          observer.assertNone();
          assertEquals(View.GONE, empty.getVisibility());
        });
    layout();
    observer.assertSingle("remove", 1, 13);
    assertEquals(View.VISIBLE, empty.getVisibility());
  }

  @Test
  public void detachedViewDefersUpdatesUntilAttachAndDestroyedViewCannotReplay() throws Exception {
    var old = state(catalog(List.of(item("alpha"))), List.of(), false, "old view", -1);
    var obsolete = state(catalog(List.of(item("beta"))), List.of(), false, "obsolete callback", -1);
    var newest = state(catalog(List.of(item("latest"))), List.of(), false, "latest view", -1);
    mount(old);
    LayoutCallback manager = useLayoutCallback();
    var states = fixture().states;
    var observer = observe();
    RecyclerView originalList = list();
    TextView originalStatus = page.find(R.id.statusText);
    RecyclerView.Adapter<?> originalAdapter = adapter();
    var parent = (android.view.ViewGroup) originalList.getParent();
    int index = parent.indexOfChild(originalList);
    assertTrue(originalList.isAttachedToWindow());

    runDuringLayout(manager, () -> states.setValue(obsolete));
    parent.removeView(originalList);
    states.setValue(newest);
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    observer.assertNone();
    assertEquals("old view", text(originalStatus));
    parent.addView(originalList, index);
    layout();
    assertEquals("latest view", text(originalStatus));
    assertEquals(List.of("latest"), renderedNames());

    observer.clear();
    runDuringLayout(manager, () -> states.setValue(obsolete));
    host.get().getSupportFragmentManager().beginTransaction().detach(page).commitNow();
    assertNull(originalList.getAdapter());
    states.setValue(newest);
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    observer.assertNone();
    assertEquals("latest view", text(originalStatus));
    originalAdapter.unregisterAdapterDataObserver(observer);
    host.get().getSupportFragmentManager().beginTransaction().attach(page).commitNow();
    layout();
    assertNotSame(originalList, list());
    assertEquals("latest view", text(page.find(R.id.statusText)));
    assertEquals(List.of("latest"), renderedNames());
    System.out.println("detached and destroyed view notifications=0, reattached item=latest");
  }

  private LayoutCallback useLayoutCallback() {
    LayoutCallback manager = new LayoutCallback(host.get());
    list().setLayoutManager(manager);
    layout();
    return manager;
  }

  private void runDuringLayout(LayoutCallback manager, Runnable callback) {
    RecyclerView list = list();
    manager.callback =
        () -> {
          assertTrue("the real RecyclerView must lock layout", list.isComputingLayout());
          callback.run();
        };
    list.requestLayout();
    measureAndLayout(list);
    assertNull("the LayoutManager callback must run", manager.callback);
  }

  private static final class LayoutCallback extends LinearLayoutManager {
    Runnable callback;

    LayoutCallback(android.content.Context context) {
      super(context);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
      super.onLayoutChildren(recycler, state);
      Runnable run = callback;
      callback = null;
      if (run != null) run.run();
    }
  }

  @Test
  public void newFragmentAfterAnotherPageRestoresFiltersExpansionAndStableKeyAnchor()
      throws Exception {
    List<PluginRepository.Item> items = recoveryItems();
    mount(state(catalog(items), List.of(), false, "", -1));
    configureRecoveryPage();
    FixtureFragment original = page;
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new OtherFragment())
        .commitNow();
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    assertNull(original.getView());

    // 同一动态身份改变展示名称，并在其前面插入一行；恢复不能按旧名称或旧 position 猜测。
    List<PluginRepository.Item> refreshed = new ArrayList<>(items);
    refreshed.set(7, anchorItem("plugin8-renamed"));
    refreshed.add(item("plugin13"));
    fixture().states.setValue(state(catalog(refreshed), List.of(), false, "", -1));
    page = new FixtureFragment();
    replacePage();
    assertNotSame(original, page);
    assertRecoveryControls();
    assertAnchor(6, "plugin8-renamed", -13);
    assertEquals(View.VISIBLE, rowAt(6).findViewById(R.id.pluginDetails).getVisibility());
    assertEquals(
        List.of(
            "plugin13",
            "plugin12",
            "plugin11",
            "plugin10",
            "plugin9",
            "plugin8-renamed",
            "plugin7",
            "plugin6",
            "plugin5",
            "plugin4",
            "plugin3",
            "plugin2",
            "plugin1"),
        renderedNames());
  }

  @Test
  public void savedActivityRestoresPageWithAFreshViewModelAndRealViews() throws Exception {
    PluginRepository.State ready = state(catalog(recoveryItems()), List.of(), false, "", -1);
    mount(ready);
    configureRecoveryPage();
    Fixture oldFixture = fixture();
    FixtureFragment oldPage = page;
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    host = null;
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();

    host = Robolectric.buildActivity(FixtureHost.class).create(saved);
    assertNotSame(oldFixture, fixture());
    fixture().states.setValue(ready);
    host.start().resume().visible();
    page = (FixtureFragment) host.get().getSupportFragmentManager().findFragmentByTag(PAGE_TAG);
    assertNotNull(page);
    assertNotSame(oldPage, page);
    layout();
    assertRecoveryControls();
    assertAnchor(5, "plugin8", -13);
    assertEquals(View.VISIBLE, rowAt(5).findViewById(R.id.pluginDetails).getVisibility());
    assertEquals(14, adapter().getItemCount());
  }

  private void configureRecoveryPage() {
    ((EditText) page.find(R.id.pluginSearch)).setText("keep");
    ((CheckBox) page.find(R.id.chkHideBuiltin)).setChecked(true);
    chooseSort(PluginSort.Mode.NAME_DESC);
    assertEquals(14, adapter().getItemCount());
    assertEquals("plugin12", text(rowAt(1).findViewById(R.id.pluginName)));
    assertEquals("plugin8", text(rowAt(5).findViewById(R.id.pluginName)));
    assertTrue(rowAt(5).findViewById(R.id.pluginExpand).performClick());
    layout();
    assertEquals(View.VISIBLE, rowAt(5).findViewById(R.id.pluginDetails).getVisibility());
    layoutManager().scrollToPositionWithOffset(5, -13);
    layout();
    assertAnchor(5, "plugin8", -13);
  }

  private void assertRecoveryControls() {
    assertEquals("keep", text(page.find(R.id.pluginSearch)));
    assertTrue(((CheckBox) page.find(R.id.chkHideBuiltin)).isChecked());
    assertEquals(host.get().getString(R.string.plugin_sort_za), text(page.find(R.id.btnSort)));
    assertTrue(page.find(R.id.btnInstalled).isSelected());
    assertEquals(View.VISIBLE, page.find(R.id.installedControls).getVisibility());
  }

  private void assertAnchor(int position, String name, int offset) {
    assertEquals(position, layoutManager().findFirstVisibleItemPosition());
    View anchor = layoutManager().findViewByPosition(position);
    assertNotNull(anchor);
    assertEquals(name, text(anchor.findViewById(R.id.pluginName)));
    assertEquals(offset, layoutManager().getDecoratedTop(anchor) - list().getPaddingTop());
  }

  private List<PluginRepository.Item> recoveryItems() throws Exception {
    List<PluginRepository.Item> items = new ArrayList<>();
    for (int index = 1; index <= 12; index++)
      items.add(index == 8 ? anchorItem("plugin8") : item("plugin" + index));
    items.add(
        new PluginRepository.Item(
            new JSONObject().put("name", "unrelated").put("available", true)));
    PluginRepository.Item builtin =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "dsh-task-notifier")
                .put("description", "keep builtin")
                .put("available", true)
                .put("enabled", true)
                .put("builtin", true));
    assertTrue(builtin.builtin);
    items.add(builtin);
    return items;
  }

  private static PluginRepository.Item item(String name) throws Exception {
    return new PluginRepository.Item(
        new JSONObject()
            .put("name", name)
            .put("description", "keep fixture " + name)
            .put("available", true)
            .put("enabled", true)
            .put("version", "1.0"));
  }

  private static PluginRepository.Item anchorItem(String name) throws Exception {
    return new PluginRepository.Item(
        new JSONObject()
            .put("name", name)
            .put("description", "keep stable anchor")
            .put("available", true)
            .put("enabled", true)
            .put("dynamic", true)
            .put("runtimeGeneration", 1)
            .put("sessionId", "fixture-session")
            .put("pluginId", "anchor-plugin"));
  }

  private static PluginRegistry.Snapshot catalog(List<PluginRepository.Item> items) {
    PluginRegistry registry = new PluginRegistry();
    assertTrue(registry.completeQuery(registry.beginQuery(SOURCE), items, List.of(), false));
    return registry.snapshot();
  }

  /** 仅反射 State 的封装构造器；目录、条目和操作快照均走生产公开 API。 */
  private static PluginRepository.State state(
      PluginRegistry.Snapshot catalog,
      List<PluginOperationQueue.Operation> operations,
      boolean busy,
      String message,
      int percent)
      throws Exception {
    Constructor<PluginRepository.State> constructor =
        PluginRepository.State.class.getDeclaredConstructor(
            PluginRegistry.Snapshot.class,
            List.class,
            boolean.class,
            String.class,
            boolean.class,
            int.class);
    constructor.setAccessible(true);
    return constructor.newInstance(catalog, operations, busy, message, busy, percent);
  }

  private static PluginRepository.State configurationState(
      PluginRegistry.Snapshot catalog, List<PluginRepository.Item> configured, boolean refreshing)
      throws Exception {
    Constructor<PluginRepository.State> constructor =
        PluginRepository.State.class.getDeclaredConstructor(
            PluginRegistry.Snapshot.class,
            List.class,
            boolean.class,
            String.class,
            boolean.class,
            int.class,
            int.class,
            com.deepseekharness.app.util.PluginManagementView.class,
            List.class,
            boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        catalog,
        List.of(),
        refreshing,
        "",
        false,
        -1,
        PluginRepository.State.RESULT_NONE,
        null,
        configured,
        refreshing);
  }

  private Fixture fixture() {
    return new ViewModelProvider(host.get()).get(Fixture.class);
  }

  private void mount(PluginRepository.State state) {
    fixture().states.setValue(state);
    page = new FixtureFragment();
    Bundle arguments = new Bundle();
    arguments.putBoolean("show_installed", true);
    page.setArguments(arguments);
    replacePage();
  }

  private void replacePage() {
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, page, PAGE_TAG)
        .commitNow();
    layout();
    assertSame(page.requireView(), list());
    assertTrue(adapter().hasStableIds());
  }

  private void publish(PluginRepository.State state) {
    fixture().states.setValue(state);
    layout();
  }

  private RecyclerView list() {
    return page.find(R.id.pluginList);
  }

  private RecyclerView.Adapter<?> adapter() {
    return list().getAdapter();
  }

  private LinearLayoutManager layoutManager() {
    return (LinearLayoutManager) list().getLayoutManager();
  }

  private void layout() {
    // PAUSED 主线程只处理已到期任务，避免 EditText 光标/动画的无限定时回调。
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    View decor = host.get().getWindow().getDecorView();
    measureAndLayout(decor);
    measureAndLayout(list());
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    measureAndLayout(list());
    assertFalse("布局必须消费真实 Adapter 更新", list().hasPendingAdapterUpdates());
  }

  private static void measureAndLayout(View view) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, WIDTH, HEIGHT);
  }

  private View rowAt(int position) {
    assertTrue("position 0 是页头，末项是页脚", position >= 1 && position < adapter().getItemCount() - 1);
    layoutManager().scrollToPositionWithOffset(position, 0);
    layout();
    RecyclerView.ViewHolder holder = list().findViewHolderForAdapterPosition(position);
    assertNotNull("插件行必须通过 RecyclerView 真实创建和绑定", holder);
    assertEquals(position, holder.getAdapterPosition());
    assertNotNull(holder.itemView.findViewById(R.id.pluginName));
    return holder.itemView;
  }

  private List<String> renderedNames() {
    List<String> names = new ArrayList<>();
    for (int position = 1; position < adapter().getItemCount() - 1; position++)
      names.add(text(rowAt(position).findViewById(R.id.pluginName)));
    return names;
  }

  private void assertEmptyText(String expected) {
    TextView empty = page.find(R.id.pluginEmpty);
    assertEquals(View.VISIBLE, empty.getVisibility());
    assertEquals(expected, text(empty));
  }

  private void chooseSort(PluginSort.Mode mode) {
    assertTrue(page.find(R.id.btnSort).performClick());
    int[] labels = {
      R.string.plugin_sort_az,
      R.string.plugin_sort_za,
      R.string.plugin_sort_enabled,
      R.string.plugin_sort_updates
    };
    chooseDrawerEntry(page.requireContext().getString(labels[mode.ordinal()]));
  }

  private void chooseDrawerEntry(String label) {
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    assertTrue(dialog.isShowing());
    View title = findText(dialog.getWindow().getDecorView(), label);
    assertNotNull(label, title);
    // The clickable row contains the title and optional description.
    View row = title;
    while (row != null && !row.isClickable()) row = (View) row.getParent();
    assertNotNull(row);
    assertTrue(row.performClick());
    layout();
    assertFalse(dialog.isShowing());
  }

  private static View findText(View view, String expected) {
    if (view instanceof TextView text && expected.contentEquals(text.getText())) return view;
    if (view instanceof android.view.ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        View found = findText(group.getChildAt(index), expected);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String text(View view) {
    assertNotNull(view);
    return ((TextView) view).getText().toString();
  }

  private String rowStatus(int position) {
    View row = rowAt(position);
    return text(row.findViewById(R.id.pluginStatus))
        + "\n"
        + text(row.findViewById(R.id.pluginRuntime));
  }

  private static void assertNoRuntimeBadge(View row) {
    TextView badge = row.findViewById(R.id.pluginRuntime);
    assertEquals(View.GONE, badge.getVisibility());
    assertEquals("", text(badge));
    assertNull(badge.getContentDescription());
  }

  /** 与生产一致的胶囊底色：同一语义色 18% 填充（中性用 raised）。 */
  private int badgeFill(int toneColor) {
    return (page.requireContext().getColor(toneColor) & 0x00FFFFFF) | (0x2E << 24);
  }

  /** 徽章必须可见、文字完整、文字色与底色都取自声明的 shell_* 语义 token。 */
  private void assertBadge(View row, int id, String expectedText, int textColor, int background) {
    TextView badge = row.findViewById(id);
    assertEquals(expectedText, text(badge));
    assertEquals(View.VISIBLE, badge.getVisibility());
    assertEquals(page.requireContext().getColor(textColor), badge.getCurrentTextColor());
    assertNotNull("徽章必须有胶囊背景", badge.getBackground());
    assertNotNull("徽章底色必须由语义 token tint", badge.getBackgroundTintList());
    assertEquals(background, badge.getBackgroundTintList().getDefaultColor());
  }

  private Notifications observe() {
    Notifications observer = new Notifications();
    adapter().registerAdapterDataObserver(observer);
    return observer;
  }

  private static final class Event {
    final String kind;
    final int position, count, destination;
    final Object payload;

    Event(String kind, int position, int count, int destination, Object payload) {
      this.kind = kind;
      this.position = position;
      this.count = count;
      this.destination = destination;
      this.payload = payload;
    }
  }

  private static final class Notifications extends RecyclerView.AdapterDataObserver {
    int fullChanges;
    final List<Event> events = new ArrayList<>();

    @Override
    public void onChanged() {
      fullChanges++;
    }

    @Override
    public void onItemRangeChanged(int positionStart, int itemCount) {
      events.add(new Event("change", positionStart, itemCount, -1, null));
    }

    @Override
    public void onItemRangeChanged(int positionStart, int itemCount, Object payload) {
      events.add(new Event("change", positionStart, itemCount, -1, payload));
    }

    @Override
    public void onItemRangeInserted(int positionStart, int itemCount) {
      events.add(new Event("insert", positionStart, itemCount, -1, null));
    }

    @Override
    public void onItemRangeRemoved(int positionStart, int itemCount) {
      events.add(new Event("remove", positionStart, itemCount, -1, null));
    }

    @Override
    public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
      events.add(new Event("move", fromPosition, itemCount, toPosition, null));
    }

    void clear() {
      fullChanges = 0;
      events.clear();
    }

    void assertNone() {
      assertEquals("禁止 notifyDataSetChanged", 0, fullChanges);
      assertTrue("页头或无关操作不应通知插件条目", events.isEmpty());
    }

    void assertSingle(String kind, int position, int count) {
      assertEquals("禁止 notifyDataSetChanged", 0, fullChanges);
      assertEquals(1, events.size());
      Event event = events.get(0);
      assertEquals(kind, event.kind);
      assertEquals(position, event.position);
      assertEquals(count, event.count);
    }
  }
}
