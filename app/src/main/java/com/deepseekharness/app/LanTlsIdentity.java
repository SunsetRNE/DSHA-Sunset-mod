package com.deepseekharness.app;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/** The certificate displayed on the phone and the one served by this listener are the same object. */
final class LanTlsIdentity extends X509ExtendedKeyManager {
  private static final String ALIAS = "lan";
  private static final long DAY = TimeUnit.DAYS.toMillis(1);
  private final PrivateKey key;
  private final X509Certificate certificate;
  private final SSLContext context;
  final String fingerprint;

  LanTlsIdentity(PrivateKey key, X509Certificate certificate) throws GeneralSecurityException {
    this.key = key;
    this.certificate = certificate;
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
    StringBuilder hex = new StringBuilder(95);
    String digits = "0123456789ABCDEF";
    for (byte b : digest) {
      if (hex.length() > 0) hex.append(':');
      hex.append(digits.charAt((b >>> 4) & 15)).append(digits.charAt(b & 15));
    }
    fingerprint = hex.toString();
    context = SSLContext.getInstance("TLS");
    context.init(new KeyManager[] {this}, null, null);
  }

  static boolean validFor(X509Certificate certificate, String address, long now)
      throws GeneralSecurityException {
    var names = certificate.getSubjectAlternativeNames();
    return names != null
        && names.size() == 1
        && names.contains(List.of(7, address))
        && !certificate.getNotBefore().after(new Date(now))
        && certificate.getNotAfter().after(new Date(now + DAY));
  }

  // bcprov is already packaged. This builder invokes the platform Signature provider with the
  // original PrivateKey handle; the private key is never encoded or handed to a software store.
  @SuppressWarnings("deprecation")
  static X509Certificate issue(PrivateKey key, PublicKey publicKey, String address, long now)
      throws GeneralSecurityException {
    var builder = new X509V3CertificateGenerator();
    var name = new X500Principal("CN=DSHA LAN");
    builder.setSubjectDN(name);
    builder.setIssuerDN(name);
    builder.setSerialNumber(new BigInteger(159, new SecureRandom()).setBit(158));
    builder.setNotBefore(new Date(now - TimeUnit.MINUTES.toMillis(5)));
    builder.setNotAfter(new Date(now + 90 * DAY));
    builder.setPublicKey(publicKey);
    builder.setSignatureAlgorithm("SHA256withECDSA");
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
    builder.addExtension(
        Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(new GeneralName(GeneralName.iPAddress, address)));
    return builder.generate(key);
  }

  SSLServerSocket bind(InetAddress address, int preferred) throws IOException {
    var socket =
        (SSLServerSocket)
            com.deepseekharness.app.util.LanPortPolicy.bind(
                context.getServerSocketFactory(), address, preferred);
    try {
      var supported = Arrays.asList(socket.getSupportedProtocols());
      socket.setEnabledProtocols(
          supported.contains("TLSv1.3")
              ? new String[] {"TLSv1.3", "TLSv1.2"}
              : new String[] {"TLSv1.2"});
      return socket;
    } catch (RuntimeException failed) {
      socket.close();
      throw failed;
    }
  }

  @Override
  public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
    return "EC".equals(type) ? ALIAS : null;
  }

  @Override
  public String[] getServerAliases(String type, Principal[] issuers) {
    return "EC".equals(type) ? new String[] {ALIAS} : null;
  }

  @Override
  public PrivateKey getPrivateKey(String alias) {
    return ALIAS.equals(alias) ? key : null;
  }

  @Override
  public X509Certificate[] getCertificateChain(String alias) {
    return ALIAS.equals(alias) ? new X509Certificate[] {certificate} : null;
  }

  @Override
  public String[] getClientAliases(String type, Principal[] issuers) {
    return null;
  }

  @Override
  public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
    return null;
  }

  @Override
  public String chooseEngineServerAlias(
      String type, Principal[] issuers, javax.net.ssl.SSLEngine engine) {
    return chooseServerAlias(type, issuers, null);
  }
}
