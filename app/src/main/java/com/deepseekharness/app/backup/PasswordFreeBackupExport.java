package com.deepseekharness.app.backup;

import java.io.*;
import java.util.Map;

/** Repackages a verified copy for a new destination while preserving its source and actual scope. */
public final class PasswordFreeBackupExport {
  public final File artifact;
  public final String sha256;
  public final Map<String, Object> manifest;

  private PasswordFreeBackupExport(File artifact, String sha256, Map<String, Object> manifest) {
    this.artifact = artifact;
    this.sha256 = sha256;
    this.manifest = manifest;
  }

  public static PasswordFreeBackupExport prepare(
      BackupFileSystem fs,
      VerifiedBackupCopy source,
      File operation,
      char[] originalPassword,
      BackupControl control)
      throws IOException {
    return prepare(fs, source, operation, originalPassword, null, control);
  }

  static PasswordFreeBackupExport prepare(
      BackupFileSystem fs,
      VerifiedBackupCopy source,
      File operation,
      char[] originalPassword,
      char[] exportPassword,
      BackupControl control)
      throws IOException {
    if (exportPassword != null && !PortableBackupCrypto.validPassword(exportPassword))
      throw new IOException("PASSWORD_LENGTH");
    source.verify(fs, control);
    File decrypted = fs.child(operation, "reexport-authenticated.dshdata"),
        output = fs.child(operation, "reexport-plain.dshbak"),
        protectedCopy = fs.child(operation, "reexport-protected.dshbak");
    if (!fs.stat(decrypted).type.equals("MISSING")
        || !fs.stat(output).type.equals("MISSING")
        || !fs.stat(protectedCopy).type.equals("MISSING"))
      throw new IOException("REEXPORT_STAGING_EXISTS");
    File input = source.artifact;
    IOException failed = null;
    try {
      if (source.passwordProtected(fs)) {
        if (originalPassword == null) throw new IOException("ORIGINAL_BACKUP_PASSWORD_REQUIRED");
        java.security.MessageDigest checked = BackupArchive.sha();
        try (InputStream encrypted =
                new java.security.DigestInputStream(
                    fs.read(source.artifact, fs.stat(source.artifact)), checked);
            OutputStream plaintext = fs.create(decrypted)) {
          PortableBackupCrypto.decrypt(encrypted, plaintext, originalPassword, control);
        }
        if (!source.sha256.equals(BackupArchive.hex(checked.digest())))
          throw new IOException("VERIFIED_COPY_CHANGED");
        input = decrypted;
      }
      Map<String, Object> manifest;
      java.security.MessageDigest checked = BackupArchive.sha();
      try (InputStream old =
              new java.security.DigestInputStream(fs.read(input, fs.stat(input)), checked);
          OutputStream fresh = fs.create(output)) {
        manifest = BackupArchive.copyWithProtection(old, fresh, control, exportPassword != null);
      }
      if (input.equals(source.artifact)
          && !source.sha256.equals(BackupArchive.hex(checked.digest())))
        throw new IOException("VERIFIED_COPY_CHANGED");
      try (InputStream fresh = fs.read(output, fs.stat(output))) {
        BackupArchive.read(fresh, null, control);
      }
      String hash;
      try (InputStream fresh = fs.read(output, fs.stat(output))) {
        hash = BackupArchive.digest(fresh, control);
      }
      if (exportPassword == null) return new PasswordFreeBackupExport(output, hash, manifest);
      PortableBackupCrypto.encryptVerified(
          fs, output, hash, protectedCopy, exportPassword, control);
      fs.delete(output);
      try (InputStream fresh = fs.read(protectedCopy, fs.stat(protectedCopy))) {
        return new PasswordFreeBackupExport(
            protectedCopy, BackupArchive.digest(fresh, control), manifest);
      }
    } catch (IOException failure) {
      failed = failure;
      try {
        if (fs.stat(output).type.equals("FILE")) fs.delete(output);
        if (fs.stat(protectedCopy).type.equals("FILE")) fs.delete(protectedCopy);
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    } finally {
      try {
        if (fs.stat(decrypted).type.equals("FILE")) fs.delete(decrypted);
      } catch (IOException cleanup) {
        if (failed != null) failed.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }
}
