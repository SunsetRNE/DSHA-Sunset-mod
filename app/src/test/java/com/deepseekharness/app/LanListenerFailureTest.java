package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.content.Context;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.util.LanNetworkState;
import com.deepseekharness.app.util.LanPassword;
import com.deepseekharness.app.util.LanPortPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Exercises the actual listener thread without exposing a LAN socket. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = LanListenerFailureTest.App.class,
    shadows = {
      LanListenerFailureTest.Identity.class,
      LanListenerFailureTest.Port.class,
      LanListenerFailureTest.Network.class
    })
public final class LanListenerFailureTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static final AtomicReference<Thread> worker = new AtomicReference<>();
  private static final AtomicReference<Throwable> uncaught = new AtomicReference<>();
  private static CountDownLatch entered;
  private static boolean failBind;
  private static ServerSocket bound;
  private static int identityLoads, bindAttempts;

  @Implements(value = AndroidLanIdentity.class, isInAndroidSdk = false)
  public static final class Identity {
    @Implementation
    protected static LanTlsIdentity load(String address)
        throws java.security.GeneralSecurityException {
      // Robolectric has no AndroidKeyStore. Retain the actual leaf, TLS context, and listener.
      var generator = java.security.KeyPairGenerator.getInstance("EC");
      generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
      var pair = generator.generateKeyPair();
      var leaf =
          LanTlsIdentity.issue(
              pair.getPrivate(), pair.getPublic(), address, System.currentTimeMillis());
      var identity = new LanTlsIdentity(pair.getPrivate(), leaf);
      identityLoads++;
      return identity;
    }
  }

  private static void markWorker() {
    Thread thread = Thread.currentThread();
    thread.setUncaughtExceptionHandler((ignored, failure) -> uncaught.set(failure));
    worker.set(thread);
    entered.countDown();
  }

  @Implements(value = LanPortPolicy.class, isInAndroidSdk = false)
  public static final class Port {
    @Implementation
    protected static ServerSocket bind(
        javax.net.ServerSocketFactory factory, InetAddress address, int port) throws IOException {
      assertTrue(factory instanceof javax.net.ssl.SSLServerSocketFactory);
      assertTrue(address.isLoopbackAddress());
      bindAttempts++;
      if (failBind) {
        markWorker();
        throw new SecurityException("private address must not appear in diagnostics");
      }
      bound = factory.createServerSocket(0, 1, address);
      return bound;
    }
  }

  private static LanNetworkState observed;

  @Implements(value = LocalNetworkAccess.class, isInAndroidSdk = false)
  public static final class Network {
    @Implementation
    protected static LanNetworkState currentLan(Context context) {
      if (Thread.currentThread().getName().startsWith("dsha-lan-accept")
          && bound != null
          && bound.isBound()) {
        markWorker();
        return null;
      }
      return observed;
    }
  }

  @Before
  public void setUp() {
    worker.set(null);
    uncaught.set(null);
    entered = new CountDownLatch(1);
    bound = null;
    identityLoads = bindAttempts = 0;
    observed = new LanNetworkState(1, "fixture", "127.0.0.1", 8);
    Context ctx = RuntimeEnvironment.getApplication();
    ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(Constants.KEY_LAN_MODE, true)
        .putString(LanPassword.PREF_KEY, "test-verifier")
        .commit();
    assertTrue(LanProxyService.trustNetwork(ctx, LocalNetworkAccess.currentLan(ctx)));
    assertTrue(LanProxyService.setDshAuthCookie("dsh-auth-test=valid", 123));
  }

  @After
  public void tearDown() {
    LanProxyService.forgetNetwork();
    LanProxyService.stop();
  }

  @Test
  public void savedSwitchDoesNotAuthorizeANewProcessOrNetwork() {
    Context context = RuntimeEnvironment.getApplication();
    LanNetworkState first = observed;
    observed = new LanNetworkState(2, "fixture", "127.0.0.1", 8);
    LanProxyService.start(null, context, 49123, 123);
    assertEquals(0, LanProxyService.port(123));
    assertFalse(LanProxyService.trustNetwork(context, first));
    assertEquals(
        com.deepseekharness.app.util.LanAccessPresentation.State.CONFIRM_NETWORK,
        LanProxyService.access(true, true, 123).state);
    assertTrue(LanProxyService.trustNetwork(context, observed));
    LanProxyService.forgetNetwork();
    LanProxyService.start(null, context, 49123, 123);
    assertEquals(0, LanProxyService.port(123));
  }

  @Test
  public void oldPreferencesAreRemovedEvenWhenSharingIsOff() {
    Context context = RuntimeEnvironment.getApplication();
    var prefs = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    prefs
        .edit()
        .putBoolean(Constants.KEY_LAN_MODE, false)
        .putString("lan_token", "obsolete")
        .putString("lan_token_v2", "obsolete")
        .commit();
    var migrated = new com.deepseekharness.app.core.ConfigStore(context);
    assertFalse(prefs.contains("lan_token"));
    assertFalse(prefs.contains("lan_token_v2"));
    assertTrue(migrated.hasLanPassword());
    assertFalse(migrated.isLanMode());
  }

  @Test
  public void deniedBindRetiresListenerWithoutUncaughtException() throws Exception {
    failBind = true;
    runFailure();
    assertNull(bound);
  }

  @Test
  public void failedNetworkPollClosesBoundListenerWithoutUncaughtException() throws Exception {
    failBind = false;
    runFailure();
    assertNotNull(bound);
    assertTrue(bound instanceof javax.net.ssl.SSLServerSocket);
    assertTrue(bound.isClosed());
  }

  private void runFailure() throws Exception {
    LanProxyService.start(null, RuntimeEnvironment.getApplication(), 49123, 123);
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    Thread thread = worker.get();
    assertNotNull(thread);
    thread.join(5000);
    assertFalse(thread.isAlive());
    assertEquals(1, identityLoads);
    assertEquals(1, bindAttempts);
    assertNull(uncaught.get());
    assertEquals(0, LanProxyService.port(123));
    assertTrue(LanProxyService.hasDshAuth(123));
  }
}
