"""Retain the R14 physical acceptance criterion and attach only matching local evidence."""
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

here=Path(__file__).resolve().parent
root=here.parents[2]
ledger=root/'artifacts/build375-local/settings/r14/total-through-r14/156条总账.csv'
csv.field_size_limit(100_000_000)
with ledger.open(encoding='utf-8-sig',newline='') as source:
    all_rows=list(csv.DictReader(source))
ordinary={
    'native.about.issue': '问题反馈按钮经选择后向外派发 ACTION_VIEW 及生产目标。',
    'native.about.license.online': '在线许可按钮派发 ACTION_VIEW 及现行声明的完整 URL。',
    'native.config.desktop': '两个独立设置表单与独立重开分别读取桌面偏好，再经旋转重建保存。新建 Android WebView 的 UA 和实机布局仍待验。',
    'native.config.port.auto': '只编辑 DNS 不会将另一个窗保存的固定端口重置为自动，已校验打开页及独立重开。原先安排的真实自动端口挑选仍待验。',
    'native.config.lan.mode': '实际配置 Fragment 先勾选再更换合成网络，保存后 LAN 偏好已开启，新网络仍须单独确认。原定实物 LAN 对端和跨域操作另验。',
}
web={
    'web.plugins.inventory.filter': '在实时清单查询 sandbox、无结果测试词及清空，读回相应正文。',
    'web.plugins.inventory.preset.switch': '打开预设菜单，选择 standard、ptc、minimal、cordis 并逐个读回清单。',
    'web.plugins.tabs': '健康宿主注册单个内置清单时只有正文，按生产单页分支检查实际标签数量。该次宿主缺多个已注册设置标签。',
    'web.settings.sections': '五个具名分区各自从关闭的设置重新进入两次，逐次查选中项及该页独立正文。',
    'web.launch.retry': '真实插件向合成宿主查询启动项，缺 Android 桥时返回 502，两轮点击重试都读到独立响应。',
    'web.general.language':'临时 Windows 工作台普通菜单实际提供 12 种语言，全部逐种选择并关闭、重开设置读回。简中和英语还分别整页重新加载。原 Android 桥同步和系统切换仍待验。',
}
rows=[]
for row in all_rows:
    leftover=json.loads(row['remainingWork'] or 'null')
    if not leftover: continue
    identity=row['id']
    evidence=None
    if identity in ordinary: evidence={'kind':'Android SDK 34 Robolectric','observation':ordinary[identity],'file':'native-final-r1/junit.log'}
    if identity in web: evidence={'kind':'Windows DSH with headless Chromium on loopback','observation':web[identity],'file':'web-r8/events.jsonl'}
    rows.append({'id':identity,'name':row['control'],'originalAndroidUser':row['plannedAndroidUser'],
      'originalSource':row['source'],'originalHandler':row['handler'],'originalCriterion':row['nextCheck'],
      'originalPrerequisiteClass':row['prerequisiteClass'],'originalPrerequisite':row['prerequisite'],
      'originalState':leftover['state'],'originalFact':leftover.get('fact'),
      'historicalEvidence':leftover.get('evidence',[]),'lastActionCommit':row['lastActualCommit'] or None,
      'lastActionApkSha256':row['lastActualApkSha256'] or None,'settingsHostEvidence':evidence,
      'newPhysicalVerification':False})
assert len(all_rows)==156 and len(rows)==107 and len(set(r['id'] for r in rows))==107
counts=Counter(r['originalState'] for r in rows)
assert sorted(counts.values())==[19,88]
out={'input':str(ledger.relative_to(root)),'inputSha256':hashlib.sha256(ledger.read_bytes()).hexdigest(),
     'sourceRows':len(all_rows),'remainingRows':len(rows),'remainingByOriginalState':dict(counts),
     'remainingByOriginalPrerequisite':dict(Counter(r['originalPrerequisiteClass'] for r in rows)),
     'withHostEvidenceInThisSettingsStudy':sum(r['settingsHostEvidence'] is not None for r in rows),
     'newlyClosedPhysicalRows':0,'scope':'设置专项。其他并行专项可另附本地资料。所有原域实机条件沿用 R14 原账。',
     'rows':rows}
(here/'107项续验.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
