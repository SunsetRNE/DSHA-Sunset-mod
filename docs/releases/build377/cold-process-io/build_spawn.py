"""Independent synthetic launcher experiment. Only artifacts are written."""
from pathlib import Path
import hashlib,json,subprocess
root=Path('F:/DSHA_RESTART')
ev=Path(__file__).resolve().parent
src=root/'source/build377-local/tools/native-session/session-launcher.c'
original=src.read_text(encoding='utf8')
needle='    pid_t child = fork();'
offset=original.index(needle)
end=original.index('    int status;',offset)
spawnblock="""    // The kernel restores SIGTERM in the child before exec. The supervisor keeps
    // its own parent-death handler and owns the same session as before.
    posix_spawnattr_t attrs;
    sigset_t defaults;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGTERM);
    int error = posix_spawnattr_init(&attrs);
    if (error != 0) { errno = error; perror("DSHA_SESSION_SPAWN_ATTR"); return 125; }
    error = posix_spawnattr_setsigdefault(&attrs, &defaults);
    if (error == 0) error = posix_spawnattr_setflags(&attrs, POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_USEVFORK);
    pid_t child = -1;
    if (error == 0) error = posix_spawnp(&child, argv[command], NULL, &attrs, &argv[command], environ);
    posix_spawnattr_destroy(&attrs);
    if (error != 0) { errno = error; perror("DSHA_SESSION_SPAWN"); return 125; }

"""
candidate=original[:offset]+spawnblock+original[end:]
candidate=candidate.replace('#include <signal.h>','#include <signal.h>\n#include <spawn.h>')
dst=ev/'spawn-launcher.c';dst.write_bytes(candidate.encode('utf8'))
tools=root/'toolchains/current/android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
result={'source':str(dst),'sourceSha256':digest(dst),'oldSourceSha256':digest(src),'variants':{}}
for arch in ['aarch64','x86_64']:
 path=ev/arch/'libdsha-session.so';path.parent.mkdir(exist_ok=True)
 args=[str(tools/'clang.exe'),'--target='+arch+'-linux-android28','-std=c11','-D_GNU_SOURCE','-O2','-fPIE','-pie','-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-ffile-prefix-map='+str(ev)+'=/src','-Wl,-z,relro','-Wl,-z,now','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=4096',str(dst),'-o',str(path)]
 subprocess.run(args,check=True)
 subprocess.run([str(tools/'llvm-strip.exe'),'--strip-unneeded',str(path)],check=True)
 result['variants'][arch]={'path':str(path),'bytes':path.stat().st_size,'sha256':digest(path),'command':args}
(ev/'spawn-build.json').write_text(json.dumps(result,indent=2),encoding='utf8')
for k,v in result['variants'].items():print(k,v['sha256'],v['bytes'])
print('source',result['sourceSha256'])
