package com.watch.assistant.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/* loaded from: classes4.dex */
public final class HuaweiAuthStore {
    private static final String KEY_AUTH_PREFIX = "auth_key_";
    private static final String KEY_HICHAIN_ID_PREFIX = "hichain_id_";
    private static final String KEY_HUAWEI_ACCOUNT_PREFIX = "huawei_account_";
    private static final String KEY_KNOWN_DEVICE_NAME_PREFIX = "known_device_name_";
    private static final String KEY_PIN_PREFIX = "pin_";
    private static final String PREFS_NAME = "watch_huawei_auth";
    private final SharedPreferences prefs;

    public HuaweiAuthStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, 0);
    }

    byte[] getAuthKey(String str) {
        return hexToBytes(this.prefs.getString(KEY_AUTH_PREFIX + normalizeAddress(str), null));
    }

    void putAuthKey(String str, byte[] bArr) {
        String str2 = KEY_AUTH_PREFIX + normalizeAddress(str);
        SharedPreferences.Editor edit = this.prefs.edit();
        if (bArr == null || bArr.length == 0) {
            edit.remove(str2);
        } else {
            edit.putString(str2, bytesToHex(bArr));
        }
        edit.apply();
    }

    byte[] getPin(String str) {
        String string = this.prefs.getString(KEY_PIN_PREFIX + normalizeAddress(str), null);
        if (TextUtils.isEmpty(string)) {
            return null;
        }
        return string.getBytes(StandardCharsets.UTF_8);
    }

    void putPin(String str, String str2) {
        String str3 = KEY_PIN_PREFIX + normalizeAddress(str);
        SharedPreferences.Editor edit = this.prefs.edit();
        if (TextUtils.isEmpty(str2)) {
            edit.remove(str3);
        } else {
            edit.putString(str3, str2);
        }
        edit.apply();
    }

    String getHuaweiAccount(String str) {
        String string = this.prefs.getString(KEY_HUAWEI_ACCOUNT_PREFIX + normalizeAddress(str), "");
        return string != null ? string.trim() : "";
    }

    public void putHuaweiAccount(String str, String str2) {
        String str3 = KEY_HUAWEI_ACCOUNT_PREFIX + normalizeAddress(str);
        SharedPreferences.Editor edit = this.prefs.edit();
        if (TextUtils.isEmpty(str2)) {
            edit.remove(str3);
        } else {
            edit.putString(str3, str2.trim());
        }
        edit.apply();
    }

    void clearPairingCredentials(String str) {
        String normalizeAddress = normalizeAddress(str);
        this.prefs.edit().remove(KEY_AUTH_PREFIX + normalizeAddress).remove(KEY_PIN_PREFIX + normalizeAddress).apply();
    }

    void putKnownDevice(String str, String str2) {
        if (TextUtils.isEmpty(str) || TextUtils.isEmpty(str2)) {
            return;
        }
        this.prefs.edit().putString(KEY_KNOWN_DEVICE_NAME_PREFIX + normalizeAddress(str), str2.trim()).apply();
    }

    boolean isKnownDevice(String str) {
        String normalizeAddress = normalizeAddress(str);
        if (this.prefs.contains(KEY_KNOWN_DEVICE_NAME_PREFIX + normalizeAddress)) {
            return true;
        }
        if (this.prefs.contains(KEY_HUAWEI_ACCOUNT_PREFIX + normalizeAddress)) {
            return true;
        }
        SharedPreferences sharedPreferences = this.prefs;
        StringBuilder sb = new StringBuilder(KEY_AUTH_PREFIX);
        sb.append(normalizeAddress);
        return sharedPreferences.contains(sb.toString());
    }

    Map<String, String> getKnownDevices() {
        LinkedHashMap linkedHashMap = new LinkedHashMap();
        Iterator<String> it = this.prefs.getAll().keySet().iterator();
        while (it.hasNext()) {
            String normalizedAddressFromKnownKey = normalizedAddressFromKnownKey(it.next());
            String restoreAddress = restoreAddress(normalizedAddressFromKnownKey);
            if (restoreAddress != null) {
                String string = this.prefs.getString(KEY_KNOWN_DEVICE_NAME_PREFIX + normalizedAddressFromKnownKey, null);
                if (TextUtils.isEmpty(string)) {
                    string = "HUAWEI Watch";
                }
                linkedHashMap.put(restoreAddress, string);
            }
        }
        return linkedHashMap;
    }

    String getOrCreateHiChainId(String str) {
        String str2 = KEY_HICHAIN_ID_PREFIX + normalizeAddress(str);
        String string = this.prefs.getString(str2, null);
        if (!TextUtils.isEmpty(string)) {
            return string;
        }
        byte[] bArr = new byte[16];
        new SecureRandom().nextBytes(bArr);
        String bytesToHex = bytesToHex(bArr);
        this.prefs.edit().putString(str2, bytesToHex).apply();
        return bytesToHex;
    }

    private static String normalizeAddress(String str) {
        return TextUtils.isEmpty(str) ? "unknown" : str.replace(":", "").toUpperCase(Locale.US);
    }

    private static String normalizedAddressFromKnownKey(String str) {
        String[] strArr = {KEY_KNOWN_DEVICE_NAME_PREFIX, KEY_HUAWEI_ACCOUNT_PREFIX, KEY_AUTH_PREFIX};
        for (int i = 0; i < 3; i++) {
            String str2 = strArr[i];
            if (str.startsWith(str2)) {
                return str.substring(str2.length());
            }
        }
        return null;
    }

    private static String restoreAddress(String str) {
        if (TextUtils.isEmpty(str) || !str.matches("[0-9A-F]{12}")) {
            return null;
        }
        StringBuilder sb = new StringBuilder(17);
        int i = 0;
        while (i < str.length()) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            int i2 = i + 2;
            sb.append((CharSequence) str, i, i2);
            i = i2;
        }
        return sb.toString();
    }

    private static String bytesToHex(byte[] bArr) {
        StringBuilder sb = new StringBuilder(bArr.length * 2);
        for (byte b : bArr) {
            sb.append(String.format(Locale.US, "%02x", Byte.valueOf(b)));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String str) {
        if (TextUtils.isEmpty(str) || str.length() % 2 != 0) {
            return null;
        }
        int length = str.length() / 2;
        byte[] bArr = new byte[length];
        for (int i = 0; i < length; i++) {
            int i2 = i * 2;
            try {
                bArr[i] = (byte) Integer.parseInt(str.substring(i2, i2 + 2), 16);
            } catch (NumberFormatException unused) {
                return null;
            }
        }
        return bArr;
    }
}
