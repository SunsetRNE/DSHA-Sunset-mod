package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.AndroidPluginOperationLock;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.util.ColdInstallPackages;
import java.io.File;
import java.io.IOException;

/** 正常启动的旧审阅标记空操作在壳内实时验证；所有实际迁移继续交原 guest 事务。 */
public final class HostLegacyPluginCheck {
  private HostLegacyPluginCheck() {}

  /** null 表示走原入口；IOException 仅表示锁释放不明，调用者必须停止而非重放。 */
  public static String check(Context context, File requestedRoot) throws IOException {
    AndroidPluginOperationLock lease = null;
    try {
      if (Thread.currentThread().isInterrupted()) return null;
      var fs = new AndroidBackupFileSystem();
      var host =
          ColdInstallPackages.bindPrivateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      File root = RuntimeAssetFiles.root(context, requestedRoot);
      if (!fs.child(host.files, "linux/ubuntu").equals(root)) return null;
      var rootIdentity = fs.stat(root);
      var layout = new UserDataLayout(fs, host.files);
      File home = layout.current();
      // child 对每一级执行 NOFOLLOW；只允许当前受管私有布局，不解析 guest 链接。
      home =
          fs.child(host.files, RuntimeAssetFiles.relative(home, context.getFilesDir(), host.files));
      File profile = fs.child(home, "profiles/web");
      lease = AndroidPluginOperationLock.tryAcquire(fs, fs.child(root, "root"), profile);
      if (lease == null) return null;
      if (!home.equals(layout.current())
          || !LegacyPluginReviewCheck.noLegacyReviews(fs, host.files, home)) return null;
      lease.verify();
      host.verify(fs);
      if (!rootIdentity.same(fs.stat(root)) || !home.equals(layout.current())) return null;
      return "PLUGIN_RESULT: {\"status\":\"ok\",\"message\":\"已启用 0 个旧版待审阅插件\","
          + "\"activated\":[],\"skipped\":[],\"hostVerified\":true}\n";
    } catch (IOException | RuntimeException unavailable) {
      // 真实字节、类型、父路径或本次快照不明时保留原 Python 行为。
      // 获取锁中的释放错误必须向上传递，不能被普通回退吞掉。
      if (unavailable instanceof IOException
          && "PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED".equals(unavailable.getMessage()))
        throw (IOException) unavailable;
      return null;
    } finally {
      if (lease != null) lease.close();
    }
  }
}
