package com.deepseekharness.app.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模块清单目录：启动时加载、只读投影，绝不执行清单代码。 */
public final class ModuleRegistry {
  private static final String ASSET_ROOT = "modules";
  private static final String FILE_ROOT = "modules";
  private static final String PREFS = "dsha_ui_module_state";
  private static final String ENABLED_PREFIX = "enabled.";

  private ModuleRegistry() {}

  public static Snapshot load(Context context) {
    if (context == null) throw new NullPointerException("context");
    Map<String, ModuleManifest> modules = new LinkedHashMap<>();
    List<Issue> issues = new ArrayList<>();
    loadBuiltins(context, modules, issues);
    loadInstalled(context, modules, issues);

    // 启停状态与主页卡片共用 ModuleState，避免同一个模块有两个开关。
    // 旧版 dsha_ui_module_state 里已有的值按 id 迁移一次，之后只读新存储。
    SharedPreferences legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    Map<String, Boolean> enabled = new LinkedHashMap<>();
    for (String id : modules.keySet()) {
      Boolean legacyValue = null;
      String legacyKey = ENABLED_PREFIX + id;
      if (legacy.contains(legacyKey)) {
        legacyValue = legacy.getBoolean(legacyKey, true);
        legacy.edit().remove(legacyKey).apply();
      }
      if (ModuleState.enabledOrNull(context, ModuleState.SCOPE_MANIFEST, id) == null
          && legacyValue != null) {
        ModuleState.setEnabled(context, ModuleState.SCOPE_MANIFEST, id, legacyValue);
      }
      enabled.put(id, ModuleState.isEnabled(context, ModuleState.SCOPE_MANIFEST, id));
    }
    return new Snapshot(modules, enabled, issues);
  }

  public static void setEnabled(Context context, String id, boolean enabled) {
    if (context == null) throw new NullPointerException("context");
    if (!isId(id)) throw new IllegalArgumentException("模块 id 无效");
    ModuleState.setEnabled(context, ModuleState.SCOPE_MANIFEST, id, enabled);
  }

  public static ModuleManifest installUserManifest(Context context, String source)
      throws IOException {
    if (context == null) throw new NullPointerException("context");
    ModuleManifest manifest = ModuleManifest.parse(source, false);
    File root = new File(context.getFilesDir(), FILE_ROOT);
    if (root.exists() && (!root.isDirectory() || java.nio.file.Files.isSymbolicLink(root.toPath())))
      throw new IOException("MODULE_ROOT_UNSAFE");
    if (!root.exists() && !root.mkdirs()) throw new IOException("MODULE_ROOT_CREATE");
    File directory = new File(root, manifest.id());
    if (directory.exists()
        && (!directory.isDirectory() || java.nio.file.Files.isSymbolicLink(directory.toPath())))
      throw new IOException("MODULE_DIRECTORY_UNSAFE");
    if (!directory.exists() && !directory.mkdirs())
      throw new IOException("MODULE_DIRECTORY_CREATE");
    File target = new File(directory, "module.json");
    File temporary = new File(directory, ".module.json." + java.util.UUID.randomUUID());
    try (java.io.OutputStream output = new java.io.FileOutputStream(temporary)) {
      output.write(source.getBytes(StandardCharsets.UTF_8));
    }
    try {
      java.nio.file.Files.move(
          temporary.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      java.nio.file.Files.deleteIfExists(temporary.toPath());
    }
    // 用户自己选的清单：装完即启用。容器投递的路径在 ModuleCardStore 里走另一套（默认关闭）。
    ModuleState.setEnabled(context, ModuleState.SCOPE_MANIFEST, manifest.id(), true);
    return manifest;
  }

  public static void clearEnabledState(Context context, String id) {
    if (context == null) throw new NullPointerException("context");
    if (!isId(id)) throw new IllegalArgumentException("模块 id 无效");
    ModuleState.clear(context, id);
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(ENABLED_PREFIX + id)
        .apply();
  }

  /**
   * 卸载用户清单：只删 module.json 与本状态，不碰同目录里的 card.json / 图片。
   *
   * <p>目录里还有别的模块文件时保留目录，避免把同一 id 的主页卡片一起删掉。
   */
  public static boolean uninstallUserManifest(Context context, String id) throws IOException {
    if (context == null) throw new NullPointerException("context");
    if (!isId(id)) throw new IllegalArgumentException("模块 id 无效");
    File directory = new File(new File(context.getFilesDir(), FILE_ROOT), id);
    if (!directory.isDirectory() || java.nio.file.Files.isSymbolicLink(directory.toPath()))
      return false;
    File manifest = new File(directory, "module.json");
    if (!manifest.isFile() || java.nio.file.Files.isSymbolicLink(manifest.toPath())) return false;
    java.nio.file.Files.delete(manifest.toPath());
    String[] rest = directory.list();
    if (rest != null && rest.length == 0) {
      java.nio.file.Files.deleteIfExists(directory.toPath());
    }
    clearEnabledState(context, id);
    return true;
  }

  private static void loadBuiltins(
      Context context, Map<String, ModuleManifest> modules, List<Issue> issues) {
    AssetManager assets = context.getAssets();
    String[] names;
    try {
      names = assets.list(ASSET_ROOT);
    } catch (IOException error) {
      issues.add(new Issue(ASSET_ROOT, "无法列出内置模块：" + message(error)));
      return;
    }
    if (names == null) return;
    java.util.Arrays.sort(names);
    for (String name : names) {
      if (!isId(name)) {
        issues.add(new Issue(ASSET_ROOT + "/" + name, "模块目录名无效"));
        continue;
      }
      String path = ASSET_ROOT + "/" + name + "/module.json";
      try {
        String source = readAsset(assets, path);
        ModuleManifest manifest = ModuleManifest.parse(source, true);
        if (!name.equals(manifest.id())) throw new IllegalArgumentException("目录名与清单 id 不一致");
        putIfUnique(modules, manifest, path, issues);
      } catch (Exception error) {
        issues.add(new Issue(path, message(error)));
      }
    }
  }

  private static void loadInstalled(
      Context context, Map<String, ModuleManifest> modules, List<Issue> issues) {
    File root = new File(context.getFilesDir(), FILE_ROOT);
    if (root.exists()
        && (!root.isDirectory() || java.nio.file.Files.isSymbolicLink(root.toPath()))) {
      issues.add(new Issue(root.getPath(), "用户模块根目录不是安全的私有目录"));
      return;
    }
    File[] directories =
        root.listFiles(
            file -> file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath()));
    if (directories == null) return;
    java.util.Arrays.sort(directories, Comparator.comparing(File::getName));
    for (File directory : directories) {
      String name = directory.getName();
      if (!isId(name)) {
        issues.add(new Issue(directory.getPath(), "模块目录名无效"));
        continue;
      }
      File manifestFile = new File(directory, "module.json");
      // 没有 module.json 的目录属于主页卡片模块（card.json），不是坏清单，直接跳过。
      if (!manifestFile.isFile()) continue;
      String path = manifestFile.getPath();
      try {
        ModuleManifest manifest = ModuleManifest.parse(readFile(manifestFile), false);
        if (!name.equals(manifest.id())) throw new IllegalArgumentException("目录名与清单 id 不一致");
        putIfUnique(modules, manifest, path, issues);
      } catch (Exception error) {
        issues.add(new Issue(path, message(error)));
      }
    }
  }

  private static void putIfUnique(
      Map<String, ModuleManifest> modules,
      ModuleManifest manifest,
      String path,
      List<Issue> issues) {
    if (modules.containsKey(manifest.id())) {
      issues.add(new Issue(path, "模块 id 与已有模块冲突：" + manifest.id()));
      return;
    }
    modules.put(manifest.id(), manifest);
  }

  private static String readAsset(AssetManager assets, String path) throws IOException {
    try (InputStream input = assets.open(path)) {
      return readStream(input);
    }
  }

  private static String readFile(File file) throws IOException {
    try (InputStream input = new java.io.FileInputStream(file)) {
      return readStream(input);
    }
  }

  private static String readStream(InputStream input) throws IOException {
    byte[] bytes = new byte[8192];
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    int count;
    while ((count = input.read(bytes)) != -1) {
      if (output.size() + count > 512 * 1024) throw new IOException("MODULE_MANIFEST_LIMIT");
      output.write(bytes, 0, count);
    }
    return output.toString(StandardCharsets.UTF_8.name());
  }

  private static boolean isId(String id) {
    return id != null && id.matches("[a-z][a-z0-9._-]{0,63}");
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
  }

  public static final class Issue {
    private final String source, message;

    Issue(String source, String message) {
      this.source = source;
      this.message = message;
    }

    public String source() {
      return source;
    }

    public String message() {
      return message;
    }
  }

  public static final class Snapshot {
    private final Map<String, ModuleManifest> modules;
    private final Map<String, Boolean> enabled;
    private final List<Issue> issues;

    Snapshot(
        Map<String, ModuleManifest> modules, Map<String, Boolean> enabled, List<Issue> issues) {
      this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
      this.enabled = Collections.unmodifiableMap(new LinkedHashMap<>(enabled));
      this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }

    public Map<String, ModuleManifest> modules() {
      return modules;
    }

    public List<ModuleManifest> enabledModules() {
      List<ModuleManifest> result = new ArrayList<>();
      for (Map.Entry<String, ModuleManifest> entry : modules.entrySet())
        if (enabled.getOrDefault(entry.getKey(), true)) result.add(entry.getValue());
      return Collections.unmodifiableList(result);
    }

    public boolean isEnabled(String id) {
      return enabled.getOrDefault(id, false);
    }

    public List<ModuleManifest.Navigation> nav() {
      List<ModuleManifest.Navigation> result = new ArrayList<>();
      for (ModuleManifest manifest : enabledModules()) result.addAll(manifest.navigation());
      return Collections.unmodifiableList(result);
    }

    public List<ModuleManifest.Section> sections(String page) {
      List<ModuleManifest.Section> result = new ArrayList<>();
      for (ModuleManifest manifest : enabledModules())
        for (ModuleManifest.Section section : manifest.sections())
          if (page == null ? section.page() == null : page.equals(section.page()))
            result.add(section);
      return Collections.unmodifiableList(result);
    }

    public Map<String, String> tokens() {
      Map<String, String> result = new LinkedHashMap<>();
      for (ModuleManifest manifest : enabledModules())
        for (Map.Entry<String, String> token : manifest.tokens().entrySet())
          result.putIfAbsent(token.getKey(), token.getValue());
      return Collections.unmodifiableMap(result);
    }

    public List<Issue> issues() {
      return issues;
    }
  }
}
