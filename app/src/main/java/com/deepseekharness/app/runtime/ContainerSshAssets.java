package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.util.WebSessionStorage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** SSH 按需资产与本机主机密钥独立于可重建 rootfs，私有路径逐级拒绝链接。 */
public final class ContainerSshAssets {
  static final String HOME = "/run/dsha-container-ssh";
  private static final String ASSET_PREFIX = "container-ssh/";

  private ContainerSshAssets() {}

  public static boolean supported(Context context) {
    if (context == null) return false;
    try {
      readManifest(context);
      return true;
    } catch (IOException unavailable) {
      return false;
    }
  }

  /** 完整读取并校验随包清单：schema、目标架构；缺失或不匹配都视为不支持。 */
  private static JSONObject readManifest(Context context) throws IOException {
    JSONObject manifest;
    try {
      manifest = new JSONObject(new String(manifestBytes(context), StandardCharsets.UTF_8));
    } catch (org.json.JSONException invalid) {
      throw new IOException("SSH_MANIFEST_INVALID", invalid);
    }
    String architecture = x86() ? "x86_64" : "aarch64";
    if (manifest.optInt("schema") != 1 || !architecture.equals(manifest.optString("architecture")))
      throw new IOException("SSH_ASSET_ARCHITECTURE");
    return manifest;
  }

  private static byte[] manifestBytes(Context context) throws IOException {
    byte[] metadata;
    try (InputStream input = context.getAssets().open(ASSET_PREFIX + "manifest.json")) {
      metadata = bounded(input, 16384);
    }
    if (metadata.length > 16384) throw new IOException("SSH_MANIFEST_LIMIT");
    return metadata;
  }

  static synchronized void bind(Context context, LaunchSpec.Builder launch) throws IOException {
    var fs = new AndroidBackupFileSystem();
    var home =
        WebSessionStorage.bind(
            fs, new File(context.getApplicationInfo().dataDir), context.getNoBackupFilesDir());
    File directory = fs.child(home.directory, "container-ssh");
    if (fs.stat(directory).type.equals("MISSING")) fs.directory(directory);
    if (!fs.stat(directory).type.equals("DIRECTORY"))
      throw new IOException("SSH_PRIVATE_DIRECTORY");
    fs.mode(directory, 0700);
    File keys = fs.child(directory, "keys");
    if (fs.stat(keys).type.equals("MISSING")) fs.directory(keys);
    if (!fs.stat(keys).type.equals("DIRECTORY")) throw new IOException("SSH_PRIVATE_KEYS");
    fs.mode(keys, 0700);
    launch.bind(keys.getAbsolutePath(), HOME);
    bindPayloads(context, fs, directory, launch);
    home.verify(fs);
  }

  private static byte[] bounded(InputStream input, int limit) throws IOException {
    var output = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (int count; (count = input.read(buffer)) != -1; ) {
      if (output.size() + count > limit) throw new IOException("SSH_ASSET_LIMIT");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static void bindPayloads(
      Context context, AndroidBackupFileSystem fs, File parent, LaunchSpec.Builder launch)
      throws IOException {
    try {
      JSONObject manifest = readManifest(context);
      var digest = BackupArchive.sha();
      String identity = BackupArchive.hex(digest.digest(manifestBytes(context)));
      File target = fs.child(parent, identity);
      if (fs.stat(target).type.equals("MISSING")) fs.directory(target);
      if (!fs.stat(target).type.equals("DIRECTORY")) throw new IOException("SSH_ASSET_DIRECTORY");
      String architecture = x86() ? "x86_64" : "aarch64";
      Map<String, String> destinations = new LinkedHashMap<>();
      destinations.put("sshd", "/usr/sbin/sshd");
      destinations.put("ssh-keygen", "/usr/bin/ssh-keygen");
      destinations.put("lib/libwrap.so.0", "/usr/lib/" + architecture + "-linux-gnu/libwrap.so.0");
      var files = manifest.getJSONArray("files");
      if (files.length() != destinations.size()) throw new IOException("SSH_ASSET_MEMBERS");
      java.util.Set<String> seen = new java.util.HashSet<>();
      for (int i = 0; i < files.length(); i++) {
        JSONObject row = files.getJSONObject(i);
        String asset = row.getString("asset"), relative = row.getString("target");
        String expected = row.getString("sha256");
        int size = row.getInt("bytes");
        if (!asset.matches("payload-[0-9]+\\.bin")
            || !destinations.containsKey(relative)
            || !seen.add(relative)
            || !expected.matches("[a-f0-9]{64}")
            || size < 20
            || size > 4 * 1024 * 1024) throw new IOException("SSH_ASSET_CONTRACT");
        byte[] bytes;
        try (InputStream input = context.getAssets().open(ASSET_PREFIX + asset)) {
          bytes = bounded(input, size);
        }
        if (bytes.length != size
            || !BackupArchive.hex(digest.digest(bytes)).equals(expected)
            || bytes[0] != 127
            || bytes[1] != 'E'
            || bytes[2] != 'L'
            || bytes[3] != 'F'
            || bytes[4] != 2 // ELFCLASS64
            || bytes[5] != 1 // 小端
            || (eType(bytes) != 2 && eType(bytes) != 3) // EXEC 或 DYN（PIE）
            || (bytes[18] & 255) != (x86() ? 62 : 183)
            || bytes[19] != 0) throw new IOException("SSH_ASSET_BYTES");
        File file = fs.child(target, asset);
        if (!fs.stat(file).type.equals("FILE")
            || !java.util.Arrays.equals(fs.small(file, size), bytes))
          fs.atomic(target, asset, bytes);
        fs.mode(file, 0700);
        launch.bind(file.getAbsolutePath(), destinations.get(relative));
      }
    } catch (org.json.JSONException invalid) {
      throw new IOException("SSH_MANIFEST_INVALID", invalid);
    }
  }

  /** ELF 头 e_type（小端 16 位，偏移 0x10）。 */
  private static int eType(byte[] elf) {
    return (elf[16] & 255) | ((elf[17] & 255) << 8);
  }

  /** 目标 ABI 是否是 x86 家族；模拟器调试包才可能为真。 */
  private static boolean x86() {
    String[] abis = android.os.Build.SUPPORTED_ABIS;
    return abis != null
        && abis.length > 0
        && abis[0] != null
        && abis[0].toLowerCase(java.util.Locale.ROOT).startsWith("x86");
  }
}
