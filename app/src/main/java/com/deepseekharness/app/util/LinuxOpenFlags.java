package com.deepseekharness.app.util;

import java.io.IOException;

/** Linux UAPI flag pairs used by Android libcore's open. */
public final class LinuxOpenFlags {
  private LinuxOpenFlags() {}

  /**
   * OsConstants supplies O_NOFOLLOW from the platform running libcore. Select the matching
   * O_DIRECTORY flag even if the APK's native libraries use a translated instruction set.
   */
  public static int directoryFlag(int noFollow) throws IOException {
    return switch (noFollow) {
      case 0100000 -> 040000; // NDK ARM and ARM64 asm/fcntl.h.
      case 00400000 -> 00200000; // NDK x86 and x86_64 use asm-generic/fcntl.h.
      default -> throw new IOException("O_DIRECTORY_LAYOUT_UNSUPPORTED");
    };
  }
}
