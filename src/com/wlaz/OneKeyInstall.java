package com.wlaz;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 一键安装：选 HAP → 在线申请 Profile → 重签名 → 通过已连接的手表安装。
 *
 * 前置：已在「我的 → DevEco 账号」登录开发者账号；手表已通过蓝牙连上（拿得到 UDID）。
 */
public class OneKeyInstall {

    public interface Cb {
        void step(String text);

        void done(boolean ok, String message, File out);
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public OneKeyInstall(Context c) {
        this.ctx = c.getApplicationContext();
    }

    private void say(Cb cb, String s) {
        if (cb == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.step(s);
            }
        });
    }

    // ---- 当前连接的手表（由 MainActivity 在连接/断开时维护） ----
    private static String gAddr = "";
    private static String gName = "";

    public static void setCurrent(String addr, String name) {
        gAddr = addr == null ? "" : addr;
        gName = name == null ? "" : name;
    }

    public static String currentAddr() {
        return gAddr;
    }

    public static String currentName() {
        return gName;
    }

    /** 内置私钥（与 AGC 调试证书成对） */
    public static String keyPem(Context c) throws Exception {
        return readAsset(c, "wl_key.pem");
    }

    /** 内置华为根 + 中间 CA */
    public static String caPem(Context c) throws Exception {
        return readAsset(c, "hsu_ca.pem");
    }

    private static String readAsset(Context c, String name) throws Exception {
        InputStream in = c.getAssets().open(name);
        byte[] d = HapSigner.readAll(in);
        return new String(d, "UTF-8");
    }

    /**
     * 完整流程（后台线程执行）。
     *
     * @param srcHap    源 HAP
     * @param udid      目标手表 UDID
     * @param watchName 手表名（登记用）
     */
    public void run(final File srcHap, final String udid, final String watchName, final Cb cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder log = new StringBuilder();
                File out = null;
                try {
                    if (!AgcApi.hasCredential(ctx))
                        throw new Exception("请先在「我的 → DevEco 账号」登录开发者账号");
                    if (udid == null || udid.trim().length() == 0)
                        throw new Exception("还没拿到手表 UDID，请先连接手表并读取设备信息");

                    say(cb, "① 在线申请 Profile…");
                    byte[] p7b = AgcApi.provisionForWatch(ctx, udid, watchName, log);
                    DeviceStore.markAgc(ctx, udid, AgcApi.p(ctx).getString("profile_id", ""));

                    say(cb, "② 重签名…");
                    File dir = Dl.signedDir(ctx);
                    if (!dir.exists()) dir.mkdirs();
                    String base = srcHap.getName();
                    int dot = base.lastIndexOf('.');
                    if (dot > 0) base = base.substring(0, dot);
                    out = new File(dir, base + "_signed.hap");
                    HapSigner.Result r = HapSigner.sign(srcHap, out, p7b, keyPem(ctx), caPem(ctx));
                    log.append(r.message).append('\n');

                    say(cb, "③ 推送到手表并安装…");
                    Bridge.init(ctx);
                    Uri uri = HapProvider.uriFor(out);
                    if (!Bridge.canInstall(udid.length() == 32 ? addrOf(udid) : "", uri, ctx)) {
                        // 原版安装器要求蓝牙地址，而不是 UDID
                        log.append("安装器未接受该包：").append(Bridge.error()).append('\n');
                    }
                    final File fout = out;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.done(true, log.toString(), fout);
                        }
                    });
                } catch (final Exception e) {
                    final File f2 = out;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.done(false, e.getMessage() + "\n" + log, f2);
                        }
                    });
                }
            }
        }).start();
    }

    private static String addrOf(String udid) {
        return udid;
    }

    /** 当前已连接手表的 UDID（原版算法：SHA-256(序列号+MAC)） */
    public static String connectedUdid(Context c, String addr) {
        try {
            Bridge.init(c);
            if (!Bridge.ready()) return "";
            return WatchUdid.of(c, addr);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 连接后自动登记设备：有新表就写入设备管理 */
    public static boolean autoRegister(Context c, String addr) {
        String udid = connectedUdid(c, addr);
        if (udid.length() == 0) return false;
        Object[] in = Bridge.info(addr);
        String name = in == null || in[0] == null ? "" : String.valueOf(in[0]);
        boolean fresh = DeviceStore.touch(c, udid, name, name, addr);
        if (fresh) Bridge.log("DeviceStore 新设备 " + name + " udid=" + udid);
        return fresh;
    }
}