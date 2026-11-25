package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.widget.TextView;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.LanAccessPresentation;
import com.deepseekharness.app.util.LanNetworkState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = AboutFragmentTest.App.class,
    shadows = ConfigLanApprovalTest.Networks.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ConfigLanApprovalTest {
  @Implements(value = LocalNetworkAccess.class, isInAndroidSdk = false)
  public static final class Networks {
    static LanNetworkState selected;

    @Implementation
    protected static LanNetworkState currentLan(Context context) {
      return selected;
    }
  }

  @Test
  public void saveAfterNetworkChangeEnablesPreferenceButRequiresFreshNetworkApproval() {
    var host = Robolectric.buildActivity(AboutFragmentTest.Host.class).setup().visible();
    var config = new ConfigStore(host.get());
    config.setLanMode(false);
    try {
      Networks.selected = new LanNetworkState(11, "wlan0", "192.168.1.9", 24);
      var fragment = new ConfigFragment();
      host.get()
          .getSupportFragmentManager()
          .beginTransaction()
          .replace(R.id.fragment_container, fragment)
          .commitNow();
      var view = fragment.requireView();
      view.findViewById(R.id.config_lan_mode).performClick();
      assertTrue(
          ((TextView) view.findViewById(R.id.config_lan_hint))
              .getText()
              .toString()
              .contains("192.168.1.9/24"));
      Networks.selected = new LanNetworkState(12, "wlan0", "192.168.1.9", 24);
      view.findViewById(R.id.config_save).performClick();
      assertTrue(config.isLanMode());
      assertEquals(
          LanAccessPresentation.State.CONFIRM_NETWORK, LanProxyService.access(true, true, 0).state);
      assertFalse(LanProxyService.isBound());
    } finally {
      config.setLanMode(false);
      host.pause().stop().destroy();
    }
  }
}
