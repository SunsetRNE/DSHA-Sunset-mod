"""Record the narrow product-identity checks for the supplied DSH Go article."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile
import time
from zipfile import ZipFile


def digest(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('article', 'repo', 'apk', 'output'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    started = datetime.now(timezone.utc).isoformat()
    clock = time.monotonic()
    signing = json.loads((args.apk.parent / 'apk-signing.json').read_bytes())
    invoke = json.loads((args.apk.parent / 'invocation.json').read_bytes())
    repo = args.repo.resolve()
    commit = subprocess.check_output(['git', '-C', repo, 'rev-parse', 'HEAD'], text=True).strip()
    apk_sha = digest(args.apk)
    assert invoke['commit'] == commit and invoke['exitCode'] == 0 and signing['apkSha256'] == apk_sha
    first = time.monotonic() - clock
    paths = ['app/src/main/java', 'app/src/main/kotlin', 'app/src/main/res',
             'app/src/main/assets', 'app/build.gradle', 'gradle/libs.versions.toml']
    terms = ['live2d', 'cubism', r'voice[ _-]?clon', '声音克隆|语音克隆',
             'sillytavern|酒馆|角色卡', r'\b(character|tavern)[ _-]?cards?\b']
    searches = {}
    for pattern in terms:
        command = ['git', '-C', repo, 'grep', '-I', '-l', '-i', '-E', pattern, commit, '--', *paths]
        result = subprocess.run(command, capture_output=True)
        if result.returncode not in (0, 1):
            raise RuntimeError(result.stderr.decode(errors='replace'))
        searches[pattern] = result.stdout.decode('utf-8').splitlines()
    names = re.compile(r'live2d|cubism|sillytavern|酒馆|角色卡|声音克隆|语音克隆|\b(?:character|tavern)[ _-]?cards?\b|voice[ _-]?clon|\.moc3$|\.model3\.json$', re.I)
    with ZipFile(args.apk) as apk:
        apk_names = apk.namelist()
        with apk.open('assets/dsh-runtime.bin') as payload:
            runtime_sha = hashlib.file_digest(payload, 'sha256').hexdigest()
        with apk.open('assets/dsh-runtime.bin') as payload, tarfile.open(fileobj=payload, mode='r|gz') as tree:
            runtime_names = [member.name for member in tree]
    with args.output.open('x', encoding='utf-8') as out:
        json.dump(dict(article=str(args.article.resolve()), articleSha256=digest(args.article),
            commit=commit, apk=str(args.apk.resolve()), apkSha256=apk_sha, runtimeSha256=runtime_sha,
            startedUtc=started, finishedUtc=datetime.now(timezone.utc).isoformat(),
            firstApkIdentitySeconds=round(first, 3), totalSeconds=round(time.monotonic()-clock, 3),
            sourceTextRoots=paths, sourceTextMatchingFiles=searches, memberNamePattern=names.pattern,
            apkMemberCount=len(apk_names), matchingApkNames=[x for x in apk_names if names.search(x)],
            runtimeMemberCount=len(runtime_names), matchingRuntimeNames=[x for x in runtime_names if names.search(x)],
            scope='Tracked nonbinary Android inputs and member names only. Excludes runtime-loaded plugins, remote services, content supplied by users, and renamed or embedded features.'),
            out, ensure_ascii=False, indent=2)
        out.write('\n')
    print(args.output)


if __name__ == '__main__':
    main()
