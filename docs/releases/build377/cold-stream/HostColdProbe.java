package com.deepseekharness.app.runtime;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.BundledRootfsAsset;

public final class HostColdProbe {
  public static void main(String[] args) throws Exception {
    String expected="0027c09c3a63493571dbe32a291ab83229c6c4f816e7285f849cabf8a15245f0";
    File out=new File(args[1]); if(!out.mkdir())throw new IOException("fresh output required");
    long started=System.nanoTime();
    try(ZipFile apk=new ZipFile(args[0])) {
      int[] closes={0}, afterClose={0}, names={0}; long[] bytes={0};
      try(InputStream asset=new FilterInputStream(apk.getInputStream(apk.getEntry("assets/ubuntu-tools.bin"))) {
        public int read() throws IOException {if(closes[0]!=0)afterClose[0]++;int n=super.read();if(n>=0)bytes[0]++;return n;}
        public int read(byte[] b,int i,int n) throws IOException {if(closes[0]!=0)afterClose[0]++;int r=super.read(b,i,n);if(r>0)bytes[0]+=r;return r;}
        public void close() throws IOException {closes[0]++;super.close();}
      };InputStream decoded=BundledRootfsAsset.decode(asset,expected)) {
        TarGzipExtractor.extractSelected(decoded,out,0,name->{names[0]++;return Set.of("root/.dsha-ubuntu-tools-version","var/lib/dpkg/status").contains(name);},new JvmBackupFileSystem());
      }
      String lock=Files.readString(new File(out,"root/.dsha-ubuntu-tools-version").toPath()).trim();
      String status=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(out,"var/lib/dpkg/status").toPath())));
      if(!lock.equals("5f75c41bb6a543143e4a05d78fc13f0abf54367c1f4454526a9266e119299c1e")||!status.equals("f4edc364d3d98514ae7567063645013b2e36f3eb3493ce0076fcc270e4b248b2")||names[0]!=2945||afterClose[0]!=0)throw new IOException("unexpected result");
      System.out.printf("jdk17 production decode/TAR EOF closed OK members=%d compressedBytes=%d raw.close=%d readsAfterClose=%d lock=%s statusSha=%s seconds=%.3f%n",names[0],bytes[0],closes[0],afterClose[0],lock,status,(System.nanoTime()-started)/1e9);
    }
  }
}
