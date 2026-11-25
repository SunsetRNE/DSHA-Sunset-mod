package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WebLegacyExitDomainTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class Observation extends WebProcessManager {
    private final File domain;
    boolean domainMoves;
    int stoppedChecks;

    Observation(File domain) {
      super(null);
      this.domain = domain;
    }

    @Override
    File root() {
      return domain;
    }

    @Override
    public boolean confirmNoUntrackedWebLaunchers() {
      return true;
    }

    @Override
    public boolean observeStopped(boolean trackedProcessAlive) throws IOException {
      stoppedChecks++;
      if (domainMoves) Files.delete(domain.toPath());
      return true;
    }
  }

  @Test
  public void aMissingOrMovingDomainDoesNotProveTheOldWebIsGone() throws Exception {
    File domain = new File(temporary.getRoot(), "records");
    var observation = new Observation(domain);
    assertFalse(observation.observeLegacySessionExited());
    assertEquals(0, observation.stoppedChecks);

    Files.createDirectory(domain.toPath());
    assertTrue(observation.observeLegacySessionExited());
    assertEquals(1, observation.stoppedChecks);

    observation.domainMoves = true;
    assertFalse(observation.observeLegacySessionExited());
    assertEquals(2, observation.stoppedChecks);
  }

  @Test
  public void aRealSymlinkDomainDoesNotProveTheOldWebIsGone() throws Exception {
    var target = temporary.newFolder("target").toPath();
    var link = temporary.getRoot().toPath().resolve("records-link");
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException unavailable) {
      assumeNoException("real symbolic links required", unavailable);
    }
    assertTrue(Files.isSymbolicLink(link));
    assertTrue(Files.isDirectory(link));
    var observation = new Observation(link.toFile());
    assertFalse(observation.observeLegacySessionExited());
    assertEquals(0, observation.stoppedChecks);
  }
}
