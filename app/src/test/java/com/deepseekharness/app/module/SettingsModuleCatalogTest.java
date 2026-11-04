package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 设置目录只验证顺序、可见性和目标工厂，不启动页面业务。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsModuleCatalogTest {
  @Test
  public void standardCatalogKeepsExistingOrderAndHidesIsland() {
    var modules = SettingsModuleCatalog.visible(false);

    assertEquals(5, modules.size());
    assertEquals("settings.ssh", modules.get(4).id());
    assertEquals("settings.install", modules.get(0).id());
    assertEquals("settings.config", modules.get(1).id());
    assertEquals("settings.workspace", modules.get(2).id());
    assertEquals("settings.device-grants", modules.get(3).id());
    assertTrue(modules.stream().noneMatch(UiModuleDescriptor::islandOnly));
  }

  @Test
  public void islandCatalogAddsOnlyTheIslandEntryAtTheEnd() {
    var modules = SettingsModuleCatalog.visible(true);

    assertEquals(6, modules.size());
    assertEquals("settings.island", modules.get(5).id());
    assertTrue(modules.get(5).islandOnly());
  }

  @Test
  public void descriptorsCreateFreshFragmentsWithoutHoldingInstances() {
    var descriptor = SettingsModuleCatalog.visible(false).get(0);

    assertNotSame(descriptor.newFragment(), descriptor.newFragment());
    assertEquals(UiModuleDescriptor.DestinationType.FRAGMENT, descriptor.destinationType());
    assertFalse(descriptor.islandOnly());
  }
}
