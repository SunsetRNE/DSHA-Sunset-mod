package com.deepseekharness.app;

import com.deepseekharness.app.util.HttpProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Only the browser-facing handshake sees the one-time proof. Preserve the original app protocols. */
final class LanWebSocketProtocols {
  static final String ENDPOINT = "/__dsha/lan/ws-ticket";
  static final String PREFIX = "dsha-lan-ticket-v1.";
  static final String EMPTY = "dsha-lan-v1";
  private static final String FIELD = "Sec-WebSocket-Protocol";
  static final LanWebSocketProtocols DIRECT = new LanWebSocketProtocols(null, List.of(), false);
  final String ticket;
  private final List<String> original;
  private final boolean empty;

  private LanWebSocketProtocols(String ticket, List<String> original, boolean empty) {
    this.ticket = ticket;
    this.original = original;
    this.empty = empty;
  }

  static boolean validPath(String path) {
    if (path.length() > 2048 || !path.startsWith("/") || path.startsWith("//")) return false;
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c <= 32 || c >= 127 || c == '?' || c == '#' || c == '\\') return false;
    }
    return true;
  }

  static LanWebSocketProtocols read(List<String> fields, boolean websocket) throws IOException {
    if (fields.isEmpty()) return DIRECT;
    if (!websocket) throw new HttpProtocol.Failure(400, "LAN_WS_PROTOCOL_REQUIRES_UPGRADE");
    var protocols = new ArrayList<String>();
    String proof = null;
    boolean placeholder = false;
    for (String field : fields) {
      for (String item : field.split(",", -1)) {
        String name = item.trim();
        if (name.startsWith(PREFIX)) {
          if (proof != null || !name.substring(PREFIX.length()).matches("[A-Za-z0-9_-]{43}"))
            throw new HttpProtocol.Failure(400, "LAN_WS_TICKET_FORMAT");
          proof = name.substring(PREFIX.length());
        } else if (name.equals(EMPTY)) {
          if (placeholder) throw new HttpProtocol.Failure(400, "LAN_WS_DUPLICATE_PLACEHOLDER");
          placeholder = true;
        } else {
          if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
            throw new HttpProtocol.Failure(400, "LAN_WS_INVALID_PROTOCOL");
          protocols.add(name);
        }
      }
    }
    if (proof == null) return DIRECT;
    if (placeholder != protocols.isEmpty())
      throw new HttpProtocol.Failure(400, "LAN_WS_PROTOCOL_OFFER");
    return new LanWebSocketProtocols(proof, protocols, placeholder);
  }

  String upstream(String head) {
    if (ticket == null) return head;
    var out = new StringBuilder();
    for (String line : head.split("\r\n")) {
      if (!line.regionMatches(true, 0, FIELD + ":", 0, FIELD.length() + 1))
        out.append(line).append("\r\n");
    }
    if (!original.isEmpty())
      out.append(FIELD + ": ").append(String.join(", ", original)).append("\r\n");
    return out.append("\r\n").toString();
  }

  String downstream(HttpProtocol.Head response, String head) throws IOException {
    if (ticket == null || response.status != 101) return head;
    List<String> chosen = response.values(FIELD);
    if (empty && chosen.isEmpty())
      return head.substring(0, head.length() - 2) + FIELD + ": " + EMPTY + "\r\n\r\n";
    if (chosen.size() != 1 || !original.contains(chosen.get(0)))
      throw new HttpProtocol.Failure(502, "LAN_WS_UPSTREAM_PROTOCOL");
    return head;
  }
}
