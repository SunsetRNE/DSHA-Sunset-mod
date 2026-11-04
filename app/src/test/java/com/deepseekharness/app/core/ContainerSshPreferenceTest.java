package com.deepseekharness.app.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import com.deepseekharness.app.util.Constants;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** SSH 授权和凭据是本机状态，不能被普通设置迁移或用户归档复活。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public class ContainerSshPreferenceTest {
  @Test
  public void sshAndLanDefaultOffAndExplicitPreferencePersists() {
    Context context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    ConfigStore store = new ConfigStore(context);
    assertFalse(store.isSshEnabled());
    assertFalse(store.isSshLanEnabled());
    assertFalse(store.hasSshPassword());
    store.setSshEnabled(true);
    assertTrue(new ConfigStore(context).isSshEnabled());
    store.setSshEnabled(false);
    assertFalse(new ConfigStore(context).isSshEnabled());
  }

  @Test
  public void sshSecretsAndAuthorityNeverEnterBackupProjection() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    context
        .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean("container_ssh_enabled", true)
        .putBoolean("container_ssh_lan_enabled", true)
        .putString("container_ssh_password", "not-a-real-keystore-ciphertext")
        .commit();
    ConfigStore store = new ConfigStore(context);
    for (String exported :
        new String[] {
          store.exportBackupSettings().toString(), store.exportPortableSettings(false).toString()
        }) {
      assertFalse(exported.contains("container_ssh"));
      assertFalse(exported.contains("not-a-real-keystore-ciphertext"));
    }
    var projected =
        com.deepseekharness.app.backup.PortablePreferenceProjection.project(
            Map.of(
                "container_ssh_enabled", true,
                "container_ssh_lan_enabled", true,
                "container_ssh_password", "not-a-real-keystore-ciphertext"));
    assertTrue(projected.isEmpty());
  }
}
