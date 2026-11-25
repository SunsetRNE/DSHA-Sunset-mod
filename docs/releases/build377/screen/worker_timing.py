"""Run the production capture loop with a scripted package source, clock and image queue."""
from pathlib import Path
import sys
import datetime,json,subprocess,time
E=Path(__file__).resolve().parent
W=E.parents[2]/'source/build377-local'
src='app/src/main/java/com/deepseekharness/app/vscreen/PhysicalScreenDisplaySession.java'
javabin=Path('F:/DSHA/_toolchains/jdk-17/bin')
now=lambda:datetime.datetime.now().astimezone().isoformat(timespec='milliseconds')
for mode in ('before','after'):
 start=time.perf_counter()
 out=E/('frame-queue-'+mode+('-'+sys.argv[1] if len(sys.argv)>1 else ''))
 out.mkdir(exist_ok=True)
 code=(subprocess.check_output(['git','show','12efafd7:'+src],cwd=W).decode() if mode=='before' else (W/src).read_text(encoding='utf-8'))
 begin=code.find('  static final class FrameQueueOwner')
 if begin<0: begin=code.index('  private void startCapture(')
 loop=code[begin:code.index('  private void discardFrameLocked()',begin)]
 pre="""
import java.util.*;
import java.util.concurrent.*;
public class FrameLoop {
  static int poll = 0; static final CountDownLatch done=new CountDownLatch(1);
  static final class SystemClock { static long now; static long elapsedRealtime(){return now+=200;} }
  static final class PhysicalScreenCore { static String currentPackage(){return poll==0?"first":"second";} }
  class Image { int row; Image(int row){this.row=row;} void close(){System.out.println("closed row="+row);} }
  class Bitmap { int row; Bitmap(int row){this.row=row;} boolean sameAs(Bitmap other){return other.row==row;} void recycle(){} }
  class Reader {
    Image acquireLatestImage(){
      if(poll>=5){active=false;done.countDown();return null;}
      int row=poll++;
      System.out.println("read row="+row+" cachePresent="+(frame!=null));
      if(row==1) throw new IllegalStateException("scripted read exception");
      if(row==2) return null;
      return new Image(row);
    }
  }
  Reader reader=new Reader(); final Object lock=new Object();
  static class Config {int maxFps(){return 30;}}
  Config config=new Config(); volatile boolean active=true;
  long lastAttemptAt, encodedSequence, sequence, capturedAt; int captureWidth=8,captureHeight=6;
  String framePackage="",encoded=""; boolean secureOrBlack; Bitmap frame;
  final List<Integer> copied=new ArrayList<>();
  Bitmap copy(Image input,int w,int h){copied.add(input.row);System.out.println("copy row="+input.row);return new Bitmap(input.row);}
  boolean nearBlack(Bitmap unused){return false;}
  void discardFrameLocked(){frame=null;framePackage="";}
"""
 tail="""
  public static void main(String[] args) throws Exception {
    FrameLoop loop=new FrameLoop(); loop.startCapture("first");
    if(!done.await(3,TimeUnit.SECONDS))throw new AssertionError("timeout");
    System.out.println("copiedRows="+loop.copied);
    List<Integer> expected=args[0].equals("before")?List.of(0,3,4):List.of(0,4);
    if(!loop.copied.equals(expected))throw new AssertionError("unexpected copied rows");
  }
}
"""
 # The callback polls the owner twice. Hold it stable for the entire polling iteration,
 # including the verification call after reading the frame.
 pre=pre.replace('return poll==0?"first":"second";','return poll<=1 && !finishedFirst?"first":"second";').replace('static int poll = 0;','static int poll = 0; static boolean finishedFirst;').replace('void close(){System.out.println("closed row="+row);}','void close(){if(row==0) finishedFirst=true;System.out.println("closed row="+row);}')
 (out/'FrameLoop.java').write_text(pre+loop+tail,encoding='utf-8')
 info={'start':now(),'mode':mode,'shape':'five polls: first pixels, switched owner reader exception, empty poll, stale queue image, fresh image','commands':[]}
 for tool,args,label in ((javabin/'javac',['-encoding','UTF-8','FrameLoop.java'],'compile'),(javabin/'java',['-cp','.','FrameLoop',mode],'run')):
  proc=subprocess.Popen([str(tool),*args],cwd=out,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  lines=[]
  for line in proc.stdout:
   lines.append(line)
   if line.startswith(b'read row=1') and 'firstResult' not in info:
    info['firstResult']={'at':now(),'elapsedSeconds':round(time.perf_counter()-start,4),'record':line.decode().strip()}
  proc.wait()
  output=b''.join(lines)
  (out/(label+'.log')).write_bytes(output)
  info['commands'].append({'cwd':str(out),'argv':[str(tool),*args],'exit':proc.returncode,'finished':now(),'elapsedSeconds':round(time.perf_counter()-start,4)})
  if proc.returncode: break
 (out/'metadata.json').write_text(json.dumps(info,indent=2),encoding='utf-8')
 print(mode,proc.returncode,output.decode(errors='replace')[-500:])
 if proc.returncode:raise SystemExit(proc.returncode)
