package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** 迁移前先在 Java 基线执行：冻结备份范围、空输入、身份与状态更新的边界。 */
public final class BackupPolicyGoldenTest {
  private String previousLanguage;

  @Before
  public void chineseMessages() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void restoreLanguage() {
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void scopeIdsKeepJavaTrimAndUnknownIntegerFallbacks() {
    for (String id : new String[] {"full", "sessions", "settings", "plugins"}) {
      assertEquals(id, BackupScope.id(BackupScope.fromId("\u0000\t " + id + "\r\u001f")));
      for (String edge : new String[] {"\u00a0", "\u2003", "\u3000", "\ud83d\ude00"}) {
        IllegalArgumentException failure =
            assertThrows(
                IllegalArgumentException.class, () -> BackupScope.fromId(edge + id + edge));
        assertEquals("UNKNOWN_BACKUP_SCOPE", failure.getMessage());
      }
    }
    for (int scope : new int[] {-1, Integer.MIN_VALUE, 4, Integer.MAX_VALUE}) {
      assertEquals("full", BackupScope.id(scope));
      assertEquals("DSHA-backup-", BackupScope.fileNamePrefix(scope));
      assertTrue(BackupScope.visibleToLegacyScan(scope));
      assertArrayEquals(new String[0], BackupScope.dshPaths(scope));
      assertArrayEquals(new String[0], BackupScope.mergeSubdirs(scope));
      assertFalse(BackupScope.includesWorkdirFiles(scope));
      assertFalse(BackupScope.includesPluginSrc(scope));
      assertFalse(BackupScope.needsPublicDataSnapshot(scope));
    }
    assertEquals(
        BackupScope.SESSIONS, BackupScope.fromFileName("\u001fC:\\d\\DSHA-sessions-x.tar.gz\t"));
    assertEquals(BackupScope.FULL, BackupScope.fromFileName("\u2003DSHA-sessions-x.tar.gz"));
    assertEquals(BackupScope.FULL, BackupScope.fromFileName("/folder/DSHA-sessions-x.tar.gz/"));
    assertEquals(BackupScope.SESSIONS, BackupScope.fromFileName("DSHA-sessions-x/"));
    assertEquals(BackupScope.FULL, BackupScope.fromFileName("dsha-sessions-x.tar.gz"));
  }

  @Test
  public void scopeArraysRetainSharedOrderAndFreshMutableResults() {
    int[] order = BackupScope.ALL;
    assertArrayEquals(new int[] {0, 1, 3, 2}, order);
    int first = order[0];
    try {
      order[0] = 99;
      assertSame(order, BackupScope.ALL);
      assertEquals(99, BackupScope.ALL[0]);
    } finally {
      order[0] = first;
    }
    for (int scope : new int[] {0, 1, 2, 3, -1}) {
      String[] paths = BackupScope.dshPaths(scope);
      String[] subdirs = BackupScope.mergeSubdirs(scope);
      assertNotSame(paths, BackupScope.dshPaths(scope));
      assertNotSame(subdirs, BackupScope.mergeSubdirs(scope));
      assertEquals(paths.length, subdirs.length);
      for (int i = 0; i < paths.length; i++) assertEquals(".dsh/" + subdirs[i], paths[i]);
      if (paths.length != 0) {
        paths[0] = "changed";
        subdirs[0] = null;
        assertNotEquals("changed", BackupScope.dshPaths(scope)[0]);
        assertNotNull(BackupScope.mergeSubdirs(scope)[0]);
      }
    }
    assertArrayEquals(
        new String[] {
          "profiles", "plugin-src", "plugin-sources.json", "plugin-history", "plugin-safe-mode.json"
        },
        BackupScope.mergeSubdirs(BackupScope.PLUGINS));
    assertEquals(".dsha-pub", BackupScope.PUB_SNAPSHOT_DIR);
  }

  @Test
  public void archiveSuffixLimitsAndGrantExclusionsStayLiteral() {
    assertEquals("DSHA-settings-A.tar.gz", BackupScope.archiveName(3, "A"));
    String maximum = "a" + "_".repeat(80);
    assertEquals("DSHA-backup-" + maximum + ".tar.gz", BackupScope.archiveName(0, maximum));
    for (String suffix :
        new String[] {null, "", "_a", " a", "a ", "a/b", "a\\b", "a\n", "é", "a".repeat(82)}) {
      assertEquals(
          "备份文件标识无效",
          assertThrows(IllegalArgumentException.class, () -> BackupScope.archiveName(0, suffix))
              .getMessage());
    }
    for (String path :
        new String[] {"dsha_device_grants", "a\\dsha_device_grants", "a/clipboard_read_mode_v1"}) {
      assertTrue(path, BackupScope.excludesNativeGrantState(path));
    }
    for (String path :
        new String[] {
          null, "", "DSHA_DEVICE_GRANTS", "dsha_device_grants/", "xclipboard_read_mode_v1"
        }) {
      assertFalse(path, BackupScope.excludesNativeGrantState(path));
    }
  }

  @Test
  public void orderedHostScopesKeepJdkMutationAndNullContracts() {
    List<String> expected =
        Arrays.asList("application", "sessions", "settings", "plugins", "projects");
    assertEquals(expected, BackupScopes.ORDERED);
    assertEquals(expected, new ArrayList<>(BackupScopes.ALL));
    assertSame(BackupScopes.ORDERED, BackupScopes.ORDERED);
    assertSame(BackupScopes.ALL, BackupScopes.ALL);
    assertThrows(UnsupportedOperationException.class, () -> BackupScopes.ORDERED.set(0, "bad"));
    assertThrows(UnsupportedOperationException.class, () -> BackupScopes.ORDERED.add("bad"));
    assertThrows(UnsupportedOperationException.class, () -> BackupScopes.ALL.remove("application"));
    Iterator<String> iterator = BackupScopes.ALL.iterator();
    iterator.next();
    assertThrows(UnsupportedOperationException.class, iterator::remove);
    assertThrows(NullPointerException.class, () -> BackupScopes.ORDERED.contains(null));
    assertFalse(BackupScopes.ALL.contains(null));
  }

  @Test
  public void fileNamesKeepRootLocaleRegexAndUuidVersion() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      String[][] mime = {
        {null, "application/octet-stream"},
        {"", "application/octet-stream"},
        {"x.TAR.GZ", "application/gzip"},
        {"x.TXT", "text/plain"},
        {"x.JSON", "application/json"},
        {"x.PNG", "image/png"},
        {"x.JPG", "image/jpeg"},
        {"x.JPEG", "image/jpeg"},
        {"x.GIF", "image/gif"},
        {"x.WEBP", "image/webp"},
        {"x.PDF", "application/pdf"},
        {"x.ZIP", "application/zip"},
        {"x.TGZ", "application/gzip"},
        {"x.GZ", "application/gzip"},
        {"x.TAR", "application/x-tar"},
        {"x.tar.gz ", "application/octet-stream"}
      };
      for (String[] row : mime)
        assertEquals(row[0], row[1], BackupFileNames.exportMimeType(row[0]));
      String prefix = "DSHA-data-v5-";
      String portable = BackupFileNames.portableName();
      UUID id = UUID.fromString(portable.substring(prefix.length(), portable.length() - 7));
      assertEquals(4, id.version());
      assertEquals(2, id.variant());
      assertTrue(BackupFileNames.discoverable(portable));
      assertTrue(BackupFileNames.discoverable("DSHA-data-v5-" + "-".repeat(36) + ".tar.gz"));
      assertTrue(BackupFileNames.discoverable("DSHA-MIGRATION-x (1)\t(42).TAR"));
      assertFalse(BackupFileNames.discoverable("DSHA-backup-x\u2003(1).gz"));
      assertFalse(BackupFileNames.discoverable(" DSHA-backup-x.tar.gz"));
      assertFalse(BackupFileNames.discoverable("DSHA-settings-x.tar.gz"));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  public void healthKeepsOverflowWhitespaceAndExactThreshold() {
    long week = 604800000L;
    assertEquals(BackupHealth.Level.FAILED, BackupHealth.evaluate(1, " ", false, false, 1));
    assertEquals(
        BackupHealth.Level.PROTECTED, BackupHealth.evaluate(1, null, false, false, week + 1));
    assertEquals(BackupHealth.Level.STALE, BackupHealth.evaluate(1, null, false, false, week + 2));
    assertEquals(
        BackupHealth.Level.STALE, BackupHealth.evaluate(1, "", false, false, Long.MIN_VALUE));
    assertEquals(
        BackupHealth.Level.PROTECTED,
        BackupHealth.evaluate(Long.MAX_VALUE, "", false, false, Long.MIN_VALUE));
    assertEquals(0, BackupHealth.ageDays(1, Long.MIN_VALUE));
    assertEquals((Long.MAX_VALUE - 1) / 86400000L, BackupHealth.ageDays(1, Long.MAX_VALUE));
    assertThrows(NullPointerException.class, () -> BackupHealth.severity(null));
  }

  @Test
  public void taskKindsKeepUnknownIdentityAndAllHistoricAliases() {
    String[][] rows = {
      {"DSHA_BACKUP_V1", "创建备份", "Create backup"},
      {"DSHA_RESTORE_V1", "恢复备份", "Restore backup"},
      {"DSHA_REBUILD_V1", "重建环境", "Rebuild environment"},
      {"DSHA_UPDATE_RUNTIME_V1", "更新运行环境", "Update runtime environment"},
      {"DSHA_RECOVER_MAINTENANCE_V1", "恢复中断维护", "Resume interrupted maintenance"},
      {"DSHA_FACTORY_RESET_V1", "格式化 DSHA", "Format DSHA"},
      {"DSHA_ROLLBACK_RUNTIME_V1", "回退兼容运行时", "Roll back compatible runtime"},
      {"DSHA_SELECT_HOME_V1", "选择数据目录", "Select data directory"},
      {"DSHA_RESET_CONFIG_V1", "重置配置", "Reset settings"},
      {"DSHA_REPAIR_STARTUP_V1", "修复启动配置", "Repair startup configuration"},
      {"DSHA_REMOVE_FAULTY_PLUGIN_V1", "卸载故障插件", "Remove faulty plugin"},
      {"DSHA_ISOLATE_PLUGIN_JOURNALS_V1", "隔离无法读取的插件日志", "Isolate unreadable plugin journals"},
      {"DSHA_TASK_RECORD_V1", "任务记录", "Task record"}
    };
    for (String[] row : rows) {
      for (String alias : row) {
        assertEquals(row[0], BackupTaskKinds.normalize(new String(alias)));
        assertEquals(row[1], BackupTaskKinds.display(alias, false));
        assertEquals(row[2], BackupTaskKinds.display(alias, true));
        assertTrue(BackupTaskKinds.is(row[0], alias));
      }
    }
    String unknown = new String(" 🗃\u0000\ud800 ");
    assertSame(unknown, BackupTaskKinds.normalize(unknown));
    assertNull(BackupTaskKinds.display(unknown, true));
    assertEquals("", BackupTaskKinds.normalize(null));
    assertTrue(BackupTaskKinds.is("", null));
    assertThrows(NullPointerException.class, () -> BackupTaskKinds.is(null, null));
  }

  @Test
  public void exportReasonsRemainCaseSensitiveAndMessagesStayLive() {
    assertEquals(BackupExportGuidance.Kind.UNKNOWN, BackupExportGuidance.classify(" BUSY"));
    assertEquals(BackupExportGuidance.Kind.UNKNOWN, BackupExportGuidance.classify("busy"));
    assertEquals(BackupExportGuidance.Kind.UNKNOWN, BackupExportGuidance.classify("BUSY\u0000"));
    assertEquals("已有数据任务正在运行，请等待当前任务结束后重试。", BackupExportGuidance.message("BUSY", false));
    assertEquals("维护记录尚待恢复；救援导出仍可继续。", BackupExportGuidance.message("MAINTENANCE_PENDING", true));
    UiText.setLanguage("en");
    assertEquals(
        "Another data task is running. Wait for it to finish and retry.",
        BackupExportGuidance.message("BUSY", false));
    assertEquals(
        "Maintenance records still need recovery; rescue export can continue.",
        BackupExportGuidance.message("MAINTENANCE_PENDING", true));
    assertEquals(
        "Export did not start. Review the state and retry.",
        BackupExportGuidance.message(null, false));
  }

  @Test
  public void snapshotsKeepIdentityNullsAndSeparateLastStage() {
    BackupTaskState state = new BackupTaskState();
    BackupTaskState.Snapshot initial = state.snapshot();
    assertEquals(0, initial.id);
    assertEquals("", initial.kind);
    assertFalse(initial.busy());
    assertNotSame(initial, state.snapshot());
    assertNotEquals(initial, state.snapshot());
    state.restoreStage("phase password=secret");
    state.restore(42, null, null, null);
    BackupTaskState.Snapshot restored = state.snapshot();
    assertNull(restored.kind);
    assertNull(restored.status);
    assertNull(restored.detail);
    assertEquals("phase password=***", restored.lastStage);
    assertFalse(restored.busy());
    state.update(42, BackupTaskState.Status.RUNNING, "ignored");
    assertNull(state.snapshot().status);
    long id = state.start(null);
    assertNull(state.snapshot().kind);
    assertEquals("正在准备…", state.snapshot().lastStage);
    state.update(id, BackupTaskState.Status.PREVIEW, "password=secret");
    assertEquals("password=***", state.snapshot().detail);
    assertEquals("正在准备…", state.snapshot().lastStage);
    assertTrue(state.confirm(id));
    assertEquals("正在恢复…", state.snapshot().detail);
    assertEquals("正在准备…", state.snapshot().lastStage);
    state.update(id, null, null);
    assertNull(state.snapshot().status);
    assertEquals("", state.snapshot().detail);
    assertFalse(state.busy());
  }

  @Test
  public void restoreRunningAndIdOverflowKeepJavaTransitions() {
    BackupTaskState state = new BackupTaskState();
    state.restoreStage("previous");
    state.restore(9, "kind", BackupTaskState.Status.RUNNING, null);
    assertEquals(BackupTaskState.Status.INTERRUPTED, state.snapshot().status);
    assertEquals("上次任务因进程退出而中断，未自动重试。请先处理未完成的维护或重新选择备份。\nnull", state.snapshot().detail);
    assertEquals("previous", state.snapshot().lastStage);
    long inFuture = System.currentTimeMillis() + 1000000;
    state.restore(inFuture, "kind", BackupTaskState.Status.SUCCEEDED, "done");
    assertEquals(inFuture + 1, state.start("next"));
    state.restore(Long.MAX_VALUE, "kind", BackupTaskState.Status.FAILED, "done");
    long before = System.currentTimeMillis();
    long wrapped = state.start("next");
    assertTrue(wrapped >= before && wrapped <= System.currentTimeMillis());
    state.reset();
    assertEquals(0, state.snapshot().id);
    assertEquals("", state.snapshot().lastStage);
    assertEquals(BackupTaskState.Status.IDLE, state.snapshot().status);
  }

  @Test
  public void schedulerUsesReferenceIdentityAndRetainsStoppedJob() {
    ScheduledBackupOwner<String> owner = new ScheduledBackupOwner<>();
    String first = new String("same");
    String equal = new String("same");
    ScheduledBackupOwner.Ticket<String> ticket = owner.begin(first);
    assertSame(first, ticket.parameters);
    assertNull(owner.stop(equal));
    assertTrue(owner.current(ticket));
    assertEquals(
        "SCHEDULED_BACKUP_JOB_ID",
        assertThrows(IllegalStateException.class, () -> owner.submitted(ticket, "")).getMessage());
    assertTrue(owner.submitted(ticket, " "));
    assertThrows(IllegalStateException.class, () -> owner.submitted(ticket, "next"));
    assertSame(ticket, owner.stop(first));
    assertEquals(" ", owner.jobId(ticket));
    assertTrue(owner.owns(ticket, " "));
    assertFalse(owner.submitted(ticket, null));
    assertFalse(owner.accept(ticket, null));
    ScheduledBackupOwner.Ticket<String> next = owner.begin(null);
    assertNull(next.parameters);
    assertSame(next, owner.stop(null));
    assertNotEquals(ticket, next);
  }

  @Test
  public void schedulerCallbackFailuresAndReentryKeepOwnershipOrder() {
    ScheduledBackupOwner<Object> owner = new ScheduledBackupOwner<>();
    ScheduledBackupOwner.Ticket<Object> ticket = owner.begin(new Object());
    RuntimeException failure = new RuntimeException("callback");
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () ->
                owner.accept(
                    ticket,
                    () -> {
                      throw failure;
                    })));
    assertTrue(owner.current(ticket));
    assertThrows(NullPointerException.class, () -> owner.accept(ticket, null));
    assertTrue(owner.accept(ticket, () -> assertSame(ticket, owner.stopCurrent())));
    assertFalse(owner.current(ticket));
  }

  @Test
  public void schedulerChecksThreadBeforeNullAndStaleArguments() throws Exception {
    ScheduledBackupOwner<Object> owner = new ScheduledBackupOwner<>();
    List<Runnable> calls =
        Arrays.asList(
            () -> owner.begin(null),
            () -> owner.current(null),
            () -> owner.accept(null, null),
            () -> owner.submitted(null, null),
            () -> owner.jobId(null),
            () -> owner.owns(null, null),
            () -> owner.finish(null),
            () -> owner.stop(null),
            owner::stopCurrent);
    AtomicReference<Throwable> unexpected = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                for (Runnable call : calls) {
                  assertEquals(
                      "SCHEDULED_BACKUP_OWNER_THREAD",
                      assertThrows(IllegalStateException.class, call::run).getMessage());
                }
              } catch (Throwable error) {
                unexpected.set(error);
              }
            });
    worker.start();
    worker.join();
    if (unexpected.get() != null) throw new AssertionError(unexpected.get());
  }
}
