package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class VscreenBridgeRequestTest {
  static class Actions implements VscreenBridgeRequest.Actions<String> {
    String called, gen, id, text;
    int start, end, calls;

    public String create(String value) {
      calls++;
      return called = "create";
    }

    public String status() {
      calls++;
      return called = "status";
    }

    public String launch(String value) {
      calls++;
      return called = "launch";
    }

    public String tree() {
      calls++;
      return called = "tree";
    }

    public String node(String g, long f, String n, String a, String t) {
      gen = g;
      id = n;
      text = t;
      calls++;
      return called = "node:" + a;
    }

    public String editor(String g, String mode, String e, String t, int s, int n) {
      gen = g;
      id = e;
      text = t;
      start = s;
      end = n;
      calls++;
      return called = mode;
    }

    public String touch(String g, long f, String s, int a, float x, float y) {
      calls++;
      return called = "touch";
    }

    public String preview() {
      calls++;
      return called = "preview";
    }

    public String action(String mode, String g, long frame, String query) {
      calls++;
      return called = mode;
    }

    public String type(String g, long f, String t) {
      calls++;
      return called = "type";
    }

    public String close() {
      calls++;
      return called = "close";
    }
  }

  static class TypedActions extends Actions {
    VscreenActionEvidence evidence;
    int typedCalls;
    String query;
    float x, y;

    private String typed(String operation, VscreenActionEvidence value) {
      evidence = value;
      typedCalls++;
      return called = operation;
    }

    @Override
    public String node(VscreenActionEvidence.Node value, String action, String text) {
      this.text = text;
      return typed("node:" + action, value);
    }

    @Override
    public String editor(
        VscreenActionEvidence.Editor value, String mode, String text, int start, int end) {
      this.text = text;
      this.start = start;
      this.end = end;
      return typed(mode, value);
    }

    @Override
    public String touch(
        VscreenActionEvidence.RawFrame value, String stroke, int action, float x, float y) {
      this.id = stroke;
      this.x = x;
      this.y = y;
      return typed("touch", value);
    }

    @Override
    public String anchor(VscreenActionEvidence.ObservedAnchor value, int x, int y) {
      this.x = x;
      this.y = y;
      return typed("anchor", value);
    }

    @Override
    public String anchoredClick(VscreenActionEvidence.AnchoredClick value) {
      return typed("anchored-click", value);
    }

    @Override
    public String action(String mode, VscreenActionEvidence.RawFrame value, String query) {
      this.query = query;
      return typed(mode, value);
    }

    @Override
    public String type(VscreenActionEvidence.RawFrame value, String text) {
      this.text = text;
      return typed("type", value);
    }
  }

  @Test
  public void productionExecuteConsumesRawFrameForEveryRawInputWithoutAnotherRoute()
      throws Exception {
    String[] routes = {"tap", "swipe", "key", "type", "touch"};
    String[] arguments = {
      "x=1&y=2",
      "x1=1&y1=2&x2=3&y2=4&ms=300",
      "keycode=4",
      "text=hello",
      "stroke=aaaaaaaa-bbbb-cccc&action=0&x=1&y=2"
    };
    String core = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    for (int i = 0; i < routes.length; i++) {
      var request =
          VscreenBridgeRequest.query(
              "/app/vscreen/" + routes[i],
              "coreInstance=" + core + "&generation=original&frameSeq=7&" + arguments[i]);
      var actions = new TypedActions();
      assertEquals(routes[i], request.execute(actions));
      assertEquals(1, actions.typedCalls);
      assertEquals(0, actions.calls);
      assertSame(request.actionEvidence(), actions.evidence);
      assertTrue(actions.evidence instanceof VscreenActionEvidence.RawFrame);
      var raw = (VscreenActionEvidence.RawFrame) actions.evidence;
      assertEquals(core, raw.coreInstance());
      assertEquals("original", raw.generation());
      assertEquals(7L, raw.frameSeq());
    }
  }

  @Test
  public void nodeWithoutFrameAndNodeWithDifferentPixelFrameKeepSemanticEvidence()
      throws Exception {
    for (String optional : new String[] {"", "&frameSeq=900"}) {
      var request =
          VscreenBridgeRequest.query(
              "/app/vscreen/node", "generation=original&nodeId=node-only&action=click" + optional);
      var actions = new TypedActions();
      assertEquals("node:click", request.execute(actions));
      assertEquals(1, actions.typedCalls);
      assertEquals(0, actions.calls);
      assertSame(request.actionEvidence(), actions.evidence);
      var node = (VscreenActionEvidence.Node) actions.evidence;
      assertEquals("node-only", node.nodeId());
      assertEquals("original", node.generation());
      assertEquals(optional.isEmpty() ? null : Long.valueOf(900), node.frameSeq());
    }
    Actions legacy = new Actions();
    assertEquals(
        "node:click",
        VscreenBridgeRequest.query(
                "/app/vscreen/node", "generation=original&nodeId=node-only&action=click")
            .execute(legacy));
    assertEquals(1, legacy.calls);
  }

  @Test
  public void editorModesHaveNoRawFrameOrOneUseTicketAndPreserveOriginalEditorIdentity()
      throws Exception {
    var get = VscreenBridgeRequest.query("/app/vscreen/editor", "generation=original");
    var edit =
        VscreenBridgeRequest.json(
            "/app/vscreen/edit",
            Map.of("generation", "original", "editorId", "8:42", "text", "正文", "start", 1));
    var submit =
        VscreenBridgeRequest.query("/app/vscreen/submit", "generation=original&editorId=8%3A42");
    String[] modes = {"editor", "edit", "submit"};
    int index = 0;
    for (var request : List.of(get, edit, submit)) {
      var actions = new TypedActions();
      assertEquals(modes[index], request.execute(actions));
      assertEquals(1, actions.typedCalls);
      assertEquals(0, actions.calls);
      assertSame(request.actionEvidence(), actions.evidence);
      var editor = (VscreenActionEvidence.Editor) actions.evidence;
      assertEquals("original", editor.generation());
      assertEquals(index == 0 ? "" : "8:42", editor.editorId());
      index++;
    }
    var actions = new TypedActions();
    assertEquals("edit", edit.execute(actions));
    assertEquals("正文", actions.text);
    assertEquals(1, actions.start);
    assertEquals(1, actions.end);
    // 旧 get 文法允许无效长 editorId 但完全忽略它，不能因新载体收紧外部语义。
    var legacyGet =
        VscreenBridgeRequest.query(
            "/app/vscreen/editor", "generation=original&editorId=" + "x".repeat(200));
    assertEquals("", ((VscreenActionEvidence.Editor) legacyGet.actionEvidence()).editorId());
  }

  @Test
  public void alternateEvidenceNamesAndUnknownFieldsNeverDispatchAnyAction() throws Exception {
    String[] routes = {"tap", "node", "edit", "anchor", "anchored-click"};
    String uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    for (String route : routes) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("coreInstance", uuid);
      body.put("generation", "original");
      if (route.equals("tap")) {
        body.put("frameSeq", 7);
        body.put("x", 1);
        body.put("y", 2);
      } else if (route.equals("node")) {
        body.put("nodeId", "node");
        body.put("action", "click");
      } else if (route.equals("edit")) {
        body.put("editorId", "8:42");
        body.put("text", "正文");
      } else {
        body.put("frameSeq", 7);
        body.put(route.equals("anchor") ? "observationId" : "anchorId", uuid);
        if (route.equals("anchor")) {
          body.put("x", 1);
          body.put("y", 2);
        }
      }
      for (String extra : List.of("nodeId", "editorId", "observationId", "anchorId", "treeId")) {
        if (body.containsKey(extra)) continue;
        var invalid = new LinkedHashMap<>(body);
        invalid.put(extra, uuid);
        var actions = new TypedActions();
        HttpProtocol.Failure rejected =
            assertThrows(
                HttpProtocol.Failure.class,
                () -> VscreenBridgeRequest.json("/app/vscreen/" + route, invalid).execute(actions));
        assertEquals("UNKNOWN_ARGUMENT", rejected.getMessage());
        assertEquals(0, actions.calls);
        assertEquals(0, actions.typedCalls);
        assertNull(actions.evidence);
      }
    }
  }

  @Test
  public void anotherEvidenceIdCannotFillRequiredRawFrameNodeOrEditorFields() throws Exception {
    String[][] invalid = {
      {"tap", "generation=gen&x=1&y=2&nodeId=node", "INVALID_frameSeq"},
      {"node", "generation=gen&action=click&editorId=e", "INVALID_nodeId"},
      {"edit", "generation=gen&text=x&nodeId=n", "INVALID_editorId"},
      {"submit", "generation=gen&frameSeq=4&anchorId=a", "INVALID_editorId"}
    };
    for (String[] row : invalid) {
      var actions = new TypedActions();
      var failure =
          assertThrows(
              HttpProtocol.Failure.class,
              () -> VscreenBridgeRequest.query("/app/vscreen/" + row[0], row[1]).execute(actions));
      assertEquals(row[2], failure.getMessage());
      assertEquals(0, actions.calls);
      assertEquals(0, actions.typedCalls);
    }
  }

  @Test
  public void lifecycleAndReadRoutesDoNotInventActionCredentials() throws Exception {
    for (String route : List.of("create", "status", "tree", "preview", "see", "close"))
      assertNull(VscreenBridgeRequest.query("/app/vscreen/" + route, "").actionEvidence());
  }

  @Test
  public void operationSpecificProductionDispatcherCallsGetEditAndSubmitSeparately()
      throws Exception {
    Actions actions = new Actions();
    assertEquals(
        "editor",
        VscreenBridgeRequest.query("/app/vscreen/editor", "generation=gen").execute(actions));
    assertEquals(
        "edit",
        VscreenBridgeRequest.json(
                "/app/vscreen/edit", Map.of("generation", "gen", "editorId", "3:42", "text", "正文"))
            .execute(actions));
    assertEquals("gen", actions.gen);
    assertEquals("3:42", actions.id);
    assertEquals("正文", actions.text);
    assertEquals(2, actions.start);
    assertEquals(2, actions.end);
    assertEquals(
        "submit",
        VscreenBridgeRequest.json(
                "/app/vscreen/submit", Map.of("generation", "gen", "editorId", "3:42"))
            .execute(actions));
    assertEquals(
        "edit",
        VscreenBridgeRequest.query(
                "/app/vscreen/editor",
                "generation=gen&editorId=3%3A42&operation=edit&text=old&start=1")
            .execute(actions));
    assertEquals(1, actions.start);
    assertEquals(1, actions.end);
  }

  @Test
  public void clickDoesNotRequireTextButSetTextRequiresItsRealPayload() throws Exception {
    Actions actions = new Actions();
    assertEquals(
        "node:click",
        VscreenBridgeRequest.json(
                "/app/vscreen/node",
                Map.of("generation", "gen", "frameSeq", 1, "nodeId", "node", "action", "click"))
            .execute(actions));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/node",
                Map.of(
                    "generation", "gen", "frameSeq", 1, "nodeId", "node", "action", "set_text")));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/edit",
                Map.of("generation", "gen", "editorId", "e", "text", "x", "start", 2)));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/edit", Map.of("generation", "gen", "editorId", "e", "text", 7)));
  }

  static HttpProtocol.Head post(int length, String type) throws Exception {
    return HttpProtocol.parse(
        "POST /app/vscreen/edit HTTP/1.1\r\nHost: 127.0.0.1:3090\r\nContent-Type: "
            + type
            + "\r\nContent-Length: "
            + length
            + "\r\n\r\n",
        true);
  }

  @Test
  public void actualLoopbackHttpHeadAndBufferedBodyPreserve16000ChineseCharacters()
      throws Exception {
    String text = "中".repeat(16000),
        json = "{\"generation\":\"gen\",\"editorId\":\"3:42\",\"text\":\"" + text + "\"}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Socket client = new Socket("127.0.0.1", server.getLocalPort());
        Socket accepted = server.accept()) {
      String header =
          "POST /app/vscreen/edit HTTP/1.1\r\nHost: 127.0.0.1:3090\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
              + body.length
              + "\r\n\r\n";
      client.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
      client.getOutputStream().write(body);
      client.getOutputStream().flush();
      InputStream in = new BufferedInputStream(accepted.getInputStream(), 8192);
      var head =
          HttpProtocol.readHead(
              in, accepted, HttpProtocol.BRIDGE, HttpProtocol.deadline(5000), true);
      var request =
          VscreenBridgeRequest.json(
              "/app/vscreen/edit", VscreenBridgeRequest.readPost(head, in, accepted));
      Actions actions = new Actions();
      assertEquals("edit", request.execute(actions));
      assertEquals(text, actions.text);
      assertEquals(16000, actions.start);
    }
    String get =
        "GET /app/vscreen/edit?generation=gen&editorId=3%3A42&text="
            + URLEncoder.encode(text, "UTF-8")
            + " HTTP/1.1\r\nHost: 127.0.0.1:3090\r\n\r\n";
    assertEquals(
        431, assertThrows(HttpProtocol.Failure.class, () -> HttpProtocol.parse(get, true)).status);
    assertEquals(
        98304,
        HttpProtocol.BRIDGE.firstLine); // Existing 8192-character command allowance unchanged.
  }

  @Test
  public void oversizedTruncatedDuplicateJsonAndAmbiguousBodyAreRejectedBeforeDispatch()
      throws Exception {
    InputStream forbidden =
        new InputStream() {
          public int read() {
            throw new AssertionError("must reject length before reading");
          }
        };
    assertEquals(
        413,
        assertThrows(
                HttpProtocol.Failure.class,
                () ->
                    VscreenBridgeRequest.readPost(
                        post(VscreenBridgeRequest.MAX_BODY + 1, "application/json"),
                        forbidden,
                        null))
            .status);
    byte[] duplicate = "{\"text\":\"a\",\"text\":\"b\"}".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        400,
        assertThrows(
                HttpProtocol.Failure.class,
                () ->
                    VscreenBridgeRequest.readPost(
                        post(duplicate.length, "application/json"),
                        new ByteArrayInputStream(duplicate),
                        null))
            .status);
    assertEquals(
        400,
        assertThrows(
                HttpProtocol.Failure.class,
                () ->
                    VscreenBridgeRequest.readPost(
                        post(10, "application/json"),
                        new ByteArrayInputStream(new byte[] {1}),
                        null))
            .status);
    assertEquals(
        415,
        assertThrows(
                HttpProtocol.Failure.class,
                () -> VscreenBridgeRequest.readPost(post(1, "text/plain"), forbidden, null))
            .status);
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.query(
                "/app/vscreen/editor", "generation=gen&operation=get&op=submit"));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.query(
                "/app/vscreen/unknown/edit", "generation=gen&editorId=e&text=x"));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/edit",
                Map.of(
                    "generation",
                    "gen",
                    "editorId",
                    "e",
                    "text",
                    "x",
                    "expectedPackage",
                    "other")));
  }
}
