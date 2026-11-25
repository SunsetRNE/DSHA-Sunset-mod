package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.deepseekharness.app.util.DocumentPaths;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class UserDataLayoutTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private void put(File root, String path, String value) throws Exception {
    File file = new File(root, path);
    Files.createDirectories(file.getParentFile().toPath());
    Files.writeString(file.toPath(), value);
  }

  @Test
  public void onlyTwoProvenAbsentHomesWithNoSelectionQualifyForFirstHomeRotation()
      throws Exception {
    File files = temp.newFolder();
    var layout = new UserDataLayout(fs, files);
    assertTrue(layout.pristineMissingHome());
    for (String marker : List.of(UserDataLayout.RECORD, UserDataLayout.RECORD + ".previous")) {
      put(files, marker, "fixture");
      assertFalse(layout.pristineMissingHome());
      Files.delete(new File(files, marker).toPath());
    }
    for (String home : List.of(UserDataLayout.LEGACY, UserDataLayout.STABLE)) {
      Path path = new File(files, home).toPath();
      Files.createDirectories(path);
      assertFalse(layout.pristineMissingHome());
      Files.delete(path);
      assertTrue(layout.pristineMissingHome());
    }
    File legacyParent = new File(files, UserDataLayout.LEGACY).getParentFile();
    for (String unsafeType : List.of("FILE", "LINK", "SPECIAL")) {
      var other =
          new JvmBackupFileSystem() {
            public Node stat(File file) throws IOException {
              return file.equals(legacyParent)
                  ? new Node(unsafeType, "fixture", 0, 0, 0, 0)
                  : super.stat(file);
            }
          };
      assertFalse(new UserDataLayout(other, files).pristineMissingHome());
    }
    var replaced =
        new JvmBackupFileSystem() {
          boolean checked;

          public Node stat(File file) throws IOException {
            if (file.equals(new File(files, UserDataLayout.STABLE))) {
              if (checked) return new Node("DIRECTORY", "replacement", 0, 0, 0, 0700);
              checked = true;
            }
            return super.stat(file);
          }
        };
    assertThrows(
        IOException.class, () -> new UserDataLayout(replaced, files).pristineMissingHome());
    var denied =
        new JvmBackupFileSystem() {
          public Node stat(File file) throws IOException {
            if (file.equals(legacyParent)) throw new IOException("PERMISSION_DENIED");
            return super.stat(file);
          }
        };
    assertThrows(IOException.class, () -> new UserDataLayout(denied, files).pristineMissingHome());
  }

  @Test
  public void explicitChoiceKeepsBothTreesAndKeepsOldDocumentIds() throws Exception {
    File root = temp.newFolder();
    put(root, UserDataLayout.LEGACY + "/sessions/old", "old");
    put(root, UserDataLayout.STABLE + "/sessions/new", "new");
    var layout = new UserDataLayout(fs, root);
    assertThrows(IOException.class, layout::selected);
    layout.choose(UserDataLayout.Home.STABLE);
    var paths = new DocumentPaths(root, p -> null, layout.documents());
    assertEquals(
        new File(root, UserDataLayout.STABLE + "/sessions/new"),
        paths.resolve(UserDataLayout.LEGACY + "/sessions/new", true));
    assertTrue(paths.childOf("linux/ubuntu/root", UserDataLayout.LEGACY + "/sessions/new"));
    assertTrue(
        paths.childOf(
            UserDataLayout.LEGACY + "/sessions", UserDataLayout.LEGACY + "/sessions/new"));
    assertEquals(
        "old", Files.readString(new File(root, UserDataLayout.LEGACY + "/sessions/old").toPath()));
  }

  @Test
  public void dataSubtreeGrantCannotUseAliasToEscapeAndPrivateJournalsStayPrivate()
      throws Exception {
    File root = temp.newFolder();
    put(root, UserDataLayout.STABLE + "/sessions/new", "new");
    put(root, "linux/ubuntu/root/private", "secret");
    var layout = new UserDataLayout(fs, root);
    layout.choose(UserDataLayout.Home.STABLE);
    var paths =
        new DocumentPaths(
            root,
            path ->
                path.getName().equals("escape")
                    ? new File(root, "linux/ubuntu/root/private").getPath()
                    : null,
            layout.documents());
    assertFalse(
        paths.childOf(
            UserDataLayout.LEGACY + "/sessions", UserDataLayout.LEGACY + "/sessions/escape"));
    assertTrue(layout.privateDocument(new File(root, "host-backup-operations/id/plaintext")));
    assertFalse(layout.privateDocument(new File(root, UserDataLayout.STABLE + "/sessions/new")));
  }

  @Test
  public void stableBindingUsesManagedScriptsFromApkRuntimeAndMissingRootIsNotEmptyData()
      throws Exception {
    File root = temp.newFolder();
    put(root, UserDataLayout.STABLE + "/settings.yaml", "{}");
    put(root, UserDataLayout.LEGACY + "/plugin-manager.py", "trusted script");
    var layout = new UserDataLayout(fs, root);
    layout.choose(UserDataLayout.Home.STABLE);
    var binds = layout.binds(new File(root, "linux/ubuntu"));
    assertTrue(binds.stream().anyMatch(row -> row[1].equals("/root/.dsh")));
    assertTrue(binds.stream().anyMatch(row -> row[1].equals("/root/.dsh/plugin-manager.py")));
    Files.delete(new File(root, UserDataLayout.STABLE + "/settings.yaml").toPath());
    Files.delete(new File(root, UserDataLayout.STABLE).toPath());
    assertThrows(IOException.class, () -> layout.binds(new File(root, "linux/ubuntu")));
  }

  @Test
  public void boundedGuestControlRecordsArePrivateButSimilarUserNamesRemainVisible()
      throws Exception {
    File root = temp.newFolder();
    put(root, UserDataLayout.STABLE + "/settings.yaml", "{}");
    var layout = new UserDataLayout(fs, root);
    layout.choose(UserDataLayout.Home.STABLE);
    assertTrue(layout.privateDocument(new File(root, "bounded-guest-active")));
    assertTrue(layout.privateDocument(new File(root, "bounded-guest-active/instance.json")));
    assertFalse(layout.privateDocument(new File(root, "bounded-guest-active-notes/readme.txt")));
  }

  @Test
  public void rc1MigrationAlwaysBindsSignedScriptOverRestoredUserFile() throws Exception {
    File root = temp.newFolder();
    for (String script :
        List.of("rc1-migration.py", "rc1-settings-migration.cjs", "materialize-cas-links.py")) {
      put(root, UserDataLayout.STABLE + "/" + script, "untrusted restored file");
      put(root, UserDataLayout.LEGACY + "/" + script, "signed asset");
    }
    var layout = new UserDataLayout(fs, root);
    layout.choose(UserDataLayout.Home.STABLE);
    var binds = layout.binds(new File(root, "linux/ubuntu"));
    for (String script :
        List.of("rc1-migration.py", "rc1-settings-migration.cjs", "materialize-cas-links.py"))
      assertTrue(
          binds.stream()
              .anyMatch(
                  row ->
                      row[1].equals("/root/.dsh/" + script)
                          && row[0].equals(
                              new File(root, UserDataLayout.LEGACY + "/" + script)
                                  .getAbsolutePath())));
    assertTrue(
        layout.privateDocument(new File(root, "rc1-migration-state/generations/id/snapshots/0")));
  }

  @Test
  public void credentialFiltersApplyToLegacyStableAndAliasesWithoutChangingDocumentIds()
      throws Exception {
    File root = temp.newFolder();
    put(root, UserDataLayout.STABLE + "/sessions/new", "new");
    var layout = new UserDataLayout(fs, root);
    layout.choose(UserDataLayout.Home.STABLE);
    for (String base :
        List.of(UserDataLayout.STABLE, UserDataLayout.LEGACY, "linux/ubuntu/root/project"))
      for (String name :
          List.of(".bridge_token", ".bridge_headers", ".credentials.yaml", ".env", ".env.local"))
        assertTrue(base + "/" + name, layout.privateDocument(new File(root, base + "/" + name)));
    assertTrue(layout.privateDocument(new File(root, "linux/ubuntu/root/.dsha-web.pid")));
    assertFalse(layout.privateDocument(new File(root, "linux/ubuntu/root/project/report.md")));
  }
}
