package com.wlaz;

import android.content.Context;

import com.watch.assistant.bridge.HuaweiAuthStore;
import com.watch.assistant.bridge.NativeHuaweiWatchBackend;
import com.watch.assistant.bridge.WatchBridge;
import com.watch.assistant.bridge.WatchBridgeProvider;
import com.watch.assistant.bridge.WatchDevice;
import com.watch.assistant.bridge.WatchInstalledItem;
import com.watch.assistant.bridge.WatchfaceReplace;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 手表后端入口。
 *
 * ★ 历史：这里原来是 DexClassLoader 加载 assets/dex/base1..4.dex
 *   （腕连原版 4 个 dex，约 14MB），再反射调它自己的 NativeHuaweiWatchBackend。
 *   现在原版反编译源码已经整体搬进本工程（com.watch.assistant.bridge 包，
 *   包名保持不变以便对外契约不变），所以**不再加载任何 dex**，
 *   直接编译期引用，体积减 14MB，也免掉反射带来的类型不匹配问题。
 *
 * FE86 协议、hichain 认证、BOND/ACCOUNT/电量/应用列表全部仍是原版实现。
 */
public class Bridge {

    private static WatchBridge bridge;
    private static NativeHuaweiWatchBackend backend;
    private static Context app;
    private static String error = "未初始化";

    public static synchronized void init(Context ctx) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (bridge != null) return;
        try {
            // WatchBridgeProvider.get 内部就是 new NativeWatchBridge() + initialize()
            bridge = WatchBridgeProvider.get(app);
            backend = NativeHuaweiWatchBackend.lastInstance();
            error = "";
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            bridge = null;
            backend = null;
        }
    }

    public static boolean ready() {
        return bridge != null;
    }

    public static String error() {
        return error;
    }

    /** 原版 resolveConnectableDevice + connect */
    public static boolean connect(Context ctx, String address) {
        init(ctx);
        if (!ready()) return false;
        // 前置校验：只允许连接本机扫描/配对过、或系统已配对的设备。
        // 否则拿一个陈旧失效的 MAC 去调原版 resolveConnectableDevice，会直接卡死在那里不返回，
        // 表现为“连上的是上一块手表 / 一直转圈 / 日志断在半截”。
        try {
            boolean known = HuaweiAuth.isKnown(ctx, address);
            boolean bonded = false;
            try {
                android.bluetooth.BluetoothManager bm0 = (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                android.bluetooth.BluetoothAdapter ad0 = bm0 == null ? null : bm0.getAdapter();
                if (ad0 != null) bonded = ad0.getRemoteDevice(address).getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED;
            } catch (Throwable ignore) {
            }
            if (!known && !bonded) {
                error = "该设备不在本机蓝牙记录中，请先扫描并配对";
                log("connect rejected: unknown addr=" + address);
                return false;
            }
        } catch (Throwable ignore) {
        }
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            android.util.Log.i("WlazBridge", "connect addr=[" + address + "] sdk=" + android.os.Build.VERSION.SDK_INT
                    + " adapter=" + (ad != null) + " enabled=" + (ad != null && ad.isEnabled())
                    + " connectPerm=" + (ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0)
                    + " scanPerm=" + (ctx.checkSelfPermission("android.permission.BLUETOOTH_SCAN") == 0)
                    + " finePerm=" + (ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == 0));
            WatchDevice dev = null;
            try {
                dev = bridge.resolveConnectableDevice(address, ctx);
            } catch (Throwable rt) {
                log("resolve threw " + rt.getClass().getSimpleName() + ": " + rt.getMessage() + " addr=" + address);
                dev = null;
            }
            android.util.Log.i("WlazBridge", "resolve result=" + dev);
            if (dev == null) {
                try {
                    WatchDevice cached = bridge.findByAddress(address.toUpperCase());
                    android.util.Log.i("WlazBridge", "cached result=" + cached);
                    if (cached != null) dev = cached;
                } catch (Throwable ignore) {
                }
            }
            if (dev == null) {
                // 原版 resolve 偶发返回 null 时，直接按原版 WatchDevice 构造参数补一个设备再连。
                // 但只允许“确实是这台手机认识的设备”走这条路：系统已配对或者本地登记过。
                // 否则随便一个失效地址都会被塞进后端连接表，导致后面一直取到旧设备信息。
                boolean plausible = false;
                try {
                    android.bluetooth.BluetoothDevice bd0 = ad.getRemoteDevice(address);
                    plausible = bd0 != null && (bd0.getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED
                            || HuaweiAuth.isKnown(ctx, address));
                } catch (Throwable ignore) {
                }
                if (!plausible) {
                    error = "设备不在系统蓝牙列表中，请先扫描并配对";
                    log("connect aborted: implausible addr=" + address);
                    return false;
                }
                try {
                    android.bluetooth.BluetoothDevice bd = ad.getRemoteDevice(address);
                    dev = new WatchDevice(bd, address, "HUAWEI Watch", null, null, "Discovered",
                            -1, -1, false, false, false, false, true, true, true, -1, -1,
                            new java.util.ArrayList<String>());
                    android.util.Log.i("WlazBridge", "fallback WatchDevice=" + dev);
                } catch (Throwable t) {
                    error = "补建设备失败：" + t.getClass().getSimpleName() + " " + t.getMessage();
                }
            }
            if (dev == null) {
                if (error == null || error.length() == 0 || error.equals("未初始化"))
                    error = "设备未进入连接表（resolve null）";
                return false;
            }
            bridge.connect(dev);
            error = "";
            return true;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            error = c.getClass().getSimpleName() + ": " + c.getMessage();
            log("connect failed addr=" + address + " " + error);
            return false;
        }
    }

    public static void disconnect(Context ctx, String address) {
        if (!ready()) {
            log("disconnect skipped: not ready");
            return;
        }
        try {
            WatchDevice dev = bridge.findByAddress(address);
            if (dev == null) {
                log("disconnect: dev null addr=" + address);
                return;
            }
            bridge.disconnect(dev);
            log("disconnect done addr=" + address + " isConnected=" + isConnected(address));
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log("disconnect failed " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    /**
     * 连接某台设备前，把后端里其它“已连接”的已知设备全部断掉。
     * 换手表（Fit2 → GT6）时原版后端会把旧设备一直留在连接表里，
     * 于是 UI 拿到的仍然是旧设备的信息，看起来像“连上了旧的 Fit2”。
     */
    public static void disconnectOthers(Context ctx, String keep) {
        if (!ready()) return;
        try {
            java.util.Map<String, String> known = HuaweiAuth.knownDevices(ctx);
            for (String mac : known.keySet()) {
                if (mac == null || mac.length() == 0) continue;
                if (keep != null && mac.equalsIgnoreCase(norm(keep))) continue;
                if (!isConnected(mac)) continue;
                log("disconnectOthers " + mac + " keep=" + keep);
                disconnect(ctx, mac);
            }
        } catch (Throwable t) {
            log("disconnectOthers failed " + t.getClass().getSimpleName());
        }
    }

    private static WatchDevice device(String address) {
        if (!ready() || address == null) return null;
        try {
            return bridge.findByAddress(address);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 返回 [名称, 电量, 状态, 是否已连接, 序列号, UID, 剩余MB, 应用数, 表盘数] */
    public static Object[] info(String address) {
        WatchDevice d = device(address);
        if (d == null) return null;
        return new Object[]{
                d.getDisplayName() == null ? "HUAWEI Watch" : d.getDisplayName(),
                d.getBatteryLevel(),
                d.getStateLabel() == null ? "" : d.getStateLabel(),
                d.isConnected(),
                d.getSerialNumber() == null ? "" : d.getSerialNumber(),
                d.getUid() == null ? "" : d.getUid(),
                d.getAvailableSpaceMb(),
                d.getInstalledAppsCount(),
                d.getInstalledWatchfacesCount()
        };
    }

    public static void requestApps(String address) {
        if (!ready()) return;
        try {
            WatchDevice d = device(address);
            if (d != null) bridge.requestAppList(d);
        } catch (Throwable ignore) {
        }
    }

    /** 指定类型：[名称, 版本, 状态, 大小]；type 1=应用 2=表盘 */
    public static java.util.List<String[]> installed(String address, int type) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        for (WatchInstalledItem it : items(address, type)) {
            long sz = it.getSizeBytes();
            String size = "";
            if (sz > 0) size = sz >= 1048576L
                    ? String.format(java.util.Locale.US, "%.1f MB", sz / 1048576.0)
                    : (sz / 1024 + " KB");
            out.add(new String[]{
                    it.getTitle() == null ? "?" : it.getTitle(),
                    it.getVersion() == null ? "" : it.getVersion(),
                    it.getStatusLabel() == null ? "" : it.getStatusLabel(),
                    size
            });
        }
        return out;
    }

    public static java.util.List<WatchInstalledItem> items(String address, int type) {
        java.util.ArrayList<WatchInstalledItem> out = new java.util.ArrayList<WatchInstalledItem>();
        if (!ready()) return out;
        if (device(address) == null) return out;
        try {
            List<WatchInstalledItem> l = bridge.getInstalledItems(address, type);
            if (l != null) {
                for (WatchInstalledItem it : l) {
                    if (it == null) continue;
                    int rt = it.getRequestType();
                    if (rt > 0 && rt != type) continue;
                    out.add(it);
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    public static String titleOf(Object it) {
        return it instanceof WatchInstalledItem && ((WatchInstalledItem) it).getTitle() != null
                ? ((WatchInstalledItem) it).getTitle() : "?";
    }

    public static String subOf(Object it) {
        if (!(it instanceof WatchInstalledItem)) return "";
        WatchInstalledItem w = (WatchInstalledItem) it;
        String s = "版本 " + (w.getVersion() == null ? "" : w.getVersion());
        long sz = w.getSizeBytes();
        if (sz > 0) s += "  ·  " + (sz >= 1048576L
                ? String.format(java.util.Locale.US, "%.1f MB", sz / 1048576.0)
                : (sz / 1024 + " KB"));
        return s;
    }

    public static String badgeOf(Object it) {
        if (!(it instanceof WatchInstalledItem)) return "已安装";
        String st = ((WatchInstalledItem) it).getStatusLabel();
        return st == null || st.length() == 0 ? "已安装" : st;
    }

    /** 供 WatchUdid 使用：拿原版 WatchDevice 实例 */
    public static Object deviceOf(String address) {
        return device(address);
    }

    public static boolean deleteItem(String address, Object item) {
        if (!ready()) return false;
        if (!(item instanceof WatchInstalledItem)) {
            error = "item 类型不对：" + (item == null ? "null" : item.getClass().getName());
            return false;
        }
        try {
            WatchDevice d = device(address);
            if (d == null) {
                error = "找不到设备对象 " + safeAddr(address);
                return false;
            }
            bridge.deleteInstalledItem(d, (WatchInstalledItem) item);
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    public static boolean activateItem(String address, Object item) {
        if (!ready()) return false;
        if (!(item instanceof WatchInstalledItem)) {
            error = "item 类型不对：" + (item == null ? "null" : item.getClass().getName());
            return false;
        }
        try {
            WatchDevice d = device(address);
            if (d == null) {
                error = "找不到设备对象 " + safeAddr(address);
                return false;
            }
            bridge.activateInstalledItem(d, (WatchInstalledItem) item);
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    public static boolean canInstall(String address, android.net.Uri uri, Context ctx) {
        if (!ready()) return false;
        try {
            WatchDevice d = device(address);
            if (d == null) return false;
            return bridge.canInstall(d, uri, null, ctx);
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 表盘替换法（鸿蒙 6.0.0.32+）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 这条表盘能不能当替换目标。
     *
     * 严格对齐 WristMirror FacePolicy.eligible()：
     * 必须带「可删除」位(0x04)，且不含预置/视频/相册/试用/万花筒位，expandType 为 0 或缺省。
     * 实测放宽放行预置表盘会被固件回 100014 / need=0，所以这里不再放宽。
     */
    public static boolean isWatchfaceReplaceable(Object item) {
        return item instanceof WatchInstalledItem && WatchfaceReplace.isReplaceable((WatchInstalledItem) item);
    }

    /** 宽松判定，仅用于给界面提示「这表盘属于哪种类型」，不能用于放行替换 */
    public static boolean isWatchfaceReplaceableLenient(Object item) {
        return item instanceof WatchInstalledItem && WatchfaceReplace.isReplaceableLenient((WatchInstalledItem) item);
    }

    /** 不能替换的原因（给界面提示） */
    public static String replaceabilityOf(Object item) {
        if (!(item instanceof WatchInstalledItem)) return "无效";
        return WatchfaceReplace.describe((WatchInstalledItem) item);
    }

    /** 预测替换后的版本号，给确认弹窗展示 */
    public static String replacementVersionFor(Object item) {
        if (!(item instanceof WatchInstalledItem)) return "";
        return WatchfaceReplace.nextVersion(((WatchInstalledItem) item).getVersion());
    }

    /**
     * 用替换法安装表盘。
     *
     * @param address 设备地址
     * @param target  被替换的目标表盘（列表里的那一项）
     * @param uri     本地 .hwt
     */
    public static boolean installWatchfaceByReplace(String address, Object target, android.net.Uri uri, Context ctx) {
        if (!ready()) {
            error = "bridge not ready";
            return false;
        }
        if (!(target instanceof WatchInstalledItem)) {
            error = "目标表盘无效";
            return false;
        }
        try {
            WatchDevice d = device(address);
            if (d == null) {
                error = "找不到设备对象 " + safeAddr(address);
                return false;
            }
            if (backend != null) {
                backend.startWatchfaceReplacement(d, (WatchInstalledItem) target, uri);
            } else {
                bridge.startInstall(d, uri, null, ctx);
            }
            error = "";
            return true;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            error = c.getClass().getSimpleName() + ": " + c.getMessage();
            return false;
        }
    }

    public static boolean install(String address, android.net.Uri uri, Context ctx) {
        if (!ready()) {
            error = "bridge not ready";
            return false;
        }
        try {
            WatchDevice d = device(address);
            if (d == null) {
                error = "找不到设备对象 " + safeAddr(address);
                return false;
            }
            // 先像原版那样检查包是否被引擎认作可安装（signed.bin / 表盘）
            try {
                if (!bridge.canInstall(d, uri, null, ctx)) {
                    error = "安装引擎不认这个包（canInstall=false）：可能是 signed.bin 格式/签名未被识别，或设备会话未就绪";
                    return false;
                }
            } catch (Throwable ignore) {
            }
            bridge.startInstall(d, uri, null, ctx);
            error = "";
            return true;
        } catch (Throwable t) {
            Throwable c = t;
            while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) {
                c = c.getCause();
            }
            error = c.getClass().getSimpleName() + ": " + c.getMessage();
            return false;
        }
    }

    private static String safeAddr(String a) {
        if (a == null || a.length() < 5) return "";
        return "**:**:**:**:" + a.substring(a.length() - 5);
    }

    private static final String PREFS = "watch_huawei_auth";

    /** 和腕连原版 HuaweiAuthStore.normalizeAddress 一模一样：去冒号 + 大写 */
    public static String norm(String mac) {
        if (mac == null) return "";
        return mac.trim().replace(":", "").toUpperCase(java.util.Locale.US);
    }

    /**
     * 把账号写进 HuaweiAuthStore，配对/重连时才会随 ACCOUNT TLV 下发给手表。
     * putHuaweiAccount 是包私有，跨包仍要反射（类已在本工程内，不再依赖 DexClassLoader）。
     */
    public static boolean setAccount(String address, String account, String watchName) {
        if (app == null) return false;
        String acc = account == null ? "" : account.trim();
        // 直接按原版键格式落盘，保证一定生效
        android.content.SharedPreferences.Editor e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String key = "huawei_account_" + norm(address);
        if (acc.length() == 0) e.remove(key);
        else {
            e.putString(key, acc);
            // 清掉早期带冒号键，避免两处账号不一致
            e.remove("huawei_account_" + address.toUpperCase(java.util.Locale.US));
        }
        e.apply();
        log("setAccount addr=" + address + " norm=" + norm(address) + " acc=" + acc + " len=" + acc.length());
        try {
            HuaweiAuthStore store = new HuaweiAuthStore(app);
            java.lang.reflect.Method m = HuaweiAuthStore.class.getDeclaredMethod("putHuaweiAccount", String.class, String.class);
            m.setAccessible(true);
            m.invoke(store, address, acc);
            log("setAccount via store ok addr=" + address);
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            log("setAccount reflect failed " + error + "（已直接写入 prefs，仍然生效）");
            return acc.length() > 0;
        }
    }

    /** 读取已保存的账号（配对时真正发给手表的那个值） */
    public static String getAccount(String address) {
        if (app == null) return "";
        String v = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("huawei_account_" + norm(address), "");
        return v == null ? "" : v.trim();
    }

    /** 注意 requestNativeStatus 实现在 NativeHuaweiWatchBackend 里，不在 bridge 接口上 */
    public static void requestStatus(String address) {
        if (backend == null) return;
        try {
            java.lang.reflect.Method m = NativeHuaweiWatchBackend.class.getDeclaredMethod("requestNativeStatus", String.class);
            m.setAccessible(true);
            Object r = m.invoke(backend, address);
            log("requestStatus addr=" + address + " result=" + r);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            error = c.getClass().getSimpleName() + ": " + c.getMessage();
            log("requestStatus failed " + error);
        }
    }

    public static boolean isConnected(String address) {
        WatchDevice d = device(address);
        return d != null && d.isConnected();
    }

    public static void log(String s) {
        android.util.Log.i("WlazBridge", s);
        try {
            if (app == null) return;
            File dir = app.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "wlaz.log");
            if (f.length() > 262144) f.delete();
            FileOutputStream fo = new FileOutputStream(f, true);
            fo.write((new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + " " + s + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignore) {
        }
    }

    public static int deviceCount() {
        if (!ready()) return -1;
        try {
            List<WatchDevice> l = bridge.getDevices();
            return l == null ? 0 : l.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 全部已配对/已知设备（原版 getDevices 的直通） */
    public static List<WatchDevice> devices() {
        if (!ready()) return new java.util.ArrayList<WatchDevice>();
        try {
            List<WatchDevice> l = bridge.getDevices();
            return l == null ? new java.util.ArrayList<WatchDevice>() : l;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return new java.util.ArrayList<WatchDevice>();
        }
    }
}