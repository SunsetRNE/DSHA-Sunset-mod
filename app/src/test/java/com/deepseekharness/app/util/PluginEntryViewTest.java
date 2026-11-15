package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PluginEntryViewTest {
  private static final String HASH = "a".repeat(64);

  @Test
  public void unsetAndOpaqueDisabledNeverDefaultToFalse() {
    PluginEntryView unset = new PluginEntryView("entry", HASH, "", false, null, "UNSET");
    PluginEntryView opaque = new PluginEntryView("entry", HASH, "", false, null, "OPAQUE_DISABLED");
    assertNull(unset.patchDisabled);
    assertNull(opaque.patchDisabled);
    assertNotEquals(unset.contentKey, opaque.contentKey);
    assertEquals(
        "UNKNOWN",
        new PluginEntryView("entry", HASH, "", false, null, "raw error text").patchReason);
  }

  @Test
  public void enabledChildOverrideDoesNotClearAncestorDisabledEvidence() {
    PluginEntryView entry =
        new PluginEntryView("child", HASH, "group", false, false, "ANCESTOR_DISABLED");
    assertEquals(Boolean.FALSE, entry.patchDisabled);
    assertTrue(entry.ancestorDisabled());
    assertEquals("group", entry.parentId);
  }

  @Test
  public void identityIsNamespacedButContentAndGroupAreIndependentEvidence() {
    PluginEntryView before = new PluginEntryView("entry", HASH, "", false, true, "LITERAL");
    PluginEntryView changed =
        new PluginEntryView("entry", "b".repeat(64), "parent", true, true, "LITERAL");
    assertEquals(before.stableKey("web", "application"), changed.stableKey("web", "application"));
    assertNotEquals(
        before.stableKey("web", "application"), before.stableKey("other", "application"));
    assertNotEquals(before.contentKey, changed.contentKey);
  }

  @Test
  public void malformedOrOversizedIdentityFailsClosed() {
    for (String id : new String[] {"", "bad\nentry", "bad\u0000entry", "x".repeat(257)}) {
      try {
        new PluginEntryView(id, HASH, "", false, false, "LITERAL");
        fail(id);
      } catch (IllegalArgumentException expected) {
        assertEquals("PLUGIN_ENTRY_IDENTITY", expected.getMessage());
      }
    }
    try {
      new PluginEntryView("entry", "not-a-content-proof", "", false, null, "UNSET");
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("PLUGIN_ENTRY_IDENTITY", expected.getMessage());
    }
  }
}
