#!/usr/bin/env python3
"""Run production Java admission for the web-search recipe and optional exact APKs."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
import runtime_stable_patches

ROOT = Path(__file__).resolve().parents[1]
ASSET = 'deepseek-web-search-headers-patch.json'
MODULE = '@deepseek-ai/dsh-web-search-deepseek/lib/index.js'
OLD_APK_SHA = 'ba5fd1e19c5043fe000f02a3ffb56f125939a11b87bad0988d1677c73334cb26'
OLD_MODULE_SHA = 'fc13dd4eaba3c9298af41a4d0559a1b8bb61614462fe18ba6e76d8c598c332bd'
# Retained 0.2.1-rc.2 canonical module, used as the real historical input.
DEFAULT_OLD_MODULE = ROOT / 'tools/fixtures/web-search-rc2/index.js'
JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class WebSearchChainFixture {
  static void require(boolean condition,String reason) {if(!condition)throw new AssertionError(reason);}
  static List<ManagedPatchChain.Recipe> chain(Path root) throws Exception {
    var result=new ArrayList<ManagedPatchChain.Recipe>();
    for(int i=0;i<Integer.parseInt(Files.readString(root.resolve("count.txt")));i++) {
      Path entry=root.resolve(Integer.toString(i));var patches=new ArrayList<ManagedPatchChain.Patch>();
      for(int j=0;j<Integer.parseInt(Files.readString(entry.resolve("count.txt")));j++)
        patches.add(new ManagedPatchChain.Patch(Files.readString(entry.resolve(j+"-before.txt")),Files.readString(entry.resolve(j+"-after.txt"))));
      result.add(new ManagedPatchChain.Recipe(Files.readString(entry.resolve("id.txt")),patches,Files.readAllLines(entry.resolve("markers.txt"))));
    }
    return result;
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);var chain=chain(root.resolve("chain"));String expected=Files.readString(root.resolve("expected.js"));
    for(String input:List.of("official.js","current-pre.js","expected.js")) {
      String result=ManagedPatchChain.apply(Files.readString(root.resolve(input)),chain,()->{throw new AssertionError(input+" unexpectedly restored canonical");});
      require(result.equals(expected),input+" differs");
      System.out.println("PASS_WEB_SEARCH_CHAIN "+input);
    }
    require(ManagedPatchChain.apply("unknown historical source",chain,()->expected).equals(expected),"canonical recovery differs");
    System.out.println("PASS_WEB_SEARCH_CHAIN guarded canonical recovery");
    for(String canonical:Arrays.asList(null,"unknown canonical source")) {
      try {ManagedPatchChain.apply("unknown historical source",chain,()->canonical);throw new AssertionError("invalid canonical accepted");}
      catch(IOException error) {require(error.getMessage().contains(canonical==null?"PATCH_CANONICAL_MISSING":"PATCH_CHAIN_MISMATCH"),"wrong guarded error");}
    }
    System.out.println("PASS_WEB_SEARCH_CHAIN missing and invalid canonical fail closed");
    if(Files.exists(root.resolve("old-rc2.js"))) {
      String old=Files.readString(root.resolve("old-rc2.js"));
      AtomicInteger restores=new AtomicInteger();
      String crossed=ManagedPatchChain.apply(old,chain,()->{restores.incrementAndGet();return expected;});
      boolean differs=!crossed.equals(expected);
      System.out.println("HISTORICAL_RC2 differs="+differs+" restores="+restores.get());
      if(!differs && restores.get()==0) throw new AssertionError("rc2 silently produced current expected");
    }
    if(Files.exists(root.resolve("apk.js"))) {
      String exact=Files.readString(root.resolve("apk.js"));require(exact.equals(expected),"exact APK module differs");
      require(ManagedPatchChain.apply(exact,chain,()->{throw new AssertionError("exact APK restore");}).equals(expected),"exact APK repeat differs");
      System.out.println("PASS_WEB_SEARCH_CHAIN exact final APK/repeat");
    }
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def module_from_apk(apk):
    with zipfile.ZipFile(apk) as package:
        with tarfile.open(fileobj=io.BytesIO(package.read('assets/dsh-runtime.bin')), mode='r:*') as runtime:
            matches = [row for row in runtime.getmembers() if row.isfile() and row.name.endswith('/node_modules/' + MODULE)]
            assert len(matches) == 1, 'APK_SEARCH_MODULE_UNIQUENESS'
            return runtime.extractfile(matches[0]).read()


def write_chain(folder, chain):
    folder.mkdir()
    (folder / 'count.txt').write_bytes(str(len(chain)).encode())
    for index, (name, patches, markers) in enumerate(chain):
        target = folder / str(index)
        target.mkdir()
        (target / 'id.txt').write_bytes(name.encode())
        (target / 'count.txt').write_bytes(str(len(patches)).encode())
        (target / 'markers.txt').write_bytes('\n'.join(markers).encode())
        for number, (before, after) in enumerate(patches):
            (target / (str(number) + '-before.txt')).write_bytes(before.encode())
            (target / (str(number) + '-after.txt')).write_bytes(after.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--old-apk', type=Path, help='read-only exact retained build191/1640 Standard APK')
    parser.add_argument('--apk', type=Path, help='read-only exact final build192 Standard APK')
    parser.add_argument('--old-module', type=Path, default=DEFAULT_OLD_MODULE,
                        help='retained 0.2.1-rc.2 canonical module (historical input)')
    args = parser.parse_args()
    if __debug__ is False:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    assets = ROOT / 'app/src/main/assets'
    recipe = json.loads((assets / ASSET).read_text(encoding='utf8'))
    chains, _ = runtime_stable_patches.load_chains(assets, json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh'])
    chain = chains.get(MODULE, [])
    assert any(name == ASSET for name, _, _ in chain), 'WEB_SEARCH_RECIPE_MUST_BE_REGISTERED'
    pointer = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
    canonical = (Path(pointer['raw']) / 'node_modules' / MODULE).read_bytes()
    assert sha(canonical) == recipe['sourceSha256'], 'LOCKED_SEARCH_MODULE_IDENTITY'
    expected = runtime_stable_patches.apply(canonical, chain)
    assert sha(expected) == recipe['patchedSha256'], 'FINAL_SEARCH_MODULE_IDENTITY'
    prior = [row for row in chain if row[0] != ASSET]
    current_pre = runtime_stable_patches.apply(canonical, prior)
    if args.old_apk:
        assert sha(args.old_apk.read_bytes()) == OLD_APK_SHA, 'OLD_191_APK_IDENTITY'
        old = module_from_apk(args.old_apk)
        old_source = 'build191/1640 APK'
    else:
        if not args.old_module.is_file():
            print(json.dumps({'status': 'BLOCKED', 'reason': 'HISTORICAL_OLD_MODULE_MISSING', 'path': str(args.old_module)}))
            return 2
        old = args.old_module.read_bytes()
        old_source = 'rc.2 npm module'
    assert sha(old) == OLD_MODULE_SHA, 'OLD_MODULE_IDENTITY'
    # Cross-generation policy: the current chain must reject the historical input
    # (fail closed), not silently re-apply it or treat current-pre as the old module.
    try:
        crossed = runtime_stable_patches.apply(old, chain)
        cross_result = 'SILENT_ACCEPT' if crossed == expected else 'APPLIED_BUT_DIFFERS'
    except ValueError:
        cross_result = 'REJECTED'
    assert cross_result != 'SILENT_ACCEPT', 'current chain silently accepted rc2 module as current'
    assert cross_result in {'REJECTED', 'APPLIED_BUT_DIFFERS'}, cross_result
    owned = Path(tempfile.mkdtemp(prefix='web-search-java-chain-', dir=ROOT / 'app/build'))
    for name, data in [('official.js', canonical), ('current-pre.js', current_pre), ('expected.js', expected)]:
        (owned / name).write_bytes(data)
    (owned / 'old-rc2.js').write_bytes(old)
    if args.apk:
        (owned / 'apk.js').write_bytes(module_from_apk(args.apk))
    write_chain(owned / 'chain', chain)
    (owned / 'WebSearchChainFixture.java').write_bytes(JAVA.encode())
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    assert javac and java, 'JDK17_REQUIRED'
    production = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
    classes = owned / 'classes'
    classes.mkdir()
    command = [javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes), str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'), str(ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'), str(owned / 'WebSearchChainFixture.java')]
    compiled = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'JAVA_COMPILE_FAILED: ' + str(owned)
    ran = subprocess.run([java, '-cp', str(classes), 'WebSearchChainFixture', str(owned)], capture_output=True, timeout=60)
    (owned / 'java.log').write_bytes(ran.stdout + ran.stderr)
    java_text = (ran.stdout + ran.stderr).decode('utf8', errors='replace')
    historical_status = 'NOT_RUN'
    for line in java_text.splitlines():
        if line.startswith('HISTORICAL_RC2 '):
            parts = dict(item.split('=', 1) for item in line.split()[1:])
            differs = parts.get('differs') == 'true'
            restores = int(parts.get('restores', '-1'))
            historical_status = 'BLOCKED_SEMANTIC_DIFF' if differs else ('PASS_RECOVERY_ONCE' if restores == 1 else 'BLOCKED_NO_RECOVERY')
    overall = 'PASS' if ran.returncode == 0 and historical_status in ('PASS_RECOVERY_ONCE', 'NOT_RUN') else ('FAIL' if ran.returncode else 'BLOCKED')
    result = dict(status=overall, exitCode=ran.returncode if overall == 'FAIL' else (3 if overall == 'BLOCKED' else 0), module=MODULE,
                  sourceSha256=sha(canonical), patchedSha256=sha(expected),
                  currentPreSha256=sha(current_pre),
                  historicalOldModuleSha256=sha(old),
                  historicalOldSource=old_source,
                  historicalOldAcceptedByCurrentChain=(cross_result == 'SILENT_ACCEPT'),
                  crossGenerationResult=cross_result,
                  historicalJavaStatus=historical_status,
                  old191ApkSha256=OLD_APK_SHA if args.old_apk else None,
                  apkSha256=sha(args.apk.read_bytes()) if args.apk else None,
                  recipes=[name for name, _, _ in chain], productionAlgorithmSha256={name:sha((production / name).read_bytes()) for name in ['ManagedPatchChain.java','ExactTextPatch.java']},
                  deviceValidation=False, output=str(owned))
    (owned / 'result.json').write_bytes((json.dumps(result, indent=2) + '\n').encode())
    print(json.dumps(result))
    if ran.returncode:
        print(java_text)
        raise SystemExit(ran.returncode)
    if overall != 'PASS':
        print(java_text)
        raise SystemExit(result['exitCode'])


if __name__ == '__main__':
    main()
