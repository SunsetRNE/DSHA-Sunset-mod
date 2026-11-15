package com.deepseekharness.app.device;

import com.deepseekharness.app.runtime.PrivilegedPackageContext;
import com.deepseekharness.app.util.RootModulePolicy;
import com.deepseekharness.app.util.SelfPackageIdentity;
import java.io.IOException;

/** 固定 F07 版本探针子入口；只 exec 当前枚举候选的已核 fd，不接受路径、命令或追加 argv。 */
public final class RootModuleProbeMain {
  private RootModuleProbeMain() {}

  interface Invocation {
    int executionUid();

    void verifyInstallation(String name, int uid) throws IOException;

    void execute(RootModulePolicy.Manager manager, RootModulePolicy.Probe probe) throws IOException;
  }

  /** 四个位置参数均由受管父 helper 生成；无 stdin shell、任意类名或文件目标。 */
  public static void main(String[] args) {
    try {
      invoke(
          args,
          new Invocation() {
            @Override
            public int executionUid() {
              return android.os.Process.myUid();
            }

            @Override
            public void verifyInstallation(String name, int uid) throws IOException {
              // 现有入口同时核验当前已装 sourceDir 与实际 java.class.path 精确相等。
              PrivilegedPackageContext.launchIdentity(name, uid);
            }

            @Override
            public void execute(RootModulePolicy.Manager manager, RootModulePolicy.Probe probe)
                throws IOException {
              RootModuleFiles.execProbe(manager, probe);
            }
          });
    } catch (Throwable rejected) {
      // 不回显异常正文、真实路径、安装信息、模块内容或上游输出。
      System.out.println("ROOT_MODULE_PROBE_REJECTED");
      System.out.flush();
      System.exit(126);
    }
  }

  static void invoke(String[] args, Invocation invocation) throws IOException {
    if (invocation.executionUid() != 0) throw new IOException("ROOT_IDENTITY_UNVERIFIED");
    if (args == null
        || args.length != 4
        || args[0] == null
        || args[0].length() > 160
        || !SelfPackageIdentity.validName(args[0])
        || args[1] == null
        || !args[1].matches("[1-9][0-9]{4,9}"))
      throw new IOException("ROOT_MODULE_PROBE_ARGUMENTS_INVALID");
    int uid;
    RootModulePolicy.Manager manager;
    RootModulePolicy.Probe probe;
    try {
      uid = Integer.parseInt(args[1]);
      manager = RootModulePolicy.Manager.valueOf(args[2]);
      probe = RootModulePolicy.Probe.valueOf(args[3]);
      // 在任何身份查询或文件访问前验证该 Manager/Probe 组合确为封闭表内选项。
      RootModulePolicy.flag(manager, probe);
    } catch (RuntimeException invalid) {
      throw new IOException("ROOT_MODULE_PROBE_ARGUMENTS_INVALID");
    }
    invocation.verifyInstallation(args[0], uid);
    invocation.execute(manager, probe);
    throw new IOException("ROOT_MODULE_PROBE_EXEC_RETURNED");
  }
}
