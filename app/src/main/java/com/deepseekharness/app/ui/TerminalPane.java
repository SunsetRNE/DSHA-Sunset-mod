package com.deepseekharness.app.ui;

/** 终端内容面板的窄接口；导航、会话目录与输入各自负责，不拥有进程生死。 */
public interface TerminalPane {
  void newSession();

  boolean selectSession(long id);

  /** 仅在宿主对指定会话取得明确确认后调用。 */
  void closeSession(long id);

  void changeFont(int deltaSp);

  void showKeyboard();

  void paste();

  void restoreInputFocus();

  boolean selectionBack();

  boolean isPty();

  interface Host {
    void onTerminalPaneChanged();

    void switchTerminalMode(boolean pty);

    void requestCloseSession(boolean pty, long id);
  }
}
