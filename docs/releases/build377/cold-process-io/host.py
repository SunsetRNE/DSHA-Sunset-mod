"""Local x86_64/UID 65534 controls for session startup. No device/ADB, guest root or user data."""
import hashlib, json, os, pathlib, select, shlex, signal, subprocess, sys, tempfile, time
assert os.geteuid() != 0
source = pathlib.Path('/mnt/f/DSHA_RESTART/source/build377-local/tools/native-session/session-launcher.c')
original = source.read_bytes()
def snapshot(pid):
    text=pathlib.Path('/proc',str(pid),'stat').read_text()
    tail=text[text.rindex(')')+2:].split()
    return (int(tail[1]),int(tail[2]),int(tail[3]),int(tail[19]))
def run_case(exe,pre,code,i,diag,root):
    status=root/f'status-{pre}-{code}-{i}-{diag}'
    if pre=='missing':
        command=[str(root/'absent-helper')]
    elif pre=='early':
        command=['/bin/sh','-c',f'exit {code}']
    else:
        command=['/bin/sh','-c','IFS= read -r ok || exit 125; [ "$ok" = DSHA_START ] || exit 125; printf "GUEST_SEEN\\n"; printf "%s\\n" "$1" > "$2"; kill -STOP $$; exit 125','fixture',str(code),str(status)]
    started=time.monotonic_ns()
    proc=subprocess.Popen([str(exe),'--birth-handshake',*command],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    assert select.select([proc.stdout],[],[],3)[0]
    announce=proc.stdout.readline().decode().strip()
    assert announce.startswith('DSHA_SESSION_STAT_V1 ')
    tail=announce[announce.rindex(')')+2:].split()
    ident=(int(tail[1]),int(tail[2]),int(tail[3]),int(tail[19]))
    assert ident[:3]==(os.getpid(),proc.pid,proc.pid)
    birth_ms=(time.monotonic_ns()-started)/1e6
    time.sleep(.04)
    before=proc.poll()
    if pre=='normal':
        assert before is None and snapshot(proc.pid)==ident
        assert not status.exists()
    err=None
    try:
        proc.stdin.write(b'DSHA_START\n')
        proc.stdin.flush()
    except BrokenPipeError:
        err='BrokenPipeError'
    try:proc.stdin.close()
    except BrokenPipeError:pass
    try:proc.stdin.close()
    except BrokenPipeError:pass
    stage_exit=None
    if pre=='normal':
        deadline=time.monotonic()+2
        while not status.exists() and time.monotonic()<deadline:time.sleep(.005)
        assert status.exists() and status.read_text().strip()==str(code)
        stage_exit=int(status.read_text())
    try:
        proc.wait(timeout=.08)
        ended_before_cleanup=True
    except subprocess.TimeoutExpired:
        ended_before_cleanup=False
    cleanup_signal=None
    if not ended_before_cleanup:
        assert snapshot(proc.pid)==ident
        proc.send_signal(signal.SIGTERM);cleanup_signal='SIGTERM-to-exact-leader'
        proc.wait(timeout=3)
    rest=proc.stdout.read().decode().strip()
    error=proc.stderr.read().decode().strip()
    if pre=='normal':assert rest=='GUEST_SEEN' and not ended_before_cleanup and err is None
    else:assert ended_before_cleanup and err=='BrokenPipeError' and stage_exit is None
    assert proc.returncode==-signal.SIGKILL
    print(json.dumps(dict(case=pre,input_code=code,round=i,diagnostic_variant=diag,uid=os.geteuid(),birth_ms=round(birth_ms,3),alive_preack=before is None,write_error=err,foreground_status=stage_exit,exited_before_own_cleanup=ended_before_cleanup,own_cleanup=cleanup_signal,leader_wait=proc.returncode,stderr=error,elapsed_ms=round((time.monotonic_ns()-started)/1e6,3))),flush=True)
with tempfile.TemporaryDirectory(prefix='dsha-377-gate-') as folder:
    root=pathlib.Path(folder)
    for diagnostic in (False,True):
        src=source
        if diagnostic:
            mark='    /* An unexpected shell exit'
            data=original.decode()
            assert data.count(mark)==1
            src=root/'observe.c'
            src.write_text(data.replace(mark,'    dprintf(2, "FIXTURE_CHILD_WAIT exited=%d status=%d signaled=%d signal=%d\\n", WIFEXITED(status), WIFEXITED(status)?WEXITSTATUS(status):-1, WIFSIGNALED(status), WIFSIGNALED(status)?WTERMSIG(status):-1);\n'+mark))
        exe=root/('probe'+str(diagnostic))
        subprocess.run(['cc','-std=c11','-D_GNU_SOURCE','-O2','-fPIE','-pie','-o',str(exe),str(src)],check=True)
        for i in range(1,4):
            for pre,code in [('normal',0),('normal',137),('early',23),('missing',127)]:
                run_case(exe,pre,code,i,diagnostic,root)
print(json.dumps({'records':24,'sourceSha256':hashlib.sha256(original).hexdigest(),'scope':'Linux x86_64, same native source, synthetic process status and stream lifecycle only'}))
