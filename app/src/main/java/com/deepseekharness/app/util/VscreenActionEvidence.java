package com.deepseekharness.app.util;

/**
 * 虚拟屏动作的五类独立凭据载体；只保存调用方提交的原证据，不签发、续期或升级授权。
 *
 * <p>文法仍由 VscreenBridgeRequest 判定；Manager 核验 coreInstance，实际帧、节点、焦点及锚点分别由各自 owner
 * 在派发点复核。这里不能从 status 或另一类凭据补齐字段，也不提供动作回退或重放。
 */
public sealed interface VscreenActionEvidence {
  String coreInstance();

  String generation();

  /** 原始输入必须保留原先观察的帧号；最新帧与已观察事实仍由 Core 核验。 */
  record RawFrame(String coreInstance, String generation, long frameSeq)
      implements VscreenActionEvidence {
    public RawFrame {
      identity(coreInstance, generation, false);
      frame(frameSeq, Long.MAX_VALUE);
    }
  }

  /** 节点可不带像素帧号，不能为语义动作额外要求像素静止。 */
  record Node(String coreInstance, String generation, Long frameSeq, String nodeId)
      implements VscreenActionEvidence {
    public Node {
      identity(coreInstance, generation, false);
      if (frameSeq != null) frame(frameSeq, Long.MAX_VALUE);
      token(nodeId, 80, false);
    }
  }

  /** 编辑器 get 没有旧 editorId；edit/submit 的非空要求仍由各自请求文法核验。 */
  record Editor(String coreInstance, String generation, String editorId)
      implements VscreenActionEvidence {
    public Editor {
      identity(coreInstance, generation, false);
      token(editorId, 160, true);
    }
  }

  /** 选锚点只接受原 see 的观察，不执行点击，也不续期原观察。 */
  record ObservedAnchor(String coreInstance, String generation, long frameSeq, String observationId)
      implements VscreenActionEvidence {
    public ObservedAnchor {
      identity(coreInstance, generation, true);
      frame(frameSeq, 9007199254740991L);
      uuid(observationId);
    }
  }

  /** 点击只消费原选点返回的锚点；不能使用 observationId 或节点 ID 冒充。 */
  record AnchoredClick(String coreInstance, String generation, long frameSeq, String anchorId)
      implements VscreenActionEvidence {
    public AnchoredClick {
      identity(coreInstance, generation, true);
      frame(frameSeq, 9007199254740991L);
      uuid(anchorId);
    }
  }

  private static void identity(String coreInstance, String generation, boolean coreRequired) {
    token(coreInstance, 36, !coreRequired);
    if (!coreInstance.isEmpty()) uuid(coreInstance);
    token(generation, 80, false);
  }

  private static void token(String value, int limit, boolean emptyAllowed) {
    if (value == null || value.length() > limit || !emptyAllowed && value.isEmpty())
      throw new IllegalArgumentException("VSCREEN_ACTION_EVIDENCE_INVALID");
  }

  private static void uuid(String value) {
    if (!Ids.uuid(value)) throw new IllegalArgumentException("VSCREEN_ACTION_EVIDENCE_INVALID");
  }

  private static void frame(long value, long limit) {
    if (value < 1 || value > limit)
      throw new IllegalArgumentException("VSCREEN_ACTION_EVIDENCE_INVALID");
  }
}
