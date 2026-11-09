package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Ed25519 分离签名：未受信、受信、篡改、缺失四种状态。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleSignatureTest {

  private Context context;
  private ModuleCard card;
  private File dir;

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    String raw =
        "{\"schema\":1,\"id\":\"signed.pet\",\"name\":\"签名卡\",\"image\":\"pet.png\","
            + "\"signature\":\"card.sig\",\"actions\":[]}";
    byte[] image = "png-bytes".getBytes(StandardCharsets.UTF_8);
    java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
    assets.put("pet.png", image);
    ModuleCardStore.importCard(context, raw, assets, ModuleCard.SOURCE_LOCAL);
    card = null;
    for (ModuleCard item : ModuleCardStore.loadAll(context))
      if (item.id.equals("signed.pet")) card = item;
    assertTrue(card != null);
    dir = new File(new File(context.getFilesDir(), "modules"), "signed.pet");
  }

  private static String sign(byte[] payload, Ed25519PrivateKeyParameters key) {
    Ed25519Signer signer = new Ed25519Signer();
    signer.init(true, key);
    signer.update(payload, 0, payload.length);
    return hex(signer.generateSignature());
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
    return value.toString();
  }

  private void writeSignature(Ed25519PrivateKeyParameters key, Ed25519PublicKeyParameters pub)
      throws Exception {
    byte[] payload = ModuleSignature.payload(dir, card);
    String keyId = ModuleSignature.fingerprint(hex(pub.getEncoded()));
    JSONObject json = new JSONObject();
    json.put("schema", 1);
    json.put("algorithm", "ed25519");
    json.put("keyId", keyId);
    json.put("publicKey", hex(pub.getEncoded()));
    json.put("signature", sign(payload, key));
    Files.write(dir.toPath().resolve("card.sig"), json.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void signatureIsUntrustedUntilTheKeyIsExplicitlyTrusted() throws Exception {
    Ed25519PrivateKeyParameters key =
        new Ed25519PrivateKeyParameters(new java.security.SecureRandom());
    Ed25519PublicKeyParameters pub = key.generatePublicKey();
    writeSignature(key, pub);
    ModuleSignature.Result result = ModuleSignature.inspect(context, card);
    assertEquals(result.message, ModuleSignature.Status.UNTRUSTED, result.status);
    assertEquals(ModuleSignature.fingerprint(hex(pub.getEncoded())), result.keyId);
    assertTrue(ModuleTrustStore.trust(context, result.keyId, result.publicKey));
    assertEquals(ModuleSignature.Status.VERIFIED, ModuleSignature.inspect(context, card).status);
    assertEquals(ModuleTrust.VERIFIED, ModuleTrust.statusOf(context, card));
  }

  @Test
  public void tamperedAssetInvalidatesSignature() throws Exception {
    Ed25519PrivateKeyParameters key =
        new Ed25519PrivateKeyParameters(new java.security.SecureRandom());
    Ed25519PublicKeyParameters pub = key.generatePublicKey();
    writeSignature(key, pub);
    ModuleSignature.Result first = ModuleSignature.inspect(context, card);
    assertTrue(ModuleTrustStore.trust(context, first.keyId, first.publicKey));
    Files.write(dir.toPath().resolve("pet.png"), "tampered".getBytes(StandardCharsets.UTF_8));
    ModuleSignature.Result result = ModuleSignature.inspect(context, card);
    assertEquals(ModuleSignature.Status.INVALID, result.status);
    assertEquals(ModuleTrust.INVALID, ModuleTrust.statusOf(context, card));
  }

  @Test
  public void missingSignatureIsInvalidWhenDeclared() {
    ModuleSignature.Result result = ModuleSignature.inspect(context, card);
    assertEquals(result.message, ModuleSignature.Status.INVALID, result.status);
    assertEquals(ModuleTrust.INVALID, ModuleTrust.statusOf(context, card));
  }

  @Test
  public void cardWithoutSignatureIsUnverified() {
    ModuleCard unsigned =
        ModuleCard.parse("{\"schema\":1,\"id\":\"plain.pet\",\"name\":\"无签名\",\"actions\":[]}");
    assertEquals(ModuleSignature.Status.MISSING, ModuleSignature.inspect(context, unsigned).status);
    assertEquals(ModuleTrust.UNVERIFIED, ModuleTrust.statusOf(context, unsigned));
  }

  @Test
  public void wrongKeyIdIsInvalidEvenWhenThePublicKeyIsPresent() throws Exception {
    Ed25519PrivateKeyParameters key =
        new Ed25519PrivateKeyParameters(new java.security.SecureRandom());
    Ed25519PublicKeyParameters pub = key.generatePublicKey();
    writeSignature(key, pub);
    JSONObject json =
        new JSONObject(
            new String(
                Files.readAllBytes(dir.toPath().resolve("card.sig")), StandardCharsets.UTF_8));
    json.put("keyId", "0000000000000000");
    Files.write(dir.toPath().resolve("card.sig"), json.toString().getBytes(StandardCharsets.UTF_8));
    assertEquals(ModuleSignature.Status.INVALID, ModuleSignature.inspect(context, card).status);
  }
}
