/* Dock · 网页滚动位置跟踪（v2）
 *
 * 解决 v1 的三个毛病：
 *   1. v1 用 querySelectorAll('*') 只挑「面积最大的可滚元素」，完全排除了 document 级滚动；
 *      用户滚的是页面（事件 target = document，nodeType 9），v1 直接跳过 → 条永远跟错容器。
 *   2. v1 选中的容器一旦可滚就永远不换，React 换容器后也不重选 → 条卡死在旧容器进度上。
 *   3. v1 每 800ms 全站扫一遍 getComputedStyle，长页面掉帧；停手 220ms 就停 rAF，
 *      最后一帧可能漏掉「到底」。
 *
 * v2 的口径：
 *   - 谁真的在滚（最近一次 scroll 事件的 target）就跟谁，document 归一到 scrollingElement；
 *   - 上报的是归一化进度 frac(0..1) + 可视比例 view(0..1) + can，native 只负责画，不再猜尺寸；
 *   - 停 rAF 前补一帧，惯性结束（touchend 后 520ms）再补一帧；
 *   - 只有 active 失效时才重选容器，重选延迟到 DOM 稳定之后。
 */
(function () {
  try {
    if (window.__whaleScroll) {
      if (window.__whaleScroll.kick) window.__whaleScroll.kick();
      return;
    }
  } catch (e) { return; }

  var lastFrac = -1, lastView = -1, lastCan = null;
  var raf = null, stopT = null, tailT = null, scanT = null;

  function bridge() {
    try {
      if (!window.WhaleBridge || typeof window.WhaleBridge.postMessage !== 'function') return null;
      return {
        state: function (f, v, c) {
          window.WhaleBridge.postMessage(JSON.stringify({ t: 'state', f: f, v: v, c: c }));
        }
      };
    } catch (e) { return null; }
  }

  function docEl() {
    return document.scrollingElement || document.documentElement || document.body;
  }

  function canScroll(el) {
    if (!el || el.nodeType !== 1) return false;
    try { return (el.scrollHeight || 0) > (el.clientHeight || 0) + 4; } catch (e) { return false; }
  }

  function visible(el) {
    try {
      var cs = getComputedStyle(el);
      if (!cs || cs.display === 'none' || cs.visibility === 'hidden') return false;
      var r = el.getBoundingClientRect();
      return r.width > 40 && r.height > 40 && r.bottom > 0 && r.top < window.innerHeight;
    } catch (e) { return false; }
  }

  // 只在「当前容器失效」时重选，且只扫「主内容容器」而不是全站：
  // 长页面（几万节点）的全站扫描是 v1 掉帧的根因；这里先找 main/article/[data-dsh-scroll]，
  // 找不到再退回 body，并对总扫描量设硬上限。
  var MAX_SCAN = 4000;

  function consider(el, state) {
    if (!el || !canScroll(el)) return;
    var cs;
    try { cs = getComputedStyle(el); } catch (e) { return; }
    var ov = cs ? cs.overflowY : '';
    if (ov !== 'auto' && ov !== 'scroll' && ov !== 'overlay') return;
    if (!visible(el)) return;
    var r = el.getBoundingClientRect();
    var a = r.width * r.height;
    if (a > state.area) { state.area = a; state.el = el; }
  }

  function pickMain() {
    var state = { el: null, area: 0 };
    var roots = [];
    try {
      var scoped = document.querySelector('main,article,[data-dsh-scroll]');
      if (scoped) roots.push(scoped);
    } catch (e) {}
    if (document.body) roots.push(document.body);
    else if (document.documentElement) roots.push(document.documentElement);
    var scanned = 0;
    for (var r = 0; r < roots.length && scanned < MAX_SCAN; r++) {
      var root = roots[r];
      consider(root, state);
      scanned++;
      var list;
      try { list = root.querySelectorAll('*'); } catch (e) { continue; }
      var limit = Math.min(list.length, MAX_SCAN - scanned);
      for (var i = 0; i < limit; i++) { consider(list[i], state); scanned++; }
    }
    return state.el;
  }

  function metrics(el) {
    var top = 0, sh = 0, ch = 0;
    try { top = el.scrollTop || 0; sh = el.scrollHeight || 0; ch = el.clientHeight || 0; } catch (e) {}
    var max = sh - ch;
    var frac = 0;
    if (max > 0) {
      frac = top / max;
      // 半像素级吸附：差 1px 到底就是到底，别在 0.99x 上抖
      if (top <= 0.5) frac = 0;
      else if (top >= max - 0.5) frac = 1;
    }
    if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
    var view = sh > 0 ? ch / sh : 1;
    if (view < 0) view = 0; else if (view > 1) view = 1;
    return { frac: frac, view: view, can: max > 4 };
  }

  var mainCache = null, mainAt = 0;

  /**
   * 主滚动容器 —— 滚动条只代表「页面主滚动」，不是「最近摸过的小滚动区」。
   * 判据：比较「文档能滚多少」和「最大内层容器能滚多少」，谁长谁是主滚动。
   *   - 接口文档 / 火山控制台：document 能滚几千 px，内层只有代码块几百 px → document
   *   - DeepSeek 登录页：document 不能滚，内层主容器能滚 → 内层
   *   - 之前的 0.3 屏阈值在「document 能滚 250px、内层能滚 2000px」时会误判成 document，
   *     表现就是拖滚动条只动外层、主内容不动。
   */
  function mainForSeek() {
    var d = docEl();
    var dMax = d.scrollHeight - d.clientHeight;

    var now = Date.now();
    if (!mainCache || !mainCache.isConnected || now - mainAt >= 1500) {
      mainCache = pickMain();
      mainAt = now;
    }
    var inner = mainCache;
    var iMax = inner ? (inner.scrollHeight - inner.clientHeight) : 0;

    // 页面被 CSS zoom 放大后，html 高度也会跟着放大（document 凭空多出 (zoom-1)*视口 的可滚范围），
    // 「比大小」就会倒向 document，拖拽滚错层。放大时优先跟占屏的内层主容器。
    var z = 1;
    try { z = parseFloat(getComputedStyle(document.documentElement).zoom) || 1; } catch (e) {}
    if (z > 1.01 && inner && iMax > 100) {
      var ir = inner.getBoundingClientRect();
      var vw = window.innerWidth || 1, vh = window.innerHeight || 1;
      if (ir.width * ir.height >= vw * vh * 0.6) return inner;
    }

    if (inner && iMax > dMax * 1.5) return inner;   // 内层明显更长 → 内层是主滚动
    if (dMax > 4) return d;
    return inner || d;
  }

  function current() { return mainForSeek(); }

  function tick() {
    try {
      var el = current();
      var m = metrics(el);
      var b = bridge();
      if (!b) return;
      // 阈值压到亚像素级：老阈值 0.0015 在长页面上等于「内容滚 3px 才动一下」，
      // 这正是「距底 1 和距底 2 没区别」的量化来源
      if (m.can === lastCan
          && Math.abs(m.frac - lastFrac) < 0.00008
          && Math.abs(m.view - lastView) < 0.00008) return;
      lastFrac = m.frac;
      lastView = m.view;
      lastCan = m.can;
      b.state(m.frac, m.view, m.can);
    } catch (e) {}
  }

  function loop() { tick(); raf = requestAnimationFrame(loop); }

  function kick(tail) {
    if (!raf) { try { raf = requestAnimationFrame(loop); } catch (e) { raf = null; } }
    if (stopT) clearTimeout(stopT);
    stopT = setTimeout(function () {
      if (raf) { try { cancelAnimationFrame(raf); } catch (e) {} raf = null; }
      tick();                    // 停之前补最后一帧
    }, 260);
    if (tail) {
      if (tailT) clearTimeout(tailT);
      tailT = setTimeout(tick, 520);   // 惯性滑动结束后再采一次
    }
  }

  function onScroll(e) { kick(true); }

  document.addEventListener('scroll', onScroll, true);
  window.addEventListener('scroll', onScroll, true);
  document.addEventListener('touchmove', function () { kick(true); }, { passive: true });
  document.addEventListener('wheel', function () { kick(true); }, { passive: true });
  document.addEventListener('touchend', function () { kick(true); }, { passive: true });
  window.addEventListener('resize', function () { mainCache = null; kick(false); }, { passive: true });

  // React 换掉滚动容器后，等 DOM 稳住再重选一次（不做全站轮询）
  try {
    if (window.MutationObserver) {
      var mo = new MutationObserver(function () {
        if (scanT) clearTimeout(scanT);
        scanT = setTimeout(function () {
          if (!mainCache || !mainCache.isConnected) { mainCache = null; tick(); }
        }, 600);
      });
      mo.observe(document.documentElement, { childList: true, subtree: true });
    }
  } catch (e) {}

  window.__whaleScroll = {
    kick: function () { mainCache = null; kick(false); },
    /**
     * 原生拖拽滚动条时调用：把主滚动容器滚到 frac（0..1）。
     * 用「谁在滚」的同一个 active 判定，所以文档级和内层容器都能拖。
     */
    seek: function (frac) {
      try {
        if (!(frac >= 0)) return;
        if (frac > 1) frac = 1;
        var el = current();
        if (!el) return;
        var max = el.scrollHeight - el.clientHeight;
        if (max <= 4) return;
        var top = Math.round(max * frac);
        if (el === docEl()) {
          try { window.scrollTo(0, top); } catch (e) {}
        }
        el.scrollTop = top;
        kick(false);
      } catch (e) {}
    }
  };

  tick();
})();
