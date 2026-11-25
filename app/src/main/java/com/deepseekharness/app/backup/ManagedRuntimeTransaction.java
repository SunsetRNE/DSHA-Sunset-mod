package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.io.*;
import java.util.*;

/** 受管更新复用宿主多根事务。用户目录不进入切换清单，健康验证结束前不提交。 */
public final class ManagedRuntimeTransaction implements HostDataTransaction.Targets {
  public static final String HOME = PrivateRoots.RUNTIMES;
  private static final String COMPLETED = "completed",
      HISTORY_PROOF = "host-runtime-completed-v1.json";
  private static final int RETAINED_PREDECESSORS = 2;
  private static final String DSH_TREE = ManagedRuntimeLayout.ROOT + ManagedRuntimeLayout.DSH;
  private static final String ACTIVE_DESCRIPTOR = "linux/.runtime-descriptor.json";
  private static final String COLD = "cold",
      COLD_WORK = "cold-work",
      COLD_ARCHIVE = "archive.bin",
      COLD_PROOF = "proof.json";
  private static final long SPACE_RESERVE = 64L * 1024 * 1024;
  private static final long MIN_COLD_BYTES = 1024L * 1024;
  private final BackupFileSystem fs;
  private final File files, directory;
  private final Map<String, String> paths = new LinkedHashMap<>();
  private Map<String, Object> mapping;
  private final HostDataTransaction transaction;
  private static final HostDataTransaction.Settings UNCHANGED =
      new HostDataTransaction.Settings() {
        public Map<String, Object> current() {
          return Collections.emptyMap();
        }

        public void apply(Map<String, Object> value) throws IOException {
          if (!value.isEmpty()) throw new IOException("RUNTIME_SETTINGS_FORBIDDEN");
        }
      };

  public interface HealthCheck {
    Map<String, Object> verify() throws IOException;
  }

  private ManagedRuntimeTransaction(
      BackupFileSystem fs, File files, File directory, HostDataTransaction.Fault fault)
      throws IOException {
    this.fs = fs;
    this.files = files.getAbsoluteFile();
    this.directory = directory;
    File log = fs.child(directory, "runtime-mapping.json");
    if (!fs.stat(log).type.equals("MISSING")) {
      mapping = BackupJson.read(fs.small(log, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
      long format = BackupJson.number(mapping, "version");
      if ((format != 1 && format != 2)
          || !directory.getName().equals(BackupJson.string(mapping, "id")))
        throw new IOException("RUNTIME_MAPPING");
      Object records = mapping.get("paths");
      if (!(records instanceof Map)
          || ((Map<?, ?>) records).isEmpty()
          || ((Map<?, ?>) records).size() > 2048) throw new IOException("RUNTIME_MAPPING");
      Set<String> unique = new HashSet<>();
      for (var entry : ((Map<?, ?>) records).entrySet()) {
        if (!(entry.getKey() instanceof String)
            || !((String) entry.getKey()).matches("runtime-[0-9]{1,4}")
            || !(entry.getValue() instanceof String)) throw new IOException("RUNTIME_MAPPING");
        String path = (String) entry.getValue();
        if (!ManagedRuntimeLayout.allowed(path)
            || path.equals("linux/.runtime-health.json")
            || !unique.add(path)) throw new IOException("RUNTIME_MAPPING");
        paths.put((String) entry.getKey(), path);
      }
      new RuntimeDescriptor(object(mapping, "candidateDescriptor"));
      if (BackupJson.number(mapping, "createdAt") <= 0) throw new IOException("RUNTIME_MAPPING");
    }
    transaction = new HostDataTransaction(fs, directory, this, UNCHANGED, fault);
  }

  public static ManagedRuntimeTransaction create(BackupFileSystem fs, File files)
      throws IOException {
    return create(fs, files, null);
  }

  public static ManagedRuntimeTransaction create(
      BackupFileSystem fs, File files, HostDataTransaction.Fault fault) throws IOException {
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!pending(fs, files).isEmpty()) throw new IOException("RUNTIME_RECOVERY_REQUIRED");
    pruneEmptyPlaceholders(fs, home);
    File entry = fs.child(home, UUID.randomUUID().toString());
    fs.directory(entry);
    fs.directory(new File(entry, "candidate"));
    fs.directory(new File(entry, "stage"));
    ManagedRuntimeTransaction created = new ManagedRuntimeTransaction(fs, files, entry, fault);
    created.markerWrite("created");
    return created;
  }

  public File directory() {
    File retained = new File(new File(files, HOME), COMPLETED + "/" + directory.getName());
    return directory.exists() ? directory : retained.exists() ? retained : directory;
  }

  public File stage() {
    return new File(directory, "stage");
  }

  public static ManagedRuntimeTransaction open(
      BackupFileSystem fs, File files, String id, HostDataTransaction.Fault fault)
      throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("RUNTIME_OPERATION_ID");
    File home = fs.child(files, HOME),
        active = fs.child(home, id),
        retained = new File(home, COMPLETED + "/" + id);
    String a = fs.stat(active).type, h = fs.stat(retained).type;
    if (!a.equals("MISSING") && !h.equals("MISSING"))
      throw new IOException("RUNTIME_OPERATION_DUPLICATE");
    File selected = a.equals("DIRECTORY") ? active : h.equals("DIRECTORY") ? retained : null;
    if (selected == null) throw new IOException("RUNTIME_OPERATION_ID");
    return new ManagedRuntimeTransaction(fs, files, selected, fault);
  }

  @Override
  public File resolve(String root) throws IOException {
    String path = paths.get(root);
    if (path == null) throw new IOException("UNKNOWN_RUNTIME_ROOT");
    return fs.child(files, path);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> value, String key)
      throws IOException {
    if (!(value.get(key) instanceof Map)) throw new IOException("RUNTIME_MAPPING");
    return (Map<String, Object>) value.get(key);
  }

  public void prepare(
      List<String> managed,
      RuntimeDescriptor candidate,
      RuntimeDescriptor previous,
      Map<String, Object> previousHealth,
      BackupControl control)
      throws IOException {
    if (mapping != null || managed.isEmpty() || managed.size() > 2048)
      throw new IOException("RUNTIME_PLAN");
    Set<String> unique = new HashSet<>();
    for (String path : managed) {
      if (path.equals("linux/.runtime-health.json")) continue;
      if (!ManagedRuntimeLayout.allowed(path) || !unique.add(path))
        throw new IOException("RUNTIME_PATH_FORBIDDEN");
      paths.put("runtime-" + paths.size(), path);
    }
    Map<String, String> removals = stageRetiredPaths(previous, previousHealth, unique, control);
    Map<String, String> before = new LinkedHashMap<>();
    for (var entry : paths.entrySet()) {
      fs.parents(files, entry.getValue());
      before.put(entry.getKey(), BackupTree.digest(fs, resolve(entry.getKey()), control));
      String retired = removals.get(entry.getKey());
      if (retired == null)
        fs.move(fs.child(stage(), entry.getValue()), transaction.candidate(entry.getKey()));
      else if (!retired.equals(before.get(entry.getKey()))) throw new IOException("TARGET_CHANGED");
    }
    // 旧回执绑定的是当时通过试运行的字节，不把后续用户修改过的受管目录冒充健康副本。
    if (!previousBytesVerified(previousHealth, before)) previousHealth = null;
    saveMapping(candidate, previous, previousHealth, "");
    transaction.prepare(
        new ArrayList<>(paths.keySet()),
        before,
        Collections.emptyMap(),
        control,
        removals.keySet());
    writeArchiveSeal();
  }

  private Map<String, String> stageRetiredPaths(
      RuntimeDescriptor previous,
      Map<String, Object> receipt,
      Set<String> staged,
      BackupControl control)
      throws IOException {
    Map<String, String> removals = new HashMap<>();
    if (previous == null
        || !RuntimeDescriptor.healthy(receipt, previous.id())
        || !(receipt.get("managedHashes") instanceof Map<?, ?> hashes)) return removals;
    for (var entry : hashes.entrySet()) {
      control.check();
      if (!(entry.getKey() instanceof String path)
          || staged.contains(path)
          || !ManagedRuntimeLayout.allowed(path)
          || !path.startsWith(ManagedRuntimeLayout.ROOT)) continue;
      boolean readHint = path.equals(ManagedRuntimeLayout.RETIRED_READ_HINT);
      if (!readHint
          && !ManagedRuntimeLayout.alias(path.substring(ManagedRuntimeLayout.ROOT.length())))
        continue;
      File live;
      try {
        live = fs.child(files, path);
        if (readHint && !isFormerReadHint(live)) continue;
      } catch (IOException unavailable) {
        continue;
      }
      if (!(entry.getValue() instanceof String digest)
          || !readHint && !fs.stat(live).type.equals("LINK")
          || !BackupTree.digest(fs, live, control).equals(digest)) continue;
      if (paths.size() == 2048) throw new IOException("RUNTIME_PLAN");
      String id = "runtime-" + paths.size();
      paths.put(id, path);
      removals.put(id, digest);
    }
    return removals;
  }

  private boolean isFormerReadHint(File directory) throws IOException {
    // The health receipt binds the whole tree. Also match the signed build373 payload, so a
    // user tree captured in a health receipt cannot qualify by its pathname alone.
    if (!fs.stat(directory).type.equals("DIRECTORY")
        || !Set.copyOf(fs.list(directory))
            .equals(Set.of("cordis.patch.yml", "lib", "node_modules", "package.json")))
      return false;
    File library = new File(directory, "lib"), modules = new File(directory, "node_modules");
    if (!fs.stat(library).type.equals("DIRECTORY")
        || !fs.list(library).equals(List.of("index.js"))
        || !fs.stat(modules).type.equals("LINK")
        || !fs.readLink(modules)
            .equals("../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules")) return false;
    return matchesFormerFile(
            new File(directory, "cordis.patch.yml"),
            320,
            "8f0ad04833345b78ed6d0f147ffdf2eb7f8ad5b929c9e368ce41be655662cf8b")
        && matchesFormerFile(
            new File(library, "index.js"),
            5381,
            "f5415dea4390d6f82bcce8a7767d5847166282d1fd2f45ae17053f5c7934a88a")
        && matchesFormerFile(
            new File(directory, "package.json"),
            642,
            "6b0cb9bf961b5f1be64f8c945730da7d17f43dfed8aed4be5496046e2f8c9b65");
  }

  private boolean matchesFormerFile(File file, int size, String sha256) throws IOException {
    var node = fs.stat(file);
    return node.type.equals("FILE")
        && node.mode == 0600
        && node.size == size
        && BackupArchive.hex(BackupArchive.sha().digest(fs.small(file, size))).equals(sha256);
  }

  private boolean previousBytesVerified(Map<String, Object> receipt, Map<String, String> before)
      throws IOException {
    if (receipt == null || !(receipt.get("managedHashes") instanceof Map)) return false;
    Map<?, ?> expected = (Map<?, ?>) receipt.get("managedHashes");
    if (expected.isEmpty() || !paths.values().containsAll(expected.keySet())) return false;
    for (var path : paths.entrySet()) {
      Object digest = expected.get(path.getValue());
      if (expected.containsKey(path.getValue())
          || !fs.stat(resolve(path.getKey())).type.equals("MISSING")) {
        if (!before.get(path.getKey()).equals(digest)) return false;
      }
    }
    return true;
  }

  private void saveMapping(
      RuntimeDescriptor candidate,
      RuntimeDescriptor previous,
      Map<String, Object> previousHealth,
      String source)
      throws IOException {
    mapping = new LinkedHashMap<>();
    mapping.put("version", 2L);
    mapping.put("id", directory.getName());
    mapping.put("paths", paths);
    mapping.put("candidateDescriptor", candidate.json());
    mapping.put("previousDescriptor", previous == null ? Collections.emptyMap() : previous.json());
    mapping.put("previousHealth", previousHealth == null ? Collections.emptyMap() : previousHealth);
    mapping.put("source", source);
    mapping.put("createdAt", System.currentTimeMillis());
    try (OutputStream out = fs.create(fs.child(directory, "runtime-mapping.json"))) {
      out.write(BackupJson.write(mapping, BackupLimits.MANIFEST));
    }
    fs.syncDirectory(directory);
  }

  public void commit(
      BackupControl control, HealthCheck check, java.util.function.BooleanSupplier mayRecover)
      throws IOException {
    RuntimeDescriptor expected = new RuntimeDescriptor(object(mapping, "candidateDescriptor"));
    transaction.commit(
        control,
        new HostDataTransaction.CommitCheck() {
          public void verify() throws IOException {
            Map<String, Object> proof = check.verify();
            if (!RuntimeDescriptor.healthy(proof, expected.id()))
              throw new IOException("RUNTIME_HEALTH_INCOMPLETE");
            try (OutputStream out = fs.create(fs.child(directory, "health.json"))) {
              out.write(BackupJson.write(proof, 512 * 1024));
            }
            fs.syncDirectory(directory);
          }

          public boolean mayRecover() {
            return mayRecover.getAsBoolean();
          }
        });
  }

  private ManagedRuntimeTransaction relocated() throws IOException {
    File active = new File(files, HOME + "/" + directory.getName());
    File history = new File(files, HOME + "/" + COMPLETED + "/" + directory.getName());
    String old = fs.stat(active).type, retained = fs.stat(history).type;
    if (!old.equals("MISSING") && !retained.equals("MISSING"))
      throw new IOException("RUNTIME_OPERATION_DUPLICATE");
    if (directory.equals(history)) {
      if (!retained.equals("DIRECTORY")) throw new IOException("RUNTIME_OPERATION_MISSING");
      return this;
    }
    if (old.equals("MISSING")) {
      if (!retained.equals("DIRECTORY")) throw new IOException("RUNTIME_OPERATION_MISSING");
      return new ManagedRuntimeTransaction(fs, files, history, null);
    }
    if (!old.equals("DIRECTORY")) throw new IOException("RUNTIME_OPERATION_DIRECTORY");
    return this;
  }

  private String recordHash(String name) throws IOException {
    return BackupArchive.hex(
        BackupArchive.sha().digest(fs.small(fs.child(directory, name), BackupLimits.MANIFEST)));
  }

  private void writeArchiveSeal() throws IOException {
    Map<String, Object> proof =
        Map.of(
            "version",
            1L,
            "id",
            directory.getName(),
            "mapping",
            recordHash("runtime-mapping.json"),
            "plan",
            recordHash("plan.json"));
    try (OutputStream out = fs.create(fs.child(directory, "archive-seal.json"))) {
      out.write(BackupJson.write(proof, 4096));
    }
    fs.syncDirectory(directory);
  }

  private boolean archiveSealMatches() throws IOException {
    if (mapping == null || BackupJson.number(mapping, "version") != 2) return false;
    File seal = fs.child(directory, "archive-seal.json");
    if (!fs.stat(seal).type.equals("FILE")) return false;
    Map<String, Object> proof = BackupJson.read(fs.small(seal, 4096), 4096);
    if (BackupJson.number(proof, "version") != 1
        || !directory.getName().equals(BackupJson.string(proof, "id"))
        || !recordHash("runtime-mapping.json").equals(BackupJson.string(proof, "mapping"))
        || !recordHash("plan.json").equals(BackupJson.string(proof, "plan"))) return false;
    Set<String> allowed =
        Set.of(
            "created",
            "candidate",
            "stage",
            "runtime-mapping.json",
            "plan.json",
            "verified",
            "switching",
            "files-committed",
            "settings-committed",
            "health.json",
            "finalized",
            "previous",
            "failed",
            COLD,
            COLD_WORK,
            "previous-expanded",
            "previous-retiring",
            "previous-cleaned",
            "rolling-back",
            "rolled-back",
            "archive-seal.json");
    return allowed.containsAll(fs.list(directory)) && marker("finalized") && !marker("rolled-back");
  }

  private boolean archiveSealed(BackupControl control) throws IOException {
    if (!archiveSealMatches()) return false;
    if (marker("previous-cleaned"))
      return emptyDirectory(fs, fs.child(directory, "previous"))
          && fs.stat(fs.child(directory, COLD)).type.equals("MISSING");
    return retainedBytesUnchanged(control, false);
  }

  public void recover() throws IOException {
    ManagedRuntimeTransaction current = relocated();
    current.transaction.recover();
  }

  public static List<ManagedRuntimeTransaction> pending(BackupFileSystem fs, File files)
      throws IOException {
    verifyHistory(fs, files);
    List<ManagedRuntimeTransaction> result = new ArrayList<>();
    for (ManagedRuntimeTransaction item : active(fs, files)) {
      if (item.marker("switching") && !item.marker("finalized") && !item.marker("rolled-back"))
        result.add(item);
    }
    if (result.size() > 1) throw new IOException("MULTIPLE_RUNTIME_TRANSACTIONS");
    return result;
  }

  public static boolean blocked(File files) {
    try {
      return !pending(new AndroidBackupFileSystem(), files.getCanonicalFile()).isEmpty();
    } catch (IOException error) {
      return true;
    }
  }

  private static List<ManagedRuntimeTransaction> all(BackupFileSystem fs, File files)
      throws IOException {
    List<ManagedRuntimeTransaction> result = active(fs, files);
    File history = new File(files, HOME + "/" + COMPLETED);
    if (fs.stat(history).type.equals("DIRECTORY"))
      for (String id : fs.list(history)) {
        if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
          throw new IOException("RUNTIME_OPERATION_ID");
        if (!fs.stat(new File(files, HOME + "/" + id)).type.equals("MISSING"))
          throw new IOException("RUNTIME_OPERATION_DUPLICATE");
        result.add(new ManagedRuntimeTransaction(fs, files, fs.child(history, id), null));
      }
    return result;
  }

  private static List<ManagedRuntimeTransaction> active(BackupFileSystem fs, File files)
      throws IOException {
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) return Collections.emptyList();
    List<String> children = fs.list(home);
    List<ManagedRuntimeTransaction> result = new ArrayList<>();
    for (String id : children) {
      if (id.equals(COMPLETED)) continue;
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("RUNTIME_OPERATION_ID");
      if (!fs.stat(new File(home, COMPLETED + "/" + id)).type.equals("MISSING"))
        throw new IOException("RUNTIME_OPERATION_DUPLICATE");
      result.add(new ManagedRuntimeTransaction(fs, files, fs.child(home, id), null));
    }
    return result;
  }

  private static void verifyHistory(BackupFileSystem fs, File files) throws IOException {
    File home = new File(files, HOME),
        history = new File(home, COMPLETED),
        proof = new File(files, HISTORY_PROOF);
    String type = fs.stat(history).type;
    if (type.equals("MISSING")) {
      if (!fs.stat(proof).type.equals("MISSING"))
        throw new IOException("RUNTIME_HISTORY_PROOF_ORPHAN");
      return;
    }
    if (!type.equals("DIRECTORY") || !fs.stat(home).type.equals("DIRECTORY"))
      throw new IOException("RUNTIME_HISTORY_TYPE");
    String filesKey = fs.stat(files).key, historyKey = fs.stat(history).key;
    if (CompletedHistory.matches(fs, proof, "filesKey", filesKey, historyKey, "RUNTIME_HISTORY_"))
      return;
    for (String id : fs.list(history)) {
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("RUNTIME_HISTORY_ID");
      ManagedRuntimeTransaction item =
          new ManagedRuntimeTransaction(fs, files, fs.child(history, id), null);
      if (!item.terminal()) throw new IOException("RUNTIME_HISTORY_NOT_TERMINAL");
      if (!fs.stat(new File(home, id)).type.equals("MISSING"))
        throw new IOException("RUNTIME_OPERATION_DUPLICATE");
    }
    CompletedHistory.write(fs, proof, "filesKey", filesKey, historyKey);
  }

  private boolean terminal() throws IOException {
    boolean finalized = marker("finalized"), rolledBack = marker("rolled-back");
    if (finalized == rolledBack) return false;
    if (mapping == null) return false;
    Map<String, Object> plan =
        BackupJson.read(
            fs.small(fs.child(directory, "plan.json"), BackupLimits.MANIFEST),
            BackupLimits.MANIFEST);
    return BackupJson.number(plan, "version") == 1
        && directory.getName().equals(BackupJson.string(plan, "id"));
  }

  public static boolean isTerminalRecord(BackupFileSystem fs, File files, String id)
      throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)) return false;
    File entry = new File(files, HOME + "/" + id);
    return fs.stat(entry).type.equals("DIRECTORY")
        && new ManagedRuntimeTransaction(fs, files, entry, null).terminal();
  }

  public static void archiveCompleted(BackupFileSystem fs, File files) throws IOException {
    archiveCompleted(fs, files, new BackupControl(null));
  }

  private static void archiveCompleted(BackupFileSystem fs, File files, BackupControl control)
      throws IOException {
    File home = new File(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) {
      verifyHistory(fs, files);
      return;
    }
    verifyHistory(fs, files);
    File history = new File(home, COMPLETED);
    for (ManagedRuntimeTransaction item : active(fs, files)) {
      control.check();
      if (!item.marker("finalized") && !item.marker("rolled-back")) continue;
      if (!item.terminal()) throw new IOException("RUNTIME_HISTORY_NOT_TERMINAL");
      try {
        if (!item.archiveSealed(control)) continue;
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException modified) {
        continue;
      }
      if (fs.stat(history).type.equals("MISSING")) fs.directory(history);
      File destination = fs.child(history, item.directory.getName());
      if (!fs.stat(destination).type.equals("MISSING"))
        throw new IOException("RUNTIME_OPERATION_DUPLICATE");
      BackupFileSystem.Node before = fs.stat(item.directory);
      String digest =
          BackupArchive.hex(
              BackupArchive.sha()
                  .digest(fs.small(fs.child(item.directory, "plan.json"), BackupLimits.MANIFEST)));
      control.check();
      fs.move(item.directory, destination);
      fs.syncDirectory(home);
      fs.syncDirectory(history);
      if (!before.key.equals(fs.stat(destination).key)
          || !digest.equals(
              BackupArchive.hex(
                  BackupArchive.sha()
                      .digest(
                          fs.small(fs.child(destination, "plan.json"), BackupLimits.MANIFEST)))))
        throw new IOException("RUNTIME_ARCHIVE_IDENTITY");
      verifyHistory(fs, files);
    }
  }

  private boolean marker(String name) throws IOException {
    return TransactionMarkers.read(fs, directory, name, "RUNTIME_MARKER");
  }

  private void markerWrite(String name) throws IOException {
    TransactionMarkers.write(fs, directory, name, "RUNTIME_MARKER");
  }

  private RuntimeDescriptor previousDescriptor() throws IOException {
    Map<String, Object> value = object(mapping, "previousDescriptor");
    return value.isEmpty() ? null : new RuntimeDescriptor(value);
  }

  public boolean hasHealthyPrevious() throws IOException {
    ManagedRuntimeTransaction current = relocated();
    if (current != this) return current.hasHealthyPrevious();
    return verifiedPrevious(new BackupControl(null), false) != null;
  }

  private RuntimeDescriptor verifiedPrevious(BackupControl control, boolean requireSeal)
      throws IOException {
    if (mapping == null
        || !marker("finalized")
        || marker("rolled-back")
        || marker("previous-cleaned")) return null;
    RuntimeDescriptor previous = previousDescriptor();
    if (previous == null
        || !RuntimeDescriptor.healthy(object(mapping, "previousHealth"), previous.id()))
      return null;
    boolean sealedFormat = BackupJson.number(mapping, "version") == 2;
    if ((requireSeal || sealedFormat) && !archiveSealMatches()) return null;
    return retainedBytesUnchanged(control, requireSeal) ? previous : null;
  }

  public static final class RollbackOption {
    public final String operationId, dshVersion, runtimeId;
    public final long createdAt;

    private RollbackOption(ManagedRuntimeTransaction source, RuntimeDescriptor previous)
        throws IOException {
      operationId = source.directory.getName();
      runtimeId = previous.id();
      dshVersion = BackupJson.string(previous.json(), "dshVersion");
      createdAt = BackupJson.number(source.mapping, "createdAt");
    }
  }

  private boolean canRollback(
      RuntimeDescriptor expected, RuntimeDescriptor current, BackupControl control)
      throws IOException {
    if (expected == null || current == null || mapping == null || !candidateMatches(current))
      return false;
    RuntimeDescriptor previous = verifiedPrevious(control, false);
    // 一次只回退直接前代，确保清单包含当前版本新增的受管文件；不恢复任何用户数据快照。
    return previous != null && rollbackCompatible(previous, expected, current);
  }

  private boolean candidateMatches(RuntimeDescriptor current) throws IOException {
    return current.id().equals(new RuntimeDescriptor(object(mapping, "candidateDescriptor")).id());
  }

  private static boolean rollbackCompatible(
      RuntimeDescriptor previous, RuntimeDescriptor expected, RuntimeDescriptor current) {
    return previous.compatible(expected) && previous.canReadDataWrittenBy(current);
  }

  public static List<RollbackOption> rollbackOptions(
      BackupFileSystem fs, File files, RuntimeDescriptor expected, RuntimeDescriptor current)
      throws IOException {
    if (!pending(fs, files).isEmpty()) throw new IOException("RUNTIME_RECOVERY_REQUIRED");
    List<RollbackOption> result = new ArrayList<>();
    BackupControl control = new BackupControl(null);
    for (var item : all(fs, files)) {
      try {
        if (item.canRollback(expected, current, control))
          result.add(new RollbackOption(item, item.previousDescriptor()));
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException unavailable) {
        // The other independent copies remain available when this copy cannot be read.
      }
    }
    result.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
    return result;
  }

  public static ManagedRuntimeTransaction prepareRollback(
      BackupFileSystem fs,
      File files,
      String sourceId,
      RuntimeDescriptor expected,
      RuntimeDescriptor current,
      Map<String, Object> currentHealth,
      BackupControl control)
      throws IOException {
    ManagedRuntimeTransaction source = open(fs, files, sourceId, null);
    if (!source.canRollback(expected, current, control))
      throw new IOException("RUNTIME_ROLLBACK_INCOMPATIBLE");
    List<PreviousRoot> rows = source.previousRoots();
    Map<String, ColdCopy> archives = new HashMap<>();
    long bytes = 0;
    for (var row : rows) {
      File old = source.previousPath(row);
      ColdCopy cold = source.verifyRetainedRoot(row, old, control, false);
      if (cold != null) archives.put(row.id(), cold);
      long expanded =
          cold == null
              ? treeBytes(fs, old, control, 0, new int[] {0})
              : cold.metadata().logicalBytes();
      bytes = BackupLimits.add(bytes, expanded, BackupLimits.BYTES);
    }
    if (files.getUsableSpace() < bytes + SPACE_RESERVE) throw new IOException("NO_SPACE");
    ManagedRuntimeTransaction next = create(fs, files);
    next.paths.putAll(source.paths);
    Map<String, String> before = new LinkedHashMap<>();
    Set<String> removals = new HashSet<>();
    for (var row : rows) {
      String id = row.id();
      fs.parents(files, next.paths.get(id));
      before.put(id, BackupTree.digest(fs, next.resolve(id), control));
      if (!row.had()) {
        removals.add(id);
        continue;
      }
      var cold = archives.get(id);
      File candidate = next.transaction.candidate(id);
      if (cold == null) {
        BackupTree.copy(fs, source.previousPath(row), candidate, control);
        if (!row.digest().equals(BackupTree.digest(fs, candidate, control)))
          throw new IOException("RETAINED_RUNTIME_CHANGED");
      } else RuntimeRollbackArchive.unpack(fs, cold.archive(), candidate, cold.metadata(), control);
    }
    if (!next.previousBytesVerified(currentHealth, before)) currentHealth = null;
    next.saveMapping(source.previousDescriptor(), current, currentHealth, sourceId);
    next.transaction.prepare(
        new ArrayList<>(next.paths.keySet()), before, Collections.emptyMap(), control, removals);
    next.writeArchiveSeal();
    return next;
  }

  private static long treeBytes(
      BackupFileSystem fs, File file, BackupControl control, int depth, int[] count)
      throws IOException {
    control.check();
    if (depth > BackupLimits.DEPTH || ++count[0] > BackupLimits.ENTRIES)
      throw new IOException("RUNTIME_LIMIT");
    var node = fs.stat(file);
    if (node.type.equals("FILE")) return node.size;
    if (!node.type.equals("DIRECTORY")) return 0;
    long result = 0;
    for (String name : fs.list(file))
      result =
          BackupLimits.add(
              result,
              treeBytes(fs, fs.child(file, name), control, depth + 1, count),
              BackupLimits.BYTES);
    return result;
  }

  public static void trimOlder(
      BackupFileSystem fs, File files, RuntimeDescriptor active, Map<String, Object> health)
      throws IOException {
    trimOlder(fs, files, active, active, health);
  }

  public static void trimOlder(
      BackupFileSystem fs,
      File files,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      Map<String, Object> health)
      throws IOException {
    trimOlder(fs, files, expected, active, health, new BackupControl(null), fs::removeOwned);
  }

  static void trimOlder(
      BackupFileSystem fs,
      File files,
      RuntimeDescriptor active,
      Map<String, Object> health,
      StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    trimOlder(fs, files, active, active, health, new BackupControl(null), remove);
  }

  static void trimOlder(
      BackupFileSystem fs,
      File files,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      Map<String, Object> health,
      BackupControl control,
      StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    control.check();
    if (expected == null
        || active == null
        || !RuntimeDescriptor.healthy(health, active.id())
        || !pending(fs, files).isEmpty()) return;
    List<RetainedPredecessor> healthy =
        verifiedHistory(fs, files, expected, active, control, remove);
    if (healthy.size() > RETAINED_PREDECESSORS && liveBytesVerified(fs, files, health, control)) {
      Set<RetainedPredecessor> kept = new HashSet<>();
      Set<String> runtimeIds = new HashSet<>();
      if (healthy.get(0).direct()) kept.add(healthy.get(0));
      for (var copy : healthy)
        if (copy.transaction().expandedDsh()) {
          kept.add(copy);
          break;
        }
      for (var copy : kept) runtimeIds.add(copy.runtimeId());
      for (var copy : healthy) {
        if (kept.size() == RETAINED_PREDECESSORS) break;
        if (runtimeIds.add(copy.runtimeId())) kept.add(copy);
      }
      // Keep two verified copies even if the history only contains one runtime identity.
      for (var copy : healthy) {
        if (kept.size() == RETAINED_PREDECESSORS) break;
        kept.add(copy);
      }
      for (var copy : healthy) {
        control.check();
        if (!kept.contains(copy)) copy.transaction().retirePrevious(control, remove);
      }
    }
    control.check();
    archiveCompleted(fs, files, control);
  }

  private record RetainedPredecessor(
      ManagedRuntimeTransaction transaction, String runtimeId, boolean direct, long createdAt) {}

  private static List<RetainedPredecessor> verifiedHistory(
      BackupFileSystem fs,
      File files,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      BackupControl control,
      StorageMaintenance.PayloadRemoval retirementRemoval)
      throws IOException {
    List<RetainedPredecessor> healthy = new ArrayList<>();
    for (var item : all(fs, files)) {
      control.check();
      try {
        if (retirementRemoval != null) {
          if (item.finalizedWithoutPrevious()) item.markerWrite("previous-cleaned");
          else item.finishRetirement(control, retirementRemoval);
        }
        RuntimeDescriptor previous = item.verifiedPrevious(control, true);
        if (previous == null) continue;
        healthy.add(
            new RetainedPredecessor(
                item,
                previous.id(),
                !previous.id().equals(active.id())
                    && item.candidateMatches(active)
                    && rollbackCompatible(previous, expected, active),
                BackupJson.number(item.mapping, "createdAt")));
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException retained) {
        // An unreadable or changed record cannot justify removing any retained copy.
      }
    }
    healthy.sort(
        Comparator.comparing(RetainedPredecessor::direct)
            .thenComparing(p -> !p.runtimeId().equals(active.id()))
            .thenComparingLong(RetainedPredecessor::createdAt)
            .reversed()
            .thenComparing(p -> p.transaction().directory.getName()));
    return healthy;
  }

  public record CompactionResult(
      int copies, long expandedBytes, long archiveBytes, String outcome, String reason) {
    public CompactionResult(int copies, long expandedBytes, long archiveBytes, String outcome) {
      this(copies, expandedBytes, archiveBytes, outcome, "");
    }

    static CompactionResult none(String outcome) {
      return new CompactionResult(0, 0, 0, outcome);
    }

    static CompactionResult proofUnavailable(String reason) {
      return new CompactionResult(0, 0, 0, "PROOF_UNAVAILABLE", reason);
    }
  }

  /** Only an explicitly requested storage cleanup may replace an expanded copy with an archive. */
  static CompactionResult compactOlder(
      BackupFileSystem fs,
      File files,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      Map<String, Object> health,
      BackupControl control,
      StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    control.check();
    String admission = archiveAdmission(fs, files, expected, active, health);
    if (!admission.isEmpty()) return CompactionResult.proofUnavailable(admission);
    List<ArchiveableOriginal> originals = archiveableHistory(fs, files, control);
    if (originals.size() < 2) return CompactionResult.none("NO_ELIGIBLE_COPY");
    String activeProof = archivalActiveProof(fs, files, health, control);
    if (!activeProof.isEmpty()) return CompactionResult.proofUnavailable(activeProof);
    String expandedId = "";
    for (var original : originals) {
      var item = original.operation();
      File raw = item.previousPath(original.root());
      if (fs.stat(raw).type.equals("DIRECTORY")
          && original.root().digest().equals(item.digestForCleanup(raw, control))) {
        expandedId = item.directory.getName();
        break;
      }
    }
    if (expandedId.isEmpty()) return CompactionResult.none("NO_ELIGIBLE_COPY");
    List<RetainedPredecessor> healthy = verifiedHistory(fs, files, expected, active, control, null);
    String newestDirectId =
        !healthy.isEmpty() && healthy.get(0).direct()
            ? healthy.get(0).transaction().directory.getName()
            : "";
    CompactionResult skipped = CompactionResult.none("NO_ELIGIBLE_COPY");
    for (boolean wasExpanded : new boolean[] {false, true}) {
      for (int i = originals.size() - 1; i >= 0; i--) {
        var original = originals.get(i);
        var item = original.operation();
        String id = item.directory.getName();
        if (id.equals(expandedId)
            || id.equals(newestDirectId)
            || item.marker("previous-expanded") != wasExpanded) continue;
        CompactionResult result =
            item.compactPrevious(original.root(), control, remove, files::getUsableSpace);
        if (result.copies() > 0) return result;
        if (!result.outcome().equals("NO_ELIGIBLE_COPY")) skipped = result;
      }
    }
    return skipped;
  }

  private boolean expandedDsh() throws IOException {
    PreviousRoot dsh = dshRoot(previousRoots());
    return dsh != null
        && fs.stat(previousPath(dsh)).type.equals("DIRECTORY")
        && fs.stat(fs.child(directory, COLD)).type.equals("MISSING")
        && fs.stat(fs.child(directory, COLD_WORK)).type.equals("MISSING");
  }

  private record ArchiveableOriginal(
      ManagedRuntimeTransaction operation, PreviousRoot root, long createdAt) {}

  private static List<ArchiveableOriginal> archiveableHistory(
      BackupFileSystem fs, File files, BackupControl control) throws IOException {
    List<ArchiveableOriginal> originals = new ArrayList<>();
    for (var item : all(fs, files)) {
      control.check();
      try {
        PreviousRoot dsh = item.archiveableDsh();
        if (dsh != null)
          originals.add(
              new ArchiveableOriginal(item, dsh, BackupJson.number(item.mapping, "createdAt")));
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException unreadable) {
        // Keep records with missing or changed provenance in place.
      }
    }
    originals.sort(
        Comparator.comparingLong(ArchiveableOriginal::createdAt)
            .reversed()
            .thenComparing(copy -> copy.operation().directory.getName()));
    return originals;
  }

  private PreviousRoot archiveableDsh() throws IOException {
    if (!archiveSealMatches()
        || marker("previous-cleaned")
        || marker("previous-retiring")
        || !fs.stat(fs.child(directory, "previous")).type.equals("DIRECTORY")) return null;
    object(mapping, "previousDescriptor");
    object(mapping, "previousHealth");
    return dshRoot(previousRoots());
  }

  public record RestorationResult(
      long expandedBytes, long archiveBytes, String outcome, String reason) {
    public RestorationResult(long expandedBytes, long archiveBytes, String outcome) {
      this(expandedBytes, archiveBytes, outcome, "");
    }

    static RestorationResult none(String outcome) {
      return new RestorationResult(0, 0, outcome);
    }

    static RestorationResult proofUnavailable(String reason) {
      return new RestorationResult(0, 0, "PROOF_UNAVAILABLE", reason);
    }
  }

  /** Expand the selected original into its private historical slot under an exclusive operation. */
  static RestorationResult restoreArchivedOriginal(
      BackupFileSystem fs,
      File files,
      String operationId,
      String expectedArchiveSha256,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      Map<String, Object> health,
      BackupControl control,
      StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    control.check();
    if (operationId == null
        || !operationId.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)
        || expectedArchiveSha256 == null
        || !expectedArchiveSha256.matches("[a-f0-9]{64}"))
      return RestorationResult.proofUnavailable("HISTORICAL_ARCHIVE_SELECTION");
    String admission = archiveAdmission(fs, files, expected, active, health);
    if (!admission.isEmpty()) return RestorationResult.proofUnavailable(admission);
    ManagedRuntimeTransaction source = open(fs, files, operationId, null);
    PreviousRoot row = source.archiveableDsh();
    if (row == null) return RestorationResult.proofUnavailable("HISTORICAL_SEAL");
    File cold = fs.child(source.directory, COLD);
    if (!fs.stat(cold).type.equals("DIRECTORY"))
      return RestorationResult.proofUnavailable("HISTORICAL_ARCHIVE_SELECTION");
    ColdCopy copy = source.readCold(cold, row);
    if (!expectedArchiveSha256.equals(copy.metadata().archiveSha256()))
      return RestorationResult.proofUnavailable("HISTORICAL_ARCHIVE_SELECTION");
    String activeProof = archivalActiveProof(fs, files, health, control);
    if (!activeProof.isEmpty()) return RestorationResult.proofUnavailable(activeProof);
    return source.restoreOriginal(row, cold, copy, control, remove, files::getUsableSpace);
  }

  private RestorationResult restoreOriginal(
      PreviousRoot row,
      File cold,
      ColdCopy copy,
      BackupControl control,
      StorageMaintenance.PayloadRemoval remove,
      java.util.function.LongSupplier usableSpace)
      throws IOException {
    var metadata = copy.metadata();
    File raw = previousPath(row);
    File work = fs.child(directory, COLD_WORK);
    var archiveDirectory = fs.stat(cold);
    var archiveFile = fs.stat(copy.archive());
    String rawType = fs.stat(raw).type;
    boolean needsExpansion = rawType.equals("MISSING");
    if (!needsExpansion
        && (!rawType.equals("DIRECTORY") || !row.digest().equals(digestForCleanup(raw, control))))
      return RestorationResult.none("RAW_CONFLICT");
    if (needsExpansion && usableSpace.getAsLong() < metadata.logicalBytes() + SPACE_RESERVE)
      return RestorationResult.none("INSUFFICIENT_SPACE");
    control.report("ROLLBACK_ARCHIVE_LOGICAL_SIZE", metadata.entries(), metadata.logicalBytes());
    if (needsExpansion) {
      RuntimeRollbackArchive.checkArchive(fs, copy.archive(), metadata, control);
      if (!archiveDirectory.same(fs.stat(cold)) || !archiveFile.same(fs.stat(copy.archive())))
        return RestorationResult.proofUnavailable("HISTORICAL_IDENTITY");
    }
    if (!clearColdWork(work, row, control, remove))
      return RestorationResult.proofUnavailable("HISTORICAL_SCRATCH");
    if (needsExpansion) {
      createColdWork(work);
      File candidate = fs.child(work, "verified");
      RuntimeRollbackArchive.unpack(fs, copy.archive(), candidate, metadata, control);
      control.report("ROLLBACK_ARCHIVE_PUBLISHING", 0, 0);
      if (!archiveDirectory.same(fs.stat(cold))
          || !archiveFile.same(fs.stat(copy.archive()))
          || !row.equals(archiveableDsh())
          || !fs.stat(raw).type.equals("MISSING")) throw new IOException("SOURCE_CHANGED");
      fs.move(candidate, raw);
      fs.syncDirectory(raw.getParentFile());
      fs.syncDirectory(work);
    }
    var published = fs.stat(raw);
    if (!row.digest().equals(digestForCleanup(raw, control)) || !published.same(fs.stat(raw)))
      throw new IOException("RUNTIME_COLD_RESTORED_TREE_CHANGED");
    try {
      markerWrite("previous-expanded");
      if (!clearColdWork(work, row, control, remove)
          || !metadata.equals(readCold(cold, row).metadata())) return expandedWithArchive(metadata);
      RuntimeRollbackArchive.checkArchive(fs, copy.archive(), metadata, control);
      control.check();
      if (!published.same(fs.stat(raw))
          || !archiveDirectory.same(fs.stat(cold))
          || !archiveFile.same(fs.stat(copy.archive()))) return expandedWithArchive(metadata);
      remove.run(directory, COLD);
      fs.syncDirectory(directory);
      if (!fs.stat(cold).type.equals("MISSING")) return expandedWithArchive(metadata);
    } catch (IOException archiveKept) {
      if (!fs.stat(cold).type.equals("MISSING")) return expandedWithArchive(metadata);
    }
    return new RestorationResult(
        metadata.logicalBytes(),
        metadata.archiveBytes(),
        needsExpansion ? "RESTORED" : "ALREADY_EXPANDED");
  }

  private static RestorationResult expandedWithArchive(RuntimeRollbackArchive.Metadata metadata) {
    return new RestorationResult(
        metadata.logicalBytes(), metadata.archiveBytes(), "RESTORED_ARCHIVE_RETAINED");
  }

  private CompactionResult compactPrevious(
      PreviousRoot selected,
      BackupControl control,
      StorageMaintenance.PayloadRemoval remove,
      java.util.function.LongSupplier usableSpace)
      throws IOException {
    if (!selected.equals(archiveableDsh()))
      return CompactionResult.proofUnavailable("HISTORICAL_SEAL");
    File raw = previousPath(selected);
    File cold = fs.child(directory, COLD);
    File work = fs.child(directory, COLD_WORK);
    if (fs.stat(work).type.equals("DIRECTORY")
        && !fs.stat(fs.child(work, "retired")).type.equals("MISSING")) {
      if (!fs.stat(raw).type.equals("MISSING"))
        return CompactionResult.proofUnavailable("HISTORICAL_IDENTITY");
      var published = readCold(cold, selected);
      return finishCompaction(work, selected, published, control, remove, true);
    }
    if (!fs.stat(raw).type.equals("DIRECTORY")) {
      if (fs.stat(raw).type.equals("MISSING") && fs.stat(cold).type.equals("DIRECTORY")) {
        var published = readCold(cold, selected);
        if (!fs.stat(work).type.equals("MISSING")) {
          RuntimeRollbackArchive.checkArchive(
              fs, published.archive(), published.metadata(), control);
          if (!clearColdWork(work, selected, control, remove))
            return CompactionResult.proofUnavailable("HISTORICAL_SCRATCH");
        }
      }
      return CompactionResult.none("NO_ELIGIBLE_COPY");
    }
    if (!selected.digest().equals(digestForCleanup(raw, control)))
      return CompactionResult.proofUnavailable("HISTORICAL_DSH_DIGEST");
    long bytes = treeBytes(fs, raw, control, 0, new int[] {0});
    if (bytes < MIN_COLD_BYTES) return CompactionResult.none("NO_ELIGIBLE_COPY");
    if (fs.stat(cold).type.equals("MISSING") && usableSpace.getAsLong() < bytes + SPACE_RESERVE)
      return CompactionResult.none("INSUFFICIENT_SPACE");
    var initial = fs.stat(raw);
    if (initial.key.isEmpty() || initial.key.equals("null"))
      return CompactionResult.proofUnavailable("HISTORICAL_IDENTITY");
    ColdCopy copy;
    if (fs.stat(cold).type.equals("MISSING")) {
      if (!clearColdWork(work, selected, control, remove))
        return CompactionResult.proofUnavailable("HISTORICAL_SCRATCH");
      createColdWork(work);
      File publish = fs.child(work, "publish");
      fs.directory(publish);
      File archive = fs.child(publish, COLD_ARCHIVE);
      var metadata = RuntimeRollbackArchive.pack(fs, raw, archive, selected.digest(), control);
      control.report("ROLLBACK_ARCHIVE_LOGICAL_SIZE", metadata.entries(), metadata.logicalBytes());
      if (metadata.logicalBytes() != bytes) throw new IOException("RUNTIME_COLD_BYTES");
      if (metadata.archiveBytes() >= bytes) {
        clearColdWork(work, selected, control, remove);
        return CompactionResult.none("NO_ELIGIBLE_COPY");
      }
      if (usableSpace.getAsLong() < metadata.logicalBytes() + SPACE_RESERVE) {
        clearColdWork(work, selected, control, remove);
        return CompactionResult.none("INSUFFICIENT_SPACE");
      }
      File restored = fs.child(work, "verified");
      writeColdProof(publish, selected, metadata);
      RuntimeRollbackArchive.unpack(fs, archive, restored, metadata, control);
      control.check();
      fs.move(publish, cold);
      fs.syncDirectory(work);
      fs.syncDirectory(directory);
      copy = readCold(cold, selected);
      if (!metadata.equals(copy.metadata())) throw new IOException("RUNTIME_COLD_PUBLICATION");
    } else {
      copy = readCold(cold, selected);
      RuntimeRollbackArchive.checkArchive(fs, copy.archive(), copy.metadata(), control);
    }
    if (!clearColdWork(work, selected, control, remove))
      return CompactionResult.proofUnavailable("HISTORICAL_SCRATCH");
    var original = fs.stat(raw);
    if (!initial.same(original)) return CompactionResult.proofUnavailable("HISTORICAL_IDENTITY");
    if (!selected.digest().equals(digestForCleanup(raw, control)))
      return CompactionResult.proofUnavailable("HISTORICAL_DSH_DIGEST");
    control.check();
    createColdWork(work);
    File retired = fs.child(work, "retired");
    writeCompactionRetirement(work, selected, copy, original);
    control.check();
    if (!original.same(fs.stat(raw))) throw new IOException("SOURCE_CHANGED");
    fs.move(raw, retired);
    fs.syncDirectory(raw.getParentFile());
    fs.syncDirectory(work);
    return finishCompaction(work, selected, copy, control, remove, false);
  }

  private void createColdWork(File work) throws IOException {
    fs.directory(work);
    try (OutputStream stream = fs.create(fs.child(work, "owned"))) {
      stream.write(
          (directory.getName() + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    fs.syncDirectory(work);
    fs.syncDirectory(directory);
  }

  private boolean ownsColdWork(File work) throws IOException {
    File owner = fs.child(work, "owned");
    return fs.stat(owner).type.equals("FILE")
        && (directory.getName() + "\n")
            .equals(new String(fs.small(owner, 128), java.nio.charset.StandardCharsets.US_ASCII));
  }

  private void writeCompactionRetirement(
      File work, PreviousRoot selected, ColdCopy archive, BackupFileSystem.Node original)
      throws IOException {
    try (OutputStream out = fs.create(fs.child(work, "retirement.json"))) {
      out.write(
          BackupJson.write(
              Map.of(
                  "operationId", directory.getName(),
                  "rootId", selected.id(),
                  "treeDigest", selected.digest(),
                  "archiveSha256", archive.metadata().archiveSha256(),
                  "device", original.device,
                  "directoryKey", original.key),
              4096));
    }
    fs.syncDirectory(work);
  }

  private CompactionResult finishCompaction(
      File work,
      PreviousRoot selected,
      ColdCopy archive,
      BackupControl control,
      StorageMaintenance.PayloadRemoval remove,
      boolean resumed)
      throws IOException {
    if (!ownsColdWork(work)) return CompactionResult.proofUnavailable("HISTORICAL_SCRATCH");
    File retired = fs.child(work, "retired");
    var node = fs.stat(retired);
    Map<String, Object> proof =
        BackupJson.read(fs.small(fs.child(work, "retirement.json"), 4096), 4096);
    if (!Set.of("owned", "retirement.json", "retired").containsAll(fs.list(work))
        || !node.type.equals("DIRECTORY")
        || !directory.getName().equals(proof.get("operationId"))
        || !selected.id().equals(proof.get("rootId"))
        || !selected.digest().equals(proof.get("treeDigest"))
        || !archive.metadata().archiveSha256().equals(proof.get("archiveSha256"))
        || !node.key.equals(proof.get("directoryKey"))
        || node.device != BackupJson.number(proof, "device"))
      return CompactionResult.proofUnavailable("HISTORICAL_IDENTITY");
    if (resumed)
      RuntimeRollbackArchive.checkRemaining(
          fs, archive.archive(), retired, archive.metadata(), control);
    control.check();
    if (!node.same(fs.stat(retired))) throw new IOException("SOURCE_CHANGED");
    remove.run(directory, COLD_WORK + "/retired");
    if (!fs.stat(retired).type.equals("MISSING"))
      throw new IOException("RUNTIME_COMPACTION_INCOMPLETE");
    fs.syncDirectory(work);
    clearColdWork(work, selected, control, remove);
    return new CompactionResult(
        1, archive.metadata().logicalBytes(), archive.metadata().archiveBytes(), "COMPACTED");
  }

  private boolean clearColdWork(
      File work,
      PreviousRoot selected,
      BackupControl control,
      StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    var workNode = fs.stat(work);
    String type = workNode.type;
    if (type.equals("MISSING")) return true;
    if (!type.equals("DIRECTORY")) return false;
    List<String> contents = fs.list(work);
    if (!contents.isEmpty()
        && (!Set.of("owned", "publish", "verified", "retirement.json").containsAll(contents)
            || !ownsColdWork(work))) return false;
    File publish = fs.child(work, "publish");
    String publication = fs.stat(publish).type;
    if (!publication.equals("MISSING")) {
      if (!publication.equals("DIRECTORY")
          || !Set.of(COLD_ARCHIVE, COLD_PROOF).containsAll(fs.list(publish))) return false;
      for (String name : fs.list(publish))
        if (!fs.stat(fs.child(publish, name)).type.equals("FILE")) return false;
    }
    File verified = fs.child(work, "verified");
    var restoredNode = fs.stat(verified);
    String restored = restoredNode.type;
    if (!restored.equals("MISSING") && !restored.equals("DIRECTORY")) return false;
    if (restored.equals("DIRECTORY")
        && !selected.digest().equals(BackupTree.digest(fs, verified, control))) {
      File published = publication.equals("DIRECTORY") ? publish : fs.child(directory, COLD);
      var archive = readCold(published, selected);
      RuntimeRollbackArchive.checkPartialRestore(
          fs, archive.archive(), verified, archive.metadata(), control);
    }
    String retirement = fs.stat(fs.child(work, "retirement.json")).type;
    if (!retirement.equals("MISSING") && !retirement.equals("FILE")) return false;
    control.check();
    if (!workNode.same(fs.stat(work)) || !restoredNode.same(fs.stat(verified)))
      throw new IOException("SOURCE_CHANGED");
    remove.run(directory, COLD_WORK);
    return fs.stat(work).type.equals("MISSING");
  }

  private static String archiveAdmission(
      BackupFileSystem fs,
      File files,
      RuntimeDescriptor expected,
      RuntimeDescriptor active,
      Map<String, Object> health)
      throws IOException {
    if (expected == null || active == null) return "ACTIVE_DESCRIPTOR_MISSING";
    if (!RuntimeDescriptor.healthy(health, active.id())) return "ACTIVE_HEALTH_INVALID";
    return pending(fs, files).isEmpty() ? "" : "PENDING_RUNTIME";
  }

  // Manual archiving and expansion change only the representation of a sealed historical DSH
  // tree. Other live component digests can change during normal maintenance. Keep the whole
  // managed proof for automatic retirement, which removes a historical copy completely.
  private static String archivalActiveProof(
      BackupFileSystem fs, File files, Map<String, Object> health, BackupControl control)
      throws IOException {
    if (!(health.get("managedHashes") instanceof Map<?, ?> hashes)
        || hashes.size() > 2048
        || !(hashes.get(DSH_TREE) instanceof String dshHash)
        || !(hashes.get(ACTIVE_DESCRIPTOR) instanceof String descriptorHash)
        || !dshHash.matches("[a-f0-9]{64}")
        || !descriptorHash.matches("[a-f0-9]{64}")) return "ACTIVE_PROOF_MAP";
    control.check();
    File descriptor = fs.child(files, ACTIVE_DESCRIPTOR);
    if (!fs.stat(descriptor).type.equals("FILE")
        || !descriptorHash.equals(digestForCleanup(fs, descriptor, control)))
      return "ACTIVE_DESCRIPTOR_DIGEST";
    File dsh = fs.child(files, DSH_TREE);
    if (!fs.stat(dsh).type.equals("DIRECTORY")
        || !dshHash.equals(digestForCleanup(fs, dsh, control))) return "ACTIVE_DSH_DIGEST";
    return "";
  }

  private static boolean liveBytesVerified(
      BackupFileSystem fs, File files, Map<String, Object> health, BackupControl control)
      throws IOException {
    if (!(health.get("managedHashes") instanceof Map<?, ?> hashes)
        || hashes.size() > 2048
        || !hashes.containsKey(DSH_TREE)) return false;
    for (var entry : hashes.entrySet()) {
      control.check();
      if (!(entry.getKey() instanceof String path)
          || !ManagedRuntimeLayout.allowed(path)
          || path.equals("linux/.runtime-health.json")
          || !(entry.getValue() instanceof String digest)
          || !digest.matches("[a-f0-9]{64}")) return false;
      File root = fs.child(files, path);
      if (path.equals(DSH_TREE) && !fs.stat(root).type.equals("DIRECTORY")) return false;
      if (!digest.equals(digestForCleanup(fs, root, control))) return false;
    }
    return true;
  }

  private void finishRetirement(BackupControl control, StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    if (mapping == null
        || marker("previous-cleaned")
        || !marker("previous-retiring")
        || !fs.stat(fs.child(directory, "previous")).type.equals("MISSING")
        || !fs.stat(fs.child(directory, COLD_WORK)).type.equals("MISSING")
        || !archiveSealMatches()) return;
    File cold = fs.child(directory, COLD);
    if (!fs.stat(cold).type.equals("MISSING")) {
      PreviousRoot dsh = dshRoot(previousRoots());
      if (dsh == null) return;
      ColdCopy copy = readCold(cold, dsh);
      RuntimeRollbackArchive.checkArchive(fs, copy.archive(), copy.metadata(), control);
      control.check();
      remove.run(directory, COLD);
      if (!fs.stat(cold).type.equals("MISSING")) return;
    }
    markerWrite("previous-cleaned");
  }

  private void retirePrevious(BackupControl control, StorageMaintenance.PayloadRemoval remove)
      throws IOException {
    if (verifiedPrevious(control, true) == null) return;
    if (!fs.stat(fs.child(directory, COLD_WORK)).type.equals("MISSING")) return;
    File cold = fs.child(directory, COLD);
    if (!fs.stat(cold).type.equals("MISSING")) {
      try {
        PreviousRoot dsh = dshRoot(previousRoots());
        if (dsh == null) return;
        ColdCopy copy = readCold(cold, dsh);
        RuntimeRollbackArchive.checkArchive(fs, copy.archive(), copy.metadata(), control);
        File raw = previousPath(dsh);
        if (!fs.stat(raw).type.equals("MISSING")
            && !dsh.digest().equals(digestForCleanup(raw, control))) return;
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException unavailable) {
        return;
      }
    }
    control.check();
    markerWrite("previous-retiring");
    remove.run(directory, "previous");
    if (!fs.stat(cold).type.equals("MISSING")) remove.run(directory, COLD);
    if (!fs.stat(fs.child(directory, "previous")).type.equals("MISSING"))
      throw new IOException("RUNTIME_RETIREMENT_INCOMPLETE");
    if (!fs.stat(cold).type.equals("MISSING"))
      throw new IOException("RUNTIME_RETIREMENT_INCOMPLETE");
    markerWrite("previous-cleaned");
  }

  /** 只删除 create() 明确登记且仍为空的失败占位；部分 stage 一律保留。 */
  private static void pruneEmptyPlaceholders(BackupFileSystem fs, File home) throws IOException {
    if (!fs.stat(home).type.equals("DIRECTORY")) return;
    for (String id : fs.list(home)) {
      if (id.equals(COMPLETED)) continue;
      if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
        throw new IOException("RUNTIME_OPERATION_ID");
      File directory = fs.child(home, id);
      if (!fs.stat(directory).type.equals("DIRECTORY"))
        throw new IOException("RUNTIME_OPERATION_DIRECTORY");
      File marker = fs.child(directory, "created");
      var markerNode = fs.stat(marker);
      if (markerNode.type.equals("MISSING")) continue;
      if (!markerNode.type.equals("FILE")
          || !(id + "\ncreated\n")
              .equals(new String(fs.small(marker, 256), java.nio.charset.StandardCharsets.UTF_8)))
        continue;
      if (!fs.stat(fs.child(directory, "runtime-mapping.json")).type.equals("MISSING")) continue;
      List<String> children = fs.list(directory);
      if (!new HashSet<>(children).equals(Set.of("candidate", "stage", "created"))) continue;
      if (emptyDirectory(fs, fs.child(directory, "candidate"))
          && emptyDirectory(fs, fs.child(directory, "stage"))) fs.removeOwned(home, id);
    }
  }

  private static boolean emptyDirectory(BackupFileSystem fs, File directory) throws IOException {
    String type = fs.stat(directory).type;
    return type.equals("MISSING") || type.equals("DIRECTORY") && fs.list(directory).isEmpty();
  }

  private boolean finalizedWithoutPrevious() throws IOException {
    if (mapping == null
        || !marker("finalized")
        || marker("rolled-back")
        || marker("previous-cleaned")) return false;
    if (!object(mapping, "previousDescriptor").isEmpty()) return false;
    return emptyDirectory(fs, fs.child(directory, "previous"));
  }

  private record PreviousRoot(String id, String digest, boolean had) {}

  private PreviousRoot dshRoot(List<PreviousRoot> rows) {
    for (var row : rows) if (DSH_TREE.equals(paths.get(row.id())) && row.had()) return row;
    return null;
  }

  private List<PreviousRoot> previousRoots() throws IOException {
    Map<String, Object> plan =
        BackupJson.read(
            fs.small(fs.child(directory, "plan.json"), BackupLimits.MANIFEST),
            BackupLimits.MANIFEST);
    if (BackupJson.number(plan, "version") != 1
        || !directory.getName().equals(plan.get("id"))
        || !(plan.get("roots") instanceof List)) throw new IOException("RUNTIME_PLAN");
    List<?> rows = (List<?>) plan.get("roots");
    if (rows.size() != paths.size()) throw new IOException("RUNTIME_PLAN");
    Set<String> checked = new HashSet<>();
    List<PreviousRoot> result = new ArrayList<>();
    for (Object value : rows) {
      if (!(value instanceof Map<?, ?> row)
          || !(row.get("root") instanceof String id)
          || !paths.containsKey(id)
          || !checked.add(id)
          || !(row.get("had") instanceof Boolean had)
          || !(row.get("before") instanceof String digest)
          || !digest.matches("[a-f0-9]{64}")) throw new IOException("RUNTIME_PLAN");
      result.add(new PreviousRoot(id, digest, had));
    }
    return result;
  }

  private File previousPath(PreviousRoot row) throws IOException {
    return fs.child(directory, "previous/" + row.id());
  }

  private boolean retainedBytesUnchanged(BackupControl control, boolean cleanup)
      throws IOException {
    File previous = fs.child(directory, "previous");
    if (!fs.stat(previous).type.equals("DIRECTORY")) return false;
    // 清单外的文件可能是用户唯一原件，不能随整个 previous 目录一起删除。
    if (!paths.keySet().containsAll(fs.list(previous))) return false;
    try {
      for (var row : previousRoots()) verifyRetainedRoot(row, previousPath(row), control, cleanup);
      return true;
    } catch (InterruptedIOException cancelled) {
      throw cancelled;
    } catch (IOException unavailable) {
      return false;
    }
  }

  private ColdCopy verifyRetainedRoot(
      PreviousRoot row, File raw, BackupControl control, boolean cleanup) throws IOException {
    control.check();
    boolean present = !fs.stat(raw).type.equals("MISSING");
    if (row.had() == present) {
      try {
        String digest =
            cleanup ? digestForCleanup(raw, control) : BackupTree.digest(fs, raw, control);
        if (row.digest().equals(digest)) return null;
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException unreadable) {
        if (!DSH_TREE.equals(paths.get(row.id()))) throw unreadable;
      }
    }
    File cold = fs.child(directory, COLD);
    if (!row.had()
        || !DSH_TREE.equals(paths.get(row.id()))
        || BackupJson.number(mapping, "version") != 2
        || !fs.stat(cold).type.equals("DIRECTORY"))
      throw new IOException("RETAINED_RUNTIME_CHANGED");
    ColdCopy copy = readCold(cold, row);
    RuntimeRollbackArchive.checkArchive(fs, copy.archive(), copy.metadata(), control);
    return copy;
  }

  private String digestForCleanup(File root, BackupControl control) throws IOException {
    return digestForCleanup(fs, root, control);
  }

  private static String digestForCleanup(BackupFileSystem fs, File root, BackupControl control)
      throws IOException {
    long[] reported = {0, 0};
    return BackupTree.digest(
        fs,
        root,
        control,
        (files, bytes) -> {
          if (files == reported[0] && bytes == reported[1]) return;
          control.report("RUNTIME_VERIFY_READ", files - reported[0], bytes - reported[1]);
          reported[0] = files;
          reported[1] = bytes;
        });
  }

  private record ColdCopy(File archive, RuntimeRollbackArchive.Metadata metadata) {}

  private ColdCopy readCold(File parent, PreviousRoot row) throws IOException {
    if (!fs.stat(parent).type.equals("DIRECTORY")
        || !new HashSet<>(fs.list(parent)).equals(Set.of(COLD_ARCHIVE, COLD_PROOF)))
      throw new IOException("RUNTIME_COLD_LAYOUT");
    File archive = fs.child(parent, COLD_ARCHIVE);
    var node = fs.stat(archive);
    Map<String, Object> proof = BackupJson.read(fs.small(fs.child(parent, COLD_PROOF), 4096), 4096);
    long archiveBytes = BackupJson.number(proof, "archiveBytes");
    long entries = BackupJson.number(proof, "entries");
    long logicalBytes = BackupJson.number(proof, "logicalBytes");
    String digest = BackupJson.string(proof, "archiveSha256");
    if (BackupJson.number(proof, "version") != 1
        || !directory.getName().equals(proof.get("operationId"))
        || !row.id().equals(proof.get("rootId"))
        || !row.digest().equals(proof.get("treeDigest"))
        || !recordHash("plan.json").equals(proof.get("planSha256"))
        || !recordHash("runtime-mapping.json").equals(proof.get("mappingSha256"))
        || !node.type.equals("FILE")
        || node.size != archiveBytes
        || archiveBytes <= 0
        || archiveBytes >= logicalBytes
        || logicalBytes > BackupLimits.BYTES
        || entries <= 0
        || entries > BackupLimits.ENTRIES
        || !digest.matches("[a-f0-9]{64}")) throw new IOException("RUNTIME_COLD_PROOF");
    return new ColdCopy(
        archive,
        new RuntimeRollbackArchive.Metadata(
            digest, archiveBytes, entries, logicalBytes, row.digest()));
  }

  private void writeColdProof(
      File publish, PreviousRoot row, RuntimeRollbackArchive.Metadata metadata) throws IOException {
    Map<String, Object> proof =
        Map.of(
            "version", 1L,
            "operationId", directory.getName(),
            "rootId", row.id(),
            "treeDigest", row.digest(),
            "mappingSha256", recordHash("runtime-mapping.json"),
            "planSha256", recordHash("plan.json"),
            "archiveSha256", metadata.archiveSha256(),
            "archiveBytes", metadata.archiveBytes(),
            "entries", metadata.entries(),
            "logicalBytes", metadata.logicalBytes());
    try (OutputStream stream = fs.create(fs.child(publish, COLD_PROOF))) {
      stream.write(BackupJson.write(proof, 4096));
    }
    fs.syncDirectory(publish);
  }
}
