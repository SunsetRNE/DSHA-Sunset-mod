package com.deepseekharness.app.contract;

import java.util.Objects;
import java.util.Set;

/**
 * 模块声明：纯数据，不读文件/网络/配置。
 *
 * <p>{@code capabilities} 只是“会请求”的声明，授权派发仍由宿主能力层判定；本类不提供权限。
 */
public record ModuleDescriptor(
    ModuleId id, int version, String name, Set<String> capabilities, Enablement enablement) {
  public enum Enablement {
    ALWAYS_ON,
    OPTIONAL_USER
  }

  public ModuleDescriptor {
    Objects.requireNonNull(id, "id");
    if (version < 1) throw new IllegalArgumentException("MODULE_VERSION_INVALID");
    if (name == null || name.isBlank() || name.length() > 200)
      throw new IllegalArgumentException("MODULE_NAME_INVALID");
    Objects.requireNonNull(capabilities, "capabilities");
    for (String capability : capabilities) {
      if (capability == null || capability.isBlank() || capability.length() > 128)
        throw new IllegalArgumentException("MODULE_CAPABILITY_INVALID");
    }
    capabilities = Set.copyOf(capabilities);
    Objects.requireNonNull(enablement, "enablement");
  }

  public boolean alwaysOn() {
    return enablement == Enablement.ALWAYS_ON;
  }
}
