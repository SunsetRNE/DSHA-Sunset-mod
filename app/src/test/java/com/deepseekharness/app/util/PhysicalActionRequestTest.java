package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PhysicalActionRequestTest {
  private static final class Fields implements PhysicalActionRequest.Fields {
    private final Map<String, Object> values;
    private final List<String> read = new ArrayList<>();

    Fields(Map<String, Object> values) {
      this.values = values;
    }

    @Override
    public String string(String name) {
      read.add(name);
      Object value = values.get(name);
      return value == null ? "" : String.valueOf(value);
    }

    @Override
    public boolean has(String name) {
      read.add("has:" + name);
      return values.containsKey(name);
    }

    @Override
    public int integer(String name, int fallback) {
      read.add("integer:" + name);
      Object value = values.get(name);
      return value instanceof Number number ? number.intValue() : fallback;
    }
  }

  @Test
  public void observationOperationsShareOneExactNameTableAndNeverReadActionFields() {
    for (String operation : List.of("senseState", "dump", "tree", "screenshot")) {
      var fields = new Fields(Map.of("observationId", "unused", "nodeId", "unused"));
      assertTrue(PhysicalActionRequest.Operation.fromWire(operation).observing());
      assertNull(PhysicalActionRequest.parse(operation, fields));
      assertTrue(fields.read.isEmpty());
    }
    for (String operation : List.of("tap", "swipe", "node", "tapText", "input", "key"))
      assertFalse(PhysicalActionRequest.Operation.fromWire(operation).observing());
  }

  @Test
  public void unknownOrWrongCaseOperationNeverBecomesAnotherAction() {
    for (String operation : new String[] {null, "", "Tap", "unknown", "anchor", "edit"}) {
      var fields = new Fields(Map.of("observationId", "valid-looking", "nodeId", "n"));
      assertEquals(
          PhysicalActionRequest.Operation.UNKNOWN,
          PhysicalActionRequest.Operation.fromWire(operation));
      assertNull(PhysicalActionRequest.parse(operation, fields));
      assertTrue(fields.read.isEmpty());
    }
  }

  @Test
  public void pixelOnlyReadsItsOriginalObservationIdEvenWithAllForeignFields() {
    for (String operation : List.of("tap", "swipe")) {
      var fields =
          new Fields(
              Map.of(
                  "observationId",
                  "physical-pixel",
                  "treeId",
                  "tree",
                  "nodeId",
                  "node",
                  "anchorId",
                  "anchor",
                  "frameSeq",
                  17,
                  "editorId",
                  "4:5"));
      var pixel = (PhysicalActionRequest.Pixel) PhysicalActionRequest.parse(operation, fields);
      assertEquals(PhysicalActionRequest.Operation.fromWire(operation), pixel.operation());
      assertEquals("physical-pixel", pixel.observationId());
      assertEquals(List.of("observationId"), fields.read);
    }
  }

  @Test
  public void missingPixelObservationCannotBorrowNodeAnchorEditorOrFrame() {
    var fields =
        new Fields(
            Map.of(
                "treeId",
                "tree",
                "nodeId",
                "node",
                "anchorId",
                "anchor",
                "frameSeq",
                17,
                "editorId",
                "4:5"));
    var pixel = (PhysicalActionRequest.Pixel) PhysicalActionRequest.parse("tap", fields);
    assertEquals("", pixel.observationId());
    assertEquals(List.of("observationId"), fields.read);
  }

  @Test
  public void nodeOnlyReadsOriginalTreeAndNodeAndDoesNotBorrowPixelOrVirtualFields() {
    var fields =
        new Fields(
            Map.of(
                "treeId",
                "physical-tree",
                "nodeId",
                "physical-node",
                "observationId",
                "pixel",
                "frameSeq",
                17,
                "anchorId",
                "anchor",
                "editorId",
                "4:5"));
    var node = (PhysicalActionRequest.Node) PhysicalActionRequest.parse("node", fields);
    assertEquals(PhysicalActionRequest.Operation.NODE, node.operation());
    assertEquals("physical-tree", node.treeId());
    assertEquals("physical-node", node.nodeId());
    assertEquals(List.of("treeId", "nodeId"), fields.read);
  }

  @Test
  public void missingNodeCredentialStaysMissingDespiteOtherIds() {
    var fields = new Fields(Map.of("observationId", "pixel", "anchorId", "anchor", "frameSeq", 17));
    var node = (PhysicalActionRequest.Node) PhysicalActionRequest.parse("node", fields);
    assertEquals("", node.treeId());
    assertEquals("", node.nodeId());
    assertEquals(List.of("treeId", "nodeId"), fields.read);
  }

  @Test
  public void legacyTextLookupDoesNotAcquireOrConsumeALeasedNode() {
    var fields = new Fields(Map.of("treeId", "tree", "nodeId", "node", "text", "settings"));
    var node = (PhysicalActionRequest.Node) PhysicalActionRequest.parse("tapText", fields);
    assertEquals(PhysicalActionRequest.Operation.TAP_TEXT, node.operation());
    assertEquals("", node.treeId());
    assertEquals("", node.nodeId());
    assertTrue(fields.read.isEmpty());
  }

  @Test
  public void focusedInputDoesNotBorrowEditorOrNodeCredentials() {
    var fields =
        new Fields(
            Map.of(
                "observationId", "pixel", "treeId", "tree", "nodeId", "node", "editorId", "4:5"));
    var input = PhysicalActionRequest.parse("input", fields);
    assertTrue(input instanceof PhysicalActionRequest.FocusedInput);
    assertEquals(PhysicalActionRequest.Operation.INPUT, input.operation());
    assertTrue(fields.read.isEmpty());
  }

  @Test
  public void keyPreservesExplicitCodePresenceAndNameWithoutCredentialLookup() {
    var fields = new Fields(Map.of("keycode", 4, "name", "home", "observationId", "unused"));
    var key = (PhysicalActionRequest.Key) PhysicalActionRequest.parse("key", fields);
    assertTrue(key.hasKeycode());
    assertEquals(4, key.keycode());
    assertEquals("home", key.name());
    assertEquals(List.of("has:keycode", "integer:keycode", "name"), fields.read);
    var named =
        (PhysicalActionRequest.Key)
            PhysicalActionRequest.parse("key", new Fields(Map.of("name", "quicksettings")));
    assertFalse(named.hasKeycode());
    assertEquals(-1, named.keycode());
    assertEquals("quicksettings", named.name());
  }

  @Test
  public void branchConstructionCannotRelabelAnOperationOrTextLookupAsLeasedNode() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhysicalActionRequest.Pixel(PhysicalActionRequest.Operation.NODE, "pixel"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhysicalActionRequest.Node(PhysicalActionRequest.Operation.TAP, "tree", "node"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PhysicalActionRequest.Node(
                PhysicalActionRequest.Operation.TAP_TEXT, "tree", "node"));
    // ID 的格式、有效期和 owner 仍由原账本验证，本类型不把看起来合法的字符串升级成许可。
    assertEquals(
        "foreign-id",
        new PhysicalActionRequest.Pixel(PhysicalActionRequest.Operation.TAP, "foreign-id")
            .observationId());
  }
}
