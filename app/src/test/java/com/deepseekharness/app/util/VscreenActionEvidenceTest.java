package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public final class VscreenActionEvidenceTest {
  private static final String CORE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String TOKEN = "11111111-2222-3333-4444-555555555555";

  @Test
  public void sealedEvidenceHasFiveSeparateRecordTypes() {
    Set<Class<?>> expected =
        Set.of(
            VscreenActionEvidence.RawFrame.class,
            VscreenActionEvidence.Node.class,
            VscreenActionEvidence.Editor.class,
            VscreenActionEvidence.ObservedAnchor.class,
            VscreenActionEvidence.AnchoredClick.class);
    assertTrue(VscreenActionEvidence.class.isSealed());
    assertEquals(expected, Set.of(VscreenActionEvidence.class.getPermittedSubclasses()));
    for (Class<?> kind : expected) {
      assertTrue(kind.isRecord());
      for (Class<?> other : expected) if (kind != other) assertFalse(kind.isAssignableFrom(other));
    }
  }

  @Test
  public void rawEvidenceKeepsOriginalCoreGenerationAndFrameWithoutRenewal() {
    var original = new VscreenActionEvidence.RawFrame(CORE, "display-before", 7);
    var later = new VscreenActionEvidence.RawFrame(TOKEN, "display-after", 99);
    assertEquals(CORE, original.coreInstance());
    assertEquals("display-before", original.generation());
    assertEquals(7L, original.frameSeq());
    assertEquals(TOKEN, later.coreInstance());
    assertEquals(99L, later.frameSeq());
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.RawFrame(CORE, "display-before", -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.RawFrame(CORE, "display-before", 0));
  }

  @Test
  public void semanticNodeDoesNotAcquireOrRequirePixelFrameEvidence() {
    var withoutPixels = new VscreenActionEvidence.Node(CORE, "display", null, "node-only");
    var incidentalFrame = new VscreenActionEvidence.Node(CORE, "display", 500L, "node-only");
    assertNull(withoutPixels.frameSeq());
    assertEquals("node-only", withoutPixels.nodeId());
    assertEquals(Long.valueOf(500), incidentalFrame.frameSeq());
    assertEquals(withoutPixels.generation(), incidentalFrame.generation());
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.Node(CORE, "display", -1L, "node-only"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.Node(CORE, "display", null, ""));
  }

  @Test
  public void editorCarriesFocusIdentityWithoutFrameOrOneUseObservationFields() {
    var get = new VscreenActionEvidence.Editor(CORE, "display", "");
    var edit = new VscreenActionEvidence.Editor(CORE, "display", "8:42");
    assertEquals("", get.editorId());
    assertEquals("8:42", edit.editorId());
    assertEquals(
        Set.of("coreInstance", "generation", "editorId"),
        Arrays.stream(VscreenActionEvidence.Editor.class.getRecordComponents())
            .map(component -> component.getName())
            .collect(Collectors.toSet()));
  }

  @Test
  public void observedAnchorAndSelectedAnchorKeepDifferentTokenTypesAndOriginalSourceFrame() {
    var observation = new VscreenActionEvidence.ObservedAnchor(CORE, "original", 10, TOKEN);
    var selected = new VscreenActionEvidence.AnchoredClick(CORE, "original", 10, TOKEN);
    assertEquals(TOKEN, observation.observationId());
    assertEquals(TOKEN, selected.anchorId());
    assertEquals(10L, observation.frameSeq());
    assertEquals(10L, selected.frameSeq());
    assertFalse(observation.equals(selected));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.ObservedAnchor("", "original", 10, TOKEN));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.AnchoredClick(CORE, "original", 10, "editor:42"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.ObservedAnchor(CORE, "original", 9007199254740992L, TOKEN));
  }

  @Test
  public void legacyMissingCoreIsNotFilledFromCurrentStatusByEvidenceCarrier() {
    var raw = new VscreenActionEvidence.RawFrame("", "original", 1);
    var node = new VscreenActionEvidence.Node("", "original", null, "n");
    var editor = new VscreenActionEvidence.Editor("", "original", "");
    assertEquals("", raw.coreInstance());
    assertEquals("", node.coreInstance());
    assertEquals("", editor.coreInstance());
    assertThrows(
        IllegalArgumentException.class,
        () -> new VscreenActionEvidence.RawFrame("not-a-core", "original", 1));
    assertThrows(
        IllegalArgumentException.class, () -> new VscreenActionEvidence.Editor(CORE, "", "e"));
  }
}
