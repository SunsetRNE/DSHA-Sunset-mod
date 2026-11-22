"""Read CI tool versions only after checking the source build configuration."""
import argparse
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ('java', 'node', 'python', 'gradle', 'agp', 'androidPlatform', 'buildTools', 'ndk')


def one(pattern, content, label):
    values = re.findall(pattern, content)
    if len(values) != 1:
        raise ValueError('CI_TOOLCHAIN_SOURCE: ' + label)
    return values[0]


def java_version(root, app):
    # 配置已整体迁入约定；先验证 app 的实际接线，再读取唯一编译目标，不能复制回 app 迎合旧正则。
    convention = one(r"(?m)^apply\s+from:\s*rootProject\.file\(['\"](build-logic/android\.gradle)['\"]\)\s*$",
                     app, 'android convention')
    if re.search(r'(?m)^\s*(?:sourceCompatibility|targetCompatibility)\b', app):
        raise ValueError('CI_TOOLCHAIN_DUPLICATE_JAVA_OWNER')
    shared = (root / convention).read_text(encoding='utf-8')
    source = one(r'(?m)^\s*sourceCompatibility\s+JavaVersion\.VERSION_(\d+)\s*$', shared, 'java')
    target = one(r'(?m)^\s*targetCompatibility\s+JavaVersion\.VERSION_(\d+)\s*$', shared, 'java target')
    if source != target:
        raise ValueError('CI_TOOLCHAIN_JAVA_TARGET')
    return source


def read(root=ROOT):
    lock = json.loads((root / 'ci/toolchain.lock.json').read_text(encoding='utf-8'))
    if lock.get('schema') != 1 or set(lock) != {'schema', *FIELDS}:
        raise ValueError('CI_TOOLCHAIN_SCHEMA')
    for name in FIELDS:
        if not isinstance(lock[name], str) or not re.fullmatch(r'[A-Za-z0-9.-]+', lock[name]):
            raise ValueError('CI_TOOLCHAIN_VALUE: ' + name)
    app = (root / 'app/build.gradle').read_text(encoding='utf-8')
    top = (root / 'build.gradle').read_text(encoding='utf-8')
    wrapper = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    platform = one(r'version\s*=*\s*release\(\s*(\d+)\s*\)', app, 'compileSdk')
    expected = {
        'java': java_version(root, app),
        'gradle': one(r'distributionUrl=[^\n]+/gradle-([0-9.]+)-bin\.zip', wrapper, 'gradle'),
        'agp': one(r"id\s+'com.android.application'\s+version\s+'([0-9.]+)'", top, 'agp'),
        'ndk': one(r"ndkVersion\s+['\"]([0-9.]+)['\"]", app, 'ndk'),
        'androidPlatform': 'android-' + platform + '.0',
    }
    if one(r'targetSdk\s+(\d+)', app, 'targetSdk') != platform:
        raise ValueError('CI_TOOLCHAIN_TARGET_SDK')
    for name, value in expected.items():
        if lock[name] != value:
            raise ValueError('CI_TOOLCHAIN_DRIFT: ' + name)
    return lock


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--field', choices=FIELDS)
    parser.add_argument('--github-output', action='store_true')
    args = parser.parse_args()
    lock = read()
    if args.github_output:
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8', newline='\n') as output:
            for name in FIELDS:
                output.write(name + '=' + lock[name] + '\n')
    elif args.field:
        print(lock[args.field])
    else:
        print(json.dumps(lock, sort_keys=True))


if __name__ == '__main__':
    main()
