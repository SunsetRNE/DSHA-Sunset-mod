package com.deepseekharness.app.transfer;

import static com.deepseekharness.app.transfer.TransferBehaviorTestSupport.*;
import static org.junit.Assert.*;

import android.app.Application;
import android.net.Uri;
import com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 真实持久化队列与发布流程，只控制调度、Android 存储和宿主初始化边界。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      TransferBehaviorTestSupport.Controller.class,
      TransferBehaviorTestSupport.HostFiles.class,
      TransferBehaviorTestSupport.Documents.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class DownloadEngineBehaviorTest {
  private static final byte[] BYTES = "真实队列回归内容".getBytes(StandardCharsets.UTF_8);
  private TransferBehaviorTestSupport fixture;
  private DownloadEngine engine;

  @Before
  public void prepare() throws Exception {
    fixture = new TransferBehaviorTestSupport();
    engine = fixture.engine();
  }

  @After
  public void release() throws Exception {
    fixture.close();
  }

  private String enqueue(String name, String destination) throws Exception {
    String id =
        engine.enqueue(artifact("artifact-" + name.replace('.', '-'), name, BYTES), destination);
    fixture.partial(id, BYTES);
    return id;
  }

  private static String status(DownloadEngine engine, String id) throws Exception {
    return engine.snapshot(id).getString("status");
  }

  @Test
  public void oneDrainPublishesQueuedTasksOnceInAdmissionOrder() throws Exception {
    String first = enqueue("first.bin", "downloads");
    String second = enqueue("second.bin", "downloads");
    String third = enqueue("third.bin", "downloads");
    long ticket = engine.runQueued();
    assertEquals(ticket, engine.runQueued());
    assertEquals("重复 Service 唤起不能重复调度 drain", 1, queue(engine).size());
    assertTrue(engine.isBusy());
    queue(engine).runAll();
    idle();
    assertEquals(List.of("first.bin", "second.bin", "third.bin"), fixture.media.insertedNames);
    for (String id : List.of(first, second, third)) {
      assertEquals("COMPLETE", status(engine, id));
      assertFalse(engine.snapshot(id).getString("uri").isEmpty());
      assertFalse(fixture.partial(id).exists());
      assertEquals("COMPLETE", fixture.journal(id).getString("status"));
    }
    assertFalse(engine.isBusy());
    assertEquals(
        List.of(third, second, first),
        engine.state().getValue().tasks.stream().map(t -> t.id).toList());
    assertEquals("", engine.state().getValue().activeId);
    assertEquals("已有完整 partial 不得去请求运行环境", 0, Controller.readyReads);
  }

  @Test
  public void restartPausesEveryInterruptedStageAndUsesActualPartialLengthWithoutStartingService()
      throws Exception {
    List<String> interrupted = List.of("DOWNLOADING", "VERIFYING", "PUBLISHING");
    for (int index = 0; index < interrupted.size(); index++) {
      String id = enqueue("restored-" + index + ".bin", "downloads");
      fixture.partial(id, new byte[index + 1]);
      JSONObject journal = fixture.journal(id);
      journal.put("status", interrupted.get(index)).put("downloaded", 999_999);
      Files.writeString(
          new File(fixture.partial(id).getParentFile(), "task.json").toPath(),
          journal.toString(),
          StandardCharsets.UTF_8);
    }
    assertEquals(3, fixture.startedServices().size());
    DownloadEngine restored = fixture.engine();
    idle();
    assertTrue("恢复不能自行重启下载服务", fixture.startedServices().isEmpty());
    assertEquals(0, queue(restored).size());
    assertFalse(restored.isBusy());
    assertEquals(3, restored.state().getValue().tasks.size());
    for (DownloadTask task : restored.state().getValue().tasks) {
      assertEquals(DownloadTask.Status.PAUSED, task.status);
      int original =
          Integer.parseInt(
              task.displayName.substring("restored-".length(), "restored-".length() + 1));
      assertEquals(original + 1, task.downloaded);
      assertTrue(task.message.contains("手动继续"));
      assertTrue(fixture.partial(task.id).exists());
    }
  }

  @Test
  public void pausedTasksRequireResumeAndCancelledTasksCannotBeResumed() throws Exception {
    String paused = enqueue("paused.bin", "downloads");
    String cancelled = enqueue("cancelled.bin", "downloads");
    engine.pause(paused);
    engine.cancel(cancelled);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("PAUSED", status(engine, paused));
    assertEquals("CANCELLED", status(engine, cancelled));
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertThrows(IOException.class, () -> engine.resume(cancelled));
    engine.resume(paused);
    assertEquals("QUEUED", status(engine, paused));
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("COMPLETE", status(engine, paused));
    assertEquals("CANCELLED", status(engine, cancelled));
    assertEquals(List.of("paused.bin"), fixture.media.insertedNames);
    assertTrue(fixture.partial(cancelled).exists());
  }

  @Test
  public void cancellationAfterReadbackDeletesOnlyPendingRowAndKeepsPrivateBytes()
      throws Exception {
    String id = enqueue("cancel-readback.bin", "downloads");
    Uri unrelated = fixture.media.document("unrelated.bin", BYTES);
    Documents.afterInputClose = uri -> engine.cancel(id);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("CANCELLED", status(engine, id));
    assertEquals("", engine.snapshot(id).getString("uri"));
    assertEquals(List.of(fixture.media.last), fixture.media.deleted);
    assertTrue(fixture.media.rows.containsKey(unrelated));
    assertArrayEquals(BYTES, Files.readAllBytes(fixture.partial(id).toPath()));
    assertFalse(fixture.media.events.contains("visible"));
    assertEquals("CANCELLED", fixture.journal(id).getString("status"));
    assertFalse(engine.isBusy());
  }

  @Test
  public void cancellationArrivingInsideCommitCannotUndoCompletedPublication() throws Exception {
    String id = enqueue("late-cancel.bin", "downloads");
    fixture.media.requiredGate = gate(engine);
    AtomicReference<Worker> cancellation = new AtomicReference<>();
    fixture.media.afterUpdate =
        () -> {
          CountDownLatch attempted = new CountDownLatch(1);
          cancellation.set(
              fixture.start(
                  () -> {
                    attempted.countDown();
                    engine.cancel(id);
                  }));
          try {
            await(attempted);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
          }
        };
    engine.runQueued();
    queue(engine).runAll();
    cancellation.get().await();
    assertEquals("COMPLETE", status(engine, id));
    assertEquals("COMPLETE", fixture.journal(id).getString("status"));
    assertEquals(0, fixture.media.require(fixture.media.last).pending());
    assertTrue(fixture.media.deleted.isEmpty());
    assertFalse(fixture.partial(id).exists());
    assertFalse(engine.snapshot(id).getString("uri").isEmpty());
  }

  @Test
  public void staleServiceGenerationCannotPauseNewerAdmissionButCurrentOwnerCan() throws Exception {
    String first = enqueue("generation-one.bin", "downloads");
    long retired = engine.runQueued();
    String second = enqueue("generation-two.bin", "downloads");
    engine.serviceGone(retired, "旧 Service 已退休");
    engine.serviceGone(0, "无所有权不能暂停");
    assertEquals("QUEUED", status(engine, first));
    assertEquals("QUEUED", status(engine, second));
    long current = engine.runQueued();
    assertTrue(current > retired);
    assertEquals(1, queue(engine).size());
    engine.serviceGone(current, "当前 Service 已中断");
    assertEquals("PAUSED", status(engine, first));
    assertEquals("PAUSED", status(engine, second));
    assertEquals("当前 Service 已中断", engine.snapshot(second).getString("message"));
    queue(engine).runAll();
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertFalse(engine.isBusy());
  }

  @Test
  public void saveAsWaitsForExplicitContentTargetAndRejectsTargetReplay() throws Exception {
    String id = enqueue("choose-target.bin", "saveas");
    engine.runQueued();
    queue(engine).runAll();
    fixture.startedServices();
    assertEquals("WAITING_DESTINATION", status(engine, id));
    assertEquals(BYTES.length, engine.snapshot(id).getLong("downloaded"));
    assertFalse(engine.isBusy());
    engine.resume(id);
    assertEquals("WAITING_DESTINATION", status(engine, id));
    assertTrue(fixture.startedServices().isEmpty());
    assertThrows(
        IOException.class, () -> engine.provideSaveTarget(id, Uri.parse("file:///tmp/unsafe")));
    Uri target = fixture.media.document("chosen-name.bin", new byte[0]);
    engine.provideSaveTarget(id, target);
    assertEquals("QUEUED", status(engine, id));
    assertThrows(IOException.class, () -> engine.provideSaveTarget(id, target));
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("COMPLETE", status(engine, id));
    assertEquals(target.toString(), engine.snapshot(id).getString("uri"));
    assertArrayEquals(BYTES, fixture.media.require(target).bytes);
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertFalse(fixture.media.events.contains("visible"));
  }

  @Test
  public void digestFailureRetainsPartialAndRunsAgainOnlyAfterExplicitResume() throws Exception {
    String id = enqueue("retry-digest.bin", "downloads");
    byte[] changed = BYTES.clone();
    changed[0] ^= 1;
    fixture.partial(id, changed);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("FAILED", status(engine, id));
    assertEquals("FILE_SHA256_MISMATCH", engine.snapshot(id).getString("message"));
    assertArrayEquals(changed, Files.readAllBytes(fixture.partial(id).toPath()));
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("FAILED", status(engine, id));
    assertTrue(fixture.media.insertedNames.isEmpty());
    fixture.partial(id, BYTES);
    engine.resume(id);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("COMPLETE", status(engine, id));
    assertEquals(List.of("retry-digest.bin"), fixture.media.insertedNames);
  }

  private void interruptedPublication(String id, Uri target, String kind) throws Exception {
    JSONObject journal = fixture.journal(id);
    journal
        .put("status", "PUBLISHING")
        .put("pendingUri", target.toString())
        .put("pendingKind", kind)
        .put("targetUri", kind.equals("saf") ? target.toString() : "");
    Files.writeString(
        new File(fixture.partial(id).getParentFile(), "task.json").toPath(),
        journal.toString(),
        StandardCharsets.UTF_8);
  }

  @Test
  public void restartVerifiesAlreadyVisibleMediaBeforeCompletingWithoutPublishingAgain()
      throws Exception {
    String id = enqueue("visible-before-crash.bin", "downloads");
    Uri visible = fixture.media.document("saved-before-crash.bin", BYTES);
    fixture
        .media
        .require(visible)
        .values
        .put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
    interruptedPublication(id, visible, "media");
    DownloadEngine restored = fixture.engine();
    assertEquals("PAUSED", status(restored, id));
    assertTrue(fixture.media.events.isEmpty());
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("COMPLETE", status(restored, id));
    assertEquals(visible.toString(), restored.snapshot(id).getString("uri"));
    assertEquals("saved-before-crash.bin", restored.snapshot(id).getString("displayName"));
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertTrue(fixture.media.deleted.isEmpty());
    assertFalse(fixture.partial(id).exists());
    assertEquals("COMPLETE", fixture.journal(id).getString("status"));
  }

  @Test
  public void corruptAlreadyVisibleMediaRemainsUnknownAndNeverDeletesUserVisibleBytes()
      throws Exception {
    String id = enqueue("corrupt-visible.bin", "downloads");
    Uri visible = fixture.media.document("corrupt-destination.bin", BYTES);
    Row row = fixture.media.require(visible);
    row.values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
    row.readback = BYTES.clone();
    row.readback[0] ^= 1;
    interruptedPublication(id, visible, "media");
    DownloadEngine restored = fixture.engine();
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("FAILED", status(restored, id));
    assertEquals("FILE_SHA256_MISMATCH", restored.snapshot(id).getString("message"));
    assertTrue(fixture.media.rows.containsKey(visible));
    assertTrue(fixture.media.deleted.isEmpty());
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertArrayEquals(BYTES, Files.readAllBytes(fixture.partial(id).toPath()));
  }

  @Test
  public void uncertainSaveAsAfterRestartRequiresNewTargetWithoutReplayingTheOldDocument()
      throws Exception {
    String id = enqueue("uncertain-saveas.bin", "saveas");
    byte[] uncertain = new byte[] {4, 2};
    Uri target = fixture.media.document("possibly-partial.bin", uncertain);
    interruptedPublication(id, target, "saf");
    DownloadEngine restored = fixture.engine();
    assertEquals("PAUSED", status(restored, id));
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("WAITING_DESTINATION", status(restored, id));
    assertArrayEquals(uncertain, fixture.media.require(target).bytes);
    assertTrue(fixture.media.deleted.isEmpty());
    assertFalse(fixture.media.events.contains("output-open"));
    assertTrue(fixture.partial(id).exists());
  }

  @Test
  public void missingPrimaryJournalUsesPreviousWhileMalformedJournalsAndBytesRemainUntouched()
      throws Exception {
    String id = enqueue("previous-journal.bin", "downloads");
    File journal = new File(fixture.partial(id).getParentFile(), "task.json");
    Files.move(journal.toPath(), new File(journal.getParentFile(), "task.json.previous").toPath());
    File unknown =
        new File(journal.getParentFile().getParentFile(), "99999999-9999-9999-9999-999999999999");
    assertTrue(unknown.mkdir());
    File bad = new File(unknown, "task.json");
    Files.writeString(bad.toPath(), "{invalid", StandardCharsets.UTF_8);
    File preserved = new File(unknown, "partial");
    Files.write(preserved.toPath(), new byte[] {9});
    DownloadEngine restored = fixture.engine();
    assertEquals("PAUSED", status(restored, id));
    assertEquals(1, restored.snapshot(null).getJSONArray("tasks").length());
    assertEquals("{invalid", Files.readString(bad.toPath()));
    assertArrayEquals(new byte[] {9}, Files.readAllBytes(preserved.toPath()));
    assertTrue(fixture.partial(id).exists());
  }

  @Test
  public void singletonGetStillUsesTheDownloadEngineClassMonitorAndIsReentrant() throws Exception {
    fixture.singleton(engine);
    CountDownLatch entered = new CountDownLatch(1), completed = new CountDownLatch(1);
    AtomicReference<DownloadEngine> result = new AtomicReference<>();
    Worker caller;
    synchronized (DownloadEngine.class) {
      assertSame(engine, DownloadEngine.get(null));
      caller =
          fixture.start(
              () -> {
                entered.countDown();
                result.set(DownloadEngine.get(null));
                completed.countDown();
              });
      await(entered);
      assertFalse(completed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
    }
    caller.await();
    assertSame(engine, result.get());
  }
}
