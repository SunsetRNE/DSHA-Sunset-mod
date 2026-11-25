package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.RollbackStorageInventory.Kind;
import com.deepseekharness.app.backup.RollbackStorageInventory.State;
import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RollbackStorageInventoryTest {
  private static final String ID = "72eb6c71-a11f-4f5e-a45b-ae643a1dacbe";
  private static final String DIGEST = "a".repeat(64);
  private static final String RUNTIME = PrivateRoots.RUNTIMES + "/completed/" + ID;
  private static final String ENVIRONMENT = PrivateRoots.ENVIRONMENTS + "/" + ID;

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void reportsHistoryAndManagedDshSeparatelyFromFailedArtifacts() throws IOException {
    Path files = folder.newFolder().toPath();
    var fs = new JvmBackupFileSystem();
    RuntimeDescriptor active = descriptor("0.2.2-rc.5"), previous = descriptor("0.2.2-rc.1");
    json(files, "linux/.runtime-descriptor.json", active.json());
    json(files, "runtime-health/" + active.id() + ".json", health(active));
    put(files, "linux/.offline-identity", "372:10:0.2.2-rc.5");
    put(files, RUNTIME + "/previous/runtime-0/dsh-package", "dsh payload");
    put(files, RUNTIME + "/previous/runtime-1", "371:10:0.2.2-rc.1");
    put(files, RUNTIME + "/failed/runtime-8/note", "failed");
    for (String marker : List.of("finalized", "switching")) mark(files, RUNTIME, marker);
    var mapping =
        Map.of(
            "id",
            ID,
            "version",
            2L,
            "createdAt",
            1L,
            "paths",
            Map.of(
                "runtime-0",
                ManagedRuntimeLayout.ROOT + ManagedRuntimeLayout.DSH,
                "runtime-1",
                "linux/.offline-identity"),
            "previousDescriptor",
            previous.json(),
            "candidateDescriptor",
            active.json(),
            "previousHealth",
            health(previous));
    byte[] mappingBytes = json(files, RUNTIME + "/runtime-mapping.json", mapping);
    byte[] plan =
        json(
            files,
            RUNTIME + "/plan.json",
            Map.of(
                "id",
                ID,
                "version",
                1L,
                "roots",
                List.of(
                    Map.of("root", "runtime-0", "before", DIGEST, "had", true),
                    Map.of("root", "runtime-1", "before", DIGEST, "had", true))));
    json(
        files,
        RUNTIME + "/archive-seal.json",
        Map.of("id", ID, "version", 1L, "mapping", sha(mappingBytes), "plan", sha(plan)));

    var result = inspect(fs, files, 1_000);
    assertEquals(active.id(), result.active().runtimeId());
    assertEquals("372", result.active().apkVersion());
    assertTrue(result.activeHealthRecorded());
    assertFalse(result.incomplete());
    assertFalse(result.pendingOperation());
    assertEquals(2, result.copies().size());
    var old = result.copies().get(0);
    assertEquals(RUNTIME + "/previous", old.relativePath());
    assertEquals(Kind.RUNTIME, old.kind());
    assertEquals(State.RETAINED_HISTORY, old.state());
    assertEquals(11, old.managedDshLogicalBytes());
    assertEquals(28, old.logicalBytes());
    assertEquals("371", old.identity().apkVersion());
    assertEquals(previous.id(), old.identity().runtimeId());
    assertTrue(old.replacedByActive());
    assertTrue(old.runtimeHealthRecorded());
    assertNull(old.expansionTarget());
    assertEquals(Kind.FAILED_RUNTIME, result.copies().get(1).kind());
    assertEquals(6, result.copies().get(1).logicalBytes());
    assertEquals(1, result.runtimeOperations());
    assertEquals(0, result.environmentOperations());
    assertTrue(result.operationMetadataBytes() > mappingBytes.length + plan.length);
    assertFalse(result.allocationComplete());

    mark(files, RUNTIME, "previous-retiring");
    result = inspect(fs, files, 1_000);
    assertTrue(result.pendingOperation());
    assertEquals(State.PENDING_OPERATION, result.copies().get(0).state());
    assertTrue(Files.exists(files.resolve(RUNTIME + "/previous/runtime-0/dsh-package")));

    Files.delete(files.resolve(RUNTIME + "/previous-retiring"));
    put(files, RUNTIME + "/cold/archive.bin", "zip");
    Map<String, Object> coldRecord =
        Map.of(
            "version",
            1L,
            "operationId",
            ID,
            "rootId",
            "runtime-0",
            "planSha256",
            sha(plan),
            "mappingSha256",
            sha(mappingBytes),
            "treeDigest",
            DIGEST,
            "archiveSha256",
            DIGEST,
            "archiveBytes",
            3L,
            "logicalBytes",
            11L,
            "entries",
            2L);
    json(files, RUNTIME + "/cold/proof.json", coldRecord);
    result = inspect(fs, files, 1_000);
    var cold = result.copies().get(1);
    assertTrue(result.pendingOperation());
    assertEquals(Kind.COMPRESSED_RUNTIME, cold.kind());
    assertEquals(State.PENDING_OPERATION, cold.state());
    assertEquals(3, cold.logicalBytes());
    assertEquals(11, cold.compressedOriginalBytes());
    assertTrue(cold.compressedProofRecorded());
    assertTrue(cold.runtimeHealthRecorded());
    assertNull(cold.expansionTarget());

    Files.delete(files.resolve(RUNTIME + "/previous/runtime-0/dsh-package"));
    Files.delete(files.resolve(RUNTIME + "/previous/runtime-0"));
    result = inspect(fs, files, 1_000);
    assertFalse(result.pendingOperation());
    assertEquals(0, result.copies().get(0).managedDshLogicalBytes());
    assertEquals(State.RETAINED_HISTORY, result.copies().get(1).state());
    assertEquals(ID, result.copies().get(1).expandOperationId());

    put(files, RUNTIME + "/cold-work/retired/dsh-package", "dsh payload");
    result = inspect(fs, files, 1_000);
    assertTrue(result.pendingOperation());
    assertEquals(State.PENDING_OPERATION, result.copies().get(1).state());
    assertNull(result.copies().get(1).expansionTarget());
    assertEquals(11, result.copies().get(2).logicalBytes());
    assertEquals(RUNTIME + "/cold-work", result.copies().get(2).relativePath());

    put(files, RUNTIME + "/cold/archive.bin", "incomplete replacement");
    cold = inspect(fs, files, 1_000).copies().get(1);
    assertFalse(cold.compressedProofRecorded());
    assertEquals(State.NO_PROOF, cold.state());
    assertEquals(-1, cold.compressedOriginalBytes());
  }

  @Test
  public void sealedColdArchiveIsExpandableWithUnknownHistoricalHealth() throws IOException {
    Path files = folder.newFolder().toPath();
    var fs = new JvmBackupFileSystem();
    var mapping = new LinkedHashMap<String, Object>();
    mapping.put("id", ID);
    mapping.put("version", 2L);
    mapping.put("createdAt", 1L);
    mapping.put(
        "paths",
        Map.of(
            "runtime-0",
            ManagedRuntimeLayout.ROOT + ManagedRuntimeLayout.DSH,
            "runtime-1",
            "linux/.offline-identity"));
    mapping.put("previousDescriptor", descriptor("0.2.2-rc.1").json());
    mapping.put("candidateDescriptor", descriptor("0.2.2-rc.5").json());
    mapping.put("previousHealth", Map.of());
    byte[] mappingBytes = json(files, RUNTIME + "/runtime-mapping.json", mapping);
    byte[] plan =
        json(
            files,
            RUNTIME + "/plan.json",
            Map.of(
                "id",
                ID,
                "version",
                1L,
                "roots",
                List.of(
                    Map.of("root", "runtime-0", "before", DIGEST, "had", true),
                    Map.of("root", "runtime-1", "before", DIGEST, "had", true))));
    json(
        files,
        RUNTIME + "/archive-seal.json",
        Map.of("id", ID, "version", 1L, "mapping", sha(mappingBytes), "plan", sha(plan)));
    String archivePath = RUNTIME + "/cold/archive.bin";
    put(files, archivePath, "zip");
    String archiveSha = "b".repeat(64);
    json(
        files,
        RUNTIME + "/cold/proof.json",
        Map.of(
            "version",
            1L,
            "operationId",
            ID,
            "rootId",
            "runtime-0",
            "planSha256",
            sha(plan),
            "mappingSha256",
            sha(mappingBytes),
            "treeDigest",
            DIGEST,
            "archiveSha256",
            archiveSha,
            "archiveBytes",
            3L,
            "logicalBytes",
            11L,
            "entries",
            2L));
    mark(files, RUNTIME, "finalized");
    put(files, RUNTIME + "/previous/runtime-1", "371:10:0.2.2-rc.1");

    var cold = inspect(fs, files, 1_000).copies().get(1);
    assertEquals(Kind.COMPRESSED_RUNTIME, cold.kind());
    assertEquals(State.ARCHIVE_PROOF_ONLY, cold.state());
    assertTrue(cold.compressedProofRecorded());
    assertFalse(cold.runtimeHealthRecorded());
    assertEquals(ID, cold.expandOperationId());
    assertEquals(archiveSha, cold.expansionTarget().archiveSha256());
    var selected =
        RollbackStorageInventory.inspectCopy(
            fs, files.toFile(), fs.stat(files.toFile()).device, () -> false, archivePath);
    assertEquals(cold.expansionTarget(), selected.expansionTarget());

    put(files, RUNTIME + "/cold-work/owned", ID + "\n");
    put(files, RUNTIME + "/cold-work/verified/dsh", "partial");
    cold = inspect(fs, files, 1_000).copies().get(1);
    assertEquals(State.PENDING_OPERATION, cold.state());
    assertEquals(ID, cold.expandOperationId());
    put(files, RUNTIME + "/cold-work/unknown", "keep");
    cold = inspect(fs, files, 1_000).copies().get(1);
    assertTrue(cold.compressedProofRecorded());
    assertNull(cold.expansionTarget());

    Files.delete(files.resolve(RUNTIME + "/cold-work/unknown"));
    String raw = RUNTIME + "/previous/runtime-0";
    put(files, raw + "/dsh", "unrelated raw");
    assertNull(inspect(fs, files, 1_000).copies().get(1).expansionTarget());
    assertEquals("unrelated raw", Files.readString(files.resolve(raw + "/dsh")));
    Files.delete(files.resolve(raw + "/dsh"));
    Files.delete(files.resolve(raw));
    put(files, "linux/ubuntu/root/personal", "active user data");
    Files.move(files.resolve(RUNTIME + "/cold-work/verified"), files.resolve(raw));

    cold = inspect(fs, files, 1_000).copies().get(1);
    assertEquals(State.PENDING_OPERATION, cold.state());
    assertEquals(
        new RollbackStorageInventory.ExpansionTarget(ID, archiveSha), cold.expansionTarget());
    assertEquals("partial", Files.readString(files.resolve(raw + "/dsh")));
    assertEquals("zip", Files.readString(files.resolve(archivePath)));
    assertEquals("active user data", Files.readString(files.resolve("linux/ubuntu/root/personal")));
    assertFalse(Files.exists(files.resolve(RUNTIME + "/previous-expanded")));

    put(files, RUNTIME + "/cold-work/owned", "foreign operation\n");
    assertNull(inspect(fs, files, 1_000).copies().get(1).expansionTarget());
    put(files, RUNTIME + "/cold-work/owned", ID + "\n");
    put(files, RUNTIME + "/previous/unmapped-original", "preserved original");
    assertNull(inspect(fs, files, 1_000).copies().get(1).expansionTarget());
    assertEquals(
        "preserved original",
        Files.readString(files.resolve(RUNTIME + "/previous/unmapped-original")));
    Files.delete(files.resolve(RUNTIME + "/previous/unmapped-original"));
    mark(files, RUNTIME, "previous-expanded");
    assertEquals(ID, inspect(fs, files, 1_000).copies().get(1).expandOperationId());

    mapping.put("version", 1L);
    mapping.put("previousHealth", health(descriptor("0.2.2-rc.1")));
    json(files, RUNTIME + "/runtime-mapping.json", mapping);
    var legacy = inspect(fs, files, 1_000);
    assertEquals(State.RETAINED_HISTORY, legacy.copies().get(0).state());
    assertTrue(legacy.copies().get(0).runtimeHealthRecorded());
    assertFalse(legacy.copies().get(1).compressedProofRecorded());
    assertNull(legacy.copies().get(1).expansionTarget());
  }

  @Test
  public void oldEnvironmentAndProtectedBackupAreDisjointAndMarkersAreSmall() throws IOException {
    Path files = folder.newFolder().toPath();
    var fs = new JvmBackupFileSystem();
    put(files, ENVIRONMENT + "/previous-linux/ubuntu/root/.dsha-web.pid", "123\n");
    put(files, ENVIRONMENT + "/previous-linux/ubuntu/root/.dsh/.plugins.lock", "lock");
    put(files, ENVIRONMENT + "/previous-linux/ubuntu/usr/package-lock.json", "package lock");
    put(files, ENVIRONMENT + "/previous-linux/.offline-identity", "307:10:0.2.1-rc.5");
    put(files, ENVIRONMENT + "/data.dshdata", "archive");
    put(files, ENVIRONMENT + "/data-roots.json", "roots");
    for (String marker : List.of("committed", "data-restored")) mark(files, ENVIRONMENT, marker);
    String oldKey = fs.stat(files.resolve(ENVIRONMENT + "/previous-linux").toFile()).key;
    json(
        files,
        ENVIRONMENT + "/retired-proof.json",
        Map.of(
            "id", ID, "version", 1L, "oldKey", oldKey, "digest", DIGEST, "archive", DIGEST,
            "mapping", DIGEST));
    json(
        files,
        ENVIRONMENT + "/intent.json",
        Map.of(
            "id", ID, "version", 1L, "oldKey", oldKey, "had", true, "archive", DIGEST, "mapping",
            DIGEST));

    var result = inspect(fs, files, 1_000);
    assertFalse(result.pendingOperation());
    assertEquals(2, result.copies().size());
    var old = result.copies().get(0);
    assertEquals(Kind.ENVIRONMENT, old.kind());
    assertEquals(State.PROOF_PENDING, old.state());
    assertEquals(2, old.runtimeMarkerFiles());
    assertEquals(8, old.runtimeMarkerBytes());
    assertEquals(37, old.logicalBytes());
    assertEquals("0.2.1-rc.5", old.identity().dshVersion());
    assertEquals(Kind.REBUILD_BACKUP, result.copies().get(1).kind());
    assertEquals(7, result.copies().get(1).logicalBytes());
  }

  @Test
  public void allocatedBytesDeduplicateInodesAndDoNotReadPayloads() throws IOException {
    Path files = folder.newFolder().toPath();
    put(files, RUNTIME + "/previous/linked-a", "short");
    put(files, RUNTIME + "/previous/linked-b", "short");
    put(files, ENVIRONMENT + "/previous-linux/linked-c", "short");
    put(files, ENVIRONMENT + "/previous-linux/sparse", "longer sample");
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            if (!node.type.equals("FILE")) return node;
            boolean linked = file.getName().startsWith("linked-");
            return new Node(
                node.type,
                linked ? "inode-shared" : "inode-sparse",
                node.size,
                node.modified,
                node.device,
                node.mode,
                linked ? 4096 : 0,
                linked ? 3 : 1);
          }

          @Override
          public InputStream read(File file, Node expected) {
            throw new AssertionError("Payload bytes read: " + file);
          }
        };
    var result = inspect(fs, files, 1_000);
    assertTrue(result.allocationComplete());
    assertEquals(1, result.multiLinkedFiles());
    assertEquals(4096, result.distinctAllocatedBytes());
    assertEquals(10, result.copies().get(0).logicalBytes());
    assertEquals(4096, result.copies().get(0).allocatedBytes());
    assertEquals(18, result.copies().get(1).logicalBytes());
    assertEquals(4096, result.copies().get(1).allocatedBytes());
  }

  @Test
  public void duplicateOperationAndBoundsRemainVisibleAsPartial() throws IOException {
    Path files = folder.newFolder().toPath();
    var fs = new JvmBackupFileSystem();
    put(files, RUNTIME + "/previous/old", "historical");
    put(files, PrivateRoots.RUNTIMES + "/" + ID + "/previous/old", "live journal");
    put(files, PrivateRoots.RUNTIMES + "/not-a-journal/file", "unrecognized path");
    var all = inspect(fs, files, 1_000);
    assertTrue(all.incomplete());
    assertEquals(2, all.copies().size());
    for (var copy : all.copies()) assertEquals(State.UNRECOGNIZED, copy.state());

    var limited = inspect(fs, files, 10);
    assertTrue(limited.incomplete());
    assertEquals(10, limited.entriesScanned());
    var cancelled =
        RollbackStorageInventory.inspect(
            fs, files.toFile(), fs.stat(files.toFile()).device, () -> true, 1_000);
    assertTrue(cancelled.incomplete());
    assertEquals(0, cancelled.entriesScanned());
  }

  @Test
  public void laterCopyRemainsSelectableWhenEarlierTreeExhaustsDefaultScan() throws IOException {
    Path files = folder.newFolder().toPath();
    String first = RUNTIME + "/previous";
    String last =
        PrivateRoots.RUNTIMES + "/completed/cd18fd92-c453-459c-b65e-f619e57e054f/previous";
    for (int i = 0; i < 100; i++) put(files, first + "/file-" + i, "x");
    put(files, last + "/item", "separately measurable");
    var fs = new JvmBackupFileSystem();
    long device = fs.stat(files.toFile()).device;
    var progress = new ArrayList<RollbackStorageInventory.Report>();
    var limited =
        RollbackStorageInventory.inspect(
            fs, files.toFile(), device, () -> false, 65, progress::add);
    assertEquals(2, progress.get(0).copies().size());
    for (var copy : progress.get(0).copies()) assertEquals(-1, copy.logicalBytes());
    assertTrue(progress.size() > 1);
    assertTrue(limited.incomplete());
    assertTrue(limited.copies().get(0).logicalBytes() > 0);
    var trailing = limited.copies().get(1);
    assertEquals(last, trailing.relativePath());
    assertEquals(-1, trailing.logicalBytes());
    assertEquals(-1, trailing.allocatedBytes());
    assertTrue(trailing.incomplete());

    var measured =
        RollbackStorageInventory.inspectCopy(fs, files.toFile(), device, () -> false, last);
    assertEquals(last, measured.relativePath());
    assertEquals(21, measured.logicalBytes());
    assertFalse(measured.incomplete());
    assertThrows(
        IOException.class,
        () ->
            RollbackStorageInventory.inspectCopy(
                fs, files.toFile(), device, () -> false, last + "/item"));
    assertThrows(
        IOException.class,
        () ->
            RollbackStorageInventory.inspectCopy(
                fs, files.toFile(), device, () -> false, "linux/ubuntu/root"));
  }

  private static RollbackStorageInventory.Report inspect(
      JvmBackupFileSystem fs, Path root, int limit) throws IOException {
    return RollbackStorageInventory.inspect(
        fs, root.toFile(), fs.stat(root.toFile()).device, () -> false, limit);
  }

  private static RuntimeDescriptor descriptor(String dsh) throws IOException {
    Map<String, Object> fields =
        new LinkedHashMap<>(
            Map.of(
                "version",
                1L,
                "baseVersion",
                "10",
                "dshVersion",
                dsh,
                "launcherContract",
                "DSHA_ARM64_V2",
                "bridgeProtocol",
                3L,
                "dataRead",
                List.of("dsh-" + dsh),
                "dataWrite",
                "dsh-" + dsh,
                "inputs",
                Map.of()));
    fields.put("runtimeId", RuntimeDescriptor.calculateId(fields));
    return new RuntimeDescriptor(fields);
  }

  private static Map<String, Object> health(RuntimeDescriptor descriptor) {
    Map<String, Object> proof =
        new LinkedHashMap<>(
            Map.of(
                "runtimeId",
                descriptor.id(),
                "nonce",
                "a".repeat(32),
                "port",
                3091L,
                "confirmedAt",
                1L));
    for (String field :
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
            "processExited")) proof.put(field, true);
    return proof;
  }

  private static void put(Path root, String relative, String content) throws IOException {
    Path target = root.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
  }

  private static byte[] json(Path root, String relative, Map<String, Object> value)
      throws IOException {
    byte[] bytes = BackupJson.write(value, BackupLimits.MANIFEST);
    Path target = root.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.write(target, bytes);
    return bytes;
  }

  private static void mark(Path root, String relative, String name) throws IOException {
    put(root, relative + "/" + name, ID + "\n" + name + "\n");
  }

  private static String sha(byte[] data) {
    return BackupArchive.hex(BackupArchive.sha().digest(data));
  }
}
