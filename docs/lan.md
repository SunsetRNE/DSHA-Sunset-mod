# 局域网访问、鉴权与故障判断

当前行为对应 build377。文中 F11、F12 为原先后端恢复与代理的历史证据。

F12补充：同代进程身份后来转为UNKNOWN时，最终发布门禁会拒绝晚到的LAN cookie；已有该代鉴权、监听和连接也会按generation撤销，保活检查不能凭旧cookie重开。旧代的晚到核验不能撤销新代连接。该收敛不发送Web进程信号、不解除未知退出屏障，也不清理用户数据。

## 开启和撤销

“配置 → 允许局域网访问”默认关闭。开启时设置 LAN 密码。当前 Web 完成本轮认证，用户确认当前网络，代理才绑定实际地址。Android API 37 起还需系统本地网络权限。

在主页“+ → 访问地址”可确认网络并复制 `https://私网IPv4:实际端口/`。浏览器用用户名 `dsha` 和设置的密码登录。当前 `dsh.android.client` 优先尝试 3082，占用时请系统分配端口。监听仅绑定已确认的地址，后端仍走本机 Web 回环端口。详见[端口与网络](ports-and-networking.md)。

关闭开关会撤销浏览器会话并关闭监听和连接，本机入口保持独立。换网或重建应用进程后，从“访问地址”确认当前网络、复制实际地址，再在浏览器登录。

## 严格恢复后复用已经验证的鉴权

F12的宿主重建恢复先核私有记录、APK/descriptor/root及本轮进程身份，执行真实HTTP鉴权，再进行第二次内核进程核验；成功只接入同一个仍活动的Web。LAN发布复用这次严格恢复中已经验证的cookie，不重新执行token交换，不调用普通新启动的 `afterAuthenticated`，也不重放启动、迁移或业务操作。

发布前仍须核对当前generation、原始鉴权URL和实际端口；`WebLifecycleController.publishRecoveredLan`与`LanAuthBridge.publishVerified`再次核对精确快照，迟到或不匹配的结果不能绑定代理。LAN关闭时不启动监听；LAN绑定失败只记LAN诊断，不撤销已经成功的本机Web接入。cookie是该次真实鉴权结果，不是从旧存储值推断出来的新许可。严格接入未通过时仍保留UNKNOWN与原停止屏障，不借LAN可达性推断可恢复。

实现见[WebSessionRecovery](../app/src/main/java/com/deepseekharness/app/core/WebSessionRecovery.java)、[WebLifecycleController](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java)及[LanAuthBridge](../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java)。本批已知修复范围见[F12记录](releases/v0.2.1-rc.2-build190-fix12-20261021.md)；最终软件/APK数量和发布状态由负责人冻结，本文不预先宣布。

## HTTPS 与首次连接

首次打开复制的 HTTPS 地址时，浏览器可能提示自签证书。输入密码前，在浏览器的证书详情中找到 SHA-256 指纹，逐字对照手机“访问地址”页所显示的 32 组十六进制数。完全相同才能信任此次连接。浏览器无法查看完整指纹时，请改用可查看证书详情的客户端。原 HTTP 书签需要重新复制。

私钥由 AndroidKeyStore 持有。证书只含当前 IPv4 地址，有效期 90 天。地址改变、证书续期或重新安装后，应重新核对指纹。本机 DSH 继续使用回环地址。当 Android 无法识别热点下行接口时，可以让两台设备连接同一 Wi-Fi 后重新确认。[加密监听](../app/src/main/java/com/deepseekharness/app/LanTlsIdentity.java) · [密钥保存](../app/src/main/java/com/deepseekharness/app/AndroidLanIdentity.java)

每次浏览器登录签发独立的 32 字节随机会话，使用 `Secure; HttpOnly; SameSite=Strict; Max-Age=43200` cookie。服务端按单调时钟执行 12 小时期限，最多同时保留 64 份。会话 cookie 名为 `__Host-dsha_lan_<实际端口>`。同一 IP 的两个端口各自登录、撤销和清理。浏览器同时提交为当前 HTTPS 来源缓存的 Basic 认证；服务用本轮内存密钥的 HMAC 复核已验证的凭据。普通请求需要这两项。自带网页在建立 WebSocket 前，通过同源且已鉴权的 HTTPS POST 取得 60 秒内单次有效的短票，随握手子协议提交。代理只为该会话和目标路径消费一次，向后端剥除短票和占位协议。能在 WSS 缓存 Basic 的客户端也可直接提交 cookie 和 Basic。密码 KDF 只在登录时运行。旧 URL 的 `token=` 没有登录权限。详见[LanAuth](../app/src/main/java/com/deepseekharness/app/LanAuth.java)。

后端 DSH token/cookie由代理在已核验的本机目标使用，不直接把3090 `X-Token`或后端凭据当作浏览器可转发凭据。请求/响应处理、WebSocket升级、分块和连接额度仍由受限HTTP处理链控制。

F11将后端实际端口未知的0保留为未就绪，不能偷偷回退3080；同代次鉴权更新到新实际端口时替换对应代理目标。普通业务401/403透传该请求，不把任意业务拒绝提升成整轮BrowserAuth失效。读头空闲连接与短请求分别限额，慢头连接不占满业务派发池。

代理在接收请求及约每秒巡检时检查 Android 网络实例、接口、地址和前缀。变化时关闭该监听和连接并撤销会话。网络须由 Android 提供可识别的非 VPN Wi-Fi、以太网或 USB 网络和直连私有 IPv4 路由，客户端须在允许的子网内。当前设备是否提供这些信息由平台决定。详见[LocalNetworkAccess](../app/src/main/java/com/deepseekharness/app/bridge/LocalNetworkAccess.java)。

## 失败时按这一顺序看

| 现象 | 含义与处理 |
|---|---|
| 没有可分享的 LAN 链接 | 查看开关、当前网络确认、系统本地网络权限和 Web 就绪状态 |
| 401，`X-Dsha-Lan-Reason` | 从应用复制当前地址，重新输入浏览器登录凭据 |
| 503 | 核对后端当前就绪和实际绑定状态 |
| 浏览器停留在旧页面 | 旧cookie或旧服务链接不代表新一轮身份；重新打开当前链接 |
| 同网仍无法连接 | 核查手机IP、Wi-Fi隔离/VPN/防火墙和系统许可，不把网络故障当认证失败 |
| 本机Web可用、LAN不可用 | 两条入口独立，保留本机可用状态，再查代理与网络 |

LAN日志由服务脱敏后写入专用日志和logcat；不要将完整带token链接放进公开日志或缺陷截图。对外监听的关闭、连接停止和后端代次绑定应分别验证，不能仅凭UI开关变色声明撤销已生效。

## 源码与验证

配置入口：[ConfigFragment](../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java)、[LocalNetworkAccess](../app/src/main/java/com/deepseekharness/app/bridge/LocalNetworkAccess.java)；认证状态：[LanAuthBridge](../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java)、[LanTokenState](../app/src/main/java/com/deepseekharness/app/util/LanTokenState.java)；失败响应：[LanFailureResponse](../app/src/main/java/com/deepseekharness/app/util/LanFailureResponse.java)。关联测试为 [LanAuthTest](../app/src/test/java/com/deepseekharness/app/LanAuthTest.java)、[LanAuthBridgeTest](../app/src/test/java/com/deepseekharness/app/core/LanAuthBridgeTest.java)、[LanTokenStateTest](../app/src/test/java/com/deepseekharness/app/util/LanTokenStateTest.java)。源码单测不能替代真实Wi-Fi、系统权限、网络隔离或长流设备验证。

F11的[test-lan-proxy.py](../tools/test-lan-proxy.py)在JDK17以实际生产代理和主机loopback socket加逻辑回归共49项通过；保留修复前44项中5失败对照。Android Context/许可/日志为宿主stub，未操作设备、ADB、真实Wi-Fi切换或运行APK；未知OEM/热点/VPN兼容仍未验证。精确最终执行范围由本轮交付回执绑定，不能把本段当手机LAN验收。
