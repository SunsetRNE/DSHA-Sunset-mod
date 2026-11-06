package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 容器 SSH 的启动脚本约束。
 *
 * <p>这条能力把容器命令行暴露给外部工具，所以脚本本身必须是可读的「策略」：
 * 默认只听回环、密码只从 stdin 进、失败次数与并发都有限制、转发与 root 登录一律关闭。
 * 任何一条被改动都应该在这里红一次。
 */
public final class ContainerSshPolicyTest {

  @Test
  public void defaultListensOnLoopbackOnly() {
    String script = ContainerSshPolicy.script(false);
    assertTrue(script, script.contains("ListenAddress 127.0.0.1"));
    assertFalse(script, script.contains("ListenAddress 0.0.0.0"));
  }

  @Test
  public void lanFlagIsTheOnlyWayToBindAllInterfaces() {
    String script = ContainerSshPolicy.script(true);
    assertTrue(script, script.contains("ListenAddress 0.0.0.0"));
  }

  @Test
  public void passwordNeverAppearsInTheScript() {
    String script = ContainerSshPolicy.script(false);
    // 密码只从 stdin 读一行，脚本里不能出现赋值字面量；否则会进 shell 历史/日志。
    assertTrue(script, script.contains("IFS= read -r DSHA_SSH_PASSWORD"));
    assertTrue(script, script.contains("unset DSHA_SSH_PASSWORD"));
    assertFalse(script, script.contains("DSHA_SSH_PASSWORD="));
  }

  @Test
  public void bothPerConnectionAndConcurrencyLimitsAreSet() {
    String script = ContainerSshPolicy.script(false);
    assertTrue("单连接失败次数", script.contains("MaxAuthTries 3"));
    // 没有这一条时，3 次限制可以靠开多条并发被放大成 3×N 次。
    assertTrue("并发未认证连接上限", script.contains("MaxStartups 3:50:10"));
    assertTrue("单连接会话数", script.contains("MaxSessions 2"));
    assertTrue("认证宽限时间", script.contains("LoginGraceTime 20"));
  }

  @Test
  public void forwardingAndRootLoginAreDisabled() {
    String script = ContainerSshPolicy.script(true);
    assertTrue(script, script.contains("PermitRootLogin no"));
    assertTrue(script, script.contains("PasswordAuthentication yes"));
    assertTrue(script, script.contains("PubkeyAuthentication no"));
    assertTrue(script, script.contains("AllowTcpForwarding no"));
    assertTrue(script, script.contains("PermitTunnel no"));
    assertTrue(script, script.contains("GatewayPorts no"));
    assertTrue(script, script.contains("AllowUsers dsha"));
  }

  @Test
  public void passwordRulesRejectTrivialSecrets() {
    assertFalse(ContainerSshPolicy.validPassword(null));
    assertFalse(ContainerSshPolicy.validPassword("short"));
    assertFalse(ContainerSshPolicy.validPassword("1234567"));
    assertFalse(ContainerSshPolicy.validPassword("has\u0000control"));
    assertFalse(ContainerSshPolicy.validPassword("a".repeat(129)));
    assertTrue(ContainerSshPolicy.validPassword("12345678"));
  }
}
