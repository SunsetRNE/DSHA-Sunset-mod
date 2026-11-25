package com.deepseekharness.app;

import android.content.Context;
import android.util.Log;
import android.os.SystemClock;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.util.LanTokenState;
import com.deepseekharness.app.util.LanAccessPresentation;
import com.deepseekharness.app.util.LanAddressPolicy;

import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.SensitiveData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import com.deepseekharness.app.util.HttpProtocol;
import com.deepseekharness.app.util.SocketDispatch;
import com.deepseekharness.app.util.LanNetworkState;

/**
 * Optional LAN bridge for the dsh Web UI.
 *
 * <p>dsh itself is always loopback-only. This service is the only component
 * that binds the LAN address and it never forwards the dsh launch token or a
 * backend Set-Cookie header to a LAN client. The dsh BrowserAuth cookie is
 * captured after an internal loopback exchange and retained only in memory.</p>
 */
public final class LanProxyService {

  private static final String TAG = "DSHA-LanProxy";
  // Approval belongs to this Android process and this exact observed network instance.
  private static LanNetworkState trustedNetwork;

  /** Historical preference only; published links must use port(generation). */
  public static final int LAN_PORT = com.deepseekharness.app.util.LanPortPolicy.LEGACY_PORT;

  public static final int DEFAULT_BACKEND_PORT = 3080;

  /**
   * BrowserAuth state is published as one immutable reference.  Keeping the
   * generation and cookie in separate volatile fields would allow a request
   * to observe a generation from one dsh run and a cookie from another.
   */
  private static final class AuthState {
    final String cookie;
    final long generation;

    AuthState(String cookie, long generation) {
      this.cookie = cookie;
      this.generation = generation;
    }
  }

  private static final AuthState NO_DSH_AUTH = new AuthState("", 0);
  private static volatile AuthState dshAuth = NO_DSH_AUTH;

  /**
   * Opaque, immutable request credential lease.  It is deliberately not
   * serializable and exposes no cookie accessor; it only lets a handler
   * prove its prior snapshot still belongs to the live dsh generation.
   */
  static final class AuthSnapshot {
    private final AuthState state;

    private AuthSnapshot(AuthState state) {
      this.state = state;
    }
  }

  /** One bind/listener lifetime.  An old accept loop must never tear down a new one. */
  private static final class ProxyRun {
    final long epoch;
    final long generation;
    final int backendPort;
    final String passwordVerifier;
    final SocketDispatch dispatch = new SocketDispatch("dsha-lan", 32, 8, 16, 8, 4);
    final Context context;
    final LanNetworkState network;
    final LanTokenState sessions = new LanTokenState(SystemClock::elapsedRealtime);
    final ConcurrentHashMap<SocketDispatch.Ticket, String> connections = new ConcurrentHashMap<>();
    final java.util.Set<SocketDispatch.Ticket> handshakes = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<SocketDispatch.Ticket, Long> finiteDeadlines =
        new ConcurrentHashMap<>();
    String fingerprint = "";
    final LanLoginThrottle loginThrottle = new LanLoginThrottle();
    final LanAuth.OriginProof originProof = new LanAuth.OriginProof();
    volatile boolean active = true;

    /** Set only after the ServerSocket bind has completed successfully. */
    volatile boolean bound;

    volatile ServerSocket server;
    volatile Thread acceptThread;

    ProxyRun(
        long epoch,
        long generation,
        int backendPort,
        String passwordVerifier,
        Context context,
        LanNetworkState network) {
      this.context = context;
      this.network = network;
      this.epoch = epoch;
      this.generation = generation;
      this.backendPort = backendPort;
      this.passwordVerifier = passwordVerifier;
    }
  }

  private static volatile ProxyRun activeRun;
  private static long nextRunEpoch;
  /* Retained for the package-private rewriteResponse(String) test helper. */
  private static volatile int backendPort = DEFAULT_BACKEND_PORT;
  private static volatile String logPath = "";
  private static final Object LOG_LOCK = new Object();
  private static final java.util.Map<String, Long> lastConnLog =
      new java.util.concurrent.ConcurrentHashMap<>();

  private LanProxyService() {}

  /** Called only for an explicit foreground user choice. Do not approve a replacement network. */
  public static boolean trustNetwork(Context context, LanNetworkState selected) {
    LanNetworkState current = LocalNetworkAccess.currentLan(context);
    synchronized (LanProxyService.class) {
      if (selected == null || !selected.equals(current)) return false;
      if (!selected.equals(trustedNetwork) && activeRun != null) deactivateRunLocked(activeRun);
      trustedNetwork = selected;
      return true;
    }
  }

  public static synchronized void forgetNetwork() {
    trustedNetwork = null;
    if (activeRun != null) deactivateRunLocked(activeRun);
  }

  private static synchronized boolean stillTrusted(LanNetworkState current) {
    if (current != null && current.equals(trustedNetwork)) return true;
    forgetNetwork();
    return false;
  }

  /**
   * Publish one BrowserAuth cookie for the current dsh generation. The
   * caller must have obtained it from a loopback 303 exchange.
   */
  public static synchronized boolean setDshAuthCookie(String cookie, long generation) {
    if (!isValidDshCookie(cookie) || generation <= 0) return false;
    String normalized = cookie.trim();
    ProxyRun run = activeRun;
    // A different dsh generation must never reuse the old listener. A
    // same-generation cookie refresh is safe: existing workers retain an
    // old AuthState snapshot and fail the identity check below, while new
    // requests receive only the replacement cookie.
    if (run != null && run.generation != generation) {
      deactivateRunLocked(run);
      closeRunSockets(run);
    }
    dshAuth = new AuthState(normalized, generation);
    return true;
  }

  /** Clear the in-memory backend credential when dsh stops or restarts. */
  public static synchronized void clearDshAuth(long generation) {
    AuthState current = dshAuth;
    if (generation == 0 || current.generation == generation) {
      dshAuth = NO_DSH_AUTH;
      ProxyRun run = activeRun;
      if (run != null && (generation == 0 || run.generation == generation)) {
        deactivateRunLocked(run);
        closeRunSockets(run);
      }
    }
  }

  public static boolean hasDshAuth(long generation) {
    return hasDshAuth(dshAuth, generation);
  }

  private static boolean hasDshAuth(AuthState state, long generation) {
    return state != null
        && generation > 0
        && generation == state.generation
        && isValidDshCookie(state.cookie);
  }

  /** Return an opaque coherent credential snapshot for the requested generation. */
  static synchronized AuthSnapshot snapshotDshAuth(long generation) {
    AuthState current = dshAuth;
    return hasDshAuth(current, generation) ? new AuthSnapshot(current) : null;
  }

  /** True only while a previously acquired credential snapshot is still current. */
  static synchronized boolean isCurrentDshAuth(AuthSnapshot snapshot) {
    return snapshot != null
        && dshAuth == snapshot.state
        && hasDshAuth(snapshot.state, snapshot.state.generation);
  }

  private static synchronized AuthSnapshot snapshotDshAuth(ProxyRun run) {
    if (!isActiveRunLocked(run)) return null;
    AuthState current = dshAuth;
    return hasDshAuth(current, run.generation) ? new AuthSnapshot(current) : null;
  }

  private static synchronized boolean isCurrentDshAuth(ProxyRun run, AuthSnapshot snapshot) {
    return snapshot != null
        && isActiveRunLocked(run)
        && dshAuth == snapshot.state
        && hasDshAuth(snapshot.state, run.generation);
  }

  private static boolean isValidDshCookie(String cookie) {
    if (cookie == null) return false;
    int semi = cookie.indexOf(';');
    if (semi >= 0) cookie = cookie.substring(0, semi);
    int eq = cookie.indexOf('=');
    if (eq <= 0 || eq != cookie.lastIndexOf('=')) return false;
    String name = cookie.substring(0, eq);
    String value = cookie.substring(eq + 1);
    return name.startsWith("dsh-auth-")
        && name.length() > "dsh-auth-".length()
        && name.matches("dsh-auth-[A-Za-z0-9_-]+")
        && value.matches("[A-Za-z0-9_.-]+")
        && value.length() <= 4096;
  }

  /**
   * Select the BrowserAuth cookie from an HTTP response without assuming it
   * is the first {@code Set-Cookie} field. Android URLConnection variants
   * differ in whether repeated headers are returned as a list or one comma
   * separated value, so inspect both shapes and accept only dsh-auth pairs.
   */
  static String extractDshAuthCookie(
      java.util.Map<String, ? extends java.util.List<String>> headers) {
    if (headers == null || headers.isEmpty()) return null;
    for (java.util.Map.Entry<String, ? extends java.util.List<String>> entry : headers.entrySet()) {
      if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) {
        continue;
      }
      java.util.List<String> values = entry.getValue();
      if (values == null) continue;
      for (String value : values) {
        if (value == null || value.isEmpty()) continue;
        for (String segment : value.split(",")) {
          int semi = segment.indexOf(';');
          String pair = (semi >= 0 ? segment.substring(0, semi) : segment).trim();
          if (isValidDshCookie(pair)) return pair;
        }
      }
    }
    return null;
  }

  /** Compatibility overload: without an exchanged BrowserAuth cookie it does nothing. */
  public static void start(String rootfsDir, Context ctx) {
    start(rootfsDir, ctx, DEFAULT_BACKEND_PORT, dshAuth.generation);
  }

  /** Compatibility overload retained for service callers. */
  public static void start(String rootfsDir, Context ctx, int backend) {
    start(rootfsDir, ctx, backend, dshAuth.generation);
  }

  /** Start the LAN bridge only after a current BrowserAuth cookie is available. */
  public static void start(String rootfsDir, Context ctx, int backend, long generation) {
    if (ctx == null
        || backend <= 0
        || backend > 65535
        || com.deepseekharness.app.util.WebPortPolicy.reserved(backend)) return;
    LanNetworkState approved;
    synchronized (LanProxyService.class) {
      if (!hasDshAuth(generation) || trustedNetwork == null) return;
      approved = trustedNetwork;
    }
    Context app = ctx.getApplicationContext();
    var prefs = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    boolean enabled = prefs.getBoolean(Constants.KEY_LAN_MODE, false);
    LanNetworkState observed =
        enabled && LocalNetworkAccess.granted(app) ? LocalNetworkAccess.currentLan(app) : null;
    String verifier = prefs.getString(com.deepseekharness.app.util.LanPassword.PREF_KEY, "");
    synchronized (LanProxyService.class) {
      if (trustedNetwork != approved || !hasDshAuth(generation) || !stillTrusted(observed)) return;
      // A password save or disable may have completed during the Android network lookup.
      if (!prefs.getBoolean(Constants.KEY_LAN_MODE, false)
          || verifier == null
          || verifier.isEmpty()
          || !verifier.equals(
              prefs.getString(com.deepseekharness.app.util.LanPassword.PREF_KEY, ""))) return;
      ProxyRun previous = activeRun;
      if (isActiveRunLocked(previous)) {
        if (previous.generation == generation
            && previous.backendPort == backend
            && previous.passwordVerifier.equals(verifier)) return;
        deactivateRunLocked(previous);
      }
      logPath = rootfsDir == null ? "" : rootfsDir + "/root/dsh-lan.log";
      ProxyRun run = new ProxyRun(++nextRunEpoch, generation, backend, verifier, app, observed);
      backendPort = backend;
      activeRun = run;
      Thread accept = new Thread(() -> acceptLoop(run), "dsha-lan-accept-" + run.epoch);
      accept.setDaemon(true);
      run.acceptThread = accept;
      accept.start();
    }
  }

  private static void acceptLoop(ProxyRun run) {
    ServerSocket ss = null;
    try {
      // KeyStore may need a hardware round trip. UI and stop calls must not wait on it.
      LanTlsIdentity identity = AndroidLanIdentity.load(run.network.address());
      if (!networkCurrent(run)) return;
      synchronized (LanProxyService.class) {
        if (!isActiveRunLocked(run)) {
          closeQuietly(ss);
          return;
        }
        // A retired, not-yet-bound accept thread cannot race a replacement
        // listener for the same port after start/stop changed the active run.
        ss =
            identity.bind(
                java.net.InetAddress.getByName(run.network.address()),
                com.deepseekharness.app.util.LanPortPolicy.preferred(
                    com.deepseekharness.app.util.AppIdentity.applicationId()));
        ss.setSoTimeout(1000);
        run.server = ss;
        run.fingerprint = identity.fingerprint;
        run.bound = true;
      }
      log(
          com.deepseekharness.app.util.UiText.format(
              "LAN 代理已启动：%s -> 127.0.0.1:%s", ss.getLocalPort(), run.backendPort));
      long nextNetworkCheck = System.nanoTime() + 1_000_000_000L;
      while (isActiveRun(run)) {
        try {
          if (System.nanoTime() >= nextNetworkCheck) {
            nextNetworkCheck = System.nanoTime() + 1_000_000_000L;
            if (!networkCurrent(run)) break;
            long now = System.nanoTime();
            run.finiteDeadlines.forEach(
                (ticket, deadline) -> {
                  if (ticket.ended()) run.finiteDeadlines.remove(ticket, deadline);
                  else if (now >= deadline) {
                    ticket.close();
                    run.finiteDeadlines.remove(ticket, deadline);
                  }
                });
            for (var pending : run.handshakes)
              if (now - pending.acceptedAt >= HttpProtocol.LAN.timeoutMs * 1_000_000L)
                pending.close();
            run.connections.forEach(
                (ticket, session) -> {
                  if (!run.sessions.accepts(session)) ticket.close();
                  if (ticket.ended()) run.connections.remove(ticket, session);
                });
          }
          Socket client = ss.accept();
          client.setSoTimeout(HttpProtocol.LAN.timeoutMs);
          if (!run.network.admits(client.getInetAddress()) || !isActiveRun(run)) {
            closeQuietly(client);
            continue;
          }
          SocketDispatch.Ticket ticket = run.dispatch.accept(client);
          if (ticket == null) continue;
          run.handshakes.add(ticket);
          if (!run.dispatch.read(ticket, () -> handshakeAndRead(ticket, run))) {
            run.handshakes.remove(ticket);
            ticket.close();
          }
        } catch (java.net.SocketTimeoutException idle) {
          // Wake periodically even when only long-lived streams remain.
        } catch (IOException e) {
          if (isActiveRun(run))
            log(
                com.deepseekharness.app.util.UiText.format(
                    "接收连接失败：%s", e.getClass().getSimpleName()));
        }
      }
    } catch (IOException | java.security.GeneralSecurityException e) {
      if (isActiveRun(run))
        log(
            com.deepseekharness.app.util.UiText.format(
                "LAN 代理绑定失败：%s", e.getClass().getSimpleName()));
    } catch (RuntimeException e) {
      // A denied bind or an OEM networking failure must retire this run instead
      // of escaping the accept thread and terminating the application process.
      // Report only the exception type; network exceptions can contain addresses.
      if (isActiveRun(run)) log("LAN 代理运行失败：" + e.getClass().getSimpleName());
    } finally {
      synchronized (LanProxyService.class) {
        closeQuietly(ss);
        if (run.server == ss) run.server = null;
        run.bound = false;
        if (activeRun == run) {
          run.active = false;
          activeRun = null;
        }
      }
      closeRunSockets(run);
      run.dispatch.close();
    }
  }

  private static void handshakeAndRead(SocketDispatch.Ticket ticket, ProxyRun run) {
    try {
      ((javax.net.ssl.SSLSocket) ticket.socket).startHandshake();
      run.handshakes.remove(ticket);
      long until = ticket.acceptedAt + HttpProtocol.LAN.timeoutMs * 1_000_000L;
      new LanConnection(ticket, run).read(until, true);
    } catch (IOException failure) {
      ticket.close();
    } finally {
      run.handshakes.remove(ticket);
    }
  }

  public static void stop() {
    stop(0);
  }

  /**
   * Stop only the externally reachable listener after the user turns LAN
   * sharing off.  The local dsh BrowserAuth cookie remains in memory so the
   * loopback WebView does not become "unauthenticated" merely because the
   * optional proxy was disabled.  dsh stop/restart still uses {@link #stop()}
   * or {@link #clearDshAuth(long)} and clears that credential immediately.
   */
  public static void stopLanListener() {
    ProxyRun run;
    synchronized (LanProxyService.class) {
      run = activeRun;
      if (run != null) deactivateRunLocked(run);
    }
    if (run != null) {
      closeRunSockets(run);
      log(com.deepseekharness.app.util.UiText.text("LAN 代理已停止"));
    }
  }

  /**
   * Stop only the proxy run belonging to {@code generation}.  A controller
   * clearing an old BrowserAuth handoff uses this overload so a delayed
   * cleanup cannot tear down a newer dsh/LAN generation that has already
   * exchanged its cookie.  Generation {@code 0} retains the unconditional
   * service-shutdown behavior used by lifecycle callbacks.
   */
  public static void stop(long generation) {
    ProxyRun run;
    synchronized (LanProxyService.class) {
      run = activeRun;
      if (generation > 0) {
        if (run == null || run.generation != generation) run = null;
        else deactivateRunLocked(run);
      } else if (run != null) {
        deactivateRunLocked(run);
      }
      // Stopping the listener is also a hard BrowserAuth boundary: an
      // old worker must not be able to resume after dsh comes back.
      if (generation <= 0 || dshAuth.generation == generation) {
        dshAuth = NO_DSH_AUTH;
      }
    }
    if (run != null) closeRunSockets(run);
    if (generation <= 0 || run != null) log(com.deepseekharness.app.util.UiText.text("LAN 代理已停止"));
  }

  public static boolean isRunning() {
    ProxyRun run = activeRun;
    return isActiveRun(run) && run.bound;
  }

  /** Explicit name for callers that need to distinguish bind success from startup in flight. */
  public static boolean isBound() {
    return isRunning();
  }

  /** The port belongs to this exact authenticated Web generation, or is unavailable. */
  public static synchronized int port(long generation) {
    ProxyRun run = activeRun;
    return isActiveRunLocked(run)
            && run.generation == generation
            && hasDshAuth(generation)
            && run.bound
            && run.server != null
            && run.server.isBound()
            && !run.server.isClosed()
        ? run.server.getLocalPort()
        : 0;
  }

  public static synchronized int port() {
    return activeRun == null ? 0 : port(activeRun.generation);
  }

  /** Capture generation, address, port and approval as a single coherent UI snapshot. */
  public static synchronized LanAccessPresentation access(
      boolean enabled, boolean permission, long generation) {
    int port = port(generation);
    LanAddressPolicy.Discovery discovery =
        port == 0
            ? null
            : new LanAddressPolicy.Discovery(
                java.util.List.of(activeRun.network.address()),
                false,
                LanAddressPolicy.Failure.NONE);
    return LanAccessPresentation.capture(
        enabled,
        permission,
        trustedNetwork != null,
        generation,
        hasDshAuth(generation),
        port,
        discovery,
        port == 0 ? "" : activeRun.fingerprint);
  }

  /** Publish only the address the listener actually holds for the requested generation. */
  static synchronized String boundAddress(long generation) {
    return port(generation) > 0 ? activeRun.network.address() : "";
  }

  private static boolean networkCurrent(ProxyRun run) {
    LanNetworkState observed =
        LocalNetworkAccess.granted(run.context) ? LocalNetworkAccess.currentLan(run.context) : null;
    synchronized (LanProxyService.class) {
      if (!isActiveRunLocked(run)) return false;
      return stillTrusted(observed);
    }
  }

  private static boolean isActiveRun(ProxyRun run) {
    return run != null && run.active && activeRun == run;
  }

  private static boolean isActiveRunLocked(ProxyRun run) {
    return run != null && run.active && activeRun == run;
  }

  private static void deactivateRunLocked(ProxyRun run) {
    if (run == null) return;
    if (activeRun == run) activeRun = null;
    run.active = false;
    run.sessions.revoke();
    closeQuietly(run.server);
    run.server = null;
    if (run.acceptThread != null) run.acceptThread.interrupt();
    run.dispatch.close();
  }

  private static void closeRunSockets(ProxyRun run) {
    if (run == null) return;
    run.dispatch.close();
  }

  /** 有限连接的各阶段转交所有权；流式正文不会占满短请求线程。 */
  private static final class LanConnection {
    final SocketDispatch.Ticket owner;
    final ProxyRun run;
    final InputStream in;
    final OutputStream out;
    boolean headOnly;

    LanConnection(SocketDispatch.Ticket owner, ProxyRun run) throws IOException {
      this.owner = owner;
      this.run = run;
      in = new java.io.BufferedInputStream(owner.socket.getInputStream(), 8192);
      out = owner.socket.getOutputStream();
    }

    void next() {
      if (!isActiveRun(run) || owner.ended()) {
        owner.close();
        return;
      }
      long until = HttpProtocol.deadline(HttpProtocol.LAN.timeoutMs);
      if (!run.dispatch.read(owner, () -> read(until, false))) owner.close();
    }

    void fail(int status) {
      fail(status, com.deepseekharness.app.util.LanFailureResponse.forStatus(status));
    }

    void fail(int status, com.deepseekharness.app.util.LanFailureResponse.Reason reason) {
      fail(status, reason, true);
    }

    void fail(
        int status,
        com.deepseekharness.app.util.LanFailureResponse.Reason reason,
        boolean clearCookie) {
      try {
        com.deepseekharness.app.util.LanFailureResponse.write(
            out, status, reason, headOnly, owner.socket.getLocalPort(), clearCookie);
      } catch (IOException ignored) {
      } finally {
        owner.close();
      }
    }

    void read(long until, boolean first) {
      try {
        if (!isActiveRun(run)) {
          owner.close();
          return;
        }
        HttpProtocol.Head request =
            HttpProtocol.readHead(in, owner.socket, HttpProtocol.LAN, until, true);
        if (request == null) {
          owner.close();
          return;
        }
        headOnly = request.method.equals("HEAD");
        boolean websocket = request.websocketRequest();
        if (!LanRequestOrigin.allowed(
            request.values("Host"),
            request.values("Origin"),
            request.values("Sec-Fetch-Site"),
            owner.socket.getLocalAddress().getHostAddress(),
            owner.socket.getLocalPort(),
            websocket)) {
          fail(403);
          return;
        }
        String ip =
            owner.socket.getInetAddress() == null
                ? ""
                : owner.socket.getInetAddress().getHostAddress();
        if (shouldLogConn(ip)) log(com.deepseekharness.app.util.UiText.format("连接来自 %s", ip));
        int port = owner.socket.getLocalPort();
        String token = LanAuth.sessionCookie(request.raw(), port);
        var authorization = request.values("Authorization");
        boolean hasSession = run.sessions.accepts(token);
        var protocols =
            LanWebSocketProtocols.read(request.values("Sec-WebSocket-Protocol"), websocket);
        if (protocols.ticket != null) {
          String path = request.target.split("\\?", 2)[0];
          if (!run.sessions.consumeTicket(protocols.ticket, token, path)) {
            fail(403);
            return;
          }
        } else if (!hasSession || !run.originProof.matches(authorization)) {
          if (!networkCurrent(run)) {
            owner.close();
            return;
          }
          // Bound expensive password checks as well as online guessing per peer.
          if (!request.value("Authorization").isEmpty() && !run.loginThrottle.acquire(ip)) {
            fail(429, com.deepseekharness.app.util.LanFailureResponse.Reason.LOGIN_RATE_LIMIT);
            return;
          }
          if (hasSession
              || websocket
              || !LanAuth.passwordOk(request.raw(), run.passwordVerifier)
              || !run.originProof.remember(authorization)) {
            fail(
                401,
                com.deepseekharness.app.util.LanFailureResponse.Reason.TOKEN_INVALID,
                !hasSession);
            return;
          }
          run.loginThrottle.success(ip);
          try {
            if (networkCurrent(run))
              writeLanRedirect(out, run.sessions.issue(), request.target, port);
          } finally {
            owner.close();
          }
          return;
        }
        if (LanWebSocketProtocols.ENDPOINT.equals(request.target)) {
          issueSocketTicket(request, token);
          return;
        }
        if (run.connections.put(owner, token) == null)
          run.connections.entrySet().removeIf(entry -> entry.getKey().ended());
        AuthSnapshot auth = snapshotDshAuth(run);
        if (auth == null) {
          fail(503, com.deepseekharness.app.util.LanFailureResponse.Reason.BACKEND_AUTH_NOT_READY);
          return;
        }
        Exchange exchange = new Exchange(this, request, auth, websocket, token, protocols);
        boolean stream =
            request.requestBody().streaming()
                || websocket
                || request.value("Accept").toLowerCase(Locale.ROOT).contains("text/event-stream");
        if (stream) {
          if (!run.dispatch.stream(owner, () -> exchange.serve(true))) fail(503);
        } else if (!run.dispatch.request(owner, () -> exchange.serve(false))) fail(503);
      } catch (java.net.SocketTimeoutException timeout) {
        // A keep-alive idle timeout is not a request and must not leave an
        // unsolicited 408 for a browser racing to reuse this connection.
        if (first) fail(408);
        else owner.close();
      } catch (HttpProtocol.Failure invalid) {
        fail(invalid.status);
      } catch (IOException error) {
        owner.close();
      }
    }

    private void issueSocketTicket(HttpProtocol.Head request, String session) throws IOException {
      var paths = request.values("X-Dsha-WebSocket-Path");
      if (!request.method.equals("POST")
          || request.requestBody().kind != HttpProtocol.Kind.NONE
          || request.values("Origin").size() != 1
          || paths.size() != 1
          || !LanWebSocketProtocols.validPath(paths.get(0))) {
        fail(400);
        return;
      }
      if (!networkCurrent(run)) {
        owner.close();
        return;
      }
      String ticket = run.sessions.issueTicket(session, paths.get(0));
      if (ticket == null) {
        fail(503);
        return;
      }
      byte[] body =
          ("{\"protocol\":\"" + LanWebSocketProtocols.PREFIX + ticket + "\"}")
              .getBytes(StandardCharsets.US_ASCII);
      try {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n"
                    + "X-Dsha-Lan-Transport: v1\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
                    + "Content-Length: "
                    + body.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
      } finally {
        owner.close();
      }
    }
  }

  private static final class Exchange {
    final LanConnection client;
    final HttpProtocol.Head request;
    final AuthSnapshot auth;
    final boolean websocket;
    final LanWebSocketProtocols protocols;
    final String session;
    Socket back;
    InputStream bin;
    OutputStream bout;
    boolean responseStarted;

    Exchange(
        LanConnection client,
        HttpProtocol.Head request,
        AuthSnapshot auth,
        boolean websocket,
        String session,
        LanWebSocketProtocols protocols) {
      this.protocols = protocols;
      this.session = session;
      this.client = client;
      this.request = request;
      this.auth = auth;
      this.websocket = websocket;
    }

    void serve(boolean longLane) {
      try {
        if (client.owner.ended() || !networkCurrent(client.run)) {
          client.owner.close();
          return;
        }
        back = new Socket();
        client.owner.attach(back);
        back.setSoTimeout(HttpProtocol.BODY_IDLE_MS);
        client.owner.socket.setSoTimeout(HttpProtocol.BODY_IDLE_MS);
        back.connect(new InetSocketAddress("127.0.0.1", client.run.backendPort), 5000);
        bin = new java.io.BufferedInputStream(back.getInputStream(), 8192);
        bout = back.getOutputStream();
        // 保留已有代次/鉴权写入临界区；逐跳头在注入本轮内部 Cookie 之前处理。
        String forwarded =
            writeCurrentRequest(
                client.run,
                auth,
                session,
                protocols.upstream(request.forwarded(websocket, !websocket)),
                bout);
        if (forwarded == null) {
          client.fail(
              503, com.deepseekharness.app.util.LanFailureResponse.Reason.BACKEND_AUTH_NOT_READY);
          return;
        }
        if (request.value("Expect").equalsIgnoreCase("100-continue")) {
          client.out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          client.out.flush();
        }
        HttpProtocol.copyBody(request.requestBody(), client.in, bout);
        long until = HttpProtocol.deadline(HttpProtocol.BODY_IDLE_MS);
        HttpProtocol.Head response;
        int interim = 0;
        while (true) {
          response = HttpProtocol.readHead(bin, back, HttpProtocol.LAN, until, false);
          if (response == null) throw new IOException("HTTP_BACKEND_EOF");
          if (response.status >= 200 || response.status == 101) break;
          if (++interim > 8) throw new IOException("HTTP_TOO_MANY_INFORMATIONAL");
          response.responseBody(request);
          client.out.write(
              rewriteResponse(response.forwarded(false, false), client.run.backendPort)
                  .getBytes(StandardCharsets.ISO_8859_1));
          client.out.flush();
        }
        HttpProtocol.Body body = response.responseBody(request);
        boolean eventStream =
            response.value("Content-Type").toLowerCase(Locale.ROOT).startsWith("text/event-stream")
                && body.kind != HttpProtocol.Kind.NONE
                && body.kind != HttpProtocol.Kind.UPGRADE;
        // A route-level denial is not proof that BrowserAuth itself expired.
        // Keep the backend's denial intact; every subsequent request still has
        // to pass the current run and backend's own authentication checks.
        boolean persistent =
            !eventStream
                && !request.close()
                && body.kind != HttpProtocol.Kind.CLOSE
                && body.kind != HttpProtocol.Kind.UPGRADE;
        String head =
            protocols.downstream(
                response,
                rewriteResponse(
                    response.forwarded(body.kind == HttpProtocol.Kind.UPGRADE, !persistent),
                    client.run.backendPort));
        if (!longLane && (body.streaming() || eventStream)) {
          Runnable transfer = () -> transfer(body, head, persistent, eventStream);
          if (!eventStream && LanFiniteResponse.eligible(body, response.value("Content-Type"))) {
            if (client.run.dispatch.tryStream(client.owner, transfer)) return;
            long deadline = HttpProtocol.deadline(LanFiniteResponse.TOTAL_MS);
            client.run.finiteDeadlines.put(client.owner, deadline);
            if (!client.run.dispatch.finite(
                client.owner, () -> finite(body, head, persistent, deadline))) {
              client.run.finiteDeadlines.remove(client.owner, deadline);
              client.fail(503);
            }
          } else if (!client.run.dispatch.stream(client.owner, transfer)) client.fail(503);
          return;
        }
        transfer(body, head, persistent, eventStream);
      } catch (HttpProtocol.Failure invalid) {
        if (!responseStarted) client.fail(invalid.status == 400 ? 400 : 502);
        else client.owner.close();
      } catch (IOException error) {
        if (!responseStarted) client.fail(502);
        else client.owner.close();
      }
    }

    private void finite(HttpProtocol.Body body, String head, boolean persistent, long deadline) {
      try {
        var prefix = new LanFiniteResponse();
        if (!prefix.complete(body, bin, back)) {
          bin = prefix.replay(bin);
          if (!client.run.dispatch.stream(
              client.owner, () -> transfer(body, head, persistent, false))) client.fail(503);
          return;
        }
        if (!isActiveRun(client.run) || client.owner.ended()) return;
        responseStarted = true;
        client.out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        prefix.writeTo(client.out);
        client.out.flush();
        client.owner.detach(back);
        if (persistent) client.next();
        else client.owner.close();
      } catch (IOException error) {
        if (!responseStarted) client.fail(502);
        else client.owner.close();
      } finally {
        client.run.finiteDeadlines.remove(client.owner, deadline);
      }
    }

    void transfer(HttpProtocol.Body body, String head, boolean persistent, boolean eventStream) {
      try {
        if (!isActiveRun(client.run) || client.owner.ended()) {
          client.owner.close();
          return;
        }
        back.setSoTimeout(eventStream ? 0 : HttpProtocol.BODY_IDLE_MS);
        if (eventStream) {
          // An SSE client sends no more HTTP data on its dedicated connection. A browser reload
          // closes the client while the backend may be waiting indefinitely to produce an event.
          client.owner.socket.setSoTimeout(0);
          if (!client.run.dispatch.read(
              client.owner,
              () -> {
                try {
                  client.in.read();
                } catch (IOException closed) {
                } finally {
                  client.owner.close();
                }
              })) {
            client.fail(503);
            return;
          }
        }
        responseStarted = true;
        client.out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        client.out.flush();
        if (body.kind == HttpProtocol.Kind.UPGRADE) {
          tunnel();
          return;
        }
        HttpProtocol.copyBody(body, bin, client.out);
        client.owner.detach(back);
        if (persistent) client.next();
        else client.owner.close();
      } catch (IOException failure) {
        client.owner.close();
      }
    }

    void tunnel() throws IOException {
      client.owner.socket.setSoTimeout(0);
      back.setSoTimeout(0);
      client.owner.socket.setKeepAlive(true);
      back.setKeepAlive(true);
      java.util.concurrent.Future<?> up =
          client.run.dispatch.pump(
              client.owner,
              () -> {
                try {
                  HttpProtocol.copyToEnd(client.in, bout);
                  back.shutdownOutput();
                } catch (IOException failure) {
                  client.owner.close();
                }
              });
      try {
        HttpProtocol.copyToEnd(bin, client.out);
        client.owner.socket.shutdownOutput();
        try {
          up.get(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ignored) {
        } catch (java.util.concurrent.ExecutionException failure) {
          client.owner.close();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      } finally {
        client.owner.close();
        up.cancel(true);
      }
    }
  }

  /** 仅返回计数，不包含请求、地址或鉴权资料。 */
  public static java.util.Map<String, Long> resourceMetrics() {
    ProxyRun run = activeRun;
    return run == null ? java.util.Collections.emptyMap() : run.dispatch.metrics();
  }

  /**
   * Write an authenticated request only if this handler still owns the
   * current listener and BrowserAuth snapshot.  Returning null is a
   * fail-closed state transition, never a signal to forward without cookie.
   */
  private static String writeCurrentRequest(
      ProxyRun run, AuthSnapshot snapshot, String session, String head, OutputStream out)
      throws IOException {
    synchronized (LanProxyService.class) {
      if (!isCurrentDshAuth(run, snapshot) || !run.sessions.accepts(session)) return null;
      String forwarded = rewriteRequest(head, run.backendPort, snapshot.state.cookie);
      if (forwarded.isEmpty()) return null;
      out.write(forwarded.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      return forwarded;
    }
  }

  private static void writeLanRedirect(OutputStream out, String token, String target, int port)
      throws IOException {
    String response =
        "HTTP/1.1 303 See Other\r\n"
            + "Location: "
            + LanAuth.cleanRedirectTarget(target)
            + "\r\nCache-Control: no-store\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "Set-Cookie: "
            + LanTokenState.cookieName(port)
            + "="
            + token
            + "; Path=/; Max-Age="
            + LanTokenState.MAX_AGE_SECONDS
            + "; Secure; HttpOnly; SameSite=Strict\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n";
    out.write(response.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
  }

  /** Rewrite all browser-controlled authority/cookie fields for loopback. */
  static String rewriteRequest(String head, int backend, String dshCookie) {
    // This helper is intentionally fail-closed too.  The handler already
    // has a generation-bound snapshot, but no caller should ever be able
    // to turn an absent/invalid BrowserAuth cookie into a raw backend
    // request by using the rewrite helper directly.
    if (head == null || !isValidDshCookie(dshCookie)) return "";
    StringBuilder result = new StringBuilder();
    boolean first = true;
    boolean host = false;
    boolean origin = false;
    boolean secFetchSite = false;
    boolean cookieAdded = false;
    for (String line : head.split("\\r?\\n", -1)) {
      if (line.isEmpty()) {
        // Browser navigation requests may omit these fields. Add their
        // loopback equivalents so dsh never sees an ambiguous origin.
        if (!host) {
          result.append("Host: 127.0.0.1:").append(backend).append("\r\n");
          host = true;
        }
        if (!origin) {
          result.append("Origin: http://127.0.0.1:").append(backend).append("\r\n");
          origin = true;
        }
        if (!secFetchSite) {
          result.append("Sec-Fetch-Site: same-origin\r\n");
          secFetchSite = true;
        }
        if (!cookieAdded && isValidDshCookie(dshCookie)) {
          result.append("Cookie: ").append(dshCookie).append("\r\n");
          cookieAdded = true;
        }
        result.append("\r\n");
        continue;
      }
      if (first) {
        result.append(LanAuth.stripTokenFromRequestLine(line)).append("\r\n");
        first = false;
        continue;
      }
      int colon = line.indexOf(':');
      String key = colon > 0 ? line.substring(0, colon).trim() : "";
      String lower = key.toLowerCase(Locale.ROOT);
      if (lower.equals("host")) {
        result.append("Host: 127.0.0.1:").append(backend).append("\r\n");
        host = true;
      } else if (lower.equals("origin")) {
        result.append("Origin: http://127.0.0.1:").append(backend).append("\r\n");
        origin = true;
      } else if (lower.equals("sec-fetch-site")) {
        result.append("Sec-Fetch-Site: same-origin\r\n");
        secFetchSite = true;
      } else if (lower.equals("referer")
          || lower.equals("cookie")
          || lower.equals("authorization")
          || (lower.contains("dsha") && lower.contains("token"))) {
        // Never pass a LAN credential, a stale external cookie, or a
        // similarly named compatibility header to dsh.
      } else {
        result.append(line).append("\r\n");
      }
    }
    if (!host) {
      // Host belongs after the request line.  Inserting at offset zero
      // would turn it into the first line and make the request invalid.
      int firstBreak = result.indexOf("\r\n");
      String hostLine = "Host: 127.0.0.1:" + backend + "\r\n";
      if (firstBreak >= 0) result.insert(firstBreak + 2, hostLine);
      else result.insert(0, hostLine);
    }
    if (!cookieAdded && isValidDshCookie(dshCookie)) {
      int end = result.lastIndexOf("\r\n\r\n");
      if (end >= 0) result.insert(end, "Cookie: " + dshCookie + "\r\n");
    }
    // 归一化结尾（与 rewriteResponse 相同）：split(-1) 会给请求头后多留一个 \r\n，
    // 对 GET 无影响，但对 POST 会把正文顶错位（Content-Length 少算 2 字节）→ dsh 400。
    // 恰好保留一个空行分隔，正文从正确偏移开始。
    String out = result.toString();
    while (out.endsWith("\r\n")) out = out.substring(0, out.length() - 2);
    return out + "\r\n\r\n";
  }

  /** Drop backend cookies and rewrite redirects so dsh credentials stay internal. */
  static String rewriteResponse(String head) {
    return rewriteResponse(head, backendPort);
  }

  private static String rewriteResponse(String head, int responseBackendPort) {
    StringBuilder result = new StringBuilder();
    // 注意：split 默认限位会把「头与正文之间的空行」这个末尾空串也删掉（少了 \r\n\r\n），
    // 而 -1 又会多留一个空串（多了 \r\n）。两者都会让客户端解析失败 ——
    // 这里用 -1 处理后统一归一化结尾：剥掉多余 CRLF，恰好保留一个空行分隔。
    for (String line : head.split("\\r?\\n", -1)) {
      if (line.isEmpty()) {
        result.append("\r\n");
        continue;
      }
      int colon = line.indexOf(':');
      String key = colon > 0 ? line.substring(0, colon).trim() : "";
      if (key.equalsIgnoreCase("Set-Cookie")
          || key.equalsIgnoreCase("Set-Cookie2")
          || key.equalsIgnoreCase("Authorization")
          || key.equalsIgnoreCase("Proxy-Authorization")
          || key.equalsIgnoreCase("X-Dsha-Token")
          || key.equalsIgnoreCase("X-Token")
          || key.toLowerCase(Locale.ROOT).startsWith("dsh-auth-")) continue;
      // The LAN endpoint is a same-origin proxy.  Do not expose a
      // permissive backend CORS policy (especially "*") to a browser;
      // keeping it would invite callers to treat the proxy as a
      // cross-origin API and would be misleading once credentials are
      // involved.
      if (key.equalsIgnoreCase("Access-Control-Allow-Origin")
          || key.equalsIgnoreCase("Access-Control-Allow-Credentials")
          || key.equalsIgnoreCase("Access-Control-Allow-Headers")
          || key.equalsIgnoreCase("Access-Control-Allow-Methods")) continue;
      if (key.equalsIgnoreCase("Location")) {
        String value = line.substring(colon + 1).trim();
        result
            .append("Location: ")
            .append(LanAuth.browserLocation(value, responseBackendPort))
            .append("\r\n");
      } else {
        result.append(line).append("\r\n");
      }
    }
    String out = result.toString();
    // 归一化结尾：多余的 CRLF 会让 chunked 正文首行解析成空十六进制长度
    while (out.endsWith("\r\n")) out = out.substring(0, out.length() - 2);
    return out + "\r\n\r\n";
  }

  private static boolean shouldLogConn(String ip) {
    long now = System.currentTimeMillis();
    Long previous = lastConnLog.get(ip);
    if (previous != null && now - previous < 60000) return false;
    if (lastConnLog.size() > 256) lastConnLog.clear();
    lastConnLog.put(ip, now);
    return true;
  }

  private static void log(String message) {
    // The same line is sent to logcat and the persistent diagnostic file.
    // Sanitize once at this boundary so future call sites cannot leak a
    // BrowserAuth/LAN credential through either sink.
    message = SensitiveData.redact(message);
    Log.i(TAG, message);
    if (logPath.isEmpty()) return;
    synchronized (LOG_LOCK) {
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(logPath, true)) {
        out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
      } catch (Throwable ignored) {
      }
    }
  }

  private static void closeQuietly(Socket socket) {
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
    }
  }

  private static void closeQuietly(ServerSocket socket) {
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
    }
  }
}
