package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 容器 SSH 偏好的安全边界。
 *
 * <p>Robolectric 没有可用的 AndroidKeyStore，所以加密写入在这套测试里必然失败 —— 这正好
 * 用来钉住最关键的性质：凭据库不可用时宁可拒绝保存，也绝不把明文落进 SharedPreferences。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class ContainerSshSettingsTest {

  private static Context context() {
    return RuntimeEnvironment.getApplication();
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("dsha_container_ssh", Context.MODE_PRIVATE);
  }

  @Test
  public void everythingDefaultsToOffAndEmpty() {
    Context context = context();
    assertFalse(ContainerSshSettings.enabled(context));
    assertFalse(ContainerSshSettings.lanEnabled(context));
    assertFalse(ContainerSshSettings.hasPassword(context));
    assertNull(ContainerSshSettings.password(context));
    assertFalse(prefs(context).contains("password"));
  }

  @Test
  public void switchPreferencesRoundTrip() {
    Context context = context();
    ContainerSshSettings.setEnabled(context, true);
    ContainerSshSettings.setLanEnabled(context, true);
    assertTrue(ContainerSshSettings.enabled(context));
    assertTrue(ContainerSshSettings.lanEnabled(context));
    ContainerSshSettings.setEnabled(context, false);
    ContainerSshSettings.setLanEnabled(context, false);
    assertFalse(ContainerSshSettings.enabled(context));
    assertFalse(ContainerSshSettings.lanEnabled(context));
  }

  @Test
  public void nullContextIsToleratedWithoutWriting() {
    ContainerSshSettings.setEnabled(null, true);
    ContainerSshSettings.setLanEnabled(null, true);
    assertFalse(ContainerSshSettings.enabled(null));
    assertFalse(ContainerSshSettings.hasPassword(null));
    assertNull(ContainerSshSettings.password(null));
  }

  @Test
  public void invalidPasswordsAreRejectedBeforeAnyStorage() {
    Context context = context();
    assertFalse(ContainerSshSettings.setPassword(context, null));
    assertFalse(ContainerSshSettings.setPassword(context, "short"));
    assertFalse(ContainerSshSettings.setPassword(context, "bad\u0000control"));
    assertFalse(ContainerSshSettings.setPassword(context, "a".repeat(129)));
    // 拒收发生在落盘之前：偏好里不能出现密码键。
    assertFalse(prefs(context).contains("password"));
    assertFalse(ContainerSshSettings.hasPassword(context));
  }

  @Test
  public void keystoreFailureRefusesToStorePlaintext() {
    Context context = context();
    // Robolectric 没有 AndroidKeyStore：encrypt 返回空串，setPassword 必须整体失败。
    boolean accepted = ContainerSshSettings.setPassword(context, "12345678");
    assertFalse("Keystore 不可用时不得假装保存成功", accepted);
    String stored = prefs(context).getString("password", null);
    assertTrue("偏好里出现明文密码", stored == null || !stored.equals("12345678"));
    assertFalse(ContainerSshSettings.hasPassword(context));
    assertNull(ContainerSshSettings.password(context));
  }

  @Test
  public void clearPasswordRemovesTheEntry() {
    Context context = context();
    prefs(context).edit().putString("password", "ciphertext").apply();
    assertTrue(ContainerSshSettings.hasPassword(context));
    ContainerSshSettings.clearPassword(context);
    assertFalse(prefs(context).contains("password"));
    assertFalse(ContainerSshSettings.hasPassword(context));
  }
}
