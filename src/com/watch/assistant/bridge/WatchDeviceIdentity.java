package com.watch.assistant.bridge;

import android.text.TextUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/* loaded from: classes4.dex */
public final class WatchDeviceIdentity {
    private WatchDeviceIdentity() {
    }

    public static String buildUid(String str, String str2) {
        if (!TextUtils.isEmpty(str) && !TextUtils.isEmpty(str2)) {
            String upperCase = str.trim().toUpperCase(Locale.US);
            String upperCase2 = str2.replace("\"", "").replace(":", "").trim().toUpperCase(Locale.US);
            if (!TextUtils.isEmpty(upperCase) && !TextUtils.isEmpty(upperCase2)) {
                return sha256Hex(upperCase + upperCase2);
            }
        }
        return null;
    }

    private static String sha256Hex(String str) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02X", Byte.valueOf(b)));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
