package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.util.ColdInstallPackages;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuntimeTrialLaunchInputsTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void exportsTheSelectedDshPnpmAndBuiltinEntries() throws Exception {
    File root = folder.getRoot(), nextRoot = folder.newFolder("promoted");
    RuntimeDescriptor previous =
        install(root, "0.2.2-rc.1", "10.31.0", "dsh-auto-review", "lib/previous.js");
    RuntimeDescriptor next =
        install(nextRoot, "0.2.2-rc.5", "10.34.5", "dsh-app-integration", "index.js");

    var old = RuntimeTrial.launchInputs(new JvmBackupFileSystem(), root, previous);
    assertEquals("0.2.2-rc.1", old.dshVersion());
    assertEquals("10.31.0", old.pnpmVersion());
    assertEquals(
        "{\"entries\":[\"/root/dsha-auto-review/lib/previous.js\"]}", old.builtinEntries());
    assertTrue(old.exports().contains("DSHA_TRIAL_DSH_VERSION=" + ShellQuote.arg("0.2.2-rc.1")));
    assertTrue(old.exports().contains("DSHA_TRIAL_PNPM_VERSION=" + ShellQuote.arg("10.31.0")));
    assertTrue(
        old.exports()
            .contains("DSHA_TRIAL_BUILTIN_ENTRIES=" + ShellQuote.arg(old.builtinEntries())));

    var current = RuntimeTrial.launchInputs(new JvmBackupFileSystem(), nextRoot, next);
    assertEquals("0.2.2-rc.5", current.dshVersion());
    assertEquals("10.34.5", current.pnpmVersion());
    assertEquals(
        "{\"entries\":[\"/root/dsha-app-integration/index.js\"]}", current.builtinEntries());
    assertEquals(
        "TRIAL_BUILTIN_REGISTRY_DIGEST",
        assertThrows(
                IOException.class,
                () -> RuntimeTrial.launchInputs(new JvmBackupFileSystem(), nextRoot, previous))
            .getMessage());
  }

  @Test
  public void rejectsInvalidMarkerAndEntryEvenWithAValidRegistryDigest() throws Exception {
    File root = folder.getRoot();
    RuntimeDescriptor selected =
        install(root, "0.2.2-rc.1", "10.31.0\n21.0.0", "dsh-auto-review", "lib/previous.js");
    assertEquals(
        "TRIAL_PNPM_VERSION_INVALID",
        assertThrows(
                IOException.class,
                () -> RuntimeTrial.launchInputs(new JvmBackupFileSystem(), root, selected))
            .getMessage());
    RuntimeDescriptor escaped =
        install(root, "0.2.2-rc.1", "10.31.0", "dsh-auto-review", "../previous.js");
    assertEquals(
        "TRIAL_BUILTIN_REGISTRY",
        assertThrows(
                IOException.class,
                () -> RuntimeTrial.launchInputs(new JvmBackupFileSystem(), root, escaped))
            .getMessage());
  }

  @Test
  public void frameworkAliasMustBeResolvedBeforeStrictRuntimeReads() throws Exception {
    File physicalData = folder.newFolder("platform-data");
    File physicalRoot = new File(physicalData, "files/linux/ubuntu");
    RuntimeDescriptor selected =
        install(physicalRoot, "0.2.2-rc.5", "10.34.5", "dsh-app-integration", "index.js");
    File declaredData = new File(folder.getRoot(), "framework-user-data");
    symbolicLink(declaredData.toPath(), physicalData.toPath());
    File declaredFiles = new File(declaredData, "files");
    File declaredRoot = new File(declaredFiles, "linux/ubuntu");
    var fs = new AnchoredReads();

    assertEquals("DIRECTORY", fs.stat(declaredRoot).type);
    assertEquals(
        "PARENT_LINK",
        assertThrows(IOException.class, () -> RuntimeTrial.launchInputs(fs, declaredRoot, selected))
            .getMessage());
    var authority = ColdInstallPackages.bindPrivateFiles(fs, declaredData, declaredFiles);
    var physical =
        fs.child(
            authority.files,
            RuntimeAssetFiles.relative(declaredRoot, declaredFiles, authority.files));
    authority.verify(fs);
    assertEquals(physicalRoot.getCanonicalFile(), physical);
    assertEquals("10.34.5", RuntimeTrial.launchInputs(fs, physical, selected).pnpmVersion());
  }

  @Test
  public void runtimeDescendantLinksAndEscapesStayOutsideTheReadBoundary() throws Exception {
    File data = folder.newFolder("data"), files = new File(data, "files");
    File root = new File(files, "linux/ubuntu");
    RuntimeDescriptor selected =
        install(root, "0.2.2-rc.5", "10.34.5", "dsh-app-integration", "index.js");
    File outside = folder.newFolder("outside");
    var fs = new AnchoredReads();
    var authority = ColdInstallPackages.bindPrivateFiles(fs, data, files);
    assertThrows(
        IOException.class, () -> RuntimeAssetFiles.relative(outside, files, authority.files));
    assertThrows(
        IOException.class,
        () ->
            RuntimeAssetFiles.relative(
                new File(files, "linux/../../outside"), files, authority.files));

    Path marker = root.toPath().resolve("root/.dsha-pnpm-version");
    Path externalVersion = outside.toPath().resolve("version");
    Files.move(marker, externalVersion);
    symbolicLink(marker, externalVersion);
    assertEquals(
        "METADATA_LIMIT",
        assertThrows(IOException.class, () -> RuntimeTrial.launchInputs(fs, root, selected))
            .getMessage());
    Files.delete(marker);
    Files.move(externalVersion, marker);

    Path registry = root.toPath().resolve("root/.dsh/builtin-plugins.json");
    Path externalRegistry = outside.toPath().resolve("builtin-plugins.json");
    Files.move(registry, externalRegistry);
    symbolicLink(registry, externalRegistry);
    assertEquals(
        "METADATA_LIMIT",
        assertThrows(IOException.class, () -> RuntimeTrial.launchInputs(fs, root, selected))
            .getMessage());
    Files.delete(registry);
    Files.delete(registry.getParent());
    symbolicLink(registry.getParent(), outside.toPath());
    assertEquals(
        "PARENT_LINK_OR_MISSING",
        assertThrows(IOException.class, () -> RuntimeTrial.launchInputs(fs, root, selected))
            .getMessage());

    File linkedRuntime = new File(files, "linked-runtime");
    symbolicLink(linkedRuntime.toPath(), outside.toPath());
    assertThrows(
        IOException.class,
        () ->
            fs.child(
                authority.files,
                RuntimeAssetFiles.relative(
                    new File(linkedRuntime, "guest"), files, authority.files)));
  }

  private static void symbolicLink(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assume.assumeNoException("real symbolic links required", unavailable);
    }
  }

  /** Android lstat observes the leaf first. Its later anchored open rejects a linked parent. */
  private static final class AnchoredReads extends JvmBackupFileSystem {
    @Override
    public InputStream read(File file, Node expected) throws IOException {
      var absolute = file.toPath().toAbsolutePath();
      var current = absolute.getRoot();
      for (int index = 0; index < absolute.getNameCount() - 1; index++) {
        current = current.resolve(absolute.getName(index));
        if (Files.isSymbolicLink(current)) throw new IOException("PARENT_LINK");
      }
      return super.read(file, expected);
    }
  }

  private static RuntimeDescriptor install(
      File root, String dsh, String pnpm, String builtin, String entry) throws IOException {
    var registryFile = root.toPath().resolve("root/.dsh/builtin-plugins.json");
    Files.createDirectories(registryFile.getParent());
    byte[] registry =
        BackupJson.write(
            Map.of(
                "schema",
                1L,
                "plugins",
                List.of(
                    Map.of(
                        "name", builtin,
                        "guestDirectory", "/root/dsha-" + builtin.substring(4),
                        "entrypoint", entry))),
            16384);
    Files.write(registryFile, registry);
    Files.writeString(root.toPath().resolve("root/.dsha-pnpm-version"), pnpm + "\n");
    String tree =
        builtin.equals("dsh-app-integration") ? "app-integration" : "builtin-plugins/" + builtin;
    Map<String, Object> descriptor =
        new HashMap<>(
            Map.of(
                "version",
                1L,
                "baseVersion",
                "10",
                "dshVersion",
                dsh,
                "dataRead",
                List.of("dsh-" + dsh),
                "dataWrite",
                "dsh-" + dsh,
                "launcherContract",
                "DSHA_ARM64_V2",
                "bridgeProtocol",
                3L,
                "inputs",
                Map.of(
                    "builtin-plugins.json",
                    BackupArchive.hex(BackupArchive.sha().digest(registry)),
                    tree + "/" + entry,
                    "a".repeat(64))));
    descriptor.put("runtimeId", RuntimeDescriptor.calculateId(descriptor));
    return new RuntimeDescriptor(descriptor);
  }
}
