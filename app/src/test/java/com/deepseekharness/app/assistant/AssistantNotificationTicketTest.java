package com.deepseekharness.app.assistant;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public final class AssistantNotificationTicketTest {
  private static AssistantBinding binding() {
    return new AssistantBinding("host", "prefix-A-same1234", "connection", 4);
  }

  private static AssistantNotificationTicket ticket() {
    return new AssistantNotificationTicket(binding(), "17", "owner-epoch", "STOP_TASK");
  }

  @Test
  public void fullConversationAndTurnMustMatch() {
    assertTrue(ticket().addresses(binding(), "17"));
    assertFalse(
        ticket()
            .addresses(new AssistantBinding("host", "prefix-B-same1234", "connection", 4), "17"));
    assertFalse(ticket().addresses(binding(), "18"));
    assertFalse(ticket().addresses(binding(), null));
  }

  @Test
  public void everyBindingDimensionChangesPendingIntentIdentity() {
    String original = ticket().identity();
    for (AssistantBinding changed :
        List.of(
            new AssistantBinding("next-host", binding().sessionId(), "connection", 4),
            new AssistantBinding("host", "prefix-B-same1234", "connection", 4),
            new AssistantBinding("host", binding().sessionId(), "next-connection", 4),
            new AssistantBinding("host", binding().sessionId(), "connection", 5))) {
      var changedTicket =
          new AssistantNotificationTicket(changed, "17", "owner-epoch", "STOP_TASK");
      assertNotEquals(original, changedTicket.identity());
      assertFalse(ticket().addresses(changed, "17"));
    }
  }

  @Test
  public void turnAndNotificationEpochDoNotReusePendingIntentIdentity() {
    assertNotEquals(
        ticket().identity(),
        new AssistantNotificationTicket(binding(), "18", "owner-epoch", "STOP_TASK").identity());
    assertNotEquals(
        ticket().identity(),
        new AssistantNotificationTicket(binding(), "17", "next-epoch", "STOP_TASK").identity());
    assertEquals(
        ticket().identity(),
        new AssistantNotificationTicket(binding(), "17", "owner-epoch", "STOP_TASK").identity());
  }

  @Test
  public void actionsHaveDifferentIdentityAtTheSameTask() {
    var identities = new HashSet<String>();
    for (String action : List.of("STOP_TASK", "REPLY", "SHOW_OVERLAY", "OPEN"))
      assertTrue(identities.add(ticket().forAction(action).identity()));
    assertEquals(4, identities.size());
  }

  @Test
  public void separatorsAndUnicodeCannotCollideAcrossAddressFields() {
    var first =
        new AssistantNotificationTicket(
            new AssistantBinding("host/a", "b", "连接", 4), "17", "epoch", "STOP_TASK");
    var second =
        new AssistantNotificationTicket(
            new AssistantBinding("host", "a/b", "连接", 4), "17", "epoch", "STOP_TASK");
    assertNotEquals(first.identity(), second.identity());
    assertTrue(first.identity().startsWith("dsha-assistant://task/"));
    assertFalse(first.identity().contains("连接"));
  }

  @Test
  public void idleConversationKeepsAnExplicitEmptyTurn() {
    var idle = new AssistantNotificationTicket(binding(), "", "epoch", "REPLY");
    assertTrue(idle.addresses(binding(), ""));
    assertFalse(idle.addresses(binding(), "17"));
    assertNotEquals(idle.identity(), ticket().forAction("REPLY").identity());
  }

  @Test
  public void invalidTicketsFailBeforeBecomingActions() {
    for (String action : new String[] {null, "", "stop", "ANY_ACTION"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AssistantNotificationTicket(binding(), "17", "epoch", action));
    }
    for (String epoch : new String[] {null, "", " ", "bad\0epoch", "x".repeat(129)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AssistantNotificationTicket(binding(), "17", epoch, "STOP_TASK"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new AssistantNotificationTicket(binding(), null, "epoch", "STOP_TASK"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AssistantNotificationTicket(binding(), "bad\0turn", "epoch", "STOP_TASK"));
    assertThrows(
        NullPointerException.class,
        () -> new AssistantNotificationTicket(null, "17", "epoch", "STOP_TASK"));
  }
}
