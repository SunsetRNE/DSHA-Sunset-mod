package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class VscreenAnchoredGrammarTest {
  private static final String ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  private Map<String, Object> base() {
    var body = new LinkedHashMap<String, Object>();
    body.put("coreInstance", ID);
    body.put("generation", "display-one");
    body.put("frameSeq", 10L);
    return body;
  }

  @Test
  public void explicitPostAnchorAndClickPreserveOriginalSequence() throws Exception {
    var anchor = base();
    anchor.put("observationId", ID);
    anchor.put("x", 12);
    anchor.put("y", 20);
    var request = VscreenBridgeRequest.json("/app/vscreen/anchor", anchor);
    assertEquals("/app/vscreen/anchor", request.route());
    assertEquals("10", request.parameters.get("frameSeq"));
    var click = base();
    click.put("anchorId", ID);
    assertEquals(
        "/app/vscreen/anchored-click",
        VscreenBridgeRequest.json("/app/vscreen/anchored-click", click).route());
    assertTrue(VscreenBridgeRequest.postRoute("/app/vscreen/anchored-click"));
    assertTrue(VirtualScreenRoutes.mutates("anchored-click"));
    assertFalse(VirtualScreenRoutes.mutates("anchor"));
  }

  @Test
  public void productionDispatcherPassesOriginalObservedAnchorAndAnchoredClickSeparately()
      throws Exception {
    var body = base();
    body.put("observationId", ID);
    body.put("x", 12);
    body.put("y", 20);
    var anchor = VscreenBridgeRequest.json("/app/vscreen/anchor", body);
    var actions = new VscreenBridgeRequestTest.TypedActions();
    assertEquals("anchor", anchor.execute(actions));
    assertSame(anchor.actionEvidence(), actions.evidence);
    var original = (VscreenActionEvidence.ObservedAnchor) actions.evidence;
    assertEquals(ID, original.coreInstance());
    assertEquals("display-one", original.generation());
    assertEquals(10L, original.frameSeq());
    assertEquals(ID, original.observationId());
    assertEquals(12f, actions.x, 0f);
    assertEquals(20f, actions.y, 0f);
    assertEquals(1, actions.typedCalls);
    assertEquals(0, actions.calls);

    var clickBody = base();
    String anchorId = "11111111-2222-3333-4444-555555555555";
    clickBody.put("anchorId", anchorId);
    var click = VscreenBridgeRequest.json("/app/vscreen/anchored-click", clickBody);
    var clicked = new VscreenBridgeRequestTest.TypedActions();
    assertEquals("anchored-click", click.execute(clicked));
    assertSame(click.actionEvidence(), clicked.evidence);
    var proof = (VscreenActionEvidence.AnchoredClick) clicked.evidence;
    assertEquals(ID, proof.coreInstance());
    assertEquals("display-one", proof.generation());
    assertEquals(10L, proof.frameSeq());
    assertEquals(anchorId, proof.anchorId());
    assertEquals(1, clicked.typedCalls);
    assertEquals(0, clicked.calls);
  }

  @Test
  public void otherTokenNameCannotSatisfyAnchorOrAnchoredClickAndNeverDispatches()
      throws Exception {
    for (String route : new String[] {"anchor", "anchored-click"}) {
      var body = base();
      body.put(route.equals("anchor") ? "anchorId" : "observationId", ID);
      if (route.equals("anchor")) {
        body.put("x", 1);
        body.put("y", 2);
      }
      var actions = new VscreenBridgeRequestTest.TypedActions();
      var failure =
          assertThrows(
              HttpProtocol.Failure.class,
              () -> VscreenBridgeRequest.json("/app/vscreen/" + route, body).execute(actions));
      assertEquals(
          route.equals("anchor") ? "INVALID_observationId" : "INVALID_anchorId",
          failure.getMessage());
      assertEquals(0, actions.typedCalls);
      assertEquals(0, actions.calls);
    }
  }

  @Test
  public void unknownAnchoredResultIsReturnedWithoutReobservingOrRawFallback() throws Exception {
    var body = base();
    body.put("anchorId", ID);
    var request = VscreenBridgeRequest.json("/app/vscreen/anchored-click", body);
    var actions =
        new VscreenBridgeRequestTest.TypedActions() {
          @Override
          public String anchoredClick(VscreenActionEvidence.AnchoredClick evidence) {
            super.anchoredClick(evidence);
            return "INPUT_RESULT_UNKNOWN";
          }
        };
    assertEquals("INPUT_RESULT_UNKNOWN", request.execute(actions));
    assertEquals(1, actions.typedCalls);
    assertEquals(0, actions.calls);
    assertSame(request.actionEvidence(), actions.evidence);
    assertTrue(actions.evidence instanceof VscreenActionEvidence.AnchoredClick);
  }

  @Test
  public void typedDefaultsStillCallLegacyAnchorAdapterWithOriginalFields() throws Exception {
    var body = base();
    body.put("observationId", ID);
    body.put("x", 12);
    body.put("y", 20);
    var legacy =
        new VscreenBridgeRequestTest.Actions() {
          @Override
          public String anchor(String gen, long frame, String observation, int x, int y) {
            calls++;
            assertEquals("display-one", gen);
            assertEquals(10L, frame);
            assertEquals(ID, observation);
            assertEquals(12, x);
            assertEquals(20, y);
            return "legacy-anchor";
          }
        };
    assertEquals(
        "legacy-anchor", VscreenBridgeRequest.json("/app/vscreen/anchor", body).execute(legacy));
    assertEquals(1, legacy.calls);
  }

  @Test
  public void missingIdentityGuessedTokenAndFractionalCoordinatesAreRejected() throws Exception {
    var body = base();
    body.put("observationId", ID);
    body.put("x", 1.5);
    body.put("y", 2);
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/anchor", body));
    body.put("x", 1);
    body.put("observationId", "NOT-A-LEASE");
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/anchor", body));
    body.put("observationId", ID);
    body.remove("coreInstance");
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/anchor", body));
  }

  @Test
  public void legacyTapGrammarIsStillExplicitFrameAndNoImplicitAnchor() throws Exception {
    var body = base();
    body.put("x", 1);
    body.put("y", 2);
    var request = VscreenBridgeRequest.json("/app/vscreen/tap", body);
    assertEquals(VscreenBridgeRequest.Operation.TAP, request.operation);
    body.put("anchorId", ID);
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/tap", body));
  }
}
