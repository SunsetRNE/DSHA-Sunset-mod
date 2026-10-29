package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import org.junit.Test;

public class WebPageScriptsTest {
  @Test
  public void focusQuestionFindsTheCardAndFieldWithoutForcingFullscreen() {
    String script = WebPageScripts.focusQuestion();
    assertTrue(script.contains("Mbwy4a_frame"));
    assertTrue(script.contains("Mbwy4a_fieldInput"));
    assertTrue(script.contains("scrollIntoView"));
    assertTrue(script.contains(".focus("));
    // 全屏回答必须由用户手动点卡片上的按钮，通知入口不能替用户展开。
    assertFalse(script.contains("question-expand"));
    assertFalse(script.contains("dataset.mobileNavQuestionFull"));
  }
}
