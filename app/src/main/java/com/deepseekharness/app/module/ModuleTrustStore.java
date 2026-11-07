package com.deepseekharness.app.module;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 签名公钥信任库。
 *
 * <p>内置受信公钥在 {@code assets/module-trust/keys.json}；用户也可以显式信任一把
 * 首次导入时看到的公钥（存在 {@code dsha_module_trust}）。没有在这两处的公钥一律不算
 * 「已验证」——摘要有指纹，但指纹本身不是信任。
 */
public final class ModuleTrustStore {

  private static final String PREFS = "dsha_module_trust";
  private static final String KEY_PREFIX = "key.";
  // 内置公钥资产（module-trust/keys.json）当前不存在于随包 assets：builtin 会是空表，
  // 「已验证」只能来自测试或未来 UI 写入 dsha_module_trust。补资产前，文档不宣称内置信任。
  private static final String ASSET = "module-trust/keys.json";

  private final Map<String, String> builtin;
  private final Context context;

  private ModuleTrustStore(Context context, Map<String, String> builtin) {
    this.context = context.getApplicationContext();
    this.builtin = Collections.unmodifiableMap(new LinkedHashMap<>(builtin));
  }

  public static ModuleTrustStore load(Context context) {
    Map<String, String> keys = new LinkedHashMap<>();
    try {
      String raw =
          new String(
              readFully(context.getAssets().open(ASSET)), java.nio.charset.StandardCharsets.UTF_8);
      JSONObject json = new JSONObject(raw);
      JSONArray list = json.optJSONArray("keys");
      if (list != null) {
        for (int i = 0; i < list.length(); i++) {
          JSONObject item = list.optJSONObject(i);
          if (item == null) continue;
          String keyId = item.optString("keyId", "").trim();
          String key = item.optString("publicKey", "").trim();
          if (keyId.matches("[0-9a-f]{16,64}") && ModuleSignature.fingerprint(key).equals(keyId))
            keys.put(keyId, key);
        }
      }
    } catch (Exception ignored) {
      // 没有内置信任库或格式坏掉时，仍可正常运行，只是没有「已验证」模块。
    }
    return new ModuleTrustStore(context, keys);
  }

  /** 内置 + 用户信任的公钥；keyId → 64 字节十六进制。 */
  public String publicKey(String keyId) {
    if (keyId == null || keyId.isEmpty()) return "";
    String builtinKey = builtin.get(keyId);
    if (builtinKey != null) return builtinKey;
    return context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PREFIX + keyId, "");
  }

  public boolean isBuiltin(String keyId) {
    return builtin.containsKey(keyId);
  }

  public Map<String, String> builtin() {
    return builtin;
  }

  /**
   * 用户显式信任一把公钥。keyId 必须与公钥指纹一致，避免把信任绑到可伪造的自述名字上。
   *
   * @return 是否写入成功
   */
  public static boolean trust(Context context, String keyId, String publicKeyHex) {
    if (ModuleSignature.fingerprint(publicKeyHex).equals(keyId)) {
      context
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putString(KEY_PREFIX + keyId, publicKeyHex)
          .apply();
      return true;
    }
    return false;
  }

  public static void revoke(Context context, String keyId) {
    if (keyId == null || keyId.isEmpty()) return;
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_PREFIX + keyId)
        .apply();
  }

  /** minSdk 30 没有 InputStream.readAllBytes（API 33），内置信任库按块读入。 */
  private static byte[] readFully(java.io.InputStream input) throws java.io.IOException {
    try (java.io.InputStream source = input) {
      java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int count; (count = source.read(buffer)) != -1; ) output.write(buffer, 0, count);
      return output.toByteArray();
    }
  }
}
