package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

/**
 * 信任状态是承诺不是装饰：没有签名、或签名密钥未被用户信任时，绝不能出现「已验证」。
 *
 * <p>这一组测试锁住诚实性 —— 作者自述与摘要都不能自我升级为已验证；签名与信任的
 * 正向路径由 {@link ModuleSignatureTest} 覆盖。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleTrustTest {

  @Test
  public void statusIsUnverifiedForAnySelfClaimedAuthor() {
    // 作者自述是谁都一样：没有签名机制，就没有已验证状态
    for (String author : new String[] {"local", "google", "official", "someone@mail"}) {
      ModuleCard card =
          ModuleCard.parse(
              "{\"schema\":1,\"author\":\""
                  + author
                  + "\","
                  + "\"id\":\"x.y\",\"name\":\"n\",\"actions\":[]}");
      assertEquals(ModuleTrust.UNVERIFIED, ModuleTrust.statusOf(card));
      assertFalse(ModuleTrust.isVerified(ModuleTrust.statusOf(card)));
    }
  }

  @Test
  public void digestNeverPromotesTrust() {
    // 有摘要也一样：SHA-256 只证明内容一致，不证明作者身份
    ModuleCard card =
        ModuleCard.parse("{\"schema\":1,\"id\":\"x.y\",\"name\":\"n\",\"actions\":[]}");
    assertFalse(card.digest.isEmpty());
    assertEquals(ModuleTrust.UNVERIFIED, ModuleTrust.statusOf(card));
  }

  @Test
  public void nullCardIsUnverifiedToo() {
    assertEquals(ModuleTrust.UNVERIFIED, ModuleTrust.statusOf((ModuleCard) null));
    assertFalse(ModuleTrust.isVerified(ModuleTrust.UNVERIFIED));
  }
}
