package com.deepseekharness.app.util;

/** 容器 OpenSSH 的固定脚本；密码只由 stdin 读入，不拼接进 argv 或日志。 */
public final class ContainerSshPolicy {
  public static final int PORT = 22222;
  private static final String HOME = "/run/dsha-container-ssh";

  private ContainerSshPolicy() {}

  public static boolean validPassword(String value) {
    if (value == null || value.length() < 8 || value.length() > 128) return false;
    for (int i = 0; i < value.length(); i++)
      if (Character.isISOControl(value.charAt(i))) return false;
    return true;
  }

  public static String script(boolean lan) {
    String address = lan ? "0.0.0.0" : "127.0.0.1";
    return "set -eu\numask 077\n"
        + "[ -x /usr/sbin/sshd ] || { echo DSHA_SSH_SERVER_MISSING; exit 78; }\n"
        + "mkdir -p /run/sshd "
        + HOME
        + " /home/dsha\n"
        + "id -u sshd >/dev/null 2>&1 || useradd --system -M -d /run/sshd -s /usr/sbin/nologin sshd\n"
        + "id -u dsha >/dev/null 2>&1 || useradd -M -d /home/dsha -s /bin/bash dsha\n"
        + "chown dsha:dsha /home/dsha; chmod 700 /home/dsha\n"
        + "IFS= read -r DSHA_SSH_PASSWORD || exit 78\n"
        + "printf 'dsha:%s\\n' \"$DSHA_SSH_PASSWORD\" | chpasswd\n"
        + "unset DSHA_SSH_PASSWORD\n"
        + "[ -s "
        + HOME
        + "/host_ed25519 ] || ssh-keygen -q -t ed25519 -N '' -f "
        + HOME
        + "/host_ed25519\n"
        + "cat > "
        + HOME
        + "/sshd_config <<'DSHA_SSH_CONFIG'\n"
        + "Port "
        + PORT
        + "\nListenAddress "
        + address
        + "\n"
        + "HostKey "
        + HOME
        + "/host_ed25519\nPidFile "
        + HOME
        + "/sshd.pid\n"
        + "PasswordAuthentication yes\nKbdInteractiveAuthentication no\nPubkeyAuthentication no\n"
        + "UsePAM no\nPermitRootLogin no\nPermitEmptyPasswords no\nAllowUsers dsha\n"
        + "AllowTcpForwarding no\nAllowStreamLocalForwarding no\nX11Forwarding no\nPermitTunnel no\nGatewayPorts no\n"
        + "MaxAuthTries 3\nLoginGraceTime 20\nUseDNS no\nSubsystem sftp internal-sftp\nDSHA_SSH_CONFIG\n"
        + "/usr/sbin/sshd -t -f "
        + HOME
        + "/sshd_config\n"
        + "exec /usr/sbin/sshd -D -e -f "
        + HOME
        + "/sshd_config";
  }
}
