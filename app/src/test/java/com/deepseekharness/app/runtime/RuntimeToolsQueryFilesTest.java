package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * 插件查询探测清单必须跟着 triplet 走。
 *
 * <p>写死 {@code aarch64-linux-gnu} 时，x86_64 模拟器包会去找不存在的
 * {@code aarch64} 目录，判据恒假——这属于「ARM 上看不出问题」的典型分支。
 */
public final class RuntimeToolsQueryFilesTest {

  @Test
  public void armTripletUsesAarch64LibraryPaths() {
    List<String> files = Arrays.asList(RuntimeTools.queryFiles("aarch64-linux-gnu"));
    assertTrue(files.contains("usr/lib/aarch64-linux-gnu/libsqlite3.so.0"));
    assertTrue(files.contains("usr/lib/aarch64-linux-gnu/libreadline.so.8"));
  }

  @Test
  public void emulatorTripletUsesX8664LibraryPaths() {
    List<String> files = Arrays.asList(RuntimeTools.queryFiles("x86_64-linux-gnu"));
    assertTrue(files.contains("usr/lib/x86_64-linux-gnu/libsqlite3.so.0"));
    assertTrue(files.contains("usr/lib/x86_64-linux-gnu/libreadline.so.8"));
  }

  @Test
  public void commonEntriesDoNotDependOnTriplet() {
    List<String> arm = Arrays.asList(RuntimeTools.queryFiles("aarch64-linux-gnu"));
    List<String> x86 = Arrays.asList(RuntimeTools.queryFiles("x86_64-linux-gnu"));
    assertEquals(arm.size(), x86.size());
    for (String entry : new String[] {"usr/bin/python3.12", "root/.dsh/plugin-manager.py"})
      assertTrue(arm.contains(entry) && x86.contains(entry));
  }
}
