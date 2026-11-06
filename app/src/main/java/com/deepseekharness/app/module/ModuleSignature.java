package com.deepseekharness.app.module;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * 模块分离签名：Ed25519 覆盖「card.json + 全部素材」。
 *
 * <p>签名文件是卡片目录里的 {@code card.sig}，内容：
 * <pre>{"schema":1,"algorithm":"ed25519","keyId":"...","signature":"&lt;128 hex&gt;"}</pre>
 * 签名负载是确定性的：
 * <pre>DSHA-MODULE-V1\n&lt;card.json sha256&gt;\n&lt;素材名&gt; &lt;sha256&gt;\n…（按名字排序）</pre>
 * 签名只证明「内容与某把私钥对应」，是否信任这把公钥由 {@link ModuleTrustStore} 决定。
 */
public final class ModuleSignature {

  public static final String FILE = "card.sig";
  public static final String SCHEMA = "DSHA-MODULE-V1";

  public enum Status {
    MISSING,
    INVALID,
    UNTRUSTED,
    VERIFIED
  }

  public static final class Result {
    public final Status status;
    public final String keyId;
    public final String publicKey;
    public final String message;

    Result(Status status, String keyId, String publicKey, String message) {
      this.status = status;
      this.keyId = keyId == null ? "" : keyId;
      this.publicKey = publicKey == null ? "" : publicKey;
      this.message = message == null ? "" : message;
    }
  }

  private ModuleSignature() {}

  public static Result inspect(android.content.Context context, ModuleCard card) {
    if (context == null || card == null) return new Result(Status.MISSING, "", "", "没有模块");
    if (card.signature.isEmpty()) return new Result(Status.MISSING, "", "", "没有签名文件");
    File dir = new File(new File(context.getFilesDir(), "modules"), card.id);
    return inspect(dir, card, ModuleTrustStore.load(context));
  }

  static Result inspect(File dir, ModuleCard card, ModuleTrustStore store) {
    File cardFile = new File(dir, ModuleCardStore.CARD_FILE);
    File sigFile = new File(dir, card.signature);
    if (!cardFile.isFile() || !sigFile.isFile())
      return new Result(Status.INVALID, "", "", "签名或卡片文件缺失");
    try {
      JSONObject sig = new JSONObject(read(sigFile));
      if (sig.optInt("schema", 0) != 1 || !"ed25519".equals(sig.optString("algorithm")))
        return new Result(Status.INVALID, "", "", "签名格式只支持 schema 1 / ed25519");
      String keyId = sig.optString("keyId", "").trim();
      String signature = sig.optString("signature", "").trim();
      String claimedKey = sig.optString("publicKey", "").trim();
      if (!keyId.matches("[0-9a-f]{16,64}") || !signature.matches("[0-9a-fA-F]{128}"))
        return new Result(
            Status.INVALID,
            keyId,
            claimedKey,
            "签名内容格式不对：keyId="
                + keyId
                + " ("
                + keyId.length()
                + ")，signature="
                + signature.length()
                + " 位");
      String publicKey = store.publicKey(keyId);
      if (publicKey.isEmpty()) {
        // 首次看到签名时允许把公钥随签名文件带过来，用户核对指纹后可显式信任。
        if (!claimedKey.isEmpty() && !fingerprint(claimedKey).equals(keyId))
          return new Result(Status.INVALID, keyId, claimedKey, "签名公钥与 keyId 指纹不一致");
        return new Result(
            Status.UNTRUSTED,
            keyId,
            claimedKey,
            claimedKey.isEmpty() ? "签名密钥未受信，无法验证作者身份" : "签名密钥未受信，请核对指纹后信任");
      }
      if (!fingerprint(publicKey).equals(keyId))
        return new Result(Status.INVALID, keyId, publicKey, "keyId 与公钥指纹不一致");
      byte[] payload = payload(dir, card);
      if (!verify(payload, signature, publicKey))
        return new Result(Status.INVALID, keyId, publicKey, "签名与当前内容不匹配");
      return new Result(Status.VERIFIED, keyId, publicKey, "签名有效且公钥已受信");
    } catch (Exception error) {
      return new Result(Status.INVALID, "", "", "签名读取失败：" + error.getMessage());
    }
  }

  /** 计算签名负载；任何素材缺失都抛，缺一个文件就不能算完整模块。 */
  static byte[] payload(File dir, ModuleCard card) throws Exception {
    File cardFile = new File(dir, ModuleCardStore.CARD_FILE);
    StringBuilder builder = new StringBuilder(SCHEMA).append('\n');
    builder.append(sha256(cardFile)).append('\n');
    List<String> assets = new ArrayList<>();
    for (String name : card.assetNames())
      if (!name.equals(card.signature) && !name.isEmpty()) assets.add(name);
    Collections.sort(assets);
    for (String name : assets) {
      File file = new File(dir, name);
      if (!file.isFile()) throw new java.io.IOException("缺少素材：" + name);
      builder.append(name).append(' ').append(sha256(file)).append('\n');
    }
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** BouncyCastle Ed25519 验签；公钥 32 字节、签名 64 字节。 */
  public static boolean verify(byte[] payload, String signatureHex, String publicKeyHex) {
    try {
      byte[] publicKey = hex(publicKeyHex);
      byte[] signature = hex(signatureHex);
      if (publicKey.length != 32 || signature.length != 64) return false;
      org.bouncycastle.crypto.params.Ed25519PublicKeyParameters params =
          new org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKey, 0);
      org.bouncycastle.crypto.signers.Ed25519Signer signer =
          new org.bouncycastle.crypto.signers.Ed25519Signer();
      signer.init(false, params);
      signer.update(payload, 0, payload.length);
      return signer.verifySignature(signature);
    } catch (RuntimeException error) {
      return false;
    }
  }

  /** 公钥指纹：SHA-256(公钥字节) 前 16 位十六进制；也是签名里的 keyId。 */
  public static String fingerprint(String publicKeyHex) {
    try {
      byte[] key = hex(publicKeyHex);
      if (key.length != 32) return "";
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
      StringBuilder value = new StringBuilder(16);
      for (int i = 0; i < 8; i++)
        value.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
      return value.toString();
    } catch (Exception error) {
      return "";
    }
  }

  static byte[] hex(String value) {
    if (value == null || (value.length() & 1) != 0) return new byte[0];
    byte[] result = new byte[value.length() / 2];
    for (int i = 0; i < result.length; i++) {
      int high = Character.digit(value.charAt(i * 2), 16);
      int low = Character.digit(value.charAt(i * 2 + 1), 16);
      if (high < 0 || low < 0) return new byte[0];
      result[i] = (byte) ((high << 4) | low);
    }
    return result;
  }

  private static String sha256(File file) throws Exception {
    try (FileInputStream input = new FileInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
      StringBuilder value = new StringBuilder(64);
      for (byte item : digest.digest())
        value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
      return value.toString();
    }
  }

  private static String read(File file) throws Exception {
    byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
    if (bytes.length > 4096) throw new java.io.IOException("签名文件过大");
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
