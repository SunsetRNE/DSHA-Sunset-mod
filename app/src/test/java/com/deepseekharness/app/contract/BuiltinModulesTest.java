package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** 内置 about 模块：ALWAYS_ON、CARD id=about、双语标题、幂等登记、重启恢复布局。 */
public class BuiltinModulesTest {
  @Test
  public void registersAboutAsAlwaysOnCardWithBilingualTitle() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    ModuleRegistry registry = ContractTestSupport.registry(preferences, new SafeModeGate(), events);

    assertTrue(BuiltinModules.ensureRegistered(registry).accepted());
    assertTrue(registry.isRegistered(BuiltinModules.ABOUT_MODULE_ID));
    assertTrue(registry.isInstalled(BuiltinModules.ABOUT_MODULE_ID));
    assertEquals(1, registry.contributions().size());

    ContributionView about = registry.contributions().get(0);
    assertEquals("about", BuiltinModules.ABOUT_CONTRIBUTION_ID);
    assertEquals(BuiltinModules.ABOUT_CONTRIBUTION_KEY, about.key());
    assertEquals(Contribution.Kind.CARD, about.kind());
    assertEquals("关于与许可", about.title());
    assertEquals("About and licenses", about.titleEn());
    assertFalse(about.hidden());
    assertEquals(
        ModuleDescriptor.Enablement.ALWAYS_ON,
        registry.descriptor(BuiltinModules.ABOUT_MODULE_ID).enablement());
    assertEquals(Set.of(), registry.descriptor(BuiltinModules.ABOUT_MODULE_ID).capabilities());
    assertEquals(
        ContractCodes.MODULE_ALWAYS_ON,
        registry.setEnabled(BuiltinModules.ABOUT_MODULE_ID, false).code());
  }

  @Test
  public void ensureRegisteredIsIdempotentAndRestoresHiddenLayout() {
    ContractTestSupport.FakeSharedPreferences preferences =
        new ContractTestSupport.FakeSharedPreferences();
    ModuleRegistry first = BuiltinModules.ensureRegistered(preferences);
    assertEquals(1, first.contributions().size());
    assertTrue(first.setContributionHidden(BuiltinModules.ABOUT_CONTRIBUTION_KEY, true).accepted());

    ModuleRegistry second = BuiltinModules.ensureRegistered(preferences);
    assertSame(first, second);
    assertSame(first.safeModeGate(), second.safeModeGate());
    assertEquals(1, second.contributions().size());
    assertTrue(second.contributions().get(0).hidden());
    assertTrue(BuiltinModules.ensureRegistered(second).accepted());
    assertEquals(1, second.contributions().size());

    ModuleRegistry other =
        BuiltinModules.ensureRegistered(new ContractTestSupport.FakeSharedPreferences());
    assertNotSame(first, other);
  }
}
