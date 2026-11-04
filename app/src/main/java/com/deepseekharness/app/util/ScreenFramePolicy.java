package com.deepseekharness.app.util;

import java.util.LinkedHashSet;
import java.util.Set;

/** 像素观察的新鲜度，不提供目标授权；动态应用仍受单次观察和独立身份围栏约束。 */
public final class ScreenFramePolicy {
  public static final long DYNAMIC_WINDOW_MS = 5_000;
  public static final int PACKAGE_LIMIT = 100;
  public static final int CONFIG_LIMIT = 8_000;

  private ScreenFramePolicy() {}

  /** 列表与修订号必须原子读取；修订变化使之前的像素观察失效。 */
  public record Settings(String configured, long revision) {
    public boolean animated(String packageName) {
      return revision >= 0 && ScreenFramePolicy.animated(configured, packageName);
    }
  }

  /** 默认不包含任何包；只接受完整 Android 包名，不使用前缀或模糊匹配。 */
  public static Set<String> packages(String configured) {
    if (configured == null || configured.isBlank()) return Set.of();
    if (configured.length() > CONFIG_LIMIT)
      throw new IllegalArgumentException("SCREEN_FRAME_PACKAGE_LIMIT");
    LinkedHashSet<String> packages = new LinkedHashSet<>();
    for (String entry : configured.split("[\\s,;]+")) {
      if (entry.isEmpty()) continue;
      if (entry.length() > 255
          || !entry.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
        throw new IllegalArgumentException("SCREEN_FRAME_PACKAGE_INVALID");
      packages.add(entry);
      if (packages.size() > PACKAGE_LIMIT)
        throw new IllegalArgumentException("SCREEN_FRAME_PACKAGE_LIMIT");
    }
    return java.util.Collections.unmodifiableSet(packages);
  }

  public static String normalizePackages(String configured) {
    return String.join("\n", packages(configured));
  }

  /** 无法读取或损坏的配置不能放宽像素验证。 */
  public static boolean animated(String configured, String packageName) {
    if (packageName == null || packageName.isEmpty()) return false;
    try {
      return packages(configured).contains(packageName);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  /** 按实际毫秒判定，同样的等待不随 30/60/120Hz 的帧数改变。 */
  public static boolean recent(long observedAt, long now, boolean animated) {
    long age = now - observedAt;
    long window = animated ? DYNAMIC_WINDOW_MS : ScreenObservationLedger.TTL_MILLIS;
    return observedAt >= 0 && now >= observedAt && age >= 0 && age <= window;
  }

  public static String observationRejection(
      ScreenObservationLedger.Observation seen, Settings current, long now) {
    if (!seen.frameSettings.equals(current)) return "OBSERVATION_POLICY_REVOKED";
    return recent(seen.createdAt, now, seen.frameSettings.animated(seen.target.packageName))
        ? null
        : "OBSERVATION_TIMEOUT";
  }

  /** Core已经见过的配置不能被晚到请求回退，同修订号也不能替换名单。 */
  public static String settingsRejection(Settings current, Settings incoming) {
    if (incoming == null || incoming.revision() < 0) return "SCREEN_FRAME_POLICY_INVALID";
    if (incoming.revision() < current.revision()) return "SCREEN_FRAME_POLICY_STALE";
    if (incoming.revision() == current.revision() && !incoming.equals(current))
      return "SCREEN_FRAME_POLICY_CONFLICT";
    return null;
  }

  public static boolean pixelsAllowed(
      ScreenObservationLedger.Observation seen,
      int width,
      int height,
      int rotation,
      String fingerprint,
      ScreenWindowRegion region) {
    return seen.sameGeometry(width, height, rotation, region)
        && (seen.frameSettings.animated(seen.target.packageName)
            || seen.fingerprint.equals(fingerprint));
  }
}
