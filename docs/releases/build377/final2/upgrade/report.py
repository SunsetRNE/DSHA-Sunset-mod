from direct_host import *
r=get_record();assert r['status']=='PASS'
b=r['stages']['baseline'];u=r['stages']['upgrade']
assert u['ok'] and b['ok'] and all(not ignore for ignore in r['shutdowns'][-1]['listeningAfter'].values())
def n(x):return f'{x:,}'
old=b['after'];new=u['after'];saved=r['shutdowns'][-1]['privateAvdStorage'];restart=r.get('restart')
body=f"""# build376 直接覆盖升级至最终 build377

本机全新专用 Android 11 / API 30 模拟器，Google APIs x86_64 rev 16，安装后应用 ABI 为 {new['abi']}，使用宿主的 ARM 原生库转译。设备名 {AVD}，2 核、2,048 MiB guest RAM，540×960，密度 240。控制台 {PORT}、传输端口 {PORT+1}、私有 ADB {SERVER}，ADB 服务仅接受刻意不存在的 USB 序列号。所有设备命令显式绑定 emulator-{PORT}。新建数据盘初次查询无 dsh.android.client。随后从零安装一次正式 376，再用 install -r 直接覆盖一次最终 377。

报告绑定旧版源码 {b.get('sourceCommit','12efafd7d3530b412d48ccb66fb657c6bd7cc8ac')}、新版签名源码 {u.get('sourceCommit','5d952dafd13c3193f07e7efbc97b65c0c2c8bea7')}。两份输入 APK 签名回执的证书 SHA-256 均为 e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5。原始 adb 日志核到两次 install 成功，只有第二次含 -r。

| 验收项 | 正式 376 | 最终 377 |
|---|---|---|
| 安装包版本名 | {old['versionName']} | {new['versionName']} |
| 包版本码 | {old['versionCode']} | {new['versionCode']} |
| 客体 UID | {old['applicationUid']} | {new['applicationUid']} |
| 首次安装时间（客体本地） | {old['firstInstallTime']} | {new['firstInstallTime']} |
| 最近更新时间（客体本地） | {old['lastUpdateTime']} | {new['lastUpdateTime']} |
| 输入 APK、客体 base.apk 与宿主读回 SHA-256 | {old['onDeviceSha256']} | {new['onDeviceSha256']} |
| 输入 APK 逻辑 / F 卷实际占用（字节） | {n(b['input']['logicalBytes'])} / {n(b['input']['allocatedBytes'])} | {n(u['input']['logicalBytes'])} / {n(u['input']['allocatedBytes'])} |
| 已安装 base.apk 逻辑 / 客体实际占用（字节） | {n(old['logicalBytes'])} / {n(old['allocatedBytes'])} | {n(new['logicalBytes'])} / {n(new['allocatedBytes'])} |
| 宿主 F 卷可用空间，安装阶段开始→结束（字节） | {n(b['hostBefore']['FfreeBytes'])} → {n(b['hostAfter']['FfreeBytes'])} | {n(u['hostBefore']['FfreeBytes'])} → {n(u['hostAfter']['FfreeBytes'])} |
| 客体 /data 同卷可用空间，安装阶段开始→结束（字节） | {n(b['guestSpaceBefore']['availableBytes'])} → {n(b['guestSpaceAfter']['availableBytes'])} | {n(u['guestSpaceBefore']['availableBytes'])} → {n(u['guestSpaceAfter']['availableBytes'])} |
| 包管理器报告成功 / 完成整轮读回核验 | {b['firstPackageManagerSuccessSeconds']} 秒 / {b['completedSecondsFromInstall']} 秒 | {u['firstPackageManagerSuccessSeconds']} 秒 / {u['completedSecondsFromInstall']} 秒 |

宿主使用 adb exec-out cat 分块读取两份安装后的 base.apk，在内存按 SHA-256 校验，读回 {n(b['hostReadback']['bytes'])} 和 {n(u['hostReadback']['bytes'])} 字节。首个 1 MiB 延时分别 {b['hostReadback']['firstMiBSeconds']}、{u['hostReadback']['firstMiBSeconds']} 秒；读完分别 {b['hostReadback']['completeSeconds']}、{u['hostReadback']['completeSeconds']} 秒。表内“包管理器成功”不包含应用界面就绪测量。

已将包级系统振动偏好由默认允许设为 ignore，重启及覆盖后仍为 {u['markersAfter']['appOp']}。第一次写入 Android/media/dsh.android.client 的合成标志，在 376 安装期间读回 SHA-256 为 {b['markersAfter']['sharedMediaFixtureSha256']}；客体关闭再启动后这个文件为 0 字节且仍装 376，原因未确认，原文件没有被重写。真正覆盖之前，在仍安装原 376 的同一客体另建不同文件名的 {r['preUpgradeMarkerObservation']['syncedContentBytes']} 字节标志，显式 sync 并核实；覆盖前后标志 SHA-256 都是 {u['markersAfter']['syncedFixtureSha256']}。原本已空的第一份标志在覆盖后仍空。Android 11 拒绝 shell 创建 Android/data 下应用目录，记录原失败并保留系统限制。这里验证的是系统侧包设置和应用命名共享媒体文件，不据此推断应用私有偏好、聊天、附件或真实设备资料。

为了给同机其他验收释放内存，376 基线核验后正常关闭本客体和私有 ADB，保留同一数据盘。收到最终签名 APK 和摘要后，用原镜像、配置和数据盘重启。重启时再核验合成系统身份哈希和构建指纹，重新实算原 376 包、首装时间、UID、振动设置以及重启前后两份标志的实际状态，之后才执行 -r。最终只关闭本模拟器及私有 ADB，三个专用端口都已退出。审计数据盘位于受 ACL 保护的 {WORK}。关闭后保留 {saved['fileCount']} 个文件，逻辑 {n(saved['logicalBytes'])} 字节，F 卷实际分配 {n(saved['allocatedBytes'])} 字节，同卷可用 {n(saved['sameVolumeFreeBytes'])} 字节。宿主其它并发工作亦会影响卷可用空间。

首次身份检查把 AVD 控制台回执中的空行当作额外条目，尚未安装便停止；去除空行后重跑并精确匹配同一 AVD 名称。第一次宿主分配计量使用 GetCompressedFileSizeW，后改用和正式包一致的 FILE_STANDARD_INFO.AllocationSize，对同一份已核摘要 APK 和已关闭数据盘重测。第一次重启预检对 config.ini 做原字节比较时停止，模拟器自行改写了键值两侧空白和数据分区大小。复核除分区大小外全部属性一致，记录的 6,442,450,944 字节与保留的 userdata 原文件长度一致，再用原参数启动。同客体身份与包数据另行核对。各阶段开始结束时刻、完整宿主读回量、每次工具调用，以及原失败均已保留。

[主回执](direct.json) · [逐次原始命令和退出码](commands.jsonl) · [早期身份解析记录](baseline-r1-report.json) · [模拟器配置](guest-config.ini) · [重启配置核对](resume-config.json) · [首次标志复核](resumed-marker-recheck.json) · [覆盖前失败记录](upgrade-preflight-r1.json)

在另一个全新私有目录重放时，先复制这些脚本，替换各脚本一致的专用 AVD 名、空目录和未占用的三个端口，再检查启动脚本的首次使用保护。原实验在 F 盘目录按以下顺序执行：

```powershell
Set-Location -LiteralPath F:/DSHA_RESTART/artifacts/build377-local/final2/upgrade
python -B direct_host.py
python -B measure_install.py baseline
python -B seed_media.py
python -B power.py suspend
python -B recheck_allocation.py
python -B power.py resume --apk "{u['input']['path']}" --sha256 {u['input']['sha256']}
python -B seed_synced.py
python -B measure_install.py upgrade --apk "{u['input']['path']}" --sha256 {u['input']['sha256']}
python -B power.py close
python -B report.py
```
"""
(E/'验收记录.md').write_text(body,encoding='utf-8')
print('Wrote report')
