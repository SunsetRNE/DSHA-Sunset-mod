"""Wait for the specified signed candidate, then independently verify its native source companion."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def wait_for_receipts(files):
    while True:
        try:
            return [read_json(path) for path in files]
        except (FileNotFoundError, json.JSONDecodeError):
            time.sleep(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--round', required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    root = args.evidence.parents[1]
    tools = root / 'toolchains/current'
    repo = root / 'source/build377-local'
    sdk = tools / 'android-sdk'
    audit = args.evidence / 'source-rights'
    folder = args.evidence / args.candidate
    out = args.evidence / args.round / 'source-rights'
    cert = 'e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5'
    receipts = [folder/'apk-signing.json', folder/'invocation.json', out.parent/'signed-source-companion.json']
    print('Waiting for signed APK, signing invocation, and matching source companion receipt.', flush=True)
    signing, invocation, source = wait_for_receipts(receipts)
    if (signing['kind'] != 'dsha-release-signed-apk' or invocation['exitCode'] != 0 or
            invocation['commit'] != args.commit or invocation['certificateSha256'] != cert or
            signing['certificateSha256'] != cert or not all(signing['schemes'][s] for s in ('v1','v2','v3'))):
        raise ValueError('SIGNING_RECEIPT_MISMATCH')
    if signing['apkFile'] != 'dsha-0.2.2-rc.5.apk':
        raise ValueError('UNEXPECTED_APK')
    apk = folder / signing['apkFile']
    companion = folder / 'dsha-proot-talloc-source.zip'
    apk_sha = digest(apk)
    source_sha = digest(companion)
    if (apk_sha != signing['apkSha256'] or apk_sha != source['apkSha256'] or apk.stat().st_size != signing['apkSize']
            or source_sha != source['sourceSha256'] or companion.stat().st_size != source['sourceBytes']):
        raise ValueError('SIGNED_FILES_MATCH_NEITHER_RECEIPT')
    with zipfile.ZipFile(apk) as z:
        if json.loads(z.read('assets/rust-native-receipt.json'))['commit'] != args.commit:
            raise ValueError('SIGNED_COMMIT_MISMATCH')
    start = time.perf_counter()
    verify = [tools/'jdk-17/bin/java.exe', '-jar', sdk/'build-tools/36.0.0/lib/apksigner.jar', 'verify',
              '--min-sdk-version', '21', '--verbose', '--print-certs', apk]
    result = subprocess.run(verify, check=True, capture_output=True, text=True)
    sig = {scheme:bool(re.search(r'^Verified using '+scheme+r' scheme .*: true$', result.stdout, re.MULTILINE))
           for scheme in ('v1','v2','v3')}
    verified_certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', result.stdout, re.MULTILINE)
    if not all(sig.values()) or verified_certs != [cert]:
        raise ValueError('INDEPENDENT_CERTIFICATE_OR_SIGNATURE_MISMATCH')
    elapsed = time.perf_counter()-start
    out.mkdir(parents=True,exist_ok=True)
    release = root/'release/0.2.2-rc.5'
    baseline_paths = [release/'dsha-0.2.2-rc.5.apk', *sorted((release/'history').glob('*build376*/*.apk'))]
    baseline = next(p for p in baseline_paths if p.is_file() and digest(p)=='b9ec7b9a45b1c3463b5ea820cd36b9a32d4aa0228f372fee96080714fc3397cc')
    command=[sys.executable, '-B', audit/'replay-source.py','--source',repo,'--audit',audit,'--ndk',
             sdk/'ndk/26.3.11579264','--before-apk',baseline,'--after-apk',apk,'--companion',companion]
    for name in ('signed-final.json', 'signed-final-bound.json'):
        subprocess.run(command+['--output',out/name], cwd=repo, check=True)
        row = read_json(out/name)
        if (row['afterApk']['sha256'] != apk_sha or row['embeddedCommit'] != args.commit or
                row['companion']['sha256'] != source_sha or len(row['gitArchiveInputs']) != 7 or
                len(row['afterVerifiedMembers']) != 9):
            raise ValueError('REPLAY_BINDING_MISMATCH')
    bound = read_json(out/'signed-final-bound.json')
    bound['independentAndroidSignature'] = dict(program=[str(x) for x in verify],returnCode=result.returncode,
        seconds=elapsed,schemes=sig,certificateSha256=cert,receiptDigests={str(p.relative_to(args.evidence)):digest(p) for p in receipts})
    (out/'signed-final-bound.json').write_text(json.dumps(bound,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    command = [sys.executable, '-B', repo/'tools/proot_source_distribution.py','--apk',apk,'--check-source',companion]
    release_copy = json.loads(subprocess.check_output(command,cwd=repo,text=True))
    if (release_copy['apkSha256'] != apk_sha or release_copy['sourceSha256'] != source_sha
            or release_copy['libraries'] != source['libraries']):
        raise ValueError('SOURCE_RELEASE_COPY_MISMATCH')
    (out/'signed-release-copy.json').write_text(json.dumps(release_copy,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(apkSha256=apk_sha,apkBytes=apk.stat().st_size,sourceSha256=source_sha,
        sourceBytes=companion.stat().st_size,commit=args.commit,signature=sig)), flush=True)


if __name__=='__main__':
    main()
