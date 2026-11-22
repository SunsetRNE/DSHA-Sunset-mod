package com.deepseekharness.app.nethealth;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.device.NetworkDiagnostics;
import com.deepseekharness.app.device.NetworkStateMonitor;
import com.deepseekharness.app.util.NetworkHealthPolicy;
import com.deepseekharness.app.util.NetworkHealthPolicy.Event;
import com.deepseekharness.app.util.NetworkHealthPolicy.Verdict;
import com.deepseekharness.app.util.NetworkSnapshot;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** 直接运行原生产类；共享协作者只替换只读边界，调度与发布行为由原类执行。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      NetworkHealthMonitorTest.ControllerShadow.class,
      NetworkHealthMonitorTest.NetworkShadow.class,
      NetworkHealthMonitorTest.DiagnosticsShadow.class,
      NetworkHealthMonitorTest.ProbeShadow.class,
      NetworkHealthMonitorTest.ProbeCompanionShadow.class,
      NetworkHealthMonitorTest.ServicesShadow.class
    })
public final class NetworkHealthMonitorTest {
  private Context app;
  private NetworkHealthMonitor monitor;
  private ManualWorker worker;
  private ScheduledExecutorService actual;
  private File store;

  @Before
  public void setUp() throws Exception {
    app = RuntimeEnvironment.getApplication();
    ControllerShadow.active = false;
    ControllerShadow.starting = false;
    ControllerShadow.fail = false;
    ControllerShadow.reads = 0;
    NetworkShadow.snapshot = NetworkSnapshot.NONE;
    NetworkShadow.queried = NetworkSnapshot.NONE;
    NetworkShadow.listener = null;
    NetworkShadow.queries = 0;
    DiagnosticsShadow.report = "DSHA_NET_V1\nresolv_state=current\n";
    ProbeShadow.calls = 0;
    ServicesShadow.services = List.of();
    ServicesShadow.fail = false;
    new ConfigStore(app).setEcoMode(false);
    setStatic("instance", null);
    Constructor<NetworkHealthMonitor> ctor =
        NetworkHealthMonitor.class.getDeclaredConstructor(Context.class);
    ctor.setAccessible(true);
    monitor = ctor.newInstance(app);
    actual = (ScheduledExecutorService) field("worker").get(monitor);
    store = (File) field("store").get(monitor);
    if (store.exists()) assertTrue(store.delete());
    File temp = new File(store.getParentFile(), "events.txt.tmp");
    if (temp.exists()) assertTrue(temp.delete());
    worker = new ManualWorker();
    field("worker").set(monitor, worker);
  }

  @After
  public void tearDown() throws Exception {
    NetworkHealthMonitor singleton = (NetworkHealthMonitor) field("instance").get(null);
    if (singleton != null && singleton != monitor) {
      ((ScheduledExecutorService) field("worker").get(singleton)).shutdownNow();
    }
    actual.shutdownNow();
    worker.shutdownNow();
    setStatic("instance", null);
  }

  @Test
  public void collectedSignalsConsumeOnlyExistingReadApis() throws Exception {
    NetworkShadow.snapshot = snapshot(false, true);
    NetworkHealthPolicy.Signals signals = (NetworkHealthPolicy.Signals) invoke("collect");
    assertTrue(signals.connected);
    assertEquals("wifi", signals.transport);
    assertEquals("current", signals.resolvState);
  }

  @Test
  public void startupLoadsBeforePublishingAndUsesFixedDelayHeartbeat() throws Exception {
    write("DSHA_NET_HEALTH_V1\n7|DNS_STALE|2|old\n");
    invoke("start");
    assertTrue(monitor.events().isEmpty());
    assertEquals(60_000, worker.period);
    assertEquals(60_000, worker.initial);
    assertNotNull(NetworkShadow.listener);
    worker.drain();
    assertEquals("old", monitor.events().get(0).evidence);
    assertThrows(UnsupportedOperationException.class, () -> monitor.events().clear());
    NetworkShadow.listener.onNetwork(NetworkStateMonitor.Event.CHANGED, NetworkSnapshot.NONE);
    worker.drain();
    assertEquals(5_000, worker.tasks.get(0).delay);
  }

  @Test
  public void backgroundAndNetworkSettleCancelWithoutInterrupting() throws Exception {
    invoke("schedule", new Class<?>[] {long.class}, 5_000L);
    monitor.onAppBackground();
    assertTrue(worker.tasks.isEmpty());
    worker.drain();
    Task first = worker.tasks.get(0);
    Task last = worker.tasks.get(1);
    assertTrue(first.cancelled);
    assertFalse(first.interrupt);
    assertEquals(3_000, last.delay);
    first.runIfPending();
    assertEquals(0, ControllerShadow.reads);
    last.runIfPending();
    assertEquals(1, ControllerShadow.reads);
  }

  @Test
  public void lateSettleReadsCurrentStateAndDoesNotRepeatProbe() throws Exception {
    NetworkShadow.snapshot = snapshot(true, true);
    monitor.onAppBackground();
    worker.drain();
    NetworkShadow.snapshot = NetworkSnapshot.NONE;
    worker.tasks.get(0).runIfPending();
    assertEquals(Verdict.OK, monitor.lastVerdict());
    assertEquals(0, ProbeShadow.calls);
    assertEquals(1, NetworkShadow.queries);
  }

  @Test
  public void heartbeatGapIsConsumedOnceAndInactiveOrEcoResetsBeat() throws Exception {
    ControllerShadow.active = true;
    field("lastBeat").setLong(monitor, SystemClock.elapsedRealtime() - 200_000);
    // Robolectric 初始时钟太小时，先推进一次，不依赖真实等待。
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(10));
    field("lastBeat").setLong(monitor, SystemClock.elapsedRealtime() - 200_000);
    invoke("heartbeat");
    assertEquals(Verdict.FGS_MISSING, monitor.lastVerdict());
    assertEquals(0L, field("pendingGap").getLong(monitor));
    assertTrue(monitor.events().get(0).evidence.contains("gap=200s"));
    invoke("heartbeat");
    assertEquals(1, monitor.events().get(0).count);
    new ConfigStore(app).setEcoMode(true);
    invoke("heartbeat");
    assertEquals(0L, field("lastBeat").getLong(monitor));
    new ConfigStore(app).setEcoMode(false);
    ControllerShadow.active = false;
    field("lastBeat").setLong(monitor, 42);
    invoke("heartbeat");
    assertEquals(0L, field("lastBeat").getLong(monitor));
  }

  @Test
  public void startingWebDoesNotReportMissingForegroundService() throws Exception {
    ControllerShadow.active = true;
    ControllerShadow.starting = true;
    invoke("evaluate");
    assertEquals(Verdict.OK, monitor.lastVerdict());
    assertTrue(monitor.events().isEmpty());
  }

  @Test
  public void automaticProbeRespectsExplanationAndManualCheckAlwaysProbesConnected()
      throws Exception {
    NetworkShadow.snapshot = snapshot(false, false);
    invoke("evaluate");
    assertEquals(1, ProbeShadow.calls);
    NetworkShadow.snapshot = snapshot(true, false);
    invoke("evaluate");
    assertEquals(1, ProbeShadow.calls);
    AtomicReference<NetworkHealthMonitor.Report> result = new AtomicReference<>();
    monitor.checkNow(result::set);
    worker.drain();
    assertEquals(2, ProbeShadow.calls);
    assertEquals(Verdict.SYSTEM_BLOCKED, result.get().verdict);
    NetworkShadow.snapshot = NetworkSnapshot.NONE;
    monitor.checkNow(null);
    worker.drain();
    assertEquals(2, ProbeShadow.calls);
  }

  @Test
  public void reportHasFrozenFormatAndRedactsDns() {
    NetworkShadow.snapshot = snapshot(false, true);
    DiagnosticsShadow.report =
        "DSHA_NET_V1\ndevice_dns=192.0.2.5\nprivate_dns=strict secret.example\n"
            + "resolv_dns=192.0.2.9\nresolv_state=current\n";
    AtomicReference<NetworkHealthMonitor.Report> result = new AtomicReference<>();
    monitor.checkNow(result::set);
    worker.drain();
    assertEquals(
        "DSHA_NET_HEALTH_CHECK_V1\nverdict=OK\nevidence=web=0 fgs=0 net=wifi vpn=0"
            + " validated=1 blocked=0 resolv=current dns=11ms https=22ms"
            + "\nprobe_host=api.deepseek.com\nDSHA_NET_V1\nprivate_dns=strict\nresolv_state=current\n",
        result.get().text);
  }

  @Test
  public void failureCallsBackNullAndCallbackExceptionIsNotSwallowed() {
    ControllerShadow.fail = true;
    AtomicReference<Object> result = new AtomicReference<>("pending");
    monitor.checkNow(result::set);
    worker.drain();
    assertNull(result.get());
    ControllerShadow.fail = false;
    java.util.concurrent.atomic.AtomicInteger callbacks =
        new java.util.concurrent.atomic.AtomicInteger();
    monitor.checkNow(
        report -> {
          assertNotNull(report);
          callbacks.incrementAndGet();
          throw new IllegalStateException("callback");
        });
    assertThrows(IllegalStateException.class, worker::drain);
    assertEquals(1, callbacks.get());
    worker.shutdown();
    assertThrows(RejectedExecutionException.class, () -> monitor.checkNow(null));
  }

  @Test
  public void callbackRunsOnSingleDaemonWorkerAfterPublication() throws Exception {
    ControllerShadow.active = true;
    field("worker").set(monitor, actual);
    CountDownLatch done = new CountDownLatch(2);
    List<Thread> threads = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    for (int i = 0; i < 2; i++) {
      monitor.checkNow(
          report -> {
            try {
              threads.add(Thread.currentThread());
              assertEquals(Verdict.FGS_MISSING, report.verdict);
              assertEquals(Verdict.FGS_MISSING, monitor.lastVerdict());
              assertFalse(monitor.events().isEmpty());
            } catch (Throwable error) {
              failure.set(error);
            } finally {
              done.countDown();
            }
          });
    }
    assertTrue(done.await(10, TimeUnit.SECONDS));
    if (failure.get() != null) throw new AssertionError(failure.get());
    assertSame(threads.get(0), threads.get(1));
    assertEquals("dsha-nethealth", threads.get(0).getName());
    assertTrue(threads.get(0).isDaemon());
    assertNotSame(Thread.currentThread(), threads.get(0));
  }

  @Test
  public void eventSnapshotsAreImmutableAndPublishedFieldsAreVolatile() throws Exception {
    assertTrue(Modifier.isVolatile(field("published").getModifiers()));
    assertTrue(Modifier.isVolatile(field("lastVerdict").getModifiers()));
    assertTrue(Modifier.isVolatile(field("instance").getModifiers()));
    invoke("record", new Class<?>[] {Verdict.class, String.class}, Verdict.DNS_STALE, "first");
    List<Event> before = monitor.events();
    invoke("record", new Class<?>[] {Verdict.class, String.class}, Verdict.DNS_STALE, "second");
    assertEquals(1, before.get(0).count);
    assertEquals(2, monitor.events().get(0).count);
    assertEquals("first", before.get(0).evidence);
    assertThrows(UnsupportedOperationException.class, () -> before.add(before.get(0)));
    List<Event> latest = monitor.events();
    invoke("record", new Class<?>[] {Verdict.class, String.class}, Verdict.OK, "");
    assertSame(latest, monitor.events());
    assertEquals(Verdict.OK, monitor.lastVerdict());
  }

  @Test
  public void badPersistedLinesKeepGoodRowsAndLargeOrDirectoryInputIsEmpty() throws Exception {
    write("DSHA_NET_HEALTH_V1\n5|DNS_STALE|0|good\nnope\n6|NEW|1|skip\n7|SYSTEM_BLOCKED|3|last\n");
    List<?> loaded = (List<?>) invoke("load");
    assertEquals(2, loaded.size());
    assertEquals(1, ((Event) loaded.get(0)).count);
    write("x".repeat(256 * 1024 + 1));
    assertTrue(((List<?>) invoke("load")).isEmpty());
    assertTrue(store.delete());
    assertTrue(store.mkdir());
    assertTrue(((List<?>) invoke("load")).isEmpty());
    assertTrue(store.delete());
  }

  @Test
  public void saveKeepsTwoHundredAndFailureDoesNotUndoPublication() throws Exception {
    List<Event> old = new ArrayList<>();
    for (int i = 0; i < 200; i++) old.add(new Event(i, Verdict.SYSTEM_BLOCKED, 1, "n" + i));
    field("events").set(monitor, old);
    invoke("record", new Class<?>[] {Verdict.class, String.class}, Verdict.DNS_STALE, "new");
    assertEquals(200, monitor.events().size());
    assertEquals("n1", monitor.events().get(0).evidence);
    assertEquals(200, NetworkHealthPolicy.parse(Files.readString(store.toPath())).size());
    assertFalse(new File(store.getParentFile(), "events.txt.tmp").exists());
    assertTrue(store.delete());
    assertTrue(store.getParentFile().delete());
    Files.writeString(store.getParentFile().toPath(), "directory blocked");
    invoke(
        "record",
        new Class<?>[] {Verdict.class, String.class},
        Verdict.EGRESS_FAILED,
        "still published");
    assertEquals(Verdict.EGRESS_FAILED, monitor.lastVerdict());
    assertEquals("still published", monitor.events().get(199).evidence);
    assertTrue(store.getParentFile().delete());
  }

  @Test
  public void foregroundQueryDistinguishesMissingOtherAppAndUnknown() throws Exception {
    ActivityManager.RunningServiceInfo info = new ActivityManager.RunningServiceInfo();
    info.service = new ComponentName("other.app", "com.deepseekharness.app.HarnessService");
    info.foreground = true;
    ServicesShadow.services = List.of(info);
    assertEquals(false, invoke("harnessForeground"));
    info.service =
        new ComponentName(app.getPackageName(), "com.deepseekharness.app.HarnessService");
    assertEquals(true, invoke("harnessForeground"));
    info.foreground = false;
    assertEquals(false, invoke("harnessForeground"));
    ServicesShadow.fail = true;
    assertEquals(true, invoke("harnessForeground"));
  }

  @Test
  public void ensureStartedPublishesOnlyOneInstanceAndNullFailureDoesNotPublish() throws Exception {
    assertThrows(NullPointerException.class, () -> NetworkHealthMonitor.ensureStarted(null));
    assertNull(field("instance").get(null));
    List<NetworkHealthMonitor> results = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Thread> callers = new ArrayList<>();
    CountDownLatch begin = new CountDownLatch(1);
    for (int i = 0; i < 8; i++) {
      Thread caller =
          new Thread(
              () -> {
                try {
                  begin.await();
                  results.add(NetworkHealthMonitor.ensureStarted(app));
                } catch (InterruptedException error) {
                  throw new AssertionError(error);
                }
              });
      callers.add(caller);
      caller.start();
    }
    begin.countDown();
    for (Thread caller : callers) {
      caller.join(3_000);
      assertFalse(caller.isAlive());
    }
    assertEquals(8, results.size());
    for (NetworkHealthMonitor result : results) assertSame(results.get(0), result);
    assertSame(results.get(0), NetworkHealthMonitor.ensureStarted(null));
  }

  @Test
  public void failedRenameLeavesDestinationAndRemovesTemporaryFile() throws Exception {
    Files.createDirectories(store.toPath());
    File sentinel = new File(store, "keep");
    Files.writeString(sentinel.toPath(), "old destination");
    invoke("record", new Class<?>[] {Verdict.class, String.class}, Verdict.DNS_STALE, "new");
    assertEquals("old destination", Files.readString(sentinel.toPath()));
    assertFalse(new File(store.getParentFile(), "events.txt.tmp").exists());
    assertEquals("new", monitor.events().get(0).evidence);
    assertTrue(sentinel.delete());
    assertTrue(store.delete());
  }

  @Test
  public void reportKeepsNullableFieldsAndIdentitySemantics() {
    NetworkHealthMonitor.Report first = new NetworkHealthMonitor.Report(null, null, null);
    assertNull(first.verdict);
    assertNull(first.evidence);
    assertNull(first.text);
    assertNotEquals(first, new NetworkHealthMonitor.Report(null, null, null));
  }

  private void write(String value) throws Exception {
    Files.createDirectories(store.getParentFile().toPath());
    Files.writeString(store.toPath(), value, StandardCharsets.UTF_8);
  }

  private static Field field(String name) throws Exception {
    Field value = NetworkHealthMonitor.class.getDeclaredField(name);
    value.setAccessible(true);
    return value;
  }

  private static void setStatic(String name, Object value) throws Exception {
    field(name).set(null, value);
  }

  private Object invoke(String name) throws Exception {
    return invoke(name, new Class<?>[0]);
  }

  private Object invoke(String name, Class<?>[] parameters, Object... values) throws Exception {
    Method method = NetworkHealthMonitor.class.getDeclaredMethod(name, parameters);
    method.setAccessible(true);
    try {
      return method.invoke(monitor, values);
    } catch (InvocationTargetException error) {
      throw (Exception) error.getCause();
    }
  }

  private static <T> T allocate(Class<T> type) {
    return org.robolectric.shadow.api.Shadow.newInstanceOf(type);
  }

  private static NetworkSnapshot snapshot(boolean blocked, boolean validated) {
    return new NetworkSnapshot(
        1, "wifi", false, validated, blocked, List.of(), "off", "", true, false);
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static class ControllerShadow {
    static boolean active, starting, fail;
    static int reads;

    @Implementation
    protected void __constructor__(Context context) {}

    @Implementation
    public static HarnessController get(Context context) {
      reads++;
      if (fail) throw new IllegalStateException("unavailable");
      return allocate(HarnessController.class);
    }

    @Implementation
    public boolean requiresRuntimeProtection() {
      return active;
    }

    @Implementation
    public boolean isStarting() {
      return starting;
    }
  }

  @Implements(value = NetworkStateMonitor.class, isInAndroidSdk = false)
  public static class NetworkShadow {
    static NetworkSnapshot snapshot, queried;
    static NetworkStateMonitor.Listener listener;
    static int queries;

    @Implementation
    protected void __constructor__(Context context) {}

    @Implementation
    public static NetworkStateMonitor ensureStarted(Context context) {
      return allocate(NetworkStateMonitor.class);
    }

    @Implementation
    public void addListener(NetworkStateMonitor.Listener value) {
      listener = value;
    }

    @Implementation
    public NetworkSnapshot current() {
      return snapshot;
    }

    @Implementation
    public static NetworkSnapshot query(Context context) {
      queries++;
      return queried;
    }
  }

  @Implements(value = NetworkDiagnostics.class, isInAndroidSdk = false)
  public static class DiagnosticsShadow {
    static String report;

    @Implementation
    public static String render(Context context, String dnsMode, List<String> roots) {
      return report;
    }
  }

  @Implements(value = NetworkHealthProbe.class, isInAndroidSdk = false)
  public static class ProbeShadow {
    static int calls;

    @Implementation
    protected static NetworkHealthPolicy.Probe run() {
      calls++;
      return new NetworkHealthPolicy.Probe(true, true, 11, 22, "");
    }
  }

  @Implements(
      className = "com.deepseekharness.app.nethealth.NetworkHealthProbe$Companion",
      isInAndroidSdk = false)
  public static class ProbeCompanionShadow {
    @Implementation
    protected NetworkHealthPolicy.Probe run() {
      return ProbeShadow.run();
    }
  }

  @Implements(ActivityManager.class)
  public static class ServicesShadow {
    static List<ActivityManager.RunningServiceInfo> services;
    static boolean fail;

    @Implementation
    protected List<ActivityManager.RunningServiceInfo> getRunningServices(int max) {
      if (fail) throw new SecurityException("unavailable");
      return services;
    }
  }

  private static final class ManualWorker extends AbstractExecutorService
      implements ScheduledExecutorService {
    final Queue<Runnable> immediate = new ArrayDeque<>();
    final List<Task> tasks = new ArrayList<>();
    long period, initial;
    boolean stopped;

    void drain() {
      while (!immediate.isEmpty()) immediate.remove().run();
    }

    @Override
    public void execute(Runnable command) {
      if (stopped) throw new RejectedExecutionException();
      immediate.add(command);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      Task task = new Task(command, unit.toMillis(delay));
      tasks.add(task);
      return task;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long delay, long period, TimeUnit unit) {
      this.initial = unit.toMillis(delay);
      this.period = unit.toMillis(period);
      return new Task(command, initial);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
      throw new AssertionError();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long delay, long period, TimeUnit unit) {
      throw new AssertionError("must use fixed delay");
    }

    @Override
    public void shutdown() {
      stopped = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      stopped = true;
      immediate.clear();
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return stopped;
    }

    @Override
    public boolean isTerminated() {
      return stopped;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return stopped;
    }
  }

  private static final class Task implements ScheduledFuture<Object> {
    final Runnable action;
    final long delay;
    boolean cancelled, interrupt, done;

    Task(Runnable action, long delay) {
      this.action = action;
      this.delay = delay;
    }

    void runIfPending() {
      if (!cancelled && !done) {
        done = true;
        action.run();
      }
    }

    @Override
    public boolean cancel(boolean interrupt) {
      cancelled = true;
      this.interrupt = interrupt;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return done || cancelled;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
      return Long.compare(delay, other.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public Object get() {
      throw new AssertionError();
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      throw new AssertionError();
    }
  }
}
