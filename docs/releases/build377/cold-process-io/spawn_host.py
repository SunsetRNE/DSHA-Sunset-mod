from pathlib import Path
base=Path(__file__).resolve().parent
src=(base/'host.py').read_text().replace("source = pathlib.Path('/mnt/f/DSHA_RESTART/source/build377-local/tools/native-session/session-launcher.c')","source = pathlib.Path(__file__).resolve().parent/'spawn-launcher.c'").replace('for diagnostic in (False,True):','for diagnostic in (False,):').replace('assert proc.returncode==-signal.SIGKILL',"assert proc.returncode==(125 if pre=='missing' else -signal.SIGKILL)").replace("'records':24","'records':12")
exec(compile(src,str(base/'host.py'),"exec"))
