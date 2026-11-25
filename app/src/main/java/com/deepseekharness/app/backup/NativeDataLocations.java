package com.deepseekharness.app.backup;

import android.content.Context;
import android.os.Environment;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.FileKind;
import com.deepseekharness.app.util.ProfileConfigPath;
import java.io.*;
import java.util.*;

/** 宿主侧定位旧/新布局，不执行 Bash/Python/Node，不因 rootfs 缺失而忽略公开对话数据。 */
public final class NativeDataLocations {
  public static final Set<String> SCOPES = com.deepseekharness.app.util.BackupScopes.ALL;
  private static final Set<String> SESSION_NAMES =
      new HashSet<>(Arrays.asList("sessions", "storages", "attachments"));
  private static final Set<String> PLUGIN_NAMES =
      new HashSet<>(
          Arrays.asList(
              "profiles",
              "node_modules",
              "plugin-src",
              "plugin-sources.json",
              "plugin-history",
              "plugin-safe-mode.json",
              ".dsha-restored-packages",
              ".dsha-restored-originals",
              ".dsha-plugin-restore.json"));
  private static final String LEGACY_PRESETS = ".agent-presets";
  private static final String OUTSIDE_SETTINGS = "OUTSIDE_SETTINGS_SCOPE";
  private static final Set<String> LEGACY_PRESET_FILES = Set.of("preset.yml", "agent.cordis.yml");

  public static final class Selection {
    public String scope = "application";
    public boolean includeApiKey, includeProviderCredentials;
    public String retainedKey = "";
    public final List<String> guestProjects = new ArrayList<>();
    public final List<BackupSource> documentProjects = new ArrayList<>();
  }

  public static final class Located {
    public final List<BackupSource> sources = new ArrayList<>();

    /** 仅本机可信定位信息，用于恢复计划；不接受归档中的绝对目标路径。 */
    public final Map<String, String> guestTargets = new LinkedHashMap<>();

    public final List<String> notices = new ArrayList<>();
    public boolean possibleExistingData;
    public NativePluginGraph plugins;
  }

  private final Context context;
  private final BackupFileSystem fs;
  private final File files, rootfs, publicStorage, publicData;
  private final GuestDataResolver resolver;

  public NativeDataLocations(Context context) throws IOException {
    this(context, new AndroidBackupFileSystem());
  }

  /** 测试夹具注入宿主文件系统实现；生产只经上面的入口使用 Android lstat。 */
  NativeDataLocations(Context context, BackupFileSystem fs) throws IOException {
    this.context = context.getApplicationContext();
    this.fs = fs;
    files = context.getFilesDir().getCanonicalFile();
    rootfs = com.deepseekharness.app.util.GuestPaths.rootfs(files);
    publicStorage = Environment.getExternalStorageDirectory().getCanonicalFile();
    publicData = new File(publicStorage, "Documents/dshdata");
    resolver =
        new GuestDataResolver(
            fs, rootfs, publicStorage, Arrays.asList(new File(files, "user-data-v5"), publicData));
  }

  public File files() {
    return files;
  }

  public BackupFileSystem filesystem() {
    return fs;
  }

  /** 一键备份入口经宿主定位器读取工作区，界面编排不直接依赖核心配置。 */
  public static String workdir(Context context) {
    return new ConfigStore(context).getWorkdir();
  }

  public Located locate(Selection selection) throws IOException {
    return locate(selection, new BackupControl(null));
  }

  public Located locate(Selection selection, BackupControl control) throws IOException {
    if (!SCOPES.contains(selection.scope)) throw new IOException("UNKNOWN_SCOPE");
    Located result = new Located();
    File data = new UserDataLayout(fs, files).current();
    if (selection.retainedKey != null && !selection.retainedKey.isEmpty()) {
      RetainedCatalogue catalogue = new RetainedCatalogue(fs, files, data);
      var entry = catalogue.resolve(selection.retainedKey);
      if (!entry.scope.equals(selection.scope) || selection.includeApiKey)
        throw new IOException("RETAINED_SCOPE_CHANGED");
      result.sources.addAll(catalogue.sources(entry));
      applyCredentialSelection(result, selection);
      result.possibleExistingData = true;
      result.notices.add("RETAINED_READ_ONLY_SOURCE");
      return result;
    }
    if (!selection.scope.equals("projects")) {
      try {
        GuestDataResolver.Resolved actual = resolver.resolve(data);
        BackupFileSystem.Node status = fs.stat(actual.file);
        if (status.type.equals("DIRECTORY")) {
          if (selection.scope.equals("application") || selection.scope.equals("plugins")) {
            try {
              result.plugins =
                  new NativePluginGraph(
                      context,
                      fs,
                      resolver,
                      actual.file,
                      new File(rootfs, "usr/local/lib/node_modules"),
                      rootfs);
              result.plugins.collect(control);
            } catch (InterruptedIOException cancelled) {
              throw cancelled;
            } catch (IOException error) {
              result.plugins = null;
              result.notices.add("PLUGIN_GRAPH_UNAVAILABLE");
              result.sources.add(
                  new UnavailableBackupSource(
                      "plugin-graph-unavailable", "plugins", BackupErrorCode.from(error), false));
            }
          }
          result.possibleExistingData = true;
          if (selection.scope.equals("settings")) {
            for (String name :
                List.of(
                    "settings.yaml",
                    "settings.yaml.imported",
                    "cordis.patch.yml",
                    LEGACY_PRESETS)) {
              control.check();
              addSelectedSetting(
                  result, new File(data, name), new File(actual.file, name), name, false);
            }
            // Legacy models and current provider/plugin settings may live in different profiles.
            addProfileSettings(result, data, actual.file);
          } else {
            for (String name : fs.list(actual.file)) {
              control.check();
              // 历史离线备份可能在 .dsh 留下明文 .dsha-apikey。
              // v5 只允许通过 native-settings + includeApiKey 显式导出原生凭据。
              if (name.equals(".dsha-apikey")) continue;
              BackupLimits.path(name);
              if (name.contains("/")) throw new IOException("DATA_NAME");
              String scope = classify(name);
              if (selection.scope.equals("application") || selection.scope.equals(scope))
                add(
                    result,
                    id(name),
                    scope,
                    new File(data, name),
                    "root/.dsh/" + name,
                    DataRootPolicy.machine(name));
            }
          }
        } else if (status.type.equals("MISSING")) discoverPublic(result, selection);
        else {
          result.possibleExistingData = true;
          result.sources.add(
              new UnavailableBackupSource("dsh", "application", "DATA_ROOT_TYPE", false));
        }
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException error) {
        result.possibleExistingData = true;
        result.sources.add(
            new UnavailableBackupSource("dsh", "application", "DATA_ROOT_UNREADABLE", false));
      }
    }
    if (selection.scope.equals("application") || selection.scope.equals("settings")) {
      result.sources.add(
          new ValueBackupSource(
              "native-settings",
              "settings",
              () -> {
                try {
                  return new ConfigStore(context)
                      .exportPortableSettings(selection.includeApiKey)
                      .toString()
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } catch (org.json.JSONException error) {
                  throw new IOException("SETTINGS_FORMAT", error);
                }
              }));
      // dsh 0.1.7 imports the legacy settings.yaml into the active web
      // profile. Keep that profile layer in a settings-only archive so
      // an upgrade does not silently lose provider/model configuration.
      if (selection.scope.equals("application")) addProfileSettings(result, data, null);
    }
    for (String project : selection.guestProjects) {
      String relative = project.startsWith("/") ? project.substring(1) : "root/" + project;
      BackupLimits.path(relative);
      control.check();
      if (relative.equals("root")
          || relative.equals("usr")
          || relative.equals("etc")
          || relative.startsWith("proc/")
          || relative.startsWith("sys/")
          || relative.startsWith("dev/")) throw new IOException("PROJECT_SCOPE_TOO_BROAD");
      File projectRoot = resolver.guest("/" + relative);
      GuestDataResolver projectResolver =
          new GuestDataResolver(
              fs,
              rootfs,
              publicStorage,
              Arrays.asList(new File(files, "user-data-v5"), projectRoot));
      add(
          result,
          "project-" + hash(relative),
          "projects",
          projectRoot,
          relative,
          false,
          projectResolver);
    }
    for (BackupSource source : selection.documentProjects)
      result.sources.add(
          source instanceof SafBackupSource
              ? ((SafBackupSource) source).copyForOperation()
              : source);
    if (result.plugins != null) result.sources.addAll(result.plugins.sources);
    applyCredentialSelection(result, selection);
    if (result.sources.isEmpty()) result.notices.add("NO_SELECTED_DATA");
    return result;
  }

  private static void applyCredentialSelection(Located result, Selection selection) {
    result.sources.replaceAll(
        source ->
            CredentialFileBackupSource.forExport(source, selection.includeProviderCredentials));
  }

  private void discoverPublic(Located result, Selection selection) throws IOException {
    result.notices.add("ROOTFS_DATA_MISSING");
    boolean readable = false;
    for (String name : Arrays.asList("sessions", "storages", "attachments", "settings.yaml")) {
      String scope = classify(name);
      if (!selection.scope.equals("application") && !selection.scope.equals(scope)) continue;
      File source = new File(publicData, name);
      try {
        if (selection.scope.equals("settings")) {
          if (addSelectedSetting(result, source, source, name, false)) {
            result.possibleExistingData = true;
            readable = true;
          }
          continue;
        }
        BackupFileSystem.Node node = fs.stat(source);
        if (!node.type.equals("MISSING")) {
          result.possibleExistingData = true;
          readable = true;
          add(result, id(name), scope, source, "root/.dsh/" + name, false);
        }
      } catch (IOException error) {
        result.possibleExistingData = true;
        result.sources.add(
            new UnavailableBackupSource(id(name), scope, "PUBLIC_DATA_PERMISSION", false));
      }
    }
    if (!readable && new ConfigStore(context).isWelcomed()) {
      result.possibleExistingData = true;
      result.sources.add(
          new UnavailableBackupSource(
              "legacy-data", "application", "OLD_DATA_LOCATION_UNCONFIRMED", false));
    }
  }

  private void addProfileSettings(Located result, File data, File selectedHome) throws IOException {
    boolean settingsOnly = selectedHome != null;
    File profiles = new File(settingsOnly ? selectedHome : data, "profiles");
    FileKind kind = fs.stat(profiles).kind;
    if (kind == FileKind.MISSING || (settingsOnly && kind != FileKind.DIRECTORY)) return;
    File actual = resolver.resolve(profiles).file;
    if (settingsOnly && !actual.equals(profiles)) return;
    List<String> names = fs.list(actual);
    if (names.size() > 512) throw new IOException("PROFILE_LIMIT");
    for (String profileName : names) {
      // DSH reserves this sibling for shared dependencies; it cannot be a profile name.
      if (settingsOnly && profileName.equals("node_modules")) continue;
      if (!ProfileConfigPath.profile(profileName)) {
        result.notices.add("PROFILE_NAME_REQUIRES_REVIEW");
        continue;
      }
      File profile = new File(profiles, profileName);
      if (settingsOnly && fs.stat(profile).kind != FileKind.DIRECTORY) continue;
      for (String name : new String[] {"package.json", "cordis.patch.yml"}) {
        File source = new File(profile, name);
        String relative = "profiles/" + profileName + "/" + name;
        if (settingsOnly) {
          addSelectedSetting(result, new File(data, relative), source, relative, true);
          continue;
        }
        if (!fs.stat(source).type.equals("FILE")) continue;
        String id = "profile-config-" + hash(relative);
        result.guestTargets.put(id, "root/.dsh/" + relative);
        File resolved = resolver.resolve(source).file;
        if (CredentialFileBackupSource.providerName(resolved.getName())) continue;
        result.sources.add(
            new FileBackupSource(fs, id, "settings", resolved, false, (path, node) -> "") {
              @Override
              public Map<String, Object> description() {
                Map<String, Object> value = super.description();
                value.put("logicalKind", "dsh-profile-config");
                value.put("name", relative);
                return value;
              }
            });
      }
    }
  }

  /** Settings exports carry configuration declarations; the legacy classifier still maps imports. */
  private boolean addSelectedSetting(
      Located result, File origin, File source, String name, boolean profile) throws IOException {
    boolean legacyPresets = name.equals(LEGACY_PRESETS);
    if (fs.stat(source).kind != selectedSettingKind("", legacyPresets)) return false;
    GuestDataResolver.Resolved resolved = resolver.resolve(origin);
    if (!resolved.file.equals(source)) throw new IOException("DATA_LOCATION_CHANGED");
    String id = profile ? "profile-config-" + hash(name) : id(name);
    FileBackupSource.Policy policy =
        new FileBackupSource.Policy() {
          @Override
          public String before(String path) {
            return selectedSettingKind(path, legacyPresets) == null ? OUTSIDE_SETTINGS : "";
          }

          @Override
          public String exclusion(String path, BackupFileSystem.Node node) throws IOException {
            if (node.kind != selectedSettingKind(path, legacyPresets)) return OUTSIDE_SETTINGS;
            if (legacyPresets && !path.isEmpty() && node.kind == FileKind.DIRECTORY) {
              for (String file : LEGACY_PRESET_FILES)
                if (fs.stat(fs.child(source, path + "/" + file)).kind == FileKind.FILE) return "";
              return OUTSIDE_SETTINGS;
            }
            return "";
          }
        };
    result.guestTargets.put(id, "root/.dsh/" + name);
    result.sources.add(
        new FileBackupSource(
            fs,
            id,
            "settings",
            source,
            !profile && !GuestDataResolver.within(rootfs, source),
            policy) {
          @Override
          public Map<String, Object> description() {
            Map<String, Object> value = super.description();
            value.put("logicalKind", profile ? "dsh-profile-config" : "dsh-child");
            value.put("name", name);
            return value;
          }

          @Override
          protected File locate(String path) throws IOException {
            GuestDataResolver.Resolved now = resolver.resolve(origin);
            if (!now.file.equals(source) || !now.proof.equals(resolved.proof))
              throw new IOException("DATA_LOCATION_CHANGED");
            return super.locate(path);
          }

          @Override
          public void walk(Visit visitor, BackupControl control) throws IOException {
            super.walk(
                item -> {
                  if (!item.reason.equals(OUTSIDE_SETTINGS)) visitor.item(item);
                },
                control);
          }
        });
    return true;
  }

  private static FileKind selectedSettingKind(String path, boolean legacyPresets) {
    if (path.isEmpty()) return legacyPresets ? FileKind.DIRECTORY : FileKind.FILE;
    if (!legacyPresets) return null;
    String[] parts = path.split("/", -1);
    if (!ProfileConfigPath.profile(parts[0])) return null;
    if (parts.length == 1) return FileKind.DIRECTORY;
    if (parts.length == 2 && LEGACY_PRESET_FILES.contains(parts[1])) return FileKind.FILE;
    return null;
  }

  private void add(
      Located result, String id, String scope, File origin, String guest, boolean machine) {
    add(result, id, scope, origin, guest, machine, resolver);
  }

  private void add(
      Located result,
      String id,
      String scope,
      File origin,
      String guest,
      boolean machine,
      GuestDataResolver resolver) {
    result.guestTargets.put(id, guest);
    try {
      GuestDataResolver.Resolved resolved = resolver.resolve(origin);
      File target = resolved.file;
      boolean outside = !GuestDataResolver.within(rootfs, target);
      BackupSource source =
          new FileBackupSource(
              fs,
              id,
              scope,
              target,
              outside,
              (path, node) -> {
                String excluded =
                    scope.equals("projects")
                        ? ""
                        : DataRootPolicy.exclusion(new File(guest).getName(), path);
                return !excluded.isEmpty()
                    ? excluded
                    : machine
                        ? "DEVICE_SPECIFIC_STATE"
                        : result.plugins == null
                            ? ""
                            : result.plugins.exclusion(new File(target, path));
              },
              resolver) {
            @Override
            public Map<String, Object> description() {
              Map<String, Object> description = super.description();
              description.put("logicalKind", scope.equals("projects") ? "project" : "dsh-child");
              description.put("name", new File(guest).getName());
              return description;
            }

            @Override
            protected File locate(String relative) throws IOException {
              // 机器状态（桥端口 / 状态等）整项排除、不读内容；停 Web 时会被删除或原子重写，
              // 不能因位置变化让整份备份失败。
              if (machine) return super.locate(relative);
              GuestDataResolver.Resolved now = resolver.resolve(origin);
              if (!now.proof.equals(resolved.proof) || !now.file.equals(resolved.file))
                throw new IOException("DATA_LOCATION_CHANGED");
              return super.locate(relative);
            }
          };
      result.sources.add(source);
    } catch (IOException error) {
      result.sources.add(new UnavailableBackupSource(id, scope, "DATA_LOCATION_UNREADABLE", false));
    }
  }

  /** Historic root mapping for reading existing archives and the full application export. */
  public static String classify(String name) {
    return SESSION_NAMES.contains(name)
        ? "sessions"
        : PLUGIN_NAMES.contains(name) ? "plugins" : "settings";
  }

  public static String id(String name) {
    return SESSION_NAMES.contains(name) || name.equals("profiles") ? name : "data-" + hash(name);
  }

  public static String hash(String text) {
    return BackupArchive.hex(
            BackupArchive.sha().digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .substring(0, 20);
  }
}
