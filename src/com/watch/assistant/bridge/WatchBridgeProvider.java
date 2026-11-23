package com.watch.assistant.bridge;

import android.content.Context;

/* loaded from: classes4.dex */
public final class WatchBridgeProvider {
    private static final WatchBridge INSTANCE = new NativeWatchBridge();

    private WatchBridgeProvider() {
    }

    public static WatchBridge get(Context context) {
        WatchBridge watchBridge = INSTANCE;
        watchBridge.initialize(context.getApplicationContext());
        return watchBridge;
    }

    public static int resolveDeviceImageResource(String str) {
        return WatchDeviceAssets.resolveImageResource(str);
    }
}
