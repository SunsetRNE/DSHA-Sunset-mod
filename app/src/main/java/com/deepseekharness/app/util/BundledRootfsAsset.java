package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.StrictGzipInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.tukaani.xz.SingleXZInputStream;
import org.tukaani.xz.XZ;

/** The current signed APK has one rootfs asset; historical backups have separate readers. */
public final class BundledRootfsAsset {
  public static final String NAME = "offline-rootfs.bin";
  public static final String SHA256 = "offline-rootfs.sha256";
  public static final String RECOVERY_NAME = "recovery-rootfs.bin";

  private static final int XZ_MEMORY_KIB = 8192;
  private static final long TAR_LIMIT = 512L * 1024 * 1024;

  private BundledRootfsAsset() {}

  public static ZipEntry find(ZipFile apk) {
    ZipEntry entry = apk.getEntry("assets/" + NAME);
    return entry != null && !entry.isDirectory() ? entry : null;
  }

  public static String readSha256(InputStream metadata) throws IOException {
    byte[] bytes = new byte[64];
    new DataInputStream(metadata).readFully(bytes);
    if (metadata.read() != '\n' || metadata.read() != -1)
      throw new IOException("ROOTFS_DIGEST_METADATA");
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  /** Returns a bounded tar stream. Its EOF validates the codec and the signed compressed digest. */
  public static InputStream decode(InputStream source, String expectedSha256) throws IOException {
    return decode(source, expectedSha256, TAR_LIMIT);
  }

  static InputStream decode(InputStream source, String expectedSha256, long tarLimit)
      throws IOException {
    if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}"))
      throw new IOException("ROOTFS_DIGEST_METADATA");
    byte[] expected = new byte[32];
    for (int i = 0; i < expected.length; i++)
      expected[i] = (byte) Integer.parseInt(expectedSha256.substring(i * 2, i * 2 + 2), 16);
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IOException(error);
    }
    byte[] magic = new byte[XZ.HEADER_MAGIC.length];
    var compressed = new PushbackInputStream(new DigestInputStream(source, digest), magic.length);
    new DataInputStream(compressed).readFully(magic);
    compressed.unread(magic);
    boolean xz = Arrays.equals(magic, XZ.HEADER_MAGIC);
    InputStream decoded;
    if (xz) {
      var decoder = new SingleXZInputStream(compressed, XZ_MEMORY_KIB, true);
      if (decoder.getCheckType() != XZ.CHECK_CRC64) {
        decoder.close();
        throw new IOException("ROOTFS_XZ_CHECK");
      }
      decoded = decoder;
    } else if (magic[0] == 31 && (magic[1] & 255) == 139) {
      decoded = new StrictGzipInputStream(compressed);
    } else {
      throw new IOException("ROOTFS_ARCHIVE_FORMAT");
    }
    return new InputStream() {
      private final byte[] single = new byte[1];
      private long expanded;
      private boolean finished;

      @Override
      public int read() throws IOException {
        return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
      }

      @Override
      public int read(byte[] bytes, int at, int length) throws IOException {
        if (length == 0) return 0;
        if (finished) return -1;
        int count = decoded.read(bytes, at, length);
        if (count < 0) {
          if (xz && compressed.read() != -1) throw new IOException("ROOTFS_XZ_TRAILING_DATA");
          if (!MessageDigest.isEqual(digest.digest(), expected))
            throw new IOException("ROOTFS_ARCHIVE_SHA256");
          finished = true;
        } else {
          if (count > tarLimit - expanded) throw new IOException("ROOTFS_TAR_LIMIT");
          expanded += count;
        }
        return count;
      }

      @Override
      public void close() throws IOException {
        decoded.close();
      }
    };
  }
}
