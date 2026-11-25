package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** App-private evidence that an exact imported empty file came from an older native reset. */
public final class NativeResetHistory {
  private static final byte[] EMPTY_SETTINGS = "{}\n".getBytes(StandardCharsets.UTF_8);

  private NativeResetHistory() {}

  public static String importedEmptyProof(BackupFileSystem fs, File files) throws IOException {
    var layout = new UserDataLayout(fs, files);
    var home = layout.selected();
    File root = layout.root(home);
    if (!fs.stat(root).type.equals("DIRECTORY")) return "";
    File active = fs.child(root, "settings.yaml");
    File imported = fs.child(root, "settings.yaml.imported");
    var node = fs.stat(imported);
    if (!fs.stat(active).type.equals("MISSING")
        || !node.type.equals("FILE")
        || node.size != EMPTY_SETTINGS.length
        || node.links != 1
        || !node.key.matches("[0-9]+:[0-9]+")
        || !Arrays.equals(EMPTY_SETTINGS, fs.small(imported, EMPTY_SETTINGS.length))) return "";
    long time = modified(imported);
    if (time <= 0) return "";
    String digest = BackupTree.digest(fs, imported, new BackupControl(null));
    String matched = "";
    for (File history : HostOperationArchive.roots(fs, HostOperationArchive.root(files))) {
      for (String id : fs.list(history)) {
        if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)) continue;
        File record = fs.child(history, id);
        if (!fs.stat(record).type.equals("DIRECTORY") || !NativeConfigurationReset.owns(fs, record))
          continue;
        long finalized = verifiedReset(fs, record, home, digest, time);
        if (finalized == 0) continue;
        if (!matched.isEmpty()
            || !HostOperationArchive.locate(fs, HostOperationArchive.root(files), id)
                .equals(record)) return "";
        // The host uses its finest available timestamps to match its journal.
        // Transfer the kernel timestamp in whole seconds across the guest boundary.
        matched =
            node.key + ":" + TimeUnit.NANOSECONDS.toSeconds(time) + ":" + finalized + ":" + id;
      }
    }
    var after = fs.stat(imported);
    if (!node.same(after)
        || after.links != 1
        || time != modified(imported)
        || !fs.stat(active).type.equals("MISSING")
        || home != layout.selected()
        || !Arrays.equals(EMPTY_SETTINGS, fs.small(imported, EMPTY_SETTINGS.length))) return "";
    return matched;
  }

  private static long verifiedReset(
      BackupFileSystem fs, File directory, UserDataLayout.Home home, String digest, long timestamp)
      throws IOException {
    File description = fs.child(directory, "config-reset.json");
    File verified = fs.child(directory, "verified");
    File finalized = fs.child(directory, "finalized");
    if (!fs.stat(verified).type.equals("FILE") || !fs.stat(finalized).type.equals("FILE")) return 0;
    long start = modified(description), staged = modified(verified), finished = modified(finalized);
    if (start > timestamp || timestamp > staged || staged > finished) return 0;
    Map<String, Object> record = BackupJson.read(fs.small(description, 4096), 4096);
    if (BackupJson.number(record, "version") != 1
        || !directory.getName().equals(BackupJson.string(record, "id"))
        || !home.name().equals(BackupJson.string(record, "home"))) return 0;
    NativeConfigurationReset.normalizeWorkspace(BackupJson.string(record, "workspace"));
    for (String phase :
        List.of("verified", "switching", "files-committed", "settings-committed", "finalized"))
      if (!TransactionMarkers.read(fs, directory, phase, "RESET_PROOF_MARKER")) return 0;
    for (String phase : List.of("rolling-back", "rolled-back"))
      if (!fs.stat(fs.child(directory, phase)).type.equals("MISSING")) return 0;
    var plan =
        BackupJson.read(
            fs.small(fs.child(directory, "plan.json"), BackupLimits.MANIFEST),
            BackupLimits.MANIFEST);
    if (BackupJson.number(plan, "version") != 1
        || !directory.getName().equals(BackupJson.string(plan, "id"))
        || !(plan.get("roots") instanceof List<?> roots)
        || roots.size() != 2
        || !(plan.get("settingsBefore") instanceof Map)
        || !plan.get("settingsBefore").equals(plan.get("settingsAfter"))) return 0;
    Map<?, ?> settings = null;
    boolean environment = false;
    for (Object value : roots) {
      if (!(value instanceof Map<?, ?> row)) return 0;
      if ("settings".equals(row.get("root"))) settings = row;
      else if ("environment".equals(row.get("root"))) environment = true;
      else return 0;
    }
    if (!environment
        || settings == null
        || !Boolean.TRUE.equals(settings.get("candidatePresent"))
        || !digest.equals(settings.get("after"))
        || !(settings.get("had") instanceof Boolean had)) return 0;
    File previousRoot = fs.child(directory, "previous");
    if (!fs.stat(previousRoot).type.equals("DIRECTORY")) return 0;
    File previous = fs.child(previousRoot, "settings");
    var prior = fs.stat(previous);
    if (had
        ? !prior.type.equals("FILE") || prior.links != 1 || prior.size > 1024 * 1024
        : !prior.type.equals("MISSING")) return 0;
    if (!BackupTree.digest(fs, previous, new BackupControl(null)).equals(settings.get("before")))
      return 0;
    return finished;
  }

  private static long modified(File file) throws IOException {
    return Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        .to(TimeUnit.NANOSECONDS);
  }
}
