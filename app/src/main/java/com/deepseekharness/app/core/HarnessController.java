package com.deepseekharness.app.core;

import android.content.Context;
import android.util.Log;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.RuntimeHostPorts;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.WebStartProtection;
import java.io.IOException;
import java.util.function.Consumer;

/** Application entry point. Collaborators share its one application-owned Web session. */
public class HarnessController {
  private final Context context;
  private final ConfigStore config;
  private final ProotBootstrap proot;
  private final WebLifecycleController web;
  private final HostConfigService hostConfig;

  public HarnessController(Context context) {
    this(context, new ProotBootstrap(context));
  }

  HarnessController(Context context, ProotBootstrap bootstrap) {
    this.context = context.getApplicationContext();
    this.config = new ConfigStore(this.context);
    this.proot = bootstrap;
    HarnessSessionState session =
        this.context instanceof DshaApp
            ? ((DshaApp) this.context).harnessSession()
            : new HarnessSessionState();
    this.hostConfig = new HostConfigService(this.context, config);
    this.web = new WebLifecycleController(this, session);
  }

  /** Compatibility resolver; the application owns the service and there is no static registry. */
  public static HarnessController get(Context context) {
    return DshaApp.from(context).harnessController();
  }

  public Context context() {
    return context;
  }

  public ConfigStore config() {
    return config;
  }

  public ProotBootstrap proot() {
    return proot;
  }

  public RuntimeHostPorts runtimeHostPorts() {
    return proot.hostPorts();
  }

  public boolean isEnvironmentReady() {
    return proot.isEnvironmentReady();
  }

  public boolean hasOfflineBundle() {
    return proot.hasOfflineBundle();
  }

  public boolean isWebRunning() {
    return web.isWebRunning();
  }

  /** 只读恢复已有实例的连接；不启动后端或重放业务。 */
  public void restoreRunningWebSession(Runnable completed) {
    web.restoreRunningWebSession(completed);
  }

  public boolean isRestoringWebSession() {
    return web.isRestoringWebSession();
  }

  public WebSessionRestoreText.Status webSessionRestoreStatus() {
    return web.webSessionRestoreStatus();
  }

  public boolean needsWebSessionRestore() {
    return web.needsWebSessionRestore();
  }

  public boolean hasUnresolvedWebSession() {
    return web.hasUnresolvedWebSession();
  }

  public void refreshRecoveredWebSession() {
    web.refreshRecoveredWebSession();
  }

  public void requestWebSessionRecheck(Runnable completed) {
    web.requestWebSessionRecheck(completed);
  }

  public boolean isWebSessionRetained(PreviewPageSession.Identity expected) {
    return web.isWebSessionRetained(expected);
  }

  public boolean isWebSessionExited(PreviewPageSession.Identity expected) {
    return web.isWebSessionExited(expected);
  }

  public boolean isWebStoppedForMaintenance() throws IOException {
    return web.isWebStoppedForMaintenance();
  }

  public boolean observeWebStopped() throws IOException {
    return web.observeWebStopped();
  }

  public boolean hasLiveWebProcesses() {
    return web.hasLiveWebProcesses();
  }

  public String lastStopError() {
    return web.lastStopError();
  }

  public WebStopCoordinator.Result lastStopResult() {
    return web.lastStopResult();
  }

  /** 停止阶段与已用预算的展示文本；空串表示不在停止中。 */
  public String stopProgressText() {
    return web.stopProgressText();
  }

  public int getWebPort() {
    return web.getWebPort();
  }

  public String getWebAuthUrl() {
    return web.getWebAuthUrl();
  }

  public PreviewPageSession.Identity getReadyWebPageIdentity() {
    return web.getReadyWebPageIdentity();
  }

  public boolean isOrdinaryWebPage(PreviewPageSession.Identity expected) {
    return web.isOrdinaryWebPage(expected);
  }

  public com.deepseekharness.app.util.AgentTaskRegistration.Snapshot getReadyTaskRegistration() {
    return web.getReadyTaskRegistration();
  }

  public void addReadyWebPageListener(Runnable listener) {
    web.addReadyWebPageListener(listener);
  }

  public void removeReadyWebPageListener(Runnable listener) {
    web.removeReadyWebPageListener(listener);
  }

  public String getWebAuthFailure() {
    return web.getWebAuthFailure();
  }

  public String runCoreCommand() {
    return web.runCoreCommand();
  }

  public boolean startWeb(Consumer<String> status) {
    return web.startWeb(status);
  }

  public boolean startWebSafely(Consumer<String> status) {
    return web.startWebSafely(status);
  }

  /** A queued visible-page request cannot supersede a later Stop or another start. */
  public boolean startWebIfUnchanged(
      long expectedGeneration, boolean manual, Consumer<String> status) {
    return web.startWebIfUnchanged(expectedGeneration, manual, status);
  }

  public WebStartProtection webStartProtection() {
    return web.startProtection();
  }

  public boolean startProtectedWeb(
      WebStartProtection.Ticket ticket, boolean safe, boolean recover, Consumer<String> status) {
    return web.startProtectedWeb(ticket, safe, recover, status);
  }

  /** Pure intent/session state only; it never probes or signals a process. */
  public boolean requiresRuntimeProtection() {
    return web.requiresRuntimeProtection();
  }

  public boolean restartWebAutomatically(long expectedGeneration, Consumer<String> status) {
    return web.restartWebAutomatically(expectedGeneration, status);
  }

  public boolean isWebCompatibilityFallback() {
    return web.isWebCompatibilityFallback();
  }

  public String exchangeDshAuthCookie() {
    return web.exchangeDshAuthCookie();
  }

  public void stopWeb() {
    web.stopWeb();
  }

  public void stopWeb(Consumer<String> status) {
    web.stopWeb(status);
  }

  void stopWebDuringMaintenance() {
    web.stopWebDuringMaintenance();
  }

  public void stopWebIfUnchanged(long generation) {
    web.stopWebIfUnchanged(generation);
  }

  /** Candidate held by one native confirmation; it cannot be reused after any new Web intent. */
  public record ForceStopRequest(long generation, WebProcessManager.ForceCandidate candidate) {}

  public ForceStopRequest prepareForceStop() throws IOException {
    return web.prepareForceStop();
  }

  public boolean forceStop(ForceStopRequest request, Consumer<String> status) {
    return web.forceStop(request, status);
  }

  public boolean isStarting() {
    return web.isStarting();
  }

  public boolean isStopping() {
    return web.isStopping();
  }

  public boolean isUserStopped() {
    return web.isUserStopped();
  }

  public boolean canAutoRestart() {
    return web.canAutoRestart();
  }

  public boolean isRestartBlocked() {
    return web.isRestartBlocked();
  }

  public void reportWebHealth(long generation, boolean healthy) {
    web.reportWebHealth(generation, healthy);
  }

  public StartupDiagnostics startupDiagnostics() {
    return web.startupDiagnostics();
  }

  public void recoverWeb(boolean safe, String disabledPlugin, Consumer<String> status) {
    web.recoverWeb(safe, disabledPlugin, status);
  }

  public boolean recoverWebIfUnchanged(
      long expectedGeneration, boolean safe, String disabledPlugin, Consumer<String> status) {
    return web.recoverWebIfUnchanged(expectedGeneration, safe, disabledPlugin, status);
  }

  public void failedWebPage(long generation, String reason) {
    web.failedWebPage(generation, reason);
  }

  void reportSlowStart(long generation, Consumer<String> status) {
    web.reportSlowStart(generation, status);
  }

  public long getWebGeneration() {
    return web.getWebGeneration();
  }

  public String resetConfig() throws IOException {
    return hostConfig.reset();
  }

  com.deepseekharness.app.backup.HostDataTransaction.Settings nativeSettingsTransaction() {
    return hostConfig.settings();
  }

  public static String fmtBytes(long bytes) {
    return com.deepseekharness.app.util.Fmt.bytes(bytes);
  }

  public void logActivity(String value) {
    Log.i("DSHA", value == null ? "" : value);
  }
}
