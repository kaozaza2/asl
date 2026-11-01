package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * ASL fork: a terminal session whose process lives elsewhere (a VM over vsock) instead of a local JNI pty.
 * The {@link Host} receives keyboard input and size changes; process output is pushed in with {@link #feed}.
 */
public final class TerminalSession extends TerminalOutput {

    public interface Host {
        void onInput(byte[] data, int offset, int count);

        void onResize(int columns, int rows);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;
    private final Host mHost;
    private boolean mRunning = true;
    private int mExitStatus;

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, Host host) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mHost = host;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        mHost.onResize(columns, rows);
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Process output; callable from any thread. */
    public void feed(byte[] data, int offset, int count) {
        if (mProcessToTerminalIOQueue.write(data, offset, count)) mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Marks the remote process as exited; callable from any thread. */
    public void finish(int exitStatus) {
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, exitStatus));
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (isRunning()) mHost.onInput(data, offset, count);
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }
        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;
        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            // Output can arrive before the view has measured and created the emulator.
            if (mEmulator == null) {
                sendMessageDelayed(obtainMessage(msg.what, msg.obj), 16);
                return;
            }
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }
            if (msg.what == MSG_PROCESS_EXITED) {
                synchronized (TerminalSession.this) {
                    mRunning = false;
                    mExitStatus = (Integer) msg.obj;
                }
                mProcessToTerminalIOQueue.close();
                byte[] exitText = ("\r\n[process exited with code " + mExitStatus + "]").getBytes(StandardCharsets.UTF_8);
                mEmulator.append(exitText, exitText.length);
                notifyScreenUpdate();
                mClient.onSessionFinished(TerminalSession.this);
            }
        }
    }
}
