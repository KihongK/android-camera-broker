/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /Users/ac01-kkhong/Library/Android/sdk/build-tools/35.0.0/aidl -p/Users/ac01-kkhong/Library/Android/sdk/platforms/android-36/framework.aidl -o/Users/ac01-kkhong/AndroidStudioProjects/android-camera-broker/shared/build/generated/aidl_source_output_dir/debug/out -I/Users/ac01-kkhong/AndroidStudioProjects/android-camera-broker/shared/src/main/aidl -I/Users/ac01-kkhong/AndroidStudioProjects/android-camera-broker/shared/src/debug/aidl -I/Users/ac01-kkhong/.gradle/caches/8.13/transforms/e81328fa094facb11cbd9127b20abb5d/transformed/core-1.17.0/aidl -I/Users/ac01-kkhong/.gradle/caches/8.13/transforms/7ecf66291251603e35c238d55075c6fc/transformed/versionedparcelable-1.1.1/aidl -d/var/folders/x1/xl_qlwpn62l1d7dgv5mwfh8c0000gn/T/aidl5540319092886237931.d /Users/ac01-kkhong/AndroidStudioProjects/android-camera-broker/shared/src/main/aidl/com/roy/camera_test/ICameraBroker.aidl
 */
package com.roy.camera_test;
public interface ICameraBroker extends android.os.IInterface
{
  /** Default implementation for ICameraBroker. */
  public static class Default implements com.roy.camera_test.ICameraBroker
  {
    /**
     * Get the SharedMemory for reading frame data.
     * Returns null if camera is not ready.
     */
    @Override public android.os.SharedMemory getSharedMemory() throws android.os.RemoteException
    {
      return null;
    }
    /**
     * Get current frame information (width, height, timestamp, etc.)
     * Returns null if no frame is available.
     */
    @Override public com.roy.camera_test.FrameInfo getCurrentFrameInfo() throws android.os.RemoteException
    {
      return null;
    }
    /**
     * Get the current frame counter.
     * Clients can poll this to detect new frames.
     */
    @Override public long getFrameCounter() throws android.os.RemoteException
    {
      return 0L;
    }
    /** Check if the camera service is ready and streaming. */
    @Override public boolean isStreaming() throws android.os.RemoteException
    {
      return false;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.roy.camera_test.ICameraBroker
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.roy.camera_test.ICameraBroker interface,
     * generating a proxy if needed.
     */
    public static com.roy.camera_test.ICameraBroker asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.roy.camera_test.ICameraBroker))) {
        return ((com.roy.camera_test.ICameraBroker)iin);
      }
      return new com.roy.camera_test.ICameraBroker.Stub.Proxy(obj);
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
        case TRANSACTION_getSharedMemory:
        {
          android.os.SharedMemory _result = this.getSharedMemory();
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getCurrentFrameInfo:
        {
          com.roy.camera_test.FrameInfo _result = this.getCurrentFrameInfo();
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getFrameCounter:
        {
          long _result = this.getFrameCounter();
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        case TRANSACTION_isStreaming:
        {
          boolean _result = this.isStreaming();
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.roy.camera_test.ICameraBroker
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
      /**
       * Get the SharedMemory for reading frame data.
       * Returns null if camera is not ready.
       */
      @Override public android.os.SharedMemory getSharedMemory() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.SharedMemory _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSharedMemory, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, android.os.SharedMemory.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Get current frame information (width, height, timestamp, etc.)
       * Returns null if no frame is available.
       */
      @Override public com.roy.camera_test.FrameInfo getCurrentFrameInfo() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        com.roy.camera_test.FrameInfo _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCurrentFrameInfo, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, com.roy.camera_test.FrameInfo.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Get the current frame counter.
       * Clients can poll this to detect new frames.
       */
      @Override public long getFrameCounter() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getFrameCounter, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Check if the camera service is ready and streaming. */
      @Override public boolean isStreaming() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isStreaming, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_getSharedMemory = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getCurrentFrameInfo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_getFrameCounter = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_isStreaming = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.roy.camera_test.ICameraBroker";
  /**
   * Get the SharedMemory for reading frame data.
   * Returns null if camera is not ready.
   */
  public android.os.SharedMemory getSharedMemory() throws android.os.RemoteException;
  /**
   * Get current frame information (width, height, timestamp, etc.)
   * Returns null if no frame is available.
   */
  public com.roy.camera_test.FrameInfo getCurrentFrameInfo() throws android.os.RemoteException;
  /**
   * Get the current frame counter.
   * Clients can poll this to detect new frames.
   */
  public long getFrameCounter() throws android.os.RemoteException;
  /** Check if the camera service is ready and streaming. */
  public boolean isStreaming() throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
