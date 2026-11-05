package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import com.deepseekharness.app.HomeLayoutStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 容器程序登记的解析边界、壳内写回，以及启动入口 tool:<id> 动作的存取。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ContainerToolsTest.App.class)
public final class ContainerToolsTest {

  /** 不注册真实前台回调：本测试只验证解析、写回与偏好存取。 */
  public static final class App extends com.deepseekharness.app.DshaApp {
    @Override
    public void onCreate() {}
  }

  private android.content.Context context;
  private java.io.File dir;

  @Before
  public void setUp() throws java.io.IOException {
    context = org.robolectric.Robolectric.buildActivity(Activity.class).setup().get();
    context.getSharedPreferences("dsha_home_layout", 0).edit().clear().commit();
    dir = java.nio.file.Files.createTempDirectory("dsha-launch").toFile();
  }

  @Test
  public void parsesValidRegistration() {
    ContainerTools.Tool tool =
        ContainerTools.parse(
            "{\"schema\":1,\"id\":\"my-tool\",\"name\":\"我的工具\","
                + "\"command\":\"nohup /opt/tool --port 8080\",\"port\":8080,\"note\":\"文件服务\"}");
    assertEquals("my-tool", tool.id);
    assertEquals("我的工具", tool.name);
    assertEquals(8080, tool.port);
    assertEquals("文件服务", tool.note);
  }

  @Test
  public void rejectsInvalidRegistrations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerTools.parse("{\"schema\":2,\"id\":\"a\",\"name\":\"n\",\"command\":\"c\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerTools.parse(
                "{\"schema\":1,\"id\":\"Bad ID\",\"name\":\"n\",\"command\":\"c\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerTools.parse("{\"schema\":1,\"id\":\"a\",\"name\":\"\",\"command\":\"c\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerTools.parse("{\"schema\":1,\"id\":\"a\",\"name\":\"n\",\"command\":\"\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerTools.parse(
                "{\"schema\":1,\"id\":\"a\",\"name\":\"n\",\"command\":\"c\",\"port\":80}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerTools.parse(
                "{\"schema\":1,\"id\":\"a\",\"name\":\"n\",\"command\":\"c\",\"port\":70000}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerTools.parse(
                "{\"schema\":1,\"id\":\"a\",\"name\":\"n\",\"command\":\"c\",\"note\":\""
                    + "x".repeat(65)
                    + "\"}"));
  }

  @Test
  public void toolActionsRoundTripThroughPreferences() {
    // 默认还是容器工作台
    assertEquals(HomeLayoutStore.ACTION_WEB, HomeLayoutStore.launchAction(context));
    // tool:<id> 可写入并读回
    HomeLayoutStore.setLaunchAction(context, "tool:my-tool");
    assertEquals("tool:my-tool", HomeLayoutStore.launchAction(context));
    assertTrue(HomeLayoutStore.isToolAction("tool:my-tool"));
    assertEquals("my-tool", HomeLayoutStore.toolIdOf("tool:my-tool"));
    assertFalse(HomeLayoutStore.isToolAction("web"));
    assertEquals("", HomeLayoutStore.toolIdOf("web"));
    // 非法 id 拒绝；坏值读回时退回默认工作台
    assertThrows(
        IllegalArgumentException.class, () -> HomeLayoutStore.setLaunchAction(context, "tool:BAD"));
    assertThrows(
        IllegalArgumentException.class, () -> HomeLayoutStore.setLaunchAction(context, "tool:"));
    context
        .getSharedPreferences("dsha_home_layout", 0)
        .edit()
        .putString("launch_action", "tool:GONE")
        .commit();
    assertEquals(HomeLayoutStore.ACTION_WEB, HomeLayoutStore.launchAction(context));
    HomeLayoutStore.setLaunchAction(context, HomeLayoutStore.ACTION_WEB);
    assertEquals(HomeLayoutStore.ACTION_WEB, HomeLayoutStore.launchAction(context));
    assertNull(ContainerTools.byId(context, "missing"));
  }

  @Test
  public void shellNavigationTargetsAreNoLongerSelectable() {
    // 启动按钮不该兼任壳内导航：terminal / settings / modules 一律拒收。
    for (String legacy : new String[] {"terminal", "settings", "modules"}) {
      assertThrows(
          IllegalArgumentException.class, () -> HomeLayoutStore.setLaunchAction(context, legacy));
      // 旧偏好里已经存过的值读回时迁移成容器工作台，并且立刻写回。
      context
          .getSharedPreferences("dsha_home_layout", 0)
          .edit()
          .putString("launch_action", legacy)
          .commit();
      assertEquals(HomeLayoutStore.ACTION_WEB, HomeLayoutStore.launchAction(context));
      assertEquals(
          HomeLayoutStore.ACTION_WEB,
          context.getSharedPreferences("dsha_home_layout", 0).getString("launch_action", ""));
    }
  }

  @Test
  public void shellEditorWritesSameJsonTheContainerWould() throws java.io.IOException {
    // 壳内新建 = 在容器 ~/.dsha-launch/ 写一份 JSON，产物必须能被同一套扫描读回。
    ContainerTools.Tool saved =
        ContainerTools.saveTo(dir, "", "night-tool", "夜间工具", "cd /opt/t && ./t", 8080, "测试");
    assertEquals("night-tool", saved.id);
    java.io.File file = new java.io.File(dir, "night-tool.json");
    assertTrue(file.isFile());
    String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    assertTrue(raw, raw.contains("\"schema\":1"));
    assertTrue(raw, raw.contains("\"id\":\"night-tool\""));
    assertTrue(raw, raw.contains("\"port\":8080"));
    // 扫描（= 容器写文件后宿主看到的东西）与壳内保存结果一致。
    ContainerTools.Scan scan = ContainerTools.scanDir(dir);
    assertEquals(1, scan.tools.size());
    assertEquals("夜间工具", scan.tools.get(0).name);
    assertTrue(scan.issues.isEmpty());
  }

  @Test
  public void editingKeepsIdOrMovesRegistrationToNewId() throws java.io.IOException {
    ContainerTools.saveTo(dir, "", "old-id", "旧", "echo 1", 0, "");
    // 同 id 编辑：原地覆盖，不新增文件。
    ContainerTools.saveTo(dir, "old-id", "old-id", "新名字", "echo 2", 0, "备注");
    assertEquals(1, dir.listFiles((d, n) -> n.endsWith(".json")).length);
    ContainerTools.Scan scan = ContainerTools.scanDir(dir);
    assertEquals("新名字", scan.tools.get(0).name);
    assertEquals("echo 2", scan.tools.get(0).command);
    // 改 id：旧登记删除，只留新 id 一份。
    ContainerTools.saveTo(dir, "old-id", "new-id", "新名字", "echo 2", 0, "备注");
    java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
    assertEquals(1, files.length);
    assertEquals("new-id.json", files[0].getName());
  }

  /** 编辑 A 把 id 改成已存在的 B：必须拒绝，不能静默覆盖 B 的登记。 */
  @Test
  public void renamingOntoAnExistingIdIsRejected() throws java.io.IOException {
    ContainerTools.saveTo(dir, "", "tool-a", "工具A", "echo a", 0, "");
    ContainerTools.saveTo(dir, "", "tool-b", "工具B", "echo b", 0, "");
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerTools.saveTo(dir, "tool-a", "tool-b", "工具B2", "echo b2", 0, ""));
    // A 还在（没有先删后写），B 内容未被覆盖。
    ContainerTools.Scan scan = ContainerTools.scanDir(dir);
    assertEquals(2, scan.tools.size());
    java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
    for (ContainerTools.Tool tool : scan.tools) names.put(tool.id, tool.name);
    assertEquals("工具A", names.get("tool-a"));
    assertEquals("工具B", names.get("tool-b"));
  }

  @Test
  public void invalidShellSaveIsRejectedBeforeTouchingDisk() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerTools.saveTo(dir, "", "BAD ID", "名", "echo", 0, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerTools.saveTo(dir, "", "ok-id", "", "echo", 0, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerTools.saveTo(dir, "", "ok-id", "名", "echo", 80, ""));
    // 校验失败不能留下半份文件。
    java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
    assertTrue(files == null || files.length == 0);
  }

  @Test
  public void deletingShellRegistrationRemovesFileAndIsIdempotent() {
    ContainerTools.saveTo(dir, "", "gone-tool", "将删", "echo", 0, "");
    assertTrue(ContainerTools.deleteFrom(dir, "gone-tool"));
    assertFalse(new java.io.File(dir, "gone-tool.json").exists());
    // 再删一次不是错误，只是没有可删的东西。
    assertFalse(ContainerTools.deleteFrom(dir, "gone-tool"));
    assertTrue(ContainerTools.scanDir(dir).tools.isEmpty());
  }

  @Test
  public void scanReportsBrokenRegistrationsWithoutDroppingGoodOnes() throws java.io.IOException {
    ContainerTools.saveTo(dir, "", "good", "好的", "echo", 0, "");
    java.nio.file.Files.write(
        new java.io.File(dir, "broken.json").toPath(), "{not json".getBytes("UTF-8"));
    ContainerTools.Scan scan = ContainerTools.scanDir(dir);
    assertEquals("坏文件不能拖垮好登记", 1, scan.tools.size());
    assertEquals("good", scan.tools.get(0).id);
    assertEquals(1, scan.issues.size());
    assertTrue(scan.issues.get(0).startsWith("broken.json"));
  }
}
