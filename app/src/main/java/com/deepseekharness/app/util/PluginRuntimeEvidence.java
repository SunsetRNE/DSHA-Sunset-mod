package com.deepseekharness.app.util;

/** 运行证据的纯匹配规则；模块名摘要与部署内容证明分别核验。 */
public final class PluginRuntimeEvidence {
  public static final String CONTENT_ALGORITHM = "dependency-graph-sha256-v1";

  private PluginRuntimeEvidence() {}

  public static String contentRejection(
      String currentAlgorithm, String current, String observedAlgorithm, String observed) {
    if (!CONTENT_ALGORITHM.equals(currentAlgorithm)
        || !CONTENT_ALGORITHM.equals(observedAlgorithm)
        || !digest(current)
        || !digest(observed)) return "CONTENT_UNVERIFIED";
    return current.equals(observed) ? "" : "CONTENT_CHANGED";
  }

  public static String identityRejection(
      String source,
      String startupId,
      String generation,
      String instance,
      String expectedGeneration,
      String expectedInstance,
      long observedAt,
      long now,
      long maxAge) {
    if (!"HOST_START_TICKET".equals(source) && !"OBSERVER_SESSION".equals(source))
      return "IDENTITY_UNBOUND";
    if ("HOST_START_TICKET".equals(source)
        && (startupId == null
            || startupId.equals("unknown")
            || !startupId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}"))) return "IDENTITY_UNBOUND";
    if ("OBSERVER_SESSION".equals(source) && !"unknown".equals(startupId))
      return "IDENTITY_UNBOUND";
    if (expectedGeneration == null
        || expectedGeneration.isEmpty()
        || !expectedGeneration.equals(generation)) return "GENERATION";
    if (expectedInstance == null
        || expectedInstance.isEmpty()
        || !expectedInstance.equals(instance)) return "INSTANCE";
    if (observedAt <= 0) return "NO_TIME";
    if (observedAt > now) return "FUTURE_TIME";
    return now - observedAt > maxAge ? "EXPIRED" : "";
  }

  /** 证据恰好达到年龄上限时仍有效，下一毫秒只安排一次内存失效，不触发目录扫描。 */
  public static long remainingLifetime(long observedAt, long now, long maxAge) {
    if (observedAt <= 0 || now < observedAt || maxAge < 0 || now - observedAt > maxAge) return -1;
    return maxAge - (now - observedAt) + 1;
  }

  public static String entryRejection(
      PluginEntryView expected, String entryId, String moduleHash, String parentId, boolean group) {
    if (expected == null || !expected.entryId.equals(entryId)) return "ENTRY_ID";
    if (!expected.moduleHash.equals(moduleHash)) return "ENTRY_MODULE";
    if (!expected.parentId.equals(parentId)) return "ENTRY_PARENT";
    return expected.group == group ? "" : "ENTRY_GROUP";
  }

  public static boolean knownPhase(String phase) {
    return phase == null
        || switch (phase) {
          case "pending", "loading", "unloading", "active", "failed" -> true;
          default -> false;
        };
  }

  private static boolean digest(String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }
}
