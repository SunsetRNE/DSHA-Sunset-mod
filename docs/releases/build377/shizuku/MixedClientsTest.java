package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(DocumentRowsSnapshotTest.NoApkRunner.class)
@Config(sdk=34, manifest=Config.NONE, application=Application.class)
public final class MixedClientsTest {
  // The Binder is intentionally un-attached so asInterface always selects the actual generated Proxy.
  private static final class Link extends Binder {
    final IBinder server;
    final List<Integer> codes=new ArrayList<>();
    final List<Integer> flagsSeen=new ArrayList<>();
    Link(IBinder server){this.server=server;}
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
      codes.add(code);flagsSeen.add(flags);
      return server.transact(code,data,reply,flags);
    }
  }
  @Test public void build376ClientToBuild377Stub() throws Exception {
    IShellService.Stub server=new IShellService.Stub(){
      public String exec(String value){return "normal-"+value;}
      public String execVirtualScreen(String value){return "typed-"+value;}
      public void destroy(){}
      public void destroyFromManager(){}
    };
    Link link=new Link(server); var client=fixture.pre.IShellService.Stub.asInterface(link);
    assertEquals("normal-data",client.exec("data"));assertEquals("typed-data",client.execVirtualScreen("data"));
    client.destroy();assertEquals(List.of(1,2,16777114),link.codes);assertEquals(List.of(0,0,0),link.flagsSeen);
  }
  @Test public void build377ClientToBuild376Stub() throws Exception {
    fixture.pre.IShellService.Stub server=new fixture.pre.IShellService.Stub(){
      public String exec(String value){return "normal-"+value;}
      public String execVirtualScreen(String value){return "typed-"+value;}
      public void destroy(){}
    };
    Link link=new Link(server);var client=IShellService.Stub.asInterface(link);
    assertEquals("normal-data",client.exec("data"));assertEquals("typed-data",client.execVirtualScreen("data"));
    client.destroy();assertEquals(List.of(1,2,16777114),link.codes);assertEquals(List.of(0,0,0),link.flagsSeen);
  }
}
