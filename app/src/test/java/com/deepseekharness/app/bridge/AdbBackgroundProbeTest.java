package com.deepseekharness.app.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest;
import com.deepseekharness.app.util.AdbResult;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = RuntimeToolsRegistryAndroidTest.OwnedApplication.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {RuntimeToolsRegistryAndroidTest.HostFileSystem.class})
public class AdbBackgroundProbeTest {
  private static final List<String> PREFIX =
      List.of("python3", "/root/.dsh/adb-shell.py", "--connect-timeout", "20", "--timeout", "10");

  @Test
  public void explicitSavedAndLocalEndpointsUseIsolatedLiteralArgv() throws Exception {
    var boot = new ProbeBootstrap();
    var port = boot.getRootfsDir().toPath().resolve("root/.dsh/adbkeys/connect_port");
    Files.createDirectories(port.getParent());
    Files.writeString(port, "45321\n");
    assertTrue(AdbResult.shellReady(AdbBridge.probe(boot, null)));
    assertEquals(args("--port", "45321", "id"), boot.argv);

    String endpoint = "fe80::1%wlan0";
    assertTrue(
        AdbResult.shellReady(AdbBridge.probe(boot, new AdbBridge.Endpoint(endpoint, 45678))));
    assertEquals(args("--host", endpoint, "--port", "45678", "id"), boot.argv);
  }

  @Test
  public void failedExitAndUnconfirmedCleanupCannotAuthenticateOrStartAnotherProbe()
      throws Exception {
    var boot = new ProbeBootstrap();
    boot.exit = 7;
    String exited = AdbBridge.probe(boot, null);
    assertFalse(AdbResult.shellReady(exited));
    assertTrue(exited.contains("[ADB_PROCESS_EXIT=7]"));

    boot.error =
        new InstallProcess.OwnedExitFailure(new IOException("controlled cleanup failure"), null);
    String unconfirmed = AdbBridge.probe(boot, null);
    assertFalse(AdbResult.shellReady(unconfirmed));
    assertTrue(AdbResult.marker(unconfirmed, "ADB_PROCESS_EXIT_UNCONFIRMED"));
    assertTrue(AdbResult.marker(AdbBridge.probe(boot, null), "ENVIRONMENT_BUSY"));
    assertEquals(2, boot.admitted);
    Files.delete(boot.pending.resolve("intent"));
    Files.delete(boot.pending);
    boot.error = null;
    boot.exit = 0;
    assertTrue(AdbResult.shellReady(AdbBridge.probe(boot, null)));
    assertEquals(3, boot.admitted);
  }

  @Test
  public void preexistingInterruptionReachesTheOwnedCancellationGate() {
    var boot = new ProbeBootstrap();
    try {
      Thread.currentThread().interrupt();
      String output = AdbBridge.probe(boot, null);
      assertTrue(boot.cancelledAtAdmission);
      assertTrue(Thread.currentThread().isInterrupted());
      assertFalse(AdbResult.shellReady(output));
      assertTrue(AdbResult.marker(output, "ADB_ERROR"));
    } finally {
      Thread.interrupted();
    }
  }

  private static List<String> args(String... options) {
    var expected = new ArrayList<>(PREFIX);
    expected.addAll(List.of(options));
    return expected;
  }

  private static final class ProbeBootstrap extends ProotBootstrap {
    List<String> argv;
    int admitted;
    int exit;
    Exception error;
    boolean cancelledAtAdmission;
    Path pending;

    ProbeBootstrap() {
      super(fixtureApplication());
    }

    private static android.app.Application fixtureApplication() {
      var app = RuntimeEnvironment.getApplication();
      app.getApplicationInfo().nativeLibraryDir = app.getFilesDir().getPath();
      return app;
    }

    @Override
    public int checkInstall(
        List<String> guestArgv, long timeoutMs, BooleanSupplier cancelled, Consumer<String> output)
        throws Exception {
      assertTrue(EnvironmentTaskGate.canExecuteCurrentThread());
      assertEquals(60_000, timeoutMs);
      admitted++;
      argv = List.copyOf(guestArgv);
      cancelledAtAdmission = cancelled.getAsBoolean();
      if (cancelledAtAdmission) throw new InterruptedException("controlled admission cancellation");
      output.accept("uid=2000(shell)");
      output.accept("[EXIT=0]");
      if (error != null) {
        String record = "7b74b129-73b8-4c00-8722-1936336cd70e";
        pending =
            getRootfsDir()
                .toPath()
                .getParent()
                .getParent()
                .resolve("bounded-guest-active")
                .resolve(record);
        Files.createDirectories(pending);
        Files.writeString(pending.resolve("intent"), record + "\n");
        throw error;
      }
      return exit;
    }
  }
}
