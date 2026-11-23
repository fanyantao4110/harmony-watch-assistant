package com.watch.assistant.bridge;

import android.bluetooth.BluetoothSocket;
import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes4.dex */
final class HuaweiRfcommWriter {
    private static final String TAG = "NativeHuawei";
    private final String address;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() { // from class: com.watch.assistant.bridge.HuaweiRfcommWriter$$ExternalSyntheticLambda1
        @Override // java.util.concurrent.ThreadFactory
        public final Thread newThread(Runnable runnable) {
            return HuaweiRfcommWriter.lambda$new$0(runnable);
        }
    });
    private final FailureListener failureListener;
    private final BluetoothSocket socket;

    interface FailureListener {
        void onWriteFailure(Exception exc);
    }

    HuaweiRfcommWriter(String str, BluetoothSocket bluetoothSocket, FailureListener failureListener) {
        this.address = str;
        this.socket = bluetoothSocket;
        this.failureListener = failureListener;
    }

    static /* synthetic */ Thread lambda$new$0(Runnable runnable) {
        Thread thread = new Thread(runnable, "HuaweiRfcommWriter");
        thread.setDaemon(true);
        return thread;
    }

    boolean enqueue(List<byte[]> list, final long j) {
        if (!list.isEmpty() && !this.closed.get()) {
            final ArrayList arrayList = new ArrayList(list.size());
            for (byte[] bArr : list) {
                if (bArr == null || bArr.length == 0) {
                    return false;
                }
                arrayList.add((byte[]) bArr.clone());
            }
            try {
                this.executor.execute(new Runnable() { // from class: com.watch.assistant.bridge.HuaweiRfcommWriter$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        HuaweiRfcommWriter.this.lambda$enqueue$1(arrayList, j);
                    }
                });
                return true;
            } catch (RejectedExecutionException unused) {
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$enqueue$1(List list, long j) {
        writeTransaction(list, Math.max(0L, j));
    }

    private void writeTransaction(List<byte[]> list, long j) {
        if (this.closed.get()) {
            return;
        }
        try {
            OutputStream outputStream = this.socket.getOutputStream();
            if (outputStream == null) {
                throw new IOException("RFCOMM output stream is null");
            }
            for (byte[] bArr : list) {
                if (this.closed.get() || !this.socket.isConnected()) {
                    throw new IOException("RFCOMM socket is not connected");
                }
                outputStream.write(bArr);
                outputStream.flush();
                Log.v(TAG, "RFCOMM tx bytes=" + bArr.length + " queuedWriter=true");
            }
            if (j > 0) {
                Thread.sleep(j);
            }
        } catch (IOException e) {
            notifyFailure(e);
        } catch (InterruptedException unused) {
            Thread.currentThread().interrupt();
        } catch (SecurityException e2) {
            notifyFailure(e2);
        }
    }

    private void notifyFailure(Exception exc) {
        if (this.closed.compareAndSet(false, true)) {
            this.executor.shutdownNow();
            Log.w(TAG, "RFCOMM writer stopped address=" + safeAddress(this.address), exc);
            this.failureListener.onWriteFailure(exc);
        }
    }

    void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.executor.shutdownNow();
        }
    }

    private static String safeAddress(String str) {
        if (str.length() <= 5) {
            return str;
        }
        return "**:**:**:**:" + str.substring(str.length() - 5);
    }
}
