package com.deepseekharness.app.util;

import java.util.Objects;

/**
 * 实体屏动作的显式数据支路；只选择本支路字段，不签发或验证凭据，也不从其他 ID 补齐缺失值。
 *
 * <p>像素观察与节点租约仍由原 owner 核验。额外字段保持旧入口的忽略语义；类型本身不授予动作许可。
 */
public sealed interface PhysicalActionRequest
    permits PhysicalActionRequest.Pixel,
        PhysicalActionRequest.Node,
        PhysicalActionRequest.FocusedInput,
        PhysicalActionRequest.Key {
  Operation operation();

  /** 查询与动作使用同一命名表；未知操作仍由执行器按旧错误码拒绝。 */
  enum Operation {
    SENSE_STATE("senseState", true),
    DUMP("dump", true),
    TREE("tree", true),
    SCREENSHOT("screenshot", true),
    TAP("tap", false),
    SWIPE("swipe", false),
    NODE("node", false),
    TAP_TEXT("tapText", false),
    INPUT("input", false),
    KEY("key", false),
    UNKNOWN("", false);

    private final String wireName;
    private final boolean observing;

    Operation(String wireName, boolean observing) {
      this.wireName = wireName;
      this.observing = observing;
    }

    public boolean observing() {
      return observing;
    }

    public static Operation fromWire(String value) {
      for (Operation operation : values())
        if (operation != UNKNOWN && operation.wireName.equals(value)) return operation;
      return UNKNOWN;
    }
  }

  /** Android 适配器沿用原 JSONObject 的 optString/has/optInt 语义，本层不做第二次类型强转。 */
  interface Fields {
    String string(String name);

    boolean has(String name);

    int integer(String name, int fallback);
  }

  record Pixel(Operation operation, String observationId) implements PhysicalActionRequest {
    public Pixel {
      if (operation != Operation.TAP && operation != Operation.SWIPE)
        throw new IllegalArgumentException("PHYSICAL_PIXEL_OPERATION_INVALID");
      Objects.requireNonNull(observationId, "observationId");
    }
  }

  /** TAP_TEXT 仅保留原有临时按文案查找路径，其空 ID 不是租约，也不会回退成有凭据的 NODE。 */
  record Node(Operation operation, String treeId, String nodeId) implements PhysicalActionRequest {
    public Node {
      if (operation != Operation.NODE && operation != Operation.TAP_TEXT)
        throw new IllegalArgumentException("PHYSICAL_NODE_OPERATION_INVALID");
      Objects.requireNonNull(treeId, "treeId");
      Objects.requireNonNull(nodeId, "nodeId");
      if (operation == Operation.TAP_TEXT && (!treeId.isEmpty() || !nodeId.isEmpty()))
        throw new IllegalArgumentException("PHYSICAL_TEXT_LOOKUP_HAS_NO_LEASE");
    }
  }

  /** 当前焦点输入仍在平台侧重新解析/核对，不读取或借用节点、像素凭据。 */
  record FocusedInput() implements PhysicalActionRequest {
    @Override
    public Operation operation() {
      return Operation.INPUT;
    }
  }

  record Key(boolean hasKeycode, int keycode, String name) implements PhysicalActionRequest {
    public Key {
      Objects.requireNonNull(name, "name");
    }

    @Override
    public Operation operation() {
      return Operation.KEY;
    }
  }

  /** 只提取动作支路；查询/未知操作返回 null，不把缺失凭据换成另一支路。 */
  static PhysicalActionRequest parse(String operation, Fields fields) {
    Objects.requireNonNull(fields, "fields");
    return switch (Operation.fromWire(operation)) {
      case TAP -> new Pixel(Operation.TAP, fields.string("observationId"));
      case SWIPE -> new Pixel(Operation.SWIPE, fields.string("observationId"));
      case NODE -> new Node(Operation.NODE, fields.string("treeId"), fields.string("nodeId"));
      case TAP_TEXT -> new Node(Operation.TAP_TEXT, "", "");
      case INPUT -> new FocusedInput();
      case KEY ->
          new Key(fields.has("keycode"), fields.integer("keycode", -1), fields.string("name"));
      default -> null;
    };
  }
}
