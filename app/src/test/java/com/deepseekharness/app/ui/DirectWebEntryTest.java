package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.EntryTarget;
import java.util.ArrayList;
import java.util.List;
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
import org.robolectric.shadows.ShadowActivity;

/** 桌面图标直启 Web 的入口编排：只用宿主状态替身，不运行 guest、不发真实启动请求。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = DirectWebEntryTest.App.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {
      DirectWebEntryTest.Controller.class,
      DirectWebEntryTest.EntryMaintenance.class,
      DirectWebEntryTest.EnvironmentAccess.class,
      DirectWebEntryTest.VisibleStart.class,
      MainEntryLayoutLifecycleTest.Environment.class,
      com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest.HostFileSystem.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class DirectWebEntryTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static String authUrl = "";
    static int startRequests;
    static boolean starting, environmentReady, unresolved;
    static final List<Runnable> checks = new ArrayList<>();
    static final java.util.Set<Runnable> readyListeners = new java.util.LinkedHashSet<>();

    @Implementation
    protected void addReadyWebPageListener(Runnable listener) {
      readyListeners.add(listener);
    }

    @Implementation
    protected void removeReadyWebPageListener(Runnable listener) {
      readyListeners.remove(listener);
    }

    static void notifyReady() {
      for (Runnable listener : List.copyOf(readyListeners)) listener.run();
    }

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {
      if (completed != null) checks.add(completed);
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      return environmentReady;
    }

    @Implementation
    protected boolean hasUnresolvedWebSession() {
      return unresolved;
    }

    @Implementation
    protected boolean isRestoringWebSession() {
      return false;
    }

    @Implementation
    protected long getWebGeneration() {
      return 1;
    }

    @Implementation
    protected String getWebAuthUrl() {
      return authUrl;
    }

    @Implementation
    protected boolean isStarting() {
      return starting;
    }

    @Implementation
    protected boolean isStopping() {
      return false;
    }

    @Implementation
    protected ConfigStore config() {
      return new ConfigStore(RuntimeEnvironment.getApplication());
    }

    /** 浏览器路由会读取已就绪的页面身份；没有已保留的页面时为 null。 */
    @Implementation
    protected com.deepseekharness.app.util.PreviewPageSession.Identity getReadyWebPageIdentity() {
      return null;
    }

    /** 主界面恢复横幅的每秒轮询会读取启动记录；用真实的私有目录实现即可。 */
    @Implementation
    protected com.deepseekharness.app.core.StartupDiagnostics startupDiagnostics() {
      return new com.deepseekharness.app.core.StartupDiagnostics(
          RuntimeEnvironment.getApplication());
    }

    @Implementation
    protected boolean startProtectedWeb(
        com.deepseekharness.app.util.WebStartProtection.Ticket ticket,
        boolean safe,
        boolean recover,
        java.util.function.Consumer<String> status) {
      startRequests++;
      return false;
    }
  }

  @Implements(value = MainEntryMaintenance.class, isInAndroidSdk = false)
  public static final class EntryMaintenance {
    static boolean blocked;

    @Implementation
    protected static boolean blocksEntry(HarnessController ignored) {
      return blocked;
    }
  }

  @Implements(value = com.deepseekharness.app.core.EnvironmentAccess.class, isInAndroidSdk = false)
  public static final class EnvironmentAccess {
    @Implementation
    protected static String limitedEntryKey(HarnessController ignored) {
      return "synthetic-install-identity";
    }

    @Implementation
    protected static boolean requiresMaintenanceBeforeStart(HarnessController ignored) {
      return false;
    }
  }

  @Implements(value = FirstRunWebStart.class, isInAndroidSdk = false)
  public static final class VisibleStart {
    static int requests;

    @Implementation
    protected static boolean enqueueVisible(
        androidx.fragment.app.FragmentActivity activity,
        java.util.concurrent.Executor executor,
        boolean safe,
        boolean recover,
        java.util.function.Consumer<String> status,
        java.util.function.Consumer<Boolean> completed) {
      assertFalse(safe);
      assertFalse(recover);
      requests++;
      Controller.starting = true;
      completed.accept(true);
      return true;
    }
  }

  @Before
  public void prepare() throws Exception {
    var app = (App) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    com.deepseekharness.app.core.InstallationIdentity.loadOrCreate(app);
    Controller.value = org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    Controller.authUrl = "";
    Controller.startRequests = 0;
    Controller.starting = false;
    Controller.environmentReady = false;
    Controller.unresolved = false;
    EntryMaintenance.blocked = false;
    VisibleStart.requests = 0;
    Controller.checks.clear();
    Controller.readyListeners.clear();
    ConfigStore config = new ConfigStore(app);
    config.setWelcomed(true);
    config.setCheckUpdate(false);
    config.setEntryTarget(EntryTarget.MAIN);
  }

  private static Intent launcher() {
    return new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER);
  }

  private static boolean field(MainActivity main, String name) throws Exception {
    var field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.getBoolean(main);
  }

  private static Object object(MainActivity main, String name) throws Exception {
    var field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(main);
  }

  private static void beginDirectWeb(MainActivity main) throws Exception {
    var probe = MainActivity.class.getDeclaredField("restoreProbeStarted");
    probe.setAccessible(true);
    probe.setBoolean(main, true);
    var begin =
        MainActivity.class.getDeclaredMethod(
            "beginDirectWeb", Bundle.class, ConfigStore.class, HarnessController.class);
    begin.setAccessible(true);
    begin.invoke(main, null, new ConfigStore(main), Controller.value);
  }

  private static Intent drainUntil(MainActivity main, Class<?> target) {
    ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(main);
    for (Intent next; (next = shadow.getNextStartedActivity()) != null; )
      if (next.getComponent() != null
          && target.getName().equals(next.getComponent().getClassName())) return next;
    return null;
  }

  @Test
  public void defaultPreferenceShowsTheBootScreenButNeverArmsDirectWeb() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class, launcher()).create().start().resume();
    try {
      MainActivity main = host.get();
      assertFalse(field(main, "directWebArmed"));
      assertNotNull("开机加载页在入口检查期间可见", object(main, "bootScreen"));
    } finally {
      host.pause().stop().destroy();
    }
  }

  @Test
  public void notificationAndRestoredEntriesNeverArmDirectWeb() throws Exception {
    new ConfigStore(RuntimeEnvironment.getApplication()).setEntryTarget(EntryTarget.WEB);
    var notification =
        Robolectric.buildActivity(
                MainActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
                    .putExtra("open_web", true))
            .create()
            .start()
            .resume();
    try {
      assertFalse(field(notification.get(), "directWebArmed"));
    } finally {
      notification.pause().stop().destroy();
    }
    var first = Robolectric.buildActivity(MainActivity.class, launcher()).create().start().resume();
    Bundle saved = new Bundle();
    first.pause().saveInstanceState(saved).stop().destroy();
    var restored =
        Robolectric.buildActivity(MainActivity.class, launcher()).create(saved).start().resume();
    try {
      assertFalse(field(restored.get(), "directWebArmed"));
    } finally {
      restored.pause().stop().destroy();
    }
  }

  /** 进程被系统回收后的保存态：同进程令牌不再匹配。 */
  private static Bundle crossProcessState() {
    var first = Robolectric.buildActivity(MainActivity.class, launcher()).create().start().resume();
    Bundle saved = new Bundle();
    first.pause().saveInstanceState(saved).stop().destroy();
    saved.remove(com.deepseekharness.app.util.MainRecreatePolicy.STATE_KEY);
    return saved;
  }

  @Test
  public void processRestoreReturnsToWebOnlyWhenTheUserLeftFromWeb() throws Exception {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setEntryTarget(EntryTarget.WEB);
    Bundle saved = crossProcessState();
    config.setLastForegroundWeb(true);
    // 最近任务恢复不带桌面图标意图：只按离开前的位置决定。
    var fromWeb =
        Robolectric.buildActivity(
                MainActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), MainActivity.class))
            .create(saved)
            .start()
            .resume();
    try {
      assertTrue("离开前停在 Web：恢复后回到 Web", field(fromWeb.get(), "directWebArmed"));
    } finally {
      fromWeb.pause().stop().destroy();
    }
    config.setLastForegroundWeb(false);
    var fromMain =
        Robolectric.buildActivity(MainActivity.class, launcher()).create(saved).start().resume();
    try {
      assertFalse("离开前停在主界面：恢复主界面", field(fromMain.get(), "directWebArmed"));
    } finally {
      fromMain.pause().stop().destroy();
    }
  }

  @Test
  public void notificationAppEntryArmsDirectWebOnlyWhenWebIsPreferred() throws Exception {
    Intent entry =
        new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
            .putExtra(AppEntryRouterActivity.EXTRA_APP_ENTRY, true)
            .putExtra("open_launch", true);
    var main = Robolectric.buildActivity(MainActivity.class, entry).create().start().resume();
    try {
      assertFalse(field(main.get(), "directWebArmed"));
    } finally {
      main.pause().stop().destroy();
    }
    new ConfigStore(RuntimeEnvironment.getApplication()).setEntryTarget(EntryTarget.WEB);
    var web =
        Robolectric.buildActivity(MainActivity.class, new Intent(entry)).create().start().resume();
    try {
      assertTrue(field(web.get(), "directWebArmed"));
    } finally {
      web.pause().stop().destroy();
    }
  }

  @Test
  public void openingWebFromTheMainScreenCoversMainOnlyWhenWebIsPreferred() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class, launcher()).create().start().resume();
    try {
      MainActivity main = host.get();
      var ready = MainActivity.class.getDeclaredField("mainReady");
      ready.setAccessible(true);
      ready.setBoolean(main, true);
      // 主界面已显示：入口加载页已收起（showMain 的效果，不运行真实启动页）。
      var dismiss = MainActivity.class.getDeclaredMethod("dismissEntryOverlay");
      dismiss.setAccessible(true);
      dismiss.invoke(main);
      main.startActivity(
          WebPreviewActivity.intent(main, "http://127.0.0.1:1/?token=fixture", null));
      assertNull("偏好为主界面：行为不变", object(main, "webCover"));
      new ConfigStore(main).setEntryTarget(EntryTarget.WEB);
      main.startActivity(
          WebPreviewActivity.intent(main, "http://127.0.0.1:1/?token=fixture", null));
      assertNotNull("偏好为 Web：打开前先盖遮罩", object(main, "webCover"));
    } finally {
      host.pause().stop().destroy();
    }
  }

  @Test
  public void readyAuthenticationOpensWebOnceAndMainCompletesBehindIt() throws Exception {
    new ConfigStore(RuntimeEnvironment.getApplication()).setEntryTarget(EntryTarget.WEB);
    var host = Robolectric.buildActivity(MainActivity.class, launcher()).create().start().resume();
    try {
      MainActivity main = host.get();
      assertTrue(field(main, "directWebArmed"));
      Controller.authUrl = "http://127.0.0.1:43210/?token=fixture";
      beginDirectWeb(main);
      // 只推进到所需的任务：不运行启动页每秒刷新，避免依赖真实运行时。
      var looper = org.robolectric.shadows.ShadowLooper.shadowMainLooper();
      Intent web = null;
      for (int i = 0; i < 20 && web == null; i++) {
        looper.runOneTask();
        web = drainUntil(main, WebPreviewActivity.class);
      }
      assertNotNull("鉴权就绪后应打开 Web", web);
      assertEquals(Controller.authUrl, web.getStringExtra("url"));
      // showMain 经 view.post 排在 Web 打开之后；只运行当前已到期的任务。
      for (int i = 0; i < 20 && !field(main, "mainReady"); i++) {
        if (looper.isIdle()) looper.idleFor(java.time.Duration.ofMillis(1));
        else looper.runOneTask();
      }
      assertTrue("主界面在 Web 之下完成初始化", field(main, "mainReady"));
      assertFalse(field(main, "directWebArmed"));
      assertNull(drainUntil(main, WebPreviewActivity.class));
      assertEquals("已就绪的实例不重复启动", 0, Controller.startRequests);
      // 打开 Web 后加载页转为遮罩留在 Main 上：任务切换期间不露出主界面。
      var cover = (BootScreen) object(main, "webCover");
      assertNotNull("Web 打开后保留遮罩", cover);
      assertTrue(cover.attached());
      // 没有待返回的原网页时，Main 回到前台就是原生页面：收起遮罩。
      new ConfigStore(main).setLastForegroundWeb(true); // Web 在前台时由浏览器宿主记下
      host.pause().stop().restart().resume();
      assertNull("回到原生主界面时收起遮罩", object(main, "webCover"));
      assertFalse("原生页面回到前台后，回收恢复去主界面", new ConfigStore(main).isLastForegroundWeb());
    } finally {
      host.pause().stop().destroy();
    }
  }

  @Test
  public void openMainFromTheBootScreenAbandonsDirectWebWithoutOpeningIt() throws Exception {
    new ConfigStore(RuntimeEnvironment.getApplication()).setEntryTarget(EntryTarget.WEB);
    var host = Robolectric.buildActivity(MainActivity.class, launcher()).create().start().resume();
    try {
      MainActivity main = host.get();
      beginDirectWeb(main);
      var launch = (DirectWebLaunch) object(main, "directWeb");
      assertNotNull(launch);
      launch.abandon();
      assertTrue("放弃直启同步回到主界面", field(main, "mainReady"));
      assertFalse(field(main, "directWebArmed"));
      assertNull(drainUntil(main, WebPreviewActivity.class));
    } finally {
      host.pause().stop().destroy();
    }
  }

  @Test
  public void warmLauncherWaitsForTheCurrentWebThenOpensOnceWithoutReplacingTheMainView()
      throws Exception {
    try (var entry = new WarmEntry()) {
      entry.deliver(launcher());
      assertEquals(1, Controller.checks.size());
      assertNotNull(object(entry.main, "bootScreen"));
      assertSame(
          entry.root, entry.main.findViewById(com.deepseekharness.app.R.id.fragment_container));
      assertNull(drainUntil(entry.main, WebPreviewActivity.class));
      entry.deliverLastCheck();
      entry.await(
          () ->
              Controller.readyListeners.size() == entry.nativeReadyListeners.size() + 1
                  && VisibleStart.requests == 1);
      entry.main.onNewIntent(launcher());
      entry.main.onPostResume();
      assertEquals("Repeated entry keeps the admitted Web launch", 1, Controller.checks.size());
      assertEquals(entry.nativeReadyListeners.size() + 1, Controller.readyListeners.size());
      assertEquals(1, VisibleStart.requests);
      Controller.authUrl = "http://127.0.0.1:43210/?token=fixture";
      Controller.notifyReady();
      Intent web = drainUntil(entry.main, WebPreviewActivity.class);
      assertNotNull(web);
      assertEquals(Controller.authUrl, web.getStringExtra("url"));
      assertNull(drainUntil(entry.main, WebPreviewActivity.class));
      assertNull(object(entry.main, "bootScreen"));
      assertNotNull(object(entry.main, "webCover"));
      assertEquals(0, ((Long) object(entry.main, "entryWindow")).longValue());
      assertEquals(entry.nativeReadyListeners, Controller.readyListeners);
      assertEquals(0, Controller.startRequests);
      assertSame(
          entry.root, entry.main.findViewById(com.deepseekharness.app.R.id.fragment_container));
    }
  }

  @Test
  public void warmPlainResumeMainPreferenceAndPendingPluginDoNotCheckForWeb() throws Exception {
    try (var entry = new WarmEntry()) {
      entry.host.pause().resume().postResume(); // The original getIntent() is still the launcher.
      assertTrue(Controller.checks.isEmpty());
      new ConfigStore(entry.main).setEntryTarget(EntryTarget.MAIN);
      entry.deliver(launcher());
      assertTrue(Controller.checks.isEmpty());
      new ConfigStore(entry.main).setEntryTarget(EntryTarget.WEB);
      entry
          .main
          .getSharedPreferences("dsha-install-link", Context.MODE_PRIVATE)
          .edit()
          .putString("pending", "synthetic")
          .commit();
      entry.deliver(launcher());
      assertTrue(Controller.checks.isEmpty());
      assertNull(object(entry.main, "bootScreen"));
      assertNull(drainUntil(entry.main, WebPreviewActivity.class));
    }
  }

  @Test
  public void warmNotificationIsAdmittedButAnExplicitNativeIntentCancelsIt() throws Exception {
    try (var entry = new WarmEntry()) {
      entry.deliver(
          new Intent(entry.main, MainActivity.class)
              .putExtra(AppEntryRouterActivity.EXTRA_APP_ENTRY, true)
              .putExtra("open_launch", true));
      assertEquals(1, Controller.checks.size());
      assertNotNull(object(entry.main, "bootScreen"));
      entry.main.onNewIntent(
          MainActivity.navigationIntent(entry.main).putExtra("open_config", true));
      entry.main.onPostResume();
      entry.main.getSupportFragmentManager().executePendingTransactions();
      assertTrue(
          entry
                  .main
                  .getSupportFragmentManager()
                  .findFragmentById(com.deepseekharness.app.R.id.fragment_container)
              instanceof ConfigFragment);
      entry.deliverLastCheck();
      assertNull(object(entry.main, "bootScreen"));
      assertEquals(0, ((Long) object(entry.main, "entryWindow")).longValue());
      assertTrue(Controller.readyListeners.isEmpty());
      assertNull(drainUntil(entry.main, WebPreviewActivity.class));
    }
  }

  @Test
  public void warmUnknownInstanceAndLifecycleCancellationReturnToTheExistingMain()
      throws Exception {
    try (var entry = new WarmEntry()) {
      Controller.unresolved = true;
      entry.deliver(launcher());
      entry.deliverLastCheck();
      entry.await(() -> object(entry.main, "bootScreen") == null);
      assertEquals(0, ((Long) object(entry.main, "entryWindow")).longValue());
      assertNull(drainUntil(entry.main, WebPreviewActivity.class));
      Controller.unresolved = false;
      entry.deliver(launcher());
      assertNotNull(object(entry.main, "bootScreen"));
      entry.main.getOnBackPressedDispatcher().onBackPressed();
      entry.deliverLastCheck();
      assertNull(object(entry.main, "bootScreen"));
      entry.deliver(launcher());
      entry.host.pause();
      entry.deliverLastCheck();
      entry.host.resume().postResume();
      assertNull(object(entry.main, "bootScreen"));
      assertEquals(entry.nativeReadyListeners, Controller.readyListeners);
      assertSame(
          entry.root, entry.main.findViewById(com.deepseekharness.app.R.id.fragment_container));
    }
  }

  @Test
  public void warmEntryKeepsMaintenanceAheadOfTheReadyWeb() throws Exception {
    try (var entry = new WarmEntry()) {
      EntryMaintenance.blocked = true;
      Controller.authUrl = "http://127.0.0.1:43210/?token=fixture";
      entry.deliver(launcher());
      entry.deliverLastCheck();
      entry.await(entry.main::isFinishing);
      assertNotNull(drainUntil(entry.main, ExtractActivity.class));
      assertNull(drainUntil(entry.main, WebPreviewActivity.class));
      assertEquals(0, Controller.startRequests);
    }
  }

  @Test
  public void warmEntryRetainsThePreviouslyApprovedRestrictedNativePage() throws Exception {
    try (var entry = new WarmEntry()) {
      new ConfigStore(entry.main).allowLimitedEntry("synthetic-install-identity");
      EntryMaintenance.blocked = true;
      Controller.authUrl = "http://127.0.0.1:43210/?token=fixture";
      entry.deliver(launcher());
      assertNotNull(object(entry.main, "bootScreen"));
      entry.deliverLastCheck();
      entry.await(() -> object(entry.main, "bootScreen") == null);
      assertFalse(entry.main.isFinishing());
      assertNull(drainUntil(entry.main, ExtractActivity.class));
      assertNull(drainUntil(entry.main, WebPreviewActivity.class));
      assertEquals(0, VisibleStart.requests);
      assertEquals(0, ((Long) object(entry.main, "entryWindow")).longValue());
    }
  }

  private static final class WarmEntry implements AutoCloseable {
    final org.robolectric.android.controller.ActivityController<MainActivity> host =
        Robolectric.buildActivity(MainActivity.class, launcher()).create().start().resume();
    final MainActivity main = host.get();
    final android.view.View root =
        main.findViewById(com.deepseekharness.app.R.id.fragment_container);
    final java.util.Set<Runnable> nativeReadyListeners;

    WarmEntry() throws Exception {
      var probe = MainActivity.class.getDeclaredField("restoreProbeStarted");
      probe.setAccessible(true);
      probe.setBoolean(main, true);
      var show =
          MainActivity.class.getDeclaredMethod(
              "showMain", Bundle.class, ConfigStore.class, HarnessController.class);
      show.setAccessible(true);
      show.invoke(main, null, new ConfigStore(main), Controller.value);
      main.getSupportFragmentManager().executePendingTransactions();
      nativeReadyListeners = java.util.Set.copyOf(Controller.readyListeners);
      Controller.checks.clear();
      Controller.environmentReady = true;
      new ConfigStore(main).setEntryTarget(EntryTarget.WEB);
    }

    void deliver(Intent intent) {
      host.pause().newIntent(intent).resume().postResume();
    }

    void deliverLastCheck() {
      Controller.checks.get(Controller.checks.size() - 1).run();
    }

    void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
      var looper = org.robolectric.shadows.ShadowLooper.shadowMainLooper();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      while (!condition.call() && System.nanoTime() < deadline) {
        looper.idle();
        Thread.sleep(5);
      }
      assertTrue("Main must consume the finite entry result", condition.call());
    }

    @Override
    public void close() {
      host.pause().stop().destroy();
    }
  }

  private static final class WaitingEntry implements AutoCloseable {
    final org.robolectric.android.controller.ActivityController<
            androidx.fragment.app.FragmentActivity>
        host = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity.class).setup();
    final DirectWebLaunch launch;
    int opened, abandoned;

    WaitingEntry() {
      Controller.starting = true;
      launch =
          new DirectWebLaunch(
              host.get(),
              new BootScreen(host.get(), com.deepseekharness.app.util.BootPalette.fromWeb(-1, -1)),
              () -> abandoned++,
              () -> opened++);
      launch.begin();
      org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    }

    Intent nextActivity() {
      ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(host.get());
      return shadow.getNextStartedActivity();
    }

    @Override
    public void close() {
      launch.cancel();
      host.pause().stop().destroy();
    }
  }

  @Test
  public void readyEventOpensDirectEntryWithoutWaitingForThe400MillisecondPoll() {
    try (var entry = new WaitingEntry()) {
      assertEquals(1, Controller.readyListeners.size());
      assertNull(entry.nextActivity());
      long before = android.os.SystemClock.uptimeMillis();
      Controller.authUrl = "http://127.0.0.1:43210/?token=fixture";
      Controller.notifyReady();
      assertEquals(before, android.os.SystemClock.uptimeMillis());
      assertEquals(1, entry.opened);
      assertEquals(Controller.authUrl, entry.nextActivity().getStringExtra("url"));
      assertTrue("Successful entry detaches its listener", Controller.readyListeners.isEmpty());
      org.robolectric.shadows.ShadowLooper.shadowMainLooper()
          .idleFor(java.time.Duration.ofSeconds(1));
      assertEquals(1, entry.opened);
      assertNull(entry.nextActivity());
      assertEquals(0, Controller.startRequests);
    }
  }

  @Test
  public void pausedDirectEntryWaitsForForegroundAndKeepsThePollFallback() {
    try (var entry = new WaitingEntry()) {
      entry.host.pause();
      Controller.authUrl = "http://127.0.0.1:43210/?token=fixture";
      Controller.notifyReady();
      assertEquals(0, entry.opened);
      assertNull(entry.nextActivity());
      entry.host.resume();
      org.robolectric.shadows.ShadowLooper.shadowMainLooper()
          .idleFor(java.time.Duration.ofMillis(400));
      assertEquals(1, entry.opened);
      assertNotNull(entry.nextActivity());
    }
  }

  @Test
  public void abandonedDirectEntryIgnoresAnAlreadyQueuedReadyEvent() {
    try (var entry = new WaitingEntry()) {
      Runnable stale = Controller.readyListeners.iterator().next();
      entry.launch.abandon();
      assertEquals(1, entry.abandoned);
      assertTrue(Controller.readyListeners.isEmpty());
      Controller.authUrl = "http://127.0.0.1:43210/?token=fixture";
      stale.run();
      org.robolectric.shadows.ShadowLooper.shadowMainLooper()
          .idleFor(java.time.Duration.ofSeconds(1));
      assertEquals(0, entry.opened);
      assertNull(entry.nextActivity());
      assertEquals(0, Controller.startRequests);
    }
  }

  @Test
  public void unreadyEventsDoNotStartOrAbandonAnExistingDirectEntry() {
    try (var entry = new WaitingEntry()) {
      for (int i = 0; i < 20; i++) Controller.notifyReady();
      assertTrue(entry.launch.active());
      assertEquals(0, entry.abandoned);
      assertEquals(0, entry.opened);
      assertNull(entry.nextActivity());
      assertEquals(0, Controller.startRequests);
    }
  }
}
