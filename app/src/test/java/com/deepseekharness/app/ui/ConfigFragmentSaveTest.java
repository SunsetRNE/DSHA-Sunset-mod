package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = AboutFragmentTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ConfigFragmentSaveTest {
  private final java.util.List<ActivityController<AboutFragmentTest.Host>> hosts =
      new java.util.ArrayList<>();

  private View open() {
    var host = Robolectric.buildActivity(AboutFragmentTest.Host.class).setup().visible();
    hosts.add(host);
    ConfigFragment config = new ConfigFragment();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, config)
        .commitNow();
    return config.requireView();
  }

  @After
  public void close() {
    for (var host : hosts) host.pause().stop().destroy();
  }

  @Test
  public void savingDnsInOlderFormPreservesNewDesktopAndFixedPort() {
    View first = open();
    View older = open();
    CompoundButton desktop = first.findViewById(R.id.config_desktop_mode);
    boolean target = !desktop.isChecked();
    desktop.performClick();
    ((CompoundButton) first.findViewById(R.id.config_automatic_port)).setChecked(false);
    ((EditText) first.findViewById(R.id.config_port)).setText("42173");
    first.findViewById(R.id.config_save).performClick();
    ConfigStore store = new ConfigStore(first.getContext());
    assertEquals(target, store.isDesktopMode());
    assertEquals(42173, store.getRequestedWebPort());

    older.findViewById(R.id.dns_ipv4).performClick();
    older.findViewById(R.id.config_save).performClick();
    View reopened = open();
    System.out.printf(
        "FACT stale-save desktop=%s port=%d dns=%s%n",
        store.isDesktopMode(), store.getRequestedWebPort(), store.getDnsMode());
    assertEquals("ipv4", store.getDnsMode());
    assertEquals(target, store.isDesktopMode());
    assertEquals(42173, store.getRequestedWebPort());
    for (View page : new View[] {older, reopened}) {
      assertEquals(
          target, ((CompoundButton) page.findViewById(R.id.config_desktop_mode)).isChecked());
      assertFalse(((CompoundButton) page.findViewById(R.id.config_automatic_port)).isChecked());
      assertEquals("42173", ((EditText) page.findViewById(R.id.config_port)).getText().toString());
      assertEquals(View.GONE, page.findViewById(R.id.config_undo).getVisibility());
    }
  }

  @Test
  public void recreatingTheOlderFormKeepsOnlyItsEdits() {
    View first = open();
    View older = open();
    older.findViewById(R.id.dns_native).performClick();
    CompoundButton desktop = first.findViewById(R.id.config_desktop_mode);
    boolean target = !desktop.isChecked();
    desktop.performClick();
    first.findViewById(R.id.config_save).performClick();
    View recreated = hosts.get(1).recreate().get().findViewById(R.id.config_save);
    recreated.performClick();
    ConfigStore store = new ConfigStore(recreated.getContext());
    System.out.printf(
        "FACT recreated-save desktop=%s dns=%s%n", store.isDesktopMode(), store.getDnsMode());
    assertEquals("native", store.getDnsMode());
    assertEquals(target, store.isDesktopMode());
  }

  @Test
  public void expandedTechnicalSummaryUpdatesAfterSavingThePort() {
    View page = open();
    View label = textContaining(page, TechNote.summary().toString());
    assertNotNull(label);
    label.performClick();
    ((CompoundButton) page.findViewById(R.id.config_automatic_port)).setChecked(false);
    ((EditText) page.findViewById(R.id.config_port)).setText("42175");
    page.findViewById(R.id.config_save).performClick();
    boolean describesSavedPort =
        textContaining(page.findViewById(R.id.config_column_end), "42175") != null;
    System.out.println("FACT expanded-summary containsSavedPort=" + describesSavedPort);
    assertTrue(describesSavedPort);
  }

  private static View textContaining(View view, String text) {
    if (view instanceof TextView label
        && !(view instanceof EditText)
        && label.getText().toString().contains(text)) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = textContaining(group.getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void successfulFixedPortSaveRendersPersistedNumber() {
    View form = open();
    ((CompoundButton) form.findViewById(R.id.config_automatic_port)).setChecked(false);
    EditText port = form.findViewById(R.id.config_port);
    port.setText("04217");
    form.findViewById(R.id.config_save).performClick();
    ConfigStore store = new ConfigStore(form.getContext());
    System.out.printf(
        "FACT normalized-port value=%s field=%s undoVisibility=%d%n",
        store.getPort(), port.getText(), form.findViewById(R.id.config_undo).getVisibility());
    assertEquals("4217", store.getPort());
    assertEquals(store.getPort(), port.getText().toString());
    assertEquals(View.GONE, form.findViewById(R.id.config_undo).getVisibility());
  }
}
