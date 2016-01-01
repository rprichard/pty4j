package com.pty4j.windows;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author traff
 */
public class NamedPipe {
  private volatile WinNT.HANDLE myHandle;

  private AtomicBoolean spinLock = new AtomicBoolean();

  public NamedPipe(WinNT.HANDLE handle) {
    myHandle = handle;
  }

  public void write(byte[] buf, int len) throws IOException {
    if (len < 0) {
      len = 0;
    }
    try {
      while (spinLock.getAndSet(true)) {
        Thread.yield();
      }
      if (myHandle == null) {
        return;
      }
      write0(myHandle, buf, len);
    }
    catch (IOException e) {
      throw new IOException("IO Exception while writing to the pipe.", e);
    }
    finally {
      spinLock.set(false);
    }
  }

  public int read(byte[] buf, int len) throws IOException {
    if (len < 0) {
      len = 0;
    }
    long curLength = 0;
    while (curLength == 0) {
      if (myHandle == null) {
        return -1;
      }
      try {
        // It is a little hazardout to call PeekNamedPipe on a HANDLE that
        // could concurrently become closed (and reopened as a different
        // object), but acquiring the spin lock (or a full lock) has its own
        // hazards.
        curLength = available(myHandle);
        if (curLength < 0) {
          return -1;
        }
      }
      catch (IOException e) {
        return -1;
      }
      try {
        Thread.sleep(20);
      }
      catch (InterruptedException e) {
        return -1;
      }
    }
    //incoming stream. read now
    try {
      while (spinLock.getAndSet(true)) {
        Thread.yield();
      }
      if (myHandle == null) {
        return -1;
      }
      return read0(myHandle, buf, len);
    }
    catch (IOException e) {
      throw new IOException("IO Exception while reading from the pipe.", e);
    }
    finally {
      spinLock.set(false);
    }
  }

  public int available() throws IOException {
    return (int) available(myHandle);
  }

  private static long available(WinNT.HANDLE handle) throws IOException {
    if (handle == null) {
      return -1;
    }

    IntByReference read = new IntByReference(0);
    Buffer b = ByteBuffer.wrap(new byte[10]);

    if (!WinPty.KERNEL32.PeekNamedPipe(handle, b, b.capacity(), new IntByReference(), read, new IntByReference())) {
      throw new IOException("Cant peek named pipe");
    }

    return read.getValue();
  }

  private static int read0(WinNT.HANDLE handle, byte[] b, int len) throws IOException {
    if (handle == null) {
      return -1;
    }
    IntByReference dwRead = new IntByReference();
    ByteBuffer buf = ByteBuffer.wrap(b);
    WinPty.KERNEL32.ReadFile(handle, buf, len, dwRead, null);

    return dwRead.getValue();
  }

  private static int write0(WinNT.HANDLE handle, byte[] b, int len) throws IOException {
    if (handle == null) {
      return -1;
    }
    IntByReference dwWritten = new IntByReference();
    Kernel32.INSTANCE.WriteFile(handle, b, len, dwWritten, null);
    return dwWritten.getValue();
  }

  public void markClosed() {
    myHandle = null;
  }

  public void close() throws IOException {
    try {
      while (spinLock.getAndSet(true)) {
        Thread.yield();
      }
      if (myHandle == null) {
        return;
      }
      boolean status = close0(myHandle);
      if (!status) {
        throw new IOException("Close error:" + Kernel32.INSTANCE.GetLastError());
      }
      myHandle = null;
    } finally {
       spinLock.set(false);
    }
  }

  public static boolean close0(WinNT.HANDLE handle) throws IOException {
    return Kernel32.INSTANCE.CloseHandle(handle);
  }
}