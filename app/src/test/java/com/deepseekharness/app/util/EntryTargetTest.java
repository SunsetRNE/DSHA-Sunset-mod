package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EntryTargetTest {
  private static EntryTarget.Launch launcher(String preference) {
    return new EntryTarget.Launch(preference, true, false, false, false, false);
  }

  @Test
  public void unknownValuesFallBackToMainAndBridgeAcceptsOnlyExactValues() {
    assertEquals(EntryTarget.MAIN, EntryTarget.normalize(null));
    assertEquals(EntryTarget.MAIN, EntryTarget.normalize(""));
    assertEquals(EntryTarget.MAIN, EntryTarget.normalize("WEB"));
    assertEquals(EntryTarget.MAIN, EntryTarget.normalize(" web"));
    assertEquals(EntryTarget.WEB, EntryTarget.normalize("web"));
    assertTrue(EntryTarget.valid("main"));
    assertTrue(EntryTarget.valid("web"));
    for (String invalid : new String[] {null, "", "Web", "web ", "terminal", "main,web"})
      assertFalse(String.valueOf(invalid), EntryTarget.valid(invalid));
  }

  @Test
  public void defaultPreferenceNeverOpensWeb() {
    assertFalse(EntryTarget.directWeb(launcher(null)));
    assertFalse(EntryTarget.directWeb(launcher(EntryTarget.MAIN)));
    assertTrue(EntryTarget.directWeb(launcher(EntryTarget.WEB)));
    assertFalse(EntryTarget.directWeb(null));
  }

  @Test
  public void onlyAFreshLauncherEntryWithoutPendingNativeWorkOpensWeb() {
    String web = EntryTarget.WEB;
    assertFalse(
        EntryTarget.directWeb(new EntryTarget.Launch(web, false, false, false, false, false)));
    assertFalse(
        EntryTarget.directWeb(new EntryTarget.Launch(web, true, true, false, false, false)));
    assertFalse(
        EntryTarget.directWeb(new EntryTarget.Launch(web, true, false, true, false, false)));
    assertFalse(
        EntryTarget.directWeb(new EntryTarget.Launch(web, true, false, false, true, false)));
    assertFalse(
        EntryTarget.directWeb(new EntryTarget.Launch(web, true, false, false, false, true)));
  }

  @Test
  public void gateOutcomesOtherThanACleanPassReturnToMain() {
    assertTrue(EntryTarget.admitted(true, false, false, false, false));
    assertFalse(EntryTarget.admitted(false, false, false, false, false));
    assertFalse(EntryTarget.admitted(true, true, false, false, false));
    assertFalse(EntryTarget.admitted(true, false, true, false, false));
    assertFalse(EntryTarget.admitted(true, false, false, true, false));
    assertFalse(EntryTarget.admitted(true, false, false, false, true));
  }

  @Test
  public void bridgeStateIsStableJsonWithNormalizedTarget() {
    assertEquals(
        "{\"target\":\"web\",\"options\":[\"main\",\"web\"]}", EntryTarget.bridgeState("web"));
    assertEquals(
        "{\"target\":\"main\",\"options\":[\"main\",\"web\"]}", EntryTarget.bridgeState("bogus"));
  }
}
