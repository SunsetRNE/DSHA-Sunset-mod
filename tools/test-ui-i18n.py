#!/usr/bin/env python3
"""Lexical inventory boundaries, growth rejection and comment/source preservation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('check_ui_i18n', Path(__file__).with_name('check-ui-i18n.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
generator_spec = importlib.util.spec_from_file_location('prepare_ui_languages', Path(__file__).with_name('prepare-ui-languages.py'))
generator = importlib.util.module_from_spec(generator_spec)
generator_spec.loader.exec_module(generator)


class UiI18nInventoryTest(unittest.TestCase):
    @staticmethod
    def layout(root, extra=()):
        modules = [{'path': ':app', 'directory': 'app', 'kind': 'android-application',
                    'sourceSets': ['main', 'standard', 'debug', 'test'],
                    'formatSourceSets': ['main', 'standard', 'debug', 'test'],
                    'testSourceSets': ['test'], 'testTask': 'testStandardDebugUnitTest'}]
        modules.extend(extra)
        path = root / 'ci/source-layout.json'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({'schema': 1, 'modules': modules}), encoding='utf-8')

    def test_kotlin_raw_templates_properties_and_nested_comments_are_scanned(self):
        source = '/* outer /* inner */ setText("注释") */\n'
        source += 'button.text = "中文"\nsetTitle("""网址 // 中文 /* 内容 */""")\n'
        source += 'Toast.makeText(context, "提示", 0)\n'
        source += 'UiText.text("共 $count 项")\nUiText.choose("共 ", "Total ") + count\n'
        source += 'setText(UiText.format("完整：%s", raw))\nlog("中文日志")'
        rows, dynamic = checker.kotlin_findings(source)
        self.assertEqual(3, sum(row[0] == 'kotlin_direct_display_literal' for row in rows))
        self.assertEqual(1, sum(row[0] == 'translated_template_literal' for row in rows))
        self.assertEqual(1, sum(row[0] == 'translated_choose_fragment_concat' for row in rows))
        self.assertEqual([2, 3, 4, 5, 6], [row[2] for row in rows])
        self.assertEqual(0, dynamic)

    def test_kotlin_comment_markers_backticks_and_dollar_literals_do_not_hide_sinks(self):
        source = 'val url = "https://example.test/a/*b*/"\n'
        source += 'val text = """/* raw */ // $value\nraw"""\n'
        source += 'val `/* identifier */` = 1\n'
        source += 'UiText.text("价格 \\$5")\nview.hint = "提示"'
        source += '\nevent.message = "业务原文"\nlog("中文日志")'
        rows, _ = checker.kotlin_findings(source)
        self.assertEqual([('kotlin_direct_display_literal', 'hint="提示"', 6)], rows)

    def test_layout_scans_both_language_roots_and_new_modules_without_refreezing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.layout(root, [{'path': ':platform:android', 'directory': 'platform/android',
                               'kind': 'android-library', 'sourceSets': ['main', 'test'],
                               'formatSourceSets': ['main', 'test'], 'testSourceSets': ['test'],
                               'testTask': 'testDebugUnitTest'}])
            paths = ['app/src/main/java/pkg/A.kt', 'app/src/main/kotlin/pkg/B.kt',
                     'platform/android/src/main/kotlin/pkg/C.kt']
            for relative in paths:
                path = root / relative
                path.parent.mkdir(parents=True)
                path.write_text('view.text = "中文"', encoding='utf-8')
            rows, _ = checker.inventory(root)
            self.assertEqual(set(paths), {row['file'] for row in rows})
            self.assertEqual(3, sum(checker.compare(rows, [])[0].values()))

    def test_kotlin_nested_template_strings_cannot_turn_into_source_comments(self):
        source = 'val marker = "${"/*"}"\n'
        source += 'val slash = "${"//"}"; view.text = "中文"\n'
        source += 'val nested = "${build("/*", run { "value" })}"\n'
        source += 'val raw = """${"""/* // */"""}"""\nview.hint = "提示"'
        self.assertEqual([
            ('kotlin_direct_display_literal', 'text="中文"', 2),
            ('kotlin_direct_display_literal', 'hint="提示"', 5),
        ], checker.kotlin_findings(source)[0])

    def test_kotlin_template_comments_braces_and_lines_are_independent(self):
        source = 'val marker = "${run {\n/* outer /* } " */ nested */\n"}"\n}}"\n'
        source += 'view.title = "标题"'
        self.assertEqual([
            ('kotlin_direct_display_literal', 'title="标题"', 5),
        ], checker.kotlin_findings(source)[0])

    def test_kotlin_executable_template_expressions_are_scanned_once(self):
        source = 'val marker = "${run { view.text = "中文"; UiText.text(payload); "done" }}"\n'
        source += 'view.hint = "提示"'
        rows, dynamic = checker.kotlin_findings(source)
        self.assertEqual([
            ('kotlin_direct_display_literal', 'text="中文"', 1),
            ('kotlin_direct_display_literal', 'hint="提示"', 2),
        ], rows)
        self.assertEqual(1, dynamic)

    def test_kotlin_unterminated_lexical_regions_fail_closed(self):
        for source in ['val x = "unterminated', 'val x = """unterminated',
                       '/* outer /* inner */', 'val x = "${run { "done" }',
                       'val x = `unterminated']:
            with self.subTest(source=source):
                with self.assertRaisesRegex(ValueError, 'UI_I18N_KOTLIN_UNTERMINATED_'):
                    checker.kotlin_findings(source)
        source = '"${' * (checker.KotlinLexer.MAX_TEMPLATE_DEPTH + 1)
        with self.assertRaisesRegex(ValueError, 'UI_I18N_KOTLIN_TEMPLATE_DEPTH'):
            checker.kotlin_findings(source)

    def test_kotlin_choose_templates_follow_argument_concatenation_policy(self):
        for source in [
                'UiText.choose("共 $count 项", "Total $count items")',
                'UiText.choose("""共 ${count} 项""", """Total ${count} items""")',
                'UiText.choose("完整中文", "Total ${compute(1, 2)} items")']:
            with self.subTest(source=source):
                rows, dynamic = checker.kotlin_findings(source)
                self.assertEqual(['translated_choose_argument_template'], [row[0] for row in rows])
                self.assertEqual(0, dynamic)
        rows, _ = checker.kotlin_findings('UiText.choose("共 " + count, "Total " + count)')
        self.assertEqual('translated_choose_argument_concat', rows[0][0])
        self.assertEqual(([], 0), checker.kotlin_findings('UiText.choose("完整中文", "Whole English")'))

    def test_kotlin_literal_dollars_and_escape_parity_are_preserved(self):
        dollar = "${'$'}"
        for source in [r'UiText.text("价格 \$5")',
                       'UiText.text("""价格 ' + dollar + '5""")',
                       'UiText.choose("""价格 ' + dollar + '5""", "Price")']:
            with self.subTest(source=source):
                self.assertEqual(([], 0), checker.kotlin_findings(source))
        for count in range(1, 5):
            source = 'UiText.text("共 ' + '\\' * count + '$count 项")'
            rows, _ = checker.kotlin_findings(source)
            self.assertEqual(int(count % 2 == 0), len(rows), source)
        rows, _ = checker.kotlin_findings(r'UiText.text("""共 \$count 项""")')
        self.assertEqual('translated_template_literal', rows[0][0])

    def test_kotlin_raw_unicode_escapes_are_literal_but_regular_escapes_are_not(self):
        self.assertEqual(([], 0), checker.kotlin_findings(r'setText("""\u4e2d""")'))
        rows, _ = checker.kotlin_findings(r'setText("\u4e2d")')
        self.assertEqual('kotlin_direct_display_literal', rows[0][0])
        self.assertEqual('java_direct_display_literal', checker.java_findings(r'setText("\u4e2d")')[0][0][0])

    def test_kotlin_apply_with_and_receiver_run_expose_implicit_properties(self):
        source = 'view.apply { text = "甲"; nested.apply { hint = "乙" }; text = "丙" }\n'
        source += 'with(view) { if (ready) { contentDescription = "丁" } }\n'
        source += 'view.run { title = "戊" }\nview?.apply { text = "己" }'
        rows, _ = checker.kotlin_findings(source)
        self.assertEqual(['text', 'hint', 'text', 'contentDescription', 'title', 'text'],
                         [row[1].split('=')[0] for row in rows])
        self.assertEqual([1, 1, 1, 2, 3, 4], [row[2] for row in rows])

    def test_kotlin_scope_lambda_arguments_do_not_bypass_receivers(self):
        source = 'view.apply({ text = "甲" })\nview.run(block = { hint = "乙" })\n'
        source += 'with(view, { title = "丙" })\nview.apply() { contentDescription = "丁" }\n'
        source += 'with(block = { title = "戊" }, receiver = view)'
        rows, _ = checker.kotlin_findings(source)
        self.assertEqual(['text', 'hint', 'title', 'contentDescription', 'title'],
                         [row[1].split('=')[0] for row in rows])
        self.assertEqual([1, 2, 3, 4, 5], [row[2] for row in rows])

    def test_kotlin_templates_inherit_enclosing_receiver_scope(self):
        source = 'view.apply { val marker = "${run { text = "中文"; "done" }}" }\n'
        source += 'view.run { val marker = """${"${run { hint = "提示"; "done" }}"}""" }'
        self.assertEqual([
            ('kotlin_direct_display_literal', 'text="中文"', 1),
            ('kotlin_direct_display_literal', 'hint="提示"', 2),
        ], checker.kotlin_findings(source)[0])

    def test_kotlin_receiver_scopes_do_not_capture_locals_named_arguments_or_plain_run(self):
        source = 'var text = "业务"\nrun { text = "业务"; val hint = "业务" }\n'
        source += 'view.apply { val text = "业务"; text = "业务"; foo(title = "业务"); '
        source += 'event.message = "业务" }\nother.let { title = "业务" }'
        self.assertEqual(([], 0), checker.kotlin_findings(source))
        source = 'view.apply { { val hint = "业务"; hint = "业务" }; hint = "提示" }\nhint = "业务"'
        self.assertEqual([
            ('kotlin_direct_display_literal', 'hint="提示"', 1),
        ], checker.kotlin_findings(source)[0])

    def test_kotlin_unknown_receiver_type_is_conservatively_reported(self):
        # 静态词法不能证明 model 的类型；同名显示属性需要人工核查，不成为绕过路径。
        rows, _ = checker.kotlin_findings('model.apply { title = "业务标题"; message = "业务原文" }')
        self.assertEqual([
            ('kotlin_direct_display_literal', 'title="业务标题"', 1),
        ], rows)

    def test_layout_keeps_java_kotlin_xml_and_nonproduction_boundaries_across_modules(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.layout(root, [
                {'path': ':platform:android', 'directory': 'platform/android', 'kind': 'android-library',
                 'sourceSets': ['main', 'test'], 'formatSourceSets': ['main', 'test'],
                 'testSourceSets': ['test'], 'testTask': 'testDebugUnitTest'},
                {'path': ':core:policy', 'directory': 'core/policy', 'kind': 'jvm',
                 'sourceSets': ['main', 'test'], 'formatSourceSets': ['main', 'test'],
                 'testSourceSets': ['test'], 'testTask': 'test'},
            ])
            sources = {
                'app/src/standard/java/pkg/A.java': 'setText("中文");',
                'platform/android/src/main/java/pkg/B.kt': 'view.text = "中文"',
                'platform/android/src/main/kotlin/pkg/C.java': 'setTitle("中文");',
                'core/policy/src/main/kotlin/pkg/D.kt': 'UiText.text("共 $count 项")',
                'platform/android/src/main/res/layout-land/page.xml': '<TextView android:text="Inline" />',
            }
            excluded = {
                'app/src/debug/kotlin/pkg/Debug.kt': 'view.text = "调试"',
                'core/policy/src/test/kotlin/pkg/Fixture.kt': 'view.text = "测试"',
            }
            for relative, content in {**sources, **excluded}.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf-8')
            rows, _ = checker.inventory(root)
            self.assertEqual(set(sources), {row['file'] for row in rows})
            self.assertEqual(5, sum(checker.compare(rows, [])[0].values()))

    def test_java_template_looking_strings_do_not_acquire_kotlin_rules(self):
        source = r'String marker = "${\"/*\"}"; view.setText("中文"); '
        source += 'UiText.text("中文 $name"); UiText.choose("价格 $x", "Price $x");'
        self.assertEqual(([
            ('java_direct_display_literal', 'setText("中文"', 1),
        ], 0), checker.java_findings(source))

    def test_catalog_format_grammar_rejects_unsupported_and_preserves_argument_roles(self):
        self.assertEqual(['s','d'], generator.format_signature('%s %d%%'))
        for value in ['%', '%f', '%1$s', '%n']:
            with self.assertRaisesRegex(ValueError, 'UI_FORMAT_UNSUPPORTED'):
                generator.format_signature(value)
        self.assertNotEqual(generator.format_signature('%s %d'), generator.format_signature('%d %s'))

    def test_direct_display_fragment_and_escaped_chinese_are_visible(self):
        rows, dynamic = checker.java_findings('button.setText("中文"); title.setText("\\u4e2d文"); '
            'Toast.makeText(getContext(), "提示", 0); UiText.text("共 ") + count; UiText.text(output);')
        self.assertEqual(3, sum(row[0] == 'java_direct_display_literal' for row in rows))
        self.assertEqual(1, sum(row[0] == 'translated_fragment_concat' for row in rows))
        self.assertEqual(1, dynamic)

    def test_comments_logs_protocol_and_user_text_are_not_display_literal_defects(self):
        source = '// setText("注释");\n/* UiText.text("片段") + n; */\n'
        source += 'log("中文日志"); setText(UiText.format("完整：%s", raw)); '
        source += 'setText(UiText.choose("中文", "English")); setText(webPrompt);'
        self.assertEqual(([], 0), checker.java_findings(source))

    def test_choose_fragments_on_both_sides_include_qualified_calls(self):
        source = 'UiText.choose("共 ", "Total: ") + raw; '
        source += 'raw + UiText.choose(" 项", " items"); '
        source += 'raw + com.deepseekharness.app.util.UiText.choose(" 后缀", " suffix"); '
        source += 'UiText.choose("\\u4e2d文：", "Chinese: ") + raw; '
        source += 'setText(UiText.choose("完整中文", "Whole English")); '
        source += 'setText(UiText.format("参数保持：%s", raw)); '
        source += '// UiText.choose("注释", "Comment") + raw;\n'
        rows, dynamic = checker.java_findings(source)
        self.assertEqual(4, len(rows))
        self.assertTrue(all(row[0] == 'translated_choose_fragment_concat' for row in rows))
        self.assertEqual(2, sum(row[1].startswith('+UiText.choose(') for row in rows))
        self.assertEqual(0, dynamic)

    def test_choose_arguments_keep_raw_values_but_expose_label_fragments(self):
        source = 'UiText.choose("共 " + count + " 项", "Total " + count + " items"); '
        source += 'UiText.choose("完整中文", "Whole English") + raw; '
        source += 'setText(UiText.format("总数：%s", compute(a + b)));'
        rows, _ = checker.java_findings(source)
        self.assertEqual(2, len(rows))
        self.assertEqual('translated_choose_argument_concat', rows[0][0])
        self.assertEqual('translated_choose_fragment_concat', rows[1][0])

    def test_complete_paragraph_nonfindings_require_exact_source_and_occurrence(self):
        row = {'kind': 'translated_choose_fragment_concat', 'file': 'app/src/main/A.java',
               'signature': 'UiText.choose("完整句。","Whole sentence.")+', 'sourceSha256': 'a'}
        declaration = dict(row, classification='independent_complete_paragraph_join',
                           reason='Two independently translated paragraphs, separated by a newline.',
                           occurrences=1)
        remaining, accepted, stale = checker.partition_nonfindings([row], [declaration])
        self.assertEqual([], remaining)
        self.assertEqual(1, len(accepted))
        self.assertEqual([], stale)
        self.assertEqual(1, len(checker.partition_nonfindings([row, row], [declaration])[0]))
        changed = dict(row, sourceSha256='b')
        remaining, accepted, stale = checker.partition_nonfindings([changed], [declaration])
        self.assertEqual([changed], remaining)
        self.assertEqual([], accepted)
        self.assertEqual(1, len(stale))

    def test_nonfinding_cannot_be_an_issue_id_or_reasonless_waiver(self):
        with self.assertRaisesRegex(ValueError, 'UI_I18N_NONFINDING_SCHEMA'):
            checker.partition_nonfindings([], [{'classification': 'all_choose_is_safe', 'occurrences': 1}])

    def test_inventory_ignores_debug_history_generated_and_resource_references(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.layout(root)
            for source_set in ['main', 'debug']:
                path = root / 'app/src' / source_set / 'res/layout/example.xml'
                path.parent.mkdir(parents=True)
                path.write_text('<TextView android:text="Inline" android:hint="@string/hint" />', encoding='utf-8')
            rows, count = checker.inventory(root)
            self.assertEqual(1, len(rows))
            self.assertEqual('text=Inline', rows[0]['signature'])
            self.assertEqual(0, count)

    def test_removing_one_debt_cannot_hide_a_new_sink_or_extra_occurrence(self):
        row = {'kind': 'xml_literal', 'file': 'app/src/main/res/layout/a.xml', 'signature': 'text=Old'}
        replacement = dict(row, signature='text=New')
        new, removed = checker.compare([replacement], [row])
        self.assertEqual(1, sum(new.values()))
        self.assertEqual(1, sum(removed.values()))
        self.assertEqual(1, sum(checker.compare([row, row], [row])[0].values()))
        self.assertFalse(checker.compare([], [row])[0])


if __name__ == '__main__':
    unittest.main()
