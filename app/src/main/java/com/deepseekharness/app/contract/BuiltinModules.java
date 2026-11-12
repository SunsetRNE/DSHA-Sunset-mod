package com.deepseekharness.app.contract;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Set;

/** 随包可信内置模块登记：A1 只内置「关于与许可」卡片，ALWAYS_ON，不依赖任何资源或网络。 */
public final class BuiltinModules {
  public static final ModuleId ABOUT_MODULE_ID = new ModuleId("builtin.about");
  public static final String ABOUT_CONTRIBUTION_ID = "about";
  public static final String ABOUT_CONTRIBUTION_KEY =
      ABOUT_MODULE_ID.value() + "#" + ABOUT_CONTRIBUTION_ID;
  public static final String ABOUT_TITLE = "关于与许可";
  public static final String ABOUT_TITLE_EN = "About and licenses";
  public static final ModuleDescriptor ABOUT_DESCRIPTOR =
      new ModuleDescriptor(
          ABOUT_MODULE_ID, 1, ABOUT_TITLE, Set.of(), ModuleDescriptor.Enablement.ALWAYS_ON);

  private BuiltinModules() {}

  /**
   * 宿主启动入口：使用独立布局命名空间创建注册表，并登记全部内置模块。
   *
   * <p>只返回登记表，不持有全局状态；需要重启恢复时再次调用即可从同一 prefs 读回布局。
   */
  public static ModuleRegistry ensureRegistered(Context context) {
    if (context == null) throw new IllegalArgumentException("CONTEXT_REQUIRED");
    ModuleRegistry registry =
        new ModuleRegistry(
            SharedPreferencesLayoutStore.open(context),
            new SafeModeGate(),
            DiagnosticsSink.discarding());
    ensureRegistered(registry);
    return registry;
  }

  /** 测试/宿主自建注册表的便捷入口；与 Context 入口使用同一布局命名空间语义。 */
  static ModuleRegistry ensureRegistered(SharedPreferences preferences) {
    ModuleRegistry registry =
        new ModuleRegistry(
            new SharedPreferencesLayoutStore(preferences),
            new SafeModeGate(),
            DiagnosticsSink.discarding());
    ensureRegistered(registry);
    return registry;
  }

  /** 幂等登记内置 about 模块；重复调用不重复注册或安装。 */
  public static ContractResult ensureRegistered(ModuleRegistry registry) {
    if (registry == null) throw new IllegalArgumentException("REGISTRY_REQUIRED");
    if (registry.isRegistered(ABOUT_MODULE_ID)) {
      return registry.isInstalled(ABOUT_MODULE_ID)
          ? ContractResult.ok()
          : registry.install(ABOUT_MODULE_ID);
    }
    ContractResult registered = registry.register(ABOUT_DESCRIPTOR, BuiltinModules::aboutInstance);
    if (registered.rejected()) return registered;
    return registry.install(ABOUT_MODULE_ID);
  }

  private static ModuleInstance aboutInstance() {
    return new ModuleInstance() {
      private final List<Contribution> contributions =
          List.of(
              Contribution.card(
                  ABOUT_MODULE_ID, ABOUT_CONTRIBUTION_ID, ABOUT_TITLE, ABOUT_TITLE_EN, 0));

      @Override
      public List<Contribution> contributions() {
        return contributions;
      }
    };
  }
}
