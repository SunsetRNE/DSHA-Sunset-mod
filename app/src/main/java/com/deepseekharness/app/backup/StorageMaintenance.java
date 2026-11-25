package com.deepseekharness.app.backup;

import android.content.Context;
import android.os.StatFs;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.util.FileKind;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.RegenerableCachePaths;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 应用私有存储的逻辑文件大小和同一数据卷的可用空间。 */
public final class StorageMaintenance {
  private StorageMaintenance() {}

  private static final List<String> CACHE =
      List.of("WebView", "GPUCache", "Code Cache", "GrShaderCache", "DawnCache");
  private static final Set<String> HISTORICAL =
      Set.of(
          "runtime-startup",
          "functional-audit",
          "adb-flow-audit",
          "install-audit",
          "layout-audit",
          "plugin-sort-audit",
          "log-panel-audit");
  private static final List<String> GUEST_CACHE = RegenerableCachePaths.rootfs();
  private static final List<String> WEBVIEW_CACHE =
      RegenerableCachePaths.webViewData().stream()
          .map(path -> path.substring("app_webview/".length()))
          .collect(Collectors.toList());
  private static final CandidatePath GUEST_CACHE_PATHS =
      CandidatePath.of(
          GUEST_CACHE.stream().map(path -> "ubuntu/" + path).collect(Collectors.toList()));
  private static final CandidatePath WEBVIEW_CACHE_PATHS = CandidatePath.of(WEBVIEW_CACHE);
  private static final Pattern OPERATION_ID = Pattern.compile(Ids.UUID_PATTERN);
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private static final class CandidatePath {
    final Map<String, CandidatePath> children = new HashMap<>();
    boolean target;

    static CandidatePath of(List<String> paths) {
      var root = new CandidatePath();
      for (String path : paths) {
        var node = root;
        for (String part : path.split("/"))
          node = node.children.computeIfAbsent(part, unused -> new CandidatePath());
        node.target = true;
      }
      return root;
    }

    CandidatePath child(String name) {
      return children.get(name);
    }
  }

  private record RetiredPayload(String name, String proof, String marker) {}

  private static final RetiredPayload RUNTIME_PREVIOUS =
      new RetiredPayload("previous", "archive-seal.json", "finalized");
  private static final RetiredPayload ENVIRONMENT_PREVIOUS =
      new RetiredPayload("previous-linux", "retired-proof.json", "committed");
  private static final Set<String> FILE_CLASSES =
      Set.of(
          "linux",
          "user-data-v5",
          PrivateRoots.RUNTIMES,
          PrivateRoots.ENVIRONMENTS,
          PrivateRoots.BACKUPS,
          "runtime-updates",
          "mozilla");

  public static final class Size {
    public long bytes;
    public long candidateBytes;

    /** 事务原有载荷的逻辑大小。清理资格由各事务重新核验。 */
    public long rollbackBytes;

    public int unreadable;
  }

  public enum Phase {
    SCANNING,
    VERIFYING,
    REMOVING,
    COMPRESSING,
    EXPANDING
  }

  public enum Outcome {
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELLED
  }

  /**
   * 扫描和删除计数在单次调用内累计。缓存目标总数按类别提供，已处理目标含缺失和受保护的路径。
   * 核验统计累计已读字节及读取完成的普通文件。压缩字节计数随阶段重置，未知分母为 -1。
   */
  public record Progress(
      Phase phase,
      String category,
      long visitedEntries,
      long knownBytes,
      long removedEntries,
      long removedBytes,
      int completedTargets,
      int totalTargets,
      long verifiedEntries,
      long verifiedBytes,
      String compressionStage,
      long compressionBytes,
      long compressionTotalBytes,
      Outcome outcome) {}

  /** 每个调用独立计数，同步提交首尾状态，过程按单调时钟节流。 */
  private static final class ProgressTracker {
    private static final long INTERVAL = TimeUnit.MILLISECONDS.toNanos(100);
    final BackupControl control;
    private final Consumer<Progress> observer;
    private Phase phase;
    private String category = "";
    private String compressionStage = "";
    private long visitedEntries, knownBytes, removedEntries, removedBytes, lastUpdate;
    private long verifiedEntries, verifiedBytes;
    private long compressionBytes, compressionTotalBytes = -1;
    private boolean expandedPayloadChanged;
    private int completedTargets, totalTargets = -1;

    ProgressTracker(Phase phase, Consumer<Progress> observer, BackupControl control) {
      this.phase = phase;
      this.observer = observer;
      this.control = control == null ? new BackupControl(null) : control;
      publish(Outcome.RUNNING, true);
    }

    void stage(Phase phase, String category, int targets) {
      if (this.phase == phase && this.category.equals(category) && totalTargets == targets) return;
      this.phase = phase;
      this.category = category;
      totalTargets = targets;
      completedTargets = 0;
      publish(Outcome.RUNNING, false);
    }

    void targetCompleted() {
      completedTargets++;
      publish(Outcome.RUNNING, false);
    }

    void visited(BackupFileSystem.Node node) {
      observed(1, node.kind == FileKind.FILE ? node.size : 0);
    }

    void observed(long entries, long bytes) {
      visitedEntries = Math.addExact(visitedEntries, entries);
      knownBytes = Math.addExact(knownBytes, bytes);
      publish(Outcome.RUNNING, false);
    }

    void removed(BackupFileSystem.Node node) {
      removedEntries++;
      if (node.kind == FileKind.FILE) removedBytes = Math.addExact(removedBytes, node.size);
      publish(Outcome.RUNNING, false);
    }

    void verified(long files, long bytes) {
      verifiedEntries = Math.addExact(verifiedEntries, files);
      verifiedBytes = Math.addExact(verifiedBytes, bytes);
      publish(Outcome.RUNNING, false);
    }

    BackupTree.DigestProgress verificationDigest() {
      if (observer == null) return null;
      return new BackupTree.DigestProgress() {
        long previousFiles, previousBytes;

        @Override
        public void update(long files, long bytes) {
          if (files != 0 || bytes != 0) verified(files - previousFiles, bytes - previousBytes);
          previousFiles = files;
          previousBytes = bytes;
        }
      };
    }

    void reportCompression(String stage, long bytes) {
      if (stage.equals("ROLLBACK_ARCHIVE_LOGICAL_SIZE")) {
        compressionTotalBytes = bytes;
      } else {
        if (stage.equals("ROLLBACK_ARCHIVING") && !compressionStage.equals(stage))
          compressionTotalBytes = -1;
        compressionStage = stage;
        compressionBytes = bytes;
      }
      publish(Outcome.RUNNING, false);
    }

    void removedExpanded(BackupFileSystem.Node node) {
      expandedPayloadChanged = true;
      if (node.kind == FileKind.FILE)
        reportCompression("ROLLBACK_ARCHIVE_REPLACING", Math.addExact(compressionBytes, node.size));
    }

    void archiveSourceRead(long bytes) {
      String stage = "ROLLBACK_ARCHIVE_SOURCE_DIGEST";
      long alreadyRead = compressionStage.equals(stage) ? compressionBytes : 0;
      reportCompression(stage, Math.addExact(alreadyRead, bytes));
    }

    BackupControl archiveControl() {
      return control.withProgress(
          (stage, entries, bytes) -> {
            switch (stage) {
              case "ROLLBACK_ARCHIVE_LOGICAL_SIZE",
                      "ROLLBACK_ARCHIVING",
                      "ROLLBACK_ARCHIVE_VERIFYING",
                      "ROLLBACK_ARCHIVE_RESTORING",
                      "ROLLBACK_ARCHIVE_RESTORE_DIGEST",
                      "ROLLBACK_ARCHIVE_PUBLISHING" ->
                  reportCompression(stage, bytes);
              case "RUNTIME_VERIFY_READ" -> archiveSourceRead(bytes);
              default -> {}
            }
          });
    }

    void finish(Outcome outcome) {
      publish(outcome, true);
    }

    void finish(String error) {
      finish(
          error.isEmpty()
              ? Outcome.SUCCESS
              : error.equals("CANCELLED")
                  ? Outcome.CANCELLED
                  : removedEntries > 0 || expandedPayloadChanged
                      ? Outcome.PARTIAL
                      : Outcome.FAILED);
    }

    void finish(CleanupResult result) {
      var compression = result.compression;
      finish(compression == null || compression.error.isEmpty() ? result.error : compression.error);
    }

    private void publish(Outcome outcome, boolean force) {
      if (observer == null) return;
      long now = System.nanoTime();
      if (!force && now - lastUpdate < INTERVAL) return;
      lastUpdate = now;
      try {
        observer.accept(
            new Progress(
                phase,
                category,
                visitedEntries,
                knownBytes,
                removedEntries,
                removedBytes,
                completedTargets,
                totalTargets,
                verifiedEntries,
                verifiedBytes,
                compressionStage,
                compressionBytes,
                compressionStage.equals("ROLLBACK_ARCHIVE_SOURCE_DIGEST")
                        || compressionStage.equals("ROLLBACK_ARCHIVE_RESTORE_DIGEST")
                        || compressionStage.equals("ROLLBACK_ARCHIVE_PUBLISHING")
                    ? -1
                    : compressionTotalBytes,
                outcome));
      } catch (RuntimeException ignored) {
        // 观察进度失败不改变清理回执或事务边界。
      }
    }
  }

  public static final class Inventory {
    public final Map<String, Size> sizes;

    /** 数据卷取样不可用时为 -1。 */
    public final long available;

    private Inventory(Map<String, Size> sizes, long available) {
      this.sizes = Collections.unmodifiableMap(sizes);
      this.available = available;
    }
  }

  public static final class CleanupResult {
    public final long availableBefore, availableAfter;
    public final Map<String, Long> removed;
    public final String error;
    public final CompressionResult compression;

    private CleanupResult(
        long before,
        long after,
        Map<String, Long> removed,
        String error,
        CompressionResult compression) {
      availableBefore = before;
      availableAfter = after;
      this.removed = Collections.unmodifiableMap(removed);
      this.error = error;
      this.compression = compression;
    }

    public long removedBytes() {
      return removed.values().stream().mapToLong(Long::longValue).sum();
    }
  }

  /** 数据卷的可用空间见清理回执。这里记录完成校验、压缩及替换的逻辑文件大小。 */
  public record CompressionResult(
      int copies,
      long expandedBytes,
      long archiveBytes,
      String outcome,
      String error,
      String reason) {
    public CompressionResult(
        int copies, long expandedBytes, long archiveBytes, String outcome, String error) {
      this(copies, expandedBytes, archiveBytes, outcome, error, "");
    }
  }

  /** Logical sizes for one restored historical root and same-volume free-space samples. */
  public record ExpansionResult(
      long availableBefore,
      long availableAfter,
      long expandedBytes,
      long archiveBytes,
      String outcome,
      String reason) {
    public ExpansionResult(
        long availableBefore,
        long availableAfter,
        long expandedBytes,
        long archiveBytes,
        String outcome) {
      this(availableBefore, availableAfter, expandedBytes, archiveBytes, outcome, "");
    }
  }

  @FunctionalInterface
  interface PayloadRemoval {
    void run(File root, String relative) throws IOException;
  }

  private static File files(Context context) throws IOException {
    return context.getFilesDir().getCanonicalFile();
  }

  private static long volume(BackupFileSystem fs, File files) throws IOException {
    var root = fs.stat(files);
    if (!root.type.equals("DIRECTORY")) throw new IOException("STORAGE_VOLUME_UNAVAILABLE");
    return root.device;
  }

  private static long available(BackupFileSystem fs, File files, long device) {
    try {
      if (volume(fs, files) == device) return new StatFs(files.getPath()).getAvailableBytes();
    } catch (IOException | IllegalArgumentException unavailable) {
      // 可用空间未知时仍显示已成功完成的目录统计。
    }
    return -1;
  }

  public static Inventory inspect(Context context) throws IOException {
    return inspect(context, null, null);
  }

  public static Inventory inspect(
      Context context, Consumer<Progress> observer, BackupControl control) throws IOException {
    var progress = new ProgressTracker(Phase.SCANNING, observer, control);
    try {
      progress.control.check();
      var fs = new AndroidBackupFileSystem();
      File files = files(context);
      long device = volume(fs, files);
      var sizes = scan(fs, files, new File(files.getParentFile(), "cache"), device, progress);
      long free = available(fs, files, device);
      progress.finish(scanOutcome(sizes));
      return new Inventory(sizes, free);
    } catch (IOException | RuntimeException failure) {
      progress.finish(
          failure instanceof InterruptedIOException ? Outcome.CANCELLED : Outcome.FAILED);
      throw failure;
    }
  }

  static Map<String, Size> inspect(BackupFileSystem fs, File files, File cache, long device)
      throws IOException {
    return inspect(fs, files, cache, device, null, null);
  }

  static Map<String, Size> inspect(
      BackupFileSystem fs,
      File files,
      File cache,
      long device,
      Consumer<Progress> observer,
      BackupControl control)
      throws IOException {
    var progress = new ProgressTracker(Phase.SCANNING, observer, control);
    try {
      var sizes = scan(fs, files, cache, device, progress);
      progress.finish(scanOutcome(sizes));
      return sizes;
    } catch (IOException | RuntimeException failure) {
      progress.finish(
          failure instanceof InterruptedIOException ? Outcome.CANCELLED : Outcome.FAILED);
      throw failure;
    }
  }

  private static Outcome scanOutcome(Map<String, Size> sizes) {
    return sizes.values().stream().anyMatch(value -> value.unreadable != 0)
        ? Outcome.PARTIAL
        : Outcome.SUCCESS;
  }

  private static Map<String, Size> scan(
      BackupFileSystem fs, File files, File cache, long device, ProgressTracker progress)
      throws IOException {
    Map<String, Size> sizes = new LinkedHashMap<>();
    for (String key :
        List.of(
            "linux",
            "user-data-v5",
            PrivateRoots.RUNTIMES,
            PrivateRoots.ENVIRONMENTS,
            PrivateRoots.BACKUPS,
            "runtime-updates",
            "mozilla",
            "webview",
            "cache",
            "historical-audit-fixtures",
            "other-private")) sizes.put(key, new Size());
    progress.control.check();
    File appRoot = files.getParentFile();
    try (var app =
        fs.getClass() == AndroidBackupFileSystem.class
            ? AndroidStorageDirectory.open(appRoot, fs.stat(appRoot))
            : null) {
      scanPartition(fs, files, app, device, false, sizes, progress);
      scanPartition(fs, cache, app, device, true, sizes, progress);
      progress.stage(Phase.SCANNING, "webview", -1);
      new InventoryTraversal(fs, device, sizes.get("webview"), null, progress)
          .count(new File(appRoot, "app_webview"), app, null, WEBVIEW_CACHE_PATHS, false, false, 0);
    } catch (InterruptedIOException interrupted) {
      throw interrupted;
    } catch (IOException unavailable) {
      sizes.values().forEach(size -> size.unreadable++);
    }
    progress.control.check();
    return sizes;
  }

  private static String filesClass(String name) {
    if (HISTORICAL.contains(name)) return "historical-audit-fixtures";
    return FILE_CLASSES.contains(name) ? name : "other-private";
  }

  private static void scanPartition(
      BackupFileSystem fs,
      File root,
      AndroidStorageDirectory parent,
      long device,
      boolean cache,
      Map<String, Size> sizes,
      ProgressTracker progress)
      throws IOException {
    progress.control.check();
    try {
      var node = observe(fs, root, parent);
      if (node.kind == FileKind.MISSING) return;
      if (node.kind != FileKind.DIRECTORY || node.device != device)
        throw new IOException("STORAGE_ROOT_UNAVAILABLE");
      try (var directory = parent == null ? null : parent.directory(root.getName(), node)) {
        for (String name : directory == null ? fs.list(root) : directory.list()) {
          String key = filesClass(name);
          if (cache && !key.equals("historical-audit-fixtures")) key = "cache";
          progress.stage(Phase.SCANNING, key, -1);
          RetiredPayload retired =
              switch (key) {
                case PrivateRoots.RUNTIMES -> RUNTIME_PREVIOUS;
                case PrivateRoots.ENVIRONMENTS -> ENVIRONMENT_PREVIOUS;
                default -> null;
              };
          new InventoryTraversal(fs, device, sizes.get(key), retired, progress)
              .count(
                  new File(root, name),
                  directory,
                  null,
                  key.equals("linux") ? GUEST_CACHE_PATHS : null,
                  cache && CACHE.contains(name),
                  false,
                  0);
        }
      }
    } catch (InterruptedIOException interrupted) {
      throw interrupted;
    } catch (IOException unreadable) {
      // Historical fixtures can come from either partition.
      sizes.get("historical-audit-fixtures").unreadable++;
      if (cache) {
        sizes.get("cache").unreadable++;
      } else {
        FILE_CLASSES.forEach(key -> sizes.get(key).unreadable++);
        sizes.get("other-private").unreadable++;
      }
    }
  }

  /** 只读预览可识别的事务。证明正文、健康度和保留数量仍由清理事务核验。 */
  private static boolean isOperation(File file, int depth) {
    return (depth == 1 || depth == 2 && file.getParentFile().getName().equals("completed"))
        && OPERATION_ID.matcher(file.getName()).matches();
  }

  private static BackupFileSystem.Node previewRecord(
      BackupFileSystem fs, File directory, AndroidStorageDirectory scoped, String leaf)
      throws InterruptedIOException {
    try {
      return scoped == null ? fs.stat(new File(directory, leaf)) : scoped.stat(leaf);
    } catch (InterruptedIOException interrupted) {
      throw interrupted;
    } catch (IOException unreadable) {
      // 正常扫描该子项时记录不可读状态。
      return null;
    }
  }

  private static boolean fileOnDevice(BackupFileSystem.Node node, long device) {
    return node != null && node.kind == FileKind.FILE && node.device == device;
  }

  private static BackupFileSystem.Node observe(
      BackupFileSystem fs, File file, AndroidStorageDirectory parent) throws IOException {
    return parent == null ? fs.stat(file) : parent.stat(file.getName());
  }

  private record InventoryTraversal(
      BackupFileSystem fs,
      long device,
      Size total,
      RetiredPayload retired,
      ProgressTracker progress) {
    void count(
        File file,
        AndroidStorageDirectory parent,
        BackupFileSystem.Node known,
        CandidatePath caches,
        boolean candidate,
        boolean rollback,
        int depth)
        throws IOException {
      progress.control.check();
      try {
        if (depth > BackupLimits.DEPTH) throw new IOException("STORAGE_DEPTH");
        var node = known == null ? observe(fs, file, parent) : known;
        if (node.kind == FileKind.MISSING) return;
        if (node.device != device) throw new IOException("STORAGE_VOLUME_BOUNDARY");
        candidate |= caches != null && caches.target;
        progress.visited(node);
        if (node.kind == FileKind.FILE) {
          total.bytes = Math.addExact(total.bytes, node.size);
          if (candidate) total.candidateBytes = Math.addExact(total.candidateBytes, node.size);
          if (rollback || coldArchive(file, depth))
            total.rollbackBytes = Math.addExact(total.rollbackBytes, node.size);
          return;
        }
        if (node.kind != FileKind.DIRECTORY) return;
        try (var directory = parent == null ? null : parent.directory(file.getName(), node)) {
          var names = directory == null ? fs.list(file) : directory.list();
          boolean operation =
              retired != null && isOperation(file, depth) && names.contains(retired.name);
          BackupFileSystem.Node marker = null, proof = null;
          if (operation && names.contains(retired.marker) && names.contains(retired.proof)) {
            marker = previewRecord(fs, file, directory, retired.marker);
            proof = previewRecord(fs, file, directory, retired.proof);
          }
          boolean marked = fileOnDevice(marker, device) && fileOnDevice(proof, device);
          for (String name : names) {
            boolean payload = operation && name.equals(retired.name);
            BackupFileSystem.Node observed =
                !operation
                    ? null
                    : name.equals(retired.marker)
                        ? marker
                        : name.equals(retired.proof) ? proof : null;
            count(
                new File(file, name),
                directory,
                observed,
                caches == null ? null : caches.child(name),
                candidate || payload && marked,
                rollback || payload,
                depth + 1);
          }
        }
      } catch (InterruptedIOException interrupted) {
        throw interrupted;
      } catch (IOException | ArithmeticException unavailable) {
        total.unreadable++;
      }
    }

    private boolean coldArchive(File file, int depth) {
      if (retired != RUNTIME_PREVIOUS
          || (depth != 3 && depth != 4)
          || !file.getName().equals("archive.bin")) return false;
      File cold = file.getParentFile();
      return cold.getName().equals("cold") && isOperation(cold.getParentFile(), depth - 2);
    }
  }

  private static void removeCache(
      BackupFileSystem fs,
      File root,
      String relative,
      long device,
      Consumer<BackupFileSystem.Node> removed,
      ProgressTracker progress)
      throws IOException {
    progress.control.check();
    if (!RegenerableCachePaths.safeRelative(relative))
      throw new IOException("CACHE_PATH_FORBIDDEN");
    File parent = root;
    for (String part : relative.split("/")) {
      var node = fs.stat(parent);
      if (node.kind != FileKind.DIRECTORY || node.device != device) return;
      parent = new File(parent, part);
    }
    removeTree(fs, parent, device, progress::visited, removed, progress.control, 0);
  }

  static void removeTree(
      BackupFileSystem fs, File file, long device, LongConsumer removed, int depth)
      throws IOException {
    removeTree(
        fs,
        file,
        device,
        ignored -> {},
        node -> {
          if (node.kind == FileKind.FILE) removed.accept(node.size);
        },
        null,
        depth);
  }

  private static void removeTree(
      BackupFileSystem fs,
      File file,
      long device,
      Consumer<BackupFileSystem.Node> visited,
      Consumer<BackupFileSystem.Node> removed,
      BackupControl control,
      int depth)
      throws IOException {
    if (fs.getClass() == AndroidBackupFileSystem.class) {
      ((AndroidBackupFileSystem) fs)
          .removeStorageTree(file, device, visited, removed, control, depth);
      return;
    }
    removeGenericTree(fs, file, device, visited, removed, control, depth);
  }

  private static void removeGenericTree(
      BackupFileSystem fs,
      File file,
      long device,
      Consumer<BackupFileSystem.Node> visited,
      Consumer<BackupFileSystem.Node> removed,
      BackupControl control,
      int depth)
      throws IOException {
    if (control != null) control.check();
    if (depth > BackupLimits.DEPTH) throw new IOException("STORAGE_DEPTH");
    var node = fs.stat(file);
    if (node.kind == FileKind.MISSING) return;
    if (node.device != device) throw new IOException("STORAGE_VOLUME_BOUNDARY");
    visited.accept(node);
    if (control != null) control.check();
    if (node.kind == FileKind.DIRECTORY) {
      fs.prepareOwnedRemoval(file);
      for (String name : fs.list(file))
        removeGenericTree(fs, new File(file, name), device, visited, removed, control, depth + 1);
      var current = fs.stat(file);
      if (current.kind != FileKind.DIRECTORY
          || !node.key.equals(current.key)
          || current.device != device) throw new IOException("SOURCE_CHANGED");
      node = current;
    }
    if (control != null) control.check();
    fs.deleteChecked(file, node);
    removed.accept(node);
  }

  public static CleanupResult clean(Context context) throws Exception {
    return clean(context, null, null);
  }

  public static CleanupResult clean(
      Context context, Consumer<Progress> observer, BackupControl control) throws Exception {
    var progress = new ProgressTracker(Phase.VERIFYING, observer, control);
    try {
      progress.control.check();
      var jobs = NativeBackupJobs.get(context);
      CleanupResult result;
      try (var lease = jobs.tryReserveStorageCleanup()) {
        if (lease == null || AutomaticBackups.factoryResetPending(context))
          throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
        result =
            MaintenanceCoordinator.exclusive(
                HarnessController.get(context), () -> cleanOwned(context, progress, true));
      }
      progress.finish(result);
      return result;
    } catch (Exception failure) {
      progress.finish(
          failure instanceof InterruptedException ? "CANCELLED" : BackupErrorCode.from(failure));
      throw failure;
    }
  }

  /** Expands the selected historical original only when no live runtime work owns the fence. */
  public static ExpansionResult expandRollback(
      Context context,
      String operationId,
      String expectedArchiveSha256,
      Consumer<Progress> observer,
      BackupControl control)
      throws Exception {
    var progress = new ProgressTracker(Phase.EXPANDING, observer, control);
    try {
      progress.control.check();
      if (operationId == null
          || !OPERATION_ID.matcher(operationId).matches()
          || expectedArchiveSha256 == null
          || !SHA256.matcher(expectedArchiveSha256).matches())
        throw new IOException("ROLLBACK_TARGET_INVALID");
      var jobs = NativeBackupJobs.get(context);
      ExpansionResult result;
      try (var lease = jobs.tryReserveStorageCleanup()) {
        if (lease == null) throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
        var controller = HarnessController.get(context);
        result =
            MaintenanceCoordinator.exclusiveWhenIdle(
                controller,
                () -> {
                  maintenanceFiles(context);
                  return AutomaticBackups.idle(context);
                },
                () ->
                    expandOwned(context, controller, operationId, expectedArchiveSha256, progress));
        if (result == null) throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
      }
      progress.finish(
          switch (result.outcome()) {
            case "RESTORED", "ALREADY_EXPANDED" -> Outcome.SUCCESS;
            case "RESTORED_ARCHIVE_RETAINED" -> Outcome.PARTIAL;
            default -> Outcome.FAILED;
          });
      return result;
    } catch (Exception failure) {
      progress.finish(
          failure instanceof InterruptedException ? "CANCELLED" : BackupErrorCode.from(failure));
      throw failure;
    }
  }

  private static ExpansionResult expandOwned(
      Context context,
      HarnessController controller,
      String operationId,
      String expectedArchiveSha256,
      ProgressTracker progress)
      throws IOException {
    progress.control.check();
    File files = maintenanceFiles(context);
    var fs = new AndroidBackupFileSystem();
    long device = volume(fs, files);
    long before = available(fs, files, device);
    progress.stage(Phase.EXPANDING, PrivateRoots.RUNTIMES, -1);
    var boot = controller.proot();
    var result =
        ManagedRuntimeTransaction.restoreArchivedOriginal(
            fs,
            files,
            operationId,
            expectedArchiveSha256,
            boot.expectedRuntimeDescriptor(),
            boot.installedRuntimeDescriptor(),
            boot.runtimeHealth(),
            progress.archiveControl(),
            (root, relative) -> {
              if (!relative.equals("cold") && !relative.equals("cold-work"))
                throw new IOException("ROLLBACK_EXPANSION_PATH");
              removeTree(
                  fs, fs.child(root, relative), device, ignored -> {}, ignored -> {}, null, 0);
            });
    return new ExpansionResult(
        before,
        available(fs, files, device),
        result.expandedBytes(),
        result.archiveBytes(),
        result.outcome(),
        result.reason());
  }

  /** 忙或有优先的运行时维护时返回 null，交给调度器稍后重试。 */
  static CleanupResult cleanWhenIdle(Context context) throws Exception {
    if (AutomaticBackups.factoryResetPending(context)) return null;
    var controller = HarnessController.get(context);
    var before = readCleanupRuntime(controller);
    if (before == null
        || !before.allowsIdleCleanup(
            () -> MaintenanceCoordinator.shouldDeferIdleCleanup(controller))) return null;
    var jobs = NativeBackupJobs.get(context);
    try (var lease = jobs.tryReserveStorageCleanup()) {
      if (lease == null || AutomaticBackups.factoryResetPending(context)) return null;
      return MaintenanceCoordinator.exclusiveWhenIdle(
          controller,
          () -> {
            if (!AutomaticBackups.idle(context) || !controller.isEnvironmentReady()) return false;
            // The cleanup lease itself is maintenance. Recheck the observed generation and
            // health here without rerunning a startup decision that would see our own lease.
            return before.matchesHealthyRuntime(readCleanupRuntime(controller));
          },
          () -> cleanOwned(context, new ProgressTracker(Phase.VERIFYING, null, null), false));
    }
  }

  private static RuntimeState readCleanupRuntime(HarnessController controller) {
    var boot = controller.proot();
    try {
      return new RuntimeState(
          boot.expectedRuntimeDescriptor(),
          boot.installedRuntimeDescriptor(),
          boot.runtimeHealth());
    } catch (IOException | RuntimeException unavailable) {
      return null;
    }
  }

  private static CleanupResult cleanOwned(Context context, ProgressTracker progress, boolean manual)
      throws IOException {
    progress.control.check();
    File files = maintenanceFiles(context);
    var boot = HarnessController.get(context).proot();
    var fs = new AndroidBackupFileSystem();
    long device = volume(fs, files);
    return cleanOwned(
        fs,
        files,
        new File(files.getParentFile(), "cache"),
        device,
        () -> available(fs, files, device),
        progress,
        () ->
            new RuntimeState(
                boot.expectedRuntimeDescriptor(),
                boot.installedRuntimeDescriptor(),
                boot.runtimeHealth()),
        manual);
  }

  private static File maintenanceFiles(Context context) throws IOException {
    File files = files(context);
    if (HostMaintenancePending.blocked(files)) throw new IOException("RECOVERY_PENDING");
    if (AutomaticBackups.factoryResetPending(context))
      throw new IOException("FACTORY_RESET_QUIESCING");
    return files;
  }

  static CleanupResult cleanOwned(
      BackupFileSystem fs,
      File files,
      File cache,
      long device,
      LongSupplier space,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      Map<String, Object> health,
      Consumer<Progress> observer,
      BackupControl control) {
    return cleanOwned(
        fs, files, cache, device, space, expected, active, health, observer, control, false);
  }

  static CleanupResult cleanOwned(
      BackupFileSystem fs,
      File files,
      File cache,
      long device,
      LongSupplier space,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      Map<String, Object> health,
      Consumer<Progress> observer,
      BackupControl control,
      boolean manual) {
    var progress = new ProgressTracker(Phase.VERIFYING, observer, control);
    try {
      var result =
          cleanOwned(
              fs,
              files,
              cache,
              device,
              space,
              progress,
              () -> new RuntimeState(expected, active, health),
              manual);
      progress.finish(result);
      return result;
    } catch (RuntimeException failure) {
      progress.finish(BackupErrorCode.from(failure));
      throw failure;
    }
  }

  record RuntimeState(
      RuntimeDescriptor expected, RuntimeDescriptor active, Map<String, Object> health) {
    private boolean healthy() {
      return expected != null
          && active != null
          && active.compatible(expected)
          && RuntimeDescriptor.healthy(health, active.id());
    }

    boolean allowsIdleCleanup(BooleanSupplier maintenanceRequired) {
      return healthy() && (active.latest(expected) || !maintenanceRequired.getAsBoolean());
    }

    boolean matchesHealthyRuntime(RuntimeState current) {
      return current != null
          && current.healthy()
          && expected.id().equals(current.expected.id())
          && active.id().equals(current.active.id())
          && health.get("nonce").equals(current.health.get("nonce"));
    }
  }

  @FunctionalInterface
  private interface RuntimeStateProvider {
    RuntimeState current() throws IOException;
  }

  private static CleanupResult cleanOwned(
      BackupFileSystem fs,
      File files,
      File cache,
      long device,
      LongSupplier space,
      ProgressTracker progress,
      RuntimeStateProvider runtime,
      boolean manual) {
    return measured(
        space,
        removed -> {
          cleanCaches(fs, files, cache, device, removed, progress);
          progress.control.check();
          progress.stage(Phase.VERIFYING, PrivateRoots.RUNTIMES, -1);
          cleanRetired(fs, files, device, runtime.current(), removed, progress);
        },
        manual ? () -> compactOlder(fs, files, device, runtime, progress) : null);
  }

  private static void cleanRetired(
      BackupFileSystem fs,
      File files,
      long device,
      RuntimeState runtime,
      Map<String, Long> removed,
      ProgressTracker progress)
      throws IOException {
    ManagedRuntimeTransaction.trimOlder(
        fs,
        files,
        runtime.expected,
        runtime.active,
        runtime.health,
        progress.control.withProgress(
            (stage, entries, bytes) -> {
              if (stage.equals("RUNTIME_VERIFY_READ")) progress.verified(entries, bytes);
            }),
        retiredRemoval(fs, device, removed, PrivateRoots.RUNTIMES, progress));
    progress.stage(Phase.VERIFYING, PrivateRoots.ENVIRONMENTS, -1);
    EnvironmentRebuildTransaction.cleanupCompleted(
        fs,
        files,
        progress.control,
        retiredRemoval(fs, device, removed, PrivateRoots.ENVIRONMENTS, progress),
        progress.verificationDigest());
  }

  private static CompressionResult compactOlder(
      BackupFileSystem fs,
      File files,
      long device,
      RuntimeStateProvider provider,
      ProgressTracker progress)
      throws IOException {
    progress.stage(Phase.COMPRESSING, PrivateRoots.RUNTIMES, -1);
    var runtime = provider.current();
    var result =
        ManagedRuntimeTransaction.compactOlder(
            fs,
            files,
            runtime.expected,
            runtime.active,
            runtime.health,
            progress.archiveControl(),
            (root, relative) -> {
              boolean expanded = relative.equals("cold-work/retired");
              if (expanded) progress.reportCompression("ROLLBACK_ARCHIVE_REPLACING", 0);
              // 校验副本已经落盘时，完成替换和事务标记，不中途响应取消。
              removeTree(
                  fs,
                  fs.child(root, relative),
                  device,
                  node -> {},
                  expanded ? progress::removedExpanded : node -> {},
                  null,
                  0);
            });
    return new CompressionResult(
        result.copies(),
        result.expandedBytes(),
        result.archiveBytes(),
        result.outcome(),
        "",
        result.reason());
  }

  private static PayloadRemoval retiredRemoval(
      BackupFileSystem fs,
      long device,
      Map<String, Long> removed,
      String category,
      ProgressTracker progress) {
    var account = accountRemoval(removed, category, progress);
    return (root, relative) -> {
      progress.control.check();
      progress.stage(Phase.REMOVING, category, -1);
      // 进入删除后收敛到事务的清理标记。途中取消将使原始证明永远无法重新验证。
      removeTree(fs, fs.child(root, relative), device, progress::visited, account, null, 0);
      progress.stage(Phase.VERIFYING, category, -1);
    };
  }

  static void cleanCaches(
      BackupFileSystem fs, File files, File cache, long device, Map<String, Long> removed)
      throws IOException {
    cleanCaches(fs, files, cache, device, removed, null, null);
  }

  static void cleanCaches(
      BackupFileSystem fs,
      File files,
      File cache,
      long device,
      Map<String, Long> removed,
      Consumer<Progress> observer,
      BackupControl control)
      throws IOException {
    var progress = new ProgressTracker(Phase.REMOVING, observer, control);
    try {
      cleanCaches(fs, files, cache, device, removed, progress);
      progress.finish(Outcome.SUCCESS);
    } catch (IOException | RuntimeException failure) {
      progress.finish(BackupErrorCode.from(failure));
      throw failure;
    }
  }

  private static void cleanCaches(
      BackupFileSystem fs,
      File files,
      File cache,
      long device,
      Map<String, Long> removed,
      ProgressTracker progress)
      throws IOException {
    cleanCacheCategory(fs, cache, "", CACHE, "cache", device, removed, progress);
    cleanCacheCategory(fs, files, "linux/ubuntu/", GUEST_CACHE, "linux", device, removed, progress);
    cleanCacheCategory(
        fs,
        new File(files.getParentFile(), "app_webview"),
        "",
        WEBVIEW_CACHE,
        "webview",
        device,
        removed,
        progress);
  }

  private static void cleanCacheCategory(
      BackupFileSystem fs,
      File root,
      String prefix,
      List<String> paths,
      String category,
      long device,
      Map<String, Long> removed,
      ProgressTracker progress)
      throws IOException {
    progress.stage(Phase.REMOVING, category, paths.size());
    var account = accountRemoval(removed, category, progress);
    for (String path : paths) {
      if (!RegenerableCachePaths.safeRelative(path)) throw new IOException("CACHE_PATH_FORBIDDEN");
      removeCache(fs, root, prefix + path, device, account, progress);
      progress.targetCompleted();
    }
  }

  private static Consumer<BackupFileSystem.Node> accountRemoval(
      Map<String, Long> removed, String category, ProgressTracker progress) {
    var bytes = counter(removed, category);
    return node -> {
      if (node.kind == FileKind.FILE) bytes.accept(node.size);
      progress.removed(node);
    };
  }

  @FunctionalInterface
  interface CleanupPass {
    void run(Map<String, Long> removed) throws IOException;
  }

  static CleanupResult measured(LongSupplier space, CleanupPass pass) {
    return measured(space, pass, null);
  }

  @FunctionalInterface
  private interface CompressionPass {
    CompressionResult run() throws IOException;
  }

  private static CleanupResult measured(
      LongSupplier space, CleanupPass pass, CompressionPass compact) {
    Map<String, Long> removed = new LinkedHashMap<>();
    long before = space.getAsLong();
    String error = "";
    CompressionResult compression = null;
    try {
      pass.run(removed);
    } catch (IOException | RuntimeException failure) {
      error = BackupErrorCode.from(failure);
    }
    if (compact != null && error.isEmpty()) {
      try {
        compression = compact.run();
      } catch (IOException | RuntimeException failure) {
        String code = BackupErrorCode.from(failure);
        String outcome = code.equals("CANCELLED") ? "CANCELLED" : "FAILED";
        compression = new CompressionResult(0, -1, -1, outcome, code);
      }
    }
    return new CleanupResult(before, space.getAsLong(), removed, error, compression);
  }

  static LongConsumer counter(Map<String, Long> removed, String category) {
    return bytes -> removed.merge(category, bytes, Math::addExact);
  }
}
