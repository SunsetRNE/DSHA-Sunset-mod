package com.deepseekharness.app.util;

/** 挂载点探针只上报固定状态与脱敏路径，并在显示空白提示前核验当前页面。 */
public final class WebRouteProbe {
  private WebRouteProbe() {}

  public static final String PREFIX = "[DSHA_ROUTE] ";
  public static final String ROUTE_BLANK = "WEB_ROUTE_BLANK";

  /** expectedUrl 是调用方编码为 JSON 的完整 URL，只用于核验当前文档，不写入控制台。 */
  public static String script(long generation, String expectedUrl) {
    return String.format(
        java.util.Locale.ROOT,
        """
        (function(generation, expectedUrl) {
          try {
            if (location.href !== expectedUrl) return;
            if (!window.__dshaErrorHook) {
              window.__dshaErrorHook = 1;
              var address = function(value) {
                return String(value || '').slice(0, 180).split('#', 1)[0].split('?', 1)[0];
              };
              var report = function(value) {
                try {
                  var message = String(value == null ? '' : value).slice(0, 300);
                  var now = Date.now();
                  if ((window.__dshaErrCount || 0) >= 20) return;
                  if (message === window.__dshaErrLast && now - (window.__dshaErrAt || 0) < 5000) return;
                  window.__dshaErrLast = message;
                  window.__dshaErrAt = now;
                  window.__dshaErrCount = (window.__dshaErrCount || 0) + 1;
                  console.error('[DSHA_JS_ERROR] ' + message);
                } catch (ignored) {}
              };
              window.addEventListener('error', function(event) {
                try {
                  report(address(event.message || event.error || 'error') + ' @ '
                    + address(event.filename) + ':' + (event.lineno || 0));
                } catch (ignored) {}
              }, true);
              window.addEventListener('unhandledrejection', function(event) {
                try {
                  var reason = event.reason;
                  report('unhandledrejection ' + address(reason && reason.message ? reason.message : reason));
                } catch (ignored) {}
              }, true);
            }
            var routeDocument = window.__dshaRouteProbeDocument;
            if (!routeDocument) {
              routeDocument = { initialUrl: expectedUrl, moved: false };
              window.__dshaRouteProbeDocument = routeDocument;
            } else if (routeDocument.initialUrl !== expectedUrl) routeDocument.moved = true;
            if (generation <= 0) return;

            var previous = window.__dshaRouteProbe;
            if (previous) previous.cancel();
            var timer, firstEmpty;
            var job = { generation: generation, cancel: cancel };
            window.__dshaRouteProbe = job;

            function cancel() {
              clearTimeout(timer);
              document.removeEventListener('visibilitychange', visibility);
              window.removeEventListener('pagehide', cancel);
              if (window.__dshaRouteProbe === job) delete window.__dshaRouteProbe;
            }

            function visibility() {
              if (job.confirm) {
                if (document.visibilityState !== 'visible') cancel();
                return;
              }
              clearTimeout(timer);
              firstEmpty = undefined;
              if (document.visibilityState === 'visible') arm(2200, false);
            }

            function arm(delay, second) {
              timer = setTimeout(function() {
                try { sample(second); } catch (ignored) { cancel(); }
              }, delay);
            }

            function deferred(el) {
              if (!el) return true;
              var style = el.style;
              return el.hidden || el.inert || el.getAttribute('aria-busy') === 'true'
                || el.hasAttribute('data-loading') || el.hasAttribute('data-deferred')
                || (style && (style.display === 'none' || style.visibility === 'hidden'
                  || style.opacity === '0' || style.contentVisibility === 'auto'
                  || style.contentVisibility === 'hidden'));
            }

            function visible(el) {
              if (typeof el.checkVisibility === 'function')
                return el.checkVisibility({opacityProperty: true, visibilityProperty: true,
                  checkOpacity: true, checkVisibilityCSS: true,
                  contentVisibilityAuto: true});
              var parent = el;
              for (var depth = 0; parent && depth < 12; depth++, parent = parent.parentElement) {
                var style = getComputedStyle(parent);
                if (style.display === 'none' || style.visibility === 'hidden'
                    || style.visibility === 'collapse' || style.opacity === '0'
                    || style.contentVisibility === 'hidden' || style.contentVisibility === 'auto')
                  return false;
              }
              return !parent && el.getClientRects().length > 0;
            }

            function generated(content) {
              return content && content !== 'none' && content !== 'normal';
            }

            function styled(effect) {
              return effect && effect !== 'none';
            }

            function emptyMountStyle(el) {
              var style = getComputedStyle(el);
              if (style.display === 'none' || style.visibility === 'hidden'
                  || style.visibility === 'collapse' || style.opacity === '0'
                  || style.contentVisibility === 'hidden' || style.contentVisibility === 'auto')
                return 'deferred';
              var background = style.backgroundColor;
              if ((background !== 'transparent' && background !== 'rgba(0, 0, 0, 0)')
                  || styled(style.backgroundImage) || styled(style.borderImageSource)
                  || styled(style.outlineStyle) || styled(style.boxShadow) || styled(style.filter)
                  || styled(style.backdropFilter) || generated(style.content)
                  || style.borderTopWidth !== '0px' || style.borderRightWidth !== '0px'
                  || style.borderBottomWidth !== '0px' || style.borderLeftWidth !== '0px'
                  || style.display.indexOf('list-item') !== -1 || el.hasAttribute('popover')
                  || generated(getComputedStyle(el, '::before').content)
                  || generated(getComputedStyle(el, '::after').content)) return 'unknown';
              return 'blank';
            }

            function measure(paintMs) {
              var body = document.body;
              if (document.readyState !== 'complete' || deferred(document.documentElement)
                  || deferred(body)) return ['deferred', ''];
              var ids = ['root', 'app', 'app-root', 'dsh-root', '__next', '__nuxt'];
              var mount, name = '';
              for (var index = 0; index < ids.length; index++) {
                mount = document.getElementById(ids[index]);
                if (mount) { name = '#' + ids[index]; break; }
              }
              if (!mount) {
                if (body.hasAttribute('data-dsha-app')) mount = body;
                var element = body.firstElementChild;
                for (var checked = 0; !mount && element && checked < 24; checked++) {
                  if (element.hasAttribute('data-dsha-app')) mount = element;
                  element = element.nextElementSibling;
                }
                if (mount) name = '[data-dsha-app]';
              }
              if (mount && deferred(mount)) return ['deferred', name];
              var root = mount || body;
              var pending = root.firstChild ? [root.firstChild] : [];
              var metadata = [], opaque = Boolean(root.shadowRoot);
              var waiting = false, visibilityChecks = 0;
              for (var checked = 0; pending.length && checked < 24; checked++) {
                var node = pending.pop();
                if (node.nextSibling) pending.push(node.nextSibling);
                var candidate = null;
                if (node.nodeType === 1) {
                  if (/^(SCRIPT|STYLE|LINK|META|TEMPLATE|NOSCRIPT)$/i.test(node.tagName)) {
                    if (metadata.length < 4) metadata.push(node);
                    else opaque = true;
                    continue;
                  }
                  if (deferred(node)) { waiting = true; continue; }
                  if (/^(IMG|SVG|CANVAS|VIDEO|AUDIO|INPUT|TEXTAREA|SELECT|BUTTON|PROGRESS|METER|IFRAME|OBJECT|EMBED|HR)$/i.test(node.tagName))
                    candidate = node;
                  else {
                    // 无法通过 DOM 证明普通元素没有独立绘制或闭合 shadow tree。
                    opaque = true;
                    if (node.firstChild) pending.push(node.firstChild);
                  }
                } else if (node.nodeType === 3) {
                  if (/\\S/.test(node.substringData(0, 64))) candidate = node.parentElement;
                  else if (node.length > 64) return ['unknown', name];
                }
                if (candidate) {
                  if (++visibilityChecks > 4) return ['unknown', name];
                  if (visible(candidate)) return ['rendered', name];
                  waiting = true;
                }
              }
              if (pending.length) return ['unknown', name];
              if (waiting || !visible(root)) return ['deferred', name];
              // 首次文档有内容绘制时保守处理；SPA 路由不能继承旧页面的绘制证据。
              if (opaque || (paintMs >= 0 && !routeDocument.moved)) return ['unknown', name];
              for (var index = 0; index < metadata.length; index++)
                if (getComputedStyle(metadata[index]).display !== 'none') return ['unknown', name];
              return [emptyMountStyle(root), name, root];
            }

            function current() {
              return window.__dshaRouteProbe === job && location.href === expectedUrl;
            }

            function stableEmpty(facts) {
              return Boolean(firstEmpty && facts[0] === 'blank'
                && facts[1] === firstEmpty[1] && facts[2] === firstEmpty[2]);
            }

            function confirm() {
              try {
                if (!current() || document.visibilityState !== 'visible') return false;
                return stableEmpty(measure(firstPaint()));
              } catch (ignored) {
                return false;
              } finally {
                cancel();
              }
            }

            function firstPaint() {
              try {
                var entries = performance.getEntriesByName('first-contentful-paint', 'paint');
                var time = entries.length ? entries[0].startTime : NaN;
                if (typeof time === 'number' && isFinite(time) && time >= 0 && time <= 3600000)
                  return Math.round(time);
              } catch (ignored) {}
              return -1;
            }

            function sample(second) {
              if (!current()) {
                cancel();
                return;
              }
              if (document.visibilityState !== 'visible') return;
              var paintMs = firstPaint();
              var facts = measure(paintMs);
              var state = facts[0], mount = facts[1];
              if (!second && state !== 'rendered') {
                if (state === 'blank') firstEmpty = facts;
                arm(3500, true);
                return;
              }
              if (state === 'blank' && !stableEmpty(facts)) state = 'unknown';
              if (state === 'blank') {
                job.confirm = confirm;
                timer = setTimeout(cancel, 10000);
              } else cancel();
              console.log('[DSHA_ROUTE] generation=' + generation
                + ' path=' + encodeURI((location.pathname || '/').slice(0, 180))
                + ' mount=' + encodeURIComponent(mount) + ' state=' + state
                + (paintMs < 0 ? '' : ' fcpMs=' + paintMs));
            }

            document.addEventListener('visibilitychange', visibility);
            window.addEventListener('pagehide', cancel);
            visibility();
          } catch (ignored) {}
        })(%d, %s)
        """,
        generation,
        expectedUrl);
  }

  /** 已换代的取消消息只能清理自己创建的定时器。 */
  public static String cancelScript(long generation) {
    return "(function(){var job=window.__dshaRouteProbe;if(job&&job.generation==="
        + generation
        + ")job.cancel()})()";
  }

  /** 原生摘要返回后，只复核同一页面的同一空挂载点。 */
  public static String confirmScript(long generation) {
    return "(function(){var job=window.__dshaRouteProbe;return Boolean(job&&job.generation==="
        + generation
        + "&&job.confirm&&job.confirm())})()";
  }

  public record Result(long generation, String path, String mount, String state, int fcpMs) {
    public boolean blank() {
      return "blank".equals(state);
    }

    public boolean readable() {
      return blank() || "rendered".equals(state);
    }

    public String record() {
      return "path="
          + path
          + " mount="
          + (mount.isEmpty() ? "-" : mount)
          + " state="
          + state
          + (fcpMs < 0 ? "" : " fcpMs=" + fcpMs);
    }
  }

  public static Result parse(String message) {
    if (message == null
        || message.length() > 2048
        || !message.startsWith(PREFIX)
        || message.indexOf('\n') >= 0
        || message.indexOf('\r') >= 0) return null;
    long generation = 0;
    String path = "", mount = "", state = "";
    int fcpMs = -1;
    for (String field : message.substring(PREFIX.length()).split(" ")) {
      int equal = field.indexOf('=');
      if (equal <= 0) continue;
      String value = field.substring(equal + 1);
      switch (field.substring(0, equal)) {
        case "generation" -> {
          try {
            generation = Long.parseLong(value);
          } catch (NumberFormatException invalid) {
            return null;
          }
        }
        case "path" -> path = WebSubresourceDiagnostic.path(value);
        case "mount" -> {
          mount =
              switch (value) {
                case "%23root", "%23app", "%23app-root", "%23dsh-root", "%23__next", "%23__nuxt" ->
                    "#" + value.substring(3);
                case "%5Bdata-dsha-app%5D" -> "[data-dsha-app]";
                default -> "";
              };
        }
        case "state" -> {
          state =
              switch (value) {
                case "blank", "rendered", "deferred", "unknown" -> value;
                default -> "";
              };
        }
        case "fcpMs" -> {
          try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 0 && parsed <= 3_600_000) fcpMs = parsed;
          } catch (NumberFormatException ignored) {
            // 此内核未提供可用的首屏耗时。
          }
        }
        default -> {
          // 忽略未声明的字段，避免页面内容进入诊断。
        }
      }
    }
    return generation > 0 && !path.isEmpty() && !state.isEmpty()
        ? new Result(generation, path, mount, state, fcpMs)
        : null;
  }
}
