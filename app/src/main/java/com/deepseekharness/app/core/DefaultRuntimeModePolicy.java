package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.runtime.RuntimeModePolicy;

/** {@link RuntimeModePolicy} 的默认实现：落在受管配置存储上。 */
public final class DefaultRuntimeModePolicy implements RuntimeModePolicy {
  private final Context context;

  public DefaultRuntimeModePolicy(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public String containerRuntimeMode() {
    return new ConfigStore(context).containerRuntimeMode();
  }

  @Override
  public void resetToProot() {
    ConfigStore config = new ConfigStore(context);
    config.setRootRuntimeAuthorization(false);
    config.setContainerRuntimeMode("proot");
  }
}
