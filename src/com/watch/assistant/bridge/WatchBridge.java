package com.watch.assistant.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.List;

/* loaded from: classes4.dex */
public interface WatchBridge {
    public static final String ACTION_DEVICE_CHANGED = "com.watch.assistant.bridge.ACTION_DEVICE_CHANGED";
    public static final String ACTION_INSTALL_FAILED = "com.watch.assistant.bridge.ACTION_INSTALL_FAILED";
    public static final String ACTION_INSTALL_FINISHED = "com.watch.assistant.bridge.ACTION_INSTALL_FINISHED";
    public static final String ACTION_INSTALL_MESSAGE = "com.watch.assistant.bridge.ACTION_INSTALL_MESSAGE";
    public static final String ACTION_INSTALL_PROGRESS = "com.watch.assistant.bridge.ACTION_INSTALL_PROGRESS";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    public static final String EXTRA_INDETERMINATE = "indeterminate";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_SELECTED_DEVICE_ADDRESS = "selected_device_address";
    public static final int REQUEST_INSTALL_APPS = 1;
    public static final int REQUEST_INSTALL_WATCHFACES = 2;

    void activateInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem);

    boolean canInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context);

    void connect(WatchDevice watchDevice);

    Intent createAppManagerIntent(Context context, WatchDevice watchDevice, int i);

    Intent createNativePairingIntent(Context context);

    Intent createPairingIntent(Context context);

    void deleteInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem);

    void disconnect(WatchDevice watchDevice);

    WatchDevice findByAddress(String str);

    WatchDevice findByUid(String str);

    List<WatchDevice> getDevices();

    List<WatchInstalledItem> getInstalledItems(String str, int i);

    List<WatchDevice> getReadyDevices();

    String getSelectedDeviceAddress(Intent intent);

    void initialize(Context context);

    void onPairingActivityResult(int i, Intent intent, Activity activity);

    void requestAppList(WatchDevice watchDevice);

    WatchDevice resolveConnectableDevice(String str, Context context);

    int resolveDeviceImageResource(String str);

    void startInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context);

    void submitAuthenticationPin(WatchDevice watchDevice, String str);
}
