package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.content.Context;
import com.deepseekharness.app.screen.ScreenActivityRecorderTest.NoApkRunner;
import java.io.File;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

@RunWith(NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = BridgeRestoreRotationTest.App.class,
    shadows = BridgeRestoreRotationTest.MissingRoot.class)
public final class BridgeRestoreRotationTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HttpShellService.class, isInAndroidSdk = false)
  public static final class MissingRoot {
    static boolean pristine;
    static File directory;
    static String written;
    static boolean failWrite;

    @Implementation
    protected static File verifiedTokenFile(Context context) throws IOException {
      if (!pristine && directory == null) throw new IOException("BRIDGE_DATA_ROOT_UNAVAILABLE");
      return directory == null ? null : new File(directory, ".bridge_token");
    }

    @Implementation
    protected static void publishReplacement(File file, String token) throws IOException {
      if (failWrite) throw new IOException("BRIDGE_TOKEN_READBACK");
      written = token;
    }
  }

  @org.junit.Before
  public void reset() {
    MissingRoot.pristine = false;
    MissingRoot.directory = null;
    MissingRoot.written = "";
    MissingRoot.failWrite = false;
  }

  @Test
  public void unverifiedRestoreRootRevokesScreenAndKeepsAuthenticationClosedAcrossRestart()
      throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    var broker = DshaApp.from(context).capabilityBroker();
    long before = broker.epoch("screen");
    HttpShellService.requireTokenRotation(context, "legacy");
    assertTrue(broker.epoch("screen") > before);
    assertThrows(IOException.class, () -> HttpShellService.resetTokenAfterRestore(context));
    assertTrue(HttpShellService.currentToken().isEmpty());
    ReflectionHelpers.setStaticField(HttpShellService.class, "rotationBlocked", false);
    assertTrue(HttpShellService.legacyTokenRotationPending(context));
    assertTrue(HttpShellService.currentToken().isEmpty());
  }

  @Test
  public void pristineRestoreDefersMandatoryReplacementUntilFirstVerifiedHome() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    MissingRoot.pristine = true;
    HttpShellService.requireTokenRotation(context, "legacy");
    HttpShellService.resetTokenAfterRestore(context);
    assertFalse(HttpShellService.legacyTokenRotationPending(context));
    assertTrue(HttpShellService.currentToken().isEmpty());
    assertTrue(MissingRoot.written.isEmpty());
    // A restarted bridge must honor the durable marker even when a new home is now visible.
    ReflectionHelpers.setStaticField(HttpShellService.class, "rotationBlocked", false);
    MissingRoot.directory = context.getFilesDir();
    MissingRoot.failWrite = true;
    assertTrue(HttpShellService.currentToken().isEmpty());
    assertTrue(MissingRoot.written.isEmpty());
    MissingRoot.failWrite = false;
    String projected = HttpShellService.currentToken();
    assertFalse(projected.isEmpty());
    assertTrue(projected.equals(MissingRoot.written));
  }
}
