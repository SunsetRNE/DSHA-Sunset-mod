/* Dock · 滚动链防护（极简版）
 *
 * 只做一件事：给 html/body 加 overscroll-behavior:none，挡掉下拉刷新 / 橡皮筋。
 *
 * 曾经还有一条 `*{overscroll-behavior:contain}`，用来防「内层滚到底把外层文档带出去」，
 * 4.1.5 删掉了。原因（真机 + 容器实验都复现）：
 *   火山控制台是双层滚动 —— 进入后 document 被自动滚下去一截，内层容器自己也能滚。
 *   手指落在内层上往下拉时，内层已经在顶，剩下的滚动本该沿滚动链传给 document 让它回滚；
 *   contain 把这条链掐断，document 永远回不去顶部，表现为「顶部栏滑不出来、双指却能」。
 *   双指是合成器手势滚动，直接作用在主框架，不经过元素级滚动链，所以 contain 管不住。
 *   代价：内层滚到底后页面会被带出去一点（穿透），比「顶部回不去」轻。
 *
 * 之前那套「锁 html/body overflow + document.scrollTop 拉回 0」已删除，原因：
 *   1. 拿 WebView 140 的合成层白块没办法 —— 那是 WebView 自己的 bug；
 *   2. 在「文档自己就是主滚动」的页面（火山控制台、接口文档）会误锁，
 *      表现为页面滚不动，用户只能双指缩放才能恢复。
 * 现在只留 CSS，不改任何布局、不碰 scrollTop，不会再误伤滚动。
 */
(function () {
  var ID = 'whale-guard';
  try {
    var st = document.getElementById(ID);
    if (!st) {
      st = document.createElement('style');
      st.id = ID;
      (document.head || document.documentElement).appendChild(st);
    }
    // 只挡视口级的下拉刷新/橡皮筋；不给元素加 contain，否则会掐断正常滚动链（见文件头）
    var css = 'html,body{overscroll-behavior:none!important}';
    if (st.textContent !== css) st.textContent = css;
  } catch (e) {}
})();
