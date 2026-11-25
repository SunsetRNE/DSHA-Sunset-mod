#!/usr/bin/env python3
"""Exercise the production Java LAN proxy on host loopback sockets; no Android/device claims."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True)
    parser.add_argument('--gradle-home', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--pure-only', action='store_true', help='compile the proxy and run only socket-free credential and presentation unit tests')
    parser.add_argument('--baseline-proxy', help='optional pre-change LanProxyService source for regression evidence')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    stubs = output / 'stubs'
    sources = {
        'LanCaseReport.java': '''import java.nio.file.*;
import org.junit.runner.*;
import org.junit.runner.notification.*;
public final class LanCaseReport {
 public static void main(String[] names) throws Exception {
  var path = Paths.get(System.getProperty("dsha.test.report"));
  var junit = new JUnitCore();
  junit.addListener(new org.junit.internal.TextListener(System.out));
  try (var log = Files.newBufferedWriter(path)) {
   log.write("class#method\\tstatus\\tseconds\\n");
   junit.addListener(new RunListener() {
    long start; boolean failed;
    public void testStarted(Description d) { start=System.nanoTime(); failed=false; }
    public void testFailure(Failure f) { failed=true; System.err.println(f.getTrace()); }
    public void testFinished(Description d) throws Exception {
     log.write(String.format(java.util.Locale.ROOT,"%s#%s\\t%s\\t%.6f%n",d.getClassName(),d.getMethodName(),
         failed?"FAIL":"PASS",(System.nanoTime()-start)*1e-9)); log.flush();
    }
   });
   Class<?>[] classes=new Class<?>[names.length];
   for(int i=0;i<names.length;i++) classes[i]=Class.forName(names[i]);
   var result=junit.run(classes);
   System.out.printf("tests=%d failures=%d skipped=%d elapsed_ms=%d%n",
       result.getRunCount(), result.getFailureCount(), result.getIgnoreCount(), result.getRunTime());
   if(!result.wasSuccessful()) System.exit(1);
  }
 }
}''',
        'com/deepseekharness/app/AndroidLanIdentity.java': '''package com.deepseekharness.app;
// The host has no AndroidKeyStore. Only key custody is substituted for this fixture.
public final class AndroidLanIdentity {
 private static LanTlsIdentity current;
 static synchronized LanTlsIdentity load(String address) throws java.security.GeneralSecurityException {
  if (current == null) {
   var gen = java.security.KeyPairGenerator.getInstance("EC");
   gen.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
   var keys = gen.generateKeyPair();
   var leaf = LanTlsIdentity.issue(keys.getPrivate(), keys.getPublic(), address, System.currentTimeMillis());
   current = new LanTlsIdentity(keys.getPrivate(), leaf);
  }
  return current;
 }
 public static java.security.cert.X509Certificate leaf() throws java.security.GeneralSecurityException {
  return load("127.0.0.1").getCertificateChain("lan")[0];
 }
}''',
        'android/content/Context.java': '''package android.content;
public class Context {
 public static final int MODE_PRIVATE = 0;
 private final java.util.Map<String,String> values = new java.util.concurrent.ConcurrentHashMap<>();
 public Context() {}
 public Context(String verifier) { values.put("lan_password_verifier_v1", verifier); }
 public Context getApplicationContext() { return this; }
 public SharedPreferences getSharedPreferences(String name, int mode) { return new SharedPreferences() {
  public boolean getBoolean(String key, boolean fallback) { return "lan_mode".equals(key) || fallback; }
  public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
  public Editor edit() { return new Editor() {
   public Editor putString(String key, String value) { values.put(key, value); return this; }
   public void apply() {}
  }; }
 }; }
}''',
        'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 boolean getBoolean(String key, boolean fallback);
 String getString(String key, String fallback);
 Editor edit();
 interface Editor { Editor putString(String key, String value); void apply(); }
}''',
        'com/deepseekharness/app/BuildConfig.java': '''package com.deepseekharness.app;
public final class BuildConfig { public static final String APPLICATION_ID = "dsh.android.client"; }''',
        'android/os/SystemClock.java': '''package android.os;
public final class SystemClock {
 public static long elapsedRealtime() { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
}''',
        'android/util/Log.java': '''package android.util;
public final class Log { public static int i(String tag, String message) { return 0; } }''',
        'com/deepseekharness/app/bridge/LocalNetworkAccess.java': '''package com.deepseekharness.app.bridge;
public final class LocalNetworkAccess {
 public static volatile long handle = 1;
 public static boolean granted(android.content.Context context) { return true; }
 public static com.deepseekharness.app.util.LanNetworkState currentLan(android.content.Context context) {
  return new com.deepseekharness.app.util.LanNetworkState(handle, "fixture", "127.0.0.1", 8);
 }
}''',
        'com/deepseekharness/app/util/UiMessages.java': '''package com.deepseekharness.app.util;
public final class UiMessages {
 public static final java.util.Map<String,String> EN = java.util.Map.of();
 public static java.util.Map<String,String> forLanguage(String language) { return EN; }
 public static String[] formatsForLanguage(String language) { return new String[0]; }
}''',
        'com/deepseekharness/app/util/BackupTaskKinds.java': '''package com.deepseekharness.app.util;
// The Java-only network fixture has no backup tasks to localize.
public final class BackupTaskKinds {
 public static String display(String value, boolean english) { return null; }
}''',
    }
    for name, value in sources.items():
        target = stubs / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value, encoding='utf-8')
    cache = Path(args.gradle_home) / 'caches/modules-2/files-2.1'
    dependencies = []
    for group, name, version in [('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3'),
                                 ('org.bouncycastle', 'bcprov-jdk15to18', '1.85.2')]:
        matches = list((cache / group / name / version).glob('**/*.jar'))
        if len(matches) != 1:
            raise RuntimeError('EXACT_JVM_DEPENDENCY_REQUIRED:' + name)
        dependencies.extend(matches)
    cp = os.pathsep.join(map(str, dependencies))
    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    overrides = output / 'overrides'
    if args.baseline_proxy:
        target = overrides / 'com/deepseekharness/app/LanProxyService.java'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(Path(args.baseline_proxy).read_bytes())
    suffix = '.exe' if os.name == 'nt' else ''
    jdk = Path(args.jdk) / 'bin'
    pure = ['LanAuthTest', 'LanPasswordAuthTest', 'LanRequestOriginTest', 'util.LanTokenStateTest', 'util.LanNetworkStateTest', 'util.LanAddressPolicyTest', 'util.LanAccessPresentationTest']
    tests = pure if args.pure_only else ['LanPasswordAuthTest', 'util.LanTokenStateTest', 'LanAuthTest', 'util.SocketDispatchTest', 'util.HttpProtocolTest', 'util.LanNetworkStateTest', 'util.LanAddressPolicyTest', 'util.LanAccessPresentationTest',
             'LanRequestOriginTest', 'LanFiniteResponseTest', 'util.LanFailureResponseTest', 'util.BridgePortPolicyTest', 'util.LanPortPolicyTest', 'util.WebPortPolicyTest', 'util.WebPortSettingsTest', 'util.ConfigInputTest']
    test_sources = [ROOT / 'app/src/test/java/com/deepseekharness/app' / (name.replace('.', '/') + '.java') for name in tests]
    command = [str(jdk / ('javac' + suffix)), '-encoding', 'UTF-8', '--release', '17', '-cp', cp,
               '-d', str(classes), '-sourcepath', os.pathsep.join(map(str, [stubs, overrides, ROOT / 'app/src/main/java', ROOT / 'app/src/test/java'])),
               str(stubs / 'LanCaseReport.java'), str(ROOT / 'tools/fixtures/lan-proxy-host/LanProxyHostTest.java'), *map(str, test_sources)]
    subprocess.run(command, check=True, timeout=90)
    subprocess.run([str(jdk / ('java' + suffix)), '-Dfile.encoding=UTF-8', '-Ddsha.test.report=' + str(output / 'cases.tsv'),
                    '-cp', str(classes) + os.pathsep + cp, 'LanCaseReport', *([] if args.pure_only else ['com.deepseekharness.app.LanProxyHostTest']),
                    *['com.deepseekharness.app.' + name for name in tests]], check=True, timeout=90)
    if args.pure_only:
        print('PASS socket-free LAN credential, route and presentation tests; compiled production proxy with Android stubs.')
        return
    print('PASS production TLS listener/leaf, HTTP, auth and dispatch over loopback. AndroidKeyStore custody, Android network and context are host stubs.')


if __name__ == '__main__':
    main()
