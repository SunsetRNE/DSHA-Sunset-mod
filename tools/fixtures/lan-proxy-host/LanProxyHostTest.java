package com.deepseekharness.app;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.HttpProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.After;
import org.junit.Test;

/** Host sockets execute the actual proxy. All credentials are generated fixture values. */
public class LanProxyHostTest {
  static final String COOKIE = "dsh-auth-fixture=v1.host.signature";
  static final String PASSWORD = "fixture密码123";
  static final String VERIFIER = com.deepseekharness.app.util.LanPassword.create(PASSWORD);
  static String name(int port) { return com.deepseekharness.app.util.LanTokenState.cookieName(port); }
  static String auth() { return "Authorization: " + basic(PASSWORD) + "\r\n"; }
  static android.content.Context context() { return new android.content.Context(VERIFIER); }
  static final Map<Integer, javax.net.ssl.SSLSocketFactory> clients = new ConcurrentHashMap<>();

  static void trust(int port, java.security.cert.X509Certificate localLeaf) throws Exception {
    var anchors = java.security.KeyStore.getInstance("PKCS12");
    anchors.load(null);
    anchors.setCertificateEntry("fixture", localLeaf);
    var managers = javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
    managers.init(anchors);
    var tls = javax.net.ssl.SSLContext.getInstance("TLS");
    tls.init(null, managers.getTrustManagers(), null);
    clients.put(port, tls.getSocketFactory());
  }

  static javax.net.ssl.SSLSocket connect(int port) throws IOException {
    var socket = (javax.net.ssl.SSLSocket) clients.get(port).createSocket("127.0.0.1", port);
    var parameters = socket.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    socket.setSSLParameters(parameters);
    socket.setSoTimeout(4000);
    return socket;
  }

  static final class Backend implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    final String label;
    final BlockingQueue<HttpProtocol.Head> requests = new LinkedBlockingQueue<>();
    final java.util.concurrent.atomic.AtomicInteger activeEvents = new java.util.concurrent.atomic.AtomicInteger();
    final CountDownLatch cancelledDownload = new CountDownLatch(1);
    final ExecutorService workers = Executors.newCachedThreadPool(r -> {
      Thread thread = new Thread(r, "lan-fixture-backend");
      thread.setDaemon(true);
      return thread;
    });

    Backend(String label) throws IOException {
      this.label = label;
      workers.execute(() -> {
        while (!server.isClosed()) {
          try {
            Socket socket = server.accept();
            workers.execute(() -> serve(socket));
          } catch (IOException closed) { break; }
        }
      });
    }

    void serve(Socket socket) {
      try (socket) {
        HttpProtocol.Head request = HttpProtocol.readHead(socket.getInputStream(), socket,
            HttpProtocol.LAN, HttpProtocol.deadline(3000), true);
        if (request == null) return;
        requests.add(request);
        assertEquals(COOKIE, request.value("Cookie"));
        if (request.target.equals("/events")) {
          activeEvents.incrementAndGet();
          try {
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                +"Transfer-Encoding: chunked\r\n\r\n3\r\n:\n\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.getInputStream().read(); // No server heartbeat is needed for browser-side cancellation.
          } finally { activeEvents.decrementAndGet(); }
          return;
        }
        if(request.target.equals("/vendor.js") || request.target.equals("/small-api")) {
          String type=request.target.equals("/vendor.js")?"text/javascript":"application/json";
          socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nTransfer-Encoding: chunked\r\n"
              +"\r\n2\r\n42\r\n0\r\nX-Fixture: end\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          return;
        }
        if(request.target.equals("/download")) {
          int size=3*1024*1024;
          socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+size+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          byte[] block=new byte[8192]; java.util.Arrays.fill(block,(byte)'a');
          for(int n=0;n<size;n+=block.length) socket.getOutputStream().write(block);
          return;
        }
        if(request.target.equals("/download-cancel")) {
          int size=16*1024*1024;
          socket.setSendBufferSize(8192);
          socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+size+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          byte[] block=new byte[8192];
          try {
            for(int n=0;n<size;n+=block.length){socket.getOutputStream().write(block);Thread.sleep(1);}
          } catch(IOException disconnected) {cancelledDownload.countDown();}
          catch(InterruptedException stopped) {Thread.currentThread().interrupt();}
          return;
        }
        if (request.target.equals("/upgrade") || request.target.equals("/api/remote.mux") || request.target.equals("/protocol")) {
          String accept = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest(
              (request.value("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
          String negotiated=request.target.equals("/protocol") ? "Sec-WebSocket-Protocol: app.echo\r\n" : "";
          socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"+negotiated+"Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          socket.getOutputStream().flush();
          var in=socket.getInputStream();
          int type=in.read(), len=in.read();
          if(type < 0 || len < 0 || (len & 127) > 125) return;
          byte[] mask = (len & 128)!=0 ? in.readNBytes(4) : null;
          byte[] message = in.readNBytes(len & 127);
          if(mask!=null) for(int i=0;i<message.length;i++) message[i]^=mask[i%4];
          socket.getOutputStream().write(type);
          socket.getOutputStream().write(message.length);
          socket.getOutputStream().write(message);
          socket.getOutputStream().flush();
          socket.getInputStream().read();
          return;
        }
        if (request.target.equals("/invalid-upgrade")) {
          socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: invalid\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          return;
        }
        if (request.target.equals("/chunked")) {
          socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: X-Fixture\r\nConnection: close\r\n\r\n3\r\nabc\r\n0\r\nX-Fixture: kept\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          return;
        }
        int status = request.target.equals("/forbidden") ? 403 : request.target.equals("/unauthorized") ? 401 : 200;
        byte[] body = label.getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\nContent-Length: "
            + body.length + "\r\nSet-Cookie: dsh-auth-fixture=backend-secret; HttpOnly\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
      } catch (IOException ignored) { }
      catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public void close() throws IOException {
      server.close();
      workers.shutdownNow();
    }
  }

  @After public void stop() { LanProxyService.stop(); }

  @Test public void readingBoundAddressRequiresTheCurrentGenerationAndCannotReviveIt()
      throws Exception {
    LanProxyService.stop();
    assertEquals("", LanProxyService.boundAddress(29));
    try (Backend backend = new Backend("credential-read")) {
      start(backend, 29);
      String current = LanProxyService.boundAddress(29);
      assertFalse(current.isEmpty());
      assertEquals("", LanProxyService.boundAddress(28));
      LanProxyService.stopLanListener();
      assertEquals("", LanProxyService.boundAddress(29));
      assertEquals("", LanProxyService.boundAddress(29));
      assertFalse(LanProxyService.isBound());
      assertTrue("disabling LAN retains local backend authentication", LanProxyService.hasDshAuth(29));
    }
  }

  static void start(Backend backend, long generation) throws Exception {
    assertTrue(LanProxyService.setDshAuthCookie(COOKIE, generation));
    android.content.Context app = context();
    assertTrue(LanProxyService.trustNetwork(app, com.deepseekharness.app.bridge.LocalNetworkAccess.currentLan(app)));
    LanProxyService.start(null, app, backend.server.getLocalPort(), generation);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!LanProxyService.isBound() && System.nanoTime() < until) Thread.sleep(10);
    assertTrue("fixture LAN listener did not bind", LanProxyService.isBound());
    trust(LanProxyService.port(), AndroidLanIdentity.leaf());
  }

  static byte[] request(String target, String token, boolean queryCredential) throws Exception {
    return requestAt(LanProxyService.port(), target, token, queryCredential);
  }

  static byte[] requestAt(int port, String target, String token, boolean queryCredential) throws Exception {
    try (Socket socket = connect(port)) {
      socket.setSoTimeout(4000);
      String header = "GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n"
          + (queryCredential ? "" : auth() + "Cookie: " + name(port) + "=" + token + "\r\n") + "\r\n";
      socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
      return socket.getInputStream().readAllBytes();
    }
  }

  static String response(String target, String token) throws Exception {
    return new String(request(target, token, false), StandardCharsets.UTF_8);
  }

  static String basic(String password) {
    return "Basic " + Base64.getEncoder().encodeToString(("dsha:" + password).getBytes(StandardCharsets.UTF_8));
  }

  static String raw(String method, String target, String headers) throws Exception {
    return rawHost(method, target, "127.0.0.1:" + LanProxyService.port(), headers);
  }

  static String rawHost(String method, String target, String host, String headers) throws Exception {
    return rawAt(LanProxyService.port(), method, target, host, headers);
  }

  static String rawAt(int port, String method, String target, String host, String headers) throws Exception {
    try (Socket socket = connect(port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: " + host
          + "\r\nConnection: close\r\n" + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static String login(String password) throws Exception {
    return raw("GET", "/", "Authorization: " + basic(password) + "\r\n");
  }

  static String sessionAt(int port) throws Exception {
    return cookieFrom(rawAt(port, "GET", "/", "127.0.0.1:" + port, "Authorization: " + basic(PASSWORD) + "\r\n"))
        .substring(name(port).length()+1);
  }

  static String cookieFrom(String reply) {
    for (String line : reply.split("\r\n"))
      if (line.startsWith("Set-Cookie: __Host-dsha_lan_")) return line.substring("Set-Cookie: ".length()).split(";", 2)[0];
    throw new AssertionError("missing fixture LAN session cookie");
  }

  @Test public void tls12And13ServeTheExactLocalFingerprintAndIpSan() throws Exception {
    try (Backend backend = new Backend("tls")) {
      start(backend, 41);
      var identity = AndroidLanIdentity.load("127.0.0.1");
      var local = AndroidLanIdentity.leaf();
      local.verify(local.getPublicKey());
      assertEquals(-1, local.getBasicConstraints());
      assertEquals(List.of("1.3.6.1.5.5.7.3.1"), local.getExtendedKeyUsage());
      assertTrue(LanTlsIdentity.validFor(local, "127.0.0.1", System.currentTimeMillis()));
      assertFalse(LanTlsIdentity.validFor(local, "127.0.0.2", System.currentTimeMillis()));
      assertFalse(LanTlsIdentity.validFor(local, "127.0.0.1", local.getNotAfter().getTime()));
      for (String protocol : List.of("TLSv1.2", "TLSv1.3")) {
        try (var socket = connect(LanProxyService.port())) {
          socket.setEnabledProtocols(new String[] {protocol});
          socket.startHandshake();
          var peer = socket.getSession().getPeerCertificates()[0];
          assertEquals(protocol, socket.getSession().getProtocol());
          assertArrayEquals(local.getEncoded(), peer.getEncoded());
          assertEquals(identity.fingerprint, java.util.HexFormat.ofDelimiter(":").withUpperCase()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(peer.getEncoded())));
        }
      }
    }
  }

  @Test public void aPlainNavigationNeverGetsAnHttpResponseOrReachesTheBackend() throws Exception {
    try (Backend backend = new Backend("tls-only")) {
      start(backend, 42);
      try (Socket cleartext = new Socket("127.0.0.1", LanProxyService.port())) {
        cleartext.setSoTimeout(3000);
        cleartext.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        byte[] reply;
        try { reply = cleartext.getInputStream().readAllBytes(); }
        catch (SocketException closed) { reply = new byte[0]; }
        assertFalse(new String(reply, StandardCharsets.ISO_8859_1).contains("HTTP/"));
      }
      assertNull(backend.requests.poll(100, TimeUnit.MILLISECONDS));
      assertTrue(login(PASSWORD).startsWith("HTTP/1.1 303"));
    }
  }

  @Test public void replacingTheObservedNetworkRetiresTlsAndRequiresANewForegroundApproval() throws Exception {
    try (Backend backend = new Backend("replacement")) {
      start(backend, 43);
      var app = context();
      var previous = com.deepseekharness.app.bridge.LocalNetworkAccess.currentLan(app);
      String oldCookie = cookieFrom(login(PASSWORD));
      com.deepseekharness.app.bridge.LocalNetworkAccess.handle++;
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (LanProxyService.isBound() && System.nanoTime() < until) Thread.sleep(20);
      assertFalse(LanProxyService.isBound());
      assertTrue(LanProxyService.hasDshAuth(43));
      assertFalse(LanProxyService.trustNetwork(app, previous));
      LanProxyService.start(null, app, backend.server.getLocalPort(), 43);
      assertEquals(0, LanProxyService.port(43));
      start(backend, 43);
      assertTrue(raw("GET", "/", "Cookie: " + oldCookie + "\r\n").startsWith("HTTP/1.1 401"));
    }
  }

  @Test public void passwordLoginAndSessionKeepCredentialsOutOfBackend() throws Exception {
    try (Backend backend = new Backend("authenticated")) {
      start(backend, 30);
      String denied = raw("GET", "/", "");
      assertTrue(denied.startsWith("HTTP/1.1 401"));
      assertTrue(denied.contains("WWW-Authenticate: Basic"));
      String reply = login(PASSWORD);
      assertTrue(reply.startsWith("HTTP/1.1 303"));
      assertTrue(reply.contains("Secure; HttpOnly; SameSite=Strict"));
      assertFalse(reply.contains(PASSWORD));
      assertFalse(reply.contains("Location: /?token="));
      String session = cookieFrom(reply);
      String forwarded = raw("GET", "/", "Cookie: " + session + "; external=private\r\nAuthorization: " + basic(PASSWORD) + "\r\n");
      assertTrue(forwarded.endsWith("authenticated"));
      HttpProtocol.Head request = backend.requests.poll(2, TimeUnit.SECONDS);
      assertNotNull(request);
      assertEquals(COOKIE, request.value("Cookie"));
      assertTrue(request.value("Authorization").isEmpty());
      assertFalse(forwarded.contains(COOKIE));
      assertTrue(login("wrong-password").startsWith("HTTP/1.1 401"));
      assertTrue(raw("POST", "/", "Authorization: " + basic(PASSWORD) + "\r\nContent-Length: 0\r\n").startsWith("HTTP/1.1 401"));
    }
  }

  @Test public void aCurrentSessionRequiresBasicForItsOriginAndOnlyItsCookieIsCleared() throws Exception {
    try (Backend backend = new Backend("both-present")) {
      start(backend, 45);
      int port = LanProxyService.port();
      String session = cookieFrom(login(PASSWORD));
      String sibling = name(port == 3081 ? 3082 : 3081);
      String cookies = "Cookie: " + session + "; " + sibling + "=synthetic-other\r\n";
      for (String credential : List.of("", "Authorization: " + basic("other-password") + "\r\n")) {
        String denied = raw("GET", "/", cookies + credential);
        assertTrue(denied.startsWith("HTTP/1.1 401"));
        assertFalse("the browser needs the current cookie when it retries the Basic challenge",denied.contains("Set-Cookie:"));
      }
      String stale=raw("GET","/","Cookie: "+name(port)+"=expired; "+sibling+"=other\r\n");
      assertTrue(stale.contains("Set-Cookie: " + name(port) + "=; Path=/; Max-Age=0; Secure;"));
      assertFalse(stale.contains("Set-Cookie: " + sibling + "="));
      assertTrue(raw("GET", "/upgrade", cookies + "Origin: https://127.0.0.1:" + port
          + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
          + "Sec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n").startsWith("HTTP/1.1 401"));
      assertTrue(backend.requests.isEmpty());
      assertTrue(raw("GET", "/", cookies + auth()).endsWith("both-present"));
      var forwarded = backend.requests.poll(1, TimeUnit.SECONDS);
      assertNotNull(forwarded);
      assertEquals("",forwarded.value("Authorization"));
      assertEquals(COOKIE,forwarded.value("Cookie"));
    }
  }

  @Test public void eightOpenEventStreamsLeaveChunkedAssetsAndApiUsableAndCancelWithoutHeartbeat() throws Exception {
    var clients=new ArrayList<Socket>();
    try(Backend backend=new Backend("page")){
      start(backend,48);int port=LanProxyService.port();String session=cookieFrom(login(PASSWORD));
      try {
        for(int i=0;i<8;i++){
          Socket socket=connect(port);clients.add(socket);
          socket.getOutputStream().write(("GET /events HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n"+auth()
              +"Cookie: "+session+"\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          var head=HttpProtocol.readHead(socket.getInputStream(),socket,HttpProtocol.LAN,HttpProtocol.deadline(4000),false);
          assertEquals(200,head.status);
        }
        assertEquals(8,backend.activeEvents.get());
        for(String path:List.of("/vendor.js","/small-api")){
          String response=raw("GET",path,auth()+"Cookie: "+session+"\r\n");
          assertTrue("short chunked resource "+path+" should return 200",response.startsWith("HTTP/1.1 200"));
          assertTrue(response.endsWith("2\r\n42\r\n0\r\nX-Fixture: end\r\n\r\n"));
        }
      } finally {for(Socket socket:clients) socket.close();}
      long start=System.nanoTime(),until=start+TimeUnit.SECONDS.toNanos(3);
      while(backend.activeEvents.get()!=0 && System.nanoTime()<until)Thread.sleep(10);
      System.out.println("SSE cancellation 8 -> "+backend.activeEvents.get()+"; elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
      assertEquals(0,backend.activeEvents.get());
      assertTrue(LanProxyService.resourceMetrics().get("completedFinite")>=2);
      String downloaded=raw("GET","/download",auth()+"Cookie: "+session+"\r\n");
      assertTrue(downloaded.startsWith("HTTP/1.1 200"));
      int startBody=downloaded.indexOf("\r\n\r\n")+4;
      assertEquals(3*1024*1024,downloaded.length()-startBody);
      assertTrue(downloaded.substring(startBody).chars().allMatch(ch -> ch=='a'));
    }
  }

  @Test public void cancellingALargeDownloadClosesItsBackendAndLeavesSmallRequestsUsable() throws Exception {
    try(Backend backend=new Backend("after-cancel")){
      start(backend,49);int port=LanProxyService.port();String session=cookieFrom(login(PASSWORD));
      try(var socket=connect(port)){
        socket.getOutputStream().write(("GET /download-cancel HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n"+auth()
            +"Cookie: "+session+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        var head=HttpProtocol.readHead(socket.getInputStream(),socket,HttpProtocol.LAN,HttpProtocol.deadline(3000),false);
        assertEquals(200,head.status);
        assertEquals("16777216",head.value("Content-Length"));
        assertEquals(8192,socket.getInputStream().readNBytes(8192).length);
      }
      assertTrue(backend.cancelledDownload.await(3,TimeUnit.SECONDS));
      assertTrue(raw("GET","/",auth()+"Cookie: "+session+"\r\n").endsWith("after-cancel"));
    }
  }

  static String issueProtocol(String session, String path) throws Exception {
    String result=raw("POST",LanWebSocketProtocols.ENDPOINT,auth()+"Cookie: "+session
        +"\r\nOrigin: https://127.0.0.1:"+LanProxyService.port()+"\r\nX-Dsha-WebSocket-Path: "+path+"\r\nContent-Length: 0\r\n");
    assertTrue(result.startsWith("HTTP/1.1 200"));
    assertTrue(result.contains("X-Dsha-Lan-Transport: v1"));
    assertTrue(result.contains("Cache-Control: no-store"));
    var match=java.util.regex.Pattern.compile("\\{\"protocol\":\"(dsha-lan-ticket-v1\\.[A-Za-z0-9_-]{43})\"\\}").matcher(result);
    assertTrue(match.find());
    return match.group(1);
  }

  static HttpProtocol.Head upgrade(String session,String target,String offered) throws Exception {
    int port=LanProxyService.port();
    try(var socket=connect(port)){
      socket.getOutputStream().write(("GET "+target+" HTTP/1.1\r\nHost: 127.0.0.1:"+port
          +"\r\nCookie: "+session+"\r\nOrigin: https://127.0.0.1:"+port+"\r\nConnection: Upgrade\r\nUpgrade: websocket"
          +"\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\nSec-WebSocket-Protocol: "+offered+"\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      return HttpProtocol.readHead(socket.getInputStream(),socket,HttpProtocol.LAN,HttpProtocol.deadline(4000),false);
    }
  }

  @Test public void ticketWorksWithoutWebsocketBasicAndIsNeverForwarded() throws Exception {
    try(Backend backend=new Backend("ticket")){
      start(backend,46);
      String session=cookieFrom(login(PASSWORD));
      String ticket=issueProtocol(session,"/api/remote.mux");
      String offered=LanWebSocketProtocols.EMPTY+", "+ticket;
      var head=upgrade(session,"/api/remote.mux",offered);
      assertEquals(101,head.status);
      assertEquals(LanWebSocketProtocols.EMPTY,head.value("Sec-WebSocket-Protocol"));
      assertFalse(head.raw().contains(ticket));
      var back=backend.requests.poll(1,TimeUnit.SECONDS);
      assertNotNull(back);
      assertTrue(back.value("Sec-WebSocket-Protocol").isEmpty());
      assertFalse(back.raw().contains(ticket));
      assertEquals(403,upgrade(session,"/api/remote.mux",offered).status);
      String next=issueProtocol(session,"/protocol");
      var original=upgrade(session,"/protocol","app.other, app.echo, "+next);
      assertEquals(101,original.status);
      assertEquals("app.echo",original.value("Sec-WebSocket-Protocol"));
      back=backend.requests.poll(1,TimeUnit.SECONDS);
      assertEquals("app.other, app.echo",back.value("Sec-WebSocket-Protocol"));
      String diagnostics=com.deepseekharness.app.util.SensitiveData.redact("protocol "+next+"\nSec-WebSocket-Protocol: "+offered);
      assertFalse(diagnostics.contains(next));
      assertFalse(diagnostics.contains(ticket));
    }
  }

  @Test public void mintRequiresSameOriginPostAndBothLoginCredentials() throws Exception {
    try(Backend backend=new Backend("no-backend")){
      start(backend,47);
      String session=cookieFrom(login(PASSWORD)), path=LanWebSocketProtocols.ENDPOINT;
      String fields="Cookie: "+session+"\r\nX-Dsha-WebSocket-Path: /api/remote.mux\r\nContent-Length: 0\r\n";
      String same="Origin: https://127.0.0.1:"+LanProxyService.port()+"\r\n";
      assertTrue(raw("POST",path,fields+same).startsWith("HTTP/1.1 401"));
      assertTrue(raw("POST",path,fields+auth()).startsWith("HTTP/1.1 400"));
      assertTrue(raw("GET",path,fields+same+auth()).startsWith("HTTP/1.1 400"));
      assertTrue(raw("POST",path,fields+"Origin: https://example.invalid\r\n"+auth()).startsWith("HTTP/1.1 403"));
      assertTrue(raw("POST",path,fields.replace("/api/remote.mux","//different")+same+auth()).startsWith("HTTP/1.1 400"));
      assertTrue(backend.requests.isEmpty());
    }
  }

  @Test public void passwordAndSessionAreRevokedWhenListenerRestarts() throws Exception {
    try (Backend backend = new Backend("rotated")) {
      start(backend, 31);
      String oldCookie = cookieFrom(login(PASSWORD));
      LanProxyService.stopLanListener();
      String replacement = "replacement密码456";
      LanProxyService.start(null, new android.content.Context(com.deepseekharness.app.util.LanPassword.create(replacement)), backend.server.getLocalPort(), 31);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!LanProxyService.isBound() && System.nanoTime() < until) Thread.sleep(10);
      assertTrue(LanProxyService.isBound());
      trust(LanProxyService.port(), AndroidLanIdentity.leaf());
      assertTrue(raw("GET", "/", "Cookie: " + oldCookie + "\r\n").startsWith("HTTP/1.1 401"));
      assertTrue(login(PASSWORD).startsWith("HTTP/1.1 401"));
      assertTrue(login(replacement).startsWith("HTTP/1.1 303"));
    }
  }

  @Test public void crossOriginCannotUseAnAuthenticatedSession() throws Exception {
    try (Backend backend = new Backend("cross-origin")) {
      start(backend, 32);
      String session = cookieFrom(login(PASSWORD));
      String reply = raw("GET", "/", "Cookie: " + session + "\r\nOrigin: https://attacker.example\r\nSec-Fetch-Site: cross-site\r\n");
      assertFalse("cross-origin request must not reach backend", reply.startsWith("HTTP/1.1 200"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void sameOriginSessionRequestsRemainUsable() throws Exception {
    try (Backend backend = new Backend("same-origin")) {
      start(backend, 33);
      String session = cookieFrom(login(PASSWORD));
      String origin = "https://127.0.0.1:" + LanProxyService.port();
      String reply = raw("GET", "/", auth() + "Cookie: " + session + "\r\nOrigin: " + origin + "\r\nSec-Fetch-Site: same-origin\r\n");
      assertTrue(reply.endsWith("same-origin"));
      assertNotNull(backend.requests.poll(2, TimeUnit.SECONDS));
      assertFalse(reply.contains("backend-secret"));
      assertFalse(reply.contains("dsh-auth-fixture"));
    }
  }

  @Test public void crossOriginWebsocketCannotUseAnAuthenticatedSession() throws Exception {
    try (Backend backend = new Backend("cross-origin-ws")) {
      start(backend, 34);
      String session = cookieFrom(login(PASSWORD));
      try (Socket socket = connect(LanProxyService.port())) {
        socket.setSoTimeout(5000);
        String headers = "GET /upgrade HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port()
            + "\r\nCookie: " + session + "\r\nOrigin: https://attacker.example\r\n"
            + "Sec-Fetch-Site: cross-site\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
            + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        HttpProtocol.Head head = HttpProtocol.readHead(socket.getInputStream(), socket, HttpProtocol.LAN, HttpProtocol.deadline(5000), false);
        assertNotEquals("cross-origin websocket must not upgrade", 101, head.status);
      }
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void rebindingAndDuplicateHostCannotUseSession() throws Exception {
    try (Backend backend = new Backend("rebind")) {
      start(backend, 35);
      String session = cookieFrom(login(PASSWORD));
      String remote = "attacker.example:" + LanProxyService.port();
      assertTrue(rawHost("GET", "/", remote, "Cookie: " + session + "\r\nOrigin: https://" + remote + "\r\nSec-Fetch-Site: same-origin\r\n").startsWith("HTTP/1.1 403"));
      assertFalse(raw("GET", "/", "Host: " + remote + "\r\nCookie: " + session + "\r\n").startsWith("HTTP/1.1 200"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void multipleBrowserLoginsAndCookiesWorkWithoutFalseThrottling() throws Exception {
    try (Backend backend = new Backend("many-browsers")) {
      start(backend, 36);
      List<String> cookies = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        String login = login(PASSWORD);
        assertTrue("successful logins from same peer must not exhaust the failure limit", login.startsWith("HTTP/1.1 303"));
        cookies.add(cookieFrom(login));
      }
      for (String cookie : cookies) {
        String result = raw("GET", "/", auth() + "Cookie: " + cookie + "\r\nSec-Fetch-Site: same-origin\r\n");
        assertTrue(result.endsWith("many-browsers"));
      }
    }
  }

  @Test public void rateLimitReturns429AndRecoversAfterWindow() throws Exception {
    try (Backend backend = new Backend("rate-limit")) {
      start(backend, 37);
      // Keep real TCP and production throttle, while advancing its injectable clock
      // to exercise the one-minute recovery without making the suite sleep a minute.
      java.lang.reflect.Field runField = LanProxyService.class.getDeclaredField("activeRun");
      runField.setAccessible(true);
      Object run = runField.get(null);
      java.lang.reflect.Field throttleField = run.getClass().getDeclaredField("loginThrottle");
      throttleField.setAccessible(true);
      Object throttle = throttleField.get(run);
      java.lang.reflect.Field clockField = throttle.getClass().getDeclaredField("clock");
      clockField.setAccessible(true);
      java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1);
      clockField.set(throttle, (java.util.function.LongSupplier) now::get);
      for (int i = 0; i < 6; i++) assertTrue(login("wrong-password").startsWith("HTTP/1.1 401"));
      String limited = login(PASSWORD);
      assertTrue(limited.startsWith("HTTP/1.1 429"));
      assertTrue(limited.contains("Retry-After: 60"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
      now.addAndGet(TimeUnit.SECONDS.toNanos(60));
      assertTrue(login(PASSWORD).startsWith("HTTP/1.1 303"));
    }
  }

  @Test public void routeDenialsDoNotPermanentlyPoisonOtherRequests() throws Exception {
    try (Backend backend = new Backend("route-denial")) {
      start(backend, 11);
      String token = sessionAt(LanProxyService.port());
      assertTrue(response("/forbidden", token).startsWith("HTTP/1.1 403"));
      assertTrue("route 403 must not destroy the current run's credential", response("/", token).startsWith("HTTP/1.1 200"));
      assertTrue(response("/unauthorized", token).startsWith("HTTP/1.1 401"));
      assertTrue("route 401 must not destroy the current run's credential", response("/", token).startsWith("HTTP/1.1 200"));
    }
  }

  @Test public void restartingListenerUsesTheNewActualBackendPort() throws Exception {
    try (Backend first = new Backend("first-port"); Backend second = new Backend("second-port")) {
      start(first, 12);
      String token = sessionAt(LanProxyService.port());
      assertTrue(response("/", token).endsWith("first-port"));
      start(second, 12);
      assertTrue("listener must capture the newly authenticated actual port", response("/", sessionAt(LanProxyService.port())).endsWith("second-port"));
    }
  }

  @Test public void unknownActualPortDoesNotFallBackToAnUnrelatedService() throws Exception {
    assertTrue(LanProxyService.setDshAuthCookie(COOKIE, 13));
    LanProxyService.start(null, null, 0, 13);
    Thread.sleep(80);
    assertFalse("an unknown actual port cannot silently become 3080", LanProxyService.isBound());
  }

  @Test public void legacyQueryTokenIsRejected() throws Exception {
    try (Backend backend = new Backend("callback")) {
      start(backend, 14);
      String token = sessionAt(LanProxyService.port());
      String reply = new String(request("/auth/callback?code=fixture&token=" + token + "&state=fixture", token, true), StandardCharsets.UTF_8);
      assertTrue(reply.startsWith("HTTP/1.1 401"));
      assertNull(backend.requests.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void eightIdleKeepAlivePeersLeaveShortRequestsResponsive() throws Exception {
    List<Socket> idle = new ArrayList<>();
    try (Backend backend = new Backend("idle-peers")) {
      start(backend, 15);
      String token = sessionAt(LanProxyService.port());
      for (int i = 0; i < 8; i++) {
        Socket socket = connect(LanProxyService.port());
        idle.add(socket);
        socket.setSoTimeout(2500);
        String header = "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port() + "\r\n" + auth() + "Cookie: " + name(LanProxyService.port()) + "=" + token + "\r\n\r\n";
        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
        var input = socket.getInputStream();
        var head = HttpProtocol.readHead(input, socket, HttpProtocol.LAN, HttpProtocol.deadline(2500), false);
        assertEquals(200, head.status);
        HttpProtocol.copyBody(head.responseBody(HttpProtocol.parse(header, true)), input, new ByteArrayOutputStream());
      }
      try (Socket current = connect(LanProxyService.port())) {
        current.setSoTimeout(2000);
        current.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port() + "\r\n" + auth() + "Cookie: " + name(LanProxyService.port()) + "=" + token + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        assertTrue("idle keep-alive sockets must not exhaust request workers", new String(current.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"));
      }
    } finally { for (Socket socket : idle) socket.close(); }
  }

  @Test public void chunkedBodyAndTrailerKeepTheirExactBoundary() throws Exception {
    try (Backend backend = new Backend("chunked")) {
      start(backend, 16);
      String reply = response("/chunked", sessionAt(LanProxyService.port()));
      assertTrue(reply.startsWith("HTTP/1.1 200"));
      assertTrue(reply.contains("Transfer-Encoding: chunked\r\n"));
      assertTrue(reply.endsWith("\r\n\r\n3\r\nabc\r\n0\r\nX-Fixture: kept\r\n\r\n"));
    }
  }

  @Test public void onlyValidWebsocketHandshakeUpgradesAndStopClosesTheTunnel() throws Exception {
    try (Backend backend = new Backend("websocket")) {
      start(backend, 17);
      String token = sessionAt(LanProxyService.port());
      String credential = " HTTP/1.1\r\nHost: 127.0.0.1:" + LanProxyService.port() + "\r\nOrigin: https://127.0.0.1:" + LanProxyService.port() + "\r\n" + auth() + "Cookie: " + name(LanProxyService.port()) + "=" + token
          + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n\r\n";
      try (Socket socket = connect(LanProxyService.port())) {
        socket.setSoTimeout(3000);
        socket.getOutputStream().write(("GET /invalid-upgrade" + credential).getBytes(StandardCharsets.US_ASCII));
        assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 502"));
      }
      try (Socket socket = connect(LanProxyService.port())) {
        socket.setSoTimeout(3000);
        socket.getOutputStream().write(("GET /upgrade" + credential).getBytes(StandardCharsets.US_ASCII));
        var head = HttpProtocol.readHead(socket.getInputStream(), socket, HttpProtocol.LAN, HttpProtocol.deadline(3000), false);
        assertEquals(101, head.status);
        byte[] frame = {(byte) 0x82, 1, 0x42};
        socket.getOutputStream().write(frame);
        assertArrayEquals(frame, socket.getInputStream().readNBytes(3));
        LanProxyService.stop(17);
        assertEquals(-1, socket.getInputStream().read());
        assertFalse(LanProxyService.hasDshAuth(17));
        assertFalse(LanProxyService.isBound());
      }
    }
  }

  @Test public void oldGenerationCannotStopOrAuthorizeTheNewGeneration() throws Exception {
    try (Backend backend = new Backend("generation")) {
      start(backend, 18);
      assertTrue(LanProxyService.port(18) > 0);
      assertEquals(0, LanProxyService.port(17));
      String old = sessionAt(LanProxyService.port());
      var snapshot = LanProxyService.snapshotDshAuth(18);
      assertTrue(LanProxyService.isCurrentDshAuth(snapshot));
      LanProxyService.stop(18);
      assertEquals(0, LanProxyService.port(18));
      start(backend, 19);
      assertEquals(0, LanProxyService.port(18));
      assertTrue(LanProxyService.port(19) > 0);
      assertFalse(LanProxyService.isCurrentDshAuth(snapshot));
      assertTrue(raw("GET", "/", "Cookie: " + name(LanProxyService.port()) + "=" + old + "\r\n").startsWith("HTTP/1.1 401"));
      LanProxyService.stop(18);
      assertTrue(LanProxyService.isBound());
      assertTrue(response("/", sessionAt(LanProxyService.port())).startsWith("HTTP/1.1 200"));
    }
  }

  @Test public void independentApplicationsHoldSeparateLanPortsAndRejectEachOthersTokens() throws Exception {
    // Isolate static app state just as two Android processes do. Both execute
    // the same compiled production proxy; Android permission/context are stubs.
    URL location = LanProxyService.class.getProtectionDomain().getCodeSource().getLocation();
    try (java.net.URLClassLoader other = new java.net.URLClassLoader(
            new URL[] {location, org.bouncycastle.asn1.ASN1Object.class.getProtectionDomain().getCodeSource().getLocation()},
            ClassLoader.getPlatformClassLoader());
         Backend first = new Backend("first-app"); Backend second = new Backend("second-app")) {
      Class<?> proxy = other.loadClass("com.deepseekharness.app.LanProxyService");
      Class<?> context = other.loadClass("android.content.Context");
      java.lang.reflect.Method otherStop = proxy.getMethod("stop");
      try {
        start(first, 21);
        int firstPort = LanProxyService.port(21);
        String firstToken = sessionAt(LanProxyService.port());
        assertEquals(true, proxy.getMethod("setDshAuthCookie", String.class, long.class).invoke(null, COOKIE, 22L));
        Object secondContext = context.getConstructor(String.class).newInstance(VERIFIER);
        Object network = other.loadClass("com.deepseekharness.app.bridge.LocalNetworkAccess")
            .getMethod("currentLan", context).invoke(null, secondContext);
        assertEquals(true, proxy.getMethod("trustNetwork", context, network.getClass()).invoke(null, secondContext, network));
        proxy.getMethod("start", String.class, context, int.class, long.class)
            .invoke(null, null, secondContext, second.server.getLocalPort(), 22L);
        java.lang.reflect.Method otherPort = proxy.getMethod("port", long.class);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        int secondPort;
        while ((secondPort = (Integer) otherPort.invoke(null, 22L)) == 0 && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(secondPort > 0);
        assertNotEquals(firstPort, secondPort);
        trust(secondPort, (java.security.cert.X509Certificate)
            other.loadClass("com.deepseekharness.app.AndroidLanIdentity").getMethod("leaf").invoke(null));
        String secondToken = sessionAt(secondPort);
        assertNotEquals(firstToken, secondToken);
        assertTrue(new String(requestAt(firstPort, "/", firstToken, false), StandardCharsets.UTF_8).endsWith("first-app"));
        assertTrue(new String(requestAt(secondPort, "/", secondToken, false), StandardCharsets.UTF_8).endsWith("second-app"));
        assertTrue(rawAt(firstPort, "GET", "/", "127.0.0.1:" + firstPort, "Cookie: " + name(secondPort) + "=" + secondToken + "\r\n").startsWith("HTTP/1.1 401"));
        assertTrue(rawAt(secondPort, "GET", "/", "127.0.0.1:" + secondPort, "Cookie: " + name(firstPort) + "=" + firstToken + "\r\n").startsWith("HTTP/1.1 401"));
        LanProxyService.stop(21);
        assertEquals(0, LanProxyService.port(21));
        assertEquals(secondPort, otherPort.invoke(null, 22L));
        assertTrue(new String(requestAt(secondPort, "/", secondToken, false), StandardCharsets.UTF_8).endsWith("second-app"));
      } finally { otherStop.invoke(null); }
    }
  }
}
