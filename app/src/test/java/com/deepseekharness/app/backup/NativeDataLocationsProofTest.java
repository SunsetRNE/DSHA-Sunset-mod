package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import android.content.Context;
import com.deepseekharness.app.core.ConfigStore;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 一键备份先定位、停 Web 时桥会删除 .bridge_port 并原子重写 .bridge_status。
 * 机器状态整项排除，不能因此让整份备份以 DATA_LOCATION_CHANGED 失败；用户数据仍失败关闭。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class NativeDataLocationsProofTest {
  private Context context;
  private File dsh;

  @Before
  public void setUp() throws Exception {
    context = org.robolectric.RuntimeEnvironment.getApplication();
    File files = context.getFilesDir().getCanonicalFile();
    dsh = new File(files, "linux/ubuntu/root/.dsh");
    assertTrue(new File(dsh, "sessions").mkdirs());
    Files.writeString(new File(dsh, "sessions/one.jsonl").toPath(), "{}\n");
    Files.writeString(new File(dsh, "settings.yaml").toPath(), "a: 1\n");
    Files.writeString(new File(dsh, ".bridge_status").toPath(), "ok port=3091\n");
    Files.writeString(new File(dsh, ".bridge_port").toPath(), "3091");
  }

  private NativeDataLocations.Located locate() throws IOException {
    return locate("application");
  }

  private NativeDataLocations.Located locate(String scope) throws IOException {
    var selection = new NativeDataLocations.Selection();
    selection.scope = scope;
    return new NativeDataLocations(context, new JvmBackupFileSystem()).locate(selection);
  }

  private void put(String path, String value) throws IOException {
    Path target = new File(dsh, path).toPath();
    Files.createDirectories(target.getParent());
    Files.writeString(target, value);
  }

  private static BackupSource source(NativeDataLocations.Located located, String name) {
    String id = NativeDataLocations.id(name);
    for (BackupSource source : located.sources) if (source.id().equals(id)) return source;
    throw new AssertionError("missing source " + name);
  }

  private static List<BackupSource.Item> walk(BackupSource source) throws IOException {
    List<BackupSource.Item> items = new ArrayList<>();
    source.walk(items::add, new BackupControl(null));
    return items;
  }

  /** 与 AndroidBackupFileSystem.atomic 相同：写临时文件后 rename，替换 inode。 */
  private static void replace(File file, String text) throws IOException {
    Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
    Files.writeString(tmp, text);
    Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE);
  }

  @Test
  public void bridgeStateRewrittenAfterLocateStaysExcluded() throws Exception {
    var located = locate();
    replace(new File(dsh, ".bridge_status"), "stopped\n");
    Files.delete(new File(dsh, ".bridge_port").toPath());
    for (String name : List.of(".bridge_status", ".bridge_port")) {
      List<BackupSource.Item> items = walk(source(located, name));
      assertEquals(1, items.size());
      assertEquals("EXCLUDED", items.get(0).kind);
      assertEquals("DEVICE_SPECIFIC_STATE", items.get(0).reason);
    }
  }

  @Test
  public void userDataReplacedAfterLocateStillFailsClosed() throws Exception {
    var located = locate();
    replace(new File(dsh, "settings.yaml"), "a: 2\n");
    List<BackupSource.Item> items = walk(source(located, "settings.yaml"));
    assertEquals("UNREADABLE", items.get(0).kind);
    assertEquals("DATA_LOCATION_CHANGED", items.get(0).reason);
  }

  @Test
  public void settingsArchiveSelectsOnlyConfigurationAcrossSupportedLayouts() throws Exception {
    var config = new ConfigStore(context);
    config.setWebPort("fixed", 7701);
    config.setWorkdir("/root/fixture-work");
    put("settings.yaml.imported", "retained-section: {model: fixture-legacy}\n");
    put("cordis.patch.yml", "- id: global-fixture\n");
    put("profiles/web/cordis.patch.yml", "- id: model-fixture\n");
    put("profiles/cli/package.json", "{\"name\":\"cli-fixture\"}\n");
    put("profiles/custom-team/cordis.patch.yml", "- id: custom-preset-fixture\n");
    put(".agent-presets/crew/preset.yml", "name: Fixture crew\n");
    put(".agent-presets/crew/agent.cordis.yml", "- id: legacy-fixture\n");
    put(".agent-presets/crew/cache/backup.tar.gz", "fixture archived copy");
    put(".agent-presets/crew/.credentials.yaml", "fictional-credential-marker");
    put(".agent-presets/crew/unknown.yml", "unrelated fixture");
    put(".agent-presets/index.json", "unrelated metadata");
    put("profiles/web/node_modules/installer/code.js", "fixture installed plugin");
    put("profiles/node_modules/package.json", "{\"name\":\"shared-dependency-fixture\"}\n");
    put("profiles/node_modules/cordis.patch.yml", "- id: shared-dependency-fixture\n");
    put("profiles/web/.env", "FIXTURE_PROFILE_SETTING_DONT_BUNDLE=1\n");
    put("retained/archives/nested.zip", "fixture zip bytes");
    put("retained/archives/nested.tar.gz", "fixture copied archive bytes");
    put("retained/plugin-src/package.json", "{\"name\":\"fixture-package\"}\n");
    put("cache/work/unknown.yaml", "fictional cache marker");
    put("unrecognized.txt", "fictional unknown marker");
    put(".credentials.yaml", "{}\n");
    put(".env", "FIXTURE_DSH_SETTING_DONT_BUNDLE=1\n");
    put(".dsha-apikey", "fictional-native-key-marker");

    var located = locate("settings");
    assertNull(located.plugins);
    var nativeSource =
        located.sources.stream()
            .filter(s -> s.id().equals("native-settings"))
            .findFirst()
            .orElseThrow();
    try (var input = nativeSource.open(walk(nativeSource).get(0))) {
      var nativeSettings =
          new org.json.JSONObject(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(7701, nativeSettings.getInt("port"));
      assertEquals("fixed", nativeSettings.getString("portMode"));
      assertEquals("/root/fixture-work", nativeSettings.getString("workdir"));
      assertFalse(nativeSettings.has("apiKey"));
    }

    var fs = new JvmBackupFileSystem();
    var control = new BackupControl(null);
    var task =
        Files.createDirectory(new File(context.getCacheDir(), "settings-fixture").toPath())
            .toFile();
    File archive = new File(task, "verified.dshdata");
    var preview = BackupPreview.inspect(located.sources, task.getUsableSpace(), control);
    var manifest =
        HostSnapshot.create(
            fs, located.sources, task, archive, BackupArchiveTest.summary(), false, control);
    var names = new HashMap<String, String>();
    for (var source : located.sources)
      names.put(
          source.id(), String.valueOf(source.description().getOrDefault("name", source.id())));
    var archivedFiles = new TreeSet<String>();
    var archivePaths = new TreeSet<String>();
    try (var input = fs.read(archive, fs.stat(archive))) {
      BackupArchive.read(
          input,
          new BackupArchive.Visitor() {
            @Override
            public OutputStream payload(int ordinal, BackupArchive.Record record) {
              String path =
                  names.get(record.root) + (record.path.isEmpty() ? "" : "/" + record.path);
              archivePaths.add(path);
              if (record.kind.equals("FILE")) archivedFiles.add(path);
              assertEquals("settings", record.scope);
              return null;
            }
          },
          control);
    }
    System.out.printf(
        "SETTINGS_FIXTURE roots=%d files=%d entries=%d logical=%d archive=%d usable=%d%n",
        located.sources.size(),
        preview.files,
        preview.entries,
        preview.bytes,
        archive.length(),
        preview.freeBytes);
    assertEquals(preview.bytes, manifest.get("bytes"));
    assertEquals(
        Set.of(
            "native-settings",
            "settings.yaml",
            "settings.yaml.imported",
            "cordis.patch.yml",
            "profiles/web/cordis.patch.yml",
            "profiles/cli/package.json",
            "profiles/custom-team/cordis.patch.yml",
            ".agent-presets/crew/preset.yml",
            ".agent-presets/crew/agent.cordis.yml"),
        archivedFiles);
    assertEquals(
        Set.of(".agent-presets", ".agent-presets/crew"),
        archivePaths.stream()
            .filter(path -> !archivedFiles.contains(path))
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  public void settingsExportDoesNotFollowARecognizedFileOrProfileLink() throws Exception {
    put("cordis.patch.yml", "placeholder");
    put("profiles/custom/cordis.patch.yml", "placeholder");
    put("unrelated/payload", "fictional linked bytes");
    put("unrelated/cordis.patch.yml", "fictional linked profile");
    File homePatch = new File(dsh, "cordis.patch.yml");
    File profile = new File(dsh, "profiles/custom");
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            if (homePatch.equals(file) || profile.equals(file))
              return new Node("LINK", "fixture-link", 0, 0, 0, 0700);
            return super.stat(file);
          }

          @Override
          public String readLink(File file) throws IOException {
            if (homePatch.equals(file)) return "unrelated/payload";
            if (profile.equals(file)) return "../unrelated";
            return super.readLink(file);
          }
        };
    var selection = new NativeDataLocations.Selection();
    selection.scope = "settings";
    var located = new NativeDataLocations(context, fs).locate(selection);
    assertEquals(
        Set.of("settings.yaml", "native-settings"),
        located.sources.stream()
            .map(root -> String.valueOf(root.description().getOrDefault("name", root.id())))
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  public void settingsExportAcceptsAStableHomeAliasButRejectsChangingItAfterLocate()
      throws Exception {
    put("../home-left/settings.yaml", "side: first\n");
    put("../home-left/profiles/web/cordis.patch.yml", "- id: first\n");
    put("../home-left/.agent-presets/legacy_crew/agent.cordis.yml", "- id: first\n");
    put("../home-right/settings.yaml", "side: second\n");
    put("../home-right/profiles/web/cordis.patch.yml", "- id: second\n");
    var alias = new java.util.concurrent.atomic.AtomicReference<>("home-left");
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            if (dsh.equals(file)) return new Node("LINK", "fixture-home-alias", 0, 0, 0, 0700);
            Path path = file.toPath();
            if (path.startsWith(dsh.toPath())) {
              Path backing = dsh.getParentFile().toPath().resolve(alias.get());
              return super.stat(backing.resolve(dsh.toPath().relativize(path)).toFile());
            }
            return super.stat(file);
          }

          @Override
          public String readLink(File file) throws IOException {
            return dsh.equals(file) ? alias.get() : super.readLink(file);
          }
        };
    var selection = new NativeDataLocations.Selection();
    selection.scope = "settings";
    var located = new NativeDataLocations(context, fs).locate(selection);
    var settings = source(located, "settings.yaml");
    var profile =
        located.sources.stream()
            .filter(root -> "profiles/web/cordis.patch.yml".equals(root.description().get("name")))
            .findFirst()
            .orElseThrow();
    var presets = source(located, ".agent-presets");
    var settingsItem = walk(settings).get(0);
    var profileItem = walk(profile).get(0);
    var presetItem =
        walk(presets).stream()
            .filter(item -> item.path.endsWith("agent.cordis.yml"))
            .findFirst()
            .orElseThrow();
    assertEquals("legacy_crew/agent.cordis.yml", presetItem.path);
    for (var data :
        Map.of(settings, settingsItem, profile, profileItem, presets, presetItem).entrySet()) {
      try (var input = data.getKey().open(data.getValue())) {
        assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("first"));
      }
    }
    alias.set("home-right");
    for (var data :
        Map.of(settings, settingsItem, profile, profileItem, presets, presetItem).entrySet()) {
      var items = walk(data.getKey());
      assertEquals(1, items.size());
      assertEquals("UNREADABLE", items.get(0).kind);
      assertEquals("DATA_LOCATION_CHANGED", items.get(0).reason);
      assertThrows(IOException.class, () -> data.getKey().open(data.getValue()));
    }
  }

  @Test
  public void otherExportScopesRetainTheirExplicitGroupsAndApplicationsCredentialProjection()
      throws Exception {
    put("attachments/fixture.bin", "fixture attachment");
    put("plugin-sources.json", "{}\n");
    put("retained/archive.zip", "fixture retained bytes");
    put(".credentials.yaml", "{}\n");
    put(".env", "FIXTURE_DSH=1\n");
    var application = locate();
    for (String name : List.of(".credentials.yaml", ".env"))
      assertEquals("PROVIDER_CREDENTIALS_OMITTED", walk(source(application, name)).get(0).reason);
    var selection = new NativeDataLocations.Selection();
    selection.includeApiKey = true;
    var locator = new NativeDataLocations(context, new JvmBackupFileSystem());
    assertEquals(
        "EXCLUDED", walk(source(locator.locate(selection), ".credentials.yaml")).get(0).kind);
    selection.includeApiKey = false;
    selection.includeProviderCredentials = true;
    var optedIn = locator.locate(selection);
    for (String name : List.of(".credentials.yaml", ".env"))
      assertEquals("FILE", walk(source(optedIn, name)).get(0).kind);
    assertTrue(
        walk(source(application, "retained")).stream()
            .anyMatch(item -> item.path.equals("archive.zip")));
    assertEquals(
        Set.of("sessions", "attachments"),
        locate("sessions").sources.stream()
            .map(BackupSource::id)
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(
        locate("plugins").sources.stream().allMatch(source -> source.scope().equals("plugins")));
    assertTrue(locate("projects").sources.isEmpty());
    assertThrows(IOException.class, () -> locate("settings,sessions"));
    assertThrows(IOException.class, () -> locate("settings+sessions"));

    put("../fixture-project/file.txt", "explicit project fixture\n");
    var selected = new NativeDataLocations.Selection();
    selected.scope = "settings";
    selected.guestProjects.add("/root/fixture-project");
    var withProject = new NativeDataLocations(context, new JvmBackupFileSystem()).locate(selected);
    var project =
        withProject.sources.stream()
            .filter(root -> root.scope().equals("projects"))
            .findFirst()
            .orElseThrow();
    assertTrue(walk(project).stream().anyMatch(item -> item.path.equals("file.txt")));
  }
}
