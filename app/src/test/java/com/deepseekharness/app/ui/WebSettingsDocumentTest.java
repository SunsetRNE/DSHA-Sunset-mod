package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WebSettingsDocumentTest {
  @Rule public final TemporaryFolder directory = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  private static final String PATCH = "profiles/web/cordis.patch.yml";

  private Path put(String relative, String content) throws IOException {
    Path file = directory.getRoot().toPath().resolve(relative);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }

  @Test
  public void selectsOnlyTheCurrentOrdinaryProfilePatch() throws Exception {
    var legacy = put(UserDataLayout.LEGACY + "/" + PATCH, "legacy: true\n");
    assertEquals("legacy: true\n", WebSettingsDocument.readCurrent(fs, directory.getRoot()).text());
    var active = put(UserDataLayout.STABLE + "/" + PATCH, "active: true\n");
    put(UserDataLayout.STABLE + "/cordis.patch.yml", "shared: true\n");
    put(UserDataLayout.STABLE + "/profiles/dsha-recovery-x/cordis.patch.yml", "recovery: true\n");
    new UserDataLayout(fs, directory.getRoot()).choose(UserDataLayout.Home.STABLE);
    var view = WebSettingsDocument.readCurrent(fs, directory.getRoot());
    assertEquals("active: true\n", view.text());
    assertFalse(view.truncated());
    assertEquals("active: true\n", Files.readString(active));
    assertEquals("legacy: true\n", Files.readString(legacy));
  }

  @Test
  public void missingFileIsDistinctAndNeverCreatedFromOtherSettings() throws Exception {
    put(UserDataLayout.STABLE + "/settings.yaml", "legacy: true\n");
    assertNull(WebSettingsDocument.readCurrent(fs, directory.getRoot()));
    var path = directory.getRoot().toPath().resolve(UserDataLayout.STABLE + "/" + PATCH);
    assertFalse(Files.exists(path));
    Files.createDirectories(path.getParent());
    assertNull(WebSettingsDocument.readCurrent(fs, directory.getRoot()));
    assertFalse(Files.exists(path));
    Files.createFile(path);
    assertEquals("", WebSettingsDocument.readCurrent(fs, directory.getRoot()).text());
  }

  @Test
  public void oversizedFileReturnsOnlyTheValidUtf8Prefix() throws Exception {
    String prefix = "x".repeat(WebSettingsDocument.PREVIEW_BYTES - 2);
    var target = put(UserDataLayout.STABLE + "/" + PATCH, prefix + "界" + "z".repeat(1024));
    var view = WebSettingsDocument.readCurrent(fs, directory.getRoot());
    assertTrue(view.truncated());
    assertEquals(prefix, view.text());
    assertTrue(Files.size(target) > WebSettingsDocument.PREVIEW_BYTES);
  }

  @Test
  public void brokenEncodingAndInvalidDataRootDoNotExposeAFile() throws Exception {
    var target = put(UserDataLayout.STABLE + "/" + PATCH, "x");
    Files.write(target, new byte[] {(byte) 0xe7, (byte) 0x95});
    assertThrows(IOException.class, () -> WebSettingsDocument.readCurrent(fs, directory.getRoot()));
    put(UserDataLayout.RECORD, "{}\n");
    assertThrows(IOException.class, () -> WebSettingsDocument.readCurrent(fs, directory.getRoot()));
  }

  @Test
  public void linkAndParentLinkAreRejected() throws Exception {
    var other = put("unrelated.yml", "marker: true\n");
    var target = directory.getRoot().toPath().resolve(UserDataLayout.STABLE + "/" + PATCH);
    Files.createDirectories(target.getParent());
    try {
      Files.createSymbolicLink(target, other);
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assume.assumeNoException(unavailable);
    }
    assertThrows(IOException.class, () -> WebSettingsDocument.readCurrent(fs, directory.getRoot()));
    Files.delete(target);
    Files.delete(target.getParent());
    Files.createSymbolicLink(target.getParent(), other.getParent());
    assertThrows(IOException.class, () -> WebSettingsDocument.readCurrent(fs, directory.getRoot()));
  }
}
