import com.deepseekharness.app.util.RustNative;

/**
 * R0 主机 JVM 差分探针：加载主机目标 libdsha_rs.so，调用 abiVersion 并核对 not-implemented 占位返回码。
 *
 * <p>本探针不接入 Android 单测、不依赖 Robolectric、不产生生产行为；R1 起在同一骨架上扩展 digest/gzip/plan 的
 * JVM↔Rust 差分。运行参数：唯一参数是 .so 绝对路径。
 */
public final class RustHostProbe {
  private static int failures = 0;

  private RustHostProbe() {}

  private static void check(String name, boolean value, String detail) {
    System.out.println(
        "{\"check\":\"" + name + "\",\"status\":\"" + (value ? "PASS" : "FAIL") + "\""
            + (detail.isEmpty() ? "" : ",\"detail\":\"" + detail + "\"") + "}");
    if (!value) {
      failures++;
    }
  }

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("用法: RustHostProbe <libdsha_rs.so 绝对路径>");
      System.exit(2);
    }
    System.load(args[0]);
    int abi = RustNative.abiVersion();
    check("abiVersion", abi == RustNative.ABI_VERSION, "abi=" + abi);
    long notImplemented = RustNative.archivePackCreate(6);
    check("notImplementedStub", notImplemented == -26, "return=" + notImplemented);
    check("classPinned", RustNative.class.getName().equals("com.deepseekharness.app.util.RustNative"),
        RustNative.class.getName());
    System.out.println(
        "{\"status\":\""
            + (failures == 0 ? "PASS" : "FAIL")
            + "\",\"failures\":"
            + failures
            + ",\"javaVersion\":\""
            + System.getProperty("java.version")
            + "\"}");
    if (failures != 0) {
      System.exit(1);
    }
  }
}
