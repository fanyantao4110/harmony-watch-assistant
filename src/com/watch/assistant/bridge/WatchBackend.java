package com.watch.assistant.bridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/* loaded from: classes4.dex */
interface WatchBackend {
    void activateInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem);

    boolean canInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context);

    void connect(WatchDevice watchDevice);

    Intent createAppManagerIntent(Context context, WatchDevice watchDevice, int i);

    void deleteInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem);

    void disconnect(WatchDevice watchDevice);

    void requestAppList(WatchDevice watchDevice);

    WatchDevice resolveConnectableDevice(String str, Context context);

    void startInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context);

    void submitAuthenticationPin(WatchDevice watchDevice, String str);
}
