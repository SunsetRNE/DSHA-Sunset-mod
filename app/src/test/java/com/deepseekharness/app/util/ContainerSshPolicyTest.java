package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class ContainerSshPolicyTest {
  @Test
  public void passwordsCannotInjectAnotherAccountThroughStdin() {
    assertTrue(ContainerSshPolicy.validPassword("sample-123"));
    assertFalse(ContainerSshPolicy.validPassword("short"));
    assertFalse(ContainerSshPolicy.validPassword("sample-123\nroot:other"));
    assertFalse(ContainerSshPolicy.validPassword("sample\0password"));
    assertFalse(ContainerSshPolicy.validPassword("sample\rpassword"));
    assertFalse(ContainerSshPolicy.validPassword("x".repeat(129)));
  }

  @Test
  public void hostKeysUsePersistentPrivateBindAndServerAccountIsPrepared() {
    String script = ContainerSshPolicy.script(false);
    assertTrue(script.contains("HostKey /run/dsha-container-ssh/host_ed25519\n"));
    assertTrue(script.contains("useradd --system -M -d /run/sshd -s /usr/sbin/nologin sshd"));
    assertFalse(script.contains("/root/.dsha-container-ssh"));
  }

  @Test
  public void serverDefaultsToLoopbackAndDisablesHostForwarding() {
    String script = ContainerSshPolicy.script(false);
    assertTrue(script.contains("ListenAddress 127.0.0.1\n"));
    assertTrue(script.contains("IFS= read -r DSHA_SSH_PASSWORD"));
    assertTrue(script.contains("\"$DSHA_SSH_PASSWORD\" | chpasswd"));
    assertTrue(script.contains("PermitRootLogin no\n"));
    assertTrue(script.contains("AllowTcpForwarding no\n"));
    assertTrue(script.contains("AllowStreamLocalForwarding no\n"));
    assertTrue(script.contains("PermitEmptyPasswords no\n"));
    assertTrue(script.contains("AllowUsers dsha\n"));
    assertTrue(ContainerSshPolicy.script(true).contains("ListenAddress 0.0.0.0\n"));
  }
}
