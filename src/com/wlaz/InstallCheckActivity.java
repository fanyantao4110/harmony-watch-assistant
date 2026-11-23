package com.wlaz;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;

/**
 * 安装链路自检：逐步验证
 *   1) 缓存目录里有签名包
 *   2) HapProvider 能用 content:// 把文件读回来（长度一致）
 *   3) 原版 canInstall 是否接受这个包
 *   4) 触摸到 startInstall 时的异常
 * 手机打开：am start -n com.wlaz/.InstallCheckActivity
 */
public class InstallCheckActivity extends Activity {

    private TextView tv;
    private final StringBuilder s = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        tv = new TextView(this);
        tv.setTextSize(11);
        tv.setPadding(16, 16, 16, 16);
        sv.addView(tv);
        setContentView(sv);
        new Thread(new Runnable() {
            @Override
            public void run() {
                run0();
            }
        }).start();
    }

    private void say(final String m) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                s.append(m).append('\n');
                tv.setText(s.toString());
            }
        });
    }

    private void run0() {
        try {
            File dir = new File(getCacheDir(), "hap");
            say("缓存目录: " + dir);
            File hap = new File(dir, "wl_signed.hap");
            if (!hap.exists()) {
                File[] fs = dir.listFiles();
                hap = null;
                if (fs != null) {
                    for (File f : fs) {
                        if (f.getName().endsWith(".hap")) hap = f;
                    }
                }
            }
            if (hap == null || !hap.exists()) {
                say("× 缓存里没有 .hap，请先在应用详情里执行一次安装来生成");
                return;
            }
            say("✓ 找到包: " + hap.getName() + "  " + hap.length() + " 字节");

            // 2) Provider 读回
            Uri uri = HapProvider.uriFor(hap);
            say("uri = " + uri);
            long n = 0;
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    say("× openInputStream 返回 null");
                    return;
                }
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) n += k;
                say("✓ Provider 读回 " + n + " 字节，与原文件" + (n == hap.length() ? "一致" : "不一致！"));
                if (n != hap.length()) return;
            } catch (Throwable t) {
                say("× Provider 打开失败: " + t);
                return;
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignore) {
                }
            }

            // 3) 找已连接设备
            Bridge.init(this);
            if (!Bridge.ready()) {
                say("× Bridge 未就绪: " + Bridge.error());
                return;
            }
            String addr = null;
            try {
                java.util.List<com.watch.assistant.bridge.WatchDevice> devs = Bridge.devices();
                for (com.watch.assistant.bridge.WatchDevice d : devs) {
                    String a = d.getAddress();
                    if (a != null && a.length() > 5) {
                        addr = a;
                        break;
                    }
                }
            } catch (Throwable t) {
                say("! 读设备列表异常: " + t);
            }
            if (addr == null) {
                say("× 没有已连接的手表，无法测试 canInstall");
                return;
            }
            say("使用设备: " + addr);

            // 4) canInstall
            boolean can = Bridge.canInstall(addr, uri, this);
            say((can ? "✓" : "×") + " canInstall = " + can + (can ? "" : "  原因: " + Bridge.error()));
        } catch (Throwable t) {
            say("× 自检异常: " + t);
            t.printStackTrace();
        }
    }
}