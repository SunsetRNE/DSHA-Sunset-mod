package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 容器 SSH 的接线契约：服务必须在 Manifest 里注册为 specialUse 前台服务；start() 的
 * 各个前置条件（资产、开关、密码）按顺序拒绝，缺任何一环都不能把启动请求送给系统。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public final class ContainerSshWiringTest {

  @Test
  public void serviceIsDeclaredAsNonExportedSpecialUseForeground() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    ServiceInfo info =
        context
            .getPackageManager()
            .getServiceInfo(new ComponentName(context, ContainerSshService.class), 0);
    assertFalse("监督服务不对外暴露", info.exported);
    assertEquals(
        "Android 14+ 前台服务类型必须是 specialUse",
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        info.getForegroundServiceType());
  }

  @Test
  public void startRefusesEveryMissingPrerequisiteBeforeReachingTheSystem() {
    Context context = RuntimeEnvironment.getApplication();
    // 单测环境合并了主 APK 资产，supported() 依环境而定，不在此断言；门控顺序是契约：
    // 默认关闭 → false；开了开关但没密码 → false。两步都不触碰 HarnessController 或
    // startForegroundService。
    assertFalse(ContainerSshService.start(context));
    ContainerSshSettings.setEnabled(context, true);
    assertFalse(ContainerSshService.start(context));
    assertFalse(ContainerSshService.isActive());
  }
}
