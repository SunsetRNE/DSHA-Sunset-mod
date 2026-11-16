#!/usr/bin/env python3
"""Bound existing UI translation debt and reject new sinks; never translate user/protocol text.

The inventory is lexical, not an Android screen audit. It only flags direct Chinese
literals in display sinks, UiText.text/choose literal concatenation, and literal XML text,
hint or content descriptions. Dynamic translation arguments are counted for review.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from source_layout import SourceLayout

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / 'tools/i18n/ui-source-baseline.json'
DISPLAY = {'setText', 'setTitle', 'setMessage', 'setHint', 'setContentDescription'}
CJK = re.compile(r'[\u3400-\u9fff]')
JAVA = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\S')
XML_LITERAL = re.compile(r'android:(text|hint|contentDescription)\s*=\s*(["\'])(.*?)\2', re.S)


def java_tokens(source):
    return [(match.group(), source.count('\n', 0, match.start()) + 1)
            for match in JAVA.finditer(source)
            if not match.group().startswith(('//', '/*'))]


def chinese_literal(value):
    if not value.startswith('"'):
        return False
    decoded = re.sub(r'\\u([0-9a-fA-F]{4})', lambda match: chr(int(match[1], 16)), value)
    return bool(CJK.search(decoded))


def call_arguments(values, start):
    arguments, depth = [[]], 0
    for pos in range(start + 1, len(values)):
        value = values[pos]
        if value == ')' and depth == 0:
            return arguments, pos
        if value in ('(', '[', '{'): depth += 1
        elif value in (')', ']', '}'): depth -= 1
        if value == ',' and depth == 0:
            arguments.append([])
        else:
            arguments[-1].append(value)
    return [], start


def top_level_concat(values):
    depth = 0
    for value in values:
        if value in ('(', '[', '{'): depth += 1
        elif value in (')', ']', '}'): depth -= 1
        elif value == '+' and depth == 0: return True
    return False


def kotlin_tokens(source):
    """保留 raw string / 模板字面量；嵌套注释不能吞掉后续显示调用。"""
    result, at, line = [], 0, 1
    while at < len(source):
        start, token_line = at, line
        if source.startswith('//', at):
            end = source.find('\n', at + 2)
            at = len(source) if end < 0 else end
            continue
        if source.startswith('/*', at):
            depth = 1
            at += 2
            while at < len(source) and depth:
                if source.startswith('/*', at):
                    depth += 1
                    at += 2
                elif source.startswith('*/', at):
                    depth -= 1
                    at += 2
                else:
                    at += 1
            line += source.count('\n', start, at)
            continue
        if source.startswith('"""', at):
            end = source.find('"""', at + 3)
            at = len(source) if end < 0 else end + 3
        elif source[at] in ('"', "'", '`'):
            quote = source[at]
            at += 1
            while at < len(source):
                if source[at] == '\\' and quote != '`':
                    at += 2
                elif source[at] == quote:
                    at += 1
                    break
                else:
                    at += 1
        elif source[at].isspace():
            line += int(source[at] == '\n')
            at += 1
            continue
        else:
            identifier = re.match(r'[A-Za-z_$][\w$]*', source[at:])
            at += len(identifier[0]) if identifier else 1
        value = source[start:at]
        result.append((value, token_line))
        line += value.count('\n')
    return result


def java_findings(source):
    return source_findings(java_tokens(source))


def kotlin_findings(source):
    return source_findings(kotlin_tokens(source), kotlin=True)


def source_findings(tokens, kotlin=False):
    values = [token[0] for token in tokens]
    results, dynamic = [], 0
    direct_kind = 'kotlin_direct_display_literal' if kotlin else 'java_direct_display_literal'
    # TextView/View 的 JavaBean 属性；业务记录的 message 不是显示方法调用。
    properties = {'text', 'hint', 'contentDescription', 'title'}
    for index, (value, line) in enumerate(tokens):
        if kotlin and value in properties and index and values[index - 1] == '.' \
                and values[index + 1:index + 2] == ['='] \
                and index + 2 < len(values) and chinese_literal(values[index + 2]):
            results.append((direct_kind, value + '=' + values[index + 2], line))
        if value in DISPLAY and values[index + 1:index + 2] == ['(']:
            if index + 2 < len(values) and chinese_literal(values[index + 2]):
                results.append((direct_kind, value + '(' + values[index + 2], line))
        if value == 'makeText' and values[index + 1:index + 2] == ['(']:
            depth = 0
            for pos in range(index + 2, len(values)):
                current = values[pos]
                if current in ('(', '[', '{'): depth += 1
                elif current in (')', ']', '}'):
                    if depth == 0: break
                    depth -= 1
                if current == ',' and depth == 0:
                    if pos + 1 < len(values) and chinese_literal(values[pos + 1]):
                        results.append((direct_kind, 'makeText(_, ' + values[pos + 1], line))
                    break
        if value == 'UiText' and values[index + 1:index + 4] == ['.', 'text', '(']:
            if index + 4 >= len(values): continue
            argument = values[index + 4]
            if not argument.startswith('"'):
                dynamic += 1
            elif kotlin and chinese_literal(argument) and re.search(
                    r'\$[A-Za-z_{]' if argument.startswith('"""') else r'(?<!\\)\$[A-Za-z_{]', argument):
                results.append(('translated_template_literal', 'UiText.text(' + argument + ')', line))
            elif values[index + 5:index + 7] == [')', '+'] and chinese_literal(argument):
                results.append(('translated_fragment_concat', 'UiText.text(' + argument + ')+', line))
        if value == 'UiText' and values[index + 1:index + 4] == ['.', 'choose', '(']:
            # Inspect the application's two-language API, including a translated
            # label constructed inside its arguments. Nested raw calculations
            # alone do not become translated fragments.
            arguments, end = call_arguments(values, index + 3)
            if len(arguments) != 2 or not any(chinese_literal(item) for item in arguments[0]):
                continue
            after = values[end + 1:end + 2] == ['+']
            start = index
            while start >= 2 and values[start - 1] == '.' and re.fullmatch(r'[A-Za-z_$][\w$]*', values[start - 2]):
                start -= 2
            before = start > 0 and values[start - 1] == '+'
            inner = any(top_level_concat(argument) for argument in arguments)
            if before or after or inner:
                call = ''.join(values[index:end + 1])
                signature = call + '+' if after else '+' + call
                if inner and not (before or after): signature = call
                kind = 'translated_choose_argument_concat' if inner else 'translated_choose_fragment_concat'
                results.append((kind, signature, line))
    return results, dynamic


def inventory(root):
    rows, dynamic = [], 0
    layout = SourceLayout(root)
    for entry in layout.source_files(('main', 'standard'), suffixes=('.java', '.kt')):
        path = entry.path
        content = path.read_bytes()
        scanner = kotlin_findings if path.suffix == '.kt' else java_findings
        found, count = scanner(content.decode('utf-8'))
        dynamic += count
        rows.extend({'kind': kind, 'file': entry.relative,
                     'signature': signature, 'line': line,
                     'sourceSha256': hashlib.sha256(content).hexdigest()} for kind, signature, line in found)
    for module in layout.modules:
        for source_set in ('main', 'standard'):
            if source_set not in module['sourceSets']:
                continue
            source_root = root / module['directory'] / 'src' / source_set
            for path in sorted((source_root / 'res').glob('layout*/*.xml')):
                source = path.read_text(encoding='utf-8')
                for match in XML_LITERAL.finditer(source):
                    value = match[3]
                    if not value or value.startswith(('@', '?')): continue
                    rows.append({'kind': 'xml_literal', 'file': path.relative_to(root).as_posix(),
                                 'signature': match[1] + '=' + value,
                                 'line': source.count('\n', 0, match.start()) + 1})
    return rows, dynamic


def signatures(rows):
    return Counter((row['kind'], row['file'], row['signature']) for row in rows)


def compare(rows, baseline):
    actual, allowed = signatures(rows), signatures(baseline)
    return actual - allowed, allowed - actual


def partition_nonfindings(rows, declarations):
    """Accept only individually reviewed whole-paragraph joins at exact bytes.

    These are separate from translation-debt findings. A new occurrence, changed
    source, label or call signature must be reviewed again, never waived by ID.
    """
    remaining = list(rows)
    accepted, stale = [], []
    for declaration in declarations:
        if (declaration.get('classification') != 'independent_complete_paragraph_join'
                or not declaration.get('reason') or not declaration.get('sourceSha256')
                or not isinstance(declaration.get('occurrences'), int)
                or declaration['occurrences'] <= 0):
            raise ValueError('UI_I18N_NONFINDING_SCHEMA')
        key = tuple(declaration.get(name) for name in ('kind', 'file', 'signature', 'sourceSha256'))
        count = 0
        kept = []
        for row in remaining:
            actual = tuple(row.get(name) for name in ('kind', 'file', 'signature', 'sourceSha256'))
            if actual == key and count < declaration['occurrences']:
                accepted.append(dict(row, classification=declaration['classification'],
                                     reason=declaration['reason'],
                                     evidence=declaration.get('evidence')))
                count += 1
            else:
                kept.append(row)
        remaining = kept
        if count != declaration['occurrences']:
            stale.append(dict(declaration, matchedOccurrences=count))
    return remaining, accepted, stale


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--baseline', type=Path, default=BASELINE)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    rows, dynamic = inventory(args.root)
    declared = json.loads(args.baseline.read_text(encoding='utf-8'))
    if declared.get('schema') != 1 or not isinstance(declared.get('findings'), list):
        raise SystemExit('UI_I18N_BASELINE_SCHEMA')
    effective, nonfindings, stale = partition_nonfindings(rows, declared.get('nonFindings', []))
    new, resolved = compare(effective, declared['findings'])
    report = {'schema': 1, 'scope': 'production-source-lexical-only',
              'counts': dict(sorted(Counter(row['kind'] for row in effective).items())),
              'lexicalCandidateCounts': dict(sorted(Counter(row['kind'] for row in rows).items())),
              'dynamicUiTextArgumentsForReview': dynamic, 'newFindings': sum(new.values()),
              'resolvedBaselineFindings': sum(resolved.values()), 'findings': effective,
              'explicitNonFindings': nonfindings, 'staleNonFindingDeclarations': stale}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    for (kind, filename, signature), count in sorted(new.items()):
        print(f'NEW_UI_I18N {kind} {filename}: {signature} ({count})')
    print(json.dumps({key: value for key, value in report.items()
                     if key not in {'findings', 'explicitNonFindings', 'staleNonFindingDeclarations'}}, ensure_ascii=True))
    print(f'UI_I18N_EXPLICIT_NONFINDINGS {len(nonfindings)}; stale declarations {len(stale)}')
    return 1 if new or stale else 0


if __name__ == '__main__':
    raise SystemExit(main())
