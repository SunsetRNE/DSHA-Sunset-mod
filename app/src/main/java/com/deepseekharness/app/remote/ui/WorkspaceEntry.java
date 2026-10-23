package com.deepseekharness.app.remote.ui;

import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.ui.MainActivity;

/** Native workspace selection precedes every local-environment gate. */
public final class WorkspaceEntry {
  public static final String LOCAL = "dsha.workspace.local";

  private WorkspaceEntry() {}

  public static boolean routeToChooser(Intent intent) {
    return intent != null
        && choose(intent.getBooleanExtra(LOCAL, false)
                || intent.getBooleanExtra("open_web", false)
                || intent.getBooleanExtra("open_plugins", false)
                || intent.getBooleanExtra("open_terminal", false)
                || intent.getBooleanExtra("open_install", false),
            Intent.ACTION_MAIN.equals(intent.getAction()),
            intent.hasCategory(Intent.CATEGORY_LAUNCHER));
  }

  static boolean choose(boolean local, boolean mainAction, boolean launcherCategory) {
    return !local && mainAction && launcherCategory;
  }

  public static Intent local(Context context) {
    return new Intent(context, MainActivity.class)
        .putExtra(LOCAL, true)
        .putExtra("open_launch", true)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
  }
}
