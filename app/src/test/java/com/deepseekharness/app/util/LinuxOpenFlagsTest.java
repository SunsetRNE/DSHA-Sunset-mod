package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import org.junit.Test;

public final class LinuxOpenFlagsTest {
  @Test
  public void armAndArm64RetainTheirDirectoryFlag() throws IOException {
    assertEquals(040000, LinuxOpenFlags.directoryFlag(0100000));
  }

  @Test
  public void x86AndX8664UseTheirDirectoryFlag() throws IOException {
    assertEquals(00200000, LinuxOpenFlags.directoryFlag(00400000));
  }

  @Test
  public void unknownPlatformRefusesInsteadOfGuessing() {
    for (int noFollow : new int[] {0, 1, 01400000}) {
      assertEquals(
          "O_DIRECTORY_LAYOUT_UNSUPPORTED",
          assertThrows(IOException.class, () -> LinuxOpenFlags.directoryFlag(noFollow))
              .getMessage());
    }
  }
}
