package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class LanTokenStateTest {
  @Test
  public void individualLoginsExpireAtTheirOwnAbsoluteDeadline() {
    AtomicLong elapsed = new AtomicLong(100);
    LanTokenState state = new LanTokenState(elapsed::get);
    String first = state.issue();
    elapsed.incrementAndGet();
    String second = state.issue();
    assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
    assertNotEquals(first, second);
    elapsed.set(99 + TimeUnit.HOURS.toMillis(12));
    assertTrue(state.accepts(first));
    elapsed.incrementAndGet();
    assertFalse(state.accepts(first));
    assertTrue(state.accepts(second));
    elapsed.incrementAndGet();
    assertFalse(state.accepts(second));
  }

  @Test
  public void capacityAndRevocationAreEnforcedByTheServer() {
    LanTokenState state = new LanTokenState(() -> 0L);
    String first = state.issue();
    String second = state.issue();
    for (int i = 2; i <= LanTokenState.MAX_SESSIONS; i++) state.issue();
    assertFalse(state.accepts(first));
    assertTrue(state.accepts(second));
    state.revoke();
    assertFalse(state.accepts(second));
    assertFalse(state.accepts(null));
    assertFalse(state.accepts(""));
  }

  @Test
  public void websocketTicketIsBoundToItsRunSessionPathAndSingleUse() throws Exception {
    var clock = new AtomicLong(100);
    var state = new LanTokenState(clock::get);
    String session = state.issue(), other = state.issue();
    String ticket = state.issueTicket(session, "/api/remote.mux");
    assertNotNull(ticket);
    assertFalse(new LanTokenState(clock::get).consumeTicket(ticket, session, "/api/remote.mux"));
    assertFalse(state.consumeTicket(ticket, other, "/api/remote.mux"));
    assertFalse(state.consumeTicket(ticket, session, "/another"));
    var wins = new java.util.concurrent.atomic.AtomicInteger();
    java.util.stream.IntStream.range(0, 8)
        .parallel()
        .forEach(
            i -> {
              if (state.consumeTicket(ticket, session, "/api/remote.mux")) wins.incrementAndGet();
            });
    assertEquals(1, wins.get());
    String expired = state.issueTicket(session, "/a");
    clock.addAndGet(LanTokenState.TICKET_MS);
    assertFalse(state.consumeTicket(expired, session, "/a"));
    String revoked = state.issueTicket(session, "/a");
    state.revoke();
    assertFalse(state.consumeTicket(revoked, session, "/a"));
  }

  @Test
  public void outstandingTicketLimitsRecoverAfterTimeout() {
    var clock = new AtomicLong(1);
    var state = new LanTokenState(clock::get);
    int count = 0;
    while (count < LanTokenState.TICKET_LIMIT) {
      String session = state.issue();
      for (int i = 0; i < LanTokenState.TICKETS_PER_SESSION; i++) {
        assertNotNull(state.issueTicket(session, "/stream"));
        count++;
      }
      assertNull(state.issueTicket(session, "/overflow"));
    }
    String last = state.issue();
    assertNull(state.issueTicket(last, "/stream"));
    clock.addAndGet(LanTokenState.TICKET_MS);
    assertNotNull(state.issueTicket(last, "/stream"));
  }
}
