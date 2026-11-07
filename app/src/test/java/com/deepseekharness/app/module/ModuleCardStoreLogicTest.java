package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.module.ModuleCardStore.Issue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

/**
 * 模块存储里纯逻辑部分的单测：错误去重截断、备份退役挑选。
 * 涉及真实文件系统与 android.system.Os 的目录事务在 ModuleCardStoreTest（Robolectric）里验。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleCardStoreLogicTest {

  @Test
  public void capIssuesDedupesAndTruncates() {
    List<Issue> found =
        Arrays.asList(
            new Issue("a.json", "不是合法 JSON"),
            new Issue("a.json", "不是合法 JSON"),
            new Issue("b.json", "schema 必须是 1"),
            new Issue("a.json", "不是合法 JSON"));
    List<Issue> capped = ModuleCardStore.capIssues(found, 20);
    assertEquals(2, capped.size());
    assertEquals("a.json", capped.get(0).where);

    List<Issue> tiny = ModuleCardStore.capIssues(found, 1);
    assertEquals(1, tiny.size());

    assertTrue(ModuleCardStore.capIssues(null, 20).isEmpty());
    assertTrue(ModuleCardStore.capIssues(new ArrayList<>(), 20).isEmpty());
    assertTrue(ModuleCardStore.capIssues(found, 0).isEmpty());
  }

  /** 备份退役：只认 <id>- 前缀，按修改时间留 keep 份，最旧的先退。 */
  @Test
  public void backupsToRetireKeepsNewestPerId() throws IOException {
    File root = tmpDir();
    try {
      File mineA = new File(root, "my.pet-1000-aaaaaaaa");
      File mineB = new File(root, "my.pet-2000-bbbbbbbb");
      File mineC = new File(root, "my.pet-3000-cccccccc");
      File other = new File(root, "other.pet-2000-dddddddd");
      for (File dir : new File[] {mineA, mineB, mineC, other}) {
        assertTrue(dir.mkdirs());
        // lastModified 用真实时间不可控：显式按名字里的时间戳设置
        long stamp = Long.parseLong(dir.getName().split("-")[1]);
        assertTrue(dir.setLastModified(stamp));
      }
      List<File> retire = ModuleCardStore.backupsToRetire(root.listFiles(), "my.pet", 1);
      assertEquals(2, retire.size());
      assertEquals(mineA, retire.get(0));
      assertEquals(mineB, retire.get(1));
      // keep=0：连同其它前缀隔离检查 —— other 不属于 my.pet，永远不退
      List<File> all = ModuleCardStore.backupsToRetire(root.listFiles(), "my.pet", 0);
      assertEquals(3, all.size());
      assertTrue(!all.contains(other));
      // 名字前缀必须是 id + "-"，防止 id "a" 吃掉 "a.b" 的备份
      assertEquals(0, ModuleCardStore.backupsToRetire(root.listFiles(), "my", 1).size());
    } finally {
      for (File dir : root.listFiles()) dir.delete();
      root.delete();
    }
  }

  private static File tmpDir() throws IOException {
    java.nio.file.Path path = java.nio.file.Files.createTempDirectory("module-store-logic");
    return path.toFile();
  }
}
