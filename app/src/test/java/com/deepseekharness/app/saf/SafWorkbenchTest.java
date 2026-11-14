package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import com.deepseekharness.app.util.RuntimeWorkPort;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSystemClock;

/** 工作台操作的真实接入反例；沿用 SAF 的 Provider/fd 测试夹具，不启动桥或设备通道。 */
@RunWith(SafBridgeTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {SafTestOs.FakeOs.class, SafTestOs.FakePfd.class})
public final class SafWorkbenchTest {
  private static final String TREE = "content://test.dsha.workbench/tree/ROOT";
  private static final String ID = SafGrantRecord.idOf(TREE);
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();
  private Application app;
  private File rootfs;
  private SafWorkbench workbench;
  private SafLedgerStore ledger;
  private SafTreeTest.FakeProvider provider;
  private Object previousWorkProvider;
  private Field workProvider;

  @Before
  public void setUp() throws Exception {
    app = RuntimeEnvironment.getApplication();
    SafTestOs.reset();
    app.getSharedPreferences("saf_grants", Context.MODE_PRIVATE).edit().clear().commit();
    app.getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    ledger = SafLedgerStore.open(app);
    assertTrue(
        ledger.save(SafTestSupport.single(TREE, SafGrantRecord.READ | SafGrantRecord.WRITE)));
    Uri tree = Uri.parse(TREE);
    app.getContentResolver()
        .takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    rootfs = temporary.newFolder("rootfs").getCanonicalFile();
    Files.createDirectories(new File(rootfs, "root").toPath());
    provider = new SafTreeTest.FakeProvider();
    provider.storage = temporary.newFolder("provider");
    provider.treeUri = tree;
    provider.documents.put(
        "ROOT",
        new String[] {"", "root", android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "0"});
    File original = new File(provider.storage, "file.txt");
    Files.writeString(original.toPath(), "hello");
    provider.documents.put("file.txt", new String[] {"ROOT", "file.txt", "text/plain", "5"});
    provider.files.put("file.txt", original);
    ProviderInfo info = new ProviderInfo();
    info.authority = tree.getAuthority();
    info.exported = true;
    info.grantUriPermissions = true;
    provider.attachInfo(app, info);
    ShadowContentResolver.registerProviderInternal(tree.getAuthority(), provider);
    workbench = new SafWorkbench(app, () -> rootfs);
    workProvider = RuntimeWorkPort.class.getDeclaredField("provider");
    workProvider.setAccessible(true);
    previousWorkProvider = workProvider.get(null);
    workProvider.set(
        null,
        new RuntimeWorkPort.Provider() {
          @Override
          public RuntimeWorkPort.Work begin(boolean detached, String kind) {
            return new RuntimeWorkPort.Work() {
              @Override
              public void retainUntilExit(Process process) {}

              @Override
              public void close() {}
            };
          }

          @Override
          public boolean hasOtherTasks() {
            return false;
          }
        });
  }

  @After
  public void tearDown() throws Exception {
    SafLocalFiles.setDirectoryListerForTests(null);
    Thread.interrupted();
    workProvider.set(null, previousWorkProvider);
    assertTrue("workbench must close native file descriptors", SafTestOs.RAW_HANDLES.isEmpty());
    assertTrue("workbench must close dup descriptors", SafTestOs.OPEN_FDS.isEmpty());
  }

  @Test
  public void rawTreeUriAndUnregisteredPersistedGrantDoNotAuthorizeWorkbench() {
    assertEquals(
        "NO_GRANT",
        assertThrows(SafTree.Failure.class, () -> workbench.prepare(TREE, "file.txt", true)).code);
    Uri other = Uri.parse("content://test.dsha.workbench/tree/OTHER");
    app.getContentResolver()
        .takePersistableUriPermission(other, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    assertEquals(
        "NO_GRANT",
        assertThrows(SafTree.Failure.class, () -> workbench.list(SafGrants.id(other), "")).code);
  }

  @Test
  public void removingGrantInvalidatesAlreadyOpenTransferPanel() throws Exception {
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "file.txt", true);
    SafGrants.forget(app, Uri.parse(TREE));
    assertEquals(
        "NO_GRANT",
        assertThrows(SafTree.Failure.class, () -> workbench.transfer(ticket, "/root/out.txt", true))
            .code);
    assertFalse(new File(rootfs, "root/out.txt").exists());
  }

  @Test
  public void regrantInvalidatesOldPanelInsteadOfRenewingItsEpoch() throws Exception {
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "", false);
    SafGrantLedger next = ledger.load().beginTake(TREE, "app", "folder", 3, 3, 2_000).ledger;
    assertTrue(ledger.save(next.commitTake(TREE, 3, 3, true, 2_001).ledger));
    assertEquals(
        "HANDLE_REVOKED",
        assertThrows(SafTree.Failure.class, () -> workbench.retarget(ticket, "new.txt")).code);
  }

  @Test
  public void systemRevocationDegradesListAndRefusesOldTicket() throws Exception {
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "file.txt", true);
    app.getContentResolver()
        .releasePersistableUriPermission(
            Uri.parse(TREE),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    assertFalse(SafGrants.listLight(app).get(0).valid);
    assertEquals(
        "PERMISSION_DENIED",
        assertThrows(SafTree.Failure.class, () -> workbench.transfer(ticket, "/root/out.txt", true))
            .code);
  }

  @Test
  public void writePreferenceIsRecheckedAfterPanelOpened() throws Exception {
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "", false);
    SafGrants.setWritable(app, Uri.parse(TREE), false);
    assertEquals(
        "HANDLE_NOT_PERMITTED",
        assertThrows(SafTree.Failure.class, () -> workbench.retarget(ticket, "new.txt")).code);
    assertEquals("hello", new String(workbench.read(ID, "file.txt", 10), StandardCharsets.UTF_8));
  }

  @Test
  public void transferPanelExpiresAndCannotBeRevivedByChangingItsPath() throws Exception {
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "", false);
    ShadowSystemClock.advanceBy(Duration.ofMillis(SafCapabilityLimits.HANDLE_TTL_MS + 1));
    assertEquals(
        "HANDLE_EXPIRED",
        assertThrows(SafTree.Failure.class, () -> workbench.retarget(ticket, "new.txt")).code);
  }

  @Test
  public void pullPublishesOnlyAfterCompleteCopyAndTicketCannotBeReplayed() throws Exception {
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "file.txt", true);
    assertEquals(5, workbench.transfer(ticket, "/root/out.txt", true));
    assertEquals("hello", Files.readString(new File(rootfs, "root/out.txt").toPath()));
    assertEquals(
        "HANDLE_UNKNOWN",
        assertThrows(
                SafTree.Failure.class, () -> workbench.transfer(ticket, "/root/again.txt", true))
            .code);
    assertFalse(new File(rootfs, "root/again.txt").exists());
  }

  @Test
  public void pushUsesExistingStreamingWriter() throws Exception {
    Files.writeString(new File(rootfs, "root/source.txt").toPath(), "export");
    SafWorkbench.Ticket selection = workbench.prepare(ID, "", false);
    SafWorkbench.Ticket ticket = workbench.retarget(selection, "export.txt");
    assertEquals(6, workbench.transfer(ticket, "/root/source.txt", false));
    assertEquals("export", Files.readString(provider.files.get(provider.lastCreatedId).toPath()));
  }

  @Test
  public void credentialAndOutsideSymlinksNeverReachProviderWriter() throws Exception {
    File credentials = new File(rootfs, "root/.dsh");
    assertTrue(credentials.mkdir());
    Files.writeString(new File(credentials, ".bridge_token").toPath(), "fixture-secret");
    Files.createSymbolicLink(
        new File(rootfs, "root/report.md").toPath(),
        new File(credentials, ".bridge_token").toPath());
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "export.txt", false);
    assertEquals(
        "LOCAL_CREDENTIAL",
        assertThrows(
                java.io.IOException.class,
                () -> workbench.transfer(ticket, "/root/report.md", false))
            .getMessage());
    File outside = temporary.newFile("outside.txt");
    Files.createSymbolicLink(new File(rootfs, "root/outside.txt").toPath(), outside.toPath());
    SafWorkbench.Ticket other = workbench.prepare(ID, "export.txt", false);
    assertEquals(
        "LOCAL_OUTSIDE_ROOTFS",
        assertThrows(
                java.io.IOException.class,
                () -> workbench.transfer(other, "/root/outside.txt", false))
            .getMessage());
    assertNull(provider.lastCreatedId);
  }

  private void assertStoppedDuringRead(String code, Runnable hook) throws Exception {
    File target = new File(rootfs, "root/out.txt");
    Files.writeString(target.toPath(), "existing");
    provider.firstReadOpen = hook;
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "file.txt", true);
    try {
      assertEquals(
          code,
          assertThrows(
                  java.io.IOException.class,
                  () -> workbench.transfer(ticket, "/root/out.txt", true))
              .getMessage());
    } finally {
      Thread.interrupted();
    }
    assertEquals("existing", Files.readString(target.toPath()));
    assertTrue("interrupted copies must never publish", SafTestOs.RENAMES.isEmpty());
    try (var files = Files.list(target.getParentFile().toPath())) {
      assertFalse(
          "partial workbench file must be cleaned",
          files.anyMatch(p -> p.getFileName().toString().startsWith(".saf-workbench-")));
    }
  }

  @Test
  public void forgetDuringFirstReadPreservesExistingTargetWithoutPublishing() throws Exception {
    assertStoppedDuringRead("HANDLE_REVOKED", () -> SafGrants.forget(app, Uri.parse(TREE)));
  }

  @Test
  public void systemRevokeDuringFirstReadPreservesExistingTargetWithoutPublishing()
      throws Exception {
    assertStoppedDuringRead(
        "PERMISSION_DENIED",
        () ->
            app.getContentResolver()
                .releasePersistableUriPermission(
                    Uri.parse(TREE),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
  }

  @Test
  public void totalDeadlineDuringFirstReadPreservesExistingTargetWithoutPublishing()
      throws Exception {
    assertStoppedDuringRead(
        "TIMEOUT",
        () -> ShadowSystemClock.advanceBy(Duration.ofMillis(SafCapabilityLimits.DEADLINE_MS + 1)));
  }

  @Test
  public void interruptDuringFirstReadPreservesExistingTargetWithoutPublishing() throws Exception {
    assertStoppedDuringRead("CANCELLED", () -> Thread.currentThread().interrupt());
  }

  @Test
  public void pushKeepsGuardCodesInsteadOfWrappingThemAsGenericIoErrors() throws Exception {
    File local = new File(rootfs, "root/source.txt");
    Files.writeString(local.toPath(), "export");
    for (String code :
        java.util.List.of("HANDLE_REVOKED", "TIMEOUT", "CANCELLED", "PERMISSION_DENIED")) {
      assertTrue(
          ledger.save(SafTestSupport.single(TREE, SafGrantRecord.READ | SafGrantRecord.WRITE)));
      app.getContentResolver().takePersistableUriPermission(Uri.parse(TREE), 3);
      SafWorkbench.Ticket ticket = workbench.prepare(ID, "export.txt", false);
      provider.firstWriteOpen =
          () -> {
            switch (code) {
              case "HANDLE_REVOKED" -> SafGrants.forget(app, Uri.parse(TREE));
              case "TIMEOUT" ->
                  ShadowSystemClock.advanceBy(
                      Duration.ofMillis(SafCapabilityLimits.DEADLINE_MS + 1));
              case "CANCELLED" -> Thread.currentThread().interrupt();
              default -> SafGrants.setWritable(app, Uri.parse(TREE), false);
            }
          };
      try {
        assertEquals(
            code,
            assertThrows(
                    java.io.IOException.class,
                    () -> workbench.transfer(ticket, "/root/source.txt", false))
                .getMessage());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  public void importCopiesExistingModeBeforePublishing() throws Exception {
    File target = new File(rootfs, "root/out.txt");
    Files.writeString(target.toPath(), "existing");
    Files.setAttribute(target.toPath(), "unix:mode", 0750);
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "file.txt", true);
    assertEquals(5, workbench.transfer(ticket, "/root/out.txt", true));
    assertEquals("hello", Files.readString(target.toPath()));
    assertEquals(1, SafTestOs.CHMODES.size());
    var copied = SafTestOs.CHMODES.entrySet().iterator().next();
    assertEquals(0750, copied.getValue().intValue());
    assertTrue(copied.getKey(), copied.getKey().contains(".saf-workbench-"));
    assertTrue(SafTestOs.RENAMES.get(0).startsWith(copied.getKey() + " -> "));
  }

  @Test
  public void staleCleanupRemovesOnlyOldOwnedRegularParts() throws Exception {
    File directory = new File(rootfs, "root");
    String oldName = ".saf-workbench-" + java.util.UUID.randomUUID() + ".part";
    File old = new File(directory, oldName);
    File fresh = new File(directory, ".saf-workbench-" + java.util.UUID.randomUUID() + ".part");
    File unrelated = new File(directory, ".saf-workbench-user.part");
    File symbolic = new File(directory, ".saf-workbench-" + java.util.UUID.randomUUID() + ".part");
    File secret = temporary.newFile("outside.txt");
    Files.writeString(old.toPath(), "stale");
    Files.writeString(fresh.toPath(), "fresh");
    Files.writeString(unrelated.toPath(), "keep");
    Files.createSymbolicLink(symbolic.toPath(), secret.toPath());
    long now = System.currentTimeMillis();
    assertTrue(old.setLastModified(now - 2 * 60 * 60 * 1000));
    assertTrue(unrelated.setLastModified(now - 2 * 60 * 60 * 1000));
    assertTrue(secret.setLastModified(now - 2 * 60 * 60 * 1000));
    SafLocalFiles.setDirectoryListerForTests(fd -> SafTestOs.FD_FILES.get(fd).list());
    try (var anchor = SafLocalFiles.openAnchoredDirectory(directory)) {
      SafWorkbench.pruneTemps(anchor, now);
    }
    assertFalse(old.exists());
    assertTrue(fresh.exists());
    assertTrue(unrelated.exists());
    assertTrue(Files.isSymbolicLink(symbolic.toPath()));
    assertTrue(secret.exists());
    assertEquals(java.util.List.of(old.getAbsolutePath()), SafTestOs.REMOVES);
  }

  @Test
  public void maintenanceBarrierReturnsTypedCodeAndReleasesTransferSlot() throws Exception {
    workProvider.set(
        null,
        new RuntimeWorkPort.Provider() {
          @Override
          public RuntimeWorkPort.Work begin(boolean detached, String kind) {
            throw new IllegalStateException("maintenance barrier");
          }

          @Override
          public boolean hasOtherTasks() {
            return false;
          }
        });
    SafWorkbench.Ticket ticket = workbench.prepare(ID, "file.txt", true);
    assertEquals(
        "MAINTENANCE_BUSY",
        assertThrows(SafTree.Failure.class, () -> workbench.transfer(ticket, "/root/out.txt", true))
            .code);
    assertFalse(new File(rootfs, "root/out.txt").exists());
    assertEquals(
        SafCapabilityLimits.MAX_CONCURRENT_TRANSFERS, SafTransferGate.TRANSFERS.availablePermits());
  }

  @Test
  public void unavailableLedgerBlocksNativeBrowsingAndPreviewWithDistinctCode() throws Exception {
    SafTestSupport.FakePreferences broken = new SafTestSupport.FakePreferences();
    broken.commitResult = false;
    android.content.ContextWrapper fallback =
        new android.content.ContextWrapper(app) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return SafLedgerStore.NAMESPACE.equals(name)
                ? broken
                : super.getSharedPreferences(name, mode);
          }
        };
    app.getSharedPreferences("saf_grants", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("uris", java.util.Set.of(TREE))
        .putString("label:" + TREE, "Legacy")
        .commit();
    SafWorkbench failed = new SafWorkbench(fallback, () -> rootfs);
    assertNotNull("bridge keeps legacy fallback", SafGrants.lookup(fallback, ID).grant);
    assertEquals(
        "STATE_UNAVAILABLE", assertThrows(SafTree.Failure.class, () -> failed.list(ID, "")).code);
    assertEquals(
        "STATE_UNAVAILABLE",
        assertThrows(SafTree.Failure.class, () -> failed.read(ID, "file.txt", 10)).code);
  }

  @Test
  public void bridgeAndWorkbenchShareTheSameTwoTransferSlots() throws Exception {
    Field field = com.deepseekharness.app.HttpShellService.class.getDeclaredField("SAF_TRANSFERS");
    field.setAccessible(true);
    assertSame(SafTransferGate.TRANSFERS, field.get(null));
    assertTrue(SafTransferGate.TRANSFERS.tryAcquire(SafCapabilityLimits.MAX_CONCURRENT_TRANSFERS));
    try {
      SafWorkbench.Ticket ticket = workbench.prepare(ID, "file.txt", true);
      assertEquals(
          "BUSY",
          assertThrows(
                  SafTree.Failure.class, () -> workbench.transfer(ticket, "/root/busy.txt", true))
              .code);
      workbench.cancel(ticket);
      assertFalse(new File(rootfs, "root/busy.txt").exists());
    } finally {
      SafTransferGate.TRANSFERS.release(SafCapabilityLimits.MAX_CONCURRENT_TRANSFERS);
    }
  }
}
