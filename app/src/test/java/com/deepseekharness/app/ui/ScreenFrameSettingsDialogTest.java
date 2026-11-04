package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.widget.AppCompatEditText;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ScreenFrameSettingsDialogTest {
  public static final class Host extends androidx.appcompat.app.AppCompatActivity {
    @Override
    public void onCreate(android.os.Bundle state) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(state);
    }
  }

  private static AppCompatEditText editor(View view) {
    if (view instanceof AppCompatEditText input) return input;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        var child = editor(group.getChildAt(i));
        if (child != null) return child;
      }
    return null;
  }

  @Test
  public void actualDialogRequiresSaveSupportsRevocationAndRejectsWildcard() {
    var activity = org.robolectric.Robolectric.buildActivity(Host.class).setup();
    var context = activity.get();
    var config = new ConfigStore(context);
    AtomicInteger changes = new AtomicInteger();
    ScreenFrameSettingsDialog.show(context, changes::incrementAndGet);
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    var dialog =
        (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
    var input = editor(dialog.getWindow().getDecorView());
    assertNotNull(input);
    input.setText("com.example.game");
    assertEquals("", config.screenAnimatedPackages());
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    assertEquals("com.example.game", config.screenAnimatedPackages());
    assertEquals(1, changes.get());
    ScreenFrameSettingsDialog.show(context, changes::incrementAndGet);
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    dialog =
        (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
    input = editor(dialog.getWindow().getDecorView());
    input.setText("*");
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    assertTrue(dialog.isShowing());
    assertNotNull(input.getError());
    assertEquals("com.example.game", config.screenAnimatedPackages());
    input.setText("");
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    assertEquals("", config.screenAnimatedPackages());
    assertEquals(2, changes.get());
    activity.pause().stop().destroy();
  }
}
