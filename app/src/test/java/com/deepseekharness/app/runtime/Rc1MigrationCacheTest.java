package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.Rc1MigrationResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class Rc1MigrationCacheTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private File files, home, state;
  private JvmBackupFileSystem fs;
  private String generation, settingsSha;

  private void fixture() throws Exception {
    files = temporary.newFolder();
    home = new File(files, "data");
    fs =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws java.io.IOException {
            Node value = super.stat(file);
            return new Node(
                value.type,
                file.equals(home) ? "100:123" : value.key,
                value.size,
                value.modified,
                100,
                value.mode);
          }
        };
    fs.directory(home);
    fs.atomic(home, "settings.yaml", "saved setting".getBytes(StandardCharsets.UTF_8));
    settingsSha = sha("saved setting".getBytes(StandardCharsets.UTF_8));
    generation = UUID.randomUUID().toString();
    state = fs.child(files, "rc1-migration-state");
    fs.parents(files, "rc1-migration-state/generations/" + generation + "/reuse.json");
    File recorded = fs.child(state, "generations/" + generation);
    var identity = Map.of("path", "/root/.dsh", "device", "100", "inode", "123");
    var inputs = Map.of("settings.yaml", settingsSha);
    var current = new HashMap<String, Object>();
    current.put("version", 2L);
    current.put("generation", generation);
    current.put("startupId", "startup");
    current.put("dshHome", "/root/.dsh");
    current.put("dataRoot", identity);
    current.put("inputs", inputs);
    fs.atomic(state, "current.json", BackupJson.write(current, 65536));
    var prepared = new HashMap<String, Object>(current);
    prepared.put("dshVersion", Constants.DSH_VERSION);
    prepared.put("status", "prepared");
    prepared.put("protectionComplete", true);
    prepared.put("inputDigest", "d".repeat(64));
    prepared.put("settingsSha256", settingsSha);
    fs.atomic(recorded, "reuse.json", BackupJson.write(prepared, 65536));
    var receipt =
        Map.of(
            "version",
            2L,
            "generation",
            generation,
            "dshVersion",
            Constants.DSH_VERSION,
            "status",
            "committed",
            "protectionComplete",
            true,
            "sourcePreserved",
            true,
            "settingsImported",
            true);
    fs.atomic(recorded, "receipt.json", BackupJson.write(receipt, 65536));
    var ledger =
        Map.of(
            "version",
            2,
            "generation",
            generation,
            "sourceSha256",
            settingsSha,
            "startupId",
            "startup",
            "status",
            "verified",
            "sections",
            Map.of("first", Map.of("status", "verified")));
    fs.atomic(recorded, "settings-result.json", BackupJson.write(ledger, 65536));
  }

  @Test
  public void freshUnchangedInputsYieldOnlyHistoricalScope() throws Exception {
    fixture();
    var reuse = Rc1MigrationCache.reuse(fs, files, home);
    assertEquals(Rc1MigrationCache.Reuse.Status.REUSABLE_HISTORY, reuse.status);
    assertEquals("verified-historical", reuse.settingsImportStatus);
    assertNotNull(reuse.ledgerDigest);
    String output = Rc1MigrationCache.output(reuse, true, "startup");
    var parsed = Rc1MigrationResult.parse(output);
    assertTrue(Rc1MigrationResult.allowsStart(parsed));
    assertTrue(Rc1MigrationResult.isRoutineReceipt(parsed));
    assertTrue(Rc1MigrationResult.isHistorical(parsed));
    assertEquals("NOT_SENT", parsed.get("executionState"));
    assertFalse(parsed.containsKey("protectionComplete"));
    assertFalse(parsed.containsKey("sourcePreserved"));
    assertFalse(parsed.containsKey("settingsImported"));
  }

  @Test
  public void changedInputsAreDefiniteNewIntentNotCacheMiss() throws Exception {
    fixture();
    fs.atomic(home, "settings.yaml", "edited setting".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        Rc1MigrationCache.Reuse.Status.NEW_INPUT, Rc1MigrationCache.reuse(fs, files, home).status);
    fs.atomic(home, "settings.yaml.imported", "extra".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        Rc1MigrationCache.Reuse.Status.NEW_INPUT, Rc1MigrationCache.reuse(fs, files, home).status);
  }

  @Test
  public void missingLedgerOrUnknownSectionCannotBeHistoricalSuccess() throws Exception {
    fixture();
    File folder = fs.child(state, "generations/" + generation);
    File ledger = fs.child(folder, "settings-result.json");
    ledger.delete();
    var missing = Rc1MigrationCache.reuse(fs, files, home);
    assertEquals(Rc1MigrationCache.Reuse.Status.UNAVAILABLE, missing.status);
    assertEquals("SETTINGS_LEDGER_MISSING", missing.reason);
    fs.atomic(
        folder,
        "settings-result.json",
        BackupJson.write(
            Map.of(
                "version",
                2,
                "generation",
                generation,
                "sourceSha256",
                settingsSha,
                "status",
                "unknown",
                "sections",
                Map.of("first", Map.of("status", "writing"))),
            65536));
    var unknown = Rc1MigrationCache.reuse(fs, files, home);
    assertEquals(Rc1MigrationCache.Reuse.Status.UNAVAILABLE, unknown.status);
    assertEquals("SETTINGS_LEDGER_UNKNOWN", unknown.reason);
  }

  @Test
  public void corruptOrOldRecordsAreUnavailableNotAbsent() throws Exception {
    fixture();
    File folder = fs.child(state, "generations/" + generation);
    File reuseFile = fs.child(folder, "reuse.json");
    byte[] saved = java.nio.file.Files.readAllBytes(reuseFile.toPath());
    fs.atomic(folder, "reuse.json", "{broken".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        Rc1MigrationCache.Reuse.Status.UNAVAILABLE,
        Rc1MigrationCache.reuse(fs, files, home).status);
    fs.atomic(folder, "reuse.json", saved);
    var oldPrepared = new HashMap<String, Object>();
    oldPrepared.put("version", 2L);
    oldPrepared.put("generation", generation);
    oldPrepared.put("dshHome", "/root/.dsh");
    oldPrepared.put("dataRoot", Map.of("path", "/root/.dsh", "device", "100", "inode", "123"));
    oldPrepared.put("inputs", Map.of("settings.yaml", settingsSha));
    oldPrepared.put("dshVersion", "0.2.0-rc.2");
    oldPrepared.put("status", "prepared");
    oldPrepared.put("protectionComplete", true);
    fs.atomic(folder, "reuse.json", BackupJson.write(oldPrepared, 65536));
    var historical = Rc1MigrationCache.reuse(fs, files, home);
    assertEquals(Rc1MigrationCache.Reuse.Status.UNAVAILABLE, historical.status);
    assertEquals("MIGRATION_REUSE_CONTRACT_VERSION", historical.reason);
  }

  @Test
  public void lostCurrentIndexWithCompletedGenerationIsUnavailable() throws Exception {
    fixture();
    fs.child(state, "current.json").delete();
    var reuse = Rc1MigrationCache.reuse(fs, files, home);
    assertEquals(Rc1MigrationCache.Reuse.Status.UNAVAILABLE, reuse.status);
    assertEquals("HOST_STATE_MISSING", reuse.reason);
  }

  @Test
  public void noMigrationStateIsAbsentWhileUnreadableStateIsUnavailable() throws Exception {
    files = temporary.newFolder();
    home = new File(files, "data");
    fs =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws java.io.IOException {
            Node value = super.stat(file);
            return new Node(
                value.type,
                file.equals(home) ? "100:123" : value.key,
                value.size,
                value.modified,
                100,
                value.mode);
          }
        };
    assertEquals(
        Rc1MigrationCache.Reuse.Status.ABSENT, Rc1MigrationCache.reuse(fs, files, home).status);
    fs.parents(files, "data/settings.yaml");
    fs.atomic(home, "settings.yaml", "saved setting".getBytes(StandardCharsets.UTF_8));
    assertTrue(fs.child(files, "rc1-migration-state").mkdir());
    assertEquals(
        Rc1MigrationCache.Reuse.Status.ABSENT, Rc1MigrationCache.reuse(fs, files, home).status);
    assertTrue(fs.child(files, "rc1-migration-state/current.json").createNewFile()); // malformed
    var invalid = Rc1MigrationCache.reuse(fs, files, home);
    assertEquals(Rc1MigrationCache.Reuse.Status.UNAVAILABLE, invalid.status);
    assertEquals("HOST_STATE_INVALID", invalid.reason);
  }

  private static String sha(byte[] data) throws Exception {
    var result = new StringBuilder();
    for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data))
      result.append(String.format("%02x", b & 255));
    return result.toString();
  }
}
