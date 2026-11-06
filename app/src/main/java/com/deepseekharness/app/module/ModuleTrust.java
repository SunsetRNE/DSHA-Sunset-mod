package com.deepseekharness.app.module;

import android.content.Context;

/**
 * 模块信任状态：签名有效且公钥受信才算「已验证」。
 *
 * <p>这里必须说实话：
 *
 * <ul>
 *   <li>{@code author} 是<b>作者自述</b> —— 任何人都可以写 "author":"google"，宿主无从核对，
 *       所以它不是身份，也不是信任依据。
 *   <li>{@code digest} 是宿主对原始 card.json 算的 SHA-256 —— 只证明「这份内容没有在
 *       安装后被改过」，不能证明「作者是谁」，更不是密码学签名。
 *   <li>现在支持分离的 Ed25519 签名（{@link ModuleSignature}），但只有公钥在
 *       {@link ModuleTrustStore} 里受信时才返回 {@link #VERIFIED}。
 * </ul>
 */
public final class ModuleTrust {

  /** 没有签名，或没有可验证内容。 */
  public static final String UNVERIFIED = "unverified";

  /** 有签名，但公钥不在信任库；界面必须显示「未受信」。 */
  public static final String UNTRUSTED = "untrusted_signature";

  /** 签名格式/内容不匹配；界面必须显示「签名无效」。 */
  public static final String INVALID = "invalid_signature";

  /** 签名有效且公钥受信。 */
  public static final String VERIFIED = "verified";

  /**
   * 「能做事」的能力（执行命令 / 运行程序 / 打开网页）是否要求已验证签名。
   *
   * <p>当前为 {@code false}，理由写在这里免得被误读成疏漏：信任库为空时强制
   * {@link #VERIFIED} 会让所有卡片（包括用户自己手写的）都无法执行，等于用一个还没
   * 就绪的密钥体系废掉功能。所以签名在本版是<b>来源标注</b>，真正的准入是逐卡内容
   * 摘要授权（{@code ModuleExec} / {@code ModuleProgramAuth} / {@code ModuleLifecycleAuth}）。
   *
   * <p>密钥体系接通（信任库有公钥、模块中心能核对指纹）后把它翻成 {@code true}，
   * 执行链随即只接受 {@link #VERIFIED}，不需要改别的代码。
   */
  public static final boolean EXEC_REQUIRES_VERIFIED = false;

  private ModuleTrust() {}

  /**
   * 不带上下文的旧入口：只回答「本对象自身是否携带已验证状态」。
   * 卡片对象本身不缓存签名结果，所以这里恒为 {@link #UNVERIFIED}。
   */
  public static String statusOf(ModuleCard card) {
    if (card == null) return UNVERIFIED;
    return UNVERIFIED;
  }

  /** 声明式清单当前同样没有签名机制。 */
  public static String statusOf(ModuleManifest manifest) {
    if (manifest == null) return UNVERIFIED;
    return UNVERIFIED;
  }

  /** 带上下文的真实检查：读 card.sig 并对照信任库。 */
  public static String statusOf(Context context, ModuleCard card) {
    if (context == null || card == null) return UNVERIFIED;
    ModuleSignature.Result result = ModuleSignature.inspect(context, card);
    switch (result.status) {
      case VERIFIED:
        return VERIFIED;
      case UNTRUSTED:
        return UNTRUSTED;
      case INVALID:
        return INVALID;
      default:
        return UNVERIFIED;
    }
  }

  public static ModuleSignature.Result inspect(Context context, ModuleCard card) {
    return ModuleSignature.inspect(context, card);
  }

  /** 人话状态；所有界面必须用这个，不得把摘要/作者名渲染成已验证。 */
  public static String statusLabel(String status, boolean zh) {
    if (VERIFIED.equals(status)) return zh ? "已验证" : "verified";
    if (UNTRUSTED.equals(status)) return zh ? "签名未受信" : "untrusted signature";
    if (INVALID.equals(status)) return zh ? "签名无效" : "invalid signature";
    return zh ? "未验证" : "unverified";
  }

  /** {@code true} 仅当状态确为「已验证」。 */
  public static boolean isVerified(String status) {
    return VERIFIED.equals(status);
  }
}
