#!/usr/bin/env python3
"""Verify every current Java patch chain and the exact build212 (rc3) generation boundary.

Old-generation modules are evidence, never a substitute for staging the signed
new runtime archive through ManagedRuntimeTransaction/RuntimeTrial. Marker
idempotence cannot prove that the official upstream module was upgraded."""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
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
NAMES = {'plugin-pnpm-argv-patch.json', 'language-patch.json'}
OLD_APK_SHA = '2a69a315eca22f8711a97122a54a2f8beb4d98e624a04d8bb3afa65a420e89d2'
OLD_MODULE_SHA = {
    '@deepseek-ai/dsh-plugin-manager/lib/index.js': '7b287c984c0f9e1835e59f3b5d5bf60a68f23430b627a85df41455136a453874',
}
OLD_ARCHIVE_SHA = 'a02cc5a42727238491ee36d4b0b46461d035dd6f8fca22580eb19d8456fbefc3'
OLD_FRONTEND = '@deepseek-ai/dsh-web-frontend/dist/assets/index-BXV-oxvX.js'
OLD_UNSHIPPED_MODULES = {
    '@deepseek-ai/dsh-tools/lib/pipeline.js',
    '@deepseek-ai/dsh-official-bundles/lib/index.js',
    '@deepseek-ai/dsh-client-ui-file-actions/lib/client.js',
}

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class Build193PatchChainFixture {
  static void require(boolean valid,String reason) {if(!valid)throw new AssertionError(reason);}
  static List<ManagedPatchChain.Recipe> chain(Path folder) throws Exception {
    var result=new ArrayList<ManagedPatchChain.Recipe>();
    for(int i=0;i<Integer.parseInt(Files.readString(folder.resolve("count.txt")));i++) {
      Path recipe=folder.resolve(Integer.toString(i));var patches=new ArrayList<ManagedPatchChain.Patch>();
      for(int j=0;j<Integer.parseInt(Files.readString(recipe.resolve("count.txt")));j++)
        patches.add(new ManagedPatchChain.Patch(Files.readString(recipe.resolve(j+"-before.txt")),Files.readString(recipe.resolve(j+"-after.txt"))));
      int previousCount=Integer.parseInt(Files.readString(recipe.resolve("previous-count.txt")));
      var previous=new ArrayList<ManagedPatchChain.Patch>();
      for(int j=0;j<previousCount;j++)
        previous.add(new ManagedPatchChain.Patch(Files.readString(recipe.resolve("previous-"+j+"-before.txt")),Files.readString(recipe.resolve("previous-"+j+"-after.txt"))));
      var current=new ManagedPatchChain.Recipe(Files.readString(recipe.resolve("id.txt")),patches,Files.readAllLines(recipe.resolve("markers.txt")),previous);
      require(current.previousPatches().size()==previousCount,"fixture dropped signed predecessor metadata");
      result.add(current);
    }
    return result;
  }
  static void rejectWithoutCanonical(String source,List<ManagedPatchChain.Recipe> chain)throws Exception {
    for(String canonical:Arrays.asList(null,"incomplete canonical")) {
      try { ManagedPatchChain.apply(source,chain,()->canonical);throw new AssertionError("bad canonical accepted"); }
      catch(IOException error) { require(error.getMessage().contains(canonical==null?"PATCH_CANONICAL_MISSING":"PATCH_CHAIN_MISMATCH"),"wrong guarded error"); }
    }
  }
  static void predecessorControls(String old,List<ManagedPatchChain.Recipe> chain,String expected)throws Exception {
    int variants=0;
    for(var recipe:chain)for(var previous:recipe.previousPatches()) {
      require(old.contains(previous.after())&&old.indexOf(previous.after())==old.lastIndexOf(previous.after()),"old192 predecessor must match exactly once");
      String partial=old.replace(previous.after(),previous.before());
      rejectWithoutCanonical(partial,chain);
      var reads=new java.util.concurrent.atomic.AtomicInteger();
      require(ManagedPatchChain.apply(partial,chain,()->{reads.incrementAndGet();return expected;}).equals(expected),"partial predecessor canonical repair differs");
      require(reads.get()==1,"partial predecessor bypassed canonical repair");
      variants++;
    }
    if(variants==0)return;
    rejectWithoutCanonical(old+old,chain);
    // Negative control for this fixture's former bug: dropping predecessor
    // metadata must change admission, rather than silently weakening the gate.
    var omitted=new ArrayList<ManagedPatchChain.Recipe>();
    for(var recipe:chain)omitted.add(new ManagedPatchChain.Recipe(recipe.id(),recipe.patches(),recipe.markers()));
    rejectWithoutCanonical(old,omitted);
    System.out.println("PASS_SIGNED_PREDECESSOR partial-variants="+variants+" duplicate/metadata-omission/failclosed");
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    for(String id:Files.readAllLines(root.resolve("modules.txt"))) {
      Path module=root.resolve(id);String expected=Files.readString(module.resolve("expected.js"));
      var chain=chain(module.resolve("chain"));
      for(String input:List.of("canonical.js","expected.js")) {
        String value=ManagedPatchChain.apply(Files.readString(module.resolve(input)),chain,()->{throw new AssertionError(input+" unexpectedly used canonical restore");});
        require(value.equals(expected),input+" differs");
      }
      // A chain is an overlay preparer, not an upstream generation installer.
      // Compare Java with the independently evaluated old-module chain result;
      // a stale marker-only result requires the separately staged new archive.
       Path oldPath=module.resolve("old-192.js"), historicalAbsence=module.resolve("historical-module-unavailable.txt");
       if(Files.exists(oldPath)) {
         var repairs=new java.util.concurrent.atomic.AtomicInteger();
         String old=Files.readString(oldPath);
         String directExpected=Files.exists(module.resolve("old-direct.js"))
             ?Files.readString(module.resolve("old-direct.js")):expected;
         String direct=ManagedPatchChain.apply(old,chain,()->{repairs.incrementAndGet();return expected;});
         require(direct.equals(directExpected),"build212 Java/Python chain semantics differ");
         require(repairs.get()==(Files.exists(module.resolve("old-direct.js"))?0:1),"build212 canonical boundary differs");
         if(Files.exists(module.resolve("predecessor-unavailable.txt")))
           System.out.println("PASS_PREDECESSOR_NOT_APPLICABLE "+Files.readString(module.resolve("predecessor-unavailable.txt")));
         else
           predecessorControls(old,chain,expected);
       } else {
         require(Files.isRegularFile(historicalAbsence),"missing historical fixture provenance");
         System.out.println("HISTORICAL_PATH_ABSENT "+Files.readString(historicalAbsence));
       }
      String staged=ManagedPatchChain.apply(expected,chain,()->{throw new AssertionError("staged new archive restore");});
      require(staged.equals(expected),"staged complete current chain differs");
      String fromArchive=ManagedPatchChain.apply("incomplete previous managed module",chain,()->expected);
      require(fromArchive.equals(expected),"new managed canonical rebuild differs");
      rejectWithoutCanonical("incomplete old module",chain);
      if(Files.exists(module.resolve("apk.js"))) {
        String exact=Files.readString(module.resolve("apk.js"));require(exact.equals(expected),"exact new APK archive differs");
        require(ManagedPatchChain.apply(exact,chain,()->{throw new AssertionError("exact APK restore");}).equals(expected),"exact APK repeat differs");
      }
       System.out.println("PASS_CURRENT_CHAIN fresh/repeat/staged-canonical/failclosed "+Files.readString(module.resolve("name.txt")));
    }
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def stream_sha(source):
    digest = hashlib.sha256()
    for block in iter(lambda: source.read(1_048_576), b''):
        digest.update(block)
    return digest.hexdigest()


def file_sha(file):
    with file.open('rb') as source:
        return stream_sha(source)


@contextmanager
def archive(apk):
    with (zipfile.ZipFile(apk) as package,
          package.open('assets/dsh-runtime.bin') as source,
          tarfile.open(fileobj=source, mode='r|*') as runtime):
        yield runtime


def module_payloads(archive_file, wanted):
    matches = {}
    for entry in archive_file:
        module = entry.name.rsplit('/node_modules/', 1)[-1]
        if entry.isfile() and module in wanted:
            assert module not in matches, 'APK_MODULE_UNIQUENESS: ' + module
            matches[module] = archive_file.extractfile(entry).read()
    return matches


def write_chain(folder, chain):
    folder.mkdir()
    (folder / 'count.txt').write_bytes(str(len(chain)).encode())
    for i, (name, patches, markers) in enumerate(chain):
        target = folder / str(i)
        target.mkdir()
        (target / 'id.txt').write_bytes(name.encode())
        (target / 'count.txt').write_bytes(str(len(patches)).encode())
        (target / 'markers.txt').write_bytes('\n'.join(markers).encode())
        for j, (before, after) in enumerate(patches):
            (target / (str(j) + '-before.txt')).write_bytes(before.encode())
            (target / (str(j) + '-after.txt')).write_bytes(after.encode())
        previous = getattr(patches, 'previous', ())
        (target / 'previous-count.txt').write_bytes(str(len(previous)).encode())
        for j, (before, after) in enumerate(previous):
            (target / ('previous-' + str(j) + '-before.txt')).write_bytes(before.encode())
            (target / ('previous-' + str(j) + '-after.txt')).write_bytes(after.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--old-apk', type=Path, default=os.environ.get('DSHA_PREVIOUS_RUNTIME_APK'), help='read-only exact published build212 Standard APK (required unless --old-archive)')
    parser.add_argument('--old-archive', type=Path, default=os.environ.get('DSHA_PREVIOUS_RUNTIME_ARCHIVE'), help='read-only hash-pinned build212 runtime archive, mutually exclusive with --old-apk')
    parser.add_argument('--apk', type=Path, help='read-only exact new APK for final archive validation')
    args = parser.parse_args()
    if __debug__ is False:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    if bool(args.old_apk) == bool(args.old_archive):
        parser.error('provide exactly one of --old-apk or --old-archive; historical input cannot be synthesized from current upstream sources')
    if args.old_archive:
        assert file_sha(args.old_archive) == OLD_ARCHIVE_SHA, 'OLD_212_ARCHIVE_IDENTITY'
    if args.old_apk:
        assert file_sha(args.old_apk) == OLD_APK_SHA, 'OLD_212_APK_IDENTITY'
    assets = ROOT / 'app/src/main/assets'
    manifest = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))
    version = manifest['dependencies']['@deepseek-ai/dsh']
    assert version == '0.2.2-rc.5', 'PATCH_CHAIN_REQUIRES_REVIEWED_0_2_2_RC5'
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    chains, _ = runtime_stable_patches.load_chains(assets, version)
    registered = {name for rows in chains.values() for name, _, _ in rows}
    assert NAMES <= registered, 'BUILD193_RECIPES_MUST_BE_REGISTERED'
    selected = os.environ.get('DSHA_TEST_RUNTIME')
    if selected is None:
        selected = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))['raw']
    raw = Path(selected) / 'node_modules'
    raw_package = json.loads((raw / '@deepseek-ai/dsh/package.json').read_text(encoding='utf8'))
    assert raw_package['name'] == '@deepseek-ai/dsh' and raw_package['version'] == version, 'RAW_RUNTIME_GENERATION'
    assert sha((Path(selected) / 'package-lock.json').read_bytes()) == sha((ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()), 'RAW_RUNTIME_LOCK_IDENTITY'
    if args.old_apk:
        with zipfile.ZipFile(args.old_apk) as package, package.open('assets/dsh-runtime.bin') as source:
            assert stream_sha(source) == OLD_ARCHIVE_SHA, 'OLD_212_ARCHIVE_IDENTITY'
    old_names = {module: OLD_FRONTEND if module.startswith('@deepseek-ai/dsh-web-frontend/dist/assets/index-')
                 else module for module in chains}
    old_wanted = set(old_names.values())
    with (archive(args.old_apk) if args.old_apk else tarfile.open(args.old_archive, 'r|*')) as historic:
        old_payloads = module_payloads(historic, old_wanted)
    assert old_wanted - old_payloads.keys() == OLD_UNSHIPPED_MODULES, 'PINNED_212_MODULE_SET'
    new_payloads = {}
    if args.apk:
        with archive(args.apk) as packaged:
            new_payloads = module_payloads(packaged, set(chains))
        assert set(chains) == new_payloads.keys(), 'APK_ALL_CURRENT_PATCH_TARGETS_REQUIRED'
    owned = Path(tempfile.mkdtemp(prefix='build193-java-chain-', dir=ROOT / 'app/build'))
    builder_spec = importlib.util.spec_from_file_location('build193_archive_builder', ROOT / 'tools/build-dsh-runtime.py')
    builder = importlib.util.module_from_spec(builder_spec)
    builder_spec.loader.exec_module(builder)
    names, records = [], []
    for module, chain in chains.items():
        directory = owned / str(len(names))
        names.append(directory.name)
        directory.mkdir()
        canonical = (raw / module).read_bytes()
        for name, _, _ in chain:
            recipe = json.loads((assets / name).read_text(encoding='utf8'))
            if 'sourceSha256' in recipe:
                assert sha(canonical) == recipe['sourceSha256'], 'UNKNOWN_OFFICIAL_SOURCE: ' + name
            if 'packageIntegrity' in recipe:
                package_parts = module.split('/')[:2 if module.startswith('@') else 1]
                package_name = 'node_modules/' + '/'.join(package_parts)
                assert recipe['packageIntegrity'] == lock['packages'][package_name]['integrity'], 'RECIPE_PACKAGE_INTEGRITY: ' + name
        prepared = builder.patched_content(Path(module), canonical)
        expected = runtime_stable_patches.apply(prepared, chain)
        old_module = old_names[module]
        old_bytes = old_payloads.get(old_module)
        predecessor_problem = None
        direct_digest = None
        if old_bytes is None:
            direct_status = 'historical-path-absent'
            (directory / 'historical-module-unavailable.txt').write_text(old_module, encoding='utf8')
        else:
            (directory / 'old-192.js').write_bytes(old_bytes)
            if module in OLD_MODULE_SHA:
                assert sha(old_bytes) == OLD_MODULE_SHA[module], 'PINNED_212_MODULE_IDENTITY'
            direct_status = 'canonical-rebuild-on-anchor-mismatch'
            try:
                direct = runtime_stable_patches.apply(old_bytes, chain)
            except ValueError as failure:
                assert 'STABLE_PATCH_ANCHOR' in str(failure), 'UNEXPECTED_OLD_CHAIN_ERROR'
            else:
                (directory / 'old-direct.js').write_bytes(direct)
                direct_digest = sha(direct)
                direct_status = 'complete-current-bytes' if direct == expected else 'old-markers-retain-old-upstream-bytes'
            # The signed new archive is always the official generation transition.
            # A successful marker-only overlay is never accepted as that transition.
            # Signed predecessor forms belong to the generation that introduced the
            # recipe; when the pinned old input already contains the replacement
            # form (e.g. the rc3 language receipt), the control is not applicable.
            old_text = old_bytes.decode('utf8')
            for name, patches, _ in chain:
                for before, after in getattr(patches, 'previous', ()):
                    if old_text.count(after) != 1:
                        predecessor_problem = name + ': predecessor form absent from pinned old input (superseded generation)'
                        break
                if predecessor_problem:
                    break
            if predecessor_problem:
                (directory / 'predecessor-unavailable.txt').write_bytes(predecessor_problem.encode())
        for filename, data in [('canonical.js', prepared), ('expected.js', expected), ('name.txt', module.encode())]:
            (directory / filename).write_bytes(data)
        write_chain(directory / 'chain', chain)
        if args.apk:
            (directory / 'apk.js').write_bytes(new_payloads[module])
        records.append(dict(module=module, sourceSha256=sha(canonical), old192Module=old_module,
                            old192Sha256=sha(old_bytes) if old_bytes is not None else None, currentSha256=sha(expected),
                            oldDirectStatus=direct_status, oldDirectSha256=direct_digest,
                            generationUpgrade='stage-signed-new-runtime-with-ManagedRuntimeTransaction-and-RuntimeTrial',
                            recipes=[name for name, _, _ in chain],
                            previousPatchCounts={name: len(getattr(patches, 'previous', ())) for name, patches, _ in chain},
                            predecessorControl='not-applicable' if predecessor_problem or old_bytes is None else 'executed'))
    assert len(names) == len(chains) and len(names) >= 30, 'ALL_CURRENT_ACTIVE_MODULES_REQUIRED'
    (owned / 'modules.txt').write_bytes('\n'.join(names).encode())
    (owned / 'Build193PatchChainFixture.java').write_bytes(JAVA.encode())
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    assert javac and java, 'JDK17_REQUIRED'
    production = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
    classes = owned / 'classes'
    classes.mkdir()
    command = [javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes), str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'), str(ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'), str(owned / 'Build193PatchChainFixture.java')]
    compiled = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'JAVA_COMPILE_FAILED: ' + str(owned)
    java_log = owned / 'java.log'
    with java_log.open('wb') as stream:
        ran = subprocess.run([java, '-cp', str(classes), 'Build193PatchChainFixture', str(owned)],
                             stdout=stream, stderr=subprocess.STDOUT, timeout=60)
    result = dict(status='PASS' if ran.returncode == 0 else 'FAIL', exitCode=ran.returncode, modules=records,
                  old192ApkSha256=OLD_APK_SHA if args.old_apk else None,
                  old192ArchiveSha256=OLD_ARCHIVE_SHA if args.old_archive else None,
                  historicalInput='actual build212 APK' if args.old_apk else 'actual build212 archive',
                  currentDshVersion=version, activeModuleCount=len(names),
                  historicalAbsentTargets=sorted(OLD_UNSHIPPED_MODULES),
                  predecessorPartialVariants=sum(sum(record['previousPatchCounts'].values()) for record in records
                                                 if record['predecessorControl'] == 'executed'),
                  predecessorNegativeControls=['each incomplete predecessor requires canonical repair', 'duplicate predecessor rejected', 'omitted predecessor metadata rejected'],
                  exactNewApkValidation=args.apk is not None,
                  generationBoundary='old package generation receives no in-place current overlay; signed new archive installation/trial is required',
                  apkSha256=file_sha(args.apk) if args.apk else None,
                  productionAlgorithmSha256={name: sha((production / name).read_bytes()) for name in ['ManagedPatchChain.java', 'ExactTextPatch.java']},
                  deviceValidation=False, output=str(owned))
    (owned / 'result.json').write_bytes((json.dumps(result, indent=2) + '\n').encode())
    print(json.dumps(result))
    if ran.returncode:
        print(java_log.read_text(encoding='utf8', errors='replace'))
        raise SystemExit(ran.returncode)


if __name__ == '__main__':
    main()
