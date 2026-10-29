package com.deepseekharness.app.util;

/** Installed identity is supplied by BuildConfig; stale bundled notes never inherit its title. */
public final class CurrentReleaseNotes {
  private CurrentReleaseNotes() {}

  public static String versionLabel(String versionName) {
    java.util.regex.Matcher diagnostic =
        java.util.regex.Pattern.compile("-\\d{8}\\.\\d{4}-dsh").matcher(versionName);
    return diagnostic.find() ? versionName.substring(0, diagnostic.start()) : versionName;
  }

  private static String bundledVersion(String versionName) {
    // 正式包与 -Pdsha.sunsetTest=true 测试包共用同一份更新说明；测试后缀不是发布身份。
    if (versionName.endsWith("-test"))
      versionName = versionName.substring(0, versionName.length() - "-test".length());
    return versionName;
  }

  public static boolean matches(String versionName, int versionCode, String notes) {
    if (notes == null) return false;
    int end = notes.indexOf('\n');
    String heading = (end < 0 ? notes : notes.substring(0, end)).trim();
    return heading.equals("# DSHA " + bundledVersion(versionName) + " / build" + versionCode);
  }

  public static String render(String versionName, int versionCode, String notes, boolean english) {
    return render(versionName, versionCode, notes, english ? "en" : "zh");
  }

  public static String render(String versionName, int versionCode, String notes, String language) {
    if (!UiLanguagePreference.isSupportedLanguage(language))
      throw new IllegalArgumentException("UI_LANGUAGE");
    String title = "DSHA " + versionName + " / build" + versionCode;
    if (!matches(versionName, versionCode, notes))
      return title
          + "\n\n"
          + UiText.choose(
              language,
              "本安装包未附当前版本的更新说明。",
              "This installation does not include release notes for the current build.");
    int end = notes.indexOf('\n');
    String body = end < 0 ? "" : notes.substring(end + 1).trim();
    return title + (body.isEmpty() ? "" : "\n\n" + body);
  }
}
