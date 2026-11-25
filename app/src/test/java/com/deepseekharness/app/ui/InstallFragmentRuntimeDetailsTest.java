package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.shadows.ShadowLooper.shadowMainLooper;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = InstallFragmentRuntimeDetailsTest.App.class,
    instrumentedPackages = "com.deepseekharness.app.backup",
    shadows = {
      RuntimeToolsRegistryAndroidTest.HostFileSystem.class,
      InstallFragmentRuntimeDetailsTest.MaintenanceCalls.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class InstallFragmentRuntimeDetailsTest {
  public static final class App extends DshaApp {
    ProbeController controller;

    @Override
    public void onCreate() {}

    @Override
    public HarnessController harnessController() {
      return controller;
    }
  }

  private record Read(boolean ready, RuntimeDescriptor descriptor, boolean unreadable) {}

  private static final class ProbeController extends HarnessController {
    private final ProotBootstrap bootstrap;
    private volatile Read next;
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private volatile boolean readOnMain;

    ProbeController(Context context) {
      super(context);
      bootstrap =
          new ProotBootstrap(context) {
            @Override
            public RuntimeDescriptor installedRuntimeDescriptor() throws IOException {
              if (next.unreadable()) throw new IOException("RUNTIME_DESCRIPTOR_CHECKSUM");
              return next.descriptor();
            }
          };
    }

    @Override
    public ProotBootstrap proot() {
      return bootstrap;
    }

    @Override
    public boolean isEnvironmentReady() {
      if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
        readOnMain = true;
      } else {
        try {
          if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture gate timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return next.ready();
    }

    void hold(Read read) {
      gate.countDown();
      next = read;
      gate = new CountDownLatch(1);
    }
  }

  private ProbeController probe;
  private RuntimeDescriptor descriptor;
  private String previousLanguage;

  @Before
  public void setUp() throws IOException {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    App app = (App) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    probe = new ProbeController(app);
    app.controller = probe;
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("version", 1L);
    value.put("baseVersion", "10");
    value.put("dshVersion", "0.2.2-rc.5");
    value.put("launcherContract", "ANDROID");
    value.put("bridgeProtocol", 1L);
    value.put("inputs", Map.of());
    value.put("dataRead", List.of("fixture"));
    value.put("dataWrite", "fixture");
    value.put("runtimeId", RuntimeDescriptor.calculateId(value));
    descriptor = new RuntimeDescriptor(value);
  }

  @After
  public void tearDown() {
    if (probe != null) probe.gate.countDown();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void detailsReadOffMainAndReplacePlaceholders() throws Exception {
    probe.hold(new Read(true, descriptor, false));
    try (var activity = host()) {
      InstallFragment fragment = open(activity.get());
      assertEquals("正在检查环境…", title(fragment).getText().toString());
      assertEquals("Ubuntu —", chip(fragment, 0));
      assertEquals("dsh —", chip(fragment, 1));
      assertTrue(fragment.requireView().findViewById(R.id.install_btn).isEnabled());

      completeWorker(fragment);
      shadowMainLooper().idle();
      assertFalse(probe.readOnMain);
      assertEquals("环境可用", title(fragment).getText().toString());
      assertEquals("Ubuntu 10", chip(fragment, 0));
      assertEquals("dsh 0.2.2-rc.5", chip(fragment, 1));
    }
  }

  @Test
  public void missingAndUnreadableDescriptorsSettleWithPlaceholders() throws Exception {
    for (Read unavailable : List.of(new Read(true, null, false), new Read(true, null, true))) {
      probe.hold(unavailable);
      try (var activity = host()) {
        InstallFragment fragment = open(activity.get());
        assertEquals("正在检查环境…", title(fragment).getText().toString());
        completeWorker(fragment);
        shadowMainLooper().idle();
        assertEquals("等待检查", title(fragment).getText().toString());
        assertEquals("Ubuntu —", chip(fragment, 0));
        assertEquals("dsh —", chip(fragment, 1));
      }
    }
  }

  @Test
  public void detailsUpdateLeavesUnrelatedViewsAndVersionChipsInPlace() throws Exception {
    probe.hold(new Read(true, descriptor, false));
    try (var activity = host()) {
      InstallFragment fragment = open(activity.get());
      shadowMainLooper().idle();
      View view = fragment.requireView();
      ViewGroup chips = chipRow(fragment);
      View base = chips.getChildAt(0);
      View dsh = chips.getChildAt(1);
      View app = chips.getChildAt(2);
      TextUpdates updates = new TextUpdates(view);

      completeWorker(fragment);
      shadowMainLooper().idle();

      assertEquals("Unrelated TextView.setText callbacks", 0, updates.count);
      assertSame(chips, chipRow(fragment));
      assertSame(base, chips.getChildAt(0));
      assertSame(dsh, chips.getChildAt(1));
      assertSame(app, chips.getChildAt(2));
      assertEquals("环境可用", title(fragment).getText().toString());
      assertEquals("Ubuntu 10", base.getContentDescription());
      assertEquals("dsh 0.2.2-rc.5", dsh.getContentDescription());
      assertTrue(dsh.performClick());
      ClipboardManager clipboard = activity.get().getSystemService(ClipboardManager.class);
      assertEquals("0.2.2-rc.5", clipboard.getPrimaryClip().getItemAt(0).getText());
    }
  }

  @Test
  public void detailsUpdateStillRendersChangedEnvironmentGateAndActions() throws Exception {
    probe.hold(new Read(true, descriptor, false));
    try (var activity = host()) {
      InstallFragment fragment = open(activity.get());
      shadowMainLooper().idle();
      View view = fragment.requireView();
      View check = view.findViewById(R.id.install_btn);
      View maintenance = view.findViewById(R.id.install_uninstall);
      assertTrue(check.isEnabled());
      assertTrue(maintenance.isEnabled());
      try (var lease = EnvironmentTaskGate.tryAcquireShared("fixture")) {
        assertNotNull(lease);
        completeWorker(fragment);
        shadowMainLooper().idle();

        assertEquals("环境任务进行中", title(fragment).getText().toString());
        assertFalse(check.isEnabled());
        assertFalse(maintenance.isEnabled());
        assertEquals("Ubuntu 10", chip(fragment, 0));
      }
      shadowMainLooper().idleFor(java.time.Duration.ofSeconds(1));
      assertEquals("环境可用", title(fragment).getText().toString());
      assertTrue(check.isEnabled());
      assertTrue(maintenance.isEnabled());
    }
  }

  @Test
  public void staleReadsDoNotReplaceTaskRefreshOrUpdateDetachedView() throws Exception {
    probe.hold(new Read(true, descriptor, false));
    try (var activity = host()) {
      FragmentActivity host = activity.get();
      InstallFragment original = open(host);
      TextView detachedTitle = title(original);
      completeWorker(original);

      probe.hold(new Read(false, null, false));
      // An idle repository snapshot after RUNNING enters the real completion branch in render().
      ReflectionHelpers.setField(original, "lastOutcome", InstallTask.Outcome.RUNNING);
      ReflectionHelpers.callInstanceMethod(original, "render");
      shadowMainLooper().idle();
      assertEquals("正在检查环境…", detachedTitle.getText().toString());
      completeWorker(original);

      host.getSupportFragmentManager().beginTransaction().remove(original).commitNow();
      assertNull(original.getView());
      probe.hold(new Read(true, descriptor, false));
      InstallFragment reopened = open(host);
      shadowMainLooper().idle();
      assertEquals("正在检查环境…", detachedTitle.getText().toString());
      assertEquals("正在检查环境…", title(reopened).getText().toString());
      completeWorker(reopened);
      shadowMainLooper().idle();
      assertEquals("环境可用", title(reopened).getText().toString());
    }
  }

  private void completeWorker(InstallFragment fragment) throws Exception {
    Future<?> result = ReflectionHelpers.getField(fragment, "detailsRead");
    assertNotNull(result);
    probe.gate.countDown();
    // The worker posts the result, while the paused main Looper gives the test ordering control.
    result.get(10, TimeUnit.SECONDS);
  }

  private static ActivityController<FragmentActivity> host() {
    var controller = Robolectric.buildActivity(FragmentActivity.class);
    FragmentActivity host = controller.get();
    host.setTheme(R.style.Theme_DeepseekHarness);
    controller.setup();
    FrameLayout root = new FrameLayout(host);
    root.setId(R.id.fragment_container);
    host.setContentView(root);
    return controller;
  }

  private static InstallFragment open(FragmentActivity host) {
    var fragment = new InstallFragment();
    host.getSupportFragmentManager()
        .beginTransaction()
        .add(R.id.fragment_container, fragment)
        .commitNow();
    return fragment;
  }

  private static TextView title(InstallFragment fragment) {
    return fragment.requireView().findViewById(R.id.install_environment);
  }

  private static String chip(InstallFragment fragment, int index) {
    return ((TextView) chipRow(fragment).getChildAt(index)).getText().toString();
  }

  private static ViewGroup chipRow(InstallFragment fragment) {
    FrameLayout chips = fragment.requireView().findViewById(R.id.install_chips);
    return (ViewGroup) chips.getChildAt(0);
  }

  private static final class TextUpdates implements TextWatcher {
    private int count;

    TextUpdates(View view) {
      int[] ids = {
        R.id.install_state1, R.id.install_num1, R.id.install_sub1,
        R.id.install_state2, R.id.install_num2, R.id.install_sub2,
        R.id.install_state3, R.id.install_num3, R.id.install_sub3,
        R.id.install_state4, R.id.install_num4, R.id.install_sub4,
        R.id.install_state5, R.id.install_num5, R.id.install_sub5,
        R.id.install_state6, R.id.install_num6, R.id.install_sub6,
        R.id.install_steps_count, R.id.install_uninstall_title, R.id.install_uninstall_sub,
        R.id.install_log_title, R.id.install_log_last, R.id.install_cancel,
      };
      for (int id : ids) view.<TextView>findViewById(id).addTextChangedListener(this);
    }

    @Override
    public void beforeTextChanged(CharSequence text, int start, int before, int after) {}

    @Override
    public void onTextChanged(CharSequence text, int start, int before, int after) {
      count++;
    }

    @Override
    public void afterTextChanged(Editable text) {}
  }

  @org.robolectric.annotation.Implements(
      value = com.deepseekharness.app.core.BackupTask.class,
      isInAndroidSdk = false)
  public static final class MaintenanceCalls {
    static int recovers, rebuilds;

    @org.robolectric.annotation.Implementation
    protected boolean recoverMaintenance() {
      recovers++;
      return false;
    }

    @org.robolectric.annotation.Implementation
    protected boolean rebuild() {
      rebuilds++;
      return false;
    }
  }

  @Test
  public void settingsShowsRecoveryForTrialWithNoHostMaintenance() throws Exception {
    MaintenanceCalls.recovers = MaintenanceCalls.rebuilds = 0;
    var context = RuntimeEnvironment.getApplication();
    var id = "01234567-89ab-4def-8123-456789abcdef";
    var operation = new java.io.File(context.getFilesDir(), "runtime-trials/" + id);
    java.nio.file.Files.createDirectories(new java.io.File(operation, "payload").toPath());
    java.nio.file.Files.writeString(new java.io.File(operation, "launched").toPath(), id);
    assertFalse(com.deepseekharness.app.core.BackupTask.get(context).pendingMaintenance());
    probe.hold(new Read(true, descriptor, false));
    try (var activity = host()) {
      InstallFragment fragment = open(activity.get());
      var root = fragment.requireView();
      assertEquals(View.VISIBLE, root.findViewById(R.id.install_recover).getVisibility());
      assertEquals(View.GONE, root.findViewById(R.id.install_repair).getVisibility());
      assertTrue(root.findViewById(R.id.install_recover).performClick());
      var dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
      assertTrue(dialog instanceof androidx.appcompat.app.AlertDialog);
      var positive =
          ((androidx.appcompat.app.AlertDialog) dialog)
              .getButton(android.content.DialogInterface.BUTTON_POSITIVE);
      assertEquals("恢复原环境", positive.getText().toString());
      assertTrue(positive.performClick());
      shadowMainLooper().idle();
      assertEquals(1, MaintenanceCalls.recovers);
      assertEquals(0, MaintenanceCalls.rebuilds);
      completeWorker(fragment);
    }
  }
}
