// Pre-generated from IShellService.aidl with Android build-tools 36.0.0, min SDK 30.
package com.deepseekharness.app;
public interface IShellService extends android.os.IInterface
{
  /** Default implementation for IShellService. */
  public static class Default implements com.deepseekharness.app.IShellService
  {
    @Override public java.lang.String exec(java.lang.String cmd) throws android.os.RemoteException
    {
      return null;
    }
    // Native-only typed launcher; generic shell text may not invoke app_process.
    @Override public java.lang.String execVirtualScreen(java.lang.String command) throws android.os.RemoteException
    {
      return null;
    }
    // Historical DSHA synchronous transaction (wire 16777114).
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    // Shizuku sends wire 16777115 with FLAG_ONEWAY when removing a UserService.
    @Override public void destroyFromManager() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.deepseekharness.app.IShellService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.deepseekharness.app.IShellService interface,
     * generating a proxy if needed.
     */
    public static com.deepseekharness.app.IShellService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.deepseekharness.app.IShellService))) {
        return ((com.deepseekharness.app.IShellService)iin);
      }
      return new com.deepseekharness.app.IShellService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_exec:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.exec(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_execVirtualScreen:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.execVirtualScreen(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_destroyFromManager:
        {
          this.destroyFromManager();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.deepseekharness.app.IShellService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public java.lang.String exec(java.lang.String cmd) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(cmd);
          boolean _status = mRemote.transact(Stub.TRANSACTION_exec, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Native-only typed launcher; generic shell text may not invoke app_process.
      @Override public java.lang.String execVirtualScreen(java.lang.String command) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(command);
          boolean _status = mRemote.transact(Stub.TRANSACTION_execVirtualScreen, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Historical DSHA synchronous transaction (wire 16777114).
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // Shizuku sends wire 16777115 with FLAG_ONEWAY when removing a UserService.
      @Override public void destroyFromManager() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroyFromManager, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_exec = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_execVirtualScreen = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16777113);
    static final int TRANSACTION_destroyFromManager = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16777114);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.deepseekharness.app.IShellService";
  public java.lang.String exec(java.lang.String cmd) throws android.os.RemoteException;
  // Native-only typed launcher; generic shell text may not invoke app_process.
  public java.lang.String execVirtualScreen(java.lang.String command) throws android.os.RemoteException;
  // Historical DSHA synchronous transaction (wire 16777114).
  public void destroy() throws android.os.RemoteException;
  // Shizuku sends wire 16777115 with FLAG_ONEWAY when removing a UserService.
  public void destroyFromManager() throws android.os.RemoteException;
}
