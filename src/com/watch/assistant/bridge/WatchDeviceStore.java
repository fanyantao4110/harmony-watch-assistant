package com.watch.assistant.bridge;

import android.content.Context;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes4.dex */
final class WatchDeviceStore {
    private static final WatchDeviceStore INSTANCE = new WatchDeviceStore();
    private static final String KEY_DEVICES = "devices";
    private static final String PREFS_NAME = "watch_device_store";
    private Context appContext;
    private final Map<String, WatchDevice> devicesByAddress = new LinkedHashMap();
    private final Map<String, List<WatchInstalledItem>> installedItemsByKey = new LinkedHashMap();

    private WatchDeviceStore() {
    }

    static WatchDeviceStore get() {
        return INSTANCE;
    }

    synchronized void initialize(Context context) {
        if (this.appContext != null) {
            return;
        }
        this.appContext = context.getApplicationContext();
        loadPersistedLocked();
    }

    synchronized void replaceAll(List<WatchDevice> list) {
        WatchDevice watchDevice;
        LinkedHashMap linkedHashMap = new LinkedHashMap(this.devicesByAddress);
        this.devicesByAddress.clear();
        for (WatchDevice watchDevice2 : list) {
            if (!TextUtils.isEmpty(watchDevice2.getAddress()) && (watchDevice = (WatchDevice) linkedHashMap.get(watchDevice2.getAddress())) != null && watchDevice2.getInstalledAppsCount() < 0 && watchDevice2.getInstalledWatchfacesCount() < 0) {
                watchDevice2 = watchDevice2.withInstalledCounts(watchDevice.getInstalledAppsCount(), watchDevice.getInstalledWatchfacesCount());
            }
            putLocked(watchDevice2);
        }
        persistLocked();
    }

    synchronized void upsert(WatchDevice watchDevice) {
        if (watchDevice == null) {
            return;
        }
        putLocked(watchDevice);
        persistLocked();
    }

    synchronized List<WatchDevice> getDevices() {
        return new ArrayList(this.devicesByAddress.values());
    }

    synchronized List<WatchDevice> getReadyDevices() {
        ArrayList arrayList;
        arrayList = new ArrayList();
        for (WatchDevice watchDevice : this.devicesByAddress.values()) {
            if (watchDevice.isReady()) {
                arrayList.add(watchDevice);
            }
        }
        return arrayList;
    }

    synchronized WatchDevice findByAddress(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        return this.devicesByAddress.get(str);
    }

    synchronized WatchDevice findByUid(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        for (WatchDevice watchDevice : this.devicesByAddress.values()) {
            if (TextUtils.equals(str, watchDevice.getUid())) {
                return watchDevice;
            }
        }
        return null;
    }

    private void putLocked(WatchDevice watchDevice) {
        if (TextUtils.isEmpty(watchDevice.getAddress())) {
            return;
        }
        WatchDevice watchDevice2 = this.devicesByAddress.get(watchDevice.getAddress());
        if (watchDevice2 != null && watchDevice.getInstalledAppsCount() < 0 && watchDevice.getInstalledWatchfacesCount() < 0) {
            watchDevice = watchDevice.withInstalledCounts(watchDevice2.getInstalledAppsCount(), watchDevice2.getInstalledWatchfacesCount());
        }
        this.devicesByAddress.put(watchDevice.getAddress(), watchDevice);
    }

    synchronized void updateInstalledCounts(String str, int i, int i2) {
        if (TextUtils.isEmpty(str)) {
            return;
        }
        WatchDevice watchDevice = this.devicesByAddress.get(str);
        if (watchDevice == null) {
            return;
        }
        this.devicesByAddress.put(str, watchDevice.withInstalledCounts(i, i2));
        persistLocked();
    }

    synchronized void updateInstalledItems(String str, int i, List<WatchInstalledItem> list) {
        if (TextUtils.isEmpty(str)) {
            return;
        }
        this.installedItemsByKey.put(contentKey(str, i), new ArrayList(list));
        WatchDevice watchDevice = this.devicesByAddress.get(str);
        if (watchDevice != null) {
            int installedAppsCount = watchDevice.getInstalledAppsCount();
            int installedWatchfacesCount = watchDevice.getInstalledWatchfacesCount();
            if (i == 2) {
                installedWatchfacesCount = list.size();
            } else {
                installedAppsCount = list.size();
            }
            this.devicesByAddress.put(str, watchDevice.withInstalledCounts(installedAppsCount, installedWatchfacesCount));
        }
        persistLocked();
    }

    synchronized List<WatchInstalledItem> getInstalledItems(String str, int i) {
        if (TextUtils.isEmpty(str)) {
            return new ArrayList();
        }
        List<WatchInstalledItem> list = this.installedItemsByKey.get(contentKey(str, i));
        return list != null ? new ArrayList(list) : new ArrayList();
    }

    synchronized void updateAvailableSpace(String str, int i) {
        if (TextUtils.isEmpty(str)) {
            return;
        }
        WatchDevice watchDevice = this.devicesByAddress.get(str);
        if (watchDevice == null) {
            return;
        }
        this.devicesByAddress.put(str, watchDevice.withAvailableSpaceMb(i));
        persistLocked();
    }

    private void loadPersistedLocked() {
        Context context = this.appContext;
        if (context == null) {
            return;
        }
        String string = context.getSharedPreferences(PREFS_NAME, 0).getString(KEY_DEVICES, null);
        if (TextUtils.isEmpty(string)) {
            return;
        }
        try {
            JSONArray jSONArray = new JSONArray(string);
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("address", null);
                    if (!TextUtils.isEmpty(optString) && !this.devicesByAddress.containsKey(optString)) {
                        String optString2 = optJSONObject.optString("displayName", optString);
                        String optString3 = optJSONObject.optString("uid", null);
                        String optString4 = optJSONObject.optString("serialNumber", null);
                        ArrayList arrayList = new ArrayList();
                        arrayList.add(optString);
                        String optString5 = optJSONObject.optString("fingerprintName", null);
                        if (!TextUtils.isEmpty(optString5)) {
                            arrayList.add(optString5);
                        }
                        String str = optString3;
                        if (TextUtils.isEmpty(optString2)) {
                            optString2 = optString;
                        }
                        if (TextUtils.isEmpty(str)) {
                            str = null;
                        }
                        putLocked(new WatchDevice(null, optString, optString2, str, TextUtils.isEmpty(optString4) ? null : optString4, "Saved", -1, -1, false, false, false, false, true, true, true, optJSONObject.optInt("installedAppsCount", -1), optJSONObject.optInt("installedWatchfacesCount", -1), arrayList));
                    }
                }
            }
        } catch (JSONException unused) {
        }
    }

    private void persistLocked() {
        Context context = this.appContext;
        if (context == null) {
            return;
        }
        JSONArray jSONArray = new JSONArray();
        for (WatchDevice watchDevice : this.devicesByAddress.values()) {
            if (!TextUtils.isEmpty(watchDevice.getAddress())) {
                JSONObject jSONObject = new JSONObject();
                try {
                    jSONObject.put("address", watchDevice.getAddress());
                    jSONObject.put("displayName", watchDevice.getDisplayName());
                    jSONObject.put("uid", watchDevice.getUid());
                    jSONObject.put("serialNumber", watchDevice.getSerialNumber());
                    jSONObject.put("installedAppsCount", watchDevice.getInstalledAppsCount());
                    jSONObject.put("installedWatchfacesCount", watchDevice.getInstalledWatchfacesCount());
                    if (!watchDevice.getFingerprints().isEmpty()) {
                        jSONObject.put("fingerprintName", watchDevice.getFingerprints().get(watchDevice.getFingerprints().size() - 1));
                    }
                    jSONArray.put(jSONObject);
                } catch (JSONException unused) {
                }
            }
        }
        context.getSharedPreferences(PREFS_NAME, 0).edit().putString(KEY_DEVICES, jSONArray.toString()).apply();
    }

    private String contentKey(String str, int i) {
        return str + "#" + i;
    }
}
