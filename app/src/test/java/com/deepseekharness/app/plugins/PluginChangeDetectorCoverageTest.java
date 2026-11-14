package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 真实 JDK NIO 的覆盖投影专项；注入观察注册口，不执行 Android native FileObserver。 */
public final class PluginChangeDetectorCoverageTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final List<PluginChangeDetector> detectors = new ArrayList<>();
  private final AtomicInteger content = new AtomicInteger();
  private final AtomicInteger runtime = new AtomicInteger();
  private final AtomicInteger reconcile = new AtomicInteger();
  private final AtomicInteger changed = new AtomicInteger();
  private final ManualWorker worker = new ManualWorker();
  private Path root;
  private boolean registrationFailure;
  private String registrationMessage = "private path and arbitrary message";
  private Runnable onStart;
  private PluginChangeDetector detector;

  @Before
  public void setup() throws Exception {
    root = temporary.newFolder("host").toPath();
    detector = create(new PluginChangeDetector.Limits(128, 2048, 32, 1000), () -> worker.now);
  }

  @After
  public void close() {
    for (PluginChangeDetector value : detectors) value.close();
    worker.runDue();
  }

  private PluginChangeDetector create(
      PluginChangeDetector.Limits limits, PluginChangeDetector.Clock clock) {
    PluginChangeDetector value =
        new PluginChangeDetector(
            content::incrementAndGet,
            runtime::incrementAndGet,
            reconcile::incrementAndGet,
            changed::incrementAndGet,
            worker,
            clock,
            (directory, events) -> {
              if (registrationFailure) throw new IOException(registrationMessage);
              if (onStart != null) {
                Runnable action = onStart;
                onStart = null;
                action.run();
              }
              return () -> {};
            },
            limits);
    detectors.add(value);
    return value;
  }

  private PluginChangeDetector.WatchPath path(Path target) {
    return new PluginChangeDetector.WatchPath(
        root.toFile(), target.toFile(), PluginChangeDetector.Kind.CONTENT, true);
  }

  private void bind(Path target) {
    detector.bind(List.of(path(target)), "source");
    detector.foreground(true);
    worker.runDue();
  }

  private void reason(String reason) {
    assertTrue(detector.coverageLimited());
    assertTrue(detector.coverageReasons().toString(), detector.coverageReasons().contains(reason));
    assertTrue(detector.coverageReasons().size() <= 8);
    for (String value : detector.coverageReasons()) assertTrue(value.matches("WATCH_[A-Z_]+"));
  }

  @Test
  public void initialAndBackgroundAreUnknownWithoutGetterWork() throws Exception {
    reason("WATCH_NOT_OBSERVING");
    PluginChangeDetector.Coverage initial = detector.coverage();
    for (int i = 0; i < 100; i++) {
      assertSame(initial, detector.coverage());
      assertSame(initial.reasons, detector.coverageReasons());
    }
    assertEquals(0, worker.jobs.size());
    assertEquals(0, changed.get());
    bind(Files.createDirectories(root.resolve("plugins")));
    assertFalse(detector.coverageLimited());
    assertEquals(List.of(), detector.coverageReasons());
    detector.foreground(false);
    reason("WATCH_NOT_OBSERVING");
    worker.runDue();
    int callbacks = changed.get();
    worker.now = 600_000;
    worker.runDue();
    assertEquals(callbacks, changed.get());
    assertEquals(0, worker.jobs.size());
  }

  @Test
  public void observerFailureIsFixedCodeAndForegroundCanRecover() throws Exception {
    registrationFailure = true;
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(plugins);
    assertEquals(List.of("WATCH_OBSERVER_FAILED"), detector.coverageReasons());
    assertEquals(0, content.get());
    assertEquals(0, runtime.get());
    int callbacks = changed.get(), checks = reconcile.get();
    for (int i = 0; i < 100; i++) detector.coverage();
    assertEquals(callbacks, changed.get());
    assertEquals(checks, reconcile.get());
    assertEquals(0, worker.jobs.size());
    detector.foreground(false);
    worker.runDue();
    registrationFailure = false;
    worker.now = 750;
    detector.foreground(true);
    worker.runDue();
    assertFalse(detector.coverageLimited());
    assertEquals(List.of(), detector.coverageReasons());
  }

  @Test
  public void observerErrorTextCannotPretendPathChangedAndStartRepairLoop() throws Exception {
    registrationFailure = true;
    registrationMessage = "PLUGIN_WATCH_PATH_CHANGED";
    bind(Files.createDirectories(root.resolve("plugins")));
    assertEquals(List.of("WATCH_OBSERVER_FAILED"), detector.coverageReasons());
    assertEquals(0, content.get());
    worker.now = 600_000;
    worker.runDue();
    assertEquals(0, worker.jobs.size());
  }

  @Test
  public void directoryBudgetIsExplicitAndDoesNotSchedulePeriodicRetry() throws Exception {
    detector = create(new PluginChangeDetector.Limits(1, 2048, 32, 1000), () -> worker.now);
    bind(Files.createDirectories(root.resolve("plugins/child")).getParent());
    reason("WATCH_DIRECTORY_LIMIT");
    worker.now = 600_000;
    worker.runDue();
    assertEquals(0, worker.jobs.size());
  }

  @Test
  public void entryBudgetReportsOnlyFixedReason() throws Exception {
    detector = create(new PluginChangeDetector.Limits(128, 1, 32, 1000), () -> worker.now);
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    Files.writeString(plugins.resolve("a.js"), "private source");
    Files.writeString(plugins.resolve("b.js"), "private source");
    bind(plugins);
    reason("WATCH_ENTRY_LIMIT");
  }

  @Test
  public void depthBudgetReportsExplicitly() throws Exception {
    detector = create(new PluginChangeDetector.Limits(128, 2048, 1, 1000), () -> worker.now);
    bind(Files.createDirectories(root.resolve("plugins/a/b")).getParent().getParent());
    reason("WATCH_DEPTH_LIMIT");
  }

  @Test
  public void elapsedBudgetIsDistinctFromPathFailure() throws Exception {
    AtomicInteger clock = new AtomicInteger();
    detector = create(new PluginChangeDetector.Limits(128, 2048, 32, 1), clock::incrementAndGet);
    bind(Files.createDirectories(root.resolve("plugins")));
    reason("WATCH_TIME_LIMIT");
    assertFalse(detector.coverageReasons().contains("WATCH_PATH_UNREADABLE"));
  }

  @Test
  public void absentTrustedRootIsUnreadableAndEmptyInputsAreUnknown() {
    Path absent = root.resolve("absent");
    detector.bind(
        List.of(
            new PluginChangeDetector.WatchPath(
                absent.toFile(), absent.toFile(), PluginChangeDetector.Kind.CONTENT, true)),
        "absent-source");
    detector.foreground(true);
    worker.runDue();
    reason("WATCH_PATH_UNREADABLE");
    detector.bind(List.of(), "empty-source");
    worker.runDue();
    worker.now = 750;
    worker.runDue();
    reason("WATCH_NO_PATHS");
  }

  @Test
  public void tooManyPathsRemainRejectedAndImmutable() {
    List<PluginChangeDetector.WatchPath> paths = new ArrayList<>();
    for (int i = 0; i < 129; i++) paths.add(path(root));
    try {
      detector.bind(paths, "source");
      fail();
    } catch (IllegalArgumentException expected) {
      reason("WATCH_PATH_LIMIT");
    }
    try {
      detector.coverageReasons().add("private-message");
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    assertEquals(0, worker.jobs.size());
    assertEquals(0, content.get());
  }

  @Test
  public void oldRebuildCannotPublishCompleteAfterBackground() throws Exception {
    onStart = () -> detector.foreground(false);
    bind(Files.createDirectories(root.resolve("plugins")));
    reason("WATCH_NOT_OBSERVING");
    worker.now = 600_000;
    worker.runDue();
    reason("WATCH_NOT_OBSERVING");
    assertEquals(0, worker.jobs.size());
  }

  @Test
  public void unchangedBindingAndGetterNeverCreateCoverageQueryLoop() throws Exception {
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    bind(plugins);
    int callbacks = changed.get(), checks = reconcile.get();
    for (int i = 0; i < 100; i++) {
      detector.bind(List.of(path(plugins)), "source");
      detector.coverage();
    }
    worker.runDue();
    assertEquals(callbacks, changed.get());
    assertEquals(checks, reconcile.get());
    assertEquals(0, content.get());
    assertEquals(0, worker.jobs.size());
  }

  private static final class ManualWorker implements PluginChangeDetector.Scheduler {
    long now;
    final List<Job> jobs = new ArrayList<>();

    public void execute(Runnable work) {
      schedule(work, 0);
    }

    public PluginChangeDetector.Cancellation schedule(Runnable work, long delay) {
      Job job = new Job(now + delay, work);
      jobs.add(job);
      return () -> job.cancelled = true;
    }

    public void close() {}

    void runDue() {
      int remaining = 1000;
      while (true) {
        jobs.removeIf(job -> job.cancelled);
        jobs.sort(Comparator.comparingLong(job -> job.at));
        if (jobs.isEmpty() || jobs.get(0).at > now) return;
        assertTrue("no coverage-induced scheduling loop", --remaining > 0);
        jobs.remove(0).work.run();
      }
    }
  }

  private static final class Job {
    final long at;
    final Runnable work;
    boolean cancelled;

    Job(long at, Runnable work) {
      this.at = at;
      this.work = work;
    }
  }
}
