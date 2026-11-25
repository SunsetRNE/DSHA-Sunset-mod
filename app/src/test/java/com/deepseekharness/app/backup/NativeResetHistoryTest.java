package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NativeResetHistoryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  // Host fixtures use real disk and finalized journals. The test filesystem
  // projects Windows fileKey/link count into the Android node wire format.
  private static final JvmBackupFileSystem FS =
      new JvmBackupFileSystem() {
        @Override
        public Node stat(File file) throws IOException {
          Node value = super.stat(file);
          if (!value.type.equals("FILE")) return value;
          return new Node(
              value.type,
              "10:" + Integer.toUnsignedLong(value.key.hashCode()),
              value.size,
              value.modified,
              value.device,
              value.mode,
              -1,
              1);
        }
      };

  private File olderReset(File files, UserDataLayout.Home scope) throws Exception {
    NativeConfigurationResetTest.fixture(files);
    var layout = new UserDataLayout(FS, files);
    File dsh = layout.root(scope);
    Files.createDirectories(dsh.toPath());
    layout.choose(scope);
    File settings = new File(dsh, "settings.yaml");
    Files.writeString(settings.toPath(), "earlier settings");
    File environment = new File(files, NativeConfigurationResetTest.ENV);
    File directory =
        new File(HostOperationArchive.reserve(FS, files), UUID.randomUUID().toString());
    FS.directory(directory);
    try (var out = FS.create(new File(directory, "config-reset.json"))) {
      out.write(
          BackupJson.write(
              Map.of(
                  "version",
                  1L,
                  "id",
                  directory.getName(),
                  "home",
                  scope.name(),
                  "workspace",
                  "/root/project"),
              4096));
    }
    var txn =
        new HostDataTransaction(
            FS,
            directory,
            key -> {
              if (key.equals("settings")) return settings;
              if (key.equals("environment")) return environment;
              throw new IOException("unexpected fixture key");
            },
            NativeConfigurationResetTest.settings(files),
            null);
    FS.directory(new File(directory, "candidate"));
    Files.writeString(txn.candidate("settings").toPath(), "{}\n");
    Files.writeString(txn.candidate("environment").toPath(), "synthetic environment");
    var control = new BackupControl(null);
    txn.prepare(
        List.of("settings", "environment"),
        Map.of(
            "settings",
            BackupTree.digest(FS, settings, control),
            "environment",
            BackupTree.digest(FS, environment, control)),
        NativeConfigurationResetTest.settings(files).current(),
        control);
    txn.commit(control);
    HostOperationArchive.archiveIfTerminal(FS, files, directory);
    File imported = new File(dsh, "settings.yaml.imported");
    Files.move(settings.toPath(), imported.toPath());
    return imported;
  }

  @Test
  public void recognizesOldResetAfterRenameInBothHomes() throws Exception {
    for (UserDataLayout.Home home : UserDataLayout.Home.values()) {
      File root = temporary.newFolder();
      File imported = olderReset(root, home);
      String value = NativeResetHistory.importedEmptyProof(FS, root);
      assertTrue(home.name(), value.matches("10:[0-9]+:[0-9]+:[0-9]+:[a-f0-9-]{36}"));
      assertEquals("{}\n", Files.readString(imported.toPath()));
    }
  }

  @Test
  public void wrongHomeAndSameBytesWithoutNativeHistoryAreInadmissible() throws Exception {
    File root = temporary.newFolder();
    File original = olderReset(root, UserDataLayout.Home.LEGACY);
    var layout = new UserDataLayout(FS, root);
    File stable = layout.root(UserDataLayout.Home.STABLE);
    Files.createDirectories(stable.toPath());
    Files.copy(
        original.toPath(),
        new File(stable, original.getName()).toPath(),
        StandardCopyOption.COPY_ATTRIBUTES);
    layout.choose(UserDataLayout.Home.STABLE);
    assertEquals("", NativeResetHistory.importedEmptyProof(FS, root));
    File noJournal = temporary.newFolder();
    NativeConfigurationResetTest.put(
        noJournal, UserDataLayout.LEGACY + "/settings.yaml.imported", "{}\n");
    assertEquals("", NativeResetHistory.importedEmptyProof(FS, noJournal));
  }

  @Test
  public void rejectsAdditionalOrAlteredInputAndMissingOriginal() throws Exception {
    for (String conflict : List.of("active", "nonempty", "missing-previous")) {
      File files = temporary.newFolder();
      File imported = olderReset(files, UserDataLayout.Home.LEGACY);
      if (conflict.equals("active"))
        Files.writeString(new File(imported.getParentFile(), "settings.yaml").toPath(), "{}\n");
      else if (conflict.equals("nonempty")) Files.writeString(imported.toPath(), "real: true\n");
      else
        Files.delete(
            new File(NativeConfigurationResetTest.operation(files), "previous/settings").toPath());
      assertEquals(conflict, "", NativeResetHistory.importedEmptyProof(FS, files));
    }
  }

  @Test
  public void unrelatedCompletedTransfersDoNotHideTheVerifiedReset() throws Exception {
    File files = temporary.newFolder();
    olderReset(files, UserDataLayout.Home.LEGACY);
    File history = HostOperationArchive.completedRoot(files);
    for (int index = 0; index < 260; index++) {
      String id = UUID.randomUUID().toString();
      File operation = new File(history, id);
      FS.directory(operation);
      try (var out = FS.create(new File(operation, "operation.json"))) {
        out.write(
            BackupJson.write(
                Map.of("version", 1L, "id", id, "busy", false, "stage", "FINISHED", "artifact", ""),
                16384));
      }
    }
    assertFalse(NativeResetHistory.importedEmptyProof(FS, files).isEmpty());
  }
}
