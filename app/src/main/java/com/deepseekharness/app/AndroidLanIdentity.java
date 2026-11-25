package com.deepseekharness.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;

/** A single app-owned signing key. Renew only its leaf certificate when the IP or validity changes. */
final class AndroidLanIdentity {
  private static final String STORE = "AndroidKeyStore";
  private static final String ALIAS = "dsha.lan.https.v1";

  private AndroidLanIdentity() {}

  static synchronized LanTlsIdentity load(String address)
      throws GeneralSecurityException, IOException {
    KeyStore store = KeyStore.getInstance(STORE);
    store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyPairGenerator generator =
          KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE);
      generator.initialize(
          new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
              .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
              // Conscrypt can ask the keystore to sign an already-digested TLS handshake.
              .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
              .build());
      generator.generateKeyPair();
    }
    PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
    X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
    long now = System.currentTimeMillis();
    if (!LanTlsIdentity.validFor(certificate, address, now)) {
      certificate = LanTlsIdentity.issue(key, certificate.getPublicKey(), address, now);
      store.setKeyEntry(ALIAS, key, null, new X509Certificate[] {certificate});
    }
    return new LanTlsIdentity(key, certificate);
  }
}
