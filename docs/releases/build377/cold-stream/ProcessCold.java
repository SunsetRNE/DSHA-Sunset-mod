package org.dsha.fixture.exactcold;
import android.app.*;import android.content.*;import android.os.*;import dalvik.system.PathClassLoader;
import java.io.*;import java.lang.reflect.*;import java.util.function.Consumer;import java.lang.Process;
public final class ProcessCold extends Instrumentation {
 static String digest(File file)throws Exception{java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder x=new StringBuilder();for(byte v:d.digest())x.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return x.toString();}
 public void onCreate(Bundle a){super.onCreate(a);start();}
 public void onStart(){Bundle r=new Bundle();try{
  Context c=getContext();String apk=c.getPackageManager().getApplicationInfo("dsh.android.client",0).sourceDir;
  String product=digest(new File(apk));if(!product.equals("2e6f0324ebe4b52b143dcb2bf04800b1ce73c106a53b567de1afdafca5dba3d9"))throw new IOException("wrong installed baseline");
  boolean overlay=c.getPackageName().contains("ackoverlay");
  r.putString("production_apk",product);r.putInt("uid",android.os.Process.myUid());
  r.putString("source_overlay",Boolean.toString(overlay));r.putString("launcher_sha256",digest(new File(c.getApplicationInfo().nativeLibraryDir,"libdsha-session.so")));
  ClassLoader cl=new PathClassLoader(overlay?c.getApplicationInfo().sourceDir+File.pathSeparator+apk:apk,c.getApplicationInfo().nativeLibraryDir,ClassLoader.getSystemClassLoader().getParent());
  Class<?> boot=cl.loadClass("com.deepseekharness.app.runtime.IsolatedInstallProcess"), op=cl.loadClass("com.deepseekharness.app.runtime.BoundedGuestSessions$Operation");
  Method begin=cl.loadClass("com.deepseekharness.app.runtime.BoundedGuestSessions").getMethod("begin",File.class);
  Method launch=boot.getDeclaredMethod("start",ProcessBuilder.class,File.class,Context.class,op);launch.setAccessible(true);
  Method collect=cl.loadClass("com.deepseekharness.app.util.BoundedProcessRunner").getMethod("collect",Process.class,long.class,int.class,Consumer.class);
  Method close=boot.getDeclaredMethod("close");close.setAccessible(true);
  for(int code:new int[]{0,137}){
    if(overlay)cl.loadClass("com.deepseekharness.app.util.UiText").getMethod("setLanguage",String.class).invoke(null,code==0?"zh":"en");
    String phase="begin";Process p=null;try{
      Object record=begin.invoke(null,c.getFilesDir());phase="start";
      p=(Process)launch.invoke(null,new ProcessBuilder("/system/bin/sh","-c","echo SYNTHETIC; exit "+code).redirectErrorStream(true),c.getCacheDir(),c,record);
      phase="collect";Object result=collect.invoke(null,p,3000L,262144,(Consumer<Process>)Process::destroy);
      int seen=result.getClass().getField("exitCode").getInt(result);String output=(String)result.getClass().getField("output").get(result);r.putString("case"+code,"returned "+seen+" "+output.trim());
    }catch(Throwable e){r.putString("case"+code,phase+" "+android.util.Log.getStackTraceString(e));}
    finally{if(p!=null)try{close.invoke(p);r.putString("close"+code,"verified");}catch(Throwable e){r.putString("close"+code,android.util.Log.getStackTraceString(e));}}
  }
  String exe=new File(c.getApplicationInfo().nativeLibraryDir,"libdsha-session.so").getAbsolutePath();
  for(String mode:new String[]{"signed-arm64","system-shell"}){
    File marker=new File(c.getCacheDir(),"entered-"+mode+"-"+java.util.UUID.randomUUID());
    File done=new File(marker+".after");
    String body="echo enter > '"+marker.getAbsolutePath()+"'; echo SHELL_ENTER; IFS= read -r word || { echo READ_EOF; exit 125; }; [ \"$word\" = DSHA_START ] || exit 125; echo ok > '"+done.getAbsolutePath()+"'; echo MARK_$word; exit 0";
    Process raw=new ProcessBuilder(mode.equals("signed-arm64")?new String[]{exe,"--birth-handshake","/system/bin/sh","-c",body}:new String[]{"/system/bin/sh","-c",body}).redirectErrorStream(true).start();
    String write="flushed";Thread.sleep(100);boolean enteredBefore=marker.exists(),afterBefore=done.exists();
    try{raw.getOutputStream().write("DSHA_START\n".getBytes("UTF-8"));raw.getOutputStream().flush();}catch(IOException failed){write=failed.getClass().getSimpleName()+":"+failed.getMessage();}
    long t=android.os.SystemClock.elapsedRealtime();boolean exited=raw.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);long wait=android.os.SystemClock.elapsedRealtime()-t;
    if(!exited){raw.destroyForcibly();raw.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);}
    StringBuilder lines=new StringBuilder();try(BufferedReader rd=new BufferedReader(new InputStreamReader(raw.getInputStream()))){String l;while((l=rd.readLine())!=null){lines.append(l.startsWith("DSHA_SESSION_STAT_V1 ")?"birth-line":l).append("|");}}
    r.putString(mode,"stdin="+write+" natural_wait="+exited+" waited_ms="+wait+" harness_force="+(!exited)+" exit="+raw.exitValue()+" shell_marker="+marker.exists()+" shell_entered_before_write="+enteredBefore+" guest_marker_before_write="+afterBefore+" guest_marker_after_write="+done.exists()+" diagnostics="+lines);
  }
  finish(Activity.RESULT_OK,r);
 }catch(Throwable f){r.putString("error",android.util.Log.getStackTraceString(f));finish(Activity.RESULT_CANCELED,r);}}
}
