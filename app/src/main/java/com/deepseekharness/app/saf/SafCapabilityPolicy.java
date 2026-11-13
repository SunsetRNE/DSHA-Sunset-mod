package com.deepseekharness.app.saf;

import com.deepseekharness.app.util.BridgePathPolicy;
import java.util.regex.Pattern;

/**
 * SAF 能力层的边界判定（纯逻辑，不 import Android API）。
 *
 * <p>两类输入，两条不同的边界：
 *
 * <ul>
 *   <li><b>tree URI</b>：只接受 {@code content://authority/tree/...}，拒绝自己的 DocumentsProvider
 *       （读取方与被读方同进程会形成自环），也拒绝超长/缺 authority 的字符串。真正授权范围仍由
 *       Android 系统持久 URI 授权决定，本类只做形状判据。</li>
 *   <li><b>容器侧本地路径</b>（导入/导出的另一半）：必须绝对、不得落在凭据区
 *       （{@code .dsh}/{@code .ssh}/{@code .android} 等），并在打开后用 canonical 真实路径复核，
 *       堵住「先建软链接指向凭据」的绕过。这里复用桥的 {@link BridgePathPolicy}，两条入口同一判据，
 *       不新增第二套拒绝表。</li>
 * </ul>
 *
 * <p><b>Provider 是信任边界</b>：本类不对目标应用里的文件内容做凭据识别，也不承诺阻止用户自己
 * 授权的文件夹里存在凭据副本。见 docs/security-model.md。
 */
public final class SafCapabilityPolicy {
  private static final Pattern AUTHORITY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

  public static final String LOCAL_NOT_ABSOLUTE = "LOCAL_NOT_ABSOLUTE";
  public static final String LOCAL_CREDENTIAL = "LOCAL_CREDENTIAL";
  public static final String LOCAL_OUTSIDE_ROOTFS = "LOCAL_OUTSIDE_ROOTFS";

  private SafCapabilityPolicy() {}

  /** tree URI 形状判定的结果；{@code OK} 之外都 fail-closed。 */
  public enum TreeUri {
    OK,
    MISSING,
    TOO_LONG,
    NOT_CONTENT,
    NOT_TREE,
    BAD_AUTHORITY,
    OWN_PROVIDER
  }

  /**
   * 判定一条 tree URI 是否可交给授权簿。
   *
   * @param uri 用户从系统 DocumentsUI 拿到的 tree URI 字符串
   * @param ownAuthority 本应用自己的 DocumentsProvider authority；可为 null（纯测试/未知调用方）
   */
  public static TreeUri treeUriProblem(String uri, String ownAuthority) {
    String lengthProblem = SafCapabilityLimits.uriProblem(uri);
    if (lengthProblem != null)
      return SafCapabilityLimits.URI_MISSING.equals(lengthProblem)
          ? TreeUri.MISSING
          : TreeUri.TOO_LONG;
    String value = uri.trim();
    if (!value.startsWith("content://")) return TreeUri.NOT_CONTENT;
    int authorityStart = "content://".length();
    int pathStart = value.indexOf('/', authorityStart);
    if (pathStart <= authorityStart) return TreeUri.BAD_AUTHORITY;
    String authority = value.substring(authorityStart, pathStart);
    if (!AUTHORITY.matcher(authority).matches()) return TreeUri.BAD_AUTHORITY;
    if (ownAuthority != null && ownAuthority.equals(authority)) return TreeUri.OWN_PROVIDER;
    String path = value.substring(pathStart);
    if (!path.startsWith("/tree/") || path.length() <= "/tree/".length()) return TreeUri.NOT_TREE;
    return TreeUri.OK;
  }

  /**
   * 容器侧入口路径的字符串判据。{@code null} 表示通过；否则返回稳定短码。
   *
   * <p>必须在打开前调用；打开后还要用 {@link #localCanonicalProblem} 复核真实路径。
   */
  public static String localSourceProblem(String rawGuestPath) {
    if (!BridgePathPolicy.isAbsolute(rawGuestPath)) return LOCAL_NOT_ABSOLUTE;
    // 桥的拒绝表同时覆盖凭据目录、相对路径与宿主私有数据；SAF 导入/导出沿用同一判据。
    if (BridgePathPolicy.denied(rawGuestPath)) return LOCAL_CREDENTIAL;
    return null;
  }

  /**
   * 已打开文件描述符的真实路径判据：软链接解析后的 canonical 必须仍在 rootfs 内，且不是凭据区。
   * {@code null} 表示通过；否则返回稳定短码。
   */
  public static String localCanonicalProblem(String canonical, String rootfsCanonical) {
    if (canonical == null || canonical.isEmpty()) return LOCAL_OUTSIDE_ROOTFS;
    // 拿不到 rootfs 时无法证明「仍在受控根内」：必须直接失败关闭。
    // 不能依赖 deniedGuestView——它对 /sdcard、/tmp 等 rootfs 外路径会返回 false（fail-open）。
    if (rootfsCanonical == null || rootfsCanonical.isBlank()) return LOCAL_OUTSIDE_ROOTFS;
    if (BridgePathPolicy.deniedGuestView(canonical, rootfsCanonical)) {
      String root = BridgePathPolicy.normalize(rootfsCanonical);
      String path = BridgePathPolicy.normalize(canonical);
      return BridgePathPolicy.startsWithPath(path, root) ? LOCAL_CREDENTIAL : LOCAL_OUTSIDE_ROOTFS;
    }
    return null;
  }

  /** tree 路径里是否含凭据名（仅用于界面提示/测试，不作为 Provider 内容安全保证）。 */
  public static boolean credentialPathName(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) return true;
    String value = relativePath.replace('\\', '/');
    while (value.startsWith("/")) value = value.substring(1);
    // 复用桥的拒绝表（凭据名 + .dsh/.ssh/.android 等目录），不在这里另立一张表。
    return BridgePathPolicy.denied("/" + value);
  }
}
