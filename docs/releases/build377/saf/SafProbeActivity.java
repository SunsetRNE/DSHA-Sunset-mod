package org.dsha.fixture.saf;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.Os;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.transfer.SelectedDocumentSave;
import com.deepseekharness.app.util.FileIntegrity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class SafProbeActivity extends Activity {
  private final JSONObject log = new JSONObject();
  private final long begin = SystemClock.elapsedRealtime();
  private String name;
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    name = getIntent().getStringExtra("name");
    if (name == null || !name.matches("saf377-[a-f0-9]{12}\\.bin")) { finish(); return; }
    if (saved == null) {
      Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
          .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,name)
          .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri(
              "com.android.externalstorage.documents","primary:Download"));
      startActivityForResult(picker,377);
    }
  }
  @Override protected void onActivityResult(int request,int result,Intent intent) {
    super.onActivityResult(request,result,intent);
    if (request != 377 || result != RESULT_OK || intent == null) { fail("picker-cancelled"); return; }
    new Thread(() -> { try { run(intent.getData()); } catch(Throwable failure) {
      StringWriter stack = new StringWriter();
      failure.printStackTrace(new PrintWriter(stack)); fail(stack.toString());
    } finally {runOnUiThread(this::finish);}}, "saf377-test").start();
  }
  private static void check(boolean ok, String point) throws IOException { if (!ok) throw new IOException(point); }
  private void fail(String detail) { try { log.put("failure",detail); save(); } catch(Exception ignored) {} }
  private void save() throws Exception {
    try(FileOutputStream file=openFileOutput(name+".json",MODE_PRIVATE)) {
      file.write(log.toString(2).getBytes(StandardCharsets.UTF_8)); file.getFD().sync();
    }
  }
  private void run(Uri uri) throws Exception {
    String authority=uri.getAuthority();
    check(DocumentsContract.isDocumentUri(this,uri) && Set.of("com.android.externalstorage.documents","com.android.providers.downloads.documents").contains(authority),"not system DocumentsProvider");
    try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
      check(c!=null && c.moveToFirst() && name.equals(c.getString(0)) && !c.isNull(1) && c.getLong(1)==0,"not our new synthetic file");
    }
    log.put("provider",authority).put("providerPackage",getPackageManager().resolveContentProvider(authority,0).packageName);
    log.put("sdk",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("initial",stat(uri));
    byte[] small=new byte[257], full=new byte[131073], replacement=new byte[65537];
    new Random(377).nextBytes(small); new Random(378).nextBytes(full);new Random(379).nextBytes(replacement);
    write(uri,small);
    check(sha(small).equals(read(uri)),"new document hash");
    log.put("firstUsableMs",SystemClock.elapsedRealtime()-begin).put("firstSaved",stat(uri)).put("firstSha",sha(small));save();
    write(uri,full); check(sha(full).equals(read(uri)),"replace hash");
    log.put("replacement",stat(uri)).put("verifiedSha",sha(full));
    final int[] opens={0};
    try {
      SelectedDocumentSave.write(this,uri,()->{
        if (++opens[0]!=1) return new ByteArrayInputStream(replacement);
        return new InputStream() {
          boolean first=true;
          public int read() throws IOException {throw new IOException("fixture-stream-failure");}
          public int read(byte[] out,int off,int len) throws IOException {
            if (!first) throw new IOException("fixture-stream-failure");
            first=false;System.arraycopy(replacement,0,out,off,17);return 17;
          }
        };
      },replacement.length,sha(replacement),new BackupControl(null));
      throw new AssertionError("failed source reported success");
    } catch(IOException expected) { check("fixture-stream-failure".equals(expected.getMessage()),"unexpected failure: "+expected); }
    String restored=read(uri);
    check(sha(full).equals(restored),"original not restored");
    log.put("afterFault",stat(uri)).put("afterFaultSha",restored).put("injectedBytes",17).put("recoveryFiles",new File(getFilesDir(),"save-recovery").list().length);
    check(log.getInt("recoveryFiles")==0,"verified rollback left a redundant file");
    log.put("completeMs",SystemClock.elapsedRealtime()-begin).put("passed",true);save();
  }
  private JSONObject stat(Uri uri) throws Exception {
    try(ParcelFileDescriptor p=getContentResolver().openFileDescriptor(uri,"r")) {
      android.system.StructStat st=Os.fstat(p.getFileDescriptor());
      return new JSONObject().put("logicalBytes",st.st_size).put("allocatedBytes",st.st_blocks*512L)
          .put("targetVolumeAvailableBytes",new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes())
          .put("appVolumeAvailableBytes", new StatFs(getFilesDir().getPath()).getAvailableBytes());
    }
  }
  private String read(Uri uri) throws Exception { try(InputStream in=getContentResolver().openInputStream(uri)) {
    return FileIntegrity.copy(in,null,200000).sha256;
  } }
  private String sha(byte[] data) throws IOException {return FileIntegrity.copy(new ByteArrayInputStream(data),null,data.length).sha256;}
  private void write(Uri uri,byte[] data) throws IOException {
    SelectedDocumentSave.write(this,uri,()->new ByteArrayInputStream(data),data.length,sha(data),new BackupControl(null));
  }
}
