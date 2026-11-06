package com.deepseekharness.app.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.data.KeyVault;
import com.deepseekharness.app.util.CredentialRead;
import com.deepseekharness.app.util.ContainerSshPolicy;

/**
 * 容器 SSH 的开关与密码（默认关闭）。
 *
 * <p>刻意不塞进 {@code ConfigStore}：那份文件被运行时描述符逐字节钉住，改一个字符就要重算
 * 受管契约；而这条能力本身是独立可选的调试入口，用独立偏好更合适，也能整块删除。
 *
 * <p><b>密码存储</b>：用独立 Keystore alias（{@code dsha_container_ssh}）做 AES/GCM 加密后
 * 才进 SharedPreferences，偏好里只有密文；解锁失败的设备读不到明文，服务启动会报
 * {@code SSH_PASSWORD_UNAVAILABLE} 而不是降级。已 root 且已解锁的设备上宿主进程仍能
 * 解出明文 —— 这是应用沙箱的边界，不是内核隔离，文档不做「安全保存」的宣称。
 */
public final class ContainerSshSettings {

  private static final String PREFS = "dsha_container_ssh";
  private static final String ENABLED = "enabled";
  private static final String LAN = "lan";
  private static final String PASSWORD = "password";
  private static final String VAULT_ALIAS = "dsha_container_ssh";

  private ContainerSshSettings() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static KeyVault vault(Context context) {
    return new KeyVault(context.getApplicationContext(), VAULT_ALIAS);
  }

  /** 默认关闭：这条能力会把容器命令行暴露给外部工具，必须用户显式打开。 */
  public static boolean enabled(Context context) {
    return context != null && prefs(context).getBoolean(ENABLED, false);
  }

  public static void setEnabled(Context context, boolean enabled) {
    if (context == null) return;
    prefs(context).edit().putBoolean(ENABLED, enabled).apply();
  }

  /** 是否允许局域网访问；默认只监听 127.0.0.1。 */
  public static boolean lanEnabled(Context context) {
    return context != null && prefs(context).getBoolean(LAN, false);
  }

  public static void setLanEnabled(Context context, boolean enabled) {
    if (context == null) return;
    prefs(context).edit().putBoolean(LAN, enabled).apply();
  }

  /** 偏好里存在密文即视为已设置；密文是否可解由读取时另行判断。 */
  public static boolean hasPassword(Context context) {
    String stored = context == null ? null : prefs(context).getString(PASSWORD, null);
    return stored != null && !stored.isEmpty();
  }

  /** 只接受 {@link ContainerSshPolicy#validPassword} 认可的密码，非法值直接拒收。 */
  public static boolean setPassword(Context context, String password) {
    if (context == null || !ContainerSshPolicy.validPassword(password)) return false;
    String encrypted = vault(context).encrypt(password);
    if (encrypted == null || encrypted.isEmpty()) return false; // Keystore 失败绝不落明文。
    prefs(context).edit().putString(PASSWORD, encrypted).apply();
    return true;
  }

  /** 解密失败（设备锁着 / 密钥丢失）返回 null，调用方按未配置处理，不降级也不明文化。 */
  public static String password(Context context) {
    if (context == null) return null;
    String stored = prefs(context).getString(PASSWORD, null);
    if (stored == null || stored.isEmpty()) return null;
    CredentialRead read = vault(context).read(stored);
    return read.state == CredentialRead.State.AVAILABLE ? read.requireValue() : null;
  }

  public static void clearPassword(Context context) {
    if (context == null) return;
    prefs(context).edit().remove(PASSWORD).apply();
  }
}
