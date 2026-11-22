#!/usr/bin/env python3
"""Guard reviewed ownership boundaries across the shared Java/Kotlin source layout."""
import re
from pathlib import Path
from source_layout import SourceLayout, PACKAGE

ROOT = Path(__file__).resolve().parents[1]
LAYOUT = SourceLayout(ROOT)
errors = []
for entry in LAYOUT.package_files('util'):
    if Path(entry.logical_path).parent.as_posix() != PACKAGE + '/util':
        continue
    source = entry.path.read_text(encoding='utf8')
    if re.search(r'^import\s+(?:android|androidx)\.', source, re.M):
        errors.append(entry.relative + ': Android import in pure policy')
for entry in LAYOUT.package_files('runtime'):
    if Path(entry.logical_path).parent.as_posix() != PACKAGE + '/runtime':
        continue
    source = entry.path.read_text(encoding='utf8')
    if re.search(r'com\.deepseekharness\.app\.(?:BackupManager|core\.(?:RuntimeTasks|MaintenanceCoordinator|ConfigStore|ColdInstallDiagnostics))\b', source):
        errors.append(entry.relative + ': runtime depends on Android maintenance authority')
for entry in LAYOUT.source_files(('main', 'standard'), ('.java', '.kt')):
    file, tree = entry.path, entry.source_set
    source = file.read_text(encoding='utf8')
    keyword = '' if file.suffix == '.kt' else r'new\s+'
    constructors = re.findall(keyword + r'(?:com\.deepseekharness\.app\.core\.)?HarnessController\s*\(', source)
    if file.stem == 'HarnessController':
        continue
    if file.stem == 'DshaApp' and tree == 'main':
        pattern = (r'harnessOwner\.get\s*\{\s*(?:com\.deepseekharness\.app\.core\.)?HarnessController\s*\(\s*this\s*\)'
                   if file.suffix == '.kt' else
                   r'harnessOwner\.get\s*\(\s*\(\s*\)\s*->\s*new\s+(?:com\.deepseekharness\.app\.core\.)?HarnessController\s*\(\s*this\s*\)')
        if len(constructors) != 1 or not re.search(pattern, source):
            errors.append(entry.relative + ': application composition must own exactly one lazy controller construction')
    elif constructors:
        errors.append(entry.relative + ': independent production controller owner')
coordinator = LAYOUT.resolve_class('core/MaintenanceCoordinator').read_text(encoding='utf8')
if re.search(r'\b(?:PtyTerminalFragment|TerminalFragment)\b', coordinator):
    errors.append('MaintenanceCoordinator depends on terminal view owners')
for name in ('PtyTerminalFragment', 'TerminalFragment'):
    path = LAYOUT.resolve_class('ui/' + name)
    source = path.read_text(encoding='utf8')
    keyword = '' if path.suffix == '.kt' else r'new\s+'
    if re.search(keyword + r'TerminalTabs\s*<', source):
        errors.append(name + ': independent terminal tab owner')
    if re.search(r'\b(?:ptyTabs|simpleTabs)\(\)\.(?:add|remove|select|beginClose|closeFailed)\s*\(', source):
        errors.append(name + ': terminal view mutates owner table')
owner_path = LAYOUT.resolve_class('core/TerminalSessionOwner')
owner = owner_path.read_text(encoding='utf8')
if re.search(r'public\s+TerminalTabs<[^>]+>\s+(?:ptyTabs|simpleTabs)\s*\(', owner) \
        or (owner_path.suffix == '.kt' and re.search(r'fun\s+(?:ptyTabs|simpleTabs)\s*\([^)]*\)\s*:\s*TerminalTabs\s*<', owner)):
    errors.append('TerminalSessionOwner exposes mutable tab table')
tabbar_path = LAYOUT.resolve_class('ui/TerminalTabBar')
tabbar = tabbar_path.read_text(encoding='utf8')
pattern = (r'render\s*\(\s*root\s*:\s*View\s*,\s*\w+\s*:\s*TerminalTabs\.ReadOnly<'
           if tabbar_path.suffix == '.kt' else r'render\s*\(View\s+root,\s*TerminalTabs\.ReadOnly<')
if not re.search(pattern, tabbar):
    errors.append('TerminalTabBar must consume read-only tab view')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS pure policies; runtime maintenance/configuration/diagnostic ports; exactly one application-owned controller construction')
