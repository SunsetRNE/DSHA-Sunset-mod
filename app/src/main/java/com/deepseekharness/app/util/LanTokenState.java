package com.deepseekharness.app.util;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A bounded set of independent browser sessions for one listener. No credential is persisted. */
public final class LanTokenState {
  public static final int MAX_AGE_SECONDS = 12 * 60 * 60;
  static final int MAX_SESSIONS = 64;
  private static final SecureRandom RANDOM = new SecureRandom();
  private final LinkedHashMap<String, Long> expires = new LinkedHashMap<>();

  private record SocketTicket(String session, String path, long deadline) {}

  private final LinkedHashMap<String, SocketTicket> tickets = new LinkedHashMap<>();
  static final long TICKET_MS = 60_000;
  static final int TICKET_LIMIT = 256, TICKETS_PER_SESSION = 16;
  private final LongSupplier elapsedMillis;

  /** Browsers share their cookie jar across ports on one host. Scope each listener's name. */
  public static String cookieName(int port) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("LAN_COOKIE_PORT");
    return "__Host-dsha_lan_" + port;
  }

  public LanTokenState(LongSupplier elapsedMillis) {
    this.elapsedMillis = elapsedMillis;
  }

  public synchronized String issue() {
    long now = elapsedMillis.getAsLong();
    expires.values().removeIf(deadline -> deadline <= now);
    if (expires.size() >= MAX_SESSIONS) expires.remove(expires.keySet().iterator().next());
    byte[] bytes = new byte[32];
    String token;
    do {
      RANDOM.nextBytes(bytes);
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    } while (expires.containsKey(token));
    expires.put(token, now + TimeUnit.SECONDS.toMillis(MAX_AGE_SECONDS));
    return token;
  }

  public synchronized boolean accepts(String token) {
    if (token == null || token.length() != 43) return false;
    Long deadline = expires.get(token);
    if (deadline == null) return false;
    if (elapsedMillis.getAsLong() < deadline) return true;
    expires.remove(token);
    return false;
  }

  /** Each LanTokenState belongs to one held listener and generation. Never evict a live peer's ticket. */
  public synchronized String issueTicket(String session, String path) {
    if (!accepts(session)) return null;
    long now = elapsedMillis.getAsLong();
    tickets
        .values()
        .removeIf(ticket -> ticket.deadline <= now || !expires.containsKey(ticket.session));
    if (tickets.size() >= TICKET_LIMIT) return null;
    int count = 0;
    for (SocketTicket ticket : tickets.values())
      if (ticket.session.equals(session) && ++count >= TICKETS_PER_SESSION) return null;
    byte[] bytes = new byte[32];
    String token;
    do {
      RANDOM.nextBytes(bytes);
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    } while (tickets.containsKey(token));
    tickets.put(token, new SocketTicket(session, path, now + TICKET_MS));
    return token;
  }

  public synchronized boolean consumeTicket(String token, String session, String path) {
    if (token == null || !accepts(session)) return false;
    SocketTicket ticket = tickets.get(token);
    if (ticket == null || !ticket.session.equals(session) || !ticket.path.equals(path))
      return false;
    tickets.remove(token);
    return elapsedMillis.getAsLong() < ticket.deadline;
  }

  public synchronized void revoke() {
    expires.clear();
    tickets.clear();
  }
}
