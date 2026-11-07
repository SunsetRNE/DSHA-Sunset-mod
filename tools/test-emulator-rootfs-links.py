#!/usr/bin/env python3
"""rootfs 软链规则的负控。

标准 Ubuntu rootfs 带 220 个软链（bin -> usr/bin、usr/bin/awk -> ../../etc/alternatives/awk）。
上一版只放行 /data/local/tmp/ 开头的宿主链接，等于按文档做出来的 x86 rootfs 无法进入
资产生成；这里锁住「相对/根内绝对放行、越界/缺失/成环/宿主路径拒绝」。
"""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


assets = load_script('emulator_assets', 'prepare-emulator-assets.py')


class Member:
    """够用的 tar member 替身：只回答 issym/isfile 与 linkname。"""

    def __init__(self, name, kind='file', link=''):
        self.name = name
        self.kind = kind
        self.linkname = link

    def issym(self):
        return self.kind == 'sym'

    def isfile(self):
        return self.kind == 'file'

    def isdir(self):
        return self.kind == 'dir'


class ResolveLinkTest(unittest.TestCase):
    def members(self):
        return {
            'usr/bin': Member('usr/bin', 'dir'),
            'usr/bin/awk': Member('usr/bin/awk', 'sym', '../../etc/alternatives/awk'),
            'etc/alternatives/awk': Member('etc/alternatives/awk'),
            'usr/lib/x86_64-linux-gnu/libc.so.6': Member('usr/lib/x86_64-linux-gnu/libc.so.6'),
            'lib/c': Member('lib/c', 'sym', '../usr/lib/x86_64-linux-gnu/libc.so.6'),
            'loop/a': Member('loop/a', 'sym', 'b'),
            'loop/b': Member('loop/b', 'sym', 'a'),
        }

    def test_relative_link_inside_root_is_kept(self):
        self.assertEqual('usr/bin', assets.resolve_link('bin', 'usr/bin', self.members()))

    def test_relative_link_with_parent_segments_stays_inside(self):
        self.assertEqual(
            'etc/alternatives/awk', assets.resolve_link('usr/bin/awk', '../../etc/alternatives/awk', self.members()))

    def test_absolute_link_resolves_against_guest_root(self):
        self.assertEqual(
            'usr/lib/x86_64-linux-gnu/libc.so.6',
            assets.resolve_link('lib/c', '/usr/lib/x86_64-linux-gnu/libc.so.6', self.members()))

    def test_escaping_root_is_rejected(self):
        with self.assertRaises(ValueError) as caught:
            assets.resolve_link('bad', '../../../../etc/passwd', self.members())
        self.assertIn('EMULATOR_ROOTFS_LINK_ESCAPE', str(caught.exception))

    def test_absolute_host_path_is_rejected(self):
        with self.assertRaises(ValueError) as caught:
            assets.resolve_link('bad', '/data/local/tmp/evil', self.members())
        self.assertIn('EMULATOR_HOST_LINK', str(caught.exception))

    def test_missing_target_is_rejected(self):
        with self.assertRaises(ValueError) as caught:
            assets.resolve_link('bin', 'usr/nope', self.members())
        self.assertIn('EMULATOR_ROOTFS_LINK_MISSING', str(caught.exception))

    def test_cycle_is_rejected(self):
        with self.assertRaises(ValueError) as caught:
            assets.resolve_link('loop/a', 'b', self.members())
        self.assertIn('EMULATOR_LINK_CYCLE', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
