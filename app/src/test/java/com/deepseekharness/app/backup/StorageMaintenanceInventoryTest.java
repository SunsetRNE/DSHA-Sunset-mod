package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;

import com.deepseekharness.app.util.Fmt;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class StorageMaintenanceInventoryTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  private static final String ID = "00000000-0000-4000-8000-000000000001";

  @Test
  public void automaticCleanupYieldsToAnUnattemptedRuntime() throws Exception {
    var active = cleanupDescriptor('a');
    var candidate = cleanupDescriptor('b');
    assertTrue(active.compatible(candidate));
    assertFalse(active.latest(candidate));
    var observation =
        new StorageMaintenance.RuntimeState(candidate, active, cleanupReceipt(active, '1'));
    assertFalse(observation.allowsIdleCleanup(() -> true));
  }

  @Test
  public void acceptedHealthyPredecessorUsesItsObservedProofInsideTheLease() throws Exception {
    var active = cleanupDescriptor('a');
    var candidate = cleanupDescriptor('b');
    var initial =
        new StorageMaintenance.RuntimeState(candidate, active, cleanupReceipt(active, '1'));
    var policyReads = new AtomicInteger();
    assertTrue(
        initial.allowsIdleCleanup(
            () -> {
              policyReads.incrementAndGet();
              return false;
            }));
    assertTrue(
        initial.matchesHealthyRuntime(
            new StorageMaintenance.RuntimeState(
                cleanupDescriptor('b'), cleanupDescriptor('a'), cleanupReceipt(active, '1'))));
    assertEquals(1, policyReads.get());
  }

  @Test
  public void healthyCurrentRuntimeDoesNotConsultPreviousRuntimeAdmission() throws Exception {
    var current = cleanupDescriptor('b');
    var observation =
        new StorageMaintenance.RuntimeState(current, current, cleanupReceipt(current, '1'));
    assertTrue(
        observation.allowsIdleCleanup(
            () -> {
              fail("the prior-runtime admission policy should not run");
              return true;
            }));
  }

  @Test
  public void leaseRecheckRejectsCandidateActiveOrHealthChanges() throws Exception {
    var active = cleanupDescriptor('a');
    var candidate = cleanupDescriptor('b');
    var initial =
        new StorageMaintenance.RuntimeState(candidate, active, cleanupReceipt(active, '1'));
    assertFalse(initial.matchesHealthyRuntime(null));
    assertFalse(
        initial.matchesHealthyRuntime(
            new StorageMaintenance.RuntimeState(
                cleanupDescriptor('c'), active, cleanupReceipt(active, '1'))));
    assertFalse(
        initial.matchesHealthyRuntime(
            new StorageMaintenance.RuntimeState(
                candidate, candidate, cleanupReceipt(candidate, '1'))));
    assertFalse(
        initial.matchesHealthyRuntime(
            new StorageMaintenance.RuntimeState(candidate, active, cleanupReceipt(active, '2'))));
    var invalid =
        new StorageMaintenance.RuntimeState(candidate, active, cleanupReceipt(candidate, '1'));
    assertFalse(invalid.allowsIdleCleanup(() -> false));
    assertFalse(initial.matchesHealthyRuntime(invalid));
  }

  private static RuntimeDescriptor cleanupDescriptor(char revision) throws Exception {
    var value = RuntimeDescriptorTest.value();
    value.put("inputs", RuntimeDescriptorTest.inputs(revision));
    RuntimeDescriptorTest.identify(value);
    return new RuntimeDescriptor(value);
  }

  private static Map<String, Object> cleanupReceipt(RuntimeDescriptor descriptor, char nonce) {
    var proof = new LinkedHashMap<String, Object>();
    proof.put("runtimeId", descriptor.id());
    proof.put("nonce", String.valueOf(nonce).repeat(32));
    proof.put("port", 12345L);
    proof.put("confirmedAt", 1L);
    for (String check :
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
            "processExited")) proof.put(check, true);
    return proof;
  }

  @Test
  public void inventoryAndCleanupAccountForTheSameWhitelistExactlyOnce() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File web = folder.newFolder("app_webview");
    File personal = fixture(files, "linux/ubuntu/root/.dsh/sessions/chat", 19);
    File attachments = fixture(files, "user-data-v5/attachments/one", 17);
    File plugins = fixture(files, "linux/ubuntu/root/.dsh/plugin-src/user/.cache/original", 13);
    File other = fixture(files, "workspace/user/project", 11);
    File audit = fixture(files, "runtime-startup/original", 7);
    File cachedAudit = fixture(cache, "layout-audit/original", 5);
    File unlisted = fixture(cache, "user-owned-file", 23);
    File cookie = fixture(web, "Default/Cookies", 29);
    File manual = fixture(files, PrivateRoots.BACKUPS + "/manual/portable.dshbak", 31);
    File automatic = fixture(files, PrivateRoots.BACKUPS + "/automatic/portable.dshbak", 37);
    File guestTemp = fixture(files, "linux/ubuntu/root/.dsh/.cache/generated", 2);
    File webTemp = fixture(web, "Default/GPUCache/generated", 3);
    File appTemp = fixture(cache, "Code Cache/generated", 4);
    String runtime = PrivateRoots.RUNTIMES + "/completed/" + ID + "/";
    File runtimePrevious = fixture(files, runtime + "previous/node/runtime", 47);
    File unmarkedPrevious = fixture(files, PrivateRoots.RUNTIMES + "/" + ID + "/previous/old", 9);
    fixture(files, runtime + "finalized", 1);
    fixture(files, runtime + "archive-seal.json", 1);
    fixture(files, runtime + "cold/archive.bin", 12);
    fixture(files, runtime + "cold/proof.json", 3);
    fixture(files, runtime + "cold-work/archive.bin", 5);
    fixture(files, PrivateRoots.RUNTIMES + "/" + ID + "/failed/untouched", 53);
    String environment = PrivateRoots.ENVIRONMENTS + "/" + ID + "/";
    File retired = fixture(files, environment + "previous-linux/tree", 41);
    fixture(files, environment + "committed", 1);
    fixture(files, environment + "retired-proof.json", 1);
    long device = fs.stat(files).device;

    var sizes = StorageMaintenance.inspect(fs, files, cache, device);
    assertSize(sizes, "linux", 34, 2);
    assertSize(sizes, "user-data-v5", 17, 0);
    assertSize(sizes, "historical-audit-fixtures", 12, 0);
    assertSize(sizes, "cache", 27, 4);
    assertSize(sizes, "webview", 32, 3);
    assertSize(sizes, "other-private", 11, 0);
    assertSize(sizes, PrivateRoots.BACKUPS, 68, 0);
    assertSize(sizes, PrivateRoots.RUNTIMES, 131, 47);
    assertSize(sizes, PrivateRoots.ENVIRONMENTS, 43, 41);
    assertEquals(68, sizes.get(PrivateRoots.RUNTIMES).rollbackBytes);
    assertEquals(41, sizes.get(PrivateRoots.ENVIRONMENTS).rollbackBytes);

    AtomicInteger samples = new AtomicInteger();
    var outcome =
        StorageMaintenance.measured(
            () -> samples.getAndIncrement() == 0 ? 800 : 792,
            removed -> StorageMaintenance.cleanCaches(fs, files, cache, device, removed));
    assertEquals("", outcome.error);
    assertEquals(800, outcome.availableBefore);
    assertEquals(792, outcome.availableAfter);
    assertEquals(Map.of("linux", 2L, "webview", 3L, "cache", 4L), outcome.removed);
    assertEquals(9, outcome.removedBytes());
    for (File file : new File[] {guestTemp, webTemp, appTemp}) assertFalse(file.exists());
    for (File file :
        new File[] {
          personal,
          attachments,
          plugins,
          other,
          audit,
          cachedAudit,
          unlisted,
          cookie,
          manual,
          automatic,
          runtimePrevious,
          unmarkedPrevious,
          retired
        }) assertTrue(file.toString(), file.isFile());
  }

  @Test
  public void unreadableFilesRootMarksDependentCategoriesAndKeepsKnownBytes() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File web = folder.newFolder("app_webview");
    fixture(files, "linux/unseen", 17);
    File other = fixture(files, "misc/unseen", 2).getParentFile();
    fixture(cache, "Code Cache/known", 7);
    fixture(cache, "layout-audit/known", 3);
    fixture(web, "Default/Cookies", 5);
    long device = fs.stat(files).device;
    var progress = new ArrayList<StorageMaintenance.Progress>();

    var sizes =
        StorageMaintenance.inspect(denyListing(files), files, cache, device, progress::add, null);
    for (String key :
        List.of(
            "linux",
            "user-data-v5",
            PrivateRoots.RUNTIMES,
            PrivateRoots.ENVIRONMENTS,
            PrivateRoots.BACKUPS,
            "runtime-updates",
            "mozilla",
            "other-private")) {
      assertEquals(key, 1, sizes.get(key).unreadable);
      assertSize(sizes, key, 0, 0);
    }
    assertSize(sizes, "historical-audit-fixtures", 3, 0);
    assertEquals(1, sizes.get("historical-audit-fixtures").unreadable);
    assertSize(sizes, "cache", 7, 7);
    assertEquals(0, sizes.get("cache").unreadable);
    assertSize(sizes, "webview", 5, 0);
    assertEquals(0, sizes.get("webview").unreadable);
    var terminal = progress.get(progress.size() - 1);
    assertEquals(StorageMaintenance.Outcome.PARTIAL, terminal.outcome());
    assertEquals(15, terminal.knownBytes());

    var local = StorageMaintenance.inspect(denyListing(other), files, cache, device);
    assertSize(local, "linux", 17, 0);
    assertEquals(1, local.get("other-private").unreadable);
    assertTrue(
        local.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("other-private"))
            .allMatch(entry -> entry.getValue().unreadable == 0));
  }

  @Test
  public void unreadableCacheRootMarksSharedHistoryWithoutHidingFiles() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    folder.newFolder("app_webview");
    fixture(files, "linux/known", 11);
    fixture(files, "runtime-startup/known", 6);
    fixture(cache, "layout-audit/unseen", 13);
    long device = fs.stat(files).device;

    var sizes = StorageMaintenance.inspect(denyListing(cache), files, cache, device);
    assertSize(sizes, "linux", 11, 0);
    assertEquals(0, sizes.get("linux").unreadable);
    assertSize(sizes, "cache", 0, 0);
    assertEquals(1, sizes.get("cache").unreadable);
    assertSize(sizes, "historical-audit-fixtures", 6, 0);
    assertEquals(1, sizes.get("historical-audit-fixtures").unreadable);
    assertSize(sizes, "webview", 0, 0);
    assertEquals(0, sizes.get("webview").unreadable);

    var progress = new ArrayList<StorageMaintenance.Progress>();
    var healthy = StorageMaintenance.inspect(fs, files, cache, device, progress::add, null);
    assertTrue(healthy.values().stream().allMatch(size -> size.unreadable == 0));
    assertSize(healthy, "historical-audit-fixtures", 19, 0);
    assertSize(healthy, "cache", 0, 0);
    assertSize(healthy, "webview", 0, 0);
    var terminal = progress.get(progress.size() - 1);
    assertEquals(StorageMaintenance.Outcome.SUCCESS, terminal.outcome());
    assertEquals(30, terminal.knownBytes());
  }

  private JvmBackupFileSystem denyListing(File denied) {
    return new JvmBackupFileSystem() {
      @Override
      public List<String> list(File root) throws IOException {
        if (root.equals(denied)) throw new AccessDeniedException(root.getName());
        return super.list(root);
      }
    };
  }

  @Test
  public void countsOnlySuccessfullyRemovedFilesBeforeADeviceBoundary() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File ordinary = fixture(cache, "WebView/a-local", 5);
    File foreign = fixture(cache, "WebView/z-other-volume", 41);
    long device = fs.stat(files).device;
    var multiDevice =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            return file.equals(foreign)
                ? new Node(node.type, node.key, node.size, node.modified, device + 1, node.mode)
                : node;
          }
        };
    var sizes = StorageMaintenance.inspect(multiDevice, files, cache, device);
    assertSize(sizes, "cache", 5, 5);
    assertEquals(1, sizes.get("cache").unreadable);

    var result =
        StorageMaintenance.measured(
            () -> -1,
            removed -> StorageMaintenance.cleanCaches(multiDevice, files, cache, device, removed));
    assertEquals("STORAGE_VOLUME_BOUNDARY", result.error);
    assertEquals(-1, result.availableBefore);
    assertEquals(-1, result.availableAfter);
    assertEquals(Map.of("cache", 5L), result.removed);
    assertFalse(ordinary.exists());
    assertTrue(foreign.isFile());
  }

  @Test
  public void storageUnitsAreBinaryAndPreserveSignedChanges() {
    assertEquals("1023 B", Fmt.binaryBytes(1023));
    assertEquals("1.00 KiB", Fmt.binaryBytes(1024));
    assertEquals("-1.50 MiB", Fmt.binaryBytes(-1572864));
  }

  @Test
  public void scanVisitsEachRollbackDirectoryAndProofOnce() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File previous =
        fixture(files, PrivateRoots.RUNTIMES + "/completed/" + ID + "/previous/old", 17);
    File operation = previous.getParentFile().getParentFile();
    File marker = fixture(operation, "finalized", 1);
    File proof = fixture(operation, "archive-seal.json", 2);
    Map<File, Integer> statCalls = new HashMap<>(), listCalls = new HashMap<>();
    var counted =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            statCalls.merge(file, 1, Integer::sum);
            return super.stat(file);
          }

          @Override
          public java.util.List<String> list(File file) throws IOException {
            listCalls.merge(file, 1, Integer::sum);
            return super.list(file);
          }
        };
    var events = new ArrayList<StorageMaintenance.Progress>();
    var sizes =
        StorageMaintenance.inspect(counted, files, cache, fs.stat(files).device, events::add, null);
    assertSize(sizes, PrivateRoots.RUNTIMES, 20, 17);
    for (int times : statCalls.values()) assertEquals(1, times);
    for (int times : listCalls.values()) assertEquals(1, times);
    assertTrue(statCalls.containsKey(previous));
    assertTrue(statCalls.containsKey(marker));
    assertTrue(statCalls.containsKey(proof));
    assertEquals(StorageMaintenance.Outcome.RUNNING, events.get(0).outcome());
    var done = events.get(events.size() - 1);
    assertEquals(StorageMaintenance.Outcome.SUCCESS, done.outcome());
    assertEquals(StorageMaintenance.Phase.SCANNING, done.phase());
    assertEquals(7, done.visitedEntries());
    assertEquals(20, done.knownBytes());
    assertTrue(events.stream().allMatch(event -> event.totalTargets() == -1));
  }

  @Test
  public void longScanPublishesAnIntermediateObservation() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File personal = fixture(files, "linux/large", 11);
    long device = fs.stat(files).device;
    var slow =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            if (file.equals(personal)) {
              try {
                TimeUnit.MILLISECONDS.sleep(160);
              } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("CANCELLED");
              }
            }
            return super.stat(file);
          }
        };
    var events = new ArrayList<StorageMaintenance.Progress>();
    StorageMaintenance.inspect(slow, files, cache, device, events::add, null);
    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event.outcome() == StorageMaintenance.Outcome.RUNNING
                        && event.category().equals("linux")
                        && event.visitedEntries() == 2
                        && event.knownBytes() == 11));
  }

  @Test
  public void digestProgressCountsReadBytesBeforeTheNextFileCompletes() throws Exception {
    File files = folder.newFolder("file-tree");
    int firstBytes = 128 * 1024, secondBytes = 4 * 1024 * 1024;
    fixture(files, "a-first", firstBytes);
    File second = fixture(files, "b-second", secondBytes);
    String expected = BackupTree.digest(fs, files, new BackupControl(null));
    var slow =
        new JvmBackupFileSystem() {
          @Override
          public InputStream read(File file, Node node) throws IOException {
            InputStream source = super.read(file, node);
            if (!file.equals(second)) return source;
            return new FilterInputStream(source) {
              int calls;

              @Override
              public int read(byte[] bytes, int offset, int length) throws IOException {
                if (++calls == 2 || calls == 10) {
                  try {
                    TimeUnit.MILLISECONDS.sleep(120);
                  } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("CANCELLED");
                  }
                }
                return super.read(bytes, offset, length);
              }
            };
          }
        };
    record Reading(long files, long bytes) {}
    var readings = new ArrayList<Reading>();
    assertEquals(
        expected,
        BackupTree.digest(
            slow,
            files,
            new BackupControl(null),
            (entries, bytes) -> readings.add(new Reading(entries, bytes))));
    assertEquals(new Reading(0, 0), readings.get(0));
    assertEquals(new Reading(2, firstBytes + secondBytes), readings.get(readings.size() - 1));
    assertTrue(
        readings.stream()
            .anyMatch(
                reading ->
                    reading.files == 1
                        && reading.bytes > firstBytes
                        && reading.bytes < firstBytes + secondBytes));
  }

  @Test
  public void scanCancellationStopsTraversalAndHasItsOwnOutcome() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File first = fixture(files, "first", 3);
    File skipped = fixture(files, "skipped", 5);
    long device = fs.stat(files).device;
    var control = new BackupControl(null);
    var cancelled =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            if (file.equals(first)) control.cancel();
            if (file.equals(skipped)) fail("cancelled scan reached a later file");
            return super.stat(file);
          }
        };
    var events = new ArrayList<StorageMaintenance.Progress>();
    assertThrows(
        InterruptedIOException.class,
        () -> StorageMaintenance.inspect(cancelled, files, cache, device, events::add, control));
    assertEquals(StorageMaintenance.Outcome.CANCELLED, events.get(events.size() - 1).outcome());
  }

  @Test
  public void cleanupCancellationPreservesExactPartialDeletionAndKnownTargetCount()
      throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File first = fixture(cache, "WebView/first", 5);
    File later = fixture(cache, "WebView/later", 11);
    long device = fs.stat(files).device;
    var control = new BackupControl(null);
    var cancellable =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            if (file.equals(later)) control.cancel();
            return super.stat(file);
          }
        };
    Map<String, Long> removed = new HashMap<>();
    var events = new ArrayList<StorageMaintenance.Progress>();
    assertThrows(
        InterruptedIOException.class,
        () ->
            StorageMaintenance.cleanCaches(
                cancellable, files, cache, device, removed, events::add, control));
    var done = events.get(events.size() - 1);
    assertEquals(StorageMaintenance.Outcome.CANCELLED, done.outcome());
    assertEquals(StorageMaintenance.Phase.REMOVING, done.phase());
    assertEquals("cache", done.category());
    assertEquals(0, done.completedTargets());
    assertEquals(5, done.totalTargets());
    assertEquals(3, done.visitedEntries());
    assertEquals(16, done.knownBytes());
    assertEquals(1, done.removedEntries());
    assertEquals(5, done.removedBytes());
    assertEquals(Map.of("cache", 5L), removed);
    assertFalse(first.exists());
    assertTrue(later.isFile());
  }

  @Test
  public void manualCompactionSkipIsSeparateFromVerifiedCacheDeletion() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File ordinary = fixture(cache, "DawnCache/local", 7);
    long device = fs.stat(files).device;
    var events = new ArrayList<StorageMaintenance.Progress>();
    var manual =
        StorageMaintenance.cleanOwned(
            fs, files, cache, device, () -> 500, null, null, Map.of(), events::add, null, true);
    assertEquals("", manual.error);
    assertEquals(7, manual.removedBytes());
    assertEquals(500, manual.availableAfter);
    assertEquals("PROOF_UNAVAILABLE", manual.compression.outcome());
    assertEquals("ACTIVE_DESCRIPTOR_MISSING", manual.compression.reason());
    assertEquals(0, manual.compression.copies());
    assertFalse(ordinary.exists());
    var done = events.get(events.size() - 1);
    assertEquals(StorageMaintenance.Phase.COMPRESSING, done.phase());
    assertEquals(StorageMaintenance.Outcome.SUCCESS, done.outcome());
    assertEquals(-1, done.compressionTotalBytes());

    fixture(cache, "DawnCache/later", 11);
    var idle =
        StorageMaintenance.cleanOwned(
            fs, files, cache, device, () -> 500, null, null, Map.of(), null, null, false);
    assertEquals("", idle.error);
    assertEquals(11, idle.removedBytes());
    assertNull(idle.compression);
  }

  @Test
  public void malformedExpansionTargetIsRefusedBeforeItNeedsAContext() throws Exception {
    var events = new ArrayList<StorageMaintenance.Progress>();
    String digest = "a".repeat(64);
    var badId =
        assertThrows(
            IOException.class,
            () ->
                StorageMaintenance.expandRollback(null, "../../other", digest, events::add, null));
    assertEquals("ROLLBACK_TARGET_INVALID", badId.getMessage());
    var badDigest =
        assertThrows(
            IOException.class,
            () -> StorageMaintenance.expandRollback(null, ID, "a".repeat(63), events::add, null));
    assertEquals("ROLLBACK_TARGET_INVALID", badDigest.getMessage());
    assertEquals(StorageMaintenance.Phase.EXPANDING, events.get(0).phase());
    assertEquals(StorageMaintenance.Outcome.RUNNING, events.get(0).outcome());
    assertEquals(StorageMaintenance.Outcome.FAILED, events.get(events.size() - 1).outcome());
  }

  @Test
  public void cancelledExpansionIsRefusedBeforeItNeedsAContext() throws Exception {
    var control = new BackupControl(null);
    control.cancel();
    var events = new ArrayList<StorageMaintenance.Progress>();
    assertThrows(
        InterruptedIOException.class,
        () -> StorageMaintenance.expandRollback(null, ID, "a".repeat(64), events::add, control));
    assertEquals(StorageMaintenance.Outcome.CANCELLED, events.get(events.size() - 1).outcome());
  }

  @Test
  public void linksDoNotContributeBytesOrRedirectCacheCleanup() throws Exception {
    File files = folder.newFolder("files");
    File cache = folder.newFolder("cache");
    File external = folder.newFolder("external");
    File original = fixture(external, "root/.dsh/.cache/original", 23);
    File linux = new File(files, "linux");
    File webCache = new File(cache, "WebView");
    Files.createDirectory(linux.toPath());
    try {
      fs.symlink(external.getPath(), new File(linux, "ubuntu"));
      fs.symlink(external.getPath(), webCache);
    } catch (IOException | UnsupportedOperationException unavailable) {
      assumeNoException(unavailable);
    }
    long device = fs.stat(files).device;
    var sizes = StorageMaintenance.inspect(fs, files, cache, device);
    assertSize(sizes, "linux", 0, 0);
    assertSize(sizes, "cache", 0, 0);
    Map<String, Long> removed = new HashMap<>();
    var events = new ArrayList<StorageMaintenance.Progress>();
    StorageMaintenance.cleanCaches(fs, files, cache, device, removed, events::add, null);
    assertTrue(original.isFile());
    assertTrue(Files.isSymbolicLink(new File(linux, "ubuntu").toPath()));
    assertFalse(Files.isSymbolicLink(webCache.toPath()));
    assertTrue(removed.isEmpty());
    var done = events.get(events.size() - 1);
    assertEquals(StorageMaintenance.Outcome.SUCCESS, done.outcome());
    assertEquals(done.totalTargets(), done.completedTargets());
    assertEquals(1, done.removedEntries());
    assertEquals(0, done.removedBytes());
  }

  private static File fixture(File root, String path, int length) throws IOException {
    File file = new File(root, path);
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), new byte[length]);
    return file;
  }

  private static void assertSize(
      Map<String, StorageMaintenance.Size> sizes, String key, long bytes, long candidate) {
    assertEquals(key, bytes, sizes.get(key).bytes);
    assertEquals(key, candidate, sizes.get(key).candidateBytes);
  }
}
