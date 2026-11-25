package com.deepseekharness.app.backup;

import android.content.Context;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** On-demand storage evidence. Only the maintenance transactions can authorize disposal. */
public final class RollbackStorageInventory {
  private static final int MAX_OPERATIONS = 128;
  private static final long MAX_ENTRIES = 300_000;
  private static final long MAX_METADATA = 8L * 1024 * 1024;
  private static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(8);
  private static final long SINGLE_ENTRIES = 1_000_000;
  private static final long SINGLE_NANOS = TimeUnit.SECONDS.toNanos(30);
  private static final String COMPLETED = "completed";
  private static final String DESCRIPTOR = ".runtime-descriptor.json";
  private static final String IDENTITY = ".offline-identity";
  private static final String MANAGED_DSH = ManagedRuntimeLayout.ROOT + ManagedRuntimeLayout.DSH;
  private static final String COLD_ARCHIVE = "cold/archive.bin";
  private static final Set<String> RUNTIME_PAYLOADS =
      Set.of("previous", "failed", "candidate", "stage", COLD_ARCHIVE, "cold-work");
  private static final Set<String> RUNTIME_JOURNAL =
      Set.of(
          "created",
          "candidate",
          "stage",
          "runtime-mapping.json",
          "plan.json",
          "archive-seal.json",
          "verified",
          "switching",
          "files-committed",
          "settings-committed",
          "health.json",
          "finalized",
          "previous",
          "failed",
          "cold",
          "cold-work",
          "previous-retiring",
          "previous-cleaned",
          "previous-expanded",
          "rolling-back",
          "rolled-back");
  private static final Set<String> ENVIRONMENT_PAYLOADS =
      Set.of("previous-linux", "failed-linux", "data.dshdata");
  private static final Set<String> RUNTIME_MARKERS =
      Set.of(
          "ubuntu/root/.dsha-web.pid",
          "ubuntu/root/.dsha-web.pid.stale",
          "ubuntu/root/.dsha-web.identity",
          "ubuntu/root/.dsha-stopped",
          "ubuntu/root/.dsh/.plugins.lock");

  public enum Kind {
    RUNTIME,
    COMPRESSED_RUNTIME,
    ENVIRONMENT,
    FAILED_RUNTIME,
    FAILED_ENVIRONMENT,
    REBUILD_BACKUP
  }

  public enum State {
    RETAINED_HISTORY,
    ARCHIVE_PROOF_ONLY,
    PROOF_PENDING,
    NO_PROOF,
    PENDING_OPERATION,
    FAILED_ARTIFACT,
    UNRECOGNIZED
  }

  /** APK version is the code from the installation marker, when that marker is available. */
  public record Identity(
      String runtimeId, String dshVersion, String baseVersion, String apkVersion) {
    private static final Identity UNKNOWN = new Identity("", "", "", "");
  }

  /** Exact history operation and sealed archive hash for one explicit expansion request. */
  public record ExpansionTarget(String operationId, String archiveSha256) {}

  /** Allocation covers regular files only and de-duplicates inodes within this copy. */
  public record Copy(
      Kind kind,
      String relativePath,
      Identity identity,
      State state,
      long logicalBytes,
      long allocatedBytes,
      boolean incomplete,
      boolean allocationComplete,
      int multiLinkedFiles,
      long managedDshLogicalBytes,
      int runtimeMarkerFiles,
      long runtimeMarkerBytes,
      boolean replacedByActive,
      long compressedOriginalBytes,
      boolean compressedProofRecorded,
      boolean runtimeHealthRecorded,
      ExpansionTarget expansionTarget) {
    public String expandOperationId() {
      return expansionTarget == null ? "" : expansionTarget.operationId;
    }
  }

  /** Distinct allocation covers all listed artifacts and counts their common inodes once. */
  public record Report(
      Identity active,
      List<Copy> copies,
      boolean incomplete,
      long entriesScanned,
      long distinctAllocatedBytes,
      boolean allocationComplete,
      int multiLinkedFiles,
      boolean pendingOperation,
      boolean activeHealthRecorded,
      int runtimeOperations,
      int environmentOperations,
      long operationMetadataBytes,
      long elapsedMillis) {}

  private RollbackStorageInventory() {}

  public static Report inspect(Context context, BooleanSupplier cancelled) throws IOException {
    return inspect(context, cancelled, null);
  }

  /** Publishes known copy rows before traversing large trees, then updates as sizes are measured. */
  public static Report inspect(
      Context context, BooleanSupplier cancelled, Consumer<Report> progress) throws IOException {
    return androidScan(context, cancelled, MAX_ENTRIES, MAX_NANOS).run(progress);
  }

  public static Copy inspectCopy(Context context, String relativePath, BooleanSupplier cancelled)
      throws IOException {
    return androidScan(context, cancelled, SINGLE_ENTRIES, SINGLE_NANOS).runCopy(relativePath);
  }

  private static Scan androidScan(Context context, BooleanSupplier cancelled, long limit, long time)
      throws IOException {
    File files = context.getFilesDir().getCanonicalFile();
    var fs = new AndroidBackupFileSystem();
    var root = fs.stat(files);
    if (!root.type.equals("DIRECTORY")) throw new IOException("STORAGE_VOLUME_UNAVAILABLE");
    return new Scan(fs, files, root.device, cancelled, limit, time);
  }

  static Report inspect(
      BackupFileSystem fs, File files, long device, BooleanSupplier cancelled, long entryLimit) {
    return inspect(fs, files, device, cancelled, entryLimit, null);
  }

  static Report inspect(
      BackupFileSystem fs,
      File files,
      long device,
      BooleanSupplier cancelled,
      long entryLimit,
      Consumer<Report> progress) {
    return new Scan(fs, files, device, cancelled, entryLimit, MAX_NANOS).run(progress);
  }

  static Copy inspectCopy(
      BackupFileSystem fs, File files, long device, BooleanSupplier cancelled, String relativePath)
      throws IOException {
    return new Scan(fs, files, device, cancelled, SINGLE_ENTRIES, SINGLE_NANOS)
        .runCopy(relativePath);
  }

  private record Operation(File directory, boolean runtime, boolean duplicate) {}

  private record RuntimeSeal(String mappingHash, String planHash, String dshDigest) {}

  private record ColdProof(long originalBytes, String archiveSha256) {
    private static final ColdProof UNKNOWN = new ColdProof(-1, "");

    boolean recorded() {
      return !archiveSha256.isEmpty();
    }
  }

  private record Artifact(File file, Copy row, String managedRoot, BackupFileSystem.Node node) {}

  private static final class Usage {
    final Set<String> linked = new HashSet<>();
    final String managedRoot;
    final boolean environment;
    final Set<String> excludedRoots;
    long logical, allocated, managedDsh, markerBytes;
    int markerFiles;
    boolean incomplete, allocationComplete = true;

    Usage(String managedRoot, boolean environment, Set<String> excludedRoots) {
      this.managedRoot = managedRoot;
      this.environment = environment;
      this.excludedRoots = excludedRoots;
    }
  }

  private static final class Scan {
    final BackupFileSystem fs;
    final File files;
    final long device, entryLimit, timeLimit;
    final BooleanSupplier cancelled;
    final long started = System.nanoTime();
    long phaseStarted = started;
    final List<Artifact> artifacts = new ArrayList<>();
    final List<Copy> copies = new ArrayList<>();
    final Set<String> linked = new HashSet<>();
    long entries, metadataBytes, distinctAllocated, operationMetadataBytes;
    boolean incomplete, allocationComplete = true, pendingOperation;
    Identity active = Identity.UNKNOWN;

    Scan(
        BackupFileSystem fs,
        File files,
        long device,
        BooleanSupplier cancelled,
        long entryLimit,
        long timeLimit) {
      this.fs = fs;
      this.files = files;
      this.device = device;
      this.cancelled = cancelled;
      this.entryLimit = entryLimit;
      this.timeLimit = timeLimit;
    }

    Report run(Consumer<Report> progress) {
      boolean activeHealth = false;
      try {
        File linux = new File(files, "linux");
        active = readIdentity(linux);
        File health = new File(files, "runtime-health");
        if (!active.runtimeId.isEmpty() && directory(health)) {
          Map<String, Object> receipt =
              json(new File(health, active.runtimeId + ".json"), 512 * 1024);
          activeHealth = receipt != null && RuntimeDescriptor.healthy(receipt, active.runtimeId);
        }
      } catch (IOException unavailable) {
        incomplete = true;
      }
      List<Operation> operations = new ArrayList<>();
      locate(PrivateRoots.RUNTIMES, true, operations);
      int runtimeOperations = operations.size();
      locate(PrivateRoots.ENVIRONMENTS, false, operations);
      // Inspect status records before walking any potentially large tree.
      for (Operation op : operations) {
        if (stop()) break;
        if (op.duplicate) incomplete = true;
        try {
          boolean committed = marked(op, op.runtime ? "finalized" : "committed");
          boolean rolledBack = marked(op, "rolled-back");
          boolean switching = marked(op, "switching");
          if ((switching && !committed && !rolledBack)
              || (op.runtime
                  && marked(op, "previous-retiring")
                  && !marked(op, "previous-cleaned")
                  && (directory(new File(op.directory, "previous"))
                      || directory(new File(op.directory, "cold"))))) pendingOperation = true;
        } catch (IOException unavailable) {
          incomplete = true;
        }
      }
      for (Operation op : operations) {
        if (stop()) break;
        if (op.runtime) runtime(op);
        else environment(op);
      }
      artifacts.sort(
          Comparator.comparing((Artifact artifact) -> artifact.row.kind)
              .thenComparing(artifact -> artifact.row.relativePath));
      for (Artifact artifact : artifacts) copies.add(artifact.row);
      int environmentOperations = operations.size() - runtimeOperations;
      if (progress != null)
        progress.accept(snapshot(activeHealth, runtimeOperations, environmentOperations, false));
      phaseStarted = System.nanoTime();
      for (int i = 0; i < artifacts.size(); i++) {
        if (stop()) break;
        copies.set(i, measure(artifacts.get(i)));
        if (progress != null)
          progress.accept(snapshot(activeHealth, runtimeOperations, environmentOperations, false));
      }
      for (Operation op : operations) {
        if (stop()) break;
        measureJournal(op);
      }
      return snapshot(activeHealth, runtimeOperations, environmentOperations, true);
    }

    private Report snapshot(
        boolean activeHealth, int runtimeOperations, int environmentOperations, boolean finished) {
      boolean partial = incomplete || !finished;
      return new Report(
          active,
          List.copyOf(copies),
          partial,
          entries,
          distinctAllocated,
          !partial && allocationComplete,
          linked.size(),
          pendingOperation,
          activeHealth,
          runtimeOperations,
          environmentOperations,
          operationMetadataBytes,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    Copy runCopy(String relativePath) throws IOException {
      BackupLimits.path(relativePath);
      String[] segments = relativePath.split("/", -1);
      if (segments.length < 3) throw new IOException("STORAGE_COPY_PATH");
      boolean runtime = segments[0].equals(PrivateRoots.RUNTIMES);
      if (!runtime && !segments[0].equals(PrivateRoots.ENVIRONMENTS))
        throw new IOException("STORAGE_COPY_PATH");
      boolean completed = segments[1].equals(COMPLETED);
      int at = completed ? 2 : 1;
      if (segments.length <= at + 1 || !segments[at].matches(Ids.UUID_PATTERN))
        throw new IOException("STORAGE_COPY_PATH");
      String payload =
          String.join("/", java.util.Arrays.copyOfRange(segments, at + 1, segments.length));
      if (!(runtime ? RUNTIME_PAYLOADS : ENVIRONMENT_PAYLOADS).contains(payload))
        throw new IOException("STORAGE_COPY_PATH");
      File home = new File(files, segments[0]);
      if (!directory(home)) throw new IOException("STORAGE_COPY_MISSING");
      File history = new File(home, COMPLETED);
      boolean hasHistory = directory(history);
      File activeDirectory = new File(home, segments[at]);
      File historicalDirectory = new File(history, segments[at]);
      boolean hasActive = directory(activeDirectory);
      boolean hasHistorical = hasHistory && directory(historicalDirectory);
      if (completed ? !hasHistorical : !hasActive) throw new IOException("STORAGE_COPY_MISSING");
      var op =
          new Operation(
              completed ? historicalDirectory : activeDirectory,
              runtime,
              hasActive && hasHistorical);
      try {
        active = readIdentity(new File(files, "linux"));
      } catch (IOException unavailable) {
        incomplete = true;
      }
      if (runtime) runtime(op);
      else environment(op);
      for (Artifact artifact : artifacts)
        if (artifact.row.relativePath.equals(relativePath)) return measure(artifact);
      throw new IOException("STORAGE_COPY_MISSING");
    }

    private void locate(String name, boolean runtime, List<Operation> operations) {
      if (stop()) return;
      File root = new File(files, name);
      try {
        if (!directory(root)) return;
        File history = new File(root, COMPLETED);
        List<String> current = fs.list(root);
        List<String> completed = directory(history) ? fs.list(history) : List.of();
        Map<String, Boolean> ids = new HashMap<>();
        for (File parent : List.of(root, history)) {
          for (String id : (parent.equals(root) ? current : completed)) {
            if (stop()) return;
            if (parent.equals(root) && id.equals(COMPLETED)) continue;
            if (!id.matches(Ids.UUID_PATTERN)) {
              incomplete = true;
              continue;
            }
            if (operations.size() + ids.size() >= MAX_OPERATIONS && !ids.containsKey(id)) {
              incomplete = true;
              break;
            }
            ids.merge(id, false, (previous, unused) -> true);
          }
        }
        // Preserve directory order for repeatable partial scans.
        for (File parent : List.of(root, history)) {
          for (String id : (parent.equals(root) ? current : completed)) {
            if (stop()) return;
            if (!ids.containsKey(id)) continue;
            if (operations.size() >= MAX_OPERATIONS) {
              incomplete = true;
              return;
            }
            File op = new File(parent, id);
            try {
              if (directory(op)) operations.add(new Operation(op, runtime, ids.get(id)));
              else incomplete = true;
            } catch (IOException unavailable) {
              incomplete = true;
            }
          }
        }
      } catch (IOException unavailable) {
        incomplete = true;
      }
    }

    private void runtime(Operation op) {
      File previous = new File(op.directory, "previous");
      File cold = new File(op.directory, "cold");
      File coldWork = new File(op.directory, "cold-work");
      Identity identity = Identity.UNKNOWN;
      State state = State.UNRECOGNIZED;
      State coldState = State.UNRECOGNIZED;
      ColdProof coldProof = ColdProof.UNKNOWN;
      ExpansionTarget expansionTarget = null;
      boolean hasCold = false;
      boolean hasColdWork = false;
      boolean runtimeHealthRecorded = false;
      boolean replacedByActive = false;
      String managedDsh = "";
      try {
        hasCold = directory(cold);
        hasColdWork = directory(coldWork);
        pendingOperation |= hasColdWork;
        boolean hasPrevious = directory(previous);
        if (hasPrevious || hasCold) {
          byte[] mappingBytes =
              bytes(new File(op.directory, "runtime-mapping.json"), BackupLimits.MANIFEST);
          Map<String, Object> mapping =
              mappingBytes == null ? null : BackupJson.read(mappingBytes, BackupLimits.MANIFEST);
          if (mapping != null && op.directory.getName().equals(mapping.get("id"))) {
            long version = BackupJson.number(mapping, "version");
            if (version != 1 && version != 2) throw new IOException("RUNTIME_MAPPING");
            var newer = descriptor(object(mapping, "candidateDescriptor"));
            if (newer == null) throw new IOException("RUNTIME_MAPPING");
            Map<String, Object> paths = object(mapping, "paths");
            if (BackupJson.number(mapping, "createdAt") <= 0 || !mappingPaths(paths))
              throw new IOException("RUNTIME_MAPPING");
            String marker = mappedRoot(paths, "linux/" + IDENTITY);
            managedDsh = mappedRoot(paths, MANAGED_DSH);
            RuntimeDescriptor old = null;
            String installMarker = "";
            try {
              old = descriptor(object(mapping, "previousDescriptor"));
              if (!marker.isEmpty() && hasPrevious)
                installMarker = text(new File(previous, marker));
            } catch (IOException unavailable) {
              incomplete = true;
            }
            identity = identity(old, installMarker);
            replacedByActive = newer.id().equals(active.runtimeId);
            boolean committed = marked(op, "finalized");
            boolean rolledBack = marked(op, "rolled-back");
            boolean retired = marked(op, "previous-cleaned");
            boolean retiring = marked(op, "previous-retiring");
            RuntimeSeal seal =
                version == 2 && committed && !rolledBack
                    ? runtimeSeal(op, mappingBytes, paths.keySet(), managedDsh)
                    : null;
            if (old != null
                && mapping.get("previousHealth") instanceof Map<?, ?>
                && committed
                && !rolledBack
                && !op.duplicate
                && (version == 1 || seal != null))
              runtimeHealthRecorded =
                  RuntimeDescriptor.healthy(object(mapping, "previousHealth"), old.id());
            if (op.duplicate || retired) state = State.UNRECOGNIZED;
            else if (retiring || !committed && !rolledBack) state = State.PENDING_OPERATION;
            else if (rolledBack) state = State.FAILED_ARTIFACT;
            else if (!runtimeHealthRecorded) state = State.NO_PROOF;
            else state = State.RETAINED_HISTORY;
            if (hasCold) {
              coldProof = coldProof(op, cold, seal, managedDsh);
              coldState =
                  state == State.RETAINED_HISTORY && !coldProof.recorded() ? State.NO_PROOF : state;
              if (coldProof.recorded() && !op.duplicate && !retired && !retiring) {
                coldState =
                    runtimeHealthRecorded ? State.RETAINED_HISTORY : State.ARCHIVE_PROOF_ONLY;
                var raw = hasPrevious ? stat(new File(previous, managedDsh)) : null;
                boolean rawAbsent = raw != null && raw.type.equals("MISSING");
                boolean rawDirectory = raw != null && raw.type.equals("DIRECTORY");
                boolean restored = rawDirectory && marked(op, "previous-expanded");
                // A completed rename leaves only the operation's owner record before the marker.
                boolean rawWithoutMarker = rawDirectory && !restored && hasColdWork;
                if (hasColdWork || !rawAbsent) {
                  pendingOperation = true;
                  coldState = State.PENDING_OPERATION;
                  if (!rawAbsent && !restored) state = State.PENDING_OPERATION;
                }
                if ((rawAbsent || restored || rawWithoutMarker)
                    && paths.keySet().containsAll(fs.list(previous))
                    && (!hasColdWork || retryableColdWork(op, coldWork, rawWithoutMarker)))
                  expansionTarget =
                      new ExpansionTarget(op.directory.getName(), coldProof.archiveSha256);
              }
            }
          } else if (!op.duplicate && marked(op, "switching") && !marked(op, "rolled-back")) {
            state = State.PENDING_OPERATION;
          }
        }
      } catch (IOException malformed) {
        incomplete = true;
      }
      describe(
          previous,
          Kind.RUNTIME,
          identity,
          state,
          managedDsh,
          replacedByActive,
          ColdProof.UNKNOWN,
          runtimeHealthRecorded,
          null);
      if (hasCold)
        describe(
            new File(op.directory, COLD_ARCHIVE),
            Kind.COMPRESSED_RUNTIME,
            identity,
            coldState,
            "",
            replacedByActive,
            coldProof,
            runtimeHealthRecorded,
            expansionTarget);
      if (hasColdWork)
        describe(
            coldWork, Kind.FAILED_RUNTIME, Identity.UNKNOWN, State.PENDING_OPERATION, "", false);
      for (String remainder : List.of("failed", "candidate", "stage")) {
        if (stop()) break;
        describe(
            new File(op.directory, remainder),
            Kind.FAILED_RUNTIME,
            Identity.UNKNOWN,
            state == State.PENDING_OPERATION ? State.PENDING_OPERATION : State.FAILED_ARTIFACT,
            "",
            false);
      }
    }

    private RuntimeSeal runtimeSeal(Operation op, byte[] mapping, Set<String> paths, String dsh)
        throws IOException {
      var seal = json(new File(op.directory, "archive-seal.json"), 4096);
      String mappingHash = sha(mapping);
      if (seal == null
          || BackupJson.number(seal, "version") != 1
          || !op.directory.getName().equals(seal.get("id"))
          || !mappingHash.equals(seal.get("mapping"))
          || !RUNTIME_JOURNAL.containsAll(fs.list(op.directory))) return null;
      byte[] plan = bytes(new File(op.directory, "plan.json"), BackupLimits.MANIFEST);
      if (plan == null) return null;
      String planHash = sha(plan);
      if (!planHash.equals(seal.get("plan"))) return null;
      var parsed = BackupJson.read(plan, BackupLimits.MANIFEST);
      if (BackupJson.number(parsed, "version") != 1
          || !op.directory.getName().equals(parsed.get("id"))
          || !(parsed.get("roots") instanceof List<?> rows)
          || rows.size() != paths.size()) return null;
      Set<String> checked = new HashSet<>();
      String dshDigest = "";
      for (Object object : rows) {
        if (!(object instanceof Map<?, ?> row)
            || !(row.get("root") instanceof String root)
            || !paths.contains(root)
            || !checked.add(root)
            || !(row.get("had") instanceof Boolean)
            || !(row.get("before") instanceof String before)
            || !before.matches("[a-f0-9]{64}")) return null;
        if (root.equals(dsh) && Boolean.TRUE.equals(row.get("had"))) dshDigest = before;
      }
      return new RuntimeSeal(mappingHash, planHash, dshDigest);
    }

    private ColdProof coldProof(Operation op, File cold, RuntimeSeal seal, String dsh)
        throws IOException {
      if (seal == null || dsh.isEmpty() || seal.dshDigest.isEmpty()) return ColdProof.UNKNOWN;
      if (!Set.copyOf(fs.list(cold)).equals(Set.of("archive.bin", "proof.json")))
        return ColdProof.UNKNOWN;
      var proof = json(new File(cold, "proof.json"), 4096);
      if (proof == null
          || BackupJson.number(proof, "version") != 1
          || !op.directory.getName().equals(proof.get("operationId"))
          || !dsh.equals(proof.get("rootId"))
          || !seal.planHash.equals(proof.get("planSha256"))
          || !seal.mappingHash.equals(proof.get("mappingSha256"))
          || !seal.dshDigest.equals(proof.get("treeDigest"))) return ColdProof.UNKNOWN;
      String digest = BackupJson.string(proof, "archiveSha256");
      var archive = stat(new File(op.directory, COLD_ARCHIVE));
      long original = BackupJson.number(proof, "logicalBytes");
      long entries = BackupJson.number(proof, "entries");
      long size = BackupJson.number(proof, "archiveBytes");
      if (!archive.type.equals("FILE")
          || size != archive.size
          || size <= 0
          || original <= size
          || original > BackupLimits.BYTES
          || entries < 1
          || entries > BackupLimits.ENTRIES
          || !digest.matches("[a-f0-9]{64}")) return ColdProof.UNKNOWN;
      return new ColdProof(original, digest);
    }

    private boolean retryableColdWork(Operation op, File work, boolean rawWithoutMarker)
        throws IOException {
      List<String> names = fs.list(work);
      if (rawWithoutMarker && !names.equals(List.of("owned"))) return false;
      if (names.isEmpty()) return true;
      if (!Set.of("owned", "verified").containsAll(names)) return false;
      byte[] owner = bytes(new File(work, "owned"), 128);
      return owner != null
          && (op.directory.getName() + "\n").equals(new String(owner, StandardCharsets.US_ASCII))
          && (!names.contains("verified") || directory(new File(work, "verified")));
    }

    private void environment(Operation op) {
      File previous = new File(op.directory, "previous-linux");
      File failed = new File(op.directory, "failed-linux");
      State state = State.UNRECOGNIZED;
      Identity identity = Identity.UNKNOWN;
      try {
        if (directory(previous)) {
          identity = readIdentity(previous);
          boolean committed = marked(op, "committed");
          boolean rolledBack = marked(op, "rolled-back");
          if (op.duplicate || marked(op, "old-environment-cleaned")) state = State.UNRECOGNIZED;
          else if (!committed && !rolledBack) state = State.PENDING_OPERATION;
          else if (rolledBack) state = State.FAILED_ARTIFACT;
          else if (!marked(op, "data-restored") || !environmentProof(op, previous))
            state = State.NO_PROOF;
          else state = State.PROOF_PENDING;
        }
      } catch (IOException malformed) {
        incomplete = true;
      }
      describe(previous, Kind.ENVIRONMENT, identity, state, "", false);
      try {
        identity = readIdentity(failed);
      } catch (IOException malformed) {
        incomplete = true;
        identity = Identity.UNKNOWN;
      }
      describe(failed, Kind.FAILED_ENVIRONMENT, identity, State.FAILED_ARTIFACT, "", false);
      describe(
          new File(op.directory, "data.dshdata"),
          Kind.REBUILD_BACKUP,
          Identity.UNKNOWN,
          State.NO_PROOF,
          "",
          false);
    }

    private boolean environmentProof(Operation op, File previous) throws IOException {
      var proof = json(new File(op.directory, "retired-proof.json"), 4096);
      var intent = json(new File(op.directory, "intent.json"), 4096);
      if (proof == null
          || intent == null
          || BackupJson.number(proof, "version") != 1
          || BackupJson.number(intent, "version") != 1
          || !Boolean.TRUE.equals(intent.get("had"))
          || !op.directory.getName().equals(proof.get("id"))
          || !op.directory.getName().equals(intent.get("id"))
          || !stat(previous).key.equals(proof.get("oldKey"))
          || !stat(previous).key.equals(intent.get("oldKey"))) return false;
      for (String key : List.of("archive", "mapping")) {
        String digest = BackupJson.string(proof, key);
        if (!digest.equals(intent.get(key)) || !digest.matches("[a-f0-9]{64}")) return false;
      }
      return BackupJson.string(proof, "digest").matches("[a-f0-9]{64}")
          && stat(new File(op.directory, "data.dshdata")).type.equals("FILE")
          && stat(new File(op.directory, "data-roots.json")).type.equals("FILE");
    }

    private Identity readIdentity(File linux) throws IOException {
      if (!directory(linux)) return Identity.UNKNOWN;
      return identity(
          descriptor(json(new File(linux, DESCRIPTOR), BackupLimits.MANIFEST)),
          text(new File(linux, IDENTITY)));
    }

    private void describe(
        File root,
        Kind kind,
        Identity identity,
        State state,
        String managed,
        boolean replacedByActive) {
      describe(
          root, kind, identity, state, managed, replacedByActive, ColdProof.UNKNOWN, false, null);
    }

    private void describe(
        File root,
        Kind kind,
        Identity identity,
        State state,
        String managed,
        boolean replacedByActive,
        ColdProof coldProof,
        boolean runtimeHealthRecorded,
        ExpansionTarget expansionTarget) {
      if (stop()) return;
      BackupFileSystem.Node node = null;
      try {
        node = stat(root);
        if (node.type.equals("MISSING")) return;
        if (kind == Kind.REBUILD_BACKUP || kind == Kind.COMPRESSED_RUNTIME
            ? !node.type.equals("FILE")
            : !node.type.equals("DIRECTORY")) {
          incomplete = true;
          return;
        }
        if (kind == Kind.FAILED_RUNTIME
            && !root.getName().equals("cold-work")
            && fs.list(root).isEmpty()) return;
      } catch (IOException unavailable) {
        incomplete = true;
      }
      String path =
          files.toPath().relativize(root.toPath()).toString().replace(File.separatorChar, '/');
      var preview =
          new Copy(
              kind,
              path,
              identity,
              state,
              -1,
              -1,
              true,
              false,
              0,
              -1,
              0,
              0,
              replacedByActive,
              coldProof.originalBytes,
              coldProof.recorded(),
              runtimeHealthRecorded,
              node == null ? null : expansionTarget);
      artifacts.add(new Artifact(root, preview, managed, node));
    }

    private Copy measure(Artifact artifact) {
      Copy row = artifact.row;
      var usage =
          new Usage(
              artifact.managedRoot,
              row.kind == Kind.ENVIRONMENT || row.kind == Kind.FAILED_ENVIRONMENT,
              Set.of());
      try {
        if (artifact.node == null || !artifact.node.same(stat(artifact.file)))
          throw new IOException("STORAGE_COPY_CHANGED");
        walk(artifact.file, artifact.node, "", false, 0, usage, null);
      } catch (IOException unavailable) {
        usage.incomplete = true;
      }
      incomplete |= usage.incomplete;
      return new Copy(
          row.kind,
          row.relativePath,
          row.identity,
          row.state,
          knownBytes(usage.logical, usage.incomplete),
          knownBytes(usage.allocated, usage.incomplete || !usage.allocationComplete),
          usage.incomplete,
          !usage.incomplete && usage.allocationComplete,
          usage.linked.size(),
          artifact.managedRoot.isEmpty() ? -1 : knownBytes(usage.managedDsh, usage.incomplete),
          usage.markerFiles,
          usage.markerBytes,
          row.replacedByActive,
          row.compressedOriginalBytes,
          row.compressedProofRecorded,
          row.runtimeHealthRecorded,
          usage.incomplete ? null : row.expansionTarget);
    }

    private static long knownBytes(long bytes, boolean partial) {
      return partial && bytes == 0 ? -1 : bytes;
    }

    private void measureJournal(Operation op) {
      if (stop()) return;
      var usage = new Usage("", false, op.runtime ? RUNTIME_PAYLOADS : ENVIRONMENT_PAYLOADS);
      try {
        var expected = stat(op.directory);
        if (!expected.type.equals("DIRECTORY")) throw new IOException("SOURCE_CHANGED");
        walk(op.directory, expected, "", false, 0, usage, null);
      } catch (IOException unavailable) {
        usage.incomplete = true;
      }
      incomplete |= usage.incomplete;
      try {
        operationMetadataBytes = Math.addExact(operationMetadataBytes, usage.logical);
      } catch (ArithmeticException overflow) {
        incomplete = true;
      }
    }

    private void walk(
        File file,
        BackupFileSystem.Node node,
        String relative,
        boolean withinManaged,
        int depth,
        Usage usage,
        AndroidStorageDirectory parent) {
      try {
        if (stop() || depth > BackupLimits.DEPTH) throw new IOException("STORAGE_SCAN_LIMIT");
        if (node.type.equals("MISSING")) throw new IOException("SOURCE_CHANGED");
        withinManaged |= !usage.managedRoot.isEmpty() && relative.equals(usage.managedRoot);
        if (node.type.equals("FILE")) {
          account(node, usage);
          if (withinManaged) usage.managedDsh = Math.addExact(usage.managedDsh, node.size);
          if (usage.environment && RUNTIME_MARKERS.contains(relative)) {
            usage.markerFiles++;
            usage.markerBytes = Math.addExact(usage.markerBytes, node.size);
          }
        }
        if (!node.type.equals("DIRECTORY")) return;
        if (fs.getClass() == AndroidBackupFileSystem.class) {
          try (var scoped =
              parent == null
                  ? AndroidStorageDirectory.open(file, node)
                  : parent.directory(file.getName(), node)) {
            walkChildren(file, relative, withinManaged, depth, usage, scoped.list(), scoped);
          }
        } else {
          walkChildren(file, relative, withinManaged, depth, usage, fs.list(file), null);
          if (!node.same(stat(file))) throw new IOException("SOURCE_CHANGED");
        }
      } catch (IOException | ArithmeticException unavailable) {
        usage.incomplete = true;
      }
    }

    private void walkChildren(
        File file,
        String relative,
        boolean withinManaged,
        int depth,
        Usage usage,
        List<String> names,
        AndroidStorageDirectory scoped)
        throws IOException {
      for (String name : names) {
        if (stop()) throw new IOException("STORAGE_SCAN_LIMIT");
        String path = relative.isEmpty() ? name : relative + "/" + name;
        if (usage.excludedRoots.contains(path)) continue;
        try {
          File child = child(file, name);
          BackupFileSystem.Node node;
          if (scoped == null) node = stat(child);
          else {
            entries++;
            node = checked(scoped.stat(name));
          }
          walk(child, node, path, withinManaged, depth + 1, usage, scoped);
        } catch (IOException unavailable) {
          usage.incomplete = true;
        }
      }
    }

    private void account(BackupFileSystem.Node node, Usage usage) {
      usage.logical = Math.addExact(usage.logical, node.size);
      if (!usage.excludedRoots.isEmpty()) return;
      if (node.allocatedBytes < 0 || node.links < 1 || node.key.isEmpty()) {
        usage.allocationComplete = false;
        allocationComplete = false;
        return;
      }
      if (node.links == 1 || usage.linked.add(node.key))
        usage.allocated = Math.addExact(usage.allocated, node.allocatedBytes);
      if (node.links == 1 || linked.add(node.key))
        distinctAllocated = Math.addExact(distinctAllocated, node.allocatedBytes);
    }

    private boolean stop() {
      if (entries < entryLimit
          && System.nanoTime() - phaseStarted < timeLimit
          && !cancelled.getAsBoolean()) return false;
      incomplete = true;
      return true;
    }

    private BackupFileSystem.Node stat(File file) throws IOException {
      if (stop()) throw new IOException("STORAGE_SCAN_LIMIT");
      entries++;
      return checked(fs.stat(file));
    }

    private BackupFileSystem.Node checked(BackupFileSystem.Node node) throws IOException {
      if (!node.type.equals("MISSING") && node.device != device)
        throw new IOException("STORAGE_VOLUME_BOUNDARY");
      return node;
    }

    private boolean directory(File file) throws IOException {
      var node = stat(file);
      if (node.type.equals("MISSING")) return false;
      if (!node.type.equals("DIRECTORY")) throw new IOException("STORAGE_DIRECTORY_TYPE");
      return true;
    }

    private byte[] bytes(File file, int limit) throws IOException {
      var node = stat(file);
      if (node.type.equals("MISSING")) return null;
      if (!node.type.equals("FILE")
          || node.size < 0
          || node.size > limit
          || node.size > MAX_METADATA - metadataBytes)
        throw new IOException("STORAGE_METADATA_LIMIT");
      metadataBytes += node.size;
      try (var in = fs.read(file, node)) {
        byte[] content = new byte[(int) node.size];
        int offset = 0;
        while (offset < content.length) {
          if (stop()) throw new IOException("STORAGE_SCAN_LIMIT");
          int read = in.read(content, offset, content.length - offset);
          if (read < 0) throw new IOException("SOURCE_CHANGED");
          offset += read;
        }
        if (in.read() != -1) throw new IOException("SOURCE_CHANGED");
        return content;
      }
    }

    private String text(File file) throws IOException {
      byte[] bytes = bytes(file, 256);
      return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private Map<String, Object> json(File file, int limit) throws IOException {
      byte[] bytes = bytes(file, limit);
      return bytes == null ? null : BackupJson.read(bytes, limit);
    }

    private boolean marked(Operation op, String name) throws IOException {
      File file = new File(op.directory, name);
      byte[] bytes = bytes(file, 256);
      if (bytes == null) return false;
      if (!(op.directory.getName() + "\n" + name + "\n")
          .equals(new String(bytes, StandardCharsets.UTF_8)))
        throw new IOException("STORAGE_TRANSACTION_MARKER");
      return true;
    }
  }

  private static File child(File parent, String name) throws IOException {
    if (name.isEmpty()
        || name.equals(".")
        || name.equals("..")
        || name.indexOf('/') >= 0
        || name.indexOf('\0') >= 0
        || (File.separatorChar == '\\' && name.indexOf('\\') >= 0))
      throw new IOException("STORAGE_PATH");
    return new File(parent, name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> value, String key)
      throws IOException {
    if (!(value.get(key) instanceof Map<?, ?> map))
      throw new IOException("STORAGE_TRANSACTION_RECORD");
    return (Map<String, Object>) map;
  }

  private static RuntimeDescriptor descriptor(Map<String, Object> value) throws IOException {
    return value == null || value.isEmpty() ? null : new RuntimeDescriptor(value);
  }

  private static Identity identity(RuntimeDescriptor descriptor, String marker) {
    String[] parts =
        marker.matches("[0-9]{1,9}:[0-9]{1,9}:[A-Za-z0-9._+-]{1,80}")
            ? marker.split(":", 3)
            : new String[] {"", "", ""};
    return descriptor == null
        ? new Identity("", parts[2], parts[1], parts[0])
        : new Identity(
            descriptor.id(),
            descriptor.dshVersion(),
            (String) descriptor.json().get("baseVersion"),
            parts[0]);
  }

  private static String mappedRoot(Map<String, Object> paths, String wanted) throws IOException {
    String root = "";
    for (var entry : paths.entrySet()) {
      if (!wanted.equals(entry.getValue())) continue;
      if (!root.isEmpty() || !entry.getKey().matches("runtime-[0-9]{1,4}"))
        throw new IOException("RUNTIME_MAPPING");
      root = entry.getKey();
    }
    return root;
  }

  private static boolean mappingPaths(Map<String, Object> paths) {
    if (paths.isEmpty() || paths.size() > 2048) return false;
    Set<String> seen = new HashSet<>();
    for (var entry : paths.entrySet())
      if (!entry.getKey().matches("runtime-[0-9]{1,4}")
          || !(entry.getValue() instanceof String path)
          || path.equals("linux/.runtime-health.json")
          || !ManagedRuntimeLayout.allowed(path)
          || !seen.add(path)) return false;
    return true;
  }

  private static String sha(byte[] value) {
    return BackupArchive.hex(BackupArchive.sha().digest(value));
  }
}
