package com.pty4j.windows;

import com.pty4j.PtyException;
import com.pty4j.WinSize;
import com.pty4j.unix.Pty;
import com.pty4j.util.PtyUtil;
import com.sun.jna.*;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import jtermios.windows.WinAPI;

import java.io.*;
import java.nio.Buffer;
import java.util.Arrays;
import java.util.List;

/**
 * @author traff
 */
public class WinPty {
  private final Pointer myWinpty;
  private final WinNT.HANDLE myProcess;

  private FileOutputStream coninPipe;
  private FileInputStream conoutPipe;
  private boolean myClosed = false;


  public WinPty(String cmdline, String cwd, String env, boolean consoleMode) throws PtyException {
    int cols = Integer.getInteger("win.pty.cols", 80);
    int rows = Integer.getInteger("win.pty.rows", 25);

    Pointer agentCfg = null;
    Pointer wp = null;
    Pointer spawnCfg = null;
    Pointer coninName = null;
    Pointer conoutName = null;

    try {
      // Agent startup configuration
      agentCfg = INSTANCE.winpty_config_new(consoleMode ? WinPtyLib.WINPTY_FLAG_PLAIN_TEXT : 0, null, null);
      if (agentCfg == null) {
        throw new PtyException("winpty cfg is null");
      }
      if (!INSTANCE.winpty_config_set_initial_size(agentCfg, cols, rows, null, null)) {
        throw new PtyException("winpty cfg set_initial_size failed");
      }

      // Connect to the agent.  These file open commands should fail instantly if they are going to fail.
      wp = INSTANCE.winpty_open(agentCfg, null, null);
      if (wp == null) {
        throw new PtyException("could not open winpty");
      }
      try {
        coninPipe = new FileOutputStream(readWStr(coninName = INSTANCE.winpty_conin_name(wp)));
        conoutPipe = new FileInputStream(readWStr(conoutName = INSTANCE.winpty_conout_name(wp)));
      } catch (FileNotFoundException e) {
        throw new PtyException("could not connect to CONIN/CONOUT winpty pipes");
      }

      // Spawn a child process from the agent.
      spawnCfg = INSTANCE.winpty_spawn_config_new(
              WinPtyLib.WINPTY_SPAWN_FLAG_AUTO_SHUTDOWN,
              null, toWString(cmdline), toWString(cwd), toWString(env), null, null);
      if (spawnCfg == null) {
        throw new PtyException("winpty spawn cfg is null");
      }

      WinNT.HANDLEByReference process = new WinNT.HANDLEByReference();

      if (!INSTANCE.winpty_spawn(wp, spawnCfg, process.getPointer(), null, null, null, null)) {
        throw new PtyException("Error running process");
      }

      // Success!
      myWinpty = wp;
      myProcess = process.getValue();
      wp = null;

      // Insert a dummy call to getPointer(), which returns the underlying
      // Memory object.  The call is harmless at worst; at best, it works
      // around a JNA/GC bug where Memory objects tend to get GC'd prematurely.
      // See https://groups.google.com/forum/#!topic/jna-users/dCPnztnQnRw.
      process.getPointer();

    } finally {
      INSTANCE.winpty_config_free(agentCfg);
      INSTANCE.winpty_free(wp);
      INSTANCE.winpty_spawn_config_free(spawnCfg);
      INSTANCE.winpty_wstr_free(coninName);
      INSTANCE.winpty_wstr_free(conoutName);
    }
  }

  private static String readWStr(Pointer ptr) {
    return ptr.getWideString(0);
  }

  private static WString toWString(String string) {
    return string == null ? null : new WString(string);
  }

  public void setWinSize(WinSize winSize) {
    if (myClosed) {
      return;
    }
    INSTANCE.winpty_set_size(myWinpty, winSize.ws_col, winSize.ws_row, null, null);
  }

  public void close() {
    if (myClosed) {
      return;
    }

    INSTANCE.winpty_free(myWinpty);
    Kernel32.INSTANCE.CloseHandle(myProcess);

    myClosed = true;
  }

  public int exitValue() {
    if (myClosed) {
      return -2;
    }
    IntByReference codeOut = new IntByReference();
    if (Kernel32.INSTANCE.GetExitCodeProcess(myProcess, codeOut)) {
      int code = codeOut.getValue();
      codeOut.getPointer();
      if (code == WinNT.STILL_ACTIVE) {
        return -1;
      } else {
        return code;
      }
    } else {
      return -1;
    }
  }

  public InputStream getInputPipe() {
    return conoutPipe;
  }

  public OutputStream getOutputPipe() {
    return coninPipe;
  }

  public InputStream getErrorPipe() {
    // TODO: Implement this...
    return null;
    //return myErrNamedPipe;
  }

  public static final Kern32 KERNEL32 = (Kern32)Native.loadLibrary("kernel32", Kern32.class);

  interface Kern32 extends Library {
    boolean PeekNamedPipe(WinNT.HANDLE hFile,
                          Buffer lpBuffer,
                          int nBufferSize,
                          IntByReference lpBytesRead,
                          IntByReference lpTotalBytesAvail,
                          IntByReference lpBytesLeftThisMessage);

    boolean ReadFile(WinNT.HANDLE handle, Buffer buffer, int i, IntByReference reference, WinBase.OVERLAPPED overlapped);

    WinNT.HANDLE CreateNamedPipeA(String lpName,
                                  int dwOpenMode,
                                  int dwPipeMode,
                                  int nMaxInstances,
                                  int nOutBufferSize,
                                  int nInBufferSize,
                                  int nDefaultTimeout,
                                  WinBase.SECURITY_ATTRIBUTES securityAttributes);

    boolean ConnectNamedPipe(WinNT.HANDLE hNamedPipe, WinBase.OVERLAPPED overlapped);

    boolean CloseHandle(WinNT.HANDLE hObject);

    WinNT.HANDLE CreateEventA(WinBase.SECURITY_ATTRIBUTES lpEventAttributes, boolean bManualReset, boolean bInitialState, String lpName);

    int GetLastError();

    int WaitForSingleObject(WinNT.HANDLE hHandle, int dwMilliseconds);

    boolean CancelIo(WinNT.HANDLE hFile);

    int GetCurrentProcessId();
  }

  public static WinPtyLib INSTANCE = (WinPtyLib)Native.loadLibrary(getLibraryPath(), WinPtyLib.class);

  private static String getLibraryPath() {
    try {
      return PtyUtil.resolveNativeLibrary().getAbsolutePath();
    }
    catch (Exception e) {
      throw new IllegalStateException("Couldn't detect jar containing folder", e);
    }
  }

  interface WinPtyLib extends Library {
    /*
     * winpty API.
     */

    int WINPTY_FLAG_PLAIN_TEXT = 1;
    int WINPTY_SPAWN_FLAG_AUTO_SHUTDOWN = 1;

    void winpty_wstr_free(Pointer str);
    Pointer winpty_config_new(int flags, Pointer err_code, Pointer err_msg);
    void winpty_config_free(Pointer cfg);
    boolean winpty_config_set_initial_size(Pointer cfg, int cols, int rows, Pointer err_code, Pointer err_msg);
    Pointer winpty_open(Pointer cfg, Pointer err_code, Pointer err_msg);
    Pointer winpty_conin_name(Pointer wp);
    Pointer winpty_conout_name(Pointer wp);
    Pointer winpty_spawn_config_new(int winptyFlags, WString appname, WString cmdline, WString cwd, WString env,
                                    Pointer err_code, Pointer err_msg);
    void winpty_spawn_config_free(Pointer cfg);
    boolean winpty_spawn(Pointer wp, Pointer cfg, Pointer processHandleOut, Pointer threadHandleOut,
                         Pointer createProcessErrorOut, Pointer err_code, Pointer err_msg);
    boolean winpty_set_size(Pointer wp, int cols, int rows, Pointer err_code, Pointer err_msg);
    void winpty_free(Pointer wp);
  }
}
