package com.deepseekharness.app.nethealth;

import static org.junit.Assert.*;

import android.app.Application;
import com.deepseekharness.app.util.NetworkHealthPolicy.Probe;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 测试进程内替换 JDK DNS/URL 边界，不访问外网，不改生产探测接口。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class NetworkHealthProbeTest {
  private Object unsafe;
  private Class<?> unsafeType;
  private Object originalNameService;
  private Map<Object, Object> originalCache;
  private Map<Object, Object> cache;
  private Hashtable<String, URLStreamHandler> handlers;
  private URLStreamHandler originalHttps;
  private final AtomicInteger lookups = new AtomicInteger();
  private final AtomicInteger opens = new AtomicInteger();
  private final AtomicReference<String> host = new AtomicReference<>();
  private final AtomicReference<Thread> lookupThread = new AtomicReference<>();
  private CountDownLatch entered;
  private CountDownLatch release;
  private CountDownLatch finished;
  private boolean delayed;
  private boolean empty;
  private Exception dnsError;
  private FakeConnection connection;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    // 仅测试夹具使用 Unsafe 访问 JDK 的私有网络入口，finally 恢复原对象和缓存。
    // 避免给 Gradle 或生产代码增加 --add-opens、测试专用接口或真实网络依赖。
    unsafeType = Class.forName("sun.misc.Unsafe");
    Field singleton = unsafeType.getDeclaredField("theUnsafe");
    singleton.setAccessible(true);
    unsafe = singleton.get(null);
    InetAddress.getLoopbackAddress();
    Field serviceField = InetAddress.class.getDeclaredField("nameService");
    originalNameService = readStatic(serviceField);
    cache = (Map<Object, Object>) readStatic(InetAddress.class.getDeclaredField("cache"));
    originalCache = new HashMap<>(cache);
    cache.clear();
    entered = new CountDownLatch(1);
    release = new CountDownLatch(1);
    finished = new CountDownLatch(1);
    Object service =
        Proxy.newProxyInstance(
            serviceField.getType().getClassLoader(),
            new Class<?>[] {serviceField.getType()},
            (proxy, method, args) -> {
              if (!method.getName().equals("lookupAllHostAddr")) throw new AssertionError(method);
              lookupThread.set(Thread.currentThread());
              lookups.incrementAndGet();
              host.set((String) args[0]);
              entered.countDown();
              try {
                while (delayed && release.getCount() > 0) {
                  try {
                    release.await();
                  } catch (InterruptedException ignored) {
                    /* 模拟不响应取消的迟到 DNS。 */
                  }
                }
                if (dnsError != null) throw dnsError;
                return empty
                    ? new InetAddress[0]
                    : new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})};
              } finally {
                finished.countDown();
              }
            });
    writeStatic(serviceField, service);
    handlers =
        (Hashtable<String, URLStreamHandler>) readStatic(URL.class.getDeclaredField("handlers"));
    originalHttps = handlers.get("https");
    connection = new FakeConnection(new URL("https://api.deepseek.com/"));
    handlers.put(
        "https",
        new URLStreamHandler() {
          @Override
          protected URLConnection openConnection(URL url) {
            opens.incrementAndGet();
            assertEquals("https://api.deepseek.com/", url.toExternalForm());
            assertNull(url.getUserInfo());
            return connection;
          }
        });
  }

  @After
  public void tearDown() throws Exception {
    boolean ended = true;
    try {
      if (release != null) release.countDown();
      if (lookups.get() > 0) {
        ended = finished.await(3, TimeUnit.SECONDS);
        Thread lookup = lookupThread.get();
        lookup.join(3_000);
        ended &= !lookup.isAlive();
      }
    } finally {
      if (originalNameService != null)
        writeStatic(InetAddress.class.getDeclaredField("nameService"), originalNameService);
      if (cache != null && originalCache != null) {
        cache.clear();
        cache.putAll(originalCache);
      }
      if (handlers != null) {
        if (originalHttps == null) handlers.remove("https");
        else handlers.put("https", originalHttps);
      }
      Thread.interrupted();
    }
    assertTrue("DNS fixture must finish before restoring the process boundary", ended);
  }

  @Test
  public void oneDnsAndHeadHasNoCredentialsBodyRedirectOrRetry() {
    connection.status = 403;
    Probe result = NetworkHealthProbe.run();
    assertTrue(result.ok());
    assertEquals(1, lookups.get());
    assertEquals("api.deepseek.com", host.get());
    assertEquals(1, opens.get());
    assertEquals("HEAD", connection.getRequestMethod());
    assertFalse(connection.getInstanceFollowRedirects());
    assertFalse(connection.getUseCaches());
    assertFalse(connection.getDoOutput());
    assertEquals(5_000, connection.getConnectTimeout());
    assertEquals(5_000, connection.getReadTimeout());
    assertEquals(
        Map.of("Connection", java.util.List.of("close")), connection.getRequestProperties());
    assertEquals(1, connection.responses);
    assertEquals(1, connection.disconnects);
    assertEquals("", result.error);
  }

  @Test
  public void redirectAndUnauthorizedAreConnectivitySuccessWithoutFollowing() {
    for (int status : new int[] {301, 401}) {
      connection.status = status;
      assertTrue(NetworkHealthProbe.run().ok());
    }
    assertEquals(2, connection.responses);
    assertEquals(2, opens.get());
    assertEquals(2, connection.disconnects);
  }

  @Test
  public void nonPositiveResponseDoesNotRetry() {
    connection.status = -1;
    Probe result = NetworkHealthProbe.run();
    assertTrue(result.dnsOk);
    assertFalse(result.httpsOk);
    assertEquals("", result.error);
    assertEquals(1, opens.get());
    assertEquals(1, connection.disconnects);
  }

  @Test
  public void httpsFailureUsesOnlyExceptionClassAndDisconnectsOnce() {
    connection.failure = new IOException("secret host=192.0.2.1 path=/key");
    Probe result = NetworkHealthProbe.run();
    assertTrue(result.dnsOk);
    assertFalse(result.httpsOk);
    assertEquals("IOException", result.error);
    assertEquals(1, connection.responses);
    assertEquals(1, connection.disconnects);
  }

  @Test
  public void dnsFailureUnwrapsCauseAndNeverOpensHttps() {
    dnsError = new UnknownHostException("secret address");
    Probe result = NetworkHealthProbe.run();
    assertFalse(result.dnsOk);
    assertFalse(result.httpsOk);
    assertEquals("UnknownHostException", result.error);
    assertEquals(0, result.httpsMs);
    assertEquals(1, lookups.get());
    assertEquals(0, opens.get());
  }

  @Test
  public void emptyDnsResultNeverOpensHttps() {
    empty = true;
    Probe result = NetworkHealthProbe.run();
    assertFalse(result.dnsOk);
    assertEquals("", result.error);
    assertEquals(0, opens.get());
  }

  @Test
  public void timedOutLookupCannotPublishLateSuccessOrStartHead() throws Exception {
    delayed = true;
    Probe result = NetworkHealthProbe.run();
    assertFalse(result.dnsOk);
    assertEquals("DNS_TIMEOUT", result.error);
    assertEquals(1, lookups.get());
    assertEquals(0, opens.get());
    release.countDown();
    assertTrue(finished.await(3, TimeUnit.SECONDS));
    assertFalse(result.dnsOk);
    assertEquals(0, opens.get());
  }

  @Test
  public void interruptedWaitCancelsLookupAndKeepsExistingInterruptContract() throws Exception {
    delayed = true;
    AtomicReference<Probe> result = new AtomicReference<>();
    AtomicReference<Boolean> interrupted = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              result.set(NetworkHealthProbe.run());
              interrupted.set(Thread.currentThread().isInterrupted());
            });
    caller.start();
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    caller.interrupt();
    caller.join(3_000);
    assertFalse(caller.isAlive());
    assertEquals("InterruptedException", result.get().error);
    assertFalse(interrupted.get());
    assertEquals(0, opens.get());
  }

  @Test
  public void disconnectRuntimeFailureStillEscapesFinally() {
    connection.disconnectFailure = true;
    assertThrows(IllegalStateException.class, NetworkHealthProbe::run);
    assertEquals(1, connection.disconnects);
  }

  private Object readStatic(Field field) throws Exception {
    Object base = unsafeType.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
    long offset =
        (Long) unsafeType.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
    return unsafeType
        .getMethod("getObjectVolatile", Object.class, long.class)
        .invoke(unsafe, base, offset);
  }

  private void writeStatic(Field field, Object value) throws Exception {
    Object base = unsafeType.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
    long offset =
        (Long) unsafeType.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
    unsafeType
        .getMethod("putObjectVolatile", Object.class, long.class, Object.class)
        .invoke(unsafe, base, offset, value);
  }

  private static final class FakeConnection extends HttpURLConnection {
    int status = 204;
    int responses, disconnects;
    IOException failure;
    boolean disconnectFailure;

    FakeConnection(URL url) {
      super(url);
    }

    @Override
    public int getResponseCode() throws IOException {
      responses++;
      if (failure != null) throw failure;
      return status;
    }

    @Override
    public void disconnect() {
      disconnects++;
      if (disconnectFailure) throw new IllegalStateException("disconnect");
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void connect() {
      throw new AssertionError("must not connect twice");
    }

    @Override
    public java.io.InputStream getInputStream() {
      throw new AssertionError("must not read body");
    }

    @Override
    public java.io.OutputStream getOutputStream() {
      throw new AssertionError("must not send body");
    }
  }
}
