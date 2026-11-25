package com.deepseekharness.app.util;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 省电只在已知持续空闲时放锁，不主动停止进程。 */
public final class PowerPolicy {
  public static final long IDLE_GRACE_MS = 60_000;
  public static final int MAX_ACTIVITY_BYTES = 256 * 1024;
  private static final long MAX_ACTIVITY_AGE_MS = 30_000;
  private long idleSince = -1;
  private IdleFrame lastIdle;

  public record IdleFrame(long generation, String instance, long at) {
    private boolean matches(long currentGeneration, String currentInstance, long now) {
      return generation == currentGeneration
          && instance.equals(currentInstance)
          && at <= now
          && now - at <= MAX_ACTIVITY_AGE_MS;
    }
  }

  /** Read the top-level heartbeat without allocating any of its plugin rows. */
  public static IdleFrame readIdleFrame(byte[] bytes) {
    if (bytes.length > MAX_ACTIVITY_BYTES) return null;
    long generation = 0, at = 0;
    String instance = null;
    boolean idle = false;
    try (JsonReader reader =
        new JsonReader(
            new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()))) {
      reader.setStrictness(Strictness.STRICT);
      reader.beginObject();
      while (reader.hasNext()) {
        switch (reader.nextName()) {
          case "generation" -> {
            if (generation != 0) return null;
            generation = Long.parseLong(reader.nextString());
            if (generation <= 0) return null;
          }
          case "instance" -> {
            if (instance != null || reader.peek() != JsonToken.STRING) return null;
            instance = reader.nextString();
            if (instance.isEmpty()) return null;
          }
          case "at" -> {
            if (at != 0 || reader.peek() != JsonToken.NUMBER) return null;
            at = Long.parseLong(reader.nextString());
            if (at <= 0) return null;
          }
          case "idle" -> {
            if (idle || reader.peek() != JsonToken.BOOLEAN || !reader.nextBoolean()) return null;
            idle = true;
          }
          default -> reader.skipValue();
        }
      }
      reader.endObject();
      if (reader.peek() != JsonToken.END_DOCUMENT) return null;
    } catch (IOException | IllegalStateException | NumberFormatException malformed) {
      return null;
    }
    return idle && generation > 0 && instance != null && at > 0
        ? new IdleFrame(generation, instance, at)
        : null;
  }

  public void observe(IdleFrame sample, long now) {
    if (sample == null || !knownIdle(sample.generation(), sample.instance(), now)) idleSince = -1;
    lastIdle = sample;
  }

  public boolean knownIdle(long generation, String instance, long now) {
    return lastIdle != null && lastIdle.matches(generation, instance, now);
  }

  public boolean keepCpu(
      boolean eco,
      boolean webActive,
      boolean starting,
      boolean interactive,
      boolean lan,
      boolean nativeWork,
      boolean knownIdle,
      long now) {
    if (!webActive || !knownIdle || starting || nativeWork || lan || interactive) idleSince = -1;
    else if (idleSince < 0 || now < idleSince) idleSince = now;
    if (!webActive) return false;
    return !eco
        || starting
        || interactive
        || lan
        || nativeWork
        || !knownIdle
        || idleSince < 0
        || now - idleSince < IDLE_GRACE_MS;
  }
}
