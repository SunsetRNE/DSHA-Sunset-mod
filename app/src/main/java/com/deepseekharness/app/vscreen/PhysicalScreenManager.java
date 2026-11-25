package com.deepseekharness.app.vscreen;

import android.content.Context;
import com.deepseekharness.app.device.DeviceTransportResult;
import com.deepseekharness.app.util.PrivilegedScreenRoutes;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Device-channel status and configuration. Physical window observations use the authorized
 * accessibility route.
 */
public final class PhysicalScreenManager {
  public record Access(
      com.deepseekharness.app.control.ControlTaskAuthority.Lease lease,
      java.util.function.BooleanSupplier live,
      java.util.function.Function<String, String> targetRejection) {
    String rejection(String pkg) {
      if (!live.getAsBoolean()) return "PRIV_SCREEN_AUTHORITY_CHANGED";
      if (pkg == null || pkg.isEmpty()) return "PRIV_TARGET_UNVERIFIABLE";
      return targetRejection.apply(pkg);
    }
  }

  interface ChannelChooser {
    VirtualScreenChannel choose(Context context);
  }

  interface Transport {
    JSONObject request(VirtualScreenHandshake endpoint, String route, String query);
  }

  private static volatile PhysicalScreenManager shared;

  private final Context app;
  private final ChannelChooser channels;
  private final Transport transport;
  private final Object LOCK = new Object();

  private VirtualScreenHandshake endpoint;
  private String channelId = "";
  private String coreInstance = "";
  private String generation = "";
  private String packageName = "";
  private boolean packageVerified;
  private String lastError = "";

  PhysicalScreenManager(Context app, ChannelChooser channels, Transport transport) {
    this.app = app;
    this.channels = channels;
    this.transport = transport;
  }

  public static PhysicalScreenManager from(Context context) {
    PhysicalScreenManager value = shared;
    if (value != null) return value;
    synchronized (PhysicalScreenManager.class) {
      if (shared == null) {
        Context app = context.getApplicationContext();
        VirtualScreenTransport transport = new VirtualScreenTransport();
        shared = new PhysicalScreenManager(app, VirtualScreenChannels::choose, transport::request);
      }
      return shared;
    }
  }

  public boolean running() {
    synchronized (LOCK) {
      return endpoint != null;
    }
  }

  public String channel() {
    synchronized (LOCK) {
      return channelId;
    }
  }

  public String generation() {
    synchronized (LOCK) {
      return generation;
    }
  }

  public String targetPackage() {
    synchronized (LOCK) {
      return packageName;
    }
  }

  public boolean targetVerified() {
    synchronized (LOCK) {
      return packageVerified;
    }
  }

  public String lastError() {
    synchronized (LOCK) {
      return lastError;
    }
  }

  /** 取状态；首次调用会拉起 Core。 */
  public JSONObject info(Access access) {
    synchronized (LOCK) {
      JSONObject started = ensureStartedLocked(access);
      if (started != null) return started;
      return performLocked(access, "/priv/info", new LinkedHashMap<>());
    }
  }

  /** No privileged protocol operation has a window-scoped pixel source. */
  public JSONObject windowSourceRequired(Access access) {
    synchronized (LOCK) {
      if (!access.live.getAsBoolean())
        return refused("PRIV_SCREEN_AUTHORITY_CHANGED", "BEGIN_THE_PHONE_TASK_FIRST");
      return refused(
          PrivilegedScreenRoutes.WINDOW_SOURCE_REQUIRED,
          PrivilegedScreenRoutes.windowSourceNextAction(android.os.Build.VERSION.SDK_INT));
    }
  }

  /** 帧通道可调参数（尺寸/质量/最大帧率）；变化会重建镜像并更换显示代次。 */
  public JSONObject configure(
      Access access, Integer maxWidth, Integer jpegQuality, Integer maxFps) {
    synchronized (LOCK) {
      JSONObject started = ensureStartedLocked(access);
      if (started != null) return started;
      Map<String, String> params = new LinkedHashMap<>();
      if (maxWidth != null) params.put("maxWidth", String.valueOf(maxWidth));
      if (jpegQuality != null) params.put("jpegQuality", String.valueOf(jpegQuality));
      if (maxFps != null) params.put("maxFps", String.valueOf(maxFps));
      return performLocked(access, "/priv/config", params);
    }
  }

  /** Last check after the bridge has encoded the payload but before it writes any bytes. */
  public boolean resultCurrent(Access access, String pkg, String resultGeneration) {
    synchronized (LOCK) {
      boolean current =
          endpoint != null && access.rejection(pkg) == null && generation.equals(resultGeneration);
      if (current) {
        JSONObject status = transport.request(endpoint, "/priv/info", "");
        current =
            status != null
                && status.optBoolean("ok")
                && status.optBoolean("packageVerified")
                && pkg.equals(status.optString("package"))
                && resultGeneration.equals(status.optString("generation"))
                && coreInstance.equals(status.optString("coreInstance"))
                && access.rejection(pkg) == null;
      }
      return current;
    }
  }

  /** 显式关闭；失败按 UNKNOWN 报告，不重试。 */
  public JSONObject close(Access access) {
    synchronized (LOCK) {
      if (endpoint == null) return completed();
      JSONObject value = performLocked(access, "/priv/close", new LinkedHashMap<>());
      endpoint = null;
      channelId = "";
      coreInstance = "";
      generation = "";
      return value;
    }
  }

  /** Core 被停止/卸载或用户撤销能力时丢弃本地连接；不向旧 Core 发送任何东西。 */
  public void revoke() {
    synchronized (LOCK) {
      endpoint = null;
      channelId = "";
      coreInstance = "";
      generation = "";
      packageName = "";
      packageVerified = false;
    }
  }

  // ---------------------------------------------------------------------------------------------

  private JSONObject ensureStartedLocked(Access access) {
    if (!access.live.getAsBoolean())
      return refused("PRIV_SCREEN_AUTHORITY_CHANGED", "OBSERVE_THE_PHYSICAL_SCREEN_AGAIN");
    if (endpoint != null) return null;
    VirtualScreenChannel channel = channels.choose(app);
    if (channel == null)
      return refused("PRIV_CHANNEL_UNAVAILABLE", "ENABLE_ROOT_SHIZUKU_OR_ADB_CHANNEL");
    String command = new PhysicalScreenLauncher().launchCommand(app);
    DeviceTransportResult result;
    try {
      result = channel.startTyped(app, command);
    } catch (Throwable error) {
      return unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY");
    }
    if (result.outcome.state
        != com.deepseekharness.app.capabilities.CapabilityResult.State.COMPLETED) {
      boolean unknown =
          result.outcome.state
              == com.deepseekharness.app.capabilities.CapabilityResult.State.UNKNOWN;
      lastError = result.outcome.code;
      return unknown
          ? unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY")
          : refused(
              result.outcome.code.isEmpty() ? "PRIV_CORE_START_FAILED" : result.outcome.code,
              "ENABLE_ROOT_SHIZUKU_OR_ADB_CHANNEL");
    }
    VirtualScreenHandshake handshake = VirtualScreenHandshake.started(result.output);
    if (handshake == null) {
      lastError = "PRIV_CORE_HANDSHAKE_MISSING";
      return unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY");
    }
    endpoint = handshake;
    channelId = channel.id();
    JSONObject probe = transport.request(handshake, "/priv/info", "");
    if (probe == null || !probe.optBoolean("ok")) {
      lastError =
          probe == null
              ? "PRIV_CORE_UNREACHABLE"
              : probe.optString("error", "PRIV_CORE_UNREACHABLE");
      return unknown("PRIV_CORE_START_UNKNOWN", "VERIFY_DEVICE_STATE_BEFORE_ANY_RETRY");
    }
    remember(probe);
    return null;
  }

  private JSONObject performLocked(Access access, String route, Map<String, String> params) {
    if (!access.live.getAsBoolean())
      return refused("PRIV_SCREEN_AUTHORITY_CHANGED", "BEGIN_THE_PHONE_TASK_FIRST");
    if (endpoint == null)
      return refused("PRIV_CORE_NOT_RUNNING", "CALL_PRIV_INFO_TO_START_THE_CORE_AGAIN");
    JSONObject response = transport.request(endpoint, route, encode(params));
    if (response == null) return unreachableLocked();
    if (!response.optBoolean("ok")) {
      String code = response.optString("error", "PRIV_CORE_RESPONSE_INVALID");
      if (isUnreachable(code)) return unreachableLocked();
      if (!code.matches("[A-Z][A-Z_0-9]{0,79}")) code = "PRIV_CORE_RESPONSE_INVALID";
      return refused(code, "CHECK_THE_DEVICE_STATE");
    }
    if (!access.live.getAsBoolean())
      return refused("PRIV_SCREEN_AUTHORITY_CHANGED", "BEGIN_THE_PHONE_TASK_FIRST");
    boolean info = "/priv/info".equals(route);
    if (info) {
      String actual = response.optBoolean("packageVerified") ? response.optString("package") : "";
      String reason = access.rejection(actual);
      if (reason != null) return refused(reason, "CHECK_THE_AUTHORIZED_TARGET_APP");
      remember(response);
    }
    // Older cores may send image or observation data. Export only typed metadata for this route.
    JSONObject value = completed();
    try {
      value
          .put("channel", channelId)
          .put("retryable", false)
          .put("coreInstance", coreInstance)
          .put("generation", generation);
      if (info) {
        value
            .put("active", true)
            .put("physical", true)
            .put("displayId", 0)
            .put("package", packageName)
            .put("packageVerified", packageVerified)
            .put("observationUnavailable", PrivilegedScreenRoutes.WINDOW_SOURCE_REQUIRED)
            .put(
                "nextAction",
                PrivilegedScreenRoutes.windowSourceNextAction(android.os.Build.VERSION.SDK_INT));
        for (String key :
            new String[] {"width", "height", "screenWidth", "screenHeight", "densityDpi"})
          if (response.has(key)) value.put(key, response.optInt(key));
      }
      if (info || "/priv/config".equals(route)) {
        JSONObject reported = response.optJSONObject("frameConfig");
        if (reported != null) {
          JSONObject config = new JSONObject();
          for (String key : new String[] {"maxWidth", "jpegQuality", "maxFps"})
            if (reported.has(key)) config.put(key, reported.optInt(key));
          value.put("frameConfig", config);
        }
      }
      return value;
    } catch (org.json.JSONException invalid) {
      return refused("PRIV_CORE_RESPONSE_INVALID", "CHECK_THE_DEVICE_STATE");
    }
  }

  private boolean isUnreachable(String code) {
    return "VSCREEN_TRANSPORT_UNAVAILABLE".equals(code)
        || "VSCREEN_NOT_RUNNING".equals(code)
        || "INPUT_RESULT_UNKNOWN".equals(code);
  }

  private JSONObject unreachableLocked() {
    endpoint = null;
    lastError = "PRIV_CORE_UNREACHABLE";
    return refused("PRIV_CORE_UNREACHABLE", "CALL_PRIV_INFO_TO_START_THE_CORE_AGAIN");
  }

  private void remember(JSONObject receipt) {
    coreInstance = receipt.optString("coreInstance");
    generation = receipt.optString("generation");
    packageName = receipt.optString("package");
    packageVerified = receipt.optBoolean("packageVerified");
  }

  private JSONObject refused(String code, String nextAction) {
    JSONObject value = new JSONObject();
    try {
      value
          .put("ok", false)
          .put("state", "NOT_SENT")
          .put("error", code)
          .put("retryable", false)
          .put("automaticRetry", false)
          .put("accessibilityRequired", false)
          .put("nextAction", nextAction)
          .put("channel", channelId)
          .put("coreInstance", coreInstance)
          .put("generation", generation);
    } catch (Exception ignored) {
    }
    lastError = code;
    return value;
  }

  private JSONObject unknown(String code, String nextAction) {
    JSONObject value = new JSONObject();
    try {
      value
          .put("ok", false)
          .put("state", "EXECUTION_UNKNOWN")
          .put("error", code)
          .put("executionUnknown", true)
          .put("retryable", false)
          .put("automaticRetry", false)
          .put("accessibilityRequired", false)
          .put("nextAction", nextAction)
          .put("channel", channelId);
    } catch (Exception ignored) {
    }
    lastError = code;
    return value;
  }

  private JSONObject completed() {
    JSONObject value = new JSONObject();
    try {
      value.put("ok", true).put("state", "COMPLETED").put("accessibilityRequired", false);
    } catch (Exception ignored) {
    }
    return value;
  }

  private static String encode(Map<String, String> query) {
    StringBuilder value = new StringBuilder();
    for (Map.Entry<String, String> entry : query.entrySet()) {
      if (value.length() > 0) value.append('&');
      try {
        value
            .append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
            .append('=')
            .append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
      } catch (java.io.UnsupportedEncodingException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    return value.toString();
  }
}
