package com.deepseekharness.app.contract;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 随包可信内置模块登记：A1 只内置「关于与许可」卡片，ALWAYS_ON，不依赖任何资源或网络。
 *
 * <p>DEFERRED(A1)：法定许可/第三方许可聚合（AboutLicense）由宿主与关于页兜底，本类不新增聚合 API。
 */
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

  private static final Object LOCK = new Object();
  private static final DiagnosticsSink BUILTIN_DIAGNOSTICS = DiagnosticsSink.discarding();
  private static SharedPreferences cachedPreferences;
  private static ModuleRegistry cachedRegistry;

  private BuiltinModules() {}

  /**
   * 宿主启动入口：使用独立布局命名空间创建/复用进程内注册表，并登记全部内置模块。
   *
   * <p>同一 Context 再次调用返回同一 registry、SafeModeGate 与诊断 sink（布局状态共享）。
   */
  public static ModuleRegistry ensureRegistered(Context context) {
    if (context == null) throw new IllegalArgumentException("CONTEXT_REQUIRED");
    Context application = context.getApplicationContext();
    Context owner = application == null ? context : application;
    return ensureRegistered(
        owner.getSharedPreferences(SharedPreferencesLayoutStore.NAMESPACE, Context.MODE_PRIVATE));
  }

  /**
   * 同一 {@link SharedPreferences} 实例（同一进程/Context 的同一命名空间）复用同一 registry、SafeModeGate 与诊断 sink。
   *
   * <p>进程内只有一个缓存槽位；换 prefs 时旧 registry 被替换，保证同一时刻唯一。
   */
  static ModuleRegistry ensureRegistered(SharedPreferences preferences) {
    Objects.requireNonNull(preferences, "preferences");
    synchronized (LOCK) {
      if (cachedPreferences == preferences && cachedRegistry != null) return cachedRegistry;
      ModuleRegistry registry =
          new ModuleRegistry(
              new SharedPreferencesLayoutStore(preferences, BUILTIN_DIAGNOSTICS),
              new SafeModeGate(),
              BUILTIN_DIAGNOSTICS);
      ensureRegistered(registry);
      cachedPreferences = preferences;
      cachedRegistry = registry;
      return registry;
    }
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
