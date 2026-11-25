package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.BackupTree;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.backup.LegacyTarReader;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ManagedRuntimeLayout;
import com.deepseekharness.app.util.ManagedRuntimePaths;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** APK-managed tree staging and health proof, separate from guest process execution. */
final class ManagedRuntimeAssets {
  interface AssetText {
    String read(String name);
  }

  interface LegacyArchive {
    ZipEntry find(ZipFile apk);
  }

  private final Context context;
  private final File rootfs;
  private final Supplier<String> environmentIdentity;
  private final AssetText assetText;
  private final LegacyArchive legacyArchive;
  private final RuntimeDescriptorPreparation preparation;

  ManagedRuntimeAssets(
      Context context,
      File rootfs,
      Supplier<String> environmentIdentity,
      AssetText assetText,
      LegacyArchive legacyArchive) {
    this(
        context,
        rootfs,
        environmentIdentity,
        assetText,
        legacyArchive,
        new RuntimeDescriptorPreparation(new RuntimePreparationInputs(context, rootfs)::observe));
  }

  ManagedRuntimeAssets(
      Context context,
      File rootfs,
      Supplier<String> environmentIdentity,
      AssetText assetText,
      LegacyArchive legacyArchive,
      RuntimeDescriptorPreparation preparation) {
    this.context = context;
    this.rootfs = rootfs;
    this.environmentIdentity = environmentIdentity;
    this.assetText = assetText;
    this.legacyArchive = legacyArchive;
    this.preparation = java.util.Objects.requireNonNull(preparation);
  }

  void invalidatePreparation() {
    preparation.invalidate();
  }

  RuntimeHostPorts.Scope openPreparation(
      long generation, java.util.function.BooleanSupplier current, Consumer<String> output) {
    return preparation.open(generation, current, output);
  }

  RuntimeDescriptor expectedDescriptor() throws IOException {
    return preparation.descriptor(
        RuntimeDescriptorPreparation.Kind.EXPECTED,
        () -> assetText.read("runtime-descriptor.json").getBytes(StandardCharsets.UTF_8));
  }

  RuntimeDescriptor installedDescriptor() throws IOException {
    try {
      AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
      File files = context.getFilesDir().getCanonicalFile();
      File linux = fs.child(files, "linux");
      if (fs.stat(linux).type.equals("MISSING")) {
        preparation.invalidate();
        return null;
      }
      File file = fs.child(linux, ".runtime-descriptor.json");
      if (fs.stat(file).type.equals("MISSING")) {
        preparation.invalidate();
        return null;
      }
      return preparation.descriptor(
          RuntimeDescriptorPreparation.Kind.INSTALLED, () -> fs.small(file, 2 * 1024 * 1024));
    } catch (IOException | RuntimeException unavailable) {
      preparation.invalidate();
      throw unavailable;
    }
  }

  Map<String, Object> health() throws IOException {
    try {
      RuntimeDescriptor descriptor = installedDescriptor();
      if (descriptor == null) return null;
      AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
      File home = new File(context.getFilesDir().getCanonicalFile(), "runtime-health");
      File file = new File(home, descriptor.id() + ".json");
      if (fs.stat(home).type.equals("MISSING") || fs.stat(file).type.equals("MISSING")) {
        preparation.invalidate();
        return null;
      }
      Map<String, Object> proof = BackupJson.read(fs.small(file, 512 * 1024), 512 * 1024);
      if (!RuntimeDescriptor.healthy(proof, descriptor.id())) preparation.invalidate();
      return proof;
    } catch (IOException | RuntimeException unavailable) {
      preparation.invalidate();
      throw unavailable;
    }
  }

  void confirmHealth(Map<String, Object> proof) throws IOException {
    preparation.invalidate();
    RuntimeDescriptor descriptor = installedDescriptor();
    if (descriptor == null || !RuntimeDescriptor.healthy(proof, descriptor.id()))
      throw new IOException("RUNTIME_HEALTH_INCOMPLETE");
    AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
    File files = context.getFilesDir().getCanonicalFile();
    File home = new File(files, "runtime-health");
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    Map<String, String> hashes = new LinkedHashMap<>();
    for (String path : installedManagedPaths())
      hashes.put(path, BackupTree.digest(fs, fs.child(files, path), new BackupControl(null)));
    proof.put("managedHashes", hashes);
    fs.atomic(home, descriptor.id() + ".json", BackupJson.write(proof, 512 * 1024));
  }

  private static final ManagedRuntimePaths.View ALIAS_VIEW =
      new ManagedRuntimePaths.View() {
        public File[] list(File directory) {
          return directory.listFiles();
        }

        public boolean symbolic(File file) {
          return Compat.isSymbolicLink(file);
        }

        public boolean exists(File file) {
          return file.exists();
        }

        public String canonical(File file) throws IOException {
          return file.getCanonicalPath();
        }
      };

  /** Only health confirmation walks the full managed tree; normal readiness reads its receipt. */
  private List<String> installedManagedPaths() throws IOException {
    return ManagedRuntimePaths.enumerate(rootfs, rootfs, ALIAS_VIEW);
  }

  List<String> stage(File stage, Consumer<String> progress, BackupControl control)
      throws IOException {
    preparation.invalidate();
    File root = new File(stage, "linux/ubuntu");
    if (!root.mkdirs()) throw new IOException(UiText.text("无法建立独立运行时暂存目录"));
    final String prefix = ManagedRuntimeLayout.DSH;
    progress.accept(UiText.text("正在解压新版 dsh（保留现有 Ubuntu 与个人目录）…"));
    try (ZipFile apk = new ZipFile(context.getPackageCodePath());
        var extraction = new AndroidTrustedAssetFileSystem(root)) {
      var rootIdentity = extraction.stat(root);
      boolean split = apk.getEntry("assets/offline-rootfs.layout") != null;
      ZipEntry bundle = split ? apk.getEntry("assets/dsh-runtime.bin") : legacyArchive.find(apk);
      if (bundle == null) throw new IOException(UiText.text("APK 没有内置运行时"));
      try {
        try (InputStream input = apk.getInputStream(bundle)) {
          TarGzipExtractor.extractSelected(
              input,
              root,
              0,
              name ->
                  name.equals(prefix)
                      || name.startsWith(prefix + "/")
                      || ManagedRuntimeLayout.alias(name)
                      || name.equals("usr/local/share/dsha/dsh-runtime.version"),
              extraction,
              control);
        }
        control.check();
        return finishStage(stage, root, progress);
      } catch (IOException | RuntimeException failure) {
        try {
          control.check();
          if (split && !extraction.writtenFiles().isEmpty()) {
            // 旧式合并包及跨进程暂存没有相同的逐文件出生证明，保留原件。
            Object inputs = expectedDescriptor().json().get("inputs");
            try (InputStream source = apk.getInputStream(bundle)) {
              if (!(inputs instanceof Map<?, ?> hashes)
                  || !BackupArchive.digest(source, control).equals(hashes.get("dsh-runtime.bin")))
                throw new IOException("RUNTIME_STAGE_ARCHIVE_CHANGED");
            }
            try (InputStream source = apk.getInputStream(bundle)) {
              reclaimStagedFiles(
                  new AndroidBackupFileSystem(),
                  root,
                  rootIdentity,
                  extraction.writtenFiles(),
                  source,
                  control);
            }
          }
        } catch (IOException | RuntimeException retained) {
          failure.addSuppressed(retained);
        }
        throw failure;
      }
    }
  }

  /** 本轮已创建 inode、内容与签名归档逐项一致才回收，未确认的路径保留。 */
  static void reclaimStagedFiles(
      BackupFileSystem fs,
      File root,
      BackupFileSystem.Node identity,
      Map<String, BackupFileSystem.Node> written,
      InputStream source,
      BackupControl control)
      throws IOException {
    byte[] buffer = new byte[65536];
    LegacyTarReader.readTrustedAssets(
        source,
        (member, body) -> {
          var owned = written.get(member.path);
          if (owned == null
              || !member.type.equals("FILE")
              || !owned.type.equals("FILE")
              || owned.size != member.size
              || owned.device != identity.device) return;
          var current = fs.stat(root);
          if (!current.type.equals("DIRECTORY")
              || current.device != identity.device
              || !current.key.equals(identity.key))
            throw new IOException("RUNTIME_STAGE_ROOT_CHANGED");
          File target = fs.child(root, member.path);
          var node = fs.stat(target);
          if (node.links != 1 || !owned.same(node)) return;
          String expected = BackupArchive.digest(body, control, buffer);
          try (InputStream existing = fs.read(target, owned)) {
            if (!expected.equals(BackupArchive.digest(existing, control, buffer))) return;
          }
          control.check();
          fs.deleteChecked(target, owned);
        },
        control);
  }

  private List<String> finishStage(File stage, File root, Consumer<String> progress)
      throws IOException {
    progress.accept(UiText.text("正在准备新版内置插件和界面适配…"));
    RuntimeTools.stage(context, root);
    RuntimeTools.prepareBuiltinDependencies(root);
    List<String> paths = ManagedRuntimePaths.enumerate(root, rootfs, ALIAS_VIEW);
    String identity = environmentIdentity.get();
    Map<ManagedRuntimePaths.Marker, String> values =
        Map.of(
            ManagedRuntimePaths.Marker.IDENTITY,
            identity,
            ManagedRuntimePaths.Marker.EXTRACTED,
            identity,
            ManagedRuntimePaths.Marker.VERSION,
            assetText.read("offline-rootfs.version").trim(),
            ManagedRuntimePaths.Marker.DESCRIPTOR,
            assetText.read("runtime-descriptor.json"));
    for (ManagedRuntimePaths.Marker marker : ManagedRuntimePaths.Marker.values())
      writeMarker(context, new File(stage, marker.relative), values.get(marker));
    return paths;
  }

  static void writeMarker(Context context, File target, String value) throws IOException {
    RuntimeAssetFiles.write(context, target, value.getBytes(StandardCharsets.UTF_8), false);
  }
}
