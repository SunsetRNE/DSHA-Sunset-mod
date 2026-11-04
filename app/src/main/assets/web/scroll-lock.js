/* Dock · 整页拖不动（v2）
 *
 * 背景（为什么需要这段）：
 *   WebView 布局成 1280*d × 720*d，再用 View.setScaleX/Y 压进 16:9 区域。
 *   但 visualViewport 绑的是「窗口可见尺寸」，不是 View 布局尺寸 —— 页面明明
 *   不能滚（DOC 720/720、IME 0），手指仍能把它整页平移（scrollY = vv.offsetTop ≠ 0）。
 *   这是 WebView 自己的口径，改不了；只能在页面侧拦 + 让原生把平移量归零。
 *
 * 这个脚本只做两件事：
 *   1) 拦截：文档不可滚时，touchmove 落在「自己能滚」的容器上放行（竖列表 / 横滑条都算），
 *      落在空白处 preventDefault。普通长页面（document 自己能滚）完全不拦。
 *   2) 上报：把「文档能不能滚」的变化告诉原生（WhaleBridge.postMessage），
 *      原生在停手 300ms 后 scrollTo(0,0) 把视觉视口拉回去。
 *      —— 以前这里是原生每 1.5s 注入一段 JS 轮询，改成事件驱动后，
 *         页面静止时零开销、后台不烧电、Activity 销毁也不会留下死循环。
 *
 * 性能：每个手势只在 touchstart 判一次容器（getComputedStyle 向上遍历很贵，
 * 不能在 touchmove 里逐帧算），touchmove 只剩两个布尔判断。
 */
(function () {
  try {
    if (window.__dockScrollLock) {
      if (window.__dockScrollLock.report) window.__dockScrollLock.report();
      return;
    }
  } catch (e) { return; }

  var reportedDocCan = null;   // 已经上报给原生的「文档可滚」状态
  var touchCanPan = false;     // 本次手势是否落在「自己能滚」的容器里（touchstart 时定一次）
  var touchDocCan = false;     // 本次手势开始时文档能不能滚

  function docEl() {
    return document.scrollingElement || document.documentElement || document.body;
  }

  function docScrollable() {
    try {
      var d = docEl();
      return !!d && (d.scrollHeight || 0) > (d.clientHeight || 0) + 2;
    } catch (e) { return false; }
  }

  /**
   * 手指按下去的这个位置，它自己能不能滚？
   *
   * 两个坑都是真机/试验台撞出来的（试验台 S3 / S6 / S12），4.4.0 的旧版两个都踩：
   *   1. 别在 body 之前收工 —— 一批页面把滚动放在 body 上（html{overflow:hidden}），
   *      走到 body 就 return false 会把整页钉死；
   *   2. 别只看 overflowY —— 横滑代码块 / tab 栏（overflow-x:auto）被 preventDefault
   *      拦掉的表现是「滑不动」，肉眼很难归因到「整页锁」。
   * 所以：html/body 一起进链，横竖两轴都认。
   */
  /** 往上一层：跨 shadow root 也能继续走（parentElement 到 shadow root 就断了） */
  function parentOf(el) {
    var p = el.parentElement;
    if (p) return p;
    try {
      var r = el.getRootNode && el.getRootNode();
      if (r && r.host) return r.host;
    } catch (e) {}
    return null;
  }

  /**
   * 真正被按到的那个节点。
   * 事件从 shadow root 里冒出来时，document 上看到的 e.target 已经被重定向成宿主元素了
   * （composed 事件跨影子边界会 retarget），拿 e.target 判容器会把 shadow 里的滚动区判成「不能滚」。
   */
  function originOf(e) {
    try {
      var path = e.composedPath && e.composedPath();
      if (path && path.length) return path[0];
    } catch (err) {}
    return e.target;
  }

  function canPanFrom(el) {
    try {
      while (el && el.nodeType === 1) {
        var cs = getComputedStyle(el);
        if (cs) {
          var oy = cs.overflowY, ox = cs.overflowX;
          if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay')
              && el.scrollHeight > el.clientHeight + 2) return true;
          if ((ox === 'auto' || ox === 'scroll' || ox === 'overlay')
              && el.scrollWidth > el.clientWidth + 2) return true;
        }
        el = parentOf(el);
      }
    } catch (e) {}
    return false;
  }

  function report() {
    var can = docScrollable();
    if (can === reportedDocCan) return;
    var bridge = null;
    try {
      if (window.WhaleBridge && typeof window.WhaleBridge.postMessage === 'function') bridge = window.WhaleBridge;
    } catch (e) {}
    // 桥还没挂上来（注入早于 WebMessageListener、或页面自己改过 window.WhaleBridge）：
    // 这次不算数，别把状态记成「已上报」—— 否则原生永远等不到真相，停手复位就成了摆设。
    if (!bridge) return;
    reportedDocCan = can;
    try { bridge.postMessage(JSON.stringify({ t: 'doc', c: can })); } catch (e) {}
    syncMoveHandler(!can);
  }

  /* 合并同一帧内的多次请求：路由切换时 DOM 会连续变几十次 */
  var rafReport = null;
  function reportSoon() {
    if (rafReport) return;
    try {
      rafReport = requestAnimationFrame(function () { rafReport = null; report(); });
    } catch (e) { rafReport = null; report(); }
  }

  /** 文档本身是不是比视口宽（宽表格 / 固定 1440 布局在 1280 视口里就是这种） */
  function docWide() {
    try {
      var d = docEl();
      return !!d && (d.scrollWidth || 0) > (d.clientWidth || 0) + 2;
    } catch (e) { return false; }
  }

  function rememberGesture(e) {
    touchDocCan = docScrollable();
    var origin = originOf(e);
    touchCanPan = !!(origin && origin.nodeType === 1 && canPanFrom(origin));
    var t0 = e.touches && e.touches[0];
    startX = t0 ? t0.clientX : null;
    startY = t0 ? t0.clientY : null;
    sawStart = true;
  }

  /* 键盘/输入焦点：绝不拦 touchmove。拦了会让输入框选区、输入法候选、
     页面自动上推都失灵，表现就是「一用键盘就跳 / 滑不动」。 */
  function isEditing() {
    try {
      var el = document.activeElement;
      return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
    } catch (e) { return false; }
  }

  function onMove(e) {
    if (isEditing()) return;
    /* 有一种页面会把 touchstart 吞掉（手势库在 window 捕获阶段 stopImmediatePropagation，
       我们挂在 document 上的监听根本收不到）。这时缓存还停着上一把手势的值，
       照着用就会把「该能滚的列表」钉死 —— 试验台 N1 就是这条。
       判据用「距上一帧 touchmove 隔了多久」而不是计时器：新手势必然有停顿，
       连续拖动时两帧相隔十几毫秒，不会误触发重算。 */
    var gap = e.timeStamp - lastMoveAt;
    lastMoveAt = e.timeStamp;
    if (!sawStart || gap > 300) rememberGesture(e);

    if (touchDocCan) return;                                   // 普通长页面：不拦
    if (e.touches && e.touches.length !== 1) return;            // 双指交给页面自己
    if (touchCanPan) return;                                   // 容器自己滚 / 横滑：放行

    /* 横着拖、而文档本身比视口宽：那是真的能横滚（宽表格 / 固定大布局），
       替它拦住就等于把右半边藏起来。竖向为主的拖动仍然拦，别用这条开洞。 */
    var t = e.touches && e.touches[0];
    if (t && startX !== null) {
      var adx = Math.abs(t.clientX - startX), ady = Math.abs(t.clientY - startY);
      if (adx > ady * 1.5 && docWide()) return;
    }
    if (e.cancelable) e.preventDefault();
  }

  /* 只在「文档真的不可滚」时才挂这个非 passive 的 touchmove。
     非 passive 监听会让浏览器每次手势都先问一遍 JS 线程能不能滚，
     可滚页面（大多数）因此白担一次启动延迟；不可滚页面反正要拦，代价才付得值。
     touchstart 里再同步一次：即使状态是靠上一帧的 report 判的，也赶得上这一下手势。 */
  var moveAttached = false;
  var sawStart = false;          // 本次手势有没有收到过 touchstart
  var startX = null, startY = null;   // 手势起点（判横向为主用）
  var lastMoveAt = -1e9;         // 上一帧 touchmove 的时间戳（判「新手势」用）
  function syncMoveHandler(need) {
    var want = (need === undefined) ? !docScrollable() : need;
    if (want === moveAttached) return;
    if (want) document.addEventListener('touchmove', onMove, { passive: false });
    else document.removeEventListener('touchmove', onMove, { passive: false });
    moveAttached = want;
  }

  document.addEventListener('touchstart', function (e) {
    lastMoveAt = e.timeStamp;
    rememberGesture(e);
    syncMoveHandler(!touchDocCan);
    reportSoon();
  }, { passive: true, capture: true });

  document.addEventListener('touchend', function () { sawStart = false; }, { passive: true, capture: true });
  document.addEventListener('touchcancel', function () { sawStart = false; }, { passive: true, capture: true });

  window.addEventListener('resize', reportSoon, { passive: true });
  window.addEventListener('scroll', reportSoon, { passive: true });
  document.addEventListener('scroll', reportSoon, { passive: true, capture: true });

  /* SPA 换路由/换容器后文档高度会变，但可能一个事件都不发 —— 兜一层 MutationObserver */
  try {
    if (window.MutationObserver) {
      var moT = null;
      new MutationObserver(function () {
        if (moT) clearTimeout(moT);
        moT = setTimeout(report, 400);
      }).observe(document.documentElement, { childList: true, subtree: true });
    }
  } catch (e) {}

  window.__dockScrollLock = { report: report };
  syncMoveHandler();
  report();
})();
