package com.wlaz;

import android.content.Context;
import android.text.TextUtils;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * 手表 UDID —— 严格按原版腕连 3.4 的算法：
 *
 *   WatchDeviceIdentity.buildUid(serialNumber, address)
 *     = sha256Hex( serialNumber.trim().toUpperCase()
 *                  + address.replace("\"","").replace(":","").trim().toUpperCase() )
 *
 * 返回值是 64 位大写十六进制串，正好是 AGC 调试设备需要的 UDID 形态。
 * 注意：这不是序列号本身，也不是老版本里 in[5] 的 getUid() 直接返回的原始值。
 */
public class WatchUdid {

    /** 按原版算法计算 UDID；任一参数为空则返回 null（原版同样返回 null） */
    public static String build(String serial, String address) {
        if (TextUtils.isEmpty(serial) || TextUtils.isEmpty(address)) return null;
        String sn = serial.trim().toUpperCase(Locale.US);
        String mac = address.replace("\"", "").replace(":", "").trim().toUpperCase(Locale.US);
        if (sn.length() == 0 || mac.length() == 0) return null;
        return sha256Hex(sn + mac);
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02X", x));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取某台已连接手表的 UDID。
     * 优先用原版 WatchDevice.getUid()（原版已算好并缓存在 store 里），
     * 拿不到再自己按 buildUid 兜底算一遍。
     */
    public static String of(Context c, String address) {
        try {
            Bridge.init(c);
            if (!Bridge.ready()) return "";
            Object[] in = Bridge.info(address);
            if (in == null) return "";

            // 1) 原版 WatchDevice.getUid()
            String uid = bridgeUid(c, address);
            if (!TextUtils.isEmpty(uid)) return uid.trim();

            // 2) 兜底：序列号(4) + 地址
            String sn = in.length > 4 && in[4] != null ? String.valueOf(in[4]).trim() : "";
            String built = build(sn, address);
            if (built != null) return built;

            // 3) 最后才用原版 getUid 的原始值（in[5]），仅作兼容
            String raw = in.length > 5 && in[5] != null ? String.valueOf(in[5]).trim() : "";
            return raw;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 通过反射拿原版 WatchDevice 对象上的 uid 字段 */
    private static String bridgeUid(Context c, String address) {
        try {
            Object dev = Bridge.deviceOf(address);
            if (dev == null) return "";
            Object v = dev.getClass().getMethod("getUid").invoke(dev);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }
}