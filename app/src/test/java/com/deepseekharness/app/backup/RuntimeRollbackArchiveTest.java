package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class RuntimeRollbackArchiveTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private File path(String name) {
    return new File(temporary.getRoot(), name);
  }

  private File source() throws IOException {
    File root = temporary.newFolder("source");
    File directory = new File(root, "目录");
    fs.directory(directory);
    byte[] payload = new byte[131_073];
    new Random(373).nextBytes(payload);
    File executable = new File(directory, "tool");
    Files.write(executable.toPath(), payload);
    fs.mode(executable, 0751);
    fs.mode(directory, 0750);
    fs.mode(root, 0751);
    return root;
  }

  private static BackupControl control() {
    return new BackupControl(null);
  }

  private String digest(File root) throws IOException {
    return BackupTree.digest(fs, root, control());
  }

  private RuntimeRollbackArchive.Metadata pack(File source, File archive) throws IOException {
    return RuntimeRollbackArchive.pack(fs, source, archive, digest(source), control());
  }

  @Test
  public void roundTripPreservesRootAndLeafModesAndOldDigest() throws Exception {
    File source = source(), archive = path("compressed"), restored = path("restored");
    String before = digest(source);
    var metadata = pack(source, archive);
    assertEquals(before, metadata.treeDigest());
    assertEquals(3, metadata.entries());
    assertEquals(131_073, metadata.logicalBytes());
    assertEquals(Files.size(archive.toPath()), metadata.archiveBytes());
    try (var input = Files.newInputStream(archive.toPath())) {
      assertEquals(BackupArchive.digest(input, control()), metadata.archiveSha256());
    }
    RuntimeRollbackArchive.checkArchive(fs, archive, metadata, control());
    var materializedBytes = new ArrayList<Long>();
    RuntimeRollbackArchive.unpack(
        fs,
        archive,
        restored,
        metadata,
        new BackupControl(
            (stage, entries, bytes) -> {
              if (stage.equals("ROLLBACK_ARCHIVE_RESTORE_DIGEST")) materializedBytes.add(bytes);
            }));
    assertEquals(Long.valueOf(0), materializedBytes.get(0));
    assertEquals(
        Long.valueOf(metadata.logicalBytes()), materializedBytes.get(materializedBytes.size() - 1));
    assertEquals(before, digest(restored));
    assertEquals(before, digest(source));
    assertEquals(fs.stat(source).mode, fs.stat(restored).mode);
    assertEquals(
        fs.stat(new File(source, "目录/tool")).mode, fs.stat(new File(restored, "目录/tool")).mode);
    assertThrows(
        IOException.class,
        () -> RuntimeRollbackArchive.unpack(fs, archive, restored, metadata, control()));
    assertEquals(before, digest(restored));
  }

  private void link(String value, File target) throws IOException {
    try {
      fs.symlink(value, target);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
      assumeNoException(unavailable);
    }
  }

  @Test
  public void preservesOriginalRelativeLinksAndRejectsEscapesThroughIntermediateLink()
      throws Exception {
    File source = source(), archive = path("compressed"), restored = path("restored");
    assumeTrue(Files.getFileStore(source.toPath()).supportsFileAttributeView("posix"));
    link("./目录/tool", new File(source, "run"));
    link("../missing", new File(source, "目录/dangling"));
    var metadata = pack(source, archive);
    RuntimeRollbackArchive.unpack(fs, archive, restored, metadata, control());
    assertEquals("./目录/tool", fs.readLink(new File(restored, "run")));
    assertEquals("../missing", fs.readLink(new File(restored, "目录/dangling")));
    link("..", new File(source, "目录/up"));
    link("目录/up/../outside", new File(source, "escape"));
    String before = digest(source);
    IOException error =
        assertThrows(
            IOException.class,
            () -> RuntimeRollbackArchive.pack(fs, source, path("refused"), before, control()));
    assertEquals("ROLLBACK_ARCHIVE_LINK_ESCAPE", error.getMessage());
    assertEquals(before, digest(source));
  }

  private RuntimeRollbackArchive.Metadata store(
      File target, byte[] bytes, long entries, long logicalBytes, String tree) throws IOException {
    Files.write(target.toPath(), bytes);
    return new RuntimeRollbackArchive.Metadata(
        BackupArchive.hex(BackupArchive.sha().digest(bytes)),
        bytes.length,
        entries,
        logicalBytes,
        tree);
  }

  @Test
  public void rejectsBadCrcTruncationSecondMemberAndWrongCompressedHash() throws Exception {
    File source = source(), archive = path("compressed");
    var metadata = pack(source, archive);
    byte[] bytes = Files.readAllBytes(archive.toPath());
    byte[] badCrc = bytes.clone();
    badCrc[badCrc.length - 8] ^= 0x20;
    byte[] twoMembers = Arrays.copyOf(bytes, bytes.length * 2);
    System.arraycopy(bytes, 0, twoMembers, bytes.length, bytes.length);
    for (byte[] malformed :
        new byte[][] {badCrc, Arrays.copyOf(bytes, bytes.length - 5), twoMembers}) {
      File candidate = path("malformed");
      var proof =
          store(
              candidate,
              malformed,
              metadata.entries(),
              metadata.logicalBytes(),
              metadata.treeDigest());
      IOException error =
          assertThrows(
              IOException.class,
              () -> RuntimeRollbackArchive.checkArchive(fs, candidate, proof, control()));
      assertTrue(error.getMessage(), error.getMessage().startsWith("GZIP_"));
    }
    var wrong =
        new RuntimeRollbackArchive.Metadata(
            "0".repeat(64),
            metadata.archiveBytes(),
            metadata.entries(),
            metadata.logicalBytes(),
            metadata.treeDigest());
    assertEquals(
        "ROLLBACK_ARCHIVE_SHA256",
        assertThrows(
                IOException.class,
                () -> RuntimeRollbackArchive.checkArchive(fs, archive, wrong, control()))
            .getMessage());
  }

  private static void text(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeShort(bytes.length);
    output.write(bytes);
  }

  private byte[] malicious(String... names) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
      out.writeLong(0x4453485242413031L);
      out.writeByte(1);
      text(out, "");
      out.writeShort(0700);
      out.writeInt(names.length);
      for (String name : names) {
        out.writeByte(2);
        text(out, name);
        out.writeShort(0600);
        out.writeLong(0);
      }
    }
    return bytes.toByteArray();
  }

  @Test
  public void abortsBeforeEscapingStagingAndRejectsDuplicateAndExtraEntries() throws Exception {
    File archive = path("compressed"), sentinel = temporary.newFile("sentinel");
    Files.writeString(sentinel.toPath(), "preserve");
    String hash = "0".repeat(64);
    var escaping = store(archive, malicious("../sentinel"), 2, 0, hash);
    assertEquals(
        "ROLLBACK_ARCHIVE_PATH",
        assertThrows(
                IOException.class,
                () ->
                    RuntimeRollbackArchive.unpack(
                        fs, archive, path("partial"), escaping, control()))
            .getMessage());
    assertEquals("preserve", Files.readString(sentinel.toPath()));
    var duplicate = store(archive, malicious("x", "x"), 3, 0, hash);
    assertEquals(
        "ROLLBACK_ARCHIVE_ORDER",
        assertThrows(
                IOException.class,
                () -> RuntimeRollbackArchive.checkArchive(fs, archive, duplicate, control()))
            .getMessage());
    var extra = store(archive, malicious("x", "y"), 2, 0, hash);
    assertEquals(
        "ROLLBACK_ARCHIVE_COUNTS",
        assertThrows(
                IOException.class,
                () -> RuntimeRollbackArchive.checkArchive(fs, archive, extra, control()))
            .getMessage());
  }

  @Test
  public void cancellationAndWriteFailurePreserveVerifiedSource() throws Exception {
    File source = source(), archive = path("compressed");
    String before = digest(source);
    BackupControl cancelled = control();
    assertThrows(
        InterruptedIOException.class,
        () ->
            RuntimeRollbackArchive.pack(
                fs,
                source,
                path("cancelled"),
                before,
                cancelled.withProgress(
                    (stage, entries, bytes) -> {
                      if (bytes > 0) cancelled.cancel();
                    })));
    assertEquals(before, digest(source));
    BackupFileSystem full =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            return new FilterOutputStream(super.create(file)) {
              @Override
              public void write(byte[] bytes, int offset, int count) throws IOException {
                if (count > 16) throw new IOException("NO_SPACE");
                out.write(bytes, offset, count);
              }
            };
          }
        };
    assertEquals(
        "NO_SPACE",
        assertThrows(
                IOException.class,
                () ->
                    RuntimeRollbackArchive.pack(full, source, path("disk-full"), before, control()))
            .getMessage());
    assertEquals(before, digest(source));
    var proof = pack(source, archive);
    assertEquals(
        "NO_SPACE",
        assertThrows(
                IOException.class,
                () ->
                    RuntimeRollbackArchive.unpack(
                        full, archive, path("restore-full"), proof, control()))
            .getMessage());
    assertEquals(before, digest(source));
    RuntimeRollbackArchive.checkArchive(fs, archive, proof, control());
  }

  @Test
  public void retryPermitsOnlyVerifiedRemainderAndOwnerDirectoryModeRepair() throws Exception {
    File source = source(), archive = path("compressed");
    File first = new File(source, "a-deleted-before-interruption");
    Files.writeString(first.toPath(), "previous bytes");
    fs.mode(source, 0551);
    var proof = pack(source, archive);
    fs.mode(source, 0751);
    Files.delete(first.toPath());
    RuntimeRollbackArchive.checkRemaining(fs, archive, source, proof, control());

    File original = new File(source, "目录/tool");
    byte[] bytes = Files.readAllBytes(original.toPath());
    byte[] changed = bytes.clone();
    changed[0] ^= 0x20;
    Files.write(original.toPath(), changed);
    assertEquals(
        "ROLLBACK_ARCHIVE_REMAINDER_CHANGED",
        assertThrows(
                IOException.class,
                () -> RuntimeRollbackArchive.checkRemaining(fs, archive, source, proof, control()))
            .getMessage());
    assertArrayEquals(changed, Files.readAllBytes(original.toPath()));

    Files.write(original.toPath(), bytes);
    File alien = new File(source, "alien");
    Files.writeString(alien.toPath(), "unique bytes");
    assertEquals(
        "ROLLBACK_ARCHIVE_REMAINDER_CHANGED",
        assertThrows(
                IOException.class,
                () -> RuntimeRollbackArchive.checkRemaining(fs, archive, source, proof, control()))
            .getMessage());
    assertEquals("unique bytes", Files.readString(alien.toPath()));
    RuntimeRollbackArchive.checkArchive(fs, archive, proof, control());
  }

  @Test
  public void interruptedRestoreScratchAcceptsOnlyArchivedPrefixesAndInitialModes()
      throws Exception {
    File source = source(), archive = path("compressed"), scratch = path("owned-scratch");
    var proof = pack(source, archive);
    fs.directory(scratch);
    fs.mode(scratch, 0700);
    File directory = new File(scratch, "目录");
    fs.directory(directory);
    fs.mode(directory, 0700);
    byte[] prefix = Arrays.copyOf(Files.readAllBytes(new File(source, "目录/tool").toPath()), 65_537);
    File written = new File(directory, "tool");
    Files.write(written.toPath(), prefix);
    fs.mode(written, 0600);
    RuntimeRollbackArchive.checkPartialRestore(fs, archive, scratch, proof, control());
    assertThrows(
        IOException.class,
        () -> RuntimeRollbackArchive.checkRemaining(fs, archive, scratch, proof, control()));

    byte[] changed = prefix.clone();
    changed[changed.length - 1] ^= 0x20;
    Files.write(written.toPath(), changed);
    assertEquals(
        "ROLLBACK_ARCHIVE_REMAINDER_CHANGED",
        assertThrows(
                IOException.class,
                () ->
                    RuntimeRollbackArchive.checkPartialRestore(
                        fs, archive, scratch, proof, control()))
            .getMessage());
    assertArrayEquals(changed, Files.readAllBytes(written.toPath()));
    Files.write(written.toPath(), prefix);
    File alien = new File(directory, "unique");
    Files.writeString(alien.toPath(), "outside the archive");
    assertEquals(
        "ROLLBACK_ARCHIVE_REMAINDER_CHANGED",
        assertThrows(
                IOException.class,
                () ->
                    RuntimeRollbackArchive.checkPartialRestore(
                        fs, archive, scratch, proof, control()))
            .getMessage());
    assertEquals("outside the archive", Files.readString(alien.toPath()));
    RuntimeRollbackArchive.checkArchive(fs, archive, proof, control());
  }
}
