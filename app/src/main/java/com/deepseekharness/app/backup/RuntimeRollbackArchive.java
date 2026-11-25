package com.deepseekharness.app.backup;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Durable transaction staging for a single previously sealed runtime tree. */
final class RuntimeRollbackArchive {
  // One RFC 1952 member. Big endian fields: magic, then the root record and its descendants.
  // Each record is kind:u8, name:utf8 (u16 length). DIR adds mode:u16, children:i32 and its
  // strictly sorted records. FILE adds mode:u16, size:i64, then the exact bytes. LINK adds
  // the original target:utf8 (u16 length). The only empty name is the root directory.
  private static final long MAGIC = 0x4453485242413031L; // DSHRBA01
  private static final int DIRECTORY = 1, FILE = 2, LINK = 3, BUFFER = 65536;
  private static final int MAX_TEXT_BYTES = 8192;
  private static final long MAX_ARCHIVE_BYTES = BackupLimits.BYTES + 256L * 1024 * 1024;

  private enum DecodeMode {
    VERIFY,
    RESTORE,
    REMAINDER,
    PARTIAL_RESTORE
  }

  record Metadata(
      String archiveSha256,
      long archiveBytes,
      long entries,
      long logicalBytes,
      String treeDigest) {}

  private RuntimeRollbackArchive() {}

  static Metadata pack(
      BackupFileSystem fs,
      File sealedRoot,
      File archive,
      String expectedTreeDigest,
      BackupControl control)
      throws IOException {
    control.check();
    if (!validHash(expectedTreeDigest)) throw new IOException("ROLLBACK_ARCHIVE_METADATA");
    var root = fs.stat(sealedRoot);
    if (!root.type.equals("DIRECTORY")) throw new IOException("ROLLBACK_ARCHIVE_ROOT");
    requireStaging(fs, sealedRoot, archive, root.device, true);
    var encoder = new Encoder(fs, control, root.device);
    MessageDigest digest = BackupArchive.sha();
    long archiveBytes;
    try (OutputStream owner = fs.create(archive)) {
      var counted = new BoundedOutput(owner);
      try (var gzip = new GZIPOutputStream(new DigestOutputStream(counted, digest), BUFFER)) {
        var output = new DataOutputStream(gzip);
        output.writeLong(MAGIC);
        encoder.write(output, sealedRoot, "", "", root);
        encoder.requireTree(expectedTreeDigest);
        control.check();
      }
      archiveBytes = counted.bytes;
    }
    var written = fs.stat(archive);
    if (!written.type.equals("FILE")
        || written.device != root.device
        || written.size != archiveBytes) throw new IOException("ROLLBACK_ARCHIVE_CHANGED");
    fs.syncDirectory(archive.getParentFile());
    control.check();
    return new Metadata(
        BackupArchive.hex(digest.digest()),
        archiveBytes,
        encoder.entries,
        encoder.bytes,
        expectedTreeDigest);
  }

  static void checkArchive(
      BackupFileSystem fs, File archive, Metadata expected, BackupControl control)
      throws IOException {
    decode(fs, archive, null, expected, control, DecodeMode.VERIFY);
  }

  /** Retry fencing: every still-present member must be an unmodified archived member. */
  static void checkRemaining(
      BackupFileSystem fs,
      File archive,
      File remainingRoot,
      Metadata expected,
      BackupControl control)
      throws IOException {
    decode(fs, archive, remainingRoot, expected, control, DecodeMode.REMAINDER);
  }

  /** Only for scratch whose directory identity the owning operation already verified. */
  static void checkPartialRestore(
      BackupFileSystem fs, File archive, File scratch, Metadata expected, BackupControl control)
      throws IOException {
    decode(fs, archive, scratch, expected, control, DecodeMode.PARTIAL_RESTORE);
  }

  static void unpack(
      BackupFileSystem fs, File archive, File newRoot, Metadata expected, BackupControl control)
      throws IOException {
    decode(fs, archive, newRoot, expected, control, DecodeMode.RESTORE);
    String restored =
        BackupTree.digest(
            fs,
            newRoot,
            control,
            (files, bytes) -> control.report("ROLLBACK_ARCHIVE_RESTORE_DIGEST", files, bytes));
    if (!expected.treeDigest.equals(restored))
      throw new IOException("ROLLBACK_ARCHIVE_RESTORE_DIGEST");
    fs.syncDirectory(newRoot.getParentFile());
    control.check();
  }

  private static void decode(
      BackupFileSystem fs,
      File archive,
      File target,
      Metadata expected,
      BackupControl control,
      DecodeMode mode)
      throws IOException {
    control.check();
    validate(expected);
    BackupFileSystem.Node sealed = fs.stat(archive);
    if (!sealed.type.equals("FILE") || sealed.size != expected.archiveBytes)
      throw new IOException("ROLLBACK_ARCHIVE_SIZE");
    if (target != null)
      requireStaging(fs, archive, target, sealed.device, mode == DecodeMode.RESTORE);
    MessageDigest digest = BackupArchive.sha();
    var decoder = new Decoder(fs, control, expected, sealed.device, mode);
    try (InputStream owner = fs.read(archive, sealed)) {
      // Closing StrictGzipInputStream releases its inflater; owner verifies the source at close.
      var borrowed =
          new FilterInputStream(owner) {
            @Override
            public void close() {}
          };
      try (var input =
          new DataInputStream(new StrictGzipInputStream(new DigestInputStream(borrowed, digest)))) {
        if (input.readLong() != MAGIC) throw new IOException("ROLLBACK_ARCHIVE_FORMAT");
        decoder.read(input, target, "", null, 0, null);
        if (input.read() != -1) throw new IOException("ROLLBACK_ARCHIVE_TRAILING_DATA");
      }
    }
    if (decoder.entries != expected.entries || decoder.bytes != expected.logicalBytes)
      throw new IOException("ROLLBACK_ARCHIVE_COUNTS");
    if (!expected.archiveSha256.equals(BackupArchive.hex(digest.digest())))
      throw new IOException("ROLLBACK_ARCHIVE_SHA256");
    decoder.requireTree(expected.treeDigest);
    control.check();
  }

  private static boolean validHash(String hash) {
    return hash != null && hash.matches("[a-f0-9]{64}");
  }

  private static void validate(Metadata expected) throws IOException {
    if (expected == null
        || !validHash(expected.archiveSha256)
        || !validHash(expected.treeDigest)
        || expected.archiveBytes < 20
        || expected.archiveBytes > MAX_ARCHIVE_BYTES
        || expected.entries < 1
        || expected.entries > BackupLimits.ENTRIES
        || expected.logicalBytes < 0
        || expected.logicalBytes > BackupLimits.BYTES)
      throw new IOException("ROLLBACK_ARCHIVE_METADATA");
  }

  private static void requireStaging(
      BackupFileSystem fs, File source, File target, long device, boolean mustBeAbsent)
      throws IOException {
    var from = source.toPath().toAbsolutePath().normalize();
    var to = target.toPath().toAbsolutePath().normalize();
    File parent = target.getParentFile();
    if (from.startsWith(to) || to.startsWith(from) || parent == null)
      throw new IOException("ROLLBACK_ARCHIVE_OVERLAP");
    var anchor = fs.stat(parent);
    if (!anchor.type.equals("DIRECTORY") || anchor.device != device)
      throw new IOException("ROLLBACK_ARCHIVE_STAGING");
    if (mustBeAbsent && !fs.stat(target).type.equals("MISSING"))
      throw new IOException("ROLLBACK_ARCHIVE_TARGET_EXISTS");
  }

  private static String path(String parent, String leaf) throws IOException {
    if (leaf.isEmpty() || leaf.indexOf('/') >= 0) throw new IOException("ROLLBACK_ARCHIVE_PATH");
    BackupLimits.path(leaf);
    return parent.isEmpty() ? leaf : BackupLimits.path(parent + "/" + leaf);
  }

  private static final class BoundedOutput extends FilterOutputStream {
    long bytes;

    BoundedOutput(OutputStream owner) {
      super(owner);
    }

    @Override
    public void write(int value) throws IOException {
      bytes = BackupLimits.add(bytes, 1, MAX_ARCHIVE_BYTES);
      out.write(value);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      bytes = BackupLimits.add(bytes, length, MAX_ARCHIVE_BYTES);
      out.write(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
      flush(); // The file owner closes once, after the gzip footer has been written.
    }
  }

  private abstract static class Codec {
    final BackupFileSystem fs;
    final BackupControl control;
    final BackupTree.TreeHash tree = new BackupTree.TreeHash();
    final MessageDigest file = BackupArchive.sha();
    final byte[] buffer = new byte[BUFFER];
    final Map<String, String> links = new HashMap<>();
    final String stage;
    long entries, bytes, metadataBytes;

    Codec(BackupFileSystem fs, BackupControl control, String stage) {
      this.fs = fs;
      this.control = control;
      this.stage = stage;
    }

    void observe() throws IOException {
      control.check();
      entries = BackupLimits.add(entries, 1, BackupLimits.ENTRIES);
      metadataBytes = BackupLimits.add(metadataBytes, 17, BackupLimits.METADATA);
      control.report(stage, entries, bytes);
    }

    byte[] copy(InputStream input, OutputStream output, long size, long limit) throws IOException {
      return copy(input, output, size, limit, null, 0);
    }

    byte[] copy(
        InputStream input,
        OutputStream output,
        long size,
        long limit,
        MessageDigest prefix,
        long prefixBytes)
        throws IOException {
      BackupLimits.add(bytes, size, limit);
      file.reset();
      long left = size;
      while (left > 0) {
        control.check();
        int count = input.read(buffer, 0, (int) Math.min(left, buffer.length));
        if (count <= 0) throw new EOFException("ROLLBACK_ARCHIVE_FILE_TRUNCATED");
        if (output != null) output.write(buffer, 0, count);
        file.update(buffer, 0, count);
        if (prefixBytes > 0) {
          int part = (int) Math.min(prefixBytes, count);
          prefix.update(buffer, 0, part);
          prefixBytes -= part;
        }
        bytes += count;
        left -= count;
        control.report(stage, entries, bytes);
      }
      byte[] hash = file.digest();
      tree.file(hash);
      return hash;
    }

    void writeText(DataOutputStream output, String value) throws IOException {
      ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
      int size = encoded.remaining();
      if (size > MAX_TEXT_BYTES) throw new IOException("ROLLBACK_ARCHIVE_TEXT");
      metadataBytes = BackupLimits.add(metadataBytes, size, BackupLimits.METADATA);
      output.writeShort(size);
      encoded.get(buffer, 0, size);
      output.write(buffer, 0, size);
    }

    String readText(DataInputStream input) throws IOException {
      int size = input.readUnsignedShort();
      if (size > MAX_TEXT_BYTES) throw new IOException("ROLLBACK_ARCHIVE_TEXT");
      metadataBytes = BackupLimits.add(metadataBytes, size, BackupLimits.METADATA);
      byte[] encoded = new byte[size];
      input.readFully(encoded);
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded)).toString();
    }

    void link(String relative, String target) throws IOException {
      if (target.isEmpty()
          || target.startsWith("/")
          || target.indexOf('\\') >= 0
          || target.indexOf('\0') >= 0
          || target.indexOf('\n') >= 0
          || target.indexOf('\r') >= 0
          || target.matches("^[A-Za-z]:.*")) throw new IOException("ROLLBACK_ARCHIVE_LINK");
      links.put(relative, target);
      tree.text(target);
    }

    void requireTree(String expected) throws IOException {
      for (Map.Entry<String, String> link : links.entrySet()) {
        control.check();
        verifyLink(link.getKey(), link.getValue());
      }
      if (!expected.equals(BackupArchive.hex(tree.finish())))
        throw new IOException("ROLLBACK_ARCHIVE_TREE_DIGEST");
    }

    private void verifyLink(String name, String target) throws IOException {
      // Resolve only in the archived namespace, never in the host filesystem. Preserve a
      // dangling target within the root, and reject escapes hidden behind an intermediate link.
      var prefix = new ArrayDeque<String>();
      int slash = name.lastIndexOf('/');
      if (slash >= 0) prefix.addAll(List.of(name.substring(0, slash).split("/")));
      var work = new ArrayDeque<>(List.of(target.split("/", -1)));
      int followed = 0;
      while (!work.isEmpty()) {
        control.check();
        String part = work.removeFirst();
        if (part.isEmpty() || part.equals(".")) continue;
        if (part.equals("..")) {
          if (prefix.pollLast() == null) throw new IOException("ROLLBACK_ARCHIVE_LINK_ESCAPE");
          continue;
        }
        prefix.addLast(part);
        if (prefix.size() > BackupLimits.DEPTH)
          throw new IOException("ROLLBACK_ARCHIVE_LINK_DEPTH");
        String nested = links.get(String.join("/", prefix));
        if (nested == null) continue;
        if (++followed > BackupLimits.LINKS) throw new IOException("ROLLBACK_ARCHIVE_LINK_CYCLE");
        prefix.removeLast();
        String[] parts = nested.split("/", -1);
        for (int at = parts.length - 1; at >= 0; at--) work.addFirst(parts[at]);
      }
    }
  }

  private static final class Encoder extends Codec {
    final long device;

    Encoder(BackupFileSystem fs, BackupControl control, long device) {
      super(fs, control, "ROLLBACK_ARCHIVING");
      this.device = device;
    }

    void write(
        DataOutputStream output,
        File source,
        String relative,
        String name,
        BackupFileSystem.Node node)
        throws IOException {
      observe();
      if (node.device != device) throw new IOException("STORAGE_VOLUME_BOUNDARY");
      tree.node(relative, node);
      switch (node.type) {
        case "DIRECTORY" -> {
          output.writeByte(DIRECTORY);
          writeText(output, name);
          output.writeShort(mode(node.mode));
          var names = new ArrayList<>(fs.list(source));
          names.sort(String::compareTo);
          BackupLimits.add(entries, names.size(), BackupLimits.ENTRIES);
          output.writeInt(names.size());
          String previous = null;
          for (String child : names) {
            control.check();
            if (previous != null && previous.compareTo(child) >= 0)
              throw new IOException("ROLLBACK_ARCHIVE_ORDER");
            previous = child;
            String at = path(relative, child);
            File entry = new File(source, child);
            write(output, entry, at, child, fs.stat(entry));
          }
        }
        case "FILE" -> {
          output.writeByte(FILE);
          writeText(output, name);
          output.writeShort(mode(node.mode));
          output.writeLong(node.size);
          try (InputStream input = fs.read(source, node)) {
            copy(input, output, node.size, BackupLimits.BYTES);
            if (input.read() != -1) throw new IOException("ROLLBACK_ARCHIVE_FILE_GREW");
          }
        }
        case "LINK" -> {
          output.writeByte(LINK);
          writeText(output, name);
          String target = fs.readLink(source);
          link(relative, target);
          writeText(output, target);
        }
        default -> throw new IOException("ROLLBACK_ARCHIVE_ENTRY_TYPE");
      }
      if (!node.same(fs.stat(source))) throw new IOException("SOURCE_CHANGED");
    }
  }

  private static int mode(int mode) throws IOException {
    if (mode < 0 || mode > 0777) throw new IOException("ROLLBACK_ARCHIVE_MODE");
    return mode;
  }

  private static final class Decoder extends Codec {
    final Metadata expected;
    final long device;
    final DecodeMode mode;

    Decoder(
        BackupFileSystem fs,
        BackupControl control,
        Metadata expected,
        long device,
        DecodeMode mode) {
      super(
          fs,
          control,
          mode == DecodeMode.RESTORE ? "ROLLBACK_ARCHIVE_RESTORING" : "ROLLBACK_ARCHIVE_VERIFYING");
      this.expected = expected;
      this.device = device;
      this.mode = mode;
    }

    String read(
        DataInputStream input,
        File parent,
        String relative,
        String previous,
        int depth,
        HashSet<String> priorMembers)
        throws IOException {
      control.check();
      if (entries >= expected.entries || depth > BackupLimits.DEPTH)
        throw new IOException("ROLLBACK_ARCHIVE_COUNTS");
      int kind = input.readUnsignedByte();
      String name = readText(input);
      if (depth == 0) {
        if (kind != DIRECTORY || !name.isEmpty()) throw new IOException("ROLLBACK_ARCHIVE_ROOT");
      } else if (previous != null && previous.compareTo(name) >= 0)
        throw new IOException("ROLLBACK_ARCHIVE_ORDER");
      String actual = depth == 0 ? "" : path(relative, name);
      observe();
      File target = parent == null || depth == 0 ? parent : new File(parent, name);
      boolean partial = mode == DecodeMode.PARTIAL_RESTORE;
      boolean compare = mode == DecodeMode.REMAINDER || partial;
      var remaining = compare ? remainingNode(target) : null;
      if (priorMembers != null && priorMembers.remove(name) != (remaining != null))
        throw remainderChanged();
      boolean restore = mode == DecodeMode.RESTORE;
      switch (kind) {
        case DIRECTORY -> {
          int mode = mode(input.readUnsignedShort());
          int children = input.readInt();
          if (children < 0 || children > expected.entries - entries)
            throw new IOException("ROLLBACK_ARCHIVE_COUNTS");
          tree.node(actual, "DIRECTORY", mode);
          if (restore) fs.directory(target);
          List<String> existing = null;
          HashSet<String> unmatched = null;
          if (remaining != null) {
            if (!remaining.type.equals("DIRECTORY")
                || (remaining.mode != mode
                    && remaining.mode != (mode | 0700)
                    && !(partial && remaining.mode == 0700))) throw remainderChanged();
            existing = fs.list(target);
            if (existing.size() > children) throw remainderChanged();
            unmatched = new HashSet<>(existing);
          }
          String prior = null;
          File into = restore || remaining != null ? target : null;
          for (int index = 0; index < children; index++)
            prior = read(input, into, actual, prior, depth + 1, unmatched);
          if (unmatched != null && (!unmatched.isEmpty() || !existing.equals(fs.list(target))))
            throw remainderChanged();
          if (restore) fs.mode(target, mode);
        }
        case FILE -> {
          int mode = mode(input.readUnsignedShort());
          long size = input.readLong();
          BackupLimits.add(bytes, size, expected.logicalBytes);
          tree.node(actual, "FILE", mode);
          MessageDigest prefix = null;
          long present = size;
          if (remaining != null) {
            present = remaining.size;
            if (!remaining.type.equals("FILE")
                || present < 0
                || present > size
                || (!partial && present != size)
                || (remaining.mode != mode && !(partial && remaining.mode == 0600)))
              throw remainderChanged();
            if (present < size) prefix = BackupArchive.sha();
          }
          byte[] hash;
          try (OutputStream output = restore ? fs.create(target) : null) {
            hash =
                copy(
                    input,
                    output,
                    size,
                    expected.logicalBytes,
                    prefix,
                    prefix == null ? 0 : present);
          }
          if (remaining != null)
            compareRemainingFile(
                target, remaining, present, prefix == null ? hash : prefix.digest());
          if (restore) fs.mode(target, mode);
        }
        case LINK -> {
          tree.node(actual, "LINK", 0);
          String value = readText(input);
          link(actual, value);
          if (remaining != null
              && (!remaining.type.equals("LINK") || !value.equals(fs.readLink(target))))
            throw remainderChanged();
          if (restore) fs.symlink(value, target);
        }
        default -> throw new IOException("ROLLBACK_ARCHIVE_ENTRY_TYPE");
      }
      if (compare && target != null) {
        var current = remainingNode(target);
        if (remaining == null ? current != null : !remaining.same(current))
          throw remainderChanged();
      }
      return name;
    }

    private BackupFileSystem.Node remainingNode(File target) throws IOException {
      if (target == null) return null;
      var node = fs.stat(target);
      if (node.type.equals("MISSING")) return null;
      if (node.device != device) throw remainderChanged();
      return node;
    }

    private void compareRemainingFile(
        File target, BackupFileSystem.Node node, long size, byte[] archived) throws IOException {
      file.reset();
      try (InputStream input = fs.read(target, node)) {
        long left = size;
        while (left > 0) {
          control.check();
          int count = input.read(buffer, 0, (int) Math.min(left, buffer.length));
          if (count <= 0) throw remainderChanged();
          left -= count;
          file.update(buffer, 0, count);
        }
        if (input.read() != -1) throw remainderChanged();
      }
      if (!MessageDigest.isEqual(archived, file.digest())) throw remainderChanged();
    }

    private static IOException remainderChanged() {
      return new IOException("ROLLBACK_ARCHIVE_REMAINDER_CHANGED");
    }
  }
}
