#!/usr/bin/env python3
"""Exercise the new native terminal and launch-page language catalog on the host."""
import base64
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from ui_language_catalog import LANGUAGES, RESOURCE_FOLDERS, format_signature, load_catalog

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/deepseekharness/app'
RES = ROOT / 'app/src/main/res'
CALL = re.compile(r'UiText\.(choose|text|format)\s*\(\s*("(?:\\.|[^"\\])*")', re.S)

spec = importlib.util.spec_from_file_location('prepare_ui_languages', ROOT / 'tools/prepare-ui-languages.py')
PREPARE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PREPARE)


class TerminalHomeLanguageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.messages, cls.catalogs = load_catalog(ROOT)
        cls.by_source = {item['zh']: item for item in cls.messages}

    def test_terminal_and_launch_literal_calls_have_complete_catalog_entries(self):
        # Check actual application call sites, including accessibility and future additions.
        for name in ('TerminalActivity.java', 'LaunchFragment.java'):
            source = (SOURCE / 'ui' / name).read_text(encoding='utf8')
            calls = [json.loads(match.group(2)) for match in CALL.finditer(source)]
            self.assertGreater(len(calls), 20, name)
            missing = sorted(set(calls) - self.by_source.keys())
            self.assertEqual([], missing, name)
        terminal = (SOURCE / 'ui/TerminalActivity.java').read_text(encoding='utf8')
        terminal_literals = {json.loads(match.group(2)) for match in CALL.finditer(terminal)}
        self.assertIn('新建终端会话', terminal_literals)
        self.assertNotIn('新建会话', terminal_literals)
        self.assertIn('聚焦输入', terminal_literals)

    def test_indexed_close_all_messages_keep_argument_validation(self):
        indexed = [item for item in self.messages if item['id'].startswith('termhome')
                   and re.search(r'%\d+\$[sd]', item['zh'])]
        self.assertGreaterEqual(len(indexed), 5)
        self.assertTrue(all(item.get('androidFormat') and not item.get('uiFormat') for item in indexed))
        # load_catalog validates each numbered argument's identity and type in all ten languages.

    def test_layout_string_resources_exist_in_every_language(self):
        names = set()
        for filename in ('fragment_launch.xml', 'fragment_pty_terminal.xml', 'fragment_terminal.xml'):
            names.update(re.findall(r'@string/([A-Za-z0-9_]+)',
                                    (RES / 'layout' / filename).read_text(encoding='utf8')))

        def resources(folder):
            found = {}
            for path in folder.glob('*.xml'):
                for item in ET.parse(path).getroot().findall('string'):
                    found[item.get('name')] = item
            return found

        default = resources(RES / 'values')
        self.assertEqual(set(), names - default.keys())
        translated_names = {name for name in names if default[name].get('translatable') != 'false'}
        for tag in LANGUAGES:
            with self.subTest(language=tag):
                localized = resources(RES / RESOURCE_FOLDERS[tag])
                self.assertEqual(set(), translated_names - localized.keys())

    def test_generated_java_renders_context_and_indexed_arguments_in_all_languages(self):
        expected_new = [
            '新建终端会话', '新增終端工作階段', 'New terminal session', 'Новый сеанс терминала',
            '新しい端末セッション', '새 터미널 세션', 'جلسة طرفية جديدة', 'Nova sessão de terminal',
            'Nova sessio terminalis', 'Nouvelle session de terminal',
        ]
        expected_hint = [
            'PTY / 简易', 'PTY / 簡易', 'PTY / Simple', 'PTY / простой', 'PTY / シンプル',
            'PTY / 간편', 'PTY / بسيط', 'PTY / simples', 'PTY / simplex', 'PTY / simple',
        ]
        expected_home = [
            '本机工作台 · 已就绪', '本機工作台 · 已就緒', 'Local workspace · ready',
            'Локальное рабочее пространство · готово', 'ローカルワークスペース · 準備完了',
            '로컬 작업대 · 준비됨', 'مساحة العمل المحلية · جاهزة', 'Bancada local · pronta',
            'Area operis localis · parata', 'Espace de travail local · prêt',
        ]
        confirm = self.by_source['将结束 %1$d 个会话（PTY %2$d / 简易 %3$d）。\n'
                                 '这不是返回页面：每个进程分别结束并核验，未确认的会话会保留并标记，Web 和其他功能不受影响。']
        jstr = lambda value: json.dumps(value, ensure_ascii=True)
        languages = ', '.join(jstr(tag) for tag in LANGUAGES)
        expected_new_java = ', '.join(jstr(value) for value in expected_new)
        expected_hint_java = ', '.join(jstr(value) for value in expected_hint)
        expected_home_java = ', '.join(jstr(value) for value in expected_home)
        harness = '''package com.deepseekharness.app.util;
public final class TerminalHomeLanguageHarness {
  public static void main(String[] args) throws Exception {
    String[] languages = {%s};
    String[] expectedNew = {%s};
    String[] expectedHint = {%s};
    String[] expectedHome = {%s};
    for (int i = 0; i < languages.length; i++) {
      String language = languages[i];
      UiText.setLanguage(language);
      String terminal = UiText.choose("新建终端会话", "New terminal session");
      String conversation = UiText.choose("新建会话", "New conversation");
      String hint = UiText.text("PTY / 简易");
      String home = UiText.choose("本机工作台 · 已就绪", "Local workspace · ready");
      String confirm = UiText.format(UiText.choose(%s, %s), 11, 22, 33).replace('\\n', ' ');
      if (!terminal.equals(expectedNew[i])) throw new AssertionError(language + ": terminal");
      if (terminal.equals(conversation)) throw new AssertionError(language + ": context collision");
      if (!hint.equals(expectedHint[i])) throw new AssertionError(language + ": hint");
      if (!home.equals(expectedHome[i])) throw new AssertionError(language + ": home");
      if (!confirm.contains("11") || !confirm.contains("PTY 22") || !confirm.contains("33")
          || confirm.matches(".*%%[0-9]+\\\\$[sd].*"))
        throw new AssertionError(language + ": indexed arguments");
      System.out.println("PASS " + language);
    }
    int count = 0;
    for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0]),
        java.nio.charset.StandardCharsets.UTF_8)) {
      String[] fields = line.split("\\t", -1);
      String language = fields[0], id = fields[1];
      String zh = decode(fields[2]), en = decode(fields[3]), expected = decode(fields[4]);
      UiText.setLanguage(language);
      String selected = UiText.choose(zh, en);
      if (!selected.equals(expected) || !UiText.text(zh).equals(expected))
        throw new AssertionError(language + ": lookup " + id);
      if (!fields[5].equals("N")) {
        Object[] values;
        if (fields[5].equals("I")) values = new Object[] {11, 22, 33, 44};
        else {
          values = new Object[fields[6].length()];
          for (int i = 0; i < values.length; i++)
            values[i] = fields[6].charAt(i) == 's' ? "ARG" + i : Integer.valueOf(10 + i);
        }
        String formatted = String.format(java.util.Locale.ROOT, expected, values);
        if (!UiText.format(selected, values).equals(formatted)
            || !UiText.format(zh, values).equals(formatted))
          throw new AssertionError(language + ": format " + id);
      }
      count++;
    }
    System.out.println("PASS catalog " + count);
  }
  private static String decode(String value) {
    return new String(java.util.Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
  }
}
''' % (languages, expected_new_java, expected_hint_java, expected_home_java,
       jstr(confirm['zh']), jstr(confirm['en']))
        with tempfile.TemporaryDirectory(prefix='dsha-terminal-i18n-') as temp:
            tmp = Path(temp)
            generated = tmp / 'generated'
            PREPARE.generate(ROOT, generated)
            package = tmp / 'com/deepseekharness/app/util'
            package.mkdir(parents=True)
            (package / 'SystemLanguage.java').write_text(
                'package com.deepseekharness.app.util; final class SystemLanguage {'
                ' static String tag() { return "zh"; } }\n', encoding='utf8')
            (package / 'TerminalHomeLanguageHarness.java').write_text(harness, encoding='utf8')
            extra = [item for item in self.messages if item['id'].startswith('termhome')]
            self.assertGreaterEqual(len(extra), 74)
            rows = []
            encode = lambda value: base64.b64encode(value.encode('utf8')).decode('ascii')
            for tag in LANGUAGES:
                for item in extra:
                    kind = 'I' if item.get('androidFormat') else 'S' if item.get('uiFormat') else 'N'
                    signature = ''.join(format_signature(item['zh'])) if kind == 'S' else ''
                    rows.append('\t'.join((tag, item['id'], encode(item['zh']), encode(item['en']),
                                           encode(self.catalogs[tag][item['id']]), kind, signature)))
            input_path = tmp / 'catalog.tsv'
            input_path.write_text('\n'.join(rows) + '\n', encoding='utf8')
            classes = tmp / 'classes'
            classes.mkdir()
            java_files = sorted((generated / 'com/deepseekharness/app/util').glob('*.java'))
            java_files += [SOURCE / 'util/UiText.java', SOURCE / 'util/UiLanguagePreference.java',
                           package / 'SystemLanguage.java', package / 'TerminalHomeLanguageHarness.java']
            subprocess.run(['javac', '-encoding', 'UTF-8', '--release', '17', '-d', str(classes),
                            *map(str, java_files)], check=True, capture_output=True, text=True,
                           encoding='utf8', errors='replace', timeout=120)
            result = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(classes),
                                     'com.deepseekharness.app.util.TerminalHomeLanguageHarness',
                                     str(input_path)],
                                    check=True, capture_output=True, text=True, encoding='utf8',
                                    errors='replace', timeout=30)
        self.assertEqual(['PASS ' + tag for tag in LANGUAGES] +
                         ['PASS catalog ' + str(len(extra) * len(LANGUAGES))],
                         result.stdout.splitlines())


if __name__ == '__main__':
    unittest.main()
