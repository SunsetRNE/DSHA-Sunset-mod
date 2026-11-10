package com.deepseekharness.app.ui;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class WebPageScripts {
  private WebPageScripts() {}

  /** 应急页只注入语言与语法兼容，不注册正式设备/插件桥。 */
  public static String emergencyCompatibility(Context context) {
    return language(context)
        + "\n"
        + read(context, "web-integration/es-compat.js")
        + "\n"
        + read(context, "web-integration/compat.js");
  }

  public static String compatibility(Context context) {
    return "window.__DSHA_NATIVE_PLUGINS__=true;\n"
        + read(context, "web-integration/safe-area.js")
        + "\n"
        + touchFeedback()
        + "\n"
        + language(context)
        + "\n"
        + theme(context)
        + "\n"
        + read(context, "web-integration/es-compat.js")
        + "\n"
        + read(context, "web-integration/compat.js")
        + "\n"
        + read(context, "web-integration/files.js")
        + "\n"
        + read(context, "bridge-token-compat.cjs")
        + "\n"
        + read(context, "web-integration/startup.js");
  }

  /**
   * DSH Web 内部触控与按键反馈：
   * 1. 彻底清除 Android WebView 默认的淡蓝点击高亮框 (-webkit-tap-highlight-color: transparent)；
   * 2. 为按钮、胶囊、菜单项、会话行提供至少 120ms 的独立 scale 按压缩放 + 表面高亮；
   * 3. 通过 DshaHaptic 桥接触发原生触觉震动反馈。
   */
  public static String touchFeedback() {
    return "(function(){\n"
        + "  if(window.top!==window)return;\n"
        + "  if(window.__dshaTouchFeedbackInstalled)return;\n"
        + "  window.__dshaTouchFeedbackInstalled=true;\n"
        + "  var CSS='*,*::before,*::after,html,body{-webkit-tap-highlight-color:transparent!important;}'\n"
        + "    +'button,[role=\"button\"],[role=\"menuitem\"],[role=\"menuitemradio\"],[role=\"menuitemcheckbox\"],[role=\"option\"],[role=\"tab\"],[role=\"treeitem\"],[role=\"combobox\"],[role=\"switch\"],[aria-haspopup],[data-trigger-menu],[data-mobile-nav]:not([data-mobile-nav=\"frame\"]):not([data-mobile-nav=\"backdrop\"]),a[href]{transition:scale .14s cubic-bezier(.22,1,.36,1),filter .14s ease,opacity .14s ease,background-color .14s ease,box-shadow .14s ease!important;}'\n"
        + "    +'[data-dsha-press-round=\"1\"]{border-radius:8px!important;}'\n"
        + "    +'[data-dsha-press=\"icon\"]{scale:.92!important;opacity:.85!important;filter:brightness(.92)!important;}'\n"
        + "    +'[data-dsha-press=\"chip\"]{scale:.96!important;opacity:.88!important;filter:brightness(.94)!important;box-shadow:inset 0 0 0 100vmax var(--dsw-alias-interactive-bg-active,rgba(120,128,140,.11))!important;}'\n"
        + "    +'[data-dsha-press=\"row\"]{scale:.985!important;opacity:.90!important;box-shadow:inset 0 0 0 100vmax var(--dsw-alias-interactive-bg-active,rgba(120,128,140,.10))!important;}'\n"
        + "    +'body[data-ds-dark-theme] [data-dsha-press=\"icon\"],html[data-ds-dark-theme] [data-dsha-press=\"icon\"],body[data-ds-dark-theme] [data-dsha-press=\"chip\"],html[data-ds-dark-theme] [data-dsha-press=\"chip\"]{filter:brightness(1.18)!important;}';\n"
        + "  function ensureStyle(){\n"
        + "    var root=document.head||document.documentElement;\n"
        + "    if(!root||document.getElementById('dsha-touch-feedback-style'))return;\n"
        + "    var st=document.createElement('style');\n"
        + "    st.id='dsha-touch-feedback-style';\n"
        + "    st.textContent=CSS;\n"
        + "    root.appendChild(st);\n"
        + "  }\n"
        + "  ensureStyle();\n"
        + "  var SEL='button,[role=\"button\"],[role=\"menuitem\"],[role=\"menuitemradio\"],[role=\"menuitemcheckbox\"],[role=\"option\"],[role=\"tab\"],[role=\"treeitem\"],[role=\"combobox\"],[role=\"switch\"],[aria-haspopup],[data-trigger-menu],[data-mobile-nav]:not([data-mobile-nav=\"frame\"]):not([data-mobile-nav=\"backdrop\"]),[data-session-id],a[href],[tabindex]:not(textarea):not(input):not([contenteditable=\"true\"]):not([tabindex=\"-1\"])';\n"
        + "  var activeEl=null,activeAt=0,clearTimer=0;\n"
        + "  function clearPress(immediate){\n"
        + "    if(!activeEl)return;\n"
        + "    var el=activeEl;\n"
        + "    var delay=immediate?0:Math.max(0,120-(performance.now()-activeAt));\n"
        + "    activeEl=null;\n"
        + "    clearTimeout(clearTimer);\n"
        + "    clearTimer=setTimeout(function(){\n"
        + "      el.removeAttribute('data-dsha-press');\n"
        + "      el.removeAttribute('data-dsha-press-round');\n"
        + "    },delay);\n"
        + "  }\n"
        + "  window.addEventListener('pointerdown',function(e){\n"
        + "    ensureStyle();\n"
        + "    var t=e.target;\n"
        + "    if(!t||typeof t.closest!=='function')return;\n"
        + "    var el=t.closest(SEL);\n"
        + "    if(!el||el===document.body||el===document.documentElement||el.disabled||el.getAttribute('aria-disabled')==='true'||el.hasAttribute('data-composer-card'))return;\n"
        + "    var r=el.getBoundingClientRect();\n"
        + "    if(r.width<12||r.height<12||r.height>160)return;\n"
        + "    clearPress(true);\n"
        + "    var kind=(r.width<=52&&r.height<=52)?'icon':(r.width<220?'chip':'row');\n"
        + "    el.setAttribute('data-dsha-press',kind);\n"
        + "    var br=getComputedStyle(el).borderRadius;\n"
        + "    if(!br||br==='0px'||br==='0')el.setAttribute('data-dsha-press-round','1');\n"
        + "    activeEl=el;activeAt=performance.now();\n"
        + "    try{window.DshaHaptic?.postMessage('tap');}catch(_){}\n"
        + "  },{capture:true,passive:true});\n"
        + "  window.addEventListener('pointerup',function(){clearPress(false);},{capture:true,passive:true});\n"
        + "  window.addEventListener('pointercancel',function(){clearPress(true);},{capture:true,passive:true});\n"
        + "  window.addEventListener('scroll',function(){clearPress(true);},{capture:true,passive:true});\n"
        + "})();";
  }

  public static String compatibility(
      Context context, com.deepseekharness.app.util.StartupPageGate gate) {
    return "window.__DSHA_PAGE_BINDING__={nonce:'"
        + gate.nonce()
        + "'};\n"
        + compatibility(context)
        + "\n//# sourceURL="
        + gate.source();
  }

  /**
   * 原生亮暗偏好交给集成插件（dsha-native-theme-sync）；偏好值经 normalize 只可能是三个常量之一。
   * 应急页不注入：应急 DSH 没有集成插件。
   */
  public static String theme(Context context) {
    var config = new com.deepseekharness.app.core.ConfigStore(context);
    return "window.__DSHA_THEME__={preference:'"
        + config.getUiTheme()
        + "',sync:"
        + config.isWebThemeSync()
        + "};window.dispatchEvent(new CustomEvent('dsha-theme'));";
  }

  public static String language(Context context) {
    String id = new com.deepseekharness.app.core.ConfigStore(context).getUiLanguage();
    return "window.__DSHA_LANGUAGE__='"
        + id
        + "';window.dispatchEvent(new CustomEvent('dsha-language'));"
        + "if(!window.__dshaLanguageSelectionBound){window.__dshaLanguageSelectionBound=true;window.addEventListener('dsha-language-selected',e=>{if(['zh','zh-Hant','en','ru','ja','ko','ar','pt','la','fr'].includes(e.detail))window.DshaLanguage?.postMessage(e.detail);});}";
  }

  private static String read(Context context, String path) {
    try (InputStream in = context.getAssets().open(path)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      return "";
    }
  }

  public static String back(Context context) {
    try (InputStream in = context.getAssets().open("web-integration/page.js")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return "(function(){"
          + new String(out.toByteArray(), StandardCharsets.UTF_8)
          + ";return window.__dshaPageBack();})()";
    } catch (IOException error) {
      return "false";
    }
  }

  public static org.json.JSONObject presentationBinding(
      com.deepseekharness.app.util.BrowserThemeGate gate) {
    var value = new org.json.JSONObject();
    try {
      value
          .put("nonce", gate.nonce())
          .put("generation", gate.identity().generation())
          .put("instance", gate.identity().instanceId());
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return value;
  }

  public static String presentation(
      Context context, com.deepseekharness.app.util.BrowserThemeGate gate) {
    return touchFeedback()
        + "\n"
        + read(context, "web-integration/ime.js")
        + "\n"
        + read(context, "web-integration/page.js")
        + "\nwindow.__dshaNativePresentation.install("
        + presentationBinding(gate).toString()
        + ");";
  }

  public static org.json.JSONObject sessionRoute(
      android.content.Intent intent, com.deepseekharness.app.util.BrowserThemeGate gate) {
    var request = presentationBinding(gate);
    try {
      request
          .put("sessionId", intent.getStringExtra(BrowserConversationRoute.SESSION))
          .put("requestToken", intent.getStringExtra(BrowserConversationRoute.REQUEST));
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return request;
  }

  /**
   * 进入会话后把待答问题滚进可视区；没有选项时才聚焦回答框。
   *
   * <p>判据是提问卡里有没有实际选项行 {@code Mbwy4a_optionRow}。选项容器
   * {@code Mbwy4a_options} 对每张提问卡都会渲染（选项为空时只剩空容器），用它判断会恒真，
   * 任何问题都不会聚焦，不能作为判据。
   *
   * <p>有选项时不能抢焦点：软键盘弹起后提问卡的底部「提交」会被挤出可视区，用户点完
   * 选项找不到提交，表现成“选项点不动”。卡片由网页侧插件渲染，类名带宿主哈希前缀，
   * 因此按前缀匹配并轮询等待渲染完成。
   */
  public static String focusQuestion() {
    return "(function(){var tries=0;function find(){"
        + "var card=document.querySelector('[class*=\"Mbwy4a_frame\"]');"
        + "if(!card)return null;"
        + "if(!card.querySelector('[class*=\"Mbwy4a_body\"]'))return null;"
        + "var field=card.querySelector('[class*=\"Mbwy4a_fieldInput\"]');"
        + "if(!field)field=card.querySelector('[class*=\"Mbwy4a_body\"] textarea');"
        + "if(!field)field=card.querySelector('[class*=\"Mbwy4a_body\"] input');"
        + "return field?{card:card,field:field}:null;}"
        + "function tick(){var hit=find();if(hit){try{hit.card.scrollIntoView({block:'center'});"
        + "if(!hit.card.querySelector('[class*=\"Mbwy4a_optionRow\"]'))"
        + "hit.field.focus({preventScroll:true});}catch(e){}return;}"
        + "if(++tries<40)setTimeout(tick,250);}tick();})();";
  }

  /** 帧只携带几何与本页身份，不包含 URL、输入内容或模型资料。 */
  public static org.json.JSONObject imeFrame(
      com.deepseekharness.app.util.BrowserThemeGate gate, org.json.JSONObject frame) {
    org.json.JSONObject request = presentationBinding(gate);
    try {
      for (String key : new String[] {"frameId", "epoch", "width", "height", "phase"})
        if (frame.has(key)) request.put(key, frame.get(key));
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return request;
  }
}
