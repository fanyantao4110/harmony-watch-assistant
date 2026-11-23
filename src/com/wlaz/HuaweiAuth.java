package com.wlaz;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 华为账号 / 已知设备 / 配对凭据的本地存储，逻辑照腕连 3.4 */
public class HuaweiAuth {

    public static final String PREFS = "watch_huawei_auth";
    private static final String K_ACC = "huawei_account_";
    private static final String K_NAME = "known_device_name_";
    private static final String K_AUTH = "auth_key_";
    private static final String K_PIN = "pin_";

    public static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 设备地址统一格式：去掉冒号并大写。
     * 必须和腕连原版 HuaweiAuthStore.normalizeAddress 完全一致，
     * 否则账号会写到一个 key、连接时从另一个 key 读取，手表收到的就是空账号。
     */
    public static String norm(String mac) {
        if (mac == null) return "";
        return mac.trim().replace(":", "").toUpperCase(Locale.US);
    }

    public static String account(Context c, String mac) {
        String m = norm(mac);
        SharedPreferences s = p(c);
        String v = s.getString(K_ACC + m, null);
        if (TextUtils.isEmpty(v)) {
            // 兼容早期版本把带冒号地址当 key 写入的数据
            String legacy = s.getString(K_ACC + mac.trim().toUpperCase(Locale.US), null);
            if (!TextUtils.isEmpty(legacy)) {
                s.edit().putString(K_ACC + m, legacy.trim()).remove(K_ACC + mac.trim().toUpperCase(Locale.US)).apply();
                v = legacy;
            }
        }
        return v == null ? "" : v.trim();
    }

    public static void putAccount(Context c, String mac, String acc) {
        String k = K_ACC + norm(mac);
        if (TextUtils.isEmpty(acc)) p(c).edit().remove(k).apply();
        else p(c).edit().putString(k, acc.trim()).apply();
    }

    /** 旧版本遗留的错误键（带冒号、或把设备名当成了地址）统一搬家 */
    public static void migrate(Context c) {
        SharedPreferences s = p(c);
        SharedPreferences.Editor e = s.edit();
        for (String k : s.getAll().keySet()) {
            String v = s.getString(k, null);
            if (v == null) continue;
            String prefix;
            if (k.startsWith(K_ACC)) prefix = K_ACC;
            else if (k.startsWith(K_NAME)) prefix = K_NAME;
            else if (k.startsWith(K_AUTH)) prefix = K_AUTH;
            else if (k.startsWith(K_PIN)) prefix = K_PIN;
            else continue;
            String raw = k.substring(prefix.length());
            if (!looksLikeAddress(raw)) {
                // 键不是设备地址（例如早期版本把设备名当地址写进来），整条丢弃
                e.remove(k);
                continue;
            }
            if (prefix.equals(K_ACC) && !v.matches("\\d{1,19}")) {
                e.remove(k);
                continue;
            }
            String fixed = norm(raw);
            if (!fixed.equals(raw)) {
                String std = prefix + fixed;
                if (!s.contains(std)) e.putString(std, v);
                e.remove(k);
            }
        }
        e.apply();
    }

    public static boolean looksLikeAddress(String s) {
        if (TextUtils.isEmpty(s)) return false;
        String t = s.trim().replace(":", "").toUpperCase(Locale.US);
        return t.matches("[0-9A-F]{12}");
    }

    public static void putKnown(Context c, String mac, String name) {
        if (TextUtils.isEmpty(mac) || TextUtils.isEmpty(name)) return;
        p(c).edit().putString(K_NAME + norm(mac), name.trim()).apply();
    }

    public static boolean isKnown(Context c, String mac) {
        String m = norm(mac);
        SharedPreferences s = p(c);
        return s.contains(K_NAME + m) || s.contains(K_ACC + m) || s.contains(K_AUTH + m);
    }

    public static Map<String, String> knownDevices(Context c) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        SharedPreferences s = p(c);
        for (String key : s.getAll().keySet()) {
            String mac = null;
            if (key.startsWith(K_NAME)) mac = key.substring(K_NAME.length());
            else if (key.startsWith(K_ACC)) mac = key.substring(K_ACC.length());
            else if (key.startsWith(K_AUTH)) mac = key.substring(K_AUTH.length());
            if (mac == null || mac.length() == 0) continue;
            String n = s.getString(K_NAME + mac, null);
            if (TextUtils.isEmpty(n)) n = "HUAWEI Watch";
            out.put(mac, n);
        }
        return out;
    }

    /** 清掉早期版本误存的无名设备，只留名字明确的设备 */
    public static void cleanupBogus(Context c) {
        SharedPreferences s = p(c);
        SharedPreferences.Editor e = s.edit();
        for (String k : s.getAll().keySet()) {
            if (!k.startsWith(K_NAME)) continue;
            String v = s.getString(k, "");
            if (v == null) continue;
            if (v.startsWith("未命名设备") || v.equals("HUAWEI Watch")) {
                String mac = k.substring(K_NAME.length());
                if (!s.contains(K_ACC + mac) && !s.contains(K_AUTH + mac)) e.remove(k);
            }
        }
        e.apply();
    }

    public static void clearPairingCredentials(Context c, String mac) {
        String m = norm(mac);
        p(c).edit().remove(K_AUTH + m).remove(K_PIN + m).apply();
    }

    public static String mask(String s) {
        if (s == null) return "";
        if (s.length() <= 6) return s;
        return s.substring(0, 3) + "..." + s.substring(s.length() - 3);
    }

    public static boolean validId(String s) {
        return s != null && s.matches("\\d{1,19}");
    }

    /** 原版只看名字里的这些关键词 */
    public static boolean looksLikeHuawei(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String u = name.toUpperCase(Locale.US);
        return u.contains("HUAWEI") || u.contains("HONOR") || u.contains("WATCH")
                || u.contains("BAND") || u.contains("GT") || u.contains("FIT");
    }

    /**
     * 原版逻辑：Cookie 里的 x-hd-grey 做 URL 解码后匹配 agcTeamId-数字；
     * 取不到就整段 Cookie 再匹配一次。
     */
    public static String extractHuaweiId(String cookie) {
        if (TextUtils.isEmpty(cookie)) return null;
        Pattern grey = Pattern.compile("(?:^|;\\s*)x-hd-grey=([^;]+)");
        Pattern team = Pattern.compile("agcTeamId-(\\d+)");
        Matcher m = grey.matcher(cookie);
        while (m.find()) {
            String v = m.group(1);
            if (TextUtils.isEmpty(v)) continue;
            try {
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignore) {
            }
            Matcher t = team.matcher(v);
            if (t.find()) return t.group(1);
        }
        Matcher t2 = team.matcher(cookie);
        if (t2.find()) return t2.group(1);
        return null;
    }
}