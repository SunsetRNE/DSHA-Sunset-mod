package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 只验证票据与受控观察的匹配；不把布尔夹具当作 A5 退出证明。 */
public final class PluginMutationTicketsTest {
  private PluginMutationTickets<String> ready() {
    var owner = new PluginMutationTickets<String>();
    owner.observeSource("A:7", 1);
    owner.observeWeb(
        PluginMutationTickets.Web.ready(7, "instance-a", "http://127.0.0.1/?token=private"));
    return owner;
  }

  @Test
  public void unknownSourceOrWebCannotIssueAndAbsenceIsExplicit() {
    var owner = new PluginMutationTickets<String>();
    assertNull(owner.issue());
    owner.observeSource("A:0", 1);
    assertNull(owner.issue());
    owner.observeWeb(PluginMutationTickets.Web.absent(0));
    assertNotNull(owner.issue());
    owner.observeWeb(PluginMutationTickets.Web.unavailable(0));
    assertNull(owner.issue());
    assertNotEquals(PluginMutationTickets.Web.absent(7), PluginMutationTickets.Web.unavailable(7));
  }

  @Test
  public void claimIsOnceAndForeignOwnerCannotUseTicket() {
    var owner = ready();
    var ticket = owner.issue();
    assertFalse(owner.active(ticket));
    assertTrue(owner.claim(ticket));
    assertFalse(owner.claim(ticket));
    assertTrue(owner.active(ticket));
    assertFalse(ready().claim(ticket));
    assertFalse(ready().active(ticket));
    owner.close(ticket);
    assertFalse(owner.active(ticket));
    assertFalse(owner.claim(ticket));
  }

  @Test
  public void sourceAbaCannotResurrectWaitingOrActiveTicket() {
    var owner = ready();
    var waiting = owner.issue();
    var active = owner.issue();
    assertTrue(owner.claim(active));
    owner.observeSource("B:7", 2);
    owner.observeSource("A:7", 3);
    assertFalse(owner.claim(waiting));
    assertFalse(owner.active(active));
    assertTrue(owner.claim(owner.issue()));
  }

  @Test
  public void instanceAndAuthAbaInvalidateEvenWhenSourceEquals() {
    for (var changed :
        new PluginMutationTickets.Web[] {
          PluginMutationTickets.Web.ready(7, "instance-b", "http://127.0.0.1/?token=private"),
          PluginMutationTickets.Web.ready(7, "instance-a", "http://127.0.0.1/?token=different"),
          PluginMutationTickets.Web.absent(7),
          PluginMutationTickets.Web.unavailable(7)
        }) {
      var owner = ready();
      var ticket = owner.issue();
      owner.observeWeb(changed);
      owner.observeWeb(
          PluginMutationTickets.Web.ready(7, "instance-a", "http://127.0.0.1/?token=private"));
      assertFalse(owner.claim(ticket));
    }
  }

  @Test
  public void controlledStopTransfersOnlyItsActiveTicketAndNeverReplays() {
    var owner = ready();
    var ticket = owner.issue();
    var queued = owner.issue();
    assertTrue(owner.claim(ticket));
    var stop = owner.armStop(ticket, "A:8", 40, true);
    assertNotNull(stop);
    assertFalse(owner.active(ticket));
    assertNull(owner.issue());
    var next = owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 41, true);
    assertNotNull(next);
    assertTrue(owner.active(next));
    assertFalse(owner.claim(next));
    assertFalse(owner.claim(queued));
    assertFalse(owner.active(ticket));
    assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 41, true));
  }

  @Test
  public void oneExpectedSourceAndWebObservationDuringStopAreAccepted() {
    var owner = ready();
    var ticket = owner.issue();
    assertTrue(owner.claim(ticket));
    var stop = owner.armStop(ticket, "A:8", 40, true);
    owner.observeSource("A:8", 2);
    owner.observeSource("A:8", 2);
    owner.observeWeb(PluginMutationTickets.Web.absent(8));
    assertNotNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 41, true));
  }

  @Test
  public void extraSourceOrWebTransitionDuringStopNeverGetsNarrowedAway() {
    for (int change = 0; change < 4; change++) {
      var owner = ready();
      var ticket = owner.issue();
      assertTrue(owner.claim(ticket));
      var stop = owner.armStop(ticket, "A:8", 40, true);
      if (change == 0) {
        owner.observeSource("B:7", 2);
        owner.observeSource("A:8", 3);
      }
      if (change == 1) {
        owner.observeSource("A:8", 2);
        owner.observeSource("A:7", 3);
        owner.observeSource("A:8", 4);
      }
      if (change == 2) {
        owner.observeWeb(PluginMutationTickets.Web.ready(7, "other", "private"));
        owner.observeWeb(PluginMutationTickets.Web.absent(8));
      }
      if (change == 3) owner.observeSource("A:8", 3);
      assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 41, true));
      owner.abortStop(stop);
      assertFalse(owner.active(ticket));
    }
  }

  @Test
  public void exactRevisionGenerationSourceAndMaintenanceOwnerAreRequired() {
    var owner = ready();
    var ticket = owner.issue();
    assertTrue(owner.claim(ticket));
    assertNull(owner.armStop(ticket, "A:8", 40, false));
    var stop = owner.armStop(ticket, "A:8", 40, true);
    assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 41, false));
    assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 40, true));
    assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 42, true));
    assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(9), 41, true));
    assertNull(owner.finishStop(stop, "B:8", PluginMutationTickets.Web.absent(8), 41, true));
    assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.unavailable(8), 41, true));
    owner.abortStop(stop);
    assertNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 41, true));
  }

  @Test
  public void missingAfterSourceRejectsWithoutConsumingCurrentTicketOrThrowing() {
    var owner = ready();
    var ticket = owner.issue();
    assertTrue(owner.claim(ticket));
    assertNull(owner.armStop(ticket, null, 40, true));
    assertTrue(owner.active(ticket));
    assertNotNull(owner.armStop(ticket, "A:8", 40, true));
  }

  @Test
  public void oldPreviewTicketDoesNotFollowControlledStop() {
    var owner = ready();
    var request = owner.issue();
    assertTrue(owner.claim(request));
    var preview = owner.issue();
    var stop = owner.armStop(request, "A:8", 40, true);
    assertNotNull(owner.finishStop(stop, "A:8", PluginMutationTickets.Web.absent(8), 41, true));
    assertFalse(owner.claim(preview));
  }

  @Test
  public void delayedAbaNotificationsInvalidateEvenWhenGetterIsAlreadyBackToA() {
    var owner = ready();
    var ticket = owner.issue();
    var a = PluginMutationTickets.Web.ready(7, "instance-a", "http://127.0.0.1/?token=private");
    owner.observeWebEvent(a);
    owner.observeWebEvent(a);
    assertFalse(owner.claim(ticket));
  }

  @Test
  public void ownStopAcceptsOneImmediateEventButLateOrExtraEventInvalidates() {
    var owner = ready();
    var ticket = owner.issue();
    assertTrue(owner.claim(ticket));
    var stop = owner.armStop(ticket, "A:8", 40, true);
    var absent = PluginMutationTickets.Web.absent(8);
    owner.observeWebEvent(absent);
    var next = owner.finishStop(stop, "A:8", absent, 41, true);
    assertNotNull(next);
    assertTrue(owner.active(next));
    owner.observeWebEvent(absent);
    assertFalse(owner.active(next));
    owner = ready();
    ticket = owner.issue();
    assertTrue(owner.claim(ticket));
    stop = owner.armStop(ticket, "A:8", 40, true);
    owner.observeWebEvent(absent);
    owner.observeWebEvent(absent);
    assertNull(owner.finishStop(stop, "A:8", absent, 41, true));
  }

  @Test
  public void privateAuthNeverAppearsInPublicTicketOrWebText() {
    var web =
        PluginMutationTickets.Web.ready(7, "instance", "http://127.0.0.1/?token=secret-value");
    assertFalse(web.toString().contains("secret-value"));
    assertFalse(web.toString().contains("http"));
    assertEquals(
        web,
        PluginMutationTickets.Web.ready(7, "instance", "http://127.0.0.1/?token=secret-value"));
  }
}
