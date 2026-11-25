package org.dsha.fixture.exactcold;
import android.app.*;
import android.content.*;
import android.os.*;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.json.*;

public final class ExactCold extends Instrumentation {
  final Bundle result=new Bundle();
  final long start=SystemClock.elapsedRealtime();
  @Override public void onCreate(Bundle args){super.onCreate(args);start();}
  static String sha(InputStream in)throws Exception {
    try(in){MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)md.update(b,0,n);StringBuilder x=new StringBuilder();for(byte v:md.digest())x.append(String.format(Locale.ROOT,"%02x",v&255));return x.toString();}
  }
  void stamp(String key){result.putLong(key,SystemClock.elapsedRealtime()-start);}
  static void tree(File node,long[] size)throws Exception {
    android.system.StructStat st=android.system.Os.lstat(node.getAbsolutePath());
    size[1]+=st.st_blocks*512L;size[2]++;
    if(android.system.OsConstants.S_ISREG(st.st_mode)){size[0]+=st.st_size;return;}
    if(android.system.OsConstants.S_ISDIR(st.st_mode)){File[] entries=node.listFiles();if(entries==null)throw new IOException("cannot enumerate fixture tree");for(File f:entries)tree(f,size);}
  }
  @Override public void onStart(){try{
    Context probe=getContext(),metadata=probe.createPackageContext("dsh.android.client",0);
    android.content.pm.ApplicationInfo info=metadata.getApplicationInfo();
    String apk=sha(new FileInputStream(info.sourceDir));result.putString("apk",apk);
    if(!apk.equals("2e6f0324ebe4b52b143dcb2bf04800b1ce73c106a53b567de1afdafca5dba3d9"))throw new IOException("wrong signed package");
    result.putInt("probe_uid",android.os.Process.myUid());result.putInt("target_uid",info.uid);
    JSONObject manifest;
    try(InputStream in=metadata.getAssets().open("ubuntu-tools.manifest.json");BufferedReader r=new BufferedReader(new InputStreamReader(in))){
      StringBuilder json=new StringBuilder();String l;while((l=r.readLine())!=null)json.append(l);manifest=new JSONObject(json.toString());
    }
    String hash=manifest.getString("archiveSha256");
    ClassLoader cl=new PathClassLoader(info.sourceDir,probe.getApplicationInfo().nativeLibraryDir,ClassLoader.getSystemClassLoader().getParent());
    Method decode=cl.loadClass("com.deepseekharness.app.util.BundledRootfsAsset").getMethod("decode",InputStream.class,String.class);
    Method extract=cl.loadClass("com.deepseekharness.app.runtime.TarGzipExtractor").getMethod("extractAuto",InputStream.class,File.class,int.class);
    try(InputStream raw=metadata.getAssets().open("ubuntu-tools.bin");InputStream decoded=(InputStream)decode.invoke(null,raw,hash)){
      result.putString("input",raw.getClass().getName());byte[] b=new byte[262144];long size=0;int n;while((n=decoded.read(b))!=-1)size+=n;result.putLong("tar_bytes",size);
      if(decoded.read()!=-1)throw new IOException("unchecked EOF");
      stamp("first_verified_EOF_ms");
    }
    File root=new File(probe.getCacheDir(),"overlay-"+UUID.randomUUID());
    if(!root.mkdir())throw new IOException("fresh isolated root");
    result.putLong("volume_available_before",new StatFs(root.getPath()).getAvailableBytes());
    try(InputStream raw=metadata.getAssets().open("ubuntu-tools.bin");InputStream decoded=(InputStream)decode.invoke(null,raw,hash)){
      extract.invoke(null,decoded,root,0);
    }
    stamp("full_extract_and_close_ms");
    result.putString("status",sha(new FileInputStream(new File(root,"var/lib/dpkg/status"))));
    String lock=new BufferedReader(new FileReader(new File(root,"root/.dsha-ubuntu-tools-version"))).readLine();
    result.putString("lock",lock);
    if(!result.getString("status").equals(manifest.getString("statusSha256"))||!lock.equals(manifest.getString("packageLockSha256")))throw new IOException("postconditions");
    stamp("complete_ms");long[] size={0,0,0};tree(root,size);result.putLong("regular_file_logical_bytes",size[0]);result.putLong("tree_allocated_bytes_including_directories_and_links",size[1]);result.putLong("tree_nodes_including_synthesized_directories",size[2]);result.putLong("volume_available_after",new StatFs(root.getPath()).getAvailableBytes());finish(Activity.RESULT_OK,result);
  }catch(Throwable failure){result.putString("error",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}}
}
