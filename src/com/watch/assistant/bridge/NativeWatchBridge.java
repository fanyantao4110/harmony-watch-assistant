package com.watch.assistant.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import java.util.List;

/* loaded from: classes4.dex */
final class NativeWatchBridge implements WatchBridge {
    private WatchBackend backend;
    private final WatchDeviceStore store = WatchDeviceStore.get();

    @Override // com.watch.assistant.bridge.WatchBridge
    public void onPairingActivityResult(int i, Intent intent, Activity activity) {
    }

    NativeWatchBridge() {
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void initialize(Context context) {
        this.store.initialize(context);
        if (this.backend == null) {
            this.backend = new NativeHuaweiWatchBackend(context, this.store);
        }
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public List<WatchDevice> getDevices() {
        return this.store.getDevices();
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public List<WatchDevice> getReadyDevices() {
        return this.store.getReadyDevices();
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public WatchDevice findByUid(String str) {
        return this.store.findByUid(str);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public WatchDevice findByAddress(String str) {
        return this.store.findByAddress(str);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public List<WatchInstalledItem> getInstalledItems(String str, int i) {
        return this.store.getInstalledItems(str, i);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public WatchDevice resolveConnectableDevice(String str, Context context) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        return backend(context).resolveConnectableDevice(str, context);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void connect(WatchDevice watchDevice) {
        backend(null).connect(watchDevice);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void disconnect(WatchDevice watchDevice) {
        backend(null).disconnect(watchDevice);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void requestAppList(WatchDevice watchDevice) {
        backend(null).requestAppList(watchDevice);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void deleteInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem) {
        backend(null).deleteInstalledItem(watchDevice, watchInstalledItem);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void activateInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem) {
        backend(null).activateInstalledItem(watchDevice, watchInstalledItem);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void submitAuthenticationPin(WatchDevice watchDevice, String str) {
        backend(null).submitAuthenticationPin(watchDevice, str);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public boolean canInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context) {
        return backend(context).canInstall(watchDevice, uri, bundle, context);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public void startInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context) {
        backend(context).startInstall(watchDevice, uri, bundle, context);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public Intent createPairingIntent(Context context) {
        return createNativePairingIntent(context);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public Intent createNativePairingIntent(Context context) {
        // 原版指向 NativeHuaweiPairingActivity（自带一套 androidx UI 与 BlE 扫描页）。
        // 本项目配对界面是独立的 PairActivity，这里直接指向它，参数由 PairActivity 自己处理。
        return new Intent(context, (Class<?>) com.wlaz.PairActivity.class);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public String getSelectedDeviceAddress(Intent intent) {
        if (intent != null) {
            return intent.getStringExtra("selected_device_address");
        }
        return null;
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public Intent createAppManagerIntent(Context context, WatchDevice watchDevice, int i) {
        return backend(context).createAppManagerIntent(context, watchDevice, i);
    }

    @Override // com.watch.assistant.bridge.WatchBridge
    public int resolveDeviceImageResource(String str) {
        return WatchDeviceAssets.resolveImageResource(str);
    }

    private WatchBackend backend(Context context) {
        if (this.backend == null) {
            if (context == null) {
                throw new IllegalStateException("Watch bridge is not initialized");
            }
            initialize(context);
        }
        return this.backend;
    }
}