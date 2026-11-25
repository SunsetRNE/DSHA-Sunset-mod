"""Append the two specifically reviewed messages; bind the existing two declarations to the reviewed sources."""
from pathlib import Path
import hashlib
import json

here=Path(__file__).resolve().parent
root=here.parents[3]/'source/build377-local'
folder=root/'tools/i18n'
rows=[
 ('x377RetainedOriginal','transfer/SelectedDocumentSave.java',{
  'zh':'旧文档的原件已保留。打开「作品文件 → DSHA → save-recovery」，选择以下文件可重新导出：%s',
  'en':'The original is preserved. In Work files → DSHA → save-recovery, select this file to export it again: %s',
  'zh-Hant':'舊文件的原件已保留。開啟「作品檔案 → DSHA → save-recovery」，選取以下檔案即可重新匯出：%s',
  'ja':'元の文書は保存されています。「作業ファイル → DSHA → save-recovery」を開き、次のファイルを選択して再度エクスポートしてください：%s',
  'ko':'원본 문서를 보관했습니다. 작업 파일 → DSHA → save-recovery에서 다음 파일을 선택해 다시 내보내세요: %s',
  'ru':'Исходный документ сохранён. Чтобы снова экспортировать его, откройте «Рабочие файлы → DSHA → save-recovery» и выберите файл %s.',
  'fr':'Le document original a été conservé. Pour l’exporter de nouveau, ouvrez Fichiers de travail → DSHA → save-recovery et sélectionnez le fichier %s.',
  'pt':'O documento original foi preservado. Para exportá-lo novamente, abra Arquivos de trabalho → DSHA → save-recovery e selecione o arquivo %s.',
  'ar':'تم الاحتفاظ بالمستند الأصلي. لتصديره مجددًا، افتح «ملفات العمل ← DSHA ← save-recovery» وحدد الملف ⁦%s⁩.',
  'la':'Documentum originale servatum est. Ad illud iterum exportandum, aperi «Documenta laboris → DSHA → save-recovery» et elige fasciculum %s.'}),
 ('x377ReadOnlyWorkbenchTitle','ui/WebSettingsDocument.java',{
  'zh':'当前工作台配置 · 只读','en':'Current workbench configuration · Read-only',
  'zh-Hant':'目前的工作台設定 · 唯讀','ja':'現在のワークベンチ設定（読み取り専用）','ko':'현재 워크벤치 설정(읽기 전용)',
  'ru':'Текущая конфигурация рабочей среды (только чтение)','fr':'Configuration actuelle de l’espace de travail (lecture seule)',
  'pt':'Configuração atual da área de trabalho (somente leitura)','ar':'إعدادات مساحة العمل الحالية (للقراءة فقط)',
  'la':'Configuratio praesentis areae laboris (ad legendum tantum)'})]

def load(p): return json.loads(p.read_text(encoding='utf-8'))
def save(p,obj):
    p.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
index=folder/'messages-extra.json'
catalog=load(index)
for key,file,entries in rows:
    data={'id':key,'zh':entries['zh'],'en':entries['en']}
    if '%s' in entries['zh']: data['uiFormat']=True
    data['files']=['app/src/main/java/com/deepseekharness/app/'+file]
    existing=next((r for r in catalog if r['id']==key),None)
    if existing is None: catalog.append(data)
    else: assert existing==data
for lang in rows[0][2]:
    if lang in ('en','zh'): continue
    p=folder/'translations-extra'/f'{lang}.json'
    values=load(p)
    for key,file,entries in rows: values[key]=entries[lang]
    save(p,values)
save(index,catalog)
baseline=folder/'ui-source-baseline.json'
declarations=load(baseline)
assert len(declarations['nonFindings'])==2 and declarations['findings']==[]
reviewed=load(here/'before-377.json')['findings']
for old in declarations['nonFindings']:
    matching=[r for r in reviewed if all(r[k]==old[k] for k in ('file','kind','signature'))]
    assert len(matching)==old['occurrences']==1
    actual=hashlib.sha256((root/old['file']).read_bytes()).hexdigest()
    assert actual==matching[0]['sourceSha256']
    old['sourceSha256']=actual
save(baseline,declarations)
