package com.wlaz;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 手表设备管理（本地）。
 * 记录连过的每块手表的 UDID / 名称 / 型号，供一键签名时挑选目标设备；
 * 首次连上某块表时自动登记，之后可一键同步到 AGC 并申请 Profile。
 */
public class DeviceStore {

    private static final String PREFS = "wl_devices";

    public static class Dev {
        public String udid = "";
        public String name = "";
        public String model = "";
        public String mac = "";
        public long lastSeen = 0;
        /** 是否已经同步进 AGC 调试设备列表 */
        public boolean inAgc = false;
        public String agcId = "";
    }

    public static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String udid) {
        return "dev_" + (udid == null ? "" : udid.trim().toUpperCase(Locale.US));
    }

    public static List<Dev> all(Context c) {
        SharedPreferences s = p(c);
        Map<String, ?> m = s.getAll();
        ArrayList<Dev> out = new ArrayList<Dev>();
        for (Map.Entry<String, ?> e : m.entrySet()) {
            if (!e.getKey().startsWith("dev_")) continue;
            String raw = String.valueOf(e.getValue());
            Dev d = decode(raw);
            if (d != null) out.add(d);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Dev>() {
            @Override
            public int compare(Dev a, Dev b) {
                return Long.compare(b.lastSeen, a.lastSeen);
            }
        });
        return out;
    }

    private static Dev decode(String raw) {
        Map<String, String> kv = new LinkedHashMap<String, String>();
        for (String line : raw.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            kv.put(line.substring(0, i), line.substring(i + 1));
        }
        Dev d = new Dev();
        d.udid = val(kv, "udid");
        if (d.udid.length() == 0) return null;
        d.name = val(kv, "name");
        d.model = val(kv, "model");
        d.mac = val(kv, "mac");
        d.lastSeen = Long.parseLong(val(kv, "last").length() == 0 ? "0" : val(kv, "last"));
        d.inAgc = "1".equals(val(kv, "agc"));
        d.agcId = val(kv, "agcid");
        return d;
    }

    private static String val(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }

    private static String encode(Dev d) {
        return "udid=" + d.udid + "\nname=" + d.name + "\nmodel=" + d.model
                + "\nmac=" + d.mac + "\nlast=" + d.lastSeen
                + "\nagc=" + (d.inAgc ? "1" : "0") + "\nagcid=" + d.agcId;
    }

    public static Dev get(Context c, String udid) {
        String raw = p(c).getString(key(udid), "");
        return raw.length() == 0 ? null : decode(raw);
    }

    public static void put(Context c, Dev d) {
        if (d == null || d.udid.length() == 0) return;
        p(c).edit().putString(key(d.udid), encode(d)).apply();
    }

    /**
     * 连接手表后自动登记。同名 UDID 只更新最后在线时间与名称，不重复添加。
     * @return true 表示这是一块新表
     */
    public static boolean touch(Context c, String udid, String name, String model, String mac) {
        if (TextUtils.isEmpty(udid)) return false;
        Dev old = get(c, udid);
        boolean fresh = old == null;
        Dev d = old == null ? new Dev() : old;
        d.udid = udid.trim().toUpperCase(Locale.US);
        if (!TextUtils.isEmpty(name)) d.name = name;
        if (!TextUtils.isEmpty(model)) d.model = model;
        if (!TextUtils.isEmpty(mac)) d.mac = mac;
        d.lastSeen = System.currentTimeMillis();
        put(c, d);
        return fresh;
    }

    public static void markAgc(Context c, String udid, String agcId) {
        Dev d = get(c, udid);
        if (d == null) return;
        d.inAgc = true;
        d.agcId = agcId;
        put(c, d);
    }

    public static void remove(Context c, String udid) {
        p(c).edit().remove(key(udid)).apply();
    }

    public static void clear(Context c) {
        SharedPreferences s = p(c);
        SharedPreferences.Editor e = s.edit();
        for (String k : s.getAll().keySet()) if (k.startsWith("dev_")) e.remove(k);
        e.apply();
    }
}