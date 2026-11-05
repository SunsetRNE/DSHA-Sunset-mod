package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.util.WebSessionStorage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    try (InputStream input = context.getAssets().open("container-ssh/manifest.json")) {
      return input.read() == '{';
    } catch (IOException unavailable) {
      return false;
    }
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
      byte[] metadata;
      try (InputStream input = context.getAssets().open("container-ssh/manifest.json")) {
        metadata = bounded(input, 16384);
      }
      if (metadata.length > 16384) throw new IOException("SSH_MANIFEST_LIMIT");
      JSONObject manifest =
          new JSONObject(new String(metadata, java.nio.charset.StandardCharsets.UTF_8));
      String architecture = BuildConfig.EMULATOR_X64 ? "x86_64" : "aarch64";
      if (manifest.getInt("schema") != 1
          || !manifest.getString("architecture").equals(architecture))
        throw new IOException("SSH_ASSET_ARCHITECTURE");
      var digest = BackupArchive.sha();
      String identity = BackupArchive.hex(digest.digest(metadata));
      File target = fs.child(parent, identity);
      if (fs.stat(target).type.equals("MISSING")) fs.directory(target);
      if (!fs.stat(target).type.equals("DIRECTORY")) throw new IOException("SSH_ASSET_DIRECTORY");
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
            || (bytes[18] & 255) != (BuildConfig.EMULATOR_X64 ? 62 : 183)
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
}
