package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 只登记已经生成返回内容的像素观察；验证截图永远不能登记为调用方观察。 */
public final class ScreenObservationLedger {
  public static final long TTL_MILLIS = 30_000L;
  private final LongSupplier clock;
  private final Map<String, Observation> observations = new LinkedHashMap<>();

  public static final class Observation {
    public final String id, fingerprint, owner;
    public final ScreenTarget target;
    public final long serviceEpoch, createdAt;
    public final int width, height, rotation;
    public final ScreenWindowRegion region;
    public final ScreenFramePolicy.Settings frameSettings;

    private Observation(
        String id,
        ScreenTarget target,
        long epoch,
        String owner,
        int width,
        int height,
        int rotation,
        String fingerprint,
        long now,
        ScreenWindowRegion region,
        ScreenFramePolicy.Settings frameSettings) {
      this.id = id;
      this.target = target;
      this.owner = owner;
      serviceEpoch = epoch;
      this.width = width;
      this.height = height;
      this.rotation = rotation;
      this.fingerprint = fingerprint;
      createdAt = now;
      this.region = region;
      this.frameSettings = frameSettings;
    }

    public boolean matches(int width, int height, int rotation, String fingerprint) {
      return this.width == width
          && this.height == height
          && this.rotation == rotation
          && this.fingerprint.equals(fingerprint);
    }

    public boolean matches(
        int width, int height, int rotation, String fingerprint, ScreenWindowRegion region) {
      return this.region.equals(region) && matches(width, height, rotation, fingerprint);
    }

    public boolean sameGeometry(int width, int height, int rotation, ScreenWindowRegion region) {
      return this.width == width
          && this.height == height
          && this.rotation == rotation
          && this.region.equals(region);
    }
  }

  public ScreenObservationLedger(LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized Observation publish(
      ScreenTarget target, long epoch, int width, int height, int rotation, String fingerprint) {
    return publish(target, epoch, "", width, height, rotation, fingerprint);
  }

  public synchronized Observation publish(
      ScreenTarget target,
      long epoch,
      String owner,
      int width,
      int height,
      int rotation,
      String fingerprint) {
    return publish(
        target,
        epoch,
        owner,
        width,
        height,
        rotation,
        fingerprint,
        ScreenWindowRegion.full(width, height));
  }

  public synchronized Observation publish(
      ScreenTarget target,
      long epoch,
      String owner,
      int width,
      int height,
      int rotation,
      String fingerprint,
      ScreenWindowRegion region) {
    return publish(
        target,
        epoch,
        owner,
        width,
        height,
        rotation,
        fingerprint,
        region,
        clock.getAsLong(),
        new ScreenFramePolicy.Settings("", 0));
  }

  public synchronized Observation publish(
      ScreenTarget target,
      long epoch,
      String owner,
      int width,
      int height,
      int rotation,
      String fingerprint,
      ScreenWindowRegion region,
      long capturedAt,
      ScreenFramePolicy.Settings frameSettings) {
    if (target == null
        || target.windowId < 0
        || width <= 0
        || height <= 0
        || fingerprint == null
        || fingerprint.isEmpty()
        || region == null
        || capturedAt < 0
        || capturedAt > clock.getAsLong()
        || frameSettings == null
        || !region.within(width, height))
      throw new IllegalArgumentException("SCREEN_OBSERVATION_INVALID");
    observations.clear(); // 一个连接只接受最近真正返回的截图。
    Observation value =
        new Observation(
            UUID.randomUUID().toString(),
            target,
            epoch,
            owner,
            width,
            height,
            rotation,
            fingerprint,
            capturedAt,
            region,
            frameSettings);
    observations.put(value.id, value);
    return value;
  }

  /** 先消费再验证：失效、拒绝和未知结果都不能重复使用同一观察。 */
  public synchronized Observation consume(String id, ScreenTarget target, long epoch) {
    return consume(id, target, epoch, "");
  }

  public synchronized Observation consume(
      String id, ScreenTarget target, long epoch, String owner) {
    Observation value = observations.remove(id);
    if (value == null
        || value.serviceEpoch != epoch
        || !value.target.matches(target)
        || !value.owner.equals(owner)) return null;
    long age = clock.getAsLong() - value.createdAt;
    return age < 0 || age > TTL_MILLIS ? null : value;
  }

  public synchronized void clear() {
    observations.clear();
  }
}
