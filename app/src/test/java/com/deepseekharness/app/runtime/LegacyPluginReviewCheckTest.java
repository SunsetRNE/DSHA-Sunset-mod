package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LegacyPluginReviewCheckTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  private static final String LEGACY = "DSHA_REVIEW_REQUIRED\n";

  private File home(String manifest) throws IOException {
    File home = temp.newFolder("dsh");
    Files.createDirectories(home.toPath().resolve("profiles/web/node_modules/@scope"));
    Files.writeString(home.toPath().resolve("profiles/web/package.json"), manifest);
    return home;
  }

  private Path marker(File home, String name) {
    return home.toPath().resolve("profiles/web/node_modules/" + name + ".disabled");
  }

  private boolean check(File home) throws IOException {
    return LegacyPluginReviewCheck.noLegacyReviews(fs, temp.getRoot(), home);
  }

  @Test
  public void actualEmptyManifestNeedsNoGuestOrDirectoryWalk() throws Exception {
    File home = home("{}");
    var readOnly =
        new JvmBackupFileSystem() {
          @Override
          public java.util.List<String> list(File path) {
            throw new AssertionError("No recursive catalog scan");
          }

          @Override
          public java.io.OutputStream create(File path) {
            throw new AssertionError("No data writes");
          }

          @Override
          public void delete(File path) {
            throw new AssertionError("No data deletion");
          }
        };
    assertTrue(LegacyPluginReviewCheck.noLegacyReviews(readOnly, temp.getRoot(), home));
  }

  @Test
  public void userDisabledAndSafeModeMarkersRemainByteIdentical() throws Exception {
    File home =
        home(
            "{\"dependencies\":{\"plain\":\"link:/root/.dsh/plugin-src/plain\",\"@scope/pkg\":\"1.0.0\"}}");
    Files.writeString(marker(home, "plain"), "");
    Files.writeString(marker(home, "@scope/pkg"), "DSHA_SAFE_MODE\n");
    assertTrue(check(home));
    assertEquals("", Files.readString(marker(home, "plain")));
    assertEquals("DSHA_SAFE_MODE\n", Files.readString(marker(home, "@scope/pkg")));
  }

  @Test
  public void exactLegacyMarkerAlwaysReturnsToExistingMigration() throws Exception {
    File home = home("{\"dependencies\":{\"plain\":\"link:/root/.dsh/plugin-src/plain\"}}");
    Files.writeString(marker(home, "plain"), LEGACY);
    assertFalse(check(home));
    assertEquals(LEGACY, Files.readString(marker(home, "plain")));
  }

  @Test
  public void builtinNamesDoNotGrantExemptions() throws Exception {
    File home = home("{\"dependencies\":{\"dsh-web-mobile\":\"link:/root/dsha-web-mobile\"}}");
    Files.writeString(marker(home, "dsh-web-mobile"), LEGACY);
    assertFalse(check(home));
  }

  @Test
  public void unknownReferenceAndInvalidNameCannotGrantSuccess() throws Exception {
    File home = home("{\"dependencies\":{\"../escape\":\"1\"}}");
    assertFalse(check(home));
    Files.writeString(
        home.toPath().resolve("profiles/web/package.json"),
        "{\"dependencies\":{\"plain\":{\"version\":\"1\"}}}");
    assertFalse(check(home));
  }

  @Test
  public void malformedOrDuplicateManifestNeverBecomesEmpty() throws Exception {
    File home = home("{\"dependencies\":{},\"dependencies\":{\"plain\":\"1\"}}");
    assertThrows(IOException.class, () -> check(home));
  }

  @Test
  public void markerLinkIsNotFollowedOrTreatedAsMissing() throws Exception {
    File home = home("{\"dependencies\":{\"plain\":\"1\"}}");
    Path outside = temp.newFile("outside").toPath();
    Files.writeString(outside, LEGACY);
    symlink(marker(home, "plain"), outside);
    assertFalse(check(home));
    assertEquals(LEGACY, Files.readString(outside));
  }

  @Test
  public void scopedAncestorLinkCannotEscapeSelectedHome() throws Exception {
    File home = home("{\"dependencies\":{\"@scope/pkg\":\"1\"}}");
    Path scope = marker(home, "@scope/pkg").getParent();
    Files.delete(scope);
    symlink(scope, temp.newFolder("outside").toPath());
    assertThrows(IOException.class, () -> check(home));
  }

  @Test
  public void sameSizeSameTimestampMarkerRewriteIsReRead() throws Exception {
    File home = home("{\"dependencies\":{\"plain\":\"1\"}}");
    Path marker = marker(home, "plain");
    Files.writeString(marker, "X".repeat(LEGACY.length()));
    FileTime modified = Files.getLastModifiedTime(marker);
    var racing =
        new JvmBackupFileSystem() {
          int reads;

          @Override
          public byte[] small(File path, int limit) throws IOException {
            if (path.toPath().equals(marker) && ++reads == 2) {
              Files.writeString(marker, LEGACY);
              Files.setLastModifiedTime(marker, modified);
            }
            return super.small(path, limit);
          }
        };
    assertFalse(LegacyPluginReviewCheck.noLegacyReviews(racing, temp.getRoot(), home));
    assertEquals(LEGACY, Files.readString(marker));
  }

  @Test
  public void sameSizeSameTimestampManifestRewriteIsReRead() throws Exception {
    File home = home("{\"dependencies\":{\"plain\":\"1\"}}");
    Path manifest = home.toPath().resolve("profiles/web/package.json");
    FileTime modified = Files.getLastModifiedTime(manifest);
    var racing =
        new JvmBackupFileSystem() {
          int reads;

          @Override
          public byte[] small(File path, int limit) throws IOException {
            if (path.toPath().equals(manifest) && ++reads == 2) {
              Files.writeString(manifest, "{\"dependencies\":{\"other\":\"1\"}}");
              Files.setLastModifiedTime(manifest, modified);
            }
            return super.small(path, limit);
          }
        };
    assertFalse(LegacyPluginReviewCheck.noLegacyReviews(racing, temp.getRoot(), home));
  }

  @Test
  public void newMarkerDuringSecondReadCannotGrantSuccess() throws Exception {
    File home = home("{\"dependencies\":{\"plain\":\"1\"}}");
    var racing =
        new JvmBackupFileSystem() {
          int reads;

          @Override
          public byte[] small(File path, int limit) throws IOException {
            if (path.getName().equals("package.json") && ++reads == 2)
              Files.writeString(marker(home, "plain"), LEGACY);
            return super.small(path, limit);
          }
        };
    assertFalse(LegacyPluginReviewCheck.noLegacyReviews(racing, temp.getRoot(), home));
  }

  @Test
  public void interruptedCheckCannotGrantSuccess() throws Exception {
    File home = home("{}");
    try {
      Thread.currentThread().interrupt();
      assertThrows(IOException.class, () -> check(home));
    } finally {
      Thread.interrupted();
    }
  }

  private static void symlink(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (java.nio.file.FileSystemException denied) {
      String reason = String.valueOf(denied.getReason()).toLowerCase(java.util.Locale.ROOT);
      if (System.getProperty("os.name").startsWith("Windows")
          && (reason.contains("privilege") || reason.contains("特权")))
        Assume.assumeNoException(
            "Windows denied real symlink creation; POSIX gate required", denied);
      throw denied;
    }
  }
}
