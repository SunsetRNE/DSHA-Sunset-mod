package com.deepseekharness.app.core;
import com.deepseekharness.app.util.Compat;

import android.content.Context;
import android.util.Log;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DshAuthUrl;
import com.deepseekharness.app.util.Fmt;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.WebProcSel;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 业务编排核心：环境准备 + 启动/停止 dsh Web + BrowserAuth 鉴权链接捕获。
 * 安装六步、备份恢复、插件市场等是后续按 seam 回填的独立协作者，不再堆进这一个类。
 */
public class HarnessController {

    private final Context ctx;
    private final ConfigStore config;
    private final ProotBootstrap proot;
    private final WebProcessManager webProc;
    private final ExecutorService io;
    /** 防重复启动：一次只允许一个 startWeb 在飞。 */
    private final java.util.concurrent.atomic.AtomicBoolean starting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 当前 dsh 进程打印的 BrowserAuth 鉴权链接（内存态，不落盘）。
     * <b>进程级静态</b>：Fragment 每次重建都会 new HarnessController，若放实例字段，
     * 用户从 WebUI 返回后「进入」会因链接丢失而要求重新启动（dsh 明明在跑）。
     */
    private static volatile String webAuthUrl = "";
    /** dsh 代次号：每次 startWeb 自增，LAN 代理用它区分新旧 dsh 会话。同 webAuthUrl 需要进程级。 */
    private static long webGeneration = 0;

    public HarnessController(Context ctx) {
        this.ctx = ctx;
        this.config = new ConfigStore(ctx);
        this.proot = new ProotBootstrap(ctx);
        this.webProc = new WebProcessManager(proot);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dsh-io");
            t.setDaemon(true);
            return t;
        });
    }

    public ConfigStore config() {
        return config;
    }

    public ProotBootstrap proot() {
        return proot;
    }

    /** 别名：供 3090 桥等原版调用方使用。 */
    public ProotBootstrap getProot() {
        return proot;
    }

    /** Web 是否在运行（按 pid 文件 + kill -0 判断，不依赖端口反查）。 */
    public boolean isWebRunning() {
        try {
            java.io.File pidFile = new java.io.File(proot.getRootfsDir(),
                    WebProcSel.pidFileRel(WebProcSel.PID_WEB));
            if (!pidFile.exists()) return false;
            String pid = new String(Compat.readAllBytes(pidFile),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            String r = proot.execAndRead("kill -0 " + pid + " 2>/dev/null && echo YES || echo NO");
            return r != null && r.contains("YES");
        } catch (Throwable e) {
            return false;
        }
    }

    /** 进程级单例（3090 桥、保活服务等共享同一实例）。 */
    private static volatile HarnessController instance;

    public static HarnessController get(Context ctx) {
        if (instance == null) {
            synchronized (HarnessController.class) {
                if (instance == null) {
                    instance = new HarnessController(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static String fmtBytes(long b) {
        return Fmt.bytes(b);
    }

    public void logActivity(String s) {
        Log.i("DSHA", s == null ? "" : s);
    }

    /** 读取 assets 里的脚本全文（供备份/自愈等注入 rootfs）。 */
    public String readAsset(String name) {
        try {
            java.io.InputStream in = ctx.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            // 资产在 Windows 检出时可能是 CRLF，注入容器后脚本认不了 \r → 统一转 LF
            return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isEnvironmentReady() {
        return proot.isEnvironmentReady();
    }

    public boolean hasOfflineBundle() {
        return proot.hasOfflineBundle();
    }

    /** 当前 BrowserAuth 鉴权链接；dsh 还没打印出来时为空串。 */
    public String getWebAuthUrl() {
        return webAuthUrl;
    }

    /** dsh 实际启动命令（写 pid 文件要在 exec 之前，exec 不换 pid）。 */
    public String runCoreCommand() {
        String apiKey = config.getApiKey();
        String apiExport = apiKey.isEmpty()
                ? ""
                : "export DEEPSEEK_API_KEY=" + ShellQuote.arg(apiKey) + " && ";
        return "rm -f " + WebProcSel.STOP_SENTINEL + " 2>/dev/null; "
                + "export DSH_HOME=/root/.dsh && "
                + apiExport
                + "export DSH_PERMISSION_MODE=" + ShellQuote.arg(config.getPermissionMode()) + " && "
                + "export DSH_CONFIRM=" + (config.isConfirmShell() ? "1" : "0") + " && "
                + "export BROWSER=true && "
                + "cd /root && "
                + ": > /root/dsh-web.log 2>/dev/null; "
                + "echo $$ > " + WebProcSel.PID_WEB + " 2>/dev/null; "
                + "exec dsh web --no-open --host 127.0.0.1 --port "
                + config.getPortInt();
    }

    /**
     * 后台启动 dsh：先清残留进程（避免端口冲突），确保运行时与 rootfs 就绪后拉起 dsh web，
     * 独立线程捕获 BrowserAuth 鉴权链接。
     */
    public void startWeb(Consumer<String> onStatus) {
        if (!starting.compareAndSet(false, true)) {
            onStatus.accept("已在启动中，请稍候…");
            return;
        }
        webAuthUrl = "";
        webGeneration++;
        io.execute(() -> {
            try {
                webProc.stop(); // 先清掉可能残留的 dsh，否则新进程撞 EADDRINUSE
                proot.ensureRuntimeFiles();
                if (!proot.isEnvironmentReady()) {
                    if (!proot.hasOfflineBundle()) {
                        onStatus.accept("没有内置离线环境包：请用完整 APK（含 offline-rootfs）安装");
                        return;
                    }
                    onStatus.accept("正在解压内置环境（首次约需几分钟，请勿退出）…");
                    proot.extractOfflineBundle((done, total) -> { });
                    onStatus.accept("环境解压完成，正在启动 dsh web…");
                }
                // 内置四插件注册：rootfs 烘焙的实体要登记进 web profile 才会被 dsh 加载。
                // 覆盖安装（rootfs 保留）与全新安装（rootfs 重新解压）都靠这一步补齐；
                // 失败不阻塞启动（插件页打开时会再触发一次，dsh 下次重启生效）。
                try {
                    String r = proot.registerBuiltinPlugins();
                    if (r != null && (r.contains("BUILTIN_REGISTER_OK")
                            || r.contains("BUILTIN_REGISTER_PARTIAL")
                            || r.contains("FAIL"))) {
                        Log.i("DSHA", "内置插件注册: " + r.trim());
                    }
                } catch (Throwable ignored) {
                }
                Process p = proot.execRootfs(runCoreCommand());
                // 3090 桥就绪：agent 在容器里调设备能力（/exec /confirm /status）走这条通道。
                // 跨实例互斥，DeviceBridgeService 已起过则是幂等 no-op。
                try {
                    if (com.deepseekharness.app.HttpShellService.instance() == null) {
                        new com.deepseekharness.app.HttpShellService(ctx).start();
                    }
                } catch (Throwable e) {
                    Log.w("DSHA", "3090 桥启动失败: "
                            + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
                }
                onStatus.accept("dsh web 已启动 → 127.0.0.1:" + Constants.DSH_WEB_PORT
                        + "（等待鉴权链接…）");
                Thread drainer = new Thread(() -> {
                    try {
                        drainWebOutput(p, onStatus);
                    } finally {
                        starting.set(false);
                    }
                }, "dsh-drain");
                drainer.setDaemon(true);
                drainer.start();
            } catch (Exception e) {
                starting.set(false);
                Log.e("DSHA", "startWeb failed", e);
                onStatus.accept("启动失败：" + e.getMessage());
            }
        });
    }

    /** 读 dsh 进程输出：抓鉴权链接（宽松）、并把脱敏后的输出落到容器日志方便排查。 */
    private void drainWebOutput(Process p, Consumer<String> onStatus) {
        StringBuilder scan = new StringBuilder();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                scan.append(chunk);
                if (scan.length() > 64_384) scan.delete(0, scan.length() - 64_384);
                appendHostLog(chunk);
                if (webAuthUrl.isEmpty()) {
                    String url = extractAuthUrl(scan.toString());
                    if (url != null) {
                        webAuthUrl = url;
                        onStatus.accept("鉴权链接已就绪，点「进入对话」即可进入 dsh");
                        // LAN 模式：拿到鉴权链接后自动交换 cookie 并启动 3081 代理。
                        // 否则代理要等用户手动点「进入」才绑定 —— 其它设备在手机上没点过
                        // 「进入」时就连不上（连接被拒），正是「局域网连不上」的头号原因。
                        // dsh 打印 URL 时 HTTP 服务可能还没就绪，交换失败就短等重试几次。
                        if (config.isLanMode()) {
                            for (int attempt = 0; attempt < 3; attempt++) {
                                try {
                                    if (exchangeDshAuthCookie() != null) break;
                                } catch (Throwable ignored) {
                                }
                                try {
                                    Thread.sleep(1200);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                            // LanProxyService.start 的绑定在独立 accept 线程里异步完成，
                            // 刚返回时 isBound() 可能还是 false —— 轮询等它绑定完再刷新 UI。
                            for (int i = 0; i < 12 && !com.deepseekharness.app.LanProxyService.isBound(); i++) {
                                try {
                                    Thread.sleep(200);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                            // 代理是在上面 onStatus.accept 之后才绑定的，启动页那次刷新
                            // 会停在「等待本轮认证」；这里再触发一次 UI 刷新，让地址可点。
                            if (com.deepseekharness.app.LanProxyService.isBound()) {
                                onStatus.accept("局域网代理已就绪：同网段设备可访问，启动页可复制地址");
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 进程退出了却还没拿到鉴权链接 → dsh 没起来（大概率端口冲突或启动失败）
        if (webAuthUrl.isEmpty()) {
            onStatus.accept("dsh 进程已退出且未打印鉴权链接（大概率端口冲突或启动失败），"
                    + "日志见 /root/dsh-web.log");
        }
    }

    /** 提取鉴权链接：先严格（官方输出行），失败再宽松（直接扫 URL）。 */
    private String extractAuthUrl(String output) {
        return DshAuthUrl.findAny(output);
    }

    /** 把 dsh 输出脱敏后落到容器内 /root/dsh-web.log（排查用，鉴权 token 不落盘）。 */
    private void appendHostLog(String chunk) {
        try {
            File log = new File(proot.getRootfsDir(), "root/dsh-web.log");
            if (log.getParentFile() != null) log.getParentFile().mkdirs();
            Compat.append(log, redactAuthUrl(chunk).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 把鉴权 token 打码，避免落盘泄露。 */
    static String redactAuthUrl(String s) {
        return DshAuthUrl.redact(s);
    }

    /**
     * Java 侧直接做一次 BrowserAuth cookie 交换：GET 鉴权链接，取回 dsh-auth-* cookie。
     * 返回 {@code "name=value"} 或 null。用于 WebView 的确定性注入鉴权。
     * 拿到 cookie 后若开了 LAN 模式，同步启动局域网反向代理（3081）。
     */
    public String exchangeDshAuthCookie() {
        String url = webAuthUrl;
        if (url.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            String cookie = extractDshAuthCookie(conn.getHeaderFields());
            if (cookie != null) {
                try {
                    long gen = webGeneration;
                    com.deepseekharness.app.LanProxyService.setDshAuthCookie(cookie, gen);
                    if (config.isLanMode()) {
                        com.deepseekharness.app.LanProxyService.start(
                                proot.getRootfsDir().getAbsolutePath(), ctx,
                                config.getPortInt(), gen);
                    }
                } catch (Throwable ignored) {
                }
            }
            return cookie;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 Set-Cookie 里挑 dsh-auth-* 那个 cookie（不假设它是第一个）。 */
    private static String extractDshAuthCookie(Map<String, List<String>> headers) {
        return DshAuthUrl.extractCookie(headers);
    }

    public void stopWeb() {
        webProc.stop();
        webAuthUrl = ""; // 链接随进程失效，清空防「停止后再进入」用旧链接
        try {
            com.deepseekharness.app.LanProxyService.stop(webGeneration);
        } catch (Throwable ignored) {
        }
    }

    /** 当前 dsh 代次号（供 LAN 代理 / 配置页开关联动）。 */
    public long getWebGeneration() {
        return webGeneration;
    }

    /** 撤销解压标记：下次启动重新走 ExtractActivity 解压（配置保留）。 */
    public void resetExtraction() {
        proot.markNotExtracted();
    }

    /** 重置容器内配置（settings.yaml + .env），保留对话记录，并按当前 App 配置重写 .env。 */
    public String resetConfig() {
        try {
            boolean any = false;
            java.io.File settings = new java.io.File(proot.getRootfsDir(), "root/.dsh/settings.yaml");
            if (settings.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                settings.delete();
                any = true;
            }
            String wd = config.getWorkdir();
            java.io.File env = new java.io.File(proot.getRootfsDir(),
                    "root/" + (wd.startsWith("/") ? wd.substring(1) : wd) + "/.env");
            if (env.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                env.delete();
                any = true;
            }
            writeEnvFile(env);
            return any
                    ? "配置已重置，对话记录已保留\n（.env 已按当前配置重写）"
                    : "没有可重置的配置（.env 已重写）";
        } catch (Throwable e) {
            return "重置失败：" + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e));
        }
    }

    /** 用当前 App 配置重写 rootfs 内的 .env。 */
    private void writeEnvFile(java.io.File env) throws Exception {
        if (env.getParentFile() != null) env.getParentFile().mkdirs();
        String apiKey = config.getApiKey();
        String keyLine = apiKey.isEmpty()
                ? "# DEEPSEEK_API_KEY=\n"
                : "DEEPSEEK_API_KEY=" + com.deepseekharness.app.util.ShellQuote.arg(apiKey) + "\n";
        Compat.write(env, keyLine.getBytes(StandardCharsets.UTF_8));
    }

    /** proot 冒烟测试，返回诊断文本。 */
    public String smokeTest() {
        return proot.smokeTest();
    }

    /**
     * 检测本机局域网 IPv4 地址（免权限，NetworkInterface 枚举，给 LAN 代理分享用）。
     * 优先 WiFi/以太网接口（wlan/eth/radio），避免选到 USB 共享网络等非目标网卡的地址
     * —— 否则复制出去的局域网地址另一台设备永远连不上。
     */
    public static String getLanAddress() {
        try {
            String fallback = null;
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String ifName = ni.getName() == null ? "" : ni.getName();
                boolean wifiLike = ifName.startsWith("wlan") || ifName.startsWith("eth")
                        || ifName.startsWith("radio") || ifName.startsWith("wifi");
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.startsWith("172."))) {
                        if (fallback == null) fallback = ip;
                        if (wifiLike) return ip; // 目标网卡命中，直接返回
                    }
                }
            }
            return fallback;
        } catch (Exception ignored) {
        }
        return null;
    }
}
