"""Self-owned child only; verify C's existing parent-death contract before and after changing spawn."""
import json,os,pathlib,subprocess,sys,tempfile,time
def stat(pid):
 try:
  s=pathlib.Path('/proc',str(pid),'stat').read_text(); f=s[s.rindex(')')+2:].split()
  return (f[0],int(f[1]),int(f[2]),int(f[3]),int(f[19]))
 except FileNotFoundError:return None
def bounded(condition):
 start=time.monotonic()
 while not condition():
  if time.monotonic()-start>3:raise RuntimeError("fixture deadline")
  time.sleep(.005)
if len(sys.argv)>1:
 # Exact child supervisor gets the parent-death SIGTERM when this sub-interpreter exits.
 exe,rootpath,phase=sys.argv[1:]
 root=pathlib.Path(rootpath)
 shell='IFS= read -r ack || exit 125; [ "$ack" = DSHA_START ] || exit 125; echo "$$" > "$1"; kill -STOP $$; exit 125'
 sub=subprocess.Popen([exe,'--birth-handshake','/bin/sh','-c',shell,'fixture',str(root/'child')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 assert sub.stdout.readline().startswith(b'DSHA_SESSION_STAT_V1 ')
 s=stat(sub.pid);assert s[1:4]==(os.getpid(),sub.pid,sub.pid)
 if phase=='after':
  sub.stdin.write(b'DSHA_START\n');sub.stdin.flush()
  bounded(lambda:(root/'child').exists() and (root/'child').read_text().strip()!='')
  child=int((root/'child').read_text().strip())
  bounded(lambda:(stat(child) or ('',))[0]=='T')
 else:
  kids=pathlib.Path(f'/proc/{sub.pid}/task/{sub.pid}/children')
  bounded(lambda:bool(kids.read_text().strip()))
  child=int(kids.read_text().strip())
 c=stat(child);assert c[1:4]==(sub.pid,sub.pid,sub.pid)
 # Keep owner alive until external observer records the exact identities of supervisor and child.
 (root/'ready').write_text(json.dumps([sub.pid,s[-1],child,c[-1]]))
 bounded(lambda:(root/'finish').exists())
 os._exit(0)
assert os.geteuid()!=0
ev=pathlib.Path(__file__).resolve().parent
old=pathlib.Path('/mnt/f/DSHA_RESTART/source/build377-local/tools/native-session/session-launcher.c')
for label,source in [('fork',old),('spawn',ev/'spawn-launcher.c')]:
 with tempfile.TemporaryDirectory(prefix='dsha-parent-exit-') as temp:
  base=pathlib.Path(temp);exe=base/'supervisor'
  subprocess.run(['cc','-D_GNU_SOURCE','-O2',str(source),'-o',str(exe)],check=True)
  for phase in ('before','after'):
   for index in range(2):
    root=base/(phase+str(index));root.mkdir()
    p=subprocess.Popen([sys.executable,__file__,str(exe),str(root),phase])
    bounded(lambda:(root/'ready').exists() and (root/'ready').stat().st_size>0)
    pid,birth,child,cBirth=json.loads((root/'ready').read_text())
    s,c=stat(pid),stat(child)
    assert s[1:]==(p.pid,pid,pid,birth) and c[1:]==(pid,pid,pid,cBirth)
    assert (root/'child').exists()==(phase=='after')
    (root/'finish').touch()
    assert p.wait(timeout=3)==0
    def gone_or_dead(n,expected):
     row=stat(n)
     return row is None or row[-1]!=expected or row[0] in ['Z','X']
    bounded(lambda:gone_or_dead(pid,birth) and gone_or_dead(child,cBirth))
    assert (root/'child').exists()==(phase=='after')
    print(json.dumps(dict(api=label,owner_uid=os.geteuid(),round=index+1,phase=phase,owner_exited=True,leader_and_child_nonexecuting=True,guest_marker=(root/'child').exists(),external_signal=False)),flush=True)
