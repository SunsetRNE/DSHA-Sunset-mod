package com.deepseekharness.app.backup;

import java.io.*;
import java.util.List;

/** Cleanup boundary for derived, private plaintext after restore recovery is terminal. */
public final class RestoreStaging {
  private RestoreStaging() {}

  public static synchronized void clearSensitivePlaintext(BackupFileSystem fs, File operation)
      throws IOException {
    if (!safeToClear(fs, operation)) throw new IOException("RESTORE_TRANSACTION_UNRESOLVED");
    for (String name : DIRECTORIES) {
      File slots = fs.child(operation, name);
      if (fs.stat(slots).type.equals("MISSING")) continue;
      if (!fs.stat(slots).type.equals("DIRECTORY")) throw new IOException("PLAINTEXT_STAGING_TYPE");
      List<String> children = fs.list(slots);
      // The two readers create only flat, numbered payload files. An unexpected member is retained.
      for (String child : children)
        if (!child.matches("0|[1-9][0-9]{0,8}")
            || !fs.stat(fs.child(slots, child)).type.equals("FILE"))
          throw new IOException("PLAINTEXT_STAGING_TYPE");
      for (String child : children) deleteFile(fs, fs.child(slots, child));
      fs.delete(slots);
    }
    for (String name : FILES) deleteFile(fs, fs.child(operation, name));
  }

  private static final List<String> DIRECTORIES = List.of("payload", "legacy-payload");
  private static final List<String> FILES =
      List.of(
          "restore.dshdata",
          "snapshot.dshdata",
          "transport.dshbak",
          "capture",
          "export.tar.gz",
          "export-verified.dshbak",
          "reexport-authenticated.dshdata",
          "reexport-plain.dshbak",
          "reexport-protected.dshbak");

  static boolean present(BackupFileSystem fs, File operation) throws IOException {
    for (String name : FILES)
      if (!fs.stat(fs.child(operation, name)).type.equals("MISSING")) return true;
    for (String name : DIRECTORIES)
      if (!fs.stat(fs.child(operation, name)).type.equals("MISSING")) return true;
    return false;
  }

  static boolean safeToClear(BackupFileSystem fs, File operation) throws IOException {
    boolean switching = marker(fs, operation, "switching"),
        finalized = marker(fs, operation, "finalized"),
        rolledBack = marker(fs, operation, "rolled-back");
    if (finalized && rolledBack) throw new IOException("RESTORE_TRANSACTION_AMBIGUOUS");
    return (!switching || finalized || rolledBack)
        && (!marker(fs, operation, "rolling-back") || rolledBack)
        && !HostPendingTransactions.rotationPending(fs, operation);
  }

  private static void deleteFile(BackupFileSystem fs, File file) throws IOException {
    BackupFileSystem.Node node = fs.stat(file);
    if (node.type.equals("MISSING")) return;
    if (!node.type.equals("FILE")) throw new IOException("PLAINTEXT_STAGING_TYPE");
    fs.deleteChecked(file, node);
  }

  private static boolean marker(BackupFileSystem fs, File operation, String name)
      throws IOException {
    return TransactionMarkers.read(fs, operation, name, "RESTORE_TRANSACTION_MARKER");
  }
}
