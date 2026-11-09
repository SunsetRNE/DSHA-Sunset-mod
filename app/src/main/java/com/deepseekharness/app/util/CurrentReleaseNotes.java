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
    // 测试变体当前用 -SunsetTest（CI 与发布脚本按此核验），历史上也出现过 -test，两者都要剥离。
    // -EmulatorX64Debug 是 x86 调试目标的 versionNameSuffix（见 app/build.gradle）；
    // 不剥离的话，模拟器包里的「更新说明」恒为空，并写一条 changelog 诊断。
    for (String suffix : new String[] {"-SunsetTest", "-test", "-EmulatorX64Debug"}) {
      if (versionName.endsWith(suffix)) {
        versionName = versionName.substring(0, versionName.length() - suffix.length());
        break;
      }
    }
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
