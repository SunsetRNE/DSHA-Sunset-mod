package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.RustArchiveCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standard tar/gzip transport around the unchanged, independently verified v5 container. */
public final class PortableBackupEnvelope {
  public static final String PAYLOAD = "portable.dshbak",
      README = "README.txt",
      DIAGNOSTICS = "diagnostics.txt";
  private static final long CONTAINER_LIMIT = BackupLimits.BYTES + 256L * 1024 * 1024;
  private static final byte[] NOTE =
      ("DSHA application backup\n\n"
              + "Import this tar.gz file in DSHA's Backup and restore page.\n"
              + "portable.dshbak contains the original verified backup and its actual data scope.\n"
              + "If password protection was enabled, the original password is required.\n"
              + "Do not modify the payload. Keep your password separately.\n")
          .getBytes(StandardCharsets.UTF_8);

  private PortableBackupEnvelope() {}

  public static void writeUnencrypted(
      BackupFileSystem fs,
      File payload,
      String expectedSha,
      OutputStream target,
      BackupControl control)
      throws IOException {
    writeUnencrypted(fs, payload, expectedSha, target, control, null);
  }

  /**
   * 救援导出附带的有界诊断正文；普通导出传 {@code null}。该成员不参与恢复，读取时只接受有界文件。
   */
  public static void writeUnencrypted(
      BackupFileSystem fs,
      File payload,
      String expectedSha,
      OutputStream target,
      BackupControl control,
      byte[] diagnostics)
      throws IOException {
    control.check();
    try (InputStream input = fs.read(payload, fs.stat(payload))) {
      byte[] header = new byte[8];
      new DataInputStream(input).readFully(header);
      if (!BackupArchive.hasMagic(header)) throw new IOException("PASSWORD_PROTECTED_EXPORT");
    }
    byte[] note =
        ("DSHA application backup\n\nImport this tar.gz file in DSHA's Backup and restore page.\n"
                + "This backup does not require a password.\nportable.dshbak contains verified data and its actual scope.\n"
                + "Do not modify the payload. Keep the backup in a trusted location.\n")
            .getBytes(StandardCharsets.UTF_8);
    write(fs, payload, expectedSha, target, control, note, diagnostics);
  }

  public static void write(
      BackupFileSystem fs,
      File payload,
      String expectedSha,
      OutputStream target,
      BackupControl control)
      throws IOException {
    write(fs, payload, expectedSha, target, control, NOTE, null);
  }

  private static void write(
      BackupFileSystem fs,
      File payload,
      String expectedSha,
      OutputStream target,
      BackupControl control,
      byte[] note,
      byte[] diagnostics)
      throws IOException {
    control.check();
    BackupFileSystem.Node node = fs.stat(payload);
    if (!node.type.equals("FILE") || node.size < 8 || node.size > CONTAINER_LIMIT)
      throw new IOException("ENVELOPE_PAYLOAD_SIZE");
    if (diagnostics != null && diagnostics.length > 64 * 1024)
      throw new IOException("ENVELOPE_DIAGNOSTICS_SIZE");
    java.security.MessageDigest digest = BackupArchive.sha();
    OutputGate gate = new OutputGate(target, control);
    // 整个 tar 的长度才是 gzip 明文预算。超 ABI 的既有输入留 JVM，不截断、不重放。
    OutputStream encoded =
        RustArchiveCodec.packStream(
            gate, tarBytes(node.size, note.length, diagnostics == null ? 0 : diagnostics.length));
    // 工厂只借用目标；构造成功后接管关闭，失败时调用方仍持有目标。
    try (target;
        OutputStream gzip = encoded) {
      try {
        gzip.write(header(PAYLOAD, node.size));
        long copied = 0;
        try (InputStream input = fs.read(payload, node)) {
          byte[] buffer = new byte[65536];
          while (true) {
            control.check();
            int n = input.read(buffer);
            control.check();
            if (n == -1) break;
            copied = BackupLimits.add(copied, n, node.size);
            gzip.write(buffer, 0, n);
            digest.update(buffer, 0, n);
            control.report("PACKAGING", 0, copied);
          }
        }
        if (copied != node.size || !BackupArchive.hex(digest.digest()).equals(expectedSha))
          throw new IOException("VERIFIED_COPY_CHANGED");
        padding(gzip, node.size);
        gzip.write(header(README, note.length));
        gzip.write(note);
        padding(gzip, note.length);
        if (diagnostics != null && diagnostics.length > 0) {
          gzip.write(header(DIAGNOSTICS, diagnostics.length));
          gzip.write(diagnostics);
          padding(gzip, diagnostics.length);
        }
        gzip.write(new byte[1024]);
        control.check();
      } catch (IOException | RuntimeException | Error failure) {
        // close 可能调用 finish；先封写门，清理不得再向原目标追加 codec 字节。
        gate.failed = true;
        throw failure;
      }
    }
    control.check();
  }

  /** Probe names only to route legacy input; accepted envelopes always consume and verify all bytes. */
  public static boolean unwrapIfPresent(
      BackupFileSystem fs, File input, File output, BackupControl control) throws IOException {
    final boolean[] recognized = {false},
        payload = {false},
        readme = {false},
        diagnostics = {false};
    control.check();
    BackupFileSystem.Node inputNode = fs.stat(input);
    try (InputStream raw = fs.read(input, inputNode)) {
      LegacyTarReader.readWithLimit(
          raw,
          (member, data) -> {
            if (!recognized[0] && !member.path.equals(PAYLOAD)) throw new NotEnvelope();
            recognized[0] = true;
            if (!member.type.equals("FILE") || !member.target.isEmpty())
              throw new IOException("ENVELOPE_MEMBER_TYPE");
            if (member.path.equals(PAYLOAD)) {
              if (payload[0] || member.size < 8 || member.size > CONTAINER_LIMIT)
                throw new IOException("ENVELOPE_PAYLOAD_SIZE");
              payload[0] = true;
              byte[] magic = new byte[8];
              new DataInputStream(data).readFully(magic);
              if (!Arrays.equals(magic, PortableBackupCrypto.MAGIC)
                  && !BackupArchive.hasMagic(magic))
                throw new IOException("ENVELOPE_PAYLOAD_FORMAT");
              try (OutputStream out = fs.create(output)) {
                out.write(magic);
                byte[] buffer = new byte[65536];
                int n;
                while ((n = data.read(buffer)) != -1) {
                  control.check();
                  out.write(buffer, 0, n);
                }
              }
            } else if (member.path.equals(README)) {
              if (readme[0] || member.size > 16384) throw new IOException("ENVELOPE_README");
              readme[0] = true;
            } else if (member.path.equals(DIAGNOSTICS)) {
              if (diagnostics[0] || member.size > 64 * 1024)
                throw new IOException("ENVELOPE_DIAGNOSTICS");
              diagnostics[0] = true;
            } else throw new IOException("ENVELOPE_EXTRA_MEMBER");
          },
          control,
          CONTAINER_LIMIT,
          inputNode.size);
    } catch (NotEnvelope ordinaryLegacy) {
      return false;
    }
    control.check();
    if (!recognized[0]) return false;
    if (!payload[0]) throw new IOException("ENVELOPE_PAYLOAD_MISSING");
    if (!readme[0]) throw new IOException("ENVELOPE_README_MISSING");
    return true;
  }

  /** UI routing only: complete authentication/CRC/path checks still run in restore preflight. */
  public static boolean passwordProtected(InputStream source, BackupControl control)
      throws IOException {
    try (PushbackInputStream raw = new PushbackInputStream(source, 8)) {
      control.check();
      byte[] magic = new byte[8];
      new DataInputStream(raw).readFully(magic);
      raw.unread(magic);
      if (Arrays.equals(magic, PortableBackupCrypto.MAGIC)) return true;
      if (BackupArchive.hasMagic(magic)) return false;
      try {
        LegacyTarReader.readWithLimit(
            raw,
            (member, data) -> {
              if (!member.path.equals(PAYLOAD)) throw new Protection(false);
              if (!member.type.equals("FILE") || member.size < 8)
                throw new IOException("ENVELOPE_PAYLOAD_FORMAT");
              byte[] inner = new byte[8];
              new DataInputStream(data).readFully(inner);
              if (Arrays.equals(inner, PortableBackupCrypto.MAGIC)) throw new Protection(true);
              if (BackupArchive.hasMagic(inner)) throw new Protection(false);
              throw new IOException("ENVELOPE_PAYLOAD_FORMAT");
            },
            control,
            CONTAINER_LIMIT);
      } catch (Protection found) {
        return found.password;
      }
      return false;
    }
  }

  /** 完整 gzip 明文预算，包含所有 tar 框架字节；不得用成员载荷预算代替。 */
  static long tarBytes(long payloadBytes, int noteBytes, int diagnosticsBytes) throws IOException {
    try {
      long total =
          Math.addExact(1024L, Math.addExact(memberBytes(payloadBytes), memberBytes(noteBytes)));
      return diagnosticsBytes == 0 ? total : Math.addExact(total, memberBytes(diagnosticsBytes));
    } catch (ArithmeticException error) {
      throw new IOException("ENVELOPE_SIZE", error);
    }
  }

  private static long memberBytes(long size) throws IOException {
    if (size < 0) throw new IOException("ENVELOPE_SIZE");
    return Math.addExact(Math.addExact(512L, size), (512 - size % 512) % 512);
  }

  /** 编码器只借用目标；失败后的 finish/close 不得再交付新的压缩字节。 */
  private static final class OutputGate extends OutputStream {
    final OutputStream target;
    final BackupControl control;
    boolean failed;

    OutputGate(OutputStream target, BackupControl control) {
      this.target = target;
      this.control = control;
    }

    private void check() throws IOException {
      if (failed) throw new IOException("ENVELOPE_OUTPUT_FAILED");
      control.check();
    }

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      try {
        check();
        target.write(bytes, offset, length);
      } catch (IOException | RuntimeException | Error failure) {
        failed = true;
        throw failure;
      }
    }

    @Override
    public void flush() throws IOException {
      try {
        check();
        target.flush();
      } catch (IOException | RuntimeException | Error failure) {
        failed = true;
        throw failure;
      }
    }
  }

  private static final class NotEnvelope extends IOException {}

  private static final class Protection extends IOException {
    final boolean password;

    Protection(boolean password) {
      this.password = password;
    }
  }

  private static void padding(OutputStream out, long size) throws IOException {
    out.write(new byte[(int) ((512 - size % 512) % 512)]);
  }

  private static byte[] header(String name, long size) throws IOException {
    byte[] header = new byte[512];
    byte[] path = name.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(path, 0, header, 0, path.length);
    number(header, 100, 8, 0600);
    number(header, 108, 8, 0);
    number(header, 116, 8, 0);
    number(header, 124, 12, size);
    number(header, 136, 12, 0);
    Arrays.fill(header, 148, 156, (byte) ' ');
    header[156] = '0';
    byte[] magic = "ustar\00000".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, header, 257, magic.length);
    long checksum = 0;
    for (byte value : header) checksum += value & 255;
    number(header, 148, 8, checksum);
    return header;
  }

  private static void number(byte[] header, int offset, int length, long value) throws IOException {
    String octal = Long.toOctalString(value);
    if (octal.length() < length) {
      Arrays.fill(header, offset, offset + length - 1, (byte) '0');
      byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(bytes, 0, header, offset + length - 1 - bytes.length, bytes.length);
    } else {
      for (int index = offset + length - 1; index > offset; index--) {
        header[index] = (byte) value;
        value >>>= 8;
      }
      if (value != 0) throw new IOException("ENVELOPE_SIZE");
      header[offset] = (byte) 0x80;
    }
  }
}
