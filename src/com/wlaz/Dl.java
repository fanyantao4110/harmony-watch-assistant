package com.wlaz;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Dl {

    public static void askPerm(Activity a) {
        if (Build.VERSION.SDK_INT <= 28
                && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9);
        }
        // 注意：这里【不要】自动跳「所有文件访问」设置页。
        // 签名流程本身不依赖公共目录写权限（signedDir 会自动回退到应用专属目录），
        // 自动跳设置会打断用户操作。需要公共目录时由界面按钮显式触发 askAllFiles()。
    }

    /** 悬赏提交收件邮箱，与网页版 site/index.html 的 MAIL 保持一致 */
    public static final String MAIL = "harmonywatchapp@163.com";
    /** 核对通过后的奖励金额，与网页版一致 */
    public static final String REWARD = "0.1 元";

    public static void pick(final Activity act, final AppItem a) {
        if (a == null || a.pkgs.isEmpty()) {
            // 悬赏应用：网页版点「提交」会弹「提交已签名 hap」对话框，
            // 里面写明邮箱、要求、奖励，点「确认提交」才跳转邮件。
            showBountyDialog(act, a);
            return;
        }
        // 未连接手表时，先引导去连接，不进入下载/签名流程
        if (!ensureConnected(act)) return;
        final String[] labels = new String[a.pkgs.size()];
        for (int i = 0; i < a.pkgs.size(); i++) labels[i] = a.pkgs.get(i).label();
        new AlertDialog.Builder(act)
                .setTitle("选择设备")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        installToWatch(act, a, a.pkgs.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 悬赏应用「提交已签名 hap」弹窗 —— 逐项对齐网页版 site/index.html 的 #dialog：
     *   标题：提交已签名 hap
     *   应用：<应用名>
     *   说明：把【已签名的 hap】和【收款码】发到下面的邮箱，
     *         邮件里写明应用名和你的手表型号。核对通过后发放 0.1 元。
     *   邮箱：harmonywatchapp@163.com   [复制]
     *   按钮：确认提交（打开邮件，收件人/标题/正文都填好）、关闭
     */
    private static void showBountyDialog(final Activity act, final AppItem a) {
        final String appName = (a != null && a.name != null && a.name.length() > 0) ? a.name : "应用";

        android.widget.LinearLayout root = new android.widget.LinearLayout(act);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (act.getResources().getDisplayMetrics().density * 20f);
        root.setPadding(pad, pad / 2, pad, 0);

        // 说明正文（含高亮内容的纯文本版，保持与网页版语义一致）
        android.widget.TextView note = new android.widget.TextView(act);
        note.setTextSize(14f);
        note.setLineSpacing(0f, 1.25f);
        note.setText("应用：" + appName + "\n\n"
                + "把【已签名的 hap】和【收款码】发到下面的邮箱，"
                + "邮件里写明应用名和你的手表型号。\n\n"
                + "核对通过后发放 " + REWARD + "。");
        root.addView(note, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // 邮箱行：地址 + 「复制」
        android.widget.LinearLayout mailRow = new android.widget.LinearLayout(act);
        mailRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        mailRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = pad / 2;

        final android.widget.TextView mailTv = new android.widget.TextView(act);
        mailTv.setTextSize(15f);
        mailTv.setTextColor(0xff2b6cb0);
        mailTv.setText(MAIL);
        mailTv.setSingleLine(true);
        mailRow.addView(mailTv, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Button copyBtn = new android.widget.Button(act);
        copyBtn.setText("复制");
        copyBtn.setAllCaps(false);
        copyBtn.setTextSize(13f);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(act, MAIL);
                Toast.makeText(act, "邮箱已复制", Toast.LENGTH_SHORT).show();
            }
        });
        mailRow.addView(copyBtn, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(mailRow, rowLp);

        // 底部提示（对应网页版 .tip）
        android.widget.TextView tip = new android.widget.TextView(act);
        tip.setTextSize(12f);
        tip.setTextColor(0xff8e8e93);
        tip.setLineSpacing(0f, 1.2f);
        tip.setText("点「确认提交」会自动打开邮件，收件人、标题和正文都填好了，"
                + "你只需填手表型号、附上 hap 和收款码。");
        android.widget.LinearLayout.LayoutParams tipLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = pad / 2;
        root.addView(tip, tipLp);

        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.addView(root);

        new AlertDialog.Builder(act)
                .setTitle("提交已签名 hap")
                .setView(sv)
                .setPositiveButton("确认提交", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openBountyMail(act, appName);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 组装 mailto：收件人、标题、正文与网页版 btnSubmit 完全一致 */
    private static void openBountyMail(Activity act, String appName) {
        String nm = (appName == null || appName.length() == 0) ? "应用" : appName;
        String subject = "提交已签名 hap：" + nm;
        String body = "应用名：" + nm + "\n"
                + "\n"
                + "手表型号：（请填写你的手表型号）\n"
                + "\n"
                + "附件：已签名 hap + 收款码（请把两个文件作为附件添加）\n"
                + "\n"
                + "核对通过后发放 " + REWARD + "，收款码请一并附上。";
        String url = "mailto:" + MAIL
                + "?subject=" + Uri.encode(subject)
                + "&body=" + Uri.encode(body);
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SENDTO);
            i.setData(Uri.parse(url));
            act.startActivity(i);
        } catch (Throwable t) {
            // 没有邮件客户端时退回通用 ACTION_SEND，让用户自选
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("message/rfc822");
                i.putExtra(android.content.Intent.EXTRA_EMAIL, new String[]{MAIL});
                i.putExtra(android.content.Intent.EXTRA_SUBJECT, subject);
                i.putExtra(android.content.Intent.EXTRA_TEXT, body);
                act.startActivity(android.content.Intent.createChooser(i, "发送邮件"));
            } catch (Throwable t2) {
                Toast.makeText(act, "没有找到邮件应用，请手动发送到 " + MAIL, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 复制文本到剪贴板 */
    private static void copyText(Activity act, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("mail", text));
        } catch (Throwable ignore) {
        }
    }

    /** 手表是否已真正连上（蓝牙通且原生会话就绪） */
    public static boolean isConnected(Activity act) {
        String addr = OneKeyInstall.currentAddr();
        if (addr == null || addr.length() == 0) {
            // 刚启动还没轮到 pollInfo 时，currentAddr 可能还是空的，回退到上次设备
            try {
                String last = HuaweiAuth.p(act).getString("last_device_", "");
                if (last != null && last.length() > 0) addr = last;
            } catch (Throwable ignore) {
            }
        }
        if (addr == null || addr.length() == 0) return false;
        try {
            Bridge.init(act);
            if (!Bridge.ready()) return false;
            Object[] in = Bridge.info(addr);
            if (in == null || !Boolean.TRUE.equals(in[3])) return false;
            String dn = in[0] == null ? "" : String.valueOf(in[0]);
            OneKeyInstall.setCurrent(addr, dn);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 保证已连接：没连上就弹窗让用户先去连接，返回 false 时调用方必须终止流程。
     */
    public static boolean ensureConnected(final Activity act) {
        if (isConnected(act)) return true;
        String addr = OneKeyInstall.currentAddr();
        String why = (addr == null || addr.length() == 0)
                ? "当前没有连接任何手表。"
                : "手表 " + addr + " 还没有连上。";
        new AlertDialog.Builder(act)
                .setTitle("请先连接设备")
                .setMessage(why + "\n下载和安装需要用已连接手表的信息来申请 Profile，所以要先连接设备。")
                .setPositiveButton("去连接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            act.startActivity(new android.content.Intent(act, PairActivity.class));
                        } catch (Throwable t) {
                            Toast.makeText(act, "无法打开配对页：" + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        return false;
    }

    /**
     * 点「获取」选完设备后的完整动作：
     *   下载 HAP → 用这台手表的 UDID 在线申请 AGC Profile → 重签名 → 推送到手表安装。
     * UDID 走原版算法（WatchUdid：SHA-256(序列号+MAC)），不再是序列号或裸 getUid。
     * Profile 按「软件」维度申请（不同软件包名不同，不能共用 Profile）。
     */
    public static void installToWatch(final Activity act, final AppItem a, final Pkg p) {
        final String addr = OneKeyInstall.currentAddr();
        if (addr == null || addr.length() == 0) {
            Toast.makeText(act, "还没有已连接的手表，请先在设备页连接手表", Toast.LENGTH_LONG).show();
            return;
        }
        final String udid = OneKeyInstall.connectedUdid(act, addr);
        String watchName = OneKeyInstall.currentName();

        if (udid == null || udid.length() == 0) {
            Toast.makeText(act, "读不到这台手表的 UDID，请等连接就绪后重试（需要读到序列号）", Toast.LENGTH_LONG).show();
            return;
        }
        if (!AgcApi.hasCredential(act)) {
            Toast.makeText(act, "请先在「我的 → DevEco 账号」登录开发者账号", Toast.LENGTH_LONG).show();
            return;
        }

        // 每个软件都有自己的 AGC 应用与 bundleName，Profile 也随之独立
        final String appKey = "a" + a.id;
        final String appName = a.name == null || a.name.length() == 0 ? "app" : a.name;

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(act);
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setTitle("获取并安装");
        pd.setMessage("准备中…");
        pd.setCancelable(false);
        pd.show();

        final String finalName = watchName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1) 下载安装包（服务器下发的是 .app 容器，里面才是 hap）
                    //    原始包放应用缓存目录，签好的包另外落到本地持久目录，方便复查/复用。
                    File dir = new File(act.getCacheDir(), "hap");
                    if (!dir.exists()) dir.mkdirs();
                    File src = new File(dir, p.file);
                    setPct(pd, 2, "① 下载安装包…");
                    download(p.url(), src, pd);
                    if (src.length() < 1000) throw new Exception("下载内容异常（" + src.length() + " 字节）");

                    // 2) 为这个软件申请 Profile + 重签名 + 安装（与导入本地包共用同一套）
                    setPct(pd, 55, "② 在线申请 Profile…");
                    File outDir = signedDir(act);
                    String outName = "wl_" + appKey + ".hap";
                    File out = new File(outDir, outName);
                    if (out.exists()) out.delete();
                    doProvisionSignInstall(act, addr, udid, finalName, src, out,
                            appKey, appName, pd, 55);

                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 交给进度弹窗接管：它监听原版安装广播直到完成/失败
                            pd.dismiss();
                            new InstallProgress(act, p.file, new InstallProgress.Done() {
                                @Override
                                public void onDone(boolean success, String message) {
                                    if (success) {
                                        toast(act, "安装完成");
                                    } else {
                                        dialog(act, "安装失败", message == null ? "未知错误" : message);
                                    }
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.e("Dl", "installToWatch failed", e);
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(act, "失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 对【本地已有】的 .app / .hap 走完整签名安装流程（与「获取」完全同一套）：
     *   在线申请 Profile → 重签名 → 推送到手表安装。
     *
     * @param appKey  软件稳定标识（决定 AGC 应用/包名，同一软件必须传同一个值）
     * @param appName 软件名（用来派生固定包名 com.wlaz.xxx）
     * @param label   界面上显示的包名/文件名
     */
    public static void signAndInstallFile(final Activity act, final File src,
                                          final String appKey, final String appName, final String label) {
        final String addr = OneKeyInstall.currentAddr();
        if (addr == null || addr.length() == 0) {
            Toast.makeText(act, "还没有已连接的手表，请先在设备页连接手表", Toast.LENGTH_LONG).show();
            return;
        }
        final String udid = OneKeyInstall.connectedUdid(act, addr);
        if (udid == null || udid.length() == 0) {
            Toast.makeText(act, "读不到这台手表的 UDID，请等连接就绪后重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (!AgcApi.hasCredential(act)) {
            Toast.makeText(act, "请先在「我的 → DevEco 账号」登录开发者账号", Toast.LENGTH_LONG).show();
            return;
        }

        final String watchName = OneKeyInstall.currentName();
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(act);
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setTitle("签名并安装");
        pd.setMessage("准备中…");
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File outDir = signedDir(act);
                    String outName = "wl_" + appKey + ".hap";
                    File out = new File(outDir, outName);
                    if (out.exists()) out.delete();
                    doProvisionSignInstall(act, addr, udid, watchName, src, out,
                            appKey, appName, pd, 55);
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            new InstallProgress(act, label, new InstallProgress.Done() {
                                @Override
                                public void onDone(boolean success, String message) {
                                    if (success) {
                                        toast(act, "安装完成");
                                    } else {
                                        dialog(act, "安装失败", message == null ? "未知错误" : message);
                                    }
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.e("Dl", "signAndInstallFile failed", e);
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(act, "失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 申请 Profile + 重签名 + 推送到手表（不含下载、不含进度弹窗收尾）。
     * 「获取」和「导入本地包」两条路都走这里，保证签名行为完全一致。
     *
     * @param startPct 进度条起点（下载阶段已占用的百分比）
     */
    static void doProvisionSignInstall(Context ctx, String addr, String udid, String watchName,
                                       File src, File out, String appKey, String appName,
                                       android.app.ProgressDialog pd, int startPct) throws Exception {
        // ★ 从安装包读原始 bundleName —— 这是签名后包名的唯一可靠来源。
        //   用软件名推导会把所有中文名软件都撞成 com.wlaz.app，互相覆盖。
        String rawBundle = rawBundleOf(src);
        android.util.Log.i("Dl", "安装包原始包名=" + rawBundle + " appKey=" + appKey + " appName=" + appName);

        setPct(pd, startPct, "② 在线申请 Profile…");
        StringBuilder log = new StringBuilder();
        byte[] p7b = AgcApi.provisionForWatch(ctx, udid, watchName, appKey, appName, rawBundle, log);
        DeviceStore.markAgc(ctx, udid, AgcApi.p(ctx).getString("profile_id", ""));

        setPct(pd, (startPct + 100) / 2, "③ 重签名…");
        HapSigner.sign(src, out, p7b, OneKeyInstall.keyPem(ctx), OneKeyInstall.caPem(ctx));
        if (!out.exists() || out.length() < 1000) throw new Exception("签名输出失败");
        android.util.Log.i("Dl", "签名包已保存：" + out.getAbsolutePath());

        setPct(pd, 90, "④ 推送到手表安装…");
        Bridge.init(ctx);
        final Uri uri = HapProvider.uriFor(out);
        if (!Bridge.install(addr, uri, ctx)) throw new Exception("推送失败：" + Bridge.error());
    }

    /**
     * 从安装包（.app / .hap）里读出原始 bundleName。
     * 读不到返回 ""，上层会退回按软件名推导。
     *
     * 为什么要这么做：包名必须每个软件唯一，否则手表上后装的会覆盖先装的。
     * 原包自带的 bundleName（com.example.dazhuankuai 之类）天然唯一，
     * 而中文软件名过 sanitizeAppName 只剩空串 → 全部变成 com.wlaz.app。
     */
    static String rawBundleOf(File src) {
        if (src == null || !src.isFile()) return "";
        try {
            java.util.LinkedHashMap<String, byte[]> files = HapSigner.collectFromApp(src);
            byte[] cfg = files.get("config.json");
            if (cfg == null || cfg.length == 0) return "";
            Object root = MiniJson.parse(new String(cfg, "UTF-8"));
            String b = MiniJson.str(root, "app.bundleName");
            if (b.length() == 0) b = MiniJson.str(root, "bundleName");
            if (b.length() == 0) b = MiniJson.str(root, "module.bundleName");
            return AgcApi.normalizeRawBundle(b);
        } catch (Throwable t) {
            android.util.Log.w("Dl", "rawBundleOf 失败：" + t);
            return "";
        }
    }

    /**
     * 签名包落盘目录。
     * ★ 关键：这个函数【永远不许失败】。以前直接 mkdirs 公共目录，
     *   在 targetSdk34 且没「所有文件访问」权限时 mkdirs 返回 false，
     *   随后 FileOutputStream 抛 FileNotFoundException，整个签名流程就断了。
     *   现在按 可写性 逐级回退：
     *     1) /storage/emulated/0/腕连助手/签名包（公共目录，需所有文件访问权限）
     *     2) 应用专属外部目录 Android/data/<pkg>/files/签名包（无需任何权限）
     *     3) 应用私有目录 files/签名包（兜底，一定能写）
     */
    static File signedDir(Context c) {
        String[] cands = new String[]{
                "/storage/emulated/0/腕连助手/签名包",
                "/sdcard/腕连助手/签名包"
        };
        for (String p : cands) {
            File d = writableDir(new File(p));
            if (d != null) return d;
        }
        try {
            File ext = c.getExternalFilesDir(null);
            if (ext != null) {
                File d = writableDir(new File(ext, "签名包"));
                if (d != null) return d;
            }
        } catch (Throwable ignore) {
        }
        File fallback = new File(c.getFilesDir(), "签名包");
        fallback.mkdirs();
        return fallback;
    }

    /** 目录存在且真的能写才返回它，否则返回 null（不抛异常） */
    private static File writableDir(File d) {
        try {
            if (!d.exists() && !d.mkdirs()) {
                // 上层目录可能已存在但自身没建出来，再试一次
                File parent = d.getParentFile();
                if (parent != null) parent.mkdirs();
                d.mkdirs();
            }
            if (!d.isDirectory()) return null;
            File probe = new File(d, ".w" + System.nanoTime());
            java.io.FileOutputStream fo = new java.io.FileOutputStream(probe);
            fo.write(1);
            fo.close();
            probe.delete();
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 引导用户开启「所有文件访问」（Android 11+）。没授权也不影响签名，只是包会落到应用专属目录 */
    static void askAllFiles(Activity a) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            if (android.os.Environment.isExternalStorageManager()) return;
            android.content.Intent it = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            it.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(it);
        } catch (Throwable t) {
            try {
                a.startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable ignore) {
            }
        }
    }

    /** 主线程提示 */
    static void toast(final Activity a, final String s) {
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, s, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 主线程错误框：失败原因要看清楚，不能只一闪而过 */
    static void dialog(final Activity a, final String title, final String body) {
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(a)
                        .setTitle(title)
                        .setMessage(body == null ? "" : body)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    /** 小工具：把进度打到对话框上（必须在主线程） */
    static void setMsg(final android.app.ProgressDialog pd, final String s) {
        if (pd == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                pd.setMessage(s);
            }
        });
    }

    /** 更新进度条百分比（主线程） */
    static void setPct(final android.app.ProgressDialog pd, final int pct, final String s) {
        if (pd == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                pd.setProgress(Math.max(0, Math.min(100, pct)));
                if (s != null) pd.setMessage(s);
            }
        });
    }

    /** 下载到本地文件，带百分比进度 */
    static void download(String url, File dst, final android.app.ProgressDialog pd) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", C.UA);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("下载失败 HTTP " + code);
        long total = c.getContentLength();
        java.io.InputStream in = c.getInputStream();
        java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[65536];
        long done = 0;
        int n;
        int lastPct = -1;
        while ((n = in.read(buf)) > 0) {
            fo.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                if (pct != lastPct) {
                    lastPct = pct;
                    // 下载占整体 0~40%
                    setPct(pd, pct * 40 / 100, "① 下载安装包… " + pct + "%");
                }
            }
        }
        fo.close();
        in.close();
        c.disconnect();
        if (dst.length() < 1000) throw new Exception("下载内容异常（" + dst.length() + " 字节）");
    }

    public static void start(Activity act, AppItem a, Pkg p) {
        try {
            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(p.url()));
            r.setTitle(a.name + " · " + C.devName(p.dev));
            r.setDescription("鸿蒙手表助手 · " + p.size + " · .app");
            r.addRequestHeader("User-Agent", C.UA);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, C.DIR + "/" + p.file);
            dm.enqueue(r);
            Toast.makeText(act, "已开始下载：" + p.file, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(act, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}