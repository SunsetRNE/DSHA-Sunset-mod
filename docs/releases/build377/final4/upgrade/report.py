"""Bind the finished dedicated AVD run to both signed source snapshots and write the human receipt."""
from direct_host import *
import argparse
def run():
 ap=argparse.ArgumentParser();ap.add_argument('--commit',required=True);a=ap.parse_args()
 assert re.fullmatch(r'[0-9a-f]{40}',a.commit)
 r=get_record();assert r['status']=='PASS'
 r['baselineSourceCommit']='12efafd7d3530b412d48ccb66fb657c6bd7cc8ac';r['finalSourceCommit']=a.commit
 b=r['stages']['baseline'];u=r['stages']['upgrade'];old=b['after'];new=u['after']
 for phase,commit in [('baseline',r['baselineSourceCommit']),('upgrade',a.commit)]:
  row=r['stages'][phase];apk=Path(row['input']['path']);p=apk.parent/'apk-signing.json';sig=json.loads(p.read_text(encoding='utf-8-sig'))
  assert sig['apkSha256']==row['input']['sha256']==row['after']['onDeviceSha256']==row['hostReadback']['sha256']
  assert sig['versionCode']==row['after']['versionCode'] and sig['packageName']=='dsh.android.client'
  assert sig['certificateSha256']=='e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5'
  row['sourceCommit']=commit;row['signingEvidence']={'path':str(p),'sha256':digest(p),'certificateSha256':sig['certificateSha256'],'versionCode':sig['versionCode'],'packageName':sig['packageName']}
 rows=[json.loads(line) for line in (E/'commands.jsonl').read_text(encoding='utf-8').splitlines()]
 installs=[x for x in rows if 'install' in x['cmd']];assert len(installs)==2 and [('Success' in x['stdout'],x['exit'],'-r' in x['cmd']) for x in installs]==[(True,0,False),(True,0,True)]
 r['checks']['syncedMarkerSurvivedGuestRestart']=r['prewarmFirstObservation']['sameMarker']
 r['checks']['appOpSurvivedGuestRestart']=r['prewarm']['appOpSurvivedGuestRestart']
 r['installationEvidence']=[{'at':x['at'],'cmd':x['cmd'],'exit':x['exit'],'elapsedSeconds':x['seconds']} for x in installs]
 put('direct.json',r)
 def n(x):return f'{x:,}'
 mark=u['markersAfter'];bootnote='测试期间一直保留同一客体进程。'
 if r.get('restart'):
  bootnote='基线就绪后为同机任务释放内存，先显式 sync 并正常关闭客体；重启原数据盘后又核验系统身份、原装 376 和合成标志，再覆盖。'
 disks=r['shutdowns'][-1]['privateAvdStorage']
 body=f"""# 正式 376 直接覆盖至第四份最终签名 377

专用 Android 11 / API 30、Google APIs x86_64 第 16 版系统镜像，原生库安装 ABI 为 {new['abi']}。宿主使用全新受 ACL 保护的数据目录，客体 2,048 MiB、2 核、540×960、密度 240。ADB 服务使用不存在的 USB 标识和单独的 5093 端口，所有设备命令指定 emulator-5696；上轮数据盘保留。首次 pm path 查询确认应用不存在，全部命令仅有两次成功安装，先安装正式 376，再以 install -r 直接覆盖第四份最终签名 377。{bootnote}

| 项目 | 原 376 | 第四份最终签名 377 |
|---|---|---|
| 源码提交 | {b['sourceCommit']} | {u['sourceCommit']} |
| 版本码及版本名 | 376 / {old['versionName']} | 377 / {new['versionName']} |
| 输入签名包、客体 base.apk 和宿主回读 SHA-256 | {old['onDeviceSha256']} | {new['onDeviceSha256']} |
| 应用 UID | {old['applicationUid']} | {new['applicationUid']} |
| 首次安装时间（客体本地时间） | {old['firstInstallTime']} | {new['firstInstallTime']} |
| 最近更新时间（客体本地时间） | {old['lastUpdateTime']} | {new['lastUpdateTime']} |
| APK 输入逻辑 / F 卷实际分配（字节） | {n(b['input']['logicalBytes'])} / {n(b['input']['allocatedBytes'])} | {n(u['input']['logicalBytes'])} / {n(u['input']['allocatedBytes'])} |
| 客体 base.apk 逻辑 / 实际分配（字节） | {n(old['logicalBytes'])} / {n(old['allocatedBytes'])} | {n(new['logicalBytes'])} / {n(new['allocatedBytes'])} |
| F 卷可用空间起→止（字节） | {n(b['hostBefore']['FfreeBytes'])} → {n(b['hostAfter']['FfreeBytes'])} | {n(u['hostBefore']['FfreeBytes'])} → {n(u['hostAfter']['FfreeBytes'])} |
| 客体 /data 同卷可用空间起→止（字节） | {n(b['guestSpaceBefore']['availableBytes'])} → {n(b['guestSpaceAfter']['availableBytes'])} | {n(u['guestSpaceBefore']['availableBytes'])} → {n(u['guestSpaceAfter']['availableBytes'])} |
| 包管理器安装成功 / 完成该轮核验 | {b['firstPackageManagerSuccessSeconds']} 秒 / {b['completedSecondsFromInstall']} 秒 | {u['firstPackageManagerSuccessSeconds']} 秒 / {u['completedSecondsFromInstall']} 秒 |

安装后分别通过 ADB 的 exec-out cat 取得两份完整 base.apk 并在宿主流式计算 SHA-256，数据量是 {n(b['hostReadback']['bytes'])} 和 {n(u['hostReadback']['bytes'])} 字节。宿主读回首个 1 MiB 分别用 {b['hostReadback']['firstMiBSeconds']}、{u['hostReadback']['firstMiBSeconds']} 秒，读完整包用 {b['hostReadback']['completeSeconds']}、{u['hostReadback']['completeSeconds']} 秒。安装成功时间取包管理器回执，应用 UI 启动时延未测。

在 376 中把系统侧包级设置 VIBRATE 从默认允许变为 ignore，并在 Android/media/dsh.android.client 创建 {mark['markerBytes']} 字节合成标志。写后执行 guest sync 并核实字节和摘要，覆盖前后 SHA-256 均为 {mark['markerSha256']}。首次在 376 下把振动改为 ignore 并读回；关机重启后这一系统包设置回到默认 allow，当时尚未发出任何 377 安装，原因未确认。仍在 376 下重新设置同一项后，执行 Android cmd appops write-settings（回复 Current settings written.）并同步、读回。实际覆盖之前和覆盖之后均为 {mark['appOp']}。UID、首次安装时间和合成系统标识哈希一致。此项证据覆盖包身份、系统侧设置及共享媒体目录内的合成文件，不推断私人聊天、附件或应用内部偏好。

最后正常关闭专用 AVD 与私有 ADB，5696、5697、5093 都已核验无监听。该轮私有 AVD 审计盘保留于 {WORK}，关闭时 {disks['fileCount']} 个文件，逻辑 {n(disks['logicalBytes'])} 字节，宿主实际分配 {n(disks['allocatedBytes'])} 字节，同卷可用 {n(disks['sameVolumeFreeBytes'])} 字节。宿主同期其他任务也会影响 F 卷可用空间。

[主回执](direct.json) · [逐条原始命令](commands.jsonl) · [客体配置](guest-config.ini) · [安装与回读脚本](measure_install.py) · [开关机与身份复核](power.py)
"""
 before_ref=ROOT/'artifacts/build377-local/final3/upgrade/direct.json'
 before_disk=json.loads(before_ref.read_text(encoding='utf-8'))['shutdowns'][0]['privateAvdStorage']
 current_disk=r['shutdowns'][0]['privateAvdStorage'] if len(r['shutdowns'])>1 else None
 if current_disk:
  body+=f"\n此次基线关闭快照存储、关闭 Quickboot 文件映射，仅复用不可变系统镜像。原第三轮基线关闭后配套文件实占 {before_disk['allocatedBytes']:,} 字节，本轮基线关闭后实占 {current_disk['allocatedBytes']:,} 字节，少 {before_disk['allocatedBytes']-current_disk['allocatedBytes']:,} 字节。两次盘逻辑大小分别 {before_disk['logicalBytes']:,} 和 {current_disk['logicalBytes']:,} 字节，F 卷当时可用分别 {before_disk['sameVolumeFreeBytes']:,} 和 {current_disk['sameVolumeFreeBytes']:,} 字节。新盘不含上轮约 2 GiB 的 ram.img；保留既有各轮原件。\n"
 body+=f"\n重放顺序与实际调用参数见 commands.jsonl。新实验需另选空目录和专用端口，原数据盘保留。关键入口为 python -B direct_host.py、python -B measure_install.py baseline、python -B power.py suspend、python -B prewarm.py（若设置在冷启动后变为默认，先用 inspect_prewarm.py 留证，再用 persist_prewarm_setting.py 明确复设原 376 的同一项并落盘）、python -B measure_install.py upgrade --apk <最终包> --sha256 <最终摘要>、python -B power.py close 和 python -B report.py --commit {a.commit}。\n"
 (E/'验收记录.md').write_text(body,encoding='utf-8')
 print('Bound signed build/source evidence; install operations=',len(installs),'final SHA-256=',new['onDeviceSha256'])
if __name__=='__main__':run()
