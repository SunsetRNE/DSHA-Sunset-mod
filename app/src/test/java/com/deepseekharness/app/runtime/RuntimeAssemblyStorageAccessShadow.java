package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.StorageAccess;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * 组装黄金只冻结“未授权公共存储”的 argv 形态。Robolectric 的卷列表与真实设备不同，
 * 固定返回未授权，避免宿主存储配置进入运行方式身份。
 */
@Implements(value = StorageAccess.class, isInAndroidSdk = false)
public final class RuntimeAssemblyStorageAccessShadow {
  @Implementation
  protected static boolean granted(Context context) {
    return false;
  }

  private RuntimeAssemblyStorageAccessShadow() {}
}
