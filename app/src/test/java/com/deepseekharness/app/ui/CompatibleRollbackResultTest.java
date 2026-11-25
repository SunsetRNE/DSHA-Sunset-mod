package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.core.EnvironmentAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest;
import com.deepseekharness.app.util.BackupTaskKinds;
import com.deepseekharness.app.util.DshVersionPolicy;
import com.deepseekharness.app.util.EnvironmentUiFacts;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    qualifiers = "zh-rCN-w960dp-h720dp-mdpi",
    application = CompatibleRollbackResultTest.App.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {
      CompatibleRollbackResultTest.Controller.class,
      CompatibleRollbackResultTest.Proot.class,
      RuntimeToolsRegistryAndroidTest.HostFileSystem.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class CompatibleRollbackResultTest {
  private static final long TASK_ID = 85;
  private static final String NONCE = "a".repeat(32);
  private static final String SUMMARY = "兼容运行时已通过本轮隔离试运行并启用。对话、配置和项目保持原位。";

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController instance;
    static boolean ready;
    static Context preferencesContext;
    static com.deepseekharness.app.core.StartupDiagnostics diagnostics;
    static final java.util.List<Runnable> entryChecks = new java.util.ArrayList<>();

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {
      if (completed != null) entryChecks.add(completed);
    }

    @Implementation
    protected void addReadyWebPageListener(Runnable ignored) {}

    @Implementation
    protected void removeReadyWebPageListener(Runnable ignored) {}

    @Implementation
    protected long getWebGeneration() {
      return 1;
    }

    @Implementation
    protected boolean hasUnresolvedWebSession() {
      return false;
    }

    @Implementation
    protected boolean isRestoringWebSession() {
      return false;
    }

    @Implementation
    protected com.deepseekharness.app.core.StartupDiagnostics startupDiagnostics() {
      return diagnostics;
    }

    @Implementation
    protected static HarnessController get(Context context) {
      return instance;
    }

    @Implementation
    protected Context context() {
      return preferencesContext == null ? RuntimeEnvironment.getApplication() : preferencesContext;
    }

    @Implementation
    protected ProotBootstrap proot() {
      return Proot.instance;
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      return ready;
    }

    @Implementation
    protected com.deepseekharness.app.core.ConfigStore config() {
      return new com.deepseekharness.app.core.ConfigStore(context());
    }
  }

  @Implements(value = ProotBootstrap.class, isInAndroidSdk = false)
  public static final class Proot {
    static ProotBootstrap instance;
    static RuntimeDescriptor expected, installed;
    static Map<String, Object> health;
    static DshVersionPolicy.Status version;
    static Runnable nextInstallationProbe;

    @Implementation
    protected boolean isEnvironmentInstalled() {
      Runnable probe = nextInstallationProbe;
      nextInstallationProbe = null;
      if (probe != null) probe.run();
      return true;
    }

    @Implementation
    protected boolean canUpdateManagedRuntime() {
      return true;
    }

    @Implementation
    protected File getRootfsDir() {
      return new File(RuntimeEnvironment.getApplication().getFilesDir(), "linux/ubuntu");
    }

    @Implementation
    protected String environmentIdentity() {
      return "isolated-robolectric-environment";
    }

    @Implementation
    protected RuntimeDescriptor installedRuntimeDescriptor() {
      return installed;
    }

    @Implementation
    protected RuntimeDescriptor expectedRuntimeDescriptor() {
      return expected;
    }

    @Implementation
    protected Map<String, Object> runtimeHealth() {
      return health;
    }

    @Implementation
    protected DshVersionPolicy.Status installedVersionStatus() {
      return version;
    }

    @Implementation
    protected void releaseRecoveryTools() {}
  }

  @Implements(
      value = com.deepseekharness.app.backup.DataProtectionService.class,
      isInAndroidSdk = false)
  public static final class FailAfterAdmission {
    static int starts;

    @Implementation
    protected static com.deepseekharness.app.backup.DataProtectionService.StartTicket start(
        Context context) throws java.io.IOException {
      starts++;
      throw new java.io.IOException("FIXTURE_STOP_AFTER_TASK_ADMISSION");
    }
  }

  private Context context;

  @Before
  public void prepare() throws Exception {
    context = RuntimeEnvironment.getApplication();
    context.getApplicationInfo().nativeLibraryDir = context.getCacheDir().getAbsolutePath();
    Controller.entryChecks.clear();
    Controller.diagnostics = new com.deepseekharness.app.core.StartupDiagnostics(context);
    var config = new com.deepseekharness.app.core.ConfigStore(context);
    config.setWelcomed(true);
    config.setCheckUpdate(false);
    Controller.instance = Shadow.newInstanceOf(HarnessController.class);
    Controller.ready = true;
    Controller.preferencesContext = null;
    Proot.instance = Shadow.newInstanceOf(ProotBootstrap.class);
    Proot.version = DshVersionPolicy.Status.CURRENT;
    Proot.nextInstallationProbe = null;
    try (var stream = context.getAssets().open("runtime-descriptor.json")) {
      var descriptor = BackupJson.read(stream.readAllBytes(), 512 * 1024);
      Proot.expected = new RuntimeDescriptor(descriptor);
      var older = new LinkedHashMap<>(descriptor);
      var inputs = new LinkedHashMap<Object, Object>((Map<?, ?>) descriptor.get("inputs"));
      inputs.put("runtime-patches.json", "1".repeat(64));
      older.put("inputs", inputs);
      older.put("runtimeId", RuntimeDescriptor.calculateId(older));
      Proot.installed = new RuntimeDescriptor(older);
    }
    Proot.health =
        new LinkedHashMap<>(
            Map.of(
                "runtimeId",
                Proot.installed.id(),
                "nonce",
                NONCE,
                "port",
                3080L,
                "confirmedAt",
                1L));
    for (String key :
        List.of(
            "assets",
            "nativeModules",
            "process",
            "authentication",
            "localApi",
            "renderer",
            "dataRead",
            "dataWrite",
            "storageFreshReopened",
            "processExited")) Proot.health.put(key, true);
    setApkUpdateTime(12345);
  }

  private void setApkUpdateTime(long time) throws Exception {
    PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    info.lastUpdateTime = time;
    Shadow.<org.robolectric.shadows.ShadowPackageManager>extract(context.getPackageManager())
        .installPackage(info);
  }

  private void record(String kind, String status) {
    context
        .getSharedPreferences("dsha-data-task", Context.MODE_PRIVATE)
        .edit()
        .putLong("id", TASK_ID)
        .putString("kind", kind)
        .putString("status", status)
        .putString("detail", SUMMARY)
        .commit();
  }

  private void chooseRollback() throws Exception {
    String choice =
        EnvironmentAccess.candidateAttemptKey(Controller.instance)
            + "#"
            + Proot.installed.id()
            + "#"
            + NONCE;
    context
        .getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE)
        .edit()
        .putString("accepted_rollback", choice)
        .commit();
  }

  private ExtractActivity page(long task) {
    Intent intent =
        new Intent(context, ExtractActivity.class)
            .putExtra("review_only", true)
            .putExtra("data_task_id", task);
    return Robolectric.buildActivity(ExtractActivity.class, intent).create().get();
  }

  private static String label(ExtractActivity page, int id) {
    return ((TextView) page.findViewById(id)).getText().toString();
  }

  @Test
  public void selectedCompatibleRollbackShowsSuccessAndNormalEntryWithOptionalUpdate()
      throws Exception {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    chooseRollback();
    ExtractActivity page = page(TASK_ID);
    assertEquals("进入主界面", label(page, R.id.extract_enter));
    assertEquals("更新运行时", label(page, R.id.extract_retry));
    assertTrue(label(page, R.id.extract_error).contains(SUMMARY));
    page.findViewById(R.id.extract_enter).performClick();
    Intent next = Shadow.<ShadowActivity>extract(page).getNextStartedActivity();
    assertFalse(next.getBooleanExtra("limited_entry", true));
  }

  @Test
  public void sameChoiceAllowsSharedStartGateAndHomeBanner() throws Exception {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    chooseRollback();
    assertFalse(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    assertFalse(
        EnvironmentUiFacts.banner(new EnvironmentUiReader(context, Controller.instance))
            .recovery());
  }

  @Test
  public void unrelatedOrUnconfirmedRollbackRemainsBlocked() throws Exception {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    chooseRollback();
    setApkUpdateTime(23456);
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
  }

  @Test
  public void rollbackReportsCompletionIfTheSelectionCannotBeSaved() {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    ExtractActivity page = page(TASK_ID);
    assertTrue(label(page, R.id.extract_error).contains(SUMMARY));
    assertTrue(label(page, R.id.extract_error).contains("无法确认本次安装继续使用该运行时"));
    assertNotEquals("进入主界面", label(page, R.id.extract_enter));
  }

  @Test
  public void otherMaintenanceSuccessStillRequiresTheBundledRuntime() throws Exception {
    record(BackupTaskKinds.UPDATE, "SUCCEEDED");
    chooseRollback();
    ExtractActivity page = page(TASK_ID);
    assertEquals("未通过", label(page, R.id.extract_pill));
    assertFalse(label(page, R.id.extract_error).contains(SUMMARY));
    assertNotEquals("进入主界面", label(page, R.id.extract_enter));
  }

  @Test
  public void changedNonceDoesNotOverrideTheExistingAttemptFallbackButDamagedHealthStillBlocks()
      throws Exception {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    chooseRollback();
    Proot.health.put("nonce", "b".repeat(32));
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    context
        .getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE)
        .edit()
        .putString("attempted_identity", EnvironmentAccess.candidateAttemptKey(Controller.instance))
        .commit();
    assertFalse(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    assertTrue(
        EnvironmentUiFacts.banner(new EnvironmentUiReader(context, Controller.instance))
            .recovery());
    Proot.health.put("nonce", NONCE);
    Proot.health.put("renderer", false);
    Controller.ready = false;
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
  }

  @Test
  public void laterForwardFailureKeepsItsExistingFallbackButDoesNotProduceARollbackSuccess()
      throws Exception {
    record(BackupTaskKinds.UPDATE, "FAILED");
    chooseRollback();
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    context
        .getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE)
        .edit()
        .putString("attempted_identity", EnvironmentAccess.candidateAttemptKey(Controller.instance))
        .commit();
    assertFalse(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    assertTrue(
        EnvironmentUiFacts.banner(new EnvironmentUiReader(context, Controller.instance))
            .recovery());
  }

  @Test
  public void failedPreferenceCommitCannotAdmitItsInMemoryValueButExistingFallbackStillWorks()
      throws Exception {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    String candidate = EnvironmentAccess.candidateAttemptKey(Controller.instance);
    AtomicReference<String> staged = new AtomicReference<>("");
    AtomicReference<String> inMemory = new AtomicReference<>("");
    AtomicReference<String> attempted = new AtomicReference<>("");
    var editor =
        (SharedPreferences.Editor)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "putString" -> {
                        staged.set((String) args[1]);
                        yield proxy;
                      }
                      case "commit" -> {
                        inMemory.set(staged.get());
                        yield false;
                      }
                      case "remove" -> throw new IllegalStateException("simulated cleanup failure");
                      default -> throw new AssertionError(method.getName());
                    });
    var prefs =
        (SharedPreferences)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getString" ->
                          args[0].equals("accepted_rollback") ? inMemory.get() : attempted.get();
                      case "edit" -> editor;
                      default -> throw new AssertionError(method.getName());
                    });
    Controller.preferencesContext =
        new ContextWrapper(context) {
          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return name.equals("dsha_environment_upgrade")
                ? prefs
                : super.getSharedPreferences(name, mode);
          }
        };
    var remember =
        EnvironmentAccess.class.getDeclaredMethod(
            "rememberAcceptedRollback", HarnessController.class);
    remember.setAccessible(true);
    try {
      remember.invoke(null, Controller.instance);
      assertEquals(candidate + "#" + Proot.installed.id() + "#" + NONCE, inMemory.get());
      assertFalse(EnvironmentAccess.hasAcceptedRollback(Controller.instance));
      assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
      assertTrue(label(page(TASK_ID), R.id.extract_error).contains("无法确认本次安装继续使用该运行时"));
      attempted.set(candidate);
      assertFalse(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
      assertFalse(EnvironmentAccess.hasAcceptedRollback(Controller.instance));
    } finally {
      Controller.preferencesContext = null;
      remember.invoke(null, Controller.instance);
    }
  }

  @Test
  public void unclosedTrialStillBlocksTheSelectedRuntimeAndPage() throws Exception {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    chooseRollback();
    String trial = "a1234567-1234-4234-8234-123456789abc";
    var dir =
        Files.createDirectories(
            context.getFilesDir().toPath().resolve("runtime-trials").resolve(trial));
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    ExtractActivity page = page(TASK_ID);
    assertNotEquals("进入主界面", label(page, R.id.extract_enter));
    assertFalse(label(page, R.id.extract_error).contains(SUMMARY));
    // An earlier upgrade attempt must not conceal a real, unclosed trial.
    Proot.health.put("nonce", "b".repeat(32));
    context
        .getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE)
        .edit()
        .putString("attempted_identity", EnvironmentAccess.candidateAttemptKey(Controller.instance))
        .commit();
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    Files.writeString(dir.resolve("closed"), "invalid");
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    Files.writeString(dir.resolve("closed"), trial);
    assertFalse(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
  }

  @Test
  public void currentRuntimeRequiresTrialClosureBeforeWebStart() throws Exception {
    Proot.installed = Proot.expected;
    assertTrue(EnvironmentAccess.runtimeLatest(Controller.instance));
    assertFalse(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));

    String id = "a1234567-1234-4234-8234-123456789abc";
    var trial =
        Files.createDirectories(context.getFilesDir().toPath().resolve("runtime-trials/" + id));
    Files.writeString(trial.resolve("launched"), id);
    Files.writeString(
        trial.resolve("failure.json"), "{\"error\":\"SETTINGS_TRIAL_PROCESS_UNCONFIRMED\"}");
    // The launcher can already be gone. The durable record still requires its own closure proof.
    assertFalse(com.deepseekharness.app.core.MaintenanceCoordinator.pending(Controller.instance));
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));

    Files.writeString(trial.resolve("closed"), "invalid");
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    Files.writeString(trial.resolve("closed"), id);
    assertFalse(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    assertTrue(Files.exists(trial.resolve("failure.json")));
  }

  @Test
  public void currentRuntimeBlocksWhenTrialStorageCannotBeAccessed() {
    Proot.installed = Proot.expected;
    Controller.preferencesContext =
        new ContextWrapper(context) {
          @Override
          public File getFilesDir() {
            throw new SecurityException("fixture trial storage denied");
          }
        };
    try {
      assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    } finally {
      Controller.preferencesContext = null;
    }
  }

  @Test
  public void newerOrUnknownDataStillBlocksTheSelectedRuntime() throws Exception {
    record(BackupTaskKinds.ROLLBACK, "SUCCEEDED");
    chooseRollback();
    Proot.version = DshVersionPolicy.Status.UNKNOWN;
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
    Proot.version = DshVersionPolicy.Status.INSTALLED_NEWER;
    assertTrue(EnvironmentAccess.requiresMaintenanceBeforeStart(Controller.instance));
  }

  private File interruptedSettingsTrial() throws Exception {
    String id = "01234567-89ab-4def-8123-456789abcdef";
    String nonce = id.replace("-", "");
    File operation = new File(context.getFilesDir(), "runtime-trials/" + id);
    assertTrue(new File(operation, "payload").mkdirs());
    Files.writeString(new File(operation, "launched").toPath(), id);
    Files.write(
        new File(operation, "intent.json").toPath(),
        BackupJson.write(
            Map.of(
                "id",
                id,
                "nonce",
                nonce,
                "purpose",
                "profile-settings",
                "profile",
                "dsha-recovery-" + nonce.substring(0, 16),
                "sourceProfile",
                "web"),
            16384));
    assertFalse(com.deepseekharness.app.core.BackupTask.get(context).pendingMaintenance());
    assertTrue(RuntimeMaintenanceStarter.pendingTrial(context));
    return operation;
  }

  private Intent mainRedirect(Intent intent) throws Exception {
    Controller.entryChecks.clear();
    var driver = Robolectric.buildActivity(MainActivity.class, intent).create().start().resume();
    try {
      assertFalse(Controller.entryChecks.isEmpty());
      for (Runnable check : List.copyOf(Controller.entryChecks)) check.run();
      var shadow = Shadow.<ShadowActivity>extract(driver.get());
      var mainReady = MainActivity.class.getDeclaredField("mainReady");
      mainReady.setAccessible(true);
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
      do {
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
        Intent next = shadow.getNextStartedActivity();
        if (next != null) return next;
        if (mainReady.getBoolean(driver.get())) return null;
        Thread.sleep(15);
      } while (System.nanoTime() < until);
      throw new AssertionError("Main neither navigated nor became ready");
    } finally {
      driver.pause().stop().destroy();
    }
  }

  @Test
  public void latestRuntimeWithOnlyInterruptedSettingsTrialStaysOnMaintenanceUntilUserChooses()
      throws Exception {
    Proot.installed = Proot.expected;
    interruptedSettingsTrial();
    assertTrue(EnvironmentAccess.runtimeLatest(Controller.instance));
    Intent launcher =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity.class);
    Intent maintenance = mainRedirect(launcher);
    assertNotNull(maintenance);
    assertEquals(ExtractActivity.class.getName(), maintenance.getComponent().getClassName());
    assertFalse(maintenance.hasExtra("review_only"));
    assertFalse(maintenance.hasExtra("skip_extract"));
    var activity = Robolectric.buildActivity(ExtractActivity.class, maintenance).create();
    var page = activity.get();
    var shadow = Shadow.<ShadowActivity>extract(page);
    Intent unexpected = shadow.getNextStartedActivity();
    if (unexpected != null) {
      assertEquals(MainActivity.class.getName(), unexpected.getComponent().getClassName());
      assertFalse(unexpected.getBooleanExtra("limited_entry", true));
      assertTrue((unexpected.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
      Intent repeated = mainRedirect(unexpected);
      assertNotNull(repeated);
      assertEquals(ExtractActivity.class.getName(), repeated.getComponent().getClassName());
      var second = Robolectric.buildActivity(ExtractActivity.class, repeated).create();
      Intent secondMain = Shadow.<ShadowActivity>extract(second.get()).getNextStartedActivity();
      assertNotNull(secondMain);
      assertEquals(MainActivity.class.getName(), secondMain.getComponent().getClassName());
      fail("Ordinary launcher repeated Main -> Extract -> non-limited Main twice");
    }
    assertEquals(
        RuntimeMaintenanceStarter.Retry.RECOVER, RuntimeMaintenanceStarter.retryKind(page, false));
    assertEquals("恢复中断维护", label(page, R.id.extract_retry));
    assertTrue(page.findViewById(R.id.extract_enter).performClick());
    Intent limited = shadow.getNextStartedActivity();
    assertNotNull(limited);
    assertEquals(MainActivity.class.getName(), limited.getComponent().getClassName());
    assertTrue(limited.getBooleanExtra("limited_entry", false));
    assertFalse(limited.getBooleanExtra(FirstRunWebStart.EXTRA, false));
    assertTrue(
        new com.deepseekharness.app.core.ConfigStore(context)
            .allowsLimitedEntry(EnvironmentAccess.limitedEntryKey(Controller.instance)));
    assertNull(mainRedirect(limited));
    assertTrue(
        EnvironmentUiFacts.banner(new EnvironmentUiReader(context, Controller.instance))
            .recovery());
  }

  @Test
  public void latestRuntimeWithClosedTrialKeepsAutomaticNormalHandoffAndReviewOnlyStays()
      throws Exception {
    Proot.installed = Proot.expected;
    var operation = interruptedSettingsTrial();
    Files.writeString(new File(operation, "closed").toPath(), operation.getName());
    assertFalse(RuntimeMaintenanceStarter.pendingTrial(context));
    Intent intent = new Intent(context, ExtractActivity.class);
    var page = Robolectric.buildActivity(ExtractActivity.class, intent).create().get();
    Intent next = Shadow.<ShadowActivity>extract(page).getNextStartedActivity();
    assertNotNull(next);
    assertFalse(next.getBooleanExtra("limited_entry", true));
    ExtractActivity review = page(0);
    assertNull(Shadow.<ShadowActivity>extract(review).getNextStartedActivity());
  }

  @Test
  @Config(shadows = {FailAfterAdmission.class})
  public void unrelatedIdleCleanupDoesNotAdoptPreviousSuccessAndResumesCurrentUpgradeOnce()
      throws Exception {
    record(BackupTaskKinds.UPDATE, "SUCCEEDED");
    FailAfterAdmission.starts = 0;
    var attempts = context.getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE);
    var activity =
        com.deepseekharness.app.core.MaintenanceCoordinator.exclusiveWhenIdle(
            Controller.instance,
            () -> true,
            () -> {
              var page =
                  Robolectric.buildActivity(
                          ExtractActivity.class, new Intent(context, ExtractActivity.class))
                      .create()
                      .start()
                      .resume();
              assertEquals(0, FailAfterAdmission.starts);
              assertFalse(attempts.contains("attempted_identity"));
              assertEquals(
                  TASK_ID, com.deepseekharness.app.core.BackupTask.get(context).snapshot().id);
              assertEquals("正在执行环境任务", label(page.get(), R.id.extract_status));
              return page;
            });
    try {
      org.robolectric.shadows.ShadowLooper.shadowMainLooper()
          .idleFor(3, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals("the current APK must get one admitted attempt", 1, FailAfterAdmission.starts);
      assertEquals(
          EnvironmentAccess.candidateAttemptKey(Controller.instance),
          attempts.getString("attempted_identity", ""));
      assertNotEquals(TASK_ID, com.deepseekharness.app.core.BackupTask.get(context).snapshot().id);
      org.robolectric.shadows.ShadowLooper.shadowMainLooper()
          .idleFor(3, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals("admitted failure remains a one-shot attempt", 1, FailAfterAdmission.starts);
    } finally {
      activity.pause().stop().destroy();
    }
  }

  @Test
  @Config(shadows = {FailAfterAdmission.class})
  public void leaseLostBeforeAdmissionDoesNotConsumeTheCandidateAttempt() throws Exception {
    record(BackupTaskKinds.UPDATE, "SUCCEEDED");
    FailAfterAdmission.starts = 0;
    var attempts = context.getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE);
    var competitor = new AtomicReference<com.deepseekharness.app.util.EnvironmentTaskGate.Lease>();
    Proot.nextInstallationProbe =
        () ->
            competitor.set(com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire("fixture"));
    try {
      assertEquals("BUSY", RuntimeMaintenanceStarter.autoStart(context).name());
      assertNotNull(competitor.get());
      assertFalse(attempts.contains("attempted_identity"));
      assertEquals(0, FailAfterAdmission.starts);
    } finally {
      if (competitor.get() != null) competitor.get().close();
    }
    assertEquals(
        RuntimeMaintenanceStarter.Auto.DECLINED, RuntimeMaintenanceStarter.autoStart(context));
    assertEquals(1, FailAfterAdmission.starts);
    assertEquals(
        EnvironmentAccess.candidateAttemptKey(Controller.instance),
        attempts.getString("attempted_identity", ""));
    assertEquals(
        RuntimeMaintenanceStarter.Auto.NOT_ELIGIBLE, RuntimeMaintenanceStarter.autoStart(context));
    assertEquals(1, FailAfterAdmission.starts);
  }

  @Test
  @Config(shadows = {FailAfterAdmission.class})
  public void newlyDiscoveredNewerDataBlocksUnderTheAcquiredLease() {
    record(BackupTaskKinds.UPDATE, "SUCCEEDED");
    FailAfterAdmission.starts = 0;
    Proot.nextInstallationProbe = () -> Proot.version = DshVersionPolicy.Status.INSTALLED_NEWER;
    assertEquals(
        RuntimeMaintenanceStarter.Auto.NOT_ELIGIBLE, RuntimeMaintenanceStarter.autoStart(context));
    assertFalse(
        context
            .getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE)
            .contains("attempted_identity"));
    assertEquals(0, FailAfterAdmission.starts);
    assertEquals(TASK_ID, com.deepseekharness.app.core.BackupTask.get(context).snapshot().id);
  }

  @Test
  public void previousRuntimeWithOnlyInterruptedSettingsTrialOffersRecoveryBeforeUpdate()
      throws Exception {
    interruptedSettingsTrial();
    assertFalse(EnvironmentAccess.runtimeLatest(Controller.instance));
    var page =
        Robolectric.buildActivity(ExtractActivity.class, new Intent(context, ExtractActivity.class))
            .create()
            .get();
    assertNull(Shadow.<ShadowActivity>extract(page).getNextStartedActivity());
    assertEquals(
        RuntimeMaintenanceStarter.Retry.RECOVER, RuntimeMaintenanceStarter.retryKind(page, false));
    assertEquals("恢复中断维护", label(page, R.id.extract_retry));
  }

  @Test
  public void corruptTrialClosureKeepsRecoveryVisibleAndValidClosureAllowsNormalHandoff()
      throws Exception {
    Proot.installed = Proot.expected;
    File operation = interruptedSettingsTrial();
    File closed = new File(operation, "closed");
    Files.writeString(closed.toPath(), "12345678-abcd-4abc-8bcd-123456789abc");
    for (String invalid : List.of(Files.readString(closed.toPath()), "x".repeat(129))) {
      Files.writeString(closed.toPath(), invalid);
      assertTrue(
          "Damaged closure cannot authorize ordinary entry",
          RuntimeMaintenanceStarter.pendingTrial(context));
      var maintenance =
          Robolectric.buildActivity(
                  ExtractActivity.class, new Intent(context, ExtractActivity.class))
              .create()
              .get();
      assertNull(Shadow.<ShadowActivity>extract(maintenance).getNextStartedActivity());
      assertEquals("恢复中断维护", label(maintenance, R.id.extract_retry));
      assertTrue(new File(operation, "payload").isDirectory());
    }
    Files.writeString(closed.toPath(), operation.getName());
    assertFalse(RuntimeMaintenanceStarter.pendingTrial(context));
    var resolved =
        Robolectric.buildActivity(ExtractActivity.class, new Intent(context, ExtractActivity.class))
            .create()
            .get();
    Intent main = Shadow.<ShadowActivity>extract(resolved).getNextStartedActivity();
    assertNotNull(main);
    assertFalse(main.getBooleanExtra("limited_entry", true));
    assertTrue(new File(operation, "payload").isDirectory());
  }
}
