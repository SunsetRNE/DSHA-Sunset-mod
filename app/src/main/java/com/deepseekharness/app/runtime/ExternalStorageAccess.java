package com.deepseekharness.app.runtime;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.content.pm.PackageManager;

/** Single fail-closed check for host shared-storage exposure to the container. */
public final class ExternalStorageAccess {
  private ExternalStorageAccess() {}

  public static boolean granted(Context context) {
    if (context == null) return false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        return Environment.isExternalStorageManager();
      } catch (RuntimeException denied) {
        return false;
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
      return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
          == PackageManager.PERMISSION_GRANTED;
    return true;
  }
}
