package com.deepseekharness.app.util;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.*;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZOutputStream;
import static org.junit.Assert.*;

public class BundledRootfsAssetTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private File apk(String... names) throws Exception {
    File file = temporary.newFile();
    try (var out = new ZipOutputStream(new FileOutputStream(file))) {
      for (String name : names) {
        out.putNextEntry(new ZipEntry(name));
        if (!name.endsWith("/")) out.write(new byte[1024]);
        out.closeEntry();
      }
    }
    return file;
  }

  @Test
  public void onlySignedCurrentPathCanBeSelected() throws Exception {
    try (var zip =
        new ZipFile(
            apk(
                "root/offline_rootfs-large.bin",
                "offline-rootfs.tar.gz",
                "assets/offline-rootfs.bin"))) {
      assertEquals("assets/offline-rootfs.bin", BundledRootfsAsset.find(zip).getName());
    }
    try (var zip = new ZipFile(apk("offline-rootfs.bin", "assets/offline-rootfs.tar.gz"))) {
      assertNull(BundledRootfsAsset.find(zip));
    }
    try (var zip = new ZipFile(apk("assets/offline-rootfs.bin/"))) {
      assertNull(BundledRootfsAsset.find(zip));
    }
  }

  private static byte[] xz(byte[] payload, int check, int dictionary) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var options = new LZMA2Options(3);
    options.setDictSize(dictionary);
    try (var compressed = new XZOutputStream(bytes, options, check)) {
      compressed.write(payload);
    }
    return bytes.toByteArray();
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static byte[] verify(byte[] encoded, String sha256, long limit) throws IOException {
    try (var data = BundledRootfsAsset.decode(new ByteArrayInputStream(encoded), sha256, limit)) {
      return data.readAllBytes();
    }
  }

  @Test
  public void oldGzipAndXZReturnTheSameBoundedBytes() throws Exception {
    byte[] tar = "known tar bytes".getBytes(StandardCharsets.UTF_8);
    var gzip = new ByteArrayOutputStream();
    try (var compressed = new GZIPOutputStream(gzip)) {
      compressed.write(tar);
    }
    byte[] old = gzip.toByteArray();
    byte[] current = xz(tar, XZ.CHECK_CRC64, 1024 * 1024);
    assertArrayEquals(tar, verify(old, sha256(old), tar.length));
    assertArrayEquals(tar, verify(current, sha256(current), tar.length));
    assertEquals(
        "ROOTFS_TAR_LIMIT",
        assertThrows(IOException.class, () -> verify(current, sha256(current), tar.length - 1))
            .getMessage());
    assertEquals(
        "ROOTFS_ARCHIVE_SHA256",
        assertThrows(IOException.class, () -> verify(current, sha256(old), tar.length))
            .getMessage());
  }

  @Test
  public void corruptTruncatedOrConcatenatedXZFailsEvenWithMatchingDigest() throws Exception {
    byte[] valid = xz(new byte[4096], XZ.CHECK_CRC64, 1024 * 1024);
    byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
    byte[] corrupt = valid.clone();
    corrupt[valid.length / 2] ^= 1;
    byte[] concatenated = Arrays.copyOf(valid, valid.length * 2);
    System.arraycopy(valid, 0, concatenated, valid.length, valid.length);
    for (byte[] invalid : new byte[][] {truncated, corrupt})
      assertThrows(IOException.class, () -> verify(invalid, sha256(invalid), 4096));
    assertEquals(
        "ROOTFS_XZ_TRAILING_DATA",
        assertThrows(IOException.class, () -> verify(concatenated, sha256(concatenated), 8192))
            .getMessage());
  }

  @Test
  public void xzRequiresCRC64AndBoundsDecoderDictionary() throws Exception {
    byte[] noCheck = xz(new byte[512], XZ.CHECK_NONE, 1024 * 1024);
    assertEquals(
        "ROOTFS_XZ_CHECK",
        assertThrows(IOException.class, () -> verify(noCheck, sha256(noCheck), 512)).getMessage());
    byte[] largeDictionary = xz(new byte[512], XZ.CHECK_CRC64, 16 * 1024 * 1024);
    assertThrows(
        org.tukaani.xz.MemoryLimitException.class,
        () -> verify(largeDictionary, sha256(largeDictionary), 512));
  }
}
