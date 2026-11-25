package com.deepseekharness.app.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Projects a verified credential file without changing it; raw and projected bytes bind each item. */
public final class CredentialFileBackupSource implements BackupSource {
  private final BackupSource original;
  private final String rootName;
  private final boolean includeProviders;

  public CredentialFileBackupSource(BackupSource original) {
    this(original, CredentialProjection.FILE, true);
  }

  private CredentialFileBackupSource(
      BackupSource original, String rootName, boolean includeProviders) {
    this.original = original;
    this.rootName = rootName;
    this.includeProviders = includeProviders;
  }

  /** Apply the same policy to active, retained, project, SAF and plugin export sources. */
  static BackupSource forExport(BackupSource source, boolean includeProviders) {
    if (source instanceof CredentialFileBackupSource)
      source = ((CredentialFileBackupSource) source).original;
    String name = String.valueOf(source.description().getOrDefault("name", ""));
    String root = new File(name).getName();
    String actual = new File(source.displayLocation()).getName();
    if (providerName(actual)
        || com.deepseekharness.app.util.CredentialPaths.machine(actual)
        || actual.equals(com.deepseekharness.app.util.CredentialPaths.LEGACY_API_KEY))
      root = actual;
    return new CredentialFileBackupSource(source, root, includeProviders);
  }

  static boolean providerName(String name) {
    return name.equals(CredentialProjection.FILE)
        || name.equals(".env")
        || name.startsWith(".env.");
  }

  private boolean excludedName(String name) {
    return name.equals(com.deepseekharness.app.util.CredentialPaths.LEGACY_API_KEY)
        || com.deepseekharness.app.util.CredentialPaths.machine(name)
        || !includeProviders && providerName(name);
  }

  private boolean excluded(String path) {
    if (excludedName(rootName)) return true;
    for (String part : path.split("/")) if (excludedName(part)) return true;
    return false;
  }

  private boolean credentialFile(String path) {
    return (path.isEmpty() ? rootName : new File(path).getName()).equals(CredentialProjection.FILE);
  }

  public String id() {
    return original.id();
  }

  public String scope() {
    return original.scope();
  }

  public boolean external() {
    return original.external();
  }

  public String displayLocation() {
    return original.displayLocation();
  }

  public boolean containsLocal(File file) {
    return original.containsLocal(file);
  }

  public Map<String, Object> description() {
    return original.description();
  }

  public String category(Item item) {
    return original.category(item);
  }

  private byte[] raw(Item item, BackupControl control) throws IOException {
    try (InputStream input = original.open(item);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        control.check();
        if (count > CredentialProjection.LIMIT - output.size())
          throw new IOException("CREDENTIAL_PROJECTION_LIMIT");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static String hash(byte[] bytes) {
    return BackupArchive.hex(BackupArchive.sha().digest(bytes));
  }

  public void walk(Visit visitor, BackupControl control) throws IOException {
    if (excluded("")) {
      visitor.item(exclusion(""));
      return;
    }
    original.walk(
        item -> {
          if (excluded(item.path)) {
            int slash = item.path.lastIndexOf('/');
            if (slash < 0 || !excluded(item.path.substring(0, slash)))
              visitor.item(exclusion(item.path));
            return;
          }
          if (!credentialFile(item.path)
              || item.kind.equals("MISSING")
              || item.kind.equals("UNREADABLE")
              || item.kind.equals("EXCLUDED")) {
            visitor.item(item);
            return;
          }
          if (!item.kind.equals("FILE")) throw new IOException("CREDENTIAL_PROJECTION_SOURCE_TYPE");
          byte[] raw = raw(item, control), portable = CredentialProjection.portable(raw);
          visitor.item(
              new Item(
                  item.path,
                  "FILE",
                  portable.length,
                  item.token + ":" + hash(raw) + ":" + hash(portable),
                  "",
                  "",
                  item.mode));
        },
        control);
  }

  private Item exclusion(String path) {
    String name = path.isEmpty() ? rootName : new File(path).getName();
    return new Item(
        path,
        "EXCLUDED",
        0,
        "",
        "",
        providerName(name) ? "PROVIDER_CREDENTIALS_OMITTED" : "DEVICE_SPECIFIC_STATE");
  }

  public InputStream open(Item item) throws IOException {
    if (excluded(item.path)) throw new IOException("CREDENTIAL_EXPORT_EXCLUDED");
    if (!credentialFile(item.path)) return original.open(item);
    String[] token = item.token.split(":", -1);
    if (token.length != 3 || !token[1].matches("[a-f0-9]{64}") || !token[2].matches("[a-f0-9]{64}"))
      throw new IOException("CREDENTIAL_PROJECTION_ITEM");
    Item source = new Item(item.path, "FILE", 0, token[0], "", "", item.mode);
    byte[] raw = raw(source, new BackupControl(null));
    if (!token[1].equals(hash(raw))) throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    byte[] portable = CredentialProjection.portable(raw);
    if (portable.length != item.size || !token[2].equals(hash(portable)))
      throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    return new ByteArrayInputStream(portable);
  }

  public void verify(Item item, String sha256, BackupControl control) throws IOException {
    if (item.kind.equals("EXCLUDED")) return;
    if (excluded(item.path)) throw new IOException("CREDENTIAL_EXPORT_EXCLUDED");
    if (!credentialFile(item.path)) {
      original.verify(item, sha256, control);
      return;
    }
    try (InputStream input = open(item)) {
      if (!sha256.equals(BackupArchive.digest(input, control)))
        throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    }
  }
}
