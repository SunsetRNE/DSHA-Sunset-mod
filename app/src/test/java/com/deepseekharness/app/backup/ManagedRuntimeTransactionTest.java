package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ManagedRuntimeLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** 真实私有文件验证：试运行失败、退出不明、保留旧版本和非目标数据均覆盖。 */
public class ManagedRuntimeTransactionTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();
  private static final String RUNTIME = ManagedRuntimeLayout.ROOT + ManagedRuntimeLayout.DSH;
  private static final String DESCRIPTOR_PATH = "linux/.runtime-descriptor.json";

  static RuntimeDescriptor descriptor(char id) throws Exception {
    var json = RuntimeDescriptorTest.value();
    json.put("inputs", RuntimeDescriptorTest.inputs(id));
    RuntimeDescriptorTest.identify(json);
    return new RuntimeDescriptor(json);
  }

  static Map<String, Object> health(RuntimeDescriptor descriptor) {
    var proof = new LinkedHashMap<String, Object>();
    proof.put("runtimeId", descriptor.id());
    proof.put("nonce", "1".repeat(32));
    proof.put("port", 3080L);
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

  private void put(File root, String path, String text) throws Exception {
    File target = new File(root, path);
    Files.createDirectories(target.getParentFile().toPath());
    Files.writeString(target.toPath(), text);
  }

  private String read(File root, String path) throws Exception {
    return Files.readString(new File(root, path).toPath());
  }

  private Map<String, Object> liveHealth(RuntimeDescriptor descriptor, String... additionalPaths)
      throws IOException {
    Map<String, Object> proof = health(descriptor);
    Map<String, String> hashes = new LinkedHashMap<>();
    List<String> paths = new ArrayList<>(List.of(RUNTIME));
    paths.addAll(List.of(additionalPaths));
    for (String path : paths)
      hashes.put(
          path, BackupTree.digest(fs, fs.child(temp.getRoot(), path), new BackupControl(null)));
    proof.put("managedHashes", hashes);
    return proof;
  }

  private Map<String, Object> archiveHealth(RuntimeDescriptor descriptor, String... otherPaths)
      throws IOException {
    Files.write(
        fs.child(temp.getRoot(), DESCRIPTOR_PATH).toPath(),
        BackupJson.write(descriptor.json(), BackupLimits.MANIFEST));
    var paths = new ArrayList<>(List.of(DESCRIPTOR_PATH));
    paths.addAll(List.of(otherPaths));
    return liveHealth(descriptor, paths.toArray(String[]::new));
  }

  private ManagedRuntimeTransaction prepare(char old, char next) throws Exception {
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", String.valueOf(old));
    put(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat", "original chat");
    var task = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    put(task.stage(), RUNTIME + "/lib/bin.js", String.valueOf(next));
    task.prepare(
        List.of(RUNTIME),
        descriptor(next),
        descriptor(old),
        liveHealth(descriptor(old)),
        new BackupControl(null));
    return task;
  }

  @Test
  public void completedRuntimeHistoryDoesNotIncreaseStablePendingCost() throws Exception {
    for (int count : new int[] {0, 65, 1000}) {
      File files = temp.newFolder(),
          home = new File(files, ManagedRuntimeTransaction.HOME),
          completed = new File(home, "completed");
      Files.createDirectories(completed.toPath());
      Map<String, Object> candidate = descriptor('a').json();
      for (int index = 0; index < count; index++) {
        String id = UUID.randomUUID().toString();
        File entry = new File(completed, id);
        Files.createDirectory(entry.toPath());
        Files.writeString(new File(entry, "finalized").toPath(), id + "\nfinalized\n");
        Files.write(
            new File(entry, "runtime-mapping.json").toPath(),
            BackupJson.write(
                Map.of(
                    "version",
                    1L,
                    "id",
                    id,
                    "paths",
                    Map.of("runtime-0", RUNTIME),
                    "candidateDescriptor",
                    candidate,
                    "createdAt",
                    1L),
                BackupLimits.MANIFEST));
        Files.write(
            new File(entry, "plan.json").toPath(),
            BackupJson.write(Map.of("version", 1L, "id", id), 4096));
      }
      JvmBackupFileSystem delegate = new JvmBackupFileSystem();
      int[] calls = {0}, historyLists = {0};
      BackupFileSystem counted =
          (BackupFileSystem)
              java.lang.reflect.Proxy.newProxyInstance(
                  BackupFileSystem.class.getClassLoader(),
                  new Class[] {BackupFileSystem.class},
                  (proxy, method, args) -> {
                    calls[0]++;
                    if (method.getName().equals("list") && ((File) args[0]).equals(completed))
                      historyLists[0]++;
                    try {
                      return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                      throw error.getCause();
                    }
                  });
      assertTrue(ManagedRuntimeTransaction.pending(counted, files).isEmpty());
      assertEquals(1, historyLists[0]);
      calls[0] = historyLists[0] = 0;
      long start = System.nanoTime();
      for (int i = 0; i < 5; i++)
        assertTrue(ManagedRuntimeTransaction.pending(counted, files).isEmpty());
      long elapsed = System.nanoTime() - start;
      assertEquals(0, historyLists[0]);
      assertTrue(calls[0] < 160);
      System.out.println(
          "RUNTIME_HISTORY_STABLE count="
              + count
              + " fsCalls="
              + calls[0]
              + " elapsedNanos="
              + elapsed);
    }
  }

  @Test
  public void webFailureRestoresOldFilesAndRetainsFailedCandidate() throws Exception {
    var task = prepare('a', 'b');
    assertThrows(
        IOException.class,
        () ->
            task.commit(
                new BackupControl(null),
                () -> {
                  throw new IOException("WEB_FAILED");
                },
                () -> true));
    assertEquals("a", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals("b", read(task.directory(), "failed/runtime-0/lib/bin.js"));
    assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
    assertTrue(ManagedRuntimeTransaction.pending(fs, temp.getRoot()).isEmpty());
  }

  @Test
  public void nodeVersionAloneCannotFinalize() throws Exception {
    var task = prepare('a', 'b');
    assertThrows(
        IOException.class,
        () ->
            task.commit(
                new BackupControl(null),
                () -> Map.of("runtimeId", "b".repeat(64), "nativeModules", true),
                () -> true));
    assertEquals("a", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertFalse(new File(task.directory(), "finalized").exists());
  }

  @Test
  public void unconfirmedGuestExitKeepsBothTreesAndBlocksNewUpdate() throws Exception {
    var task = prepare('a', 'b');
    assertThrows(
        IOException.class,
        () ->
            task.commit(
                new BackupControl(null),
                () -> {
                  throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
                },
                () -> false));
    assertEquals("b", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals("a", read(task.directory(), "previous/runtime-0/lib/bin.js"));
    assertThrows(IOException.class, () -> ManagedRuntimeTransaction.create(fs, temp.getRoot()));
    var pending = ManagedRuntimeTransaction.pending(fs, temp.getRoot());
    assertEquals(1, pending.size());
    pending.get(0).recover();
    assertEquals("a", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
  }

  @Test
  public void lateUserDataNeverRollsBackAfterHealthCommit() throws Exception {
    var task = prepare('a', 'b');
    var next = descriptor('b');
    task.commit(new BackupControl(null), () -> health(next), () -> true);
    assertTrue(task.hasHealthyPrevious());
    put(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat", "new chat");
    task.recover();
    assertEquals("new chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
    assertEquals("b", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), next, health(next));
    assertEquals("a", read(task.directory(), "previous/runtime-0/lib/bin.js"));
  }

  @Test
  public void readOnlyPendingCannotMoveCommittedWorkBeforeCleanup() throws Exception {
    var task = prepare('a', 'b');
    var current = descriptor('b');
    task.commit(new BackupControl(null), () -> health(current), () -> true);
    File active = task.directory();
    assertTrue(ManagedRuntimeTransaction.pending(fs, temp.getRoot()).isEmpty());
    assertTrue("caller still owns its work until trim finishes", active.isDirectory());
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), current, health(current));
    assertTrue(task.directory().isDirectory());
    assertEquals("completed", task.directory().getParentFile().getName());
    assertEquals("a", read(task.directory(), "previous/runtime-0/lib/bin.js"));
    put(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat", "later user conversation");
    assertTrue(task.hasHealthyPrevious());
    task.recover();
    assertEquals(
        "later user conversation", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
  }

  @Test
  public void legacyVersionOneWithoutSealKeepsExplicitRollbackAfterFullPreflight()
      throws Exception {
    var task = prepare('a', 'b');
    var current = descriptor('b');
    task.commit(new BackupControl(null), () -> health(current), () -> true);
    File mapping = new File(task.directory(), "runtime-mapping.json");
    var record = BackupJson.read(Files.readAllBytes(mapping.toPath()), BackupLimits.MANIFEST);
    record.put("version", 1L);
    Files.write(mapping.toPath(), BackupJson.write(record, BackupLimits.MANIFEST));
    Files.delete(new File(task.directory(), "archive-seal.json").toPath());
    assertEquals(
        1, ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), current, current).size());
    assertEquals("a", read(task.directory(), "previous/runtime-0/lib/bin.js"));
    ManagedRuntimeTransaction.archiveCompleted(fs, temp.getRoot());
    assertEquals(
        "legacy original remains in active location without an invented seal",
        new File(temp.getRoot(), ManagedRuntimeTransaction.HOME),
        task.directory().getParentFile());
    put(task.directory(), "previous/runtime-0/lib/bin.js", "user changed previous bytes");
    assertTrue(
        ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), current, current).isEmpty());
    assertThrows(
        IOException.class,
        () ->
            ManagedRuntimeTransaction.prepareRollback(
                fs,
                temp.getRoot(),
                task.directory().getName(),
                current,
                current,
                health(current),
                new BackupControl(null)));
  }

  @Test
  public void newVersionTwoWithoutSealCannotBorrowLegacyCompatibility() throws Exception {
    var task = prepare('a', 'b');
    var current = descriptor('b');
    task.commit(new BackupControl(null), () -> health(current), () -> true);
    Files.delete(new File(task.directory(), "archive-seal.json").toPath());
    assertTrue(
        ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), current, current).isEmpty());
    ManagedRuntimeTransaction.archiveCompleted(fs, temp.getRoot());
    assertEquals(
        new File(temp.getRoot(), ManagedRuntimeTransaction.HOME), task.directory().getParentFile());
  }

  @Test
  public void healthCheckCannotMutateManagedCandidate() throws Exception {
    var task = prepare('a', 'b');
    var next = descriptor('b');
    assertThrows(
        IOException.class,
        () ->
            task.commit(
                new BackupControl(null),
                () -> {
                  Files.writeString(
                      new File(temp.getRoot(), RUNTIME + "/lib/bin.js").toPath(),
                      "unexpected mutation");
                  return health(next);
                },
                () -> true));
    assertEquals("unexpected mutation", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals("a", read(task.directory(), "previous/runtime-0/lib/bin.js"));
    assertEquals(1, ManagedRuntimeTransaction.pending(fs, temp.getRoot()).size());
  }

  private List<ManagedRuntimeTransaction> committedHistory() throws Exception {
    List<ManagedRuntimeTransaction> history = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      char old = (char) ('a' + index), next = (char) (old + 1);
      var task = prepare(old, next);
      var candidate = descriptor(next);
      task.commit(new BackupControl(null), () -> health(candidate), () -> true);
      history.add(task);
      Thread.sleep(2);
    }
    return history;
  }

  @Test
  public void cleanupRechecksBytesAndRetainsTwoVerifiedPredecessors() throws Exception {
    var history = committedHistory();
    put(history.get(0).directory(), "previous/runtime-0/lib/bin.js", "user edited original");
    var active = descriptor('e');
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, liveHealth(active));
    assertEquals(
        "user edited original", read(history.get(0).directory(), "previous/runtime-0/lib/bin.js"));
    assertFalse(new File(history.get(1).directory(), "previous").exists());
    assertEquals("c", read(history.get(2).directory(), "previous/runtime-0/lib/bin.js"));
    assertEquals("d", read(history.get(3).directory(), "previous/runtime-0/lib/bin.js"));
  }

  @Test
  public void incompatibleHealthyVersionsDoNotAccumulateForever() throws Exception {
    var history = committedHistory();
    var value = RuntimeDescriptorTest.value();
    value.put("dataWrite", "dsh-v4");
    value.put("dataRead", List.of("dsh-v4"));
    RuntimeDescriptorTest.identify(value);
    var active = new RuntimeDescriptor(value);
    assertFalse(descriptor('a').canReadDataWrittenBy(active));
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, liveHealth(active));
    assertFalse(new File(history.get(0).directory(), "previous").exists());
    assertFalse(new File(history.get(1).directory(), "previous").exists());
    assertTrue(new File(history.get(2).directory(), "previous").exists());
    assertTrue(new File(history.get(3).directory(), "previous").exists());
    assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
  }

  @Test
  public void changedNewerCopiesCannotJustifyDeletingLastHealthyCopies() throws Exception {
    var history = committedHistory();
    for (int index = 2; index < 4; index++)
      put(history.get(index).directory(), "previous/runtime-0/lib/bin.js", "changed");
    var active = descriptor('e');
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, liveHealth(active));
    for (var task : history) assertTrue(new File(task.directory(), "previous").isDirectory());
    assertEquals("a", read(history.get(0).directory(), "previous/runtime-0/lib/bin.js"));
    assertEquals("b", read(history.get(1).directory(), "previous/runtime-0/lib/bin.js"));
  }

  @Test
  public void unknownFileInsideRetainedTreePreventsAutomaticCleanup() throws Exception {
    var history = committedHistory();
    put(history.get(0).directory(), "previous/unique-project.txt", "only copy");
    var active = descriptor('e');
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, liveHealth(active));
    assertEquals("only copy", read(history.get(0).directory(), "previous/unique-project.txt"));
    assertEquals("a", read(history.get(0).directory(), "previous/runtime-0/lib/bin.js"));
  }

  @Test
  public void directFallbackAndDistinctRuntimeTakePriorityOverRecentDuplicates() throws Exception {
    var directA = commit('a', 'd');
    var oldB = commit('b', 'c');
    var repeatedB = commit('b', 'c');
    var directB = commit('b', 'd');
    var active = descriptor('d');
    String directAKey = fs.stat(directA.directory()).key;
    String directBKey = fs.stat(directB.directory()).key;
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, liveHealth(active));
    assertEquals("a", read(directA.directory(), "previous/runtime-0/lib/bin.js"));
    assertEquals("b", read(directB.directory(), "previous/runtime-0/lib/bin.js"));
    assertEquals(directAKey, fs.stat(directA.directory()).key);
    assertEquals(directBKey, fs.stat(directB.directory()).key);
    assertFalse(new File(oldB.directory(), "previous").exists());
    assertFalse(new File(repeatedB.directory(), "previous").exists());
    assertEquals(
        Set.of(descriptor('a').id(), descriptor('b').id()),
        Set.copyOf(
            ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), active, active).stream()
                .map(option -> option.runtimeId)
                .toList()));
    auditRetained("direct-a", directA);
    auditRetained("direct-b", directB);
  }

  @Test
  public void completedRollbacksRotateWithoutDiscardingTheirOwnReturnPath() throws Exception {
    List<ManagedRuntimeTransaction> series = new ArrayList<>();
    series.add(commit('a', 'b'));
    RuntimeDescriptor expected = descriptor('b'), active = expected;
    BackupControl control = new BackupControl(null);
    for (int index = 0; index < 6; index++) {
      var options = ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), expected, active);
      assertFalse(options.isEmpty());
      assertEquals(series.get(series.size() - 1).directory().getName(), options.get(0).operationId);
      RuntimeDescriptor target = descriptor(index % 2 == 0 ? 'a' : 'b');
      var rollback =
          ManagedRuntimeTransaction.prepareRollback(
              fs,
              temp.getRoot(),
              options.get(0).operationId,
              expected,
              active,
              liveHealth(active),
              control);
      rollback.commit(control, () -> liveHealth(target), () -> true);
      series.add(rollback);
      ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), expected, target, liveHealth(target));
      active = target;
      assertEquals(index % 2 == 0 ? "a" : "b", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
      assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
      Thread.sleep(2);
    }
    assertEquals(
        2, series.stream().filter(t -> new File(t.directory(), "previous").isDirectory()).count());
    assertEquals(
        7, fs.list(new File(temp.getRoot(), ManagedRuntimeTransaction.HOME + "/completed")).size());
    auditRetained("rollback-return", series.get(series.size() - 1));
  }

  @Test
  public void staleLiveHealthCannotAuthorizeRetirement() throws Exception {
    var history = committedHistory();
    var active = descriptor('e');
    var proof = liveHealth(active);
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "edited after confirmation");
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, proof);
    for (var item : history) assertTrue(new File(item.directory(), "previous").isDirectory());
    assertEquals("edited after confirmation", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "e");
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, proof);
    assertFalse(new File(history.get(0).directory(), "previous").exists());
    assertTrue(new File(history.get(3).directory(), "previous").isDirectory());
  }

  @Test
  public void completedRetirementCanPublishItsMarkerAfterInterruption() throws Exception {
    var history = committedHistory();
    var active = descriptor('e');
    var proof = liveHealth(active);
    assertThrows(
        IOException.class,
        () ->
            ManagedRuntimeTransaction.trimOlder(
                fs,
                temp.getRoot(),
                active,
                active,
                proof,
                new BackupControl(null),
                (root, relative) -> {
                  fs.removeOwned(root, relative);
                  throw new IOException("INTERRUPTED_AFTER_REMOVAL");
                }));
    var interrupted = history.get(1).directory();
    assertTrue(new File(interrupted, "previous-retiring").isFile());
    assertFalse(new File(interrupted, "previous-cleaned").exists());
    assertFalse(new File(interrupted, "previous").exists());
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, proof);
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, proof);
    assertTrue(new File(history.get(1).directory(), "previous-cleaned").isFile());
    assertFalse(new File(history.get(0).directory(), "previous").exists());
    assertTrue(new File(history.get(2).directory(), "previous").isDirectory());
    assertTrue(new File(history.get(3).directory(), "previous").isDirectory());
  }

  @Test
  public void partialRetirementRetainsUnverifiedRemainderOnRetry() throws Exception {
    var history = committedHistory();
    var active = descriptor('e');
    var proof = liveHealth(active);
    assertThrows(
        IOException.class,
        () ->
            ManagedRuntimeTransaction.trimOlder(
                fs,
                temp.getRoot(),
                active,
                active,
                proof,
                new BackupControl(null),
                (root, relative) -> {
                  fs.removeOwned(root, relative + "/runtime-0/lib/bin.js");
                  throw new IOException("INTERRUPTED_DURING_REMOVAL");
                }));
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, proof);
    assertTrue(new File(history.get(1).directory(), "previous-retiring").isFile());
    assertTrue(new File(history.get(1).directory(), "previous/runtime-0/lib").isDirectory());
    assertFalse(new File(history.get(1).directory(), "previous-cleaned").exists());
    assertFalse(new File(history.get(0).directory(), "previous").exists());
  }

  @Test
  public void previouslyPresentMissingFileInvalidatesOldHealth() throws Exception {
    String marker = "linux/.offline-identity";
    var old = descriptor('a');
    var active = descriptor('b');
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "a");
    put(temp.getRoot(), marker, "old build");
    var stale = liveHealth(old, marker);
    Files.delete(new File(temp.getRoot(), marker).toPath());
    var upgrade = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    put(upgrade.stage(), RUNTIME + "/lib/bin.js", "b");
    put(upgrade.stage(), marker, "new build");
    upgrade.prepare(List.of(RUNTIME, marker), active, old, stale, new BackupControl(null));
    upgrade.commit(new BackupControl(null), () -> liveHealth(active, marker), () -> true);
    assertFalse(upgrade.hasHealthyPrevious());
    assertEquals("a", read(upgrade.directory(), "previous/runtime-0/lib/bin.js"));
    assertEquals("new build", read(temp.getRoot(), marker));
  }

  @Test
  public void upgradeRetiresOnlyProvenOwnedAliasAndRollbackRestoresIt() throws Exception {
    String alias = ManagedRuntimeLayout.ROOT + "usr/local/bin/tsserver";
    String unrelated = ManagedRuntimeLayout.ROOT + "usr/local/bin/tsc";
    var old = descriptor('a');
    var active = descriptor('b');
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "old");
    put(temp.getRoot(), unrelated, "user tool");
    File link = new File(temp.getRoot(), alias);
    String destination = "../lib/node_modules/@deepseek-ai/dsh/lib/bin.js";
    try {
      fs.symlink(destination, link);
    } catch (UnsupportedOperationException | IOException unavailable) {
      org.junit.Assume.assumeNoException("actual symlink required", unavailable);
    }
    var upgrade = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    put(upgrade.stage(), RUNTIME + "/lib/bin.js", "new");
    BackupControl control = new BackupControl(null);
    upgrade.prepare(List.of(RUNTIME), active, old, liveHealth(old, alias), control);
    upgrade.commit(control, () -> liveHealth(active), () -> true);
    assertEquals("MISSING", fs.stat(link).type);
    assertTrue(upgrade.hasHealthyPrevious());
    assertEquals(destination, fs.readLink(new File(upgrade.directory(), "previous/runtime-1")));
    var rollback =
        ManagedRuntimeTransaction.prepareRollback(
            fs,
            temp.getRoot(),
            upgrade.directory().getName(),
            active,
            active,
            liveHealth(active),
            control);
    rollback.commit(control, () -> liveHealth(old, alias), () -> true);
    assertEquals(destination, fs.readLink(link));
    assertEquals("user tool", read(temp.getRoot(), unrelated));
    assertEquals("old", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    auditRetained("retired-alias", upgrade);
  }

  private ManagedRuntimeTransaction commit(char old, char next) throws Exception {
    var operation = prepare(old, next);
    var candidate = descriptor(next);
    operation.commit(new BackupControl(null), () -> liveHealth(candidate), () -> true);
    Thread.sleep(2);
    return operation;
  }

  @Test
  public void coldCopyRestoresExactTreeAndKeepsOtherExpandedDirectFallback() throws Exception {
    var history = compactionHistory();
    var older = history.get(0);
    var latest = history.get(1);
    File raw = new File(older.directory(), "previous/runtime-0");
    assumeDirectoryIdentity(raw);
    var active = descriptor('d');
    var treeHash = BackupTree.digest(fs, raw, new BackupControl(null));
    var compacted = compact(active, fs::removeOwned);
    assertEquals("COMPACTED", compacted.outcome());
    assertEquals(1, compacted.copies());
    assertTrue(compacted.expandedBytes() > 1024 * 1024);
    assertTrue(compacted.archiveBytes() < compacted.expandedBytes());
    assertFalse(raw.exists());
    assertTrue(new File(latest.directory(), "previous/runtime-0").isDirectory());
    assertFalse(new File(latest.directory(), "cold").exists());
    assertFalse(new File(older.directory(), "cold-work").exists());
    var rollback =
        ManagedRuntimeTransaction.prepareRollback(
            fs,
            temp.getRoot(),
            older.directory().getName(),
            active,
            active,
            liveHealth(active),
            new BackupControl(null));
    var target = descriptor('a');
    rollback.commit(new BackupControl(null), () -> liveHealth(target), () -> true);
    assertEquals(
        treeHash,
        BackupTree.digest(fs, fs.child(temp.getRoot(), RUNTIME), new BackupControl(null)));
    assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
    System.out.println(
        "RUNTIME_COLD directory="
            + older.directory()
            + " logicalBytes="
            + compacted.expandedBytes()
            + " compressedBytes="
            + compacted.archiveBytes()
            + " restoredTree="
            + treeHash);
  }

  @Test
  public void coldArchiveRemainsUsableAndOwnedTombstoneResumesAfterInterruption() throws Exception {
    var older = compactionHistory().get(0);
    assumeDirectoryIdentity(new File(older.directory(), "previous/runtime-0"));
    var active = descriptor('d');
    assertThrows(
        IOException.class,
        () ->
            compact(
                active,
                (operation, relative) -> {
                  if (relative.equals("cold-work/retired")) {
                    fs.removeOwned(operation, relative + "/lib/bin.js");
                    throw new IOException("INTERRUPTED_DURING_RETIREMENT");
                  }
                  fs.removeOwned(operation, relative);
                }));
    assertFalse(new File(older.directory(), "previous/runtime-0").exists());
    assertTrue(new File(older.directory(), "cold-work/retired").isDirectory());
    assertTrue(older.hasHealthyPrevious());
    String unknown = "cold-work/retired/lib/unknown.js";
    put(older.directory(), unknown, "user's only copy");
    assertThrows(IOException.class, () -> compact(active, fs::removeOwned));
    assertEquals("user's only copy", read(older.directory(), unknown));
    assertTrue(older.hasHealthyPrevious());
    Files.delete(new File(older.directory(), unknown).toPath());
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    assertFalse(new File(older.directory(), "cold-work").exists());
    assertTrue(older.hasHealthyPrevious());
  }

  @Test
  public void corruptedColdArchiveCannotHideUnrelatedExpandedRollback() throws Exception {
    var history = compactionHistory();
    var older = history.get(0);
    assumeDirectoryIdentity(new File(older.directory(), "previous/runtime-0"));
    var active = descriptor('d');
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    File archive = new File(older.directory(), "cold/archive.bin");
    try (RandomAccessFile mutate = new RandomAccessFile(archive, "rw")) {
      long midpoint = mutate.length() / 2;
      mutate.seek(midpoint);
      int original = mutate.readUnsignedByte();
      mutate.seek(midpoint);
      mutate.write(original ^ 128);
    }
    var options = ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), active, active);
    assertEquals(1, options.size());
    assertEquals(history.get(1).directory().getName(), options.get(0).operationId);
    assertTrue(archive.isFile());
    assertThrows(
        IOException.class,
        () ->
            ManagedRuntimeTransaction.prepareRollback(
                fs,
                temp.getRoot(),
                older.directory().getName(),
                active,
                active,
                liveHealth(active),
                new BackupControl(null)));
    assertEquals("d", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
  }

  @Test
  public void insufficientCompactionSpaceDoesNotWriteArchiveOrMoveOriginal() throws Exception {
    var history = compactionHistory();
    var older = history.get(0);
    var active = descriptor('d');
    File fullVolume =
        new File(temp.getRoot().getPath()) {
          @Override
          public long getUsableSpace() {
            return 0;
          }
        };
    assertEquals(
        "INSUFFICIENT_SPACE",
        ManagedRuntimeTransaction.compactOlder(
                fs,
                fullVolume,
                active,
                active,
                archiveHealth(active),
                new BackupControl(null),
                fs::removeOwned)
            .outcome());
    assertTrue(new File(older.directory(), "previous/runtime-0").isDirectory());
    assertFalse(new File(older.directory(), "cold").exists());
    assertFalse(new File(older.directory(), "cold-work").exists());
  }

  @Test
  public void interruptedStagingAcceptsAnExactPrefixAndPreservesSameNameForeignBytes()
      throws Exception {
    var older = compactionHistory().get(0);
    File raw = new File(older.directory(), "previous/runtime-0");
    assumeDirectoryIdentity(raw);
    var active = descriptor('d');
    BackupControl interrupt =
        new BackupControl(
            (stage, files, bytes) -> {
              if (stage.equals("ROLLBACK_ARCHIVE_RESTORE_DIGEST"))
                throw new InterruptedIOException("FIXTURE_INTERRUPTED_STAGE");
            });
    assertThrows(
        InterruptedIOException.class,
        () ->
            ManagedRuntimeTransaction.compactOlder(
                fs,
                temp.getRoot(),
                active,
                active,
                archiveHealth(active),
                interrupt,
                fs::removeOwned));
    assertTrue(raw.isDirectory());
    assertFalse(new File(older.directory(), "cold").exists());
    File partial = new File(older.directory(), "cold-work/verified/lib/larger.js");
    try (RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
      output.setLength(23);
      output.write(7);
    }
    fs.mode(partial, 0600);
    fs.mode(partial.getParentFile(), 0700);
    assertThrows(IOException.class, () -> compact(active, fs::removeOwned));
    assertEquals(7, Files.readAllBytes(partial.toPath())[0]);
    assertTrue(raw.isDirectory());
    try (RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
      output.write(0);
    }
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    assertFalse(raw.exists());
    assertFalse(new File(older.directory(), "cold-work").exists());
  }

  @Test
  public void sealedOriginalWithoutHealthArchivesAndRestoresWithoutAcquiringRollbackRights()
      throws Exception {
    var history = compactionHistory(false, 'a', 'b', 'c');
    var older = history.get(0);
    File raw = new File(older.directory(), "previous/runtime-0");
    assumeDirectoryIdentity(raw);
    var active = descriptor('d');
    var original = BackupTree.digest(fs, raw, new BackupControl(null));
    put(older.directory(), "previous/extra-project.txt", "private original");
    assertFalse(older.hasHealthyPrevious());
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    assertFalse(raw.exists());
    assertTrue(new File(history.get(1).directory(), "previous/runtime-0").isDirectory());
    assertEquals("private original", read(older.directory(), "previous/extra-project.txt"));
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, liveHealth(active));
    assertTrue(new File(older.directory(), "cold/archive.bin").isFile());
    assertTrue(
        ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), active, active).isEmpty());
    String archiveSha = archivedSha(older);
    var interrupted =
        restoreArchive(
            older,
            archiveSha,
            active,
            (operation, relative) -> {
              if (relative.equals("cold")) throw new IOException("RETIREMENT_INTERRUPTED");
              fs.removeOwned(operation, relative);
            });
    assertEquals("RESTORED_ARCHIVE_RETAINED", interrupted.outcome());
    assertEquals(original, BackupTree.digest(fs, raw, new BackupControl(null)));
    assertTrue(new File(older.directory(), "cold/archive.bin").isFile());
    assertTrue(new File(older.directory(), "previous-expanded").isFile());
    assertEquals(
        "ALREADY_EXPANDED", restoreArchive(older, archiveSha, active, fs::removeOwned).outcome());
    assertEquals(original, BackupTree.digest(fs, raw, new BackupControl(null)));
    assertEquals("private original", read(older.directory(), "previous/extra-project.txt"));
    assertFalse(new File(older.directory(), "cold").exists());
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    assertTrue(raw.isDirectory());
    assertTrue(new File(history.get(1).directory(), "cold/archive.bin").isFile());
    assertFalse(older.hasHealthyPrevious());
    assertEquals("d", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
    assertTrue(
        ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), active, active).isEmpty());
  }

  @Test
  public void changedSealedOriginalWithoutHealthIsNeverArchived() throws Exception {
    var older = compactionHistory(false).get(0);
    var active = descriptor('d');
    put(older.directory(), "previous/runtime-0/lib/bin.js", "user edited runtime");
    var skipped = compact(active, fs::removeOwned);
    assertEquals("PROOF_UNAVAILABLE", skipped.outcome());
    assertEquals("HISTORICAL_DSH_DIGEST", skipped.reason());
    assertEquals("user edited runtime", read(older.directory(), "previous/runtime-0/lib/bin.js"));
    assertFalse(new File(older.directory(), "cold").exists());
    assertFalse(new File(older.directory(), "cold-work").exists());
  }

  @Test
  public void archiveRestoreRejectsAStaleSelectionAndConflictingRawOriginal() throws Exception {
    var older = compactionHistory(false).get(0);
    File raw = new File(older.directory(), "previous/runtime-0");
    assumeDirectoryIdentity(raw);
    var active = descriptor('d');
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    String archiveSha = archivedSha(older);
    assertEquals(
        "PROOF_UNAVAILABLE",
        restoreArchive(older, "0".repeat(64), active, fs::removeOwned).outcome());
    assertFalse(raw.exists());
    assertFalse(new File(older.directory(), "cold-work").exists());
    put(older.directory(), "previous/runtime-0/lib/bin.js", "different raw tree");
    assertEquals(
        "RAW_CONFLICT", restoreArchive(older, archiveSha, active, fs::removeOwned).outcome());
    assertEquals("different raw tree", read(older.directory(), "previous/runtime-0/lib/bin.js"));
    assertTrue(new File(older.directory(), "cold/archive.bin").isFile());
    assertFalse(new File(older.directory(), "cold-work").exists());
  }

  @Test
  public void archiveRestoreWithLowSpaceOrCorruptBytesKeepsColdOriginal() throws Exception {
    var older = compactionHistory(false).get(0);
    File raw = new File(older.directory(), "previous/runtime-0");
    assumeDirectoryIdentity(raw);
    var active = descriptor('d');
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    String archiveSha = archivedSha(older);
    File noSpace =
        new File(temp.getRoot().getPath()) {
          @Override
          public long getUsableSpace() {
            return 0;
          }
        };
    assertEquals(
        "INSUFFICIENT_SPACE",
        ManagedRuntimeTransaction.restoreArchivedOriginal(
                fs,
                noSpace,
                older.directory().getName(),
                archiveSha,
                active,
                active,
                archiveHealth(active),
                new BackupControl(null),
                fs::removeOwned)
            .outcome());
    assertFalse(raw.exists());
    assertFalse(new File(older.directory(), "cold-work").exists());
    File archive = new File(older.directory(), "cold/archive.bin");
    try (RandomAccessFile mutate = new RandomAccessFile(archive, "rw")) {
      mutate.write(1);
    }
    assertThrows(
        IOException.class, () -> restoreArchive(older, archiveSha, active, fs::removeOwned));
    assertFalse(raw.exists());
    assertTrue(archive.isFile());
    assertFalse(new File(older.directory(), "cold-work").exists());
    assertFalse(new File(older.directory(), "previous-expanded").exists());
  }

  @Test
  public void manualArchiveAndExpandAllowClearedBuiltinCacheButKeepAutomaticTrimStrict()
      throws Exception {
    var history = compactionHistory(true, 'a', 'b', 'c', 'e');
    var oldest = history.get(0);
    File raw = new File(oldest.directory(), "previous/runtime-0");
    assumeDirectoryIdentity(raw);
    var active = descriptor('d');
    String entity = "root/dsha-web-mobile";
    String cache = entity + "/.cache";
    assertTrue(com.deepseekharness.app.util.RegenerableCachePaths.rootfs().contains(cache));
    put(temp.getRoot(), ManagedRuntimeLayout.ROOT + entity + "/lib/client.js", "builtin");
    put(temp.getRoot(), ManagedRuntimeLayout.ROOT + cache + "/temp", "cached");
    Map<String, Object> proof = archiveHealth(active, ManagedRuntimeLayout.ROOT + entity);
    String original = BackupTree.digest(fs, raw, new BackupControl(null));
    fs.removeOwned(temp.getRoot(), ManagedRuntimeLayout.ROOT + cache);

    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), active, proof);
    for (var item : history)
      assertTrue(new File(item.directory(), "previous/runtime-0").isDirectory());
    assertEquals("COMPACTED", compact(active, proof, fs::removeOwned).outcome());
    assertFalse(new File(oldest.directory(), "previous/runtime-0").exists());
    assertTrue(new File(history.get(3).directory(), "previous/runtime-0").isDirectory());
    String archiveSha = archivedSha(oldest);

    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "altered active");
    assertEquals(
        "ACTIVE_DSH_DIGEST",
        restoreArchive(oldest, archiveSha, active, proof, fs::removeOwned).reason());
    assertFalse(new File(oldest.directory(), "cold-work").exists());
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "d");
    File descriptor = fs.child(temp.getRoot(), DESCRIPTOR_PATH);
    byte[] installed = Files.readAllBytes(descriptor.toPath());
    Files.writeString(descriptor.toPath(), "altered descriptor");
    assertEquals(
        "ACTIVE_DESCRIPTOR_DIGEST",
        restoreArchive(oldest, archiveSha, active, proof, fs::removeOwned).reason());
    assertTrue(new File(oldest.directory(), "cold/archive.bin").isFile());
    assertFalse(new File(oldest.directory(), "cold-work").exists());
    Files.write(descriptor.toPath(), installed);

    assertEquals(
        "RESTORED", restoreArchive(oldest, archiveSha, active, proof, fs::removeOwned).outcome());
    assertEquals(
        original,
        BackupTree.digest(
            fs, new File(oldest.directory(), "previous/runtime-0"), new BackupControl(null)));
    assertEquals(
        "builtin", read(temp.getRoot(), ManagedRuntimeLayout.ROOT + entity + "/lib/client.js"));
    assertFalse(new File(temp.getRoot(), ManagedRuntimeLayout.ROOT + cache).exists());
    assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
  }

  @Test
  public void manualArchiveReportsMissingAndChangedLiveCriticalProofs() throws Exception {
    var history = compactionHistory(false);
    var active = descriptor('d');
    assertEquals("ACTIVE_PROOF_MAP", compact(active, liveHealth(active), fs::removeOwned).reason());
    Map<String, Object> proof = archiveHealth(active);
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "changed active");
    assertEquals("ACTIVE_DSH_DIGEST", compact(active, proof, fs::removeOwned).reason());
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "d");
    put(temp.getRoot(), DESCRIPTOR_PATH, "changed descriptor data");
    assertEquals("ACTIVE_DESCRIPTOR_DIGEST", compact(active, proof, fs::removeOwned).reason());
    for (var item : history) {
      assertTrue(new File(item.directory(), "previous/runtime-0").isDirectory());
      assertFalse(new File(item.directory(), "cold").exists());
      assertFalse(new File(item.directory(), "cold-work").exists());
    }
  }

  @Test
  public void manualArchivePreservesChangedOldestAndTriesTheNextOriginal() throws Exception {
    var history = compactionHistory(false, 'a', 'b', 'c');
    var oldest = history.get(0);
    var next = history.get(1);
    assumeDirectoryIdentity(new File(next.directory(), "previous/runtime-0"));
    var active = descriptor('d');
    put(oldest.directory(), "previous/runtime-0/lib/bin.js", "changed old original");
    assertEquals("COMPACTED", compact(active, fs::removeOwned).outcome());
    assertEquals("changed old original", read(oldest.directory(), "previous/runtime-0/lib/bin.js"));
    assertFalse(new File(oldest.directory(), "cold").exists());
    assertTrue(new File(next.directory(), "cold/archive.bin").isFile());
    assertTrue(new File(history.get(2).directory(), "previous/runtime-0").isDirectory());
  }

  private String archivedSha(ManagedRuntimeTransaction operation) throws IOException {
    return BackupJson.string(
        BackupJson.read(
            Files.readAllBytes(new File(operation.directory(), "cold/proof.json").toPath()), 4096),
        "archiveSha256");
  }

  private ManagedRuntimeTransaction.RestorationResult restoreArchive(
      ManagedRuntimeTransaction operation,
      String archiveSha,
      RuntimeDescriptor active,
      StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    return restoreArchive(operation, archiveSha, active, archiveHealth(active), remove);
  }

  private ManagedRuntimeTransaction.RestorationResult restoreArchive(
      ManagedRuntimeTransaction operation,
      String archiveSha,
      RuntimeDescriptor active,
      Map<String, Object> proof,
      StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    return ManagedRuntimeTransaction.restoreArchivedOriginal(
        fs,
        temp.getRoot(),
        operation.directory().getName(),
        archiveSha,
        active,
        active,
        proof,
        new BackupControl(null),
        remove);
  }

  private List<ManagedRuntimeTransaction> compactionHistory() throws Exception {
    return compactionHistory(true);
  }

  private List<ManagedRuntimeTransaction> compactionHistory(boolean previousHealth)
      throws Exception {
    return compactionHistory(previousHealth, 'a', 'b');
  }

  private List<ManagedRuntimeTransaction> compactionHistory(boolean previousHealth, char... before)
      throws Exception {
    var history = new ArrayList<ManagedRuntimeTransaction>();
    var active = descriptor('d');
    for (char old : before) {
      put(temp.getRoot(), RUNTIME + "/lib/bin.js", String.valueOf(old));
      Files.write(
          new File(temp.getRoot(), RUNTIME + "/lib/larger.js").toPath(), new byte[1024 * 1024]);
      put(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat", "original chat");
      var update = ManagedRuntimeTransaction.create(fs, temp.getRoot());
      put(update.stage(), RUNTIME + "/lib/bin.js", "d");
      update.prepare(
          List.of(RUNTIME),
          active,
          descriptor(old),
          previousHealth ? liveHealth(descriptor(old)) : null,
          new BackupControl(null));
      update.commit(new BackupControl(null), () -> liveHealth(active), () -> true);
      history.add(update);
      Thread.sleep(2);
    }
    return history;
  }

  private void assumeDirectoryIdentity(File raw) throws IOException {
    org.junit.Assume.assumeFalse(
        "directory identity required for durable retirement", fs.stat(raw).key.equals("null"));
  }

  private ManagedRuntimeTransaction.CompactionResult compact(
      RuntimeDescriptor active, StorageMaintenance.PayloadRemoval remove) throws IOException {
    return compact(active, archiveHealth(active), remove);
  }

  private ManagedRuntimeTransaction.CompactionResult compact(
      RuntimeDescriptor active, Map<String, Object> proof, StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    return ManagedRuntimeTransaction.compactOlder(
        fs, temp.getRoot(), active, active, proof, new BackupControl(null), remove);
  }

  private void auditRetained(String name, ManagedRuntimeTransaction operation) throws IOException {
    File root = new File(operation.directory(), "previous/runtime-0");
    var node = fs.stat(new File(root, "lib/bin.js"));
    System.out.println(
        "RUNTIME_RETENTION "
            + name
            + " directory="
            + operation.directory()
            + " identity="
            + fs.stat(operation.directory()).key
            + " bytes="
            + node.size
            + " treeHash="
            + BackupTree.digest(fs, root, new BackupControl(null)));
  }

  @Test
  public void runtimeMappingCannotWriteUserTargets() throws Exception {
    var task = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    put(task.stage(), "linux/ubuntu/root/.dsh/sessions/chat", "incoming");
    assertThrows(
        IOException.class,
        () ->
            task.prepare(
                List.of("linux/ubuntu/root/.dsh/sessions"),
                descriptor('b'),
                null,
                null,
                new BackupControl(null)));
    assertFalse(new File(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions").exists());
  }

  @Test
  public void historyBeyondLegacyThirtyTwoStillAllowsNewTransaction() throws Exception {
    File home = new File(temp.getRoot(), ManagedRuntimeTransaction.HOME);
    assertTrue(home.mkdirs());
    for (int i = 0; i < 32; i++) assertTrue(new File(home, UUID.randomUUID().toString()).mkdirs());
    var task = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    assertNotNull(task);
    assertEquals(33, fs.list(home).size());
  }

  @Test
  public void sixtyFifthOperationDoesNotHitHistoricalDirectoryLimit() throws Exception {
    File home = new File(temp.getRoot(), ManagedRuntimeTransaction.HOME);
    assertTrue(home.mkdirs());
    // Unknown but valid historical records are retained; they no longer make
    // the next signed-runtime transaction permanently unavailable.
    File first = null;
    for (int i = 0; i < 64; i++) {
      File record = new File(home, UUID.randomUUID().toString());
      assertTrue(record.mkdir());
      Files.writeString(new File(record, "retained-unknown.bin").toPath(), "preserve-" + i);
      if (i == 0) first = record;
    }
    var next = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    assertNotNull(next);
    assertEquals(65, fs.list(home).size());
    assertEquals("preserve-0", Files.readString(new File(first, "retained-unknown.bin").toPath()));
  }

  @Test
  public void onlyExplicitlyCreatedEmptyPlaceholderIsPruned() throws Exception {
    File home = new File(temp.getRoot(), ManagedRuntimeTransaction.HOME);
    assertTrue(home.mkdirs());
    var empty = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    File emptySlot = empty.directory();
    var next = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    assertFalse(emptySlot.exists());
    assertTrue(next.directory().isDirectory());

    var partial = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    File partialStage = new File(partial.stage(), "linux/ubuntu/lib/partial.bin");
    Files.createDirectories(partialStage.toPath().getParent());
    Files.writeString(partialStage.toPath(), "unverified stage bytes");
    var following = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    assertTrue(partialStage.isFile());
    assertTrue(following.directory().isDirectory());
  }

  @Test
  public void completedSignedRuntimeHistoryRemainsAuditablePastSixtyFour() throws Exception {
    char old = 'a';
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", String.valueOf(old));
    List<String> ids = new ArrayList<>();
    for (int index = 0; index < 70; index++) {
      char next = (char) ('A' + index % 26);
      var task = prepare(old, next);
      var candidate = descriptor(next);
      task.commit(new BackupControl(null), () -> health(candidate), () -> true);
      String originalKey = fs.stat(task.directory()).key;
      ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), candidate, liveHealth(candidate));
      assertEquals(originalKey, fs.stat(task.directory()).key);
      ids.add(task.directory().getName());
      old = next;
    }
    File home = new File(temp.getRoot(), ManagedRuntimeTransaction.HOME);
    assertEquals(70, fs.list(new File(home, "completed")).size());
    for (String id : ids) {
      var retained = ManagedRuntimeTransaction.open(fs, temp.getRoot(), id, null);
      assertEquals("completed", retained.directory().getParentFile().getName());
      assertTrue(new File(retained.directory(), "plan.json").isFile());
    }
    assertEquals(String.valueOf(old), read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
    assertTrue(
        "the direct healthy predecessor remains rollbackable",
        ManagedRuntimeTransaction.rollbackOptions(
                    fs, temp.getRoot(), descriptor(old), descriptor(old))
                .size()
            >= 1);
    var next = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    assertNotNull("no historical total-count cliff after seventy completed updates", next);
    assertEquals(70, fs.list(new File(home, "completed")).size());
    assertTrue(next.directory().isDirectory());
  }

  @Test
  public void modifiedCompletedJournalIsNeverAutomaticallyDeleted() throws Exception {
    var task = prepare('a', 'b');
    var candidate = descriptor('b');
    task.commit(new BackupControl(null), () -> health(candidate), () -> true);
    File mapping = new File(task.directory(), "runtime-mapping.json");
    var edited = BackupJson.read(Files.readAllBytes(mapping.toPath()), BackupLimits.MANIFEST);
    edited.put("createdAt", 123456789L);
    Files.write(mapping.toPath(), BackupJson.write(edited, BackupLimits.MANIFEST));
    File extra = new File(task.directory(), "retained-user-evidence.bin");
    Files.writeString(extra.toPath(), "keep this original");
    ManagedRuntimeTransaction.trimOlder(fs, temp.getRoot(), candidate, health(candidate));
    assertTrue(task.directory().isDirectory());
    assertEquals("keep this original", Files.readString(extra.toPath()));
    assertTrue(mapping.isFile());
    assertEquals(
        new File(temp.getRoot(), ManagedRuntimeTransaction.HOME), task.directory().getParentFile());
    assertTrue(
        ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), candidate, candidate)
            .isEmpty());
  }

  @Test
  public void processDeathAtHealthAndAgainDuringRollbackRetainsOriginal() throws Exception {
    var task = prepare('a', 'b');
    killAt(task, "validation-complete", false);
    assertTrue(new File(task.directory(), "health.json").isFile());
    assertFalse(new File(task.directory(), "finalized").exists());
    killAt(task, "rollback-new-runtime-0", true);
    var pending = ManagedRuntimeTransaction.pending(fs, temp.getRoot());
    assertEquals(1, pending.size());
    pending.get(0).recover();
    pending.get(0).recover();
    assertEquals("a", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals("original chat", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
    assertEquals("b", read(task.directory(), "failed/runtime-0/lib/bin.js"));
  }

  @Test
  public void explicitCompatibleRollbackKeepsMessagesWrittenByNewVersion() throws Exception {
    var task = prepare('a', 'b');
    var current = descriptor('b');
    task.commit(new BackupControl(null), () -> health(current), () -> true);
    put(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat", "message written by b");
    var options = ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), current, current);
    assertEquals(1, options.size());
    var rollback =
        ManagedRuntimeTransaction.prepareRollback(
            fs,
            temp.getRoot(),
            options.get(0).operationId,
            current,
            current,
            health(current),
            new BackupControl(null));
    var old = descriptor('a');
    rollback.commit(new BackupControl(null), () -> health(old), () -> true);
    assertEquals("a", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals(
        "message written by b", read(temp.getRoot(), "linux/ubuntu/root/.dsh/sessions/chat"));
    assertEquals("b", read(rollback.directory(), "previous/runtime-0/lib/bin.js"));
  }

  @Test
  public void incompatibleLauncherOrTamperedRetainedRuntimeCannotRollBack() throws Exception {
    var task = prepare('a', 'b');
    var current = descriptor('b');
    task.commit(new BackupControl(null), () -> health(current), () -> true);
    var changed = current.json();
    changed.put("launcherContract", "NEXT_CONTRACT");
    RuntimeDescriptorTest.identify(changed);
    var nextApk = new RuntimeDescriptor(changed);
    assertTrue(
        ManagedRuntimeTransaction.rollbackOptions(fs, temp.getRoot(), nextApk, current).isEmpty());
    assertThrows(
        IOException.class,
        () ->
            ManagedRuntimeTransaction.prepareRollback(
                fs,
                temp.getRoot(),
                task.directory().getName(),
                nextApk,
                current,
                health(current),
                new BackupControl(null)));
    put(task.directory(), "previous/runtime-0/lib/bin.js", "tampered");
    assertThrows(
        IOException.class,
        () ->
            ManagedRuntimeTransaction.prepareRollback(
                fs,
                temp.getRoot(),
                task.directory().getName(),
                current,
                current,
                health(current),
                new BackupControl(null)));
    assertEquals("b", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
    assertEquals(1, fs.list(new File(temp.getRoot(), ManagedRuntimeTransaction.HOME)).size());
  }

  @Test
  public void rollbackRemovesOnlyNewManagedAliasAndKeepsBothVersions() throws Exception {
    String alias = "linux/ubuntu/usr/local/bin/tsserver";
    put(temp.getRoot(), RUNTIME + "/lib/bin.js", "a");
    var previous = health(descriptor('a'));
    previous.put(
        "managedHashes",
        Map.of(
            RUNTIME,
            BackupTree.digest(fs, new File(temp.getRoot(), RUNTIME), new BackupControl(null))));
    var task = ManagedRuntimeTransaction.create(fs, temp.getRoot());
    put(task.stage(), RUNTIME + "/lib/bin.js", "b");
    put(task.stage(), alias, "new managed alias");
    task.prepare(
        List.of(RUNTIME, alias),
        descriptor('b'),
        descriptor('a'),
        previous,
        new BackupControl(null));
    var current = descriptor('b');
    task.commit(new BackupControl(null), () -> health(current), () -> true);
    var rollback =
        ManagedRuntimeTransaction.prepareRollback(
            fs,
            temp.getRoot(),
            task.directory().getName(),
            current,
            current,
            health(current),
            new BackupControl(null));
    var old = descriptor('a');
    rollback.commit(new BackupControl(null), () -> health(old), () -> true);
    assertFalse(new File(temp.getRoot(), alias).exists());
    assertEquals("new managed alias", read(rollback.directory(), "previous/runtime-1"));
    assertEquals("a", read(temp.getRoot(), RUNTIME + "/lib/bin.js"));
  }

  private void killAt(ManagedRuntimeTransaction task, String phase, boolean recovery)
      throws Exception {
    File marker = new File(temp.getRoot(), "reached-" + phase);
    Set<String> classpath = new LinkedHashSet<>();
    for (Class<?> type :
        List.of(
            ManagedRuntimeTransactionTest.class,
            HostDataTransaction.class,
            com.google.gson.stream.JsonReader.class,
            kotlin.Unit.class,
            org.junit.Assert.class))
      classpath.add(
          new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
    File binary =
        new File(
            System.getProperty("java.home"),
            "bin/" + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"));
    Process child =
        new ProcessBuilder(
                binary.getPath(),
                "-cp",
                String.join(File.pathSeparator, classpath),
                HostRuntimeCrashProcess.class.getName(),
                temp.getRoot().getPath(),
                task.directory().getName(),
                phase,
                Boolean.toString(recovery))
            .redirectErrorStream(true)
            .redirectOutput(new File(temp.getRoot(), "runtime-child-" + phase + ".log"))
            .start();
    long until = System.currentTimeMillis() + 15000;
    try {
      while (!marker.exists() && child.isAlive() && System.currentTimeMillis() < until)
        Thread.sleep(20);
      assertTrue("未到真实强杀边界：" + phase, marker.exists());
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }
}
