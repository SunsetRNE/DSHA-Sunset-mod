package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 状态矩阵：首屏标签 ≤5 字、明细非空、诊断码不出现在用户可见文本里。
 *
 * <p>上一版状态行是「运行中 · 端口 22222」（9 字）和「已开启 · 监听未确认，详见通知」（13 字），
 * 失败时还一边说「详见通知」一边把通知删掉。这里按 v6 ⑬ 的状态矩阵把结论锁住；
 * 截图级验收（十语/短屏/1.3 倍）仍需要设备，不在单测范围内。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ContainerSshStatusTest {

  private static final String[] STATES = {
    "running",
    "starting",
    "stopping",
    "stopped",
    "SSH_DISABLED",
    "SSH_LAN_PERMISSION_REQUIRED",
    "SSH_RUNTIME_NOT_READY",
    "SSH_PASSWORD_UNAVAILABLE",
    "SSH_LISTENER_UNCONFIRMED",
    "SSH_CLEANUP_UNCONFIRMED",
    "SSH_START_FAILED"
  };

  @Before
  public void chinese() {
    UiText.setLanguage("zh");
  }

  @Test
  public void everyStateHasAShortLabelWithoutDiagnosticCodes() {
    for (String state : STATES) {
      String label = ContainerSshStatus.label(state);
      assertFalse("状态标签不能为空: " + state, label.trim().isEmpty());
      assertTrue("首屏标签必须 ≤5 字，实际 " + label.length() + ": " + label, label.length() <= 5);
      assertFalse("诊断码不能出现在用户可见文本: " + state, label.contains("SSH_"));
    }
  }

  @Test
  public void everyStateHasAThreePartDetail() {
    for (String state : STATES) {
      String detail = ContainerSshStatus.detail(state, 22222, false);
      assertFalse("明细不能为空: " + state, detail.trim().isEmpty());
      assertFalse("明细里也不能有诊断码: " + state, detail.contains("SSH_"));
    }
  }

  @Test
  public void unknownStateFallsBackToStartFailureInsteadOfRawCode() {
    assertEquals(UiText.text("启动失败"), ContainerSshStatus.label("SSH_SOMETHING_NEW"));
    assertEquals(UiText.text("启动失败"), ContainerSshStatus.label(null));
    // 未知状态也不能把内部状态机用词直接抛给用户。
    assertEquals(UiText.text("启动失败"), ContainerSshStatus.label("weird"));
  }

  @Test
  public void runningDetailShowsPortAndReachability() {
    String loopback = ContainerSshStatus.detail("running", 22222, false);
    String lan = ContainerSshStatus.detail("running", 22222, true);
    assertTrue(loopback.contains("22222"));
    assertTrue(lan.contains("22222"));
    assertFalse("仅本机与局域网的说明必须不同", loopback.equals(lan));
  }

  @Test
  public void failureStatesAreExactlyTheDiagnosticCodes() {
    for (String state : STATES) {
      assertEquals("失败判定: " + state, state.startsWith("SSH_"), ContainerSshStatus.isFailure(state));
    }
    assertFalse(ContainerSshStatus.isFailure("running"));
    assertFalse(ContainerSshStatus.isFailure("stopped"));
  }

  @Test
  public void cleanupUnconfirmedKeepsItsOwnLabel() {
    // 未确认既不是「运行中」也不是「启动失败」：维护被推迟，用户要能看到这个区别。
    assertEquals(UiText.text("监听未确认"), ContainerSshStatus.label("SSH_CLEANUP_UNCONFIRMED"));
    assertTrue(ContainerSshStatus.isFailure("SSH_CLEANUP_UNCONFIRMED"));
  }
}
