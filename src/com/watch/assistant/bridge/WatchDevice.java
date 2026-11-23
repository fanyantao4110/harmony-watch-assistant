package com.watch.assistant.bridge;

import java.util.Collections;
import java.util.List;

/* loaded from: classes4.dex */
public class WatchDevice {
    private final String address;
    private final boolean authenticationRequired;
    private final int availableSpaceMb;
    private final int batteryLevel;
    private final boolean connected;
    private final boolean connecting;
    private final String displayName;
    private final List<String> fingerprints;
    private final boolean initialized;
    private final int installedAppsCount;
    private final int installedWatchfacesCount;
    private final Object nativeDevice;
    private final String serialNumber;
    private final String stateLabel;
    private final boolean supportsAppListFetching;
    private final boolean supportsApps;
    private final boolean supportsWatchfaces;
    private final String uid;

    public WatchDevice(Object obj, String str, String str2, String str3, String str4, String str5, int i, int i2, boolean z, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6, boolean z7, int i3, int i4, List<String> list) {
        this.nativeDevice = obj;
        this.address = str;
        this.displayName = str2;
        this.uid = str3;
        this.serialNumber = str4;
        this.stateLabel = str5;
        this.batteryLevel = i;
        this.availableSpaceMb = i2;
        this.connected = z;
        this.initialized = z2;
        this.connecting = z3;
        this.authenticationRequired = z4;
        this.supportsApps = z5;
        this.supportsWatchfaces = z6;
        this.supportsAppListFetching = z7;
        this.installedAppsCount = i3;
        this.installedWatchfacesCount = i4;
        this.fingerprints = Collections.unmodifiableList(list);
    }

    public Object getNativeDevice() {
        return this.nativeDevice;
    }

    public String getAddress() {
        return this.address;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public String getUid() {
        return this.uid;
    }

    public String getSerialNumber() {
        return this.serialNumber;
    }

    public String getStateLabel() {
        return this.stateLabel;
    }

    public int getBatteryLevel() {
        return this.batteryLevel;
    }

    public int getAvailableSpaceMb() {
        return this.availableSpaceMb;
    }

    public boolean isConnected() {
        return this.connected;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public boolean isReady() {
        return this.initialized;
    }

    public boolean isConnecting() {
        return this.connecting;
    }

    public boolean isAuthenticationRequired() {
        return this.authenticationRequired;
    }

    public boolean supportsApps() {
        return this.supportsApps;
    }

    public boolean supportsWatchfaces() {
        return this.supportsWatchfaces;
    }

    public boolean supportsAppListFetching() {
        return this.supportsAppListFetching;
    }

    public int getInstalledAppsCount() {
        return this.installedAppsCount;
    }

    public int getInstalledWatchfacesCount() {
        return this.installedWatchfacesCount;
    }

    public List<String> getFingerprints() {
        return this.fingerprints;
    }

    WatchDevice withInstalledCounts(int i, int i2) {
        return new WatchDevice(this.nativeDevice, this.address, this.displayName, this.uid, this.serialNumber, this.stateLabel, this.batteryLevel, this.availableSpaceMb, this.connected, this.initialized, this.connecting, this.authenticationRequired, this.supportsApps, this.supportsWatchfaces, this.supportsAppListFetching, i, i2, this.fingerprints);
    }

    WatchDevice withAvailableSpaceMb(int i) {
        return new WatchDevice(this.nativeDevice, this.address, this.displayName, this.uid, this.serialNumber, this.stateLabel, this.batteryLevel, i, this.connected, this.initialized, this.connecting, this.authenticationRequired, this.supportsApps, this.supportsWatchfaces, this.supportsAppListFetching, this.installedAppsCount, this.installedWatchfacesCount, this.fingerprints);
    }
}
