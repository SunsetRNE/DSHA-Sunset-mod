#!/usr/bin/env python3
"""Bound existing UI translation debt and reject new sinks; never translate user/protocol text.

The inventory is lexical, not an Android screen audit. It flags direct Chinese
literals in display sinks, UiText.text/choose fragments or Kotlin templates, and
literal XML text, hint or content descriptions. Kotlin receiver scopes are checked
conservatively without type resolution. Dynamic translation arguments are counted
for review; unclosed Kotlin strings, templates or comments fail the scan.
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
    decoded = value if getattr(value, 'raw', False) else re.sub(
        r'\\u([0-9a-fA-F]{4})', lambda match: chr(int(match[1], 16)), value)
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


class KotlinString(str):
    """字符串仍作为一个调用参数；只附带词法可确定的模板和 raw 标记。"""
    def __new__(cls, value, raw=False, template=False, expressions=()):
        result = super().__new__(cls, value)
        result.raw = raw
        result.template = template
        result.expressions = expressions
        return result


class KotlinLexer:
    """只识别词法边界，不推断类型或执行模板；不确定的未闭合输入直接失败。"""
    MAX_TEMPLATE_DEPTH = 64

    def __init__(self, source):
        self.source, self.at, self.line = source, 0, 1

    def advance(self, count=1):
        end = self.at + count
        self.line += self.source.count('\n', self.at, end)
        self.at = end

    def fail(self, kind, line):
        raise ValueError(f'UI_I18N_KOTLIN_UNTERMINATED_{kind}: line {line}')

    def comment(self):
        start_line = self.line
        if self.source.startswith('//', self.at):
            end = self.source.find('\n', self.at + 2)
            self.advance((len(self.source) if end < 0 else end) - self.at)
            return
        depth = 1
        self.advance(2)
        while self.at < len(self.source):
            if self.source.startswith('/*', self.at):
                depth += 1
                self.advance(2)
            elif self.source.startswith('*/', self.at):
                depth -= 1
                self.advance(2)
                if not depth:
                    return
            else:
                self.advance()
        self.fail('COMMENT', start_line)

    def quoted(self, depth):
        start, start_line = self.at, self.line
        raw = self.source.startswith('"""', self.at)
        quote = self.source[self.at]
        self.advance(3 if raw else 1)
        template, expressions = False, []
        while self.at < len(self.source):
            if raw and self.source.startswith('"""', self.at):
                # raw 字符串末尾允许一两个内容引号紧贴三个结束引号。
                while self.at < len(self.source) and self.source[self.at] == '"':
                    self.advance()
                return KotlinString(self.source[start:self.at], raw=True, template=template,
                                    expressions=expressions)
            if not raw and self.source[self.at] == quote:
                self.advance()
                value = self.source[start:self.at]
                return KotlinString(value, template=template, expressions=expressions) if quote == '"' else value
            if not raw and quote != '`' and self.source[self.at] == '\\':
                if self.at + 1 >= len(self.source):
                    self.fail('STRING', start_line)
                self.advance(2)
            elif quote == '"' and self.source.startswith('${', self.at):
                if depth >= self.MAX_TEMPLATE_DEPTH:
                    raise ValueError(f'UI_I18N_KOTLIN_TEMPLATE_DEPTH: line {self.line}')
                self.advance(2)
                expression = self.scan(template_depth=depth + 1, expression=True)
                expressions.append(expression)
                # raw 字符串中 ${'$'} 是 Kotlin 表达字面 dollar 的常见写法，不是动态参数。
                literal_dollar = len(expression) == 1 and expression[0][0] in ("'$'", "'\\u0024'")
                template = template or not literal_dollar
            elif quote == '"' and self.source[self.at] == '$' and self.at + 1 < len(self.source) \
                    and (self.source[self.at + 1] == '_' or self.source[self.at + 1].isalpha()):
                template = True
                self.advance()
            else:
                if not raw and self.source[self.at] == '\n':
                    self.fail('STRING', start_line)
                self.advance()
        self.fail('STRING', start_line)

    def scan(self, template_depth=0, expression=False):
        result, braces, start_line = [], 0, self.line
        while self.at < len(self.source):
            start, token_line = self.at, self.line
            if self.source.startswith(('//', '/*'), self.at):
                self.comment()
                continue
            if self.source[self.at] in ('"', "'", '`'):
                value = self.quoted(template_depth)
            elif self.source[self.at].isspace():
                self.advance()
                continue
            else:
                value = self.source[self.at]
                if expression and value == '}' and not braces:
                    self.advance()
                    return result
                if value == '{':
                    braces += 1
                elif value == '}':
                    braces -= 1
                identifier = re.match(r'[A-Za-z_$][\w$]*', self.source[self.at:])
                self.advance(len(identifier[0]) if identifier else 1)
                value = self.source[start:self.at]
            result.append((value, token_line))
        if expression:
            self.fail('TEMPLATE', start_line)
        return result


def kotlin_tokens(source):
    return KotlinLexer(source).scan()


def kotlin_receiver_properties(tokens, inherited=False):
    """apply/with/receiver.run 中保守识别隐式属性；不把普通赋值或命名参数当显示。

    不解析接收者的静态类型：业务对象同名属性也可能报告，必须由审阅者判断，不能静默跳过。
    只认这些标准作用域函数的直接调用，不解析别名、自定义 receiver DSL 或类型重载。
    """
    values = [value for value, _ in tokens]
    receiver_braces = set()
    for index, value in enumerate(values):
        receiver_call = value in ('apply', 'run') and index > 0 and values[index - 1] == '.'
        if receiver_call and values[index + 1:index + 2] == ['{']:
            receiver_braces.add(index + 1)
        if (receiver_call or value == 'with') and values[index + 1:index + 2] == ['(']:
            arguments, end = call_arguments(values, index + 1)
            if end == index + 1:
                continue
            trailing = (receiver_call and arguments == [[]]) or (
                value == 'with' and len(arguments) == 1 and arguments[0])
            if trailing and values[end + 1:end + 2] == ['{']:
                receiver_braces.add(end + 1)
            # apply({ ... }) / run(block = { ... }) / with(view, { ... })。
            argument, start, depth = 0, index + 2, 0
            for pos in range(start, end):
                token = values[pos]
                if token == ',' and not depth:
                    argument, start = argument + 1, pos + 1
                elif token in ('(', '[', '{'):
                    if token == '{' and not depth and (values[start:pos] == ['block', '='] or (
                            argument == (1 if value == 'with' else 0) and not values[start:pos])):
                        receiver_braces.add(pos)
                    depth += 1
                elif token in (')', ']', '}'):
                    depth -= 1
    stack = [('{', True, set())] if inherited else []
    result, contexts = set(), set()
    for index, value in enumerate(values):
        if any(entry[1] for entry in stack):
            contexts.add(index)
        if value in ('(', '[', '{'):
            stack.append((value, index in receiver_braces, set()))
        elif value in (')', ']', '}'):
            if stack:
                stack.pop()
        elif value in ('val', 'var') and stack and index + 1 < len(values):
            # 简单局部变量的遮蔽；声明和后续同名赋值都不是隐式 View 属性。
            scope = next((entry for entry in reversed(stack) if entry[0] == '{'), None)
            if scope is not None:
                scope[2].add(values[index + 1])
        elif stack and stack[-1][0] == '{' and any(entry[1] for entry in stack) \
                and not any(value in entry[2] for entry in stack):
            result.add(index)
    return result, contexts


def java_findings(source):
    return source_findings(java_tokens(source))


def kotlin_findings(source):
    def region(tokens, inherited=False):
        implicit, contexts = kotlin_receiver_properties(tokens, inherited)
        results, dynamic = source_findings(tokens, kotlin=True, implicit_properties=implicit)
        # 模板表达式独立扫描，但继续继承外层可见的 receiver，不破坏外层调用参数。
        for index, (value, _) in enumerate(tokens):
            for expression in getattr(value, 'expressions', ()):
                found, count = region(expression, index in contexts)
                results.extend(found)
                dynamic += count
        return results, dynamic
    results, dynamic = region(kotlin_tokens(source))
    return sorted(results, key=lambda row: row[2]), dynamic


def source_findings(tokens, kotlin=False, implicit_properties=()):
    values = [token[0] for token in tokens]
    results, dynamic = [], 0
    direct_kind = 'kotlin_direct_display_literal' if kotlin else 'java_direct_display_literal'
    # TextView/View 的 JavaBean 属性；业务记录的 message 不是显示方法调用。
    properties = {'text', 'hint', 'contentDescription', 'title'}
    for index, (value, line) in enumerate(tokens):
        if kotlin and value in properties and index \
                and (values[index - 1] == '.' or index in implicit_properties) \
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
            elif kotlin and chinese_literal(argument) and getattr(argument, 'template', False):
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
            template = kotlin and any(getattr(item, 'template', False)
                                      for argument in arguments for item in argument)
            if before or after or inner or template:
                call = ''.join(values[index:end + 1])
                signature = call + '+' if after else '+' + call
                if (inner or template) and not (before or after): signature = call
                kind = ('translated_choose_argument_template' if template else
                        'translated_choose_argument_concat' if inner else 'translated_choose_fragment_concat')
                results.append((kind, signature, line))
    return results, dynamic


def inventory(root):
    rows, dynamic = [], 0
    layout = SourceLayout(root)
    for entry in layout.source_files(('main', 'standard'), suffixes=('.java', '.kt')):
        path = entry.path
        content = path.read_bytes()
        scanner = kotlin_findings if path.suffix == '.kt' else java_findings
        try:
            found, count = scanner(content.decode('utf-8'))
        except ValueError as error:
            raise ValueError(f'{entry.relative}: {error}') from error
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
