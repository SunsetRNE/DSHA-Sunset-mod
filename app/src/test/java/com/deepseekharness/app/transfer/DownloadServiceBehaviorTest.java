package com.deepseekharness.app.transfer;

import static com.deepseekharness.app.transfer.TransferBehaviorTestSupport.*;
import static org.junit.Assert.*;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.PowerManager;
import com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest;
import com.deepseekharness.app.util.NotificationIds;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowService;

/** 创建真实 Service 与 Engine，只使用系统服务 shadow 和可暂停的真实文件 I/O 边界。 */
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
public final class DownloadServiceBehaviorTest {
  private static final byte[] BYTES = "通知中的真实下载任务".getBytes(StandardCharsets.UTF_8);
  private TransferBehaviorTestSupport fixture;
  private DownloadEngine engine;
  private final List<ServiceController<DownloadService>> services = new ArrayList<>();

  @Before
  public void prepare() throws Exception {
    fixture = new TransferBehaviorTestSupport();
    engine = fixture.engine();
    fixture.singleton(engine);
    ShadowPowerManager.clearWakeLocks();
  }

  @After
  public void release() throws Exception {
    for (ServiceController<DownloadService> service : new ArrayList<>(services)) destroy(service);
    fixture.close();
  }

  private ServiceController<DownloadService> create() {
    ServiceController<DownloadService> controller =
        Robolectric.buildService(DownloadService.class).create();
    services.add(controller);
    return controller;
  }

  private void destroy(ServiceController<DownloadService> controller) {
    controller.destroy();
    services.remove(controller);
  }

  private String enqueue(String name) throws Exception {
    String id =
        engine.enqueue(artifact("service-" + name.replace('.', '-'), name, BYTES), "downloads");
    fixture.partial(id, BYTES);
    return id;
  }

  private String status(String id) throws Exception {
    return engine.snapshot(id).getString("status");
  }

  @Test
  public void idleNullRestartIsNotStickyCreatesLowImportanceChannelAndTakesNoWakeLock()
      throws Exception {
    DownloadService service = create().get();
    assertNull(service.onBind(null));
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 17));
    ShadowService shadow = Shadow.<ShadowService>extract(service);
    assertTrue(shadow.isStoppedBySelf());
    assertEquals(17, shadow.getStopSelfId());
    assertTrue(shadow.isForegroundStopped());
    assertFalse(shadow.getNotificationShouldRemoved());
    assertNull(ShadowPowerManager.getLatestWakeLock());
    assertEquals(0, queue(engine).size());
    Notification notification = shadow.getLastForegroundNotification();
    assertEquals("dsha_file_downloads", notification.getChannelId());
    assertEquals(0, notification.flags & Notification.FLAG_ONGOING_EVENT);
    assertNotEquals(0, notification.flags & Notification.FLAG_AUTO_CANCEL);
    NotificationManager manager = service.getSystemService(NotificationManager.class);
    assertEquals(
        NotificationManager.IMPORTANCE_LOW,
        manager.getNotificationChannel(notification.getChannelId()).getImportance());
  }

  @Test
  public void activeNotificationHasProgressAndImmutableWorkingPauseCancelActions()
      throws Exception {
    String id = enqueue("active-notification.bin");
    CountDownLatch reading = fixture.blockRead(fixture.partial(id));
    engine.runQueued();
    Worker worker = fixture.start(queue(engine).take());
    await(reading);
    idle();
    assertEquals(id, engine.state().getValue().activeId);
    DownloadService service = create().get();
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(), 0, 41));
    Notification notification =
        Shadow.<ShadowService>extract(service).getLastForegroundNotification();
    assertEquals(
        "active-notification.bin", notification.extras.getCharSequence(Notification.EXTRA_TITLE));
    assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
    assertEquals(0, notification.extras.getInt(Notification.EXTRA_PROGRESS));
    assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
    assertNotEquals(0, notification.flags & Notification.FLAG_ONGOING_EVENT);
    assertNotEquals(0, notification.flags & Notification.FLAG_ONLY_ALERT_ONCE);
    assertEquals(2, notification.actions.length);
    ShadowPendingIntent open = Shadow.<ShadowPendingIntent>extract(notification.contentIntent);
    assertTrue(open.isActivity());
    assertTrue(open.isImmutable());
    List<Intent> actions = new ArrayList<>();
    for (Notification.Action action : notification.actions) {
      ShadowPendingIntent pending = Shadow.<ShadowPendingIntent>extract(action.actionIntent);
      assertTrue(pending.isService());
      assertTrue(pending.isImmutable());
      Intent intent = pending.getSavedIntent();
      assertEquals(id, intent.getStringExtra("taskId"));
      assertEquals(DownloadService.class.getName(), intent.getComponent().getClassName());
      actions.add(intent);
    }
    assertEquals("com.deepseekharness.app.FILE_PAUSE", actions.get(0).getAction());
    assertEquals("com.deepseekharness.app.FILE_CANCEL", actions.get(1).getAction());
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(actions.get(0), 0, 42));
    assertEquals("PAUSED", status(id));
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(actions.get(1), 0, 43));
    assertEquals("CANCELLED", status(id));
    fixture.unblock();
    worker.await();
    idle();
    ShadowService shadow = Shadow.<ShadowService>extract(service);
    assertTrue(shadow.isStoppedBySelf());
    assertEquals(43, shadow.getStopSelfId());
    Notification finished =
        Shadow.<ShadowNotificationManager>extract(
                service.getSystemService(NotificationManager.class))
            .getNotification(NotificationIds.FILE_DOWNLOAD);
    assertNotNull(finished);
    assertEquals(0, finished.flags & Notification.FLAG_ONGOING_EVENT);
    assertNotEquals(0, finished.flags & Notification.FLAG_AUTO_CANCEL);
    assertTrue(finished.actions == null || finished.actions.length == 0);
  }

  @Test
  public void repeatedStartsReuseNonReferenceCountedWakeLockAndDestroyRemovesObserver()
      throws Exception {
    String id = enqueue("wake-lock.bin");
    idle();
    ServiceController<DownloadService> controller = create();
    DownloadService service = controller.get();
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(), 0, 1));
    PowerManager.WakeLock wakeLock = ShadowPowerManager.getLatestWakeLock();
    assertNotNull(wakeLock);
    assertTrue(wakeLock.isHeld());
    assertFalse(Shadow.<ShadowPowerManager.ShadowWakeLock>extract(wakeLock).isReferenceCounted());
    assertEquals(
        "DSHA:file-download", Shadow.<ShadowPowerManager.ShadowWakeLock>extract(wakeLock).getTag());
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(), 0, 2));
    assertSame(wakeLock, ShadowPowerManager.getLatestWakeLock());
    assertEquals(1, Shadow.<ShadowPowerManager.ShadowWakeLock>extract(wakeLock).getTimesHeld());
    assertEquals(1, queue(engine).size());
    assertTrue(engine.state().hasObservers());
    destroy(controller);
    assertFalse(wakeLock.isHeld());
    assertFalse(engine.state().hasObservers());
    assertEquals("PAUSED", status(id));
    assertTrue(fixture.partial(id).exists());
  }

  @Test
  public void retiringServiceCannotPauseNewAdmissionAndCurrentServiceTimeoutKeepsManualResume()
      throws Exception {
    String first = enqueue("old-service.bin");
    idle();
    ServiceController<DownloadService> old = create();
    old.get().onStartCommand(new Intent(), 0, 10);
    PowerManager.WakeLock oldWake = ShadowPowerManager.getLatestWakeLock();
    String newer = enqueue("new-service.bin");
    destroy(old);
    assertFalse(oldWake.isHeld());
    assertEquals("QUEUED", status(first));
    assertEquals("QUEUED", status(newer));
    ServiceController<DownloadService> current = create();
    DownloadService service = current.get();
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(), 0, 11));
    PowerManager.WakeLock currentWake = ShadowPowerManager.getLatestWakeLock();
    assertTrue(currentWake.isHeld());
    service.onTimeout(11, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    assertEquals("PAUSED", status(first));
    assertEquals("PAUSED", status(newer));
    assertTrue(Shadow.<ShadowService>extract(service).isStoppedBySelf());
    assertTrue(Shadow.<ShadowService>extract(service).getNotificationShouldRemoved());
    assertTrue(fixture.partial(first).exists());
    assertTrue(fixture.partial(newer).exists());
    queue(engine).runAll();
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertFalse(engine.isBusy());
    destroy(current);
    assertFalse(currentWake.isHeld());
    assertFalse(engine.state().hasObservers());
  }

  @Test
  public void deniedForegroundPausesQueueWithoutWakeLockOrAutomaticRetryAndIsNotSticky()
      throws Exception {
    String id = enqueue("foreground-denied.bin");
    DownloadService service = create().get();
    ShadowService shadow = Shadow.<ShadowService>extract(service);
    shadow.setThrowInStartForeground(new SecurityException("fixture foreground admission denied"));
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(), 0, 61));
    assertEquals("PAUSED", status(id));
    assertTrue(shadow.isStoppedBySelf());
    assertEquals(61, shadow.getStopSelfId());
    assertTrue(shadow.getNotificationShouldRemoved());
    assertNull(ShadowPowerManager.getLatestWakeLock());
    assertEquals(0, queue(engine).size());
    assertFalse(engine.state().hasObservers());
    assertTrue(fixture.partial(id).exists());
    engine.runQueued();
    queue(engine).runAll();
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertEquals("PAUSED", status(id));
  }
}
