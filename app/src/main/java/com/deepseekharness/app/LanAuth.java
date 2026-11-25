package com.deepseekharness.app;

/**
 * Small, Android-free LAN credential and request-line helpers.
 *
 * <p>The LAN credential is deliberately separate from the dsh BrowserAuth
 * cookie and from the 3090 bridge token. A password bootstraps an individual browser session.
 * Requests require its port-specific cookie and the browser's cached origin credential. A WSS
 * upgrade may instead prove its origin with a one-time ticket obtained over authenticated HTTPS.</p>
 */
final class LanAuth {

  private LanAuth() {}

  /** Ordinary requests present a single session cookie. Duplicate credentials are ambiguous. */
  static String sessionCookie(String head, int port) {
    if (head == null) return null;
    String prefix = com.deepseekharness.app.util.LanTokenState.cookieName(port) + "=";
    String session = null;
    for (String line : head.split("\\r?\\n")) {
      int colon = line.indexOf(':');
      if (colon <= 0 || !line.substring(0, colon).trim().equalsIgnoreCase("Cookie")) continue;
      for (String part : line.substring(colon + 1).split(";")) {
        String cookie = part.trim();
        if (cookie.startsWith(prefix)) {
          if (session != null) return null;
          session = cookie.substring(prefix.length()).trim();
        }
      }
    }
    return session;
  }

  /** The browser caches Basic for this origin. Keep only a per-run keyed tag of the verified
   * credential; routine HTTP and WSS requests do not repeat the password KDF. */
  static final class OriginProof {
    private final javax.crypto.spec.SecretKeySpec key;
    private volatile byte[] verified;

    OriginProof() {
      byte[] secret = new byte[32];
      new java.security.SecureRandom().nextBytes(secret);
      key = new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256");
      java.util.Arrays.fill(secret, (byte) 0);
    }

    boolean matches(java.util.List<String> fields) {
      byte[] expected = verified;
      byte[] presented = expected == null ? null : tag(fields);
      return presented != null && java.security.MessageDigest.isEqual(expected, presented);
    }

    /** Caller must first verify this exact request with the configured password. */
    boolean remember(java.util.List<String> fields) {
      byte[] tag = tag(fields);
      if (tag == null) return false;
      verified = tag;
      return true;
    }

    private byte[] tag(java.util.List<String> fields) {
      if (fields.size() != 1) return null;
      String header = fields.get(0);
      if (header.length() > 1024 || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
      byte[] decoded;
      try {
        decoded = java.util.Base64.getDecoder().decode(header.substring(6).trim());
      } catch (IllegalArgumentException malformed) {
        return null;
      }
      try {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(decoded);
      } catch (java.security.GeneralSecurityException unavailable) {
        throw new IllegalStateException("LAN_ORIGIN_PROOF", unavailable);
      } finally {
        java.util.Arrays.fill(decoded, (byte) 0);
      }
    }
  }

  /** Basic is only a bootstrap on a safe navigation, never a write authorization. */
  static boolean passwordOk(String head, String verifier) {
    if (head == null || !head.startsWith("GET ")) return false;
    String authorization = null;
    for (String line : head.split("\\r?\\n")) {
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String name = line.substring(0, colon).trim();
      String value = line.substring(colon + 1).trim();
      if (name.equalsIgnoreCase("Sec-Fetch-Site") && value.equalsIgnoreCase("cross-site"))
        return false;
      if (name.equalsIgnoreCase("Authorization")) {
        if (authorization != null) return false;
        authorization = value;
      }
    }
    if (authorization == null
        || !authorization.regionMatches(true, 0, "Basic ", 0, 6)
        || authorization.length() > 1024) return false;
    try {
      String decoded =
          java.nio.charset.StandardCharsets.UTF_8
              .newDecoder()
              .decode(
                  java.nio.ByteBuffer.wrap(
                      java.util.Base64.getDecoder().decode(authorization.substring(6).trim())))
              .toString();
      if (!decoded.startsWith("dsha:")) return false;
      return com.deepseekharness.app.util.LanPassword.verify(decoded.substring(5), verifier);
    } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
      return false;
    }
  }

  /** Keep callback paths and state while removing the proxy credential only. */
  static String cleanRedirectTarget(String target) {
    // A Location beginning // or /\\ is interpreted as a different authority by
    // browsers. Bootstrap may redirect only to a single-slash local target.
    if (target == null
        || !target.startsWith("/")
        || target.startsWith("//")
        || target.indexOf('\\') >= 0
        || target.indexOf('#') >= 0
        || containsControlOrSpace(target)) return "/";
    return stripTokenFromUrl(target);
  }

  /** Local backend redirects stay on the external HTTPS origin. Keep external HTTPS OAuth flows. */
  static String browserLocation(String value, int backendPort) {
    if (value.isEmpty() || value.startsWith("//") || value.indexOf('\\') >= 0) return "/";
    try {
      java.net.URI uri = new java.net.URI(value);
      if (!uri.isAbsolute()) return stripTokenFromUrl(value);
      if (uri.getHost() == null || uri.getRawUserInfo() != null) return "/";
      if ("https".equalsIgnoreCase(uri.getScheme())) return stripTokenFromUrl(value);
      if (!"http".equalsIgnoreCase(uri.getScheme())
          || uri.getPort() != backendPort
          || !("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())))
        return "/";
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) path = "/";
      if (path.startsWith("//")) return "/";
      if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
      if (uri.getRawFragment() != null) path += "#" + uri.getRawFragment();
      return stripTokenFromUrl(path);
    } catch (java.net.URISyntaxException invalid) {
      return "/";
    }
  }

  private static boolean containsControlOrSpace(String target) {
    // 逐字符检查保留相同规则，也支持 API 23。
    for (int i = 0; i < target.length(); i++) {
      char value = target.charAt(i);
      if (value <= 32 || value == 127) return true;
    }
    return false;
  }

  /** Remove only the LAN query credential while preserving HTTP version. */
  static String stripTokenFromRequestLine(String line) {
    if (line == null) return null;
    int first = line.indexOf(' ');
    if (first < 0) return line;
    int second = line.indexOf(' ', first + 1);
    String target = second > first ? line.substring(first + 1, second) : line.substring(first + 1);
    String tail = second > first ? line.substring(second) : "";
    int q = target.indexOf('?');
    if (q < 0) return line;
    String path = target.substring(0, q);
    String query = target.substring(q + 1);
    String fragment = "";
    int hash = query.indexOf('#');
    if (hash >= 0) {
      fragment = query.substring(hash);
      query = query.substring(0, hash);
    }
    StringBuilder kept = new StringBuilder();
    for (String item : query.split("&")) {
      if (item.isEmpty()) continue;
      int eq = item.indexOf('=');
      String name = eq > 0 ? item.substring(0, eq) : item;
      if ("token".equals(name)) continue;
      if (kept.length() > 0) kept.append('&');
      kept.append(item);
    }
    String rebuilt = kept.length() == 0 ? path + fragment : path + "?" + kept + fragment;
    return line.substring(0, first + 1) + rebuilt + tail;
  }

  /** Remove only an exact LAN {@code token=} query parameter from a URL. */
  static String stripTokenFromUrl(String url) {
    if (url == null || url.isEmpty()) return url;
    int hash = url.indexOf('#');
    String fragment = hash >= 0 ? url.substring(hash) : "";
    String base = hash >= 0 ? url.substring(0, hash) : url;
    int q = base.indexOf('?');
    if (q < 0) return url;
    String path = base.substring(0, q);
    String query = base.substring(q + 1);
    StringBuilder kept = new StringBuilder();
    for (String item : query.split("&", -1)) {
      if (item.isEmpty()) continue;
      int eq = item.indexOf('=');
      String name = eq > 0 ? item.substring(0, eq) : item;
      if ("token".equals(name)) continue;
      if (kept.length() > 0) kept.append('&');
      kept.append(item);
    }
    return kept.length() == 0 ? path + fragment : path + "?" + kept + fragment;
  }

  static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    int diff = a.length() ^ b.length();
    for (int i = 0; i < a.length(); i++) {
      diff |= a.charAt(i) ^ (i < b.length() ? b.charAt(i) : 0);
    }
    return diff == 0;
  }
}
