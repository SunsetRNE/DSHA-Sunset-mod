package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** ZIP 模块包导入：扁平文件、card.json/module.json、拒绝路径穿越。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleImportTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
  }

  private static byte[] zip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }

  @Test
  public void importsCardPackageWithAssets() throws Exception {
    String card =
        "{\"schema\":1,\"id\":\"zip.test\",\"name\":\"包\",\"image\":\"pic.png\",\"actions\":[]}";
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("card.json", card.getBytes(StandardCharsets.UTF_8));
    entries.put("pic.png", "not-a-real-png".getBytes(StandardCharsets.UTF_8));
    ModuleImport.Result result = ModuleImport.importPackage(context, zip(entries));
    assertTrue(result.card);
    assertTrue(ModuleCardStore.loadById(context).containsKey("user:zip.test"));
    assertTrue(
        new java.io.File(new java.io.File(context.getFilesDir(), "modules/zip.test"), "pic.png")
            .isFile());
  }

  @Test
  public void importsManifestPackage() throws Exception {
    String manifest =
        "{\"schema\":1,\"id\":\"zip.manifest\",\"name\":\"清单\",\"version\":\"1.0.0\","
            + "\"type\":\"frontend\",\"requires\":{\"minApi\":30},"
            + "\"contributes\":{\"tokens\":{\"accent\":\"#6aa4ff\"}}}";
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("module.json", manifest.getBytes(StandardCharsets.UTF_8));
    ModuleImport.Result result = ModuleImport.importPackage(context, zip(entries));
    assertFalse(result.card);
    assertTrue(ModuleRegistry.load(context).modules().containsKey("zip.manifest"));
  }

  @Test
  public void rejectsPathTraversalInPackage() throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put(
        "card.json",
        "{\"schema\":1,\"id\":\"zip.bad\",\"name\":\"坏\",\"actions\":[]}"
            .getBytes(StandardCharsets.UTF_8));
    entries.put("../evil.png", "x".getBytes(StandardCharsets.UTF_8));
    assertThrows(
        IllegalArgumentException.class, () -> ModuleImport.importPackage(context, zip(entries)));
  }

  @Test
  public void importsWebModuleWithHtmlCssJs() throws Exception {
    String card =
        "{\"schema\":1,\"id\":\"web.demo\",\"name\":\"网页\",\"actions\":[],"
            + "\"provides\":{\"module.web\":{\"entry\":\"index.html\","
            + "\"files\":[\"index.html\",\"style.css\",\"app.js\"]}},"
            + "\"view\":[{\"type\":\"web\",\"src\":\"index.html\",\"fill\":true}]}";
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("card.json", card.getBytes(StandardCharsets.UTF_8));
    entries.put("index.html", "<!doctype html><p>hi</p>".getBytes(StandardCharsets.UTF_8));
    entries.put("style.css", "body{background:#000}".getBytes(StandardCharsets.UTF_8));
    entries.put("app.js", "document.body.dataset.ok='1'".getBytes(StandardCharsets.UTF_8));
    ModuleImport.Result result = ModuleImport.importPackage(context, zip(entries));
    assertTrue(result.card);
    java.io.File dir =
        new java.io.File(new java.io.File(context.getFilesDir(), "modules/web.demo"), "");
    assertTrue(new java.io.File(dir, "index.html").isFile());
    assertTrue(new java.io.File(dir, "style.css").isFile());
    assertTrue(new java.io.File(dir, "app.js").isFile());
  }

  @Test
  public void identitySummaryNeverPromotesUnsignedCardToVerified() {
    ModuleCard card =
        ModuleCard.parse(
            "{\"schema\":1,\"id\":\"zip.unsigned\",\"name\":\"无签名\",\"actions\":[]}",
            ModuleCard.SOURCE_LOCAL);
    String summary = ModuleImport.identitySummary(card, true);
    assertTrue(summary.contains("未验证"));
    assertFalse(summary.contains("已验证"));
  }

  @Test
  public void plainJsonStillImportsThroughPackageEntry() throws Exception {
    String raw = "{\"schema\":1,\"id\":\"zip.json\",\"name\":\"JSON\",\"actions\":[]}";
    ModuleImport.Result result =
        ModuleImport.importPackage(context, raw.getBytes(StandardCharsets.UTF_8));
    assertTrue(result.card);
    assertEquals("user:zip.json", result.detail);
  }
}
