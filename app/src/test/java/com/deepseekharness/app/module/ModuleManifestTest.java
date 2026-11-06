package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 模块清单契约的负控：不允许把声明文件变成可执行入口或绕过模块边界。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ModuleManifestTest {
  @Test
  public void parsesValidManifestAndKeepsImmutableProjection() {
    ModuleManifest manifest = ModuleManifest.parse(validManifest(), true);

    assertEquals("dsha.core.settings", manifest.id());
    assertEquals(ModuleManifest.Type.FRONTEND, manifest.type());
    assertTrue(manifest.builtin());
    assertEquals("local", manifest.author());
    assertEquals("builtin", manifest.source());
    assertTrue(manifest.digest().matches("[0-9a-f]{64}"));
    assertEquals(1, manifest.pages().size());
    assertEquals(1, manifest.navigation().size());
    assertEquals(1, manifest.sections().size());
    assertEquals("#6aa4ff", manifest.tokens().get("accent"));
    assertEquals(null, manifest.architecture());
    assertTrue(manifest.capabilities().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> manifest.pages().clear());
    assertThrows(UnsupportedOperationException.class, () -> manifest.tokens().put("bg", "x"));
  }

  @Test
  public void parsesArmProviderCapabilitiesWithoutGrantingThem() {
    String source =
        validManifest()
            .replace(
                "\"requires\":{\"minApi\":30}",
                "\"requires\":{\"minApi\":30,\"architecture\":\"aarch64\",\"capabilities\":[\"runtime.proot\",\"storage.workspace\"]}");
    ModuleManifest manifest = ModuleManifest.parse(source, true);
    assertEquals("aarch64", manifest.architecture());
    assertEquals(2, manifest.capabilities().size());
    assertTrue(manifest.capabilities().contains("runtime.proot"));
  }

  @Test
  public void rejectsUnknownArchitectureAndCapability() {
    assertInvalid(
        validManifest().replace("\"minApi\":30", "\"minApi\":30,\"architecture\":\"armv7\""));
    assertInvalid(
        validManifest().replace("\"minApi\":30", "\"minApi\":30,\"capabilities\":[\"root.host\"]"));
  }

  @Test
  public void userCannotClaimBuiltin() {
    assertThrows(
        IllegalArgumentException.class, () -> ModuleManifest.parse(validManifest(), false));
  }

  @Test
  public void rejectsJavaRenderer() {
    assertInvalid(validManifest().replace("\"settings\"", "\"com.example.Settings\""));
  }

  @Test
  public void rejectsUnauthorizedSystemTarget() {
    assertInvalid(validManifest().replace("system:diagnostics", "system:adb"));
  }

  @Test
  public void rejectsCrossModulePageTarget() {
    assertInvalid(
        validManifest().replace("page:dsha.core.settings/diagnostics", "page:other/page"));
  }

  @Test
  public void rejectsPathInjection() {
    assertInvalid(validManifest().replace("settings.svg", "../secret.svg"));
    assertInvalid(validManifest().replace("settings.svg", "icon\".svg"));
  }

  @Test
  public void rejectsQuantityBombs() {
    StringBuilder nav = new StringBuilder("\"nav\":[");
    for (int i = 0; i < 7; i++) {
      if (i > 0) nav.append(',');
      nav.append("{\"id\":\"n")
          .append(i)
          .append("\",\"title\":\"N\",\"icon\":\"x\",\"action\":{\"type\":\"manage\"}}");
    }
    nav.append(']');
    assertInvalid(
        validManifest()
            .replace(
                "\"nav\":[{\"id\":\"settings\",\"title\":\"设置\",\"icon\":\"settings.svg\",\"action\":{\"type\":\"open\",\"target\":\"page:dsha.core.settings/diagnostics\"}}]",
                nav));
  }

  @Test
  public void rejectsTokensThatHidePageDefinition() {
    assertInvalid(validManifest().replace("\"accent\":\"#6aa4ff\"", "\"page\":\"settings\""));
  }

  @Test
  public void rejectsUppercaseOrSpaceInId() {
    assertInvalid(validManifest().replace("dsha.core.settings", "Dsha Core"));
  }

  @Test
  public void rejectsNonSemver() {
    assertInvalid(validManifest().replace("\"version\":\"1.0.0\"", "\"version\":\"latest\""));
  }

  @Test
  public void rejectsScriptInRow() {
    assertInvalid(validManifest().replace("打开诊断", "<script>alert(1)</script>"));
  }

  @Test
  public void rejectsMissingOrEmptyContributes() {
    assertInvalid(
        validManifest()
            .replace(
                ",\"contributes\":{\"pages\":[{\"id\":\"diagnostics\",\"title\":\"诊断\",\"renderer\":\"settings\",\"action\":{\"type\":\"open\",\"target\":\"system:diagnostics\"}}],\"nav\":[{\"id\":\"settings\",\"title\":\"设置\",\"icon\":\"settings.svg\",\"action\":{\"type\":\"open\",\"target\":\"page:dsha.core.settings/diagnostics\"}}],\"sections\":[{\"id\":\"general\",\"title\":\"常用\",\"page\":\"diagnostics\",\"rows\":[{\"id\":\"diagnostics\",\"title\":\"诊断\",\"subtitle\":\"打开诊断\",\"icon\":\"settings.svg\",\"action\":{\"type\":\"open\",\"target\":\"system:diagnostics\"}}]}],\"tokens\":{\"accent\":\"#6aa4ff\"}}",
                ""));
    assertInvalid(validManifest().replace("\"contributes\":", "\"other\":{} ,\"contributes\":"));
  }

  @Test
  public void rejectsManageTarget() {
    assertInvalid(
        validManifest()
            .replace(
                "\"type\":\"open\",\"target\":\"system:diagnostics\"",
                "\"type\":\"manage\",\"target\":\"system:diagnostics\""));
  }

  @Test
  public void rejectsUnknownFields() {
    assertInvalid(validManifest().replace("\"schema\":1", "\"schema\":1,\"script\":\"run\""));
  }

  private static void assertInvalid(String source) {
    assertThrows(IllegalArgumentException.class, () -> ModuleManifest.parse(source, true));
  }

  private static String validManifest() {
    return "{"
        + "\"schema\":1,"
        + "\"id\":\"dsha.core.settings\","
        + "\"name\":\"DSHA 设置\","
        + "\"version\":\"1.0.0\","
        + "\"type\":\"frontend\","
        + "\"builtin\":true,"
        + "\"requires\":{\"minApi\":30},"
        + "\"contributes\":{"
        + "\"pages\":[{\"id\":\"diagnostics\",\"title\":\"诊断\",\"renderer\":\"settings\",\"action\":{\"type\":\"open\",\"target\":\"system:diagnostics\"}}],"
        + "\"nav\":[{\"id\":\"settings\",\"title\":\"设置\",\"icon\":\"settings.svg\",\"action\":{\"type\":\"open\",\"target\":\"page:dsha.core.settings/diagnostics\"}}],"
        + "\"sections\":[{\"id\":\"general\",\"title\":\"常用\",\"page\":\"diagnostics\",\"rows\":[{\"id\":\"diagnostics\",\"title\":\"诊断\",\"subtitle\":\"打开诊断\",\"icon\":\"settings.svg\",\"action\":{\"type\":\"open\",\"target\":\"system:diagnostics\"}}]}],"
        + "\"tokens\":{\"accent\":\"#6aa4ff\"}"
        + "}}";
  }
}
