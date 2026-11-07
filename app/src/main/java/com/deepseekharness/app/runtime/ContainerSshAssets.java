package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.util.ContainerSshPayloadPolicy;
import com.deepseekharness.app.util.ContainerSshPolicy;
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
  /** 容器内密钥与配置根；脚本侧的同一常量见 {@code ContainerSshPolicy.HOME}。 */
  public static final String HOME = ContainerSshPolicy.HOME;

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

  /** 绑定密钥目录与 payload；返回宿主侧密钥目录（sshd.pid 也写在这里）。 */
  static synchronized File bind(Context context, LaunchSpec.Builder launch) throws IOException {
    var fs = new AndroidBackupFileSystem();
    var home =
        WebSessionStorage.bind(
            fs, new File(context.getApplicationInfo().dataDir), context.getNoBackupFilesDir());
    File directory = fs.child(home.directory, "container-ssh");
    File keys = keysDirectory(fs, directory);
    launch.bind(keys.getAbsolutePath(), HOME);
    bindPayloads(context, fs, directory, launch);
    home.verify(fs);
    return keys;
  }

  /**
   * 密钥目录（sshd.pid 也写在这里），供启动前的孤儿对账使用。
   *
   * <p>对账必须在创建容器进程之前做：{@code bind} 需要 LaunchSpec，而 spec 由
   * {@code execRootfsWithSpec} 在启动过程中创建，所以在 customizer 里对账会变成递归启动。
   */
  static File keysDirectory(Context context) throws IOException {
    var fs = new AndroidBackupFileSystem();
    var home =
        WebSessionStorage.bind(
            fs, new File(context.getApplicationInfo().dataDir), context.getNoBackupFilesDir());
    return keysDirectory(fs, fs.child(home.directory, "container-ssh"));
  }

  private static File keysDirectory(AndroidBackupFileSystem fs, File directory) throws IOException {
    if (fs.stat(directory).type.equals("MISSING")) fs.directory(directory);
    if (!fs.stat(directory).type.equals("DIRECTORY"))
      throw new IOException("SSH_PRIVATE_DIRECTORY");
    fs.mode(directory, 0700);
    File keys = fs.child(directory, "keys");
    if (fs.stat(keys).type.equals("MISSING")) fs.directory(keys);
    if (!fs.stat(keys).type.equals("DIRECTORY")) throw new IOException("SSH_PRIVATE_KEYS");
    fs.mode(keys, 0700);
    return keys;
  }

  /** 完整读取并校验随包清单：schema、目标架构；缺失或不匹配都视为不支持。 */
  private static JSONObject readManifest(Context context) throws IOException {
    JSONObject manifest;
    try {
      manifest = new JSONObject(new String(manifestBytes(context), StandardCharsets.UTF_8));
    } catch (org.json.JSONException invalid) {
      throw new IOException("SSH_MANIFEST_INVALID", invalid);
    }
    try {
      ContainerSshPayloadPolicy.validateManifest(manifest, isX86());
    } catch (IllegalArgumentException mismatch) {
      throw new IOException(mismatch.getMessage());
    }
    return manifest;
  }

  private static byte[] manifestBytes(Context context) throws IOException {
    byte[] metadata;
    try (InputStream input = context.getAssets().open(ASSET_PREFIX + "manifest.json")) {
      metadata = bounded(input, ContainerSshPayloadPolicy.MANIFEST_LIMIT);
    }
    if (metadata.length > ContainerSshPayloadPolicy.MANIFEST_LIMIT)
      throw new IOException("SSH_MANIFEST_LIMIT");
    return metadata;
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
      boolean x86 = isX86();
      Map<String, String> destinations = new LinkedHashMap<>();
      destinations.put("sshd", "/usr/sbin/sshd");
      destinations.put("ssh-keygen", "/usr/bin/ssh-keygen");
      destinations.put(
          "lib/libwrap.so.0",
          "/usr/lib/" + (x86 ? "x86_64" : "aarch64") + "-linux-gnu/libwrap.so.0");
      var files = manifest.getJSONArray("files");
      ContainerSshPayloadPolicy.validateMemberCount(files, destinations.size());
      java.util.Set<String> seen = new java.util.HashSet<>();
      for (int i = 0; i < files.length(); i++) {
        JSONObject row = files.getJSONObject(i);
        String guest = ContainerSshPayloadPolicy.validateRow(row, destinations);
        if (!seen.add(row.getString("target"))) throw new IOException("SSH_ASSET_CONTRACT");
        String asset = row.getString("asset"), relative = row.getString("target");
        String expected = row.getString("sha256");
        int size = row.getInt("bytes");
        byte[] bytes;
        try (InputStream input = context.getAssets().open(ASSET_PREFIX + asset)) {
          bytes = bounded(input, size);
        }
        if (bytes.length != size || !BackupArchive.hex(digest.digest(bytes)).equals(expected))
          throw new IOException("SSH_ASSET_BYTES");
        ContainerSshPayloadPolicy.validateElf(bytes, x86);
        File file = fs.child(target, asset);
        if (!fs.stat(file).type.equals("FILE")
            || !java.util.Arrays.equals(fs.small(file, size), bytes))
          fs.atomic(target, asset, bytes);
        fs.mode(file, 0700);
        launch.bind(file.getAbsolutePath(), guest);
      }
    } catch (org.json.JSONException invalid) {
      throw new IOException("SSH_MANIFEST_INVALID", invalid);
    } catch (IllegalArgumentException rejected) {
      throw new IOException(rejected.getMessage());
    }
  }

  private static boolean isX86() {
    return ContainerSshPayloadPolicy.isX86(android.os.Build.SUPPORTED_ABIS);
  }
}
