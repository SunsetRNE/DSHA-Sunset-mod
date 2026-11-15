package com.deepseekharness.app.plugins;

import java.io.File;
import java.util.Objects;

/** 已观察到的数据根、运行根与 Web 代次；不代表插件内容已完整校验。 */
public final class PluginSourceIdentity {
  public final File dataRoot, rootfs;
  public final String dataRootKey, rootfsKey, runtimeMarkerSha256;
  public final long dataRootDevice, rootfsDevice, webGeneration;

  public PluginSourceIdentity(
      File dataRoot,
      String dataRootKey,
      long dataRootDevice,
      File rootfs,
      String rootfsKey,
      long rootfsDevice,
      String runtimeMarkerSha256,
      long webGeneration) {
    this.dataRoot = Objects.requireNonNull(dataRoot).getAbsoluteFile();
    this.dataRootKey = Objects.requireNonNull(dataRootKey);
    this.dataRootDevice = dataRootDevice;
    this.rootfs = Objects.requireNonNull(rootfs).getAbsoluteFile();
    this.rootfsKey = Objects.requireNonNull(rootfsKey);
    this.rootfsDevice = rootfsDevice;
    this.runtimeMarkerSha256 = Objects.requireNonNull(runtimeMarkerSha256);
    this.webGeneration = webGeneration;
  }

  /** 仅复制预期的受控停止后身份；不能据此证明停止已完成。 */
  public PluginSourceIdentity withWebGeneration(long generation) {
    return new PluginSourceIdentity(
        dataRoot,
        dataRootKey,
        dataRootDevice,
        rootfs,
        rootfsKey,
        rootfsDevice,
        runtimeMarkerSha256,
        generation);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof PluginSourceIdentity)) return false;
    PluginSourceIdentity value = (PluginSourceIdentity) other;
    return sameDataSource(value) && webGeneration == value.webGeneration;
  }

  /** 仅用于保留同一用户目录的陈旧展示；不能用于执行凭据、查询票据或运行证据。 */
  public boolean sameUserDataRoot(PluginSourceIdentity value) {
    return value != null
        && dataRoot.equals(value.dataRoot)
        && dataRootKey.equals(value.dataRootKey)
        && dataRootDevice == value.dataRootDevice;
  }

  /** 只比较持久根；执行票据必须另核对 Registry 代次与精确 Web 观察，不能单用此方法放行。 */
  public boolean sameDataSource(PluginSourceIdentity value) {
    return value != null
        && dataRoot.equals(value.dataRoot)
        && dataRootKey.equals(value.dataRootKey)
        && dataRootDevice == value.dataRootDevice
        && rootfs.equals(value.rootfs)
        && rootfsKey.equals(value.rootfsKey)
        && rootfsDevice == value.rootfsDevice
        && runtimeMarkerSha256.equals(value.runtimeMarkerSha256);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        dataRoot,
        dataRootKey,
        dataRootDevice,
        rootfs,
        rootfsKey,
        rootfsDevice,
        runtimeMarkerSha256,
        webGeneration);
  }
}
