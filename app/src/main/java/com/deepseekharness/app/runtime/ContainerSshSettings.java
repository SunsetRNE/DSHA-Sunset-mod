package com.deepseekharness.app.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.ContainerSshPolicy;

/**
 * 容器 SSH 的开关与密码（默认关闭）。
 *
 * <p>刻意不塞进 {@code ConfigStore}：那份文件被运行时描述符逐字节钉住，改一个字符就要重算
 * 受管契约；而这条能力本身是独立可选的调试入口，用独立偏好更合适，也能整块删除。
 *
 * <p><b>密码存储的实话</b>：这里是 {@code MODE_PRIVATE} 的 SharedPreferences，靠应用沙箱
 * 隔离，<b>不是</b>加密存储。设备已 root 时能读到；所以文档里不写「安全保存密码」，
 * 只说「和 App 的其它私有数据同级」。真正需要加密时应迁到 {@code data/KeyVault}。
 */
public final class ContainerSshSettings {

  private static final String PREFS = "dsha_container_ssh";
  private static final String ENABLED = "enabled";
  private static final String LAN = "lan";
  private static final String PASSWORD = "password";

  private ContainerSshSettings() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

  public static boolean hasPassword(Context context) {
    String value = context == null ? null : prefs(context).getString(PASSWORD, null);
    return value != null && !value.isEmpty();
  }

  /** 只接受 {@link ContainerSshPolicy#validPassword} 认可的密码，非法值直接拒收。 */
  public static boolean setPassword(Context context, String password) {
    if (context == null || !ContainerSshPolicy.validPassword(password)) return false;
    prefs(context).edit().putString(PASSWORD, password).apply();
    return true;
  }

  public static String password(Context context) {
    return context == null ? null : prefs(context).getString(PASSWORD, null);
  }

  public static void clearPassword(Context context) {
    if (context == null) return;
    prefs(context).edit().remove(PASSWORD).apply();
  }
}
