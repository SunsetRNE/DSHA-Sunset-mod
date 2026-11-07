package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 脚本沙箱里那些「判定」的负控：不建 WebView 也能测的部分。
 *
 * <p>WebView 本身的断网/断文件行为只能在真机或模拟器上验收（本 PR 没有设备，见 PR 说明），
 * 但结算竞态、队列上限、console 上限与 exec 的签名策略都是纯逻辑，没理由不测 —— 上一轮
 * 正是「单测全绿但桥接逻辑没人测」被点出来。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleScriptTest {

  @Test
  public void doneOnlySettlesTheRunningRequest() {
    assertTrue(ModuleScript.acceptsDone("s7", "s7", true));
    // 迟到的旧 id：前一个脚本超时后新的已经在跑，旧回调不能把它结算掉。
    assertFalse(ModuleScript.acceptsDone("s8", "s7", true));
    assertFalse(ModuleScript.acceptsDone(null, "s7", true));
    assertFalse(ModuleScript.acceptsDone("s7", null, true));
    // 已经没有正在跑的请求。
    assertFalse(ModuleScript.acceptsDone("s7", "s7", false));
  }

  @Test
  public void queueIsBounded() {
    for (int queued = 0; queued < 4; queued++) assertTrue(ModuleScript.canEnqueue(queued));
    assertFalse(ModuleScript.canEnqueue(4));
    assertFalse(ModuleScript.canEnqueue(99));
  }

  @Test
  public void consoleStopsAtTheLimit() {
    StringBuilder buffer = new StringBuilder();
    for (int i = 0; i < 500 && buffer.length() < ModuleScript.CONSOLE_LIMIT; i++)
      ModuleScript.appendConsole(buffer, "line-" + i + "-padpadpadpadpadpad");
    int before = buffer.length();
    assertTrue("先涨到上限", before >= ModuleScript.CONSOLE_LIMIT);
    ModuleScript.appendConsole(buffer, "more");
    assertEquals("到达上限后不再追加", before, buffer.length());
    ModuleScript.appendConsole(buffer, null);
    ModuleScript.appendConsole(null, "x");
  }

  @Test
  public void execPolicyOnlyBlocksUnsignedCardsWhenVerificationIsRequired() {
    // 当前策略是「签名是来源标注，不是准入」：不强制时一律放行到执行门。
    assertNull(ModuleScript.execRefusal(false, ModuleTrust.UNVERIFIED));
    assertNull(ModuleScript.execRefusal(true, ModuleTrust.VERIFIED));
    // 策略翻成强制时必须拒绝未验证/未受信/无效。
    assertTrue(ModuleScript.execRefusal(true, ModuleTrust.UNVERIFIED).contains("未授权"));
    assertTrue(ModuleScript.execRefusal(true, ModuleTrust.UNTRUSTED).contains("未授权"));
    assertTrue(ModuleScript.execRefusal(true, ModuleTrust.INVALID).contains("未授权"));
  }
}
