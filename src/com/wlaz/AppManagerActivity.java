package com.wlaz;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AppManagerActivity extends Activity {

    private String addr = "";
    private int type = 1;
    private TextView tip;
    private BaseAdapter adapter;
    private final List<Object> data = new ArrayList<Object>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private BroadcastReceiver installRx;
    private Object lbm;
    /** 替换法：当前可选的表盘槽列表（选完文件后再让用户选） */
    private List<Object> replaceFaces;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_apps);
        addr = getIntent().getStringExtra("addr");
        if (addr == null) addr = "";
        type = getIntent().getIntExtra("type", 1);
        ((TextView) findViewById(R.id.title)).setText(type == 2 ? "表盘管理" : "应用管理");
        tip = (TextView) findViewById(R.id.tip);
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        findViewById(R.id.add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseInstallMode();
            }
        });
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Object getItem(int p) {
                return data.get(p);
            }

            @Override
            public long getItemId(int p) {
                return p;
            }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                if (cv == null) cv = LayoutInflater.from(AppManagerActivity.this).inflate(R.layout.item_ble, parent, false);
                Object it = data.get(p);
                ((TextView) cv.findViewById(R.id.name)).setText(Bridge.titleOf(it));
                ((TextView) cv.findViewById(R.id.details)).setText(Bridge.subOf(it));
                ((TextView) cv.findViewById(R.id.status)).setText(Bridge.badgeOf(it));
                final Object item = it;
                cv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        actions(item);
                    }
                });
                return cv;
            }
        };
        ((ListView) findViewById(R.id.list)).setAdapter(adapter);

        installRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String act = i.getAction();
                if (WatchConst.ACTION_INSTALL_MESSAGE.equals(act)) {
                    tip.setText(String.valueOf(i.getStringExtra("message")));
                } else if (WatchConst.ACTION_INSTALL_PROGRESS.equals(act)) {
                    tip.setText("正在传输安装包 " + i.getIntExtra("progress", 0) + "%");
                } else if (WatchConst.ACTION_INSTALL_FINISHED.equals(act)) {
                    tip.setText("安装完成，正在刷新列表…");
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            load();
                        }
                    }, 1500);
                } else if (WatchConst.ACTION_INSTALL_FAILED.equals(act)) {
                    tip.setText("安装失败：" + i.getStringExtra("message"));
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WatchConst.ACTION_INSTALL_MESSAGE);
        f.addAction(WatchConst.ACTION_INSTALL_PROGRESS);
        f.addAction(WatchConst.ACTION_INSTALL_FINISHED);
        f.addAction(WatchConst.ACTION_INSTALL_FAILED);
        try {
            // LocalBroadcastManager 现在是本工程内的源码类（androidx.localbroadcastmanager 包），直接引用
            Class<?> lbc = Class.forName("androidx.localbroadcastmanager.content.LocalBroadcastManager", true, getClassLoader());
            lbm = lbc.getMethod("getInstance", Context.class).invoke(null, this);
            lbc.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class).invoke(lbm, installRx, f);
        } catch (Throwable t) {
            registerReceiver(installRx, f);
        }
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (installRx != null) {
            try {
                if (lbm != null) lbm.getClass().getMethod("unregisterReceiver", BroadcastReceiver.class).invoke(lbm, installRx);
                else unregisterReceiver(installRx);
            } catch (Throwable ignore) {
            }
        }
    }
    /** 右上角「安装」：应用页问用哪种方式，表盘页问直接传还是替换法 */
    private void chooseInstallMode() {
        if (type == 2) {
            chooseWatchfaceInstallMode();
            return;
        }
        final String[] items = new String[]{
                "直接安装（仅支持已签名的 .hap）",
                "签名后再安装（仅支持 .app）",
                "取消"};
        new AlertDialog.Builder(this)
                .setTitle("导入应用")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            pickPackage(7, new String[]{"*/*"}, "选择安装包");
                        } else if (which == 1) {
                            if (!Dl.ensureConnected(AppManagerActivity.this)) return;
                            pickPackage(8, new String[]{"*/*"}, "选择安装包");
                        }
                    }
                })
                .show();
    }

    /**
     * 表盘安装方式选择。
     *
     * <ul>
     *   <li><b>直接传输</b>：老流程，走普通「新增表盘」。固件 < 鸿蒙 6.0.0.32 可用。</li>
     *   <li><b>替换法</b>：鸿蒙 6.0.0.32 起普通新增会被服务器授权/绑定/摘要校验拦掉，
     *       改成挑一个手表上已登记的普通表盘槽，用原资源 ID + 版本号 +1 做「新版本替换」。
     *       移植自 WristMirror-1.1.0。</li>
     * </ul>
     */
    private void chooseWatchfaceInstallMode() {
        final String[] items = new String[]{
                "直接传输（适用于鸿蒙 6.0.0.32 以下）",
                "替换法安装（适用于鸿蒙 6.0.0.32 及以上）",
                "取消"};
        new AlertDialog.Builder(this)
                .setTitle("安装表盘")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            pickPackage(9, new String[]{"application/octet-stream", "*/*"}, "选择表盘文件 (.hwt)");
                        } else if (which == 1) {
                            if (!Dl.ensureConnected(AppManagerActivity.this)) return;
                            startReplaceFlow();
                        }
                    }
                })
                .show();
    }

    /**
     * 替换法第 1 步：先选本地 .hwt。
     * 选完文件不会立即传输 —— 还要再选「替换哪一块表盘」并确认。
     */
    private void startReplaceFlow() {
        Bridge.log("REPLACE step1 startReplaceFlow enter addr=" + addr);
        if (!Bridge.ready()) {
            Bridge.init(this);
            if (!Bridge.ready()) {
                Bridge.log("REPLACE step1 ABORT bridge not ready: " + Bridge.error());
                tip.setText("底层未就绪：" + Bridge.error());
                return;
            }
        }
        List<Object> faces = new ArrayList<Object>();
        for (com.watch.assistant.bridge.WatchInstalledItem it : Bridge.items(addr, 2)) {
            faces.add(it);
        }
        Bridge.log("REPLACE step1 items=" + faces.size());
        if (faces.isEmpty()) {
            // 列表还没读到就先催一次，但不要卡住流程：
            // 选完文件后 chooseReplaceTarget 会再现场取一次列表。
            Bridge.requestApps(addr);
            tip.setText("列表还没读到，已请求刷新；可先选文件，稍后再选目标表盘");
        }
        replaceFaces = faces;
        pickPackage(10, new String[]{"application/octet-stream", "*/*"}, "选择表盘文件 (.hwt)");
    }

    /**
     * 替换法第 2 步：选要被替换的目标表盘槽。
     *
     * 排序规则：越可能被固件接受的越靠前。
     *   1. 真正的中性普通表盘（不带预置位）—— 最稳
     *   2. 带可删除位的预置表盘 —— 通常也能走原 ID 替换
     *   3. 其它可尝试的
     *   4. 视频/相册/贴纸这类扩展表盘 —— 直接禁用
     *
     * 能不能成功最终由固件回 need 决定，这里只负责把顺序排好。
     */
    private void chooseReplaceTarget(final Uri hwt) {
        Bridge.log("REPLACE step2 chooseReplaceTarget file=" + fileNameOf(hwt));
        // 不能只依赖成员变量 replaceFaces：选文件期间 Activity 可能被系统回收重建，
        // 重建后 replaceFaces 是 null。这里每次现场重新取一次列表。
        if (!Bridge.ready()) {
            Bridge.init(this);
            if (!Bridge.ready()) {
                Bridge.log("REPLACE step2 ABORT bridge not ready " + Bridge.error());
                tip.setText("底层未就绪：" + Bridge.error());
                return;
            }
        }
        List<Object> source = new ArrayList<Object>();
        for (com.watch.assistant.bridge.WatchInstalledItem it : Bridge.items(addr, 2)) {
            source.add(it);
        }
        if (source.isEmpty() && replaceFaces != null) {
            source = replaceFaces;
        }
        Bridge.log("REPLACE step2 items=" + source.size() + " addr=" + addr);
        if (source.isEmpty()) {
            Bridge.log("REPLACE step2 ABORT list empty");
            tip.setText("表盘列表还是空的，请先点「刷新」等列表读出来，再点安装");
            new AlertDialog.Builder(this)
                    .setTitle("无法替换")
                    .setMessage("表盘列表还是空的。\n请先点右上角「刷新」，等表盘列表显示出来，再点「安装 → 替换法」。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final List<Object> faces = source;
        if (faces == null || faces.isEmpty()) {
            tip.setText("没有可用的表盘列表，请先刷新一次");
            return;
        }

        // 先分拣再排序：只有严格可替换(rank 0)才可选，其余一律 rank 9 禁用并写明原因。
        // 实测放宽放行的预置表盘会被固件直接回 100014 / need=0，所以不再给「可尝试」入口。
        List<Object> ordered = new ArrayList<Object>();
        List<Integer> ranks = new ArrayList<Integer>();
        int strictCount = 0;
        for (int i = 0; i < faces.size(); i++) {
            Object it = faces.get(i);
            int rank;
            if (Bridge.isWatchfaceReplaceable(it)) {
                rank = 0;
                strictCount++;
            } else {
                rank = 9;
            }
            ordered.add(it);
            ranks.add(Integer.valueOf(rank));
        }
        Bridge.log("REPLACE step2 strictCount=" + strictCount + " total=" + faces.size());
        for (Object it : faces) {
            Bridge.log("REPLACE step2 item " + Bridge.titleOf(it)
                    + " ver=" + versionOf(it)
                    + " rawType=" + rawTypeOf(it)
                    + " expand=" + expandTypeOf(it)
                    + " replaceable=" + Bridge.isWatchfaceReplaceable(it)
                    + " why=" + Bridge.replaceabilityOf(it));
        }

        // 稳定插入排序：rank 小的往前，同 rank 保持原顺序
        List<Object> sorted = new ArrayList<Object>();
        List<Integer> sortedRanks = new ArrayList<Integer>();
        for (int targetRank = 0; targetRank <= 9; targetRank++) {
            for (int i = 0; i < ordered.size(); i++) {
                if (ranks.get(i).intValue() == targetRank) {
                    sorted.add(ordered.get(i));
                    sortedRanks.add(ranks.get(i));
                }
            }
        }

        int usable = 0;
        for (int i = 0; i < sortedRanks.size(); i++) {
            if (sortedRanks.get(i).intValue() != 9) usable++;
        }
        if (usable == 0) {
            Bridge.log("REPLACE step2 ABORT usable=0 total=" + sorted.size());
            String msg = "这块手表上没有可作为替换目标表盘（需带「可删除」位，且不是预置/试用/视频/相册/贴纸类型）。\n\n"
                    + "本次读到 " + sorted.size() + " 个表盘，全部不可替换。\n"
                    + "请先在手表上添加一个第三方表盘，再回来尝试替换。";
            tip.setText("没有可替换的表盘，详见弹窗");
            new AlertDialog.Builder(this)
                    .setTitle("无法替换")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        final List<Object> targetList = sorted;
        final String[] labels = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            Object it = sorted.get(i);
            int rank = sortedRanks.get(i).intValue();
            String title = Bridge.titleOf(it);
            if (title == null || title.trim().length() == 0) title = "未命名表盘";
            String cur = versionOf(it);
            String next = Bridge.replacementVersionFor(it);
            boolean ok = rank != 9;

            StringBuilder sb = new StringBuilder();
            sb.append(ok ? "✓ " : "✗ ").append(title);
            if (cur != null && cur.length() > 0 && next != null && next.length() > 0) {
                sb.append("　").append(cur).append(" → ").append(next);
            }
            // 第二行只留最关键的短标签，避免像之前那样把整段说明挤成一坨
            if (rank == 0) {
                sb.append("\n　　普通表盘（固件允许改写），可替换");
            } else {
                sb.append("\n　　").append(Bridge.replaceabilityOf(it)).append("，不可选");
            }
            labels[i] = sb.toString();
        }

        String note;
        if (strictCount == 0) {
            note = "这块手表上没有固件允许改写的普通表盘（需要同时具备「可删除」位且不是预置/试用/特殊类型）。\n"
                    + "出厂预置表盘固件会直接拒绝替换（实测返回 100014 / need=0），因此这里不再提供入口。\n"
                    + "请先在手表上添加一个第三方表盘，再来尝试替换。";
        } else {
            note = "只有带「普通表盘」标记的项可以替换，其余项固件会拒绝。";
        }

        // 不要用 setMessage + setItems：两者同时存在时，多行说明会把列表区挤成 0 高，
        // 弹窗只剩标题、说明和「取消」，列表项完全看不见。这里自己拼一个可滚动列表。
        final AlertDialog[] holder = new AlertDialog[1];
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (density * 16);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setText(note);
        head.setTextSize(12);
        head.setPadding(0, 0, 0, pad);
        root.addView(head);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(labels[i]);
            row.setTextSize(14);
            row.setPadding(pad / 2, pad, pad / 2, pad);
            if (sortedRanks.get(i).intValue() == 9) {
                row.setTextColor(0xFF888888);
                row.setAlpha(0.7f);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Object target = targetList.get(idx);
                    if (!Bridge.isWatchfaceReplaceable(target)) {
                        Toast.makeText(AppManagerActivity.this,
                                "该项不支持替换：" + Bridge.replaceabilityOf(target), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (holder[0] != null) holder[0].dismiss();
                    confirmReplace(hwt, target);
                }
            });
            list.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
        }
        sc.addView(list, new android.widget.LinearLayout.LayoutParams(-1, -2));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        root.addView(sc, new android.widget.LinearLayout.LayoutParams(-1, maxH));

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("选择要被替换的表盘")
                .setView(root)
                .setNegativeButton("取消", null)
                .create();
        holder[0].show();
    }

    private String versionOf(Object it) {
        try {
            return String.valueOf(it.getClass().getMethod("getVersion").invoke(it));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 日志用：表盘原始 type 位，-1 表示没读到 */
    private String rawTypeOf(Object it) {
        try {
            Object v = it.getClass().getMethod("getRawType").invoke(it);
            int n = ((Integer) v).intValue();
            return "0x" + Integer.toHexString(n & 0xFF) + (n < 0 ? "(未读到)" : "");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 日志用：表盘 expandType，-1 表示没读到 */
    private String expandTypeOf(Object it) {
        try {
            Object v = it.getClass().getMethod("getRawExpandType").invoke(it);
            return String.valueOf(((Integer) v).intValue());
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 替换法第 3 步：确认 */
    private void confirmReplace(final Uri hwt, final Object target) {
        final String newVer = Bridge.replacementVersionFor(target);
        Bridge.log("REPLACE step2b confirmReplace dialog target=" + Bridge.titleOf(target)
                + " ver=" + versionOf(target) + " -> " + newVer);
        String msg = "本地文件：" + fileNameOf(hwt)
                + "\n目标表盘：" + Bridge.titleOf(target)
                + "\n版本：" + versionOf(target) + " → " + newVer
                + "\n\n替换会覆盖这个表盘槽的原有内容。"
                + "\n传输完成后请在手表上确认画面。";
        new AlertDialog.Builder(this)
                .setTitle("确认替换")
                .setMessage(msg)
                .setPositiveButton("开始替换", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Bridge.log("REPLACE step2c user tapped 开始替换 target=" + Bridge.titleOf(target));
                        if (!Bridge.installWatchfaceByReplace(addr, target, hwt, AppManagerActivity.this)) {
                            String why = Bridge.error();
                            android.util.Log.w("WatchfaceReplace", "install by replace rejected: " + why);
                            tip.setText("启动替换失败：" + why);
                            new AlertDialog.Builder(AppManagerActivity.this)
                                    .setTitle("无法开始替换")
                                    .setMessage(why)
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        tip.setText("已提交替换，等待手表响应…");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickPackage(int req) {
        pickPackage(req, new String[]{"*/*"}, "选择安装包");
    }

    private void pickPackage(int req, String[] types, String title) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(types[0]);
        if (types.length > 1) {
            // 有些文件管理器会给 .hwt 报成 octet-stream，加一层兜底
            i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        }
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, title), req);
        } catch (Exception e) {
            Toast.makeText(this, "没有文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (res != RESULT_OK || d == null || d.getData() == null) return;
        final Uri uri = d.getData();
        if (req == 7) {
            // 直接安装：包必须是已签名 HAP，原样交给原版安装引擎
            Bridge.init(this);
            if (!Bridge.install(addr, uri, this)) {
                tip.setText("启动安装失败：" + Bridge.error());
                return;
            }
            tip.setText("已提交安装，等待手表响应…");
        } else if (req == 8) {
            signAndInstall(uri);
        } else if (req == 9) {
            installWatchface(uri);
        } else if (req == 10) {
            // 替换法：文件选完了，接着让用户选要替换哪一块
            chooseReplaceTarget(uri);
        }
    }

    /**
     * 表盘安装（.hwt）。
     *
     * 表盘和 .hap/.app 是两套东西：.hwt 是手表表盘资源包，
     * 原版安装引擎靠 HuaweiInstallPackageInspector 认出它是 watchface 后走
     * SERVICE_WATCHFACE 的上传通道，不需要也**不能**走 DevEco 签名流程
     * （签名只针对 hap/app，给 hwt 套签名会直接失败）。
     * 所以这里只做文件类型确认，然后原样交给原版引擎。
     */
    private void installWatchface(final Uri uri) {
        final String name = fileNameOf(uri);
        if (name.length() > 0 && !name.toLowerCase(java.util.Locale.US).endsWith(".hwt")) {
            new AlertDialog.Builder(this)
                    .setTitle("文件类型不对")
                    .setMessage("表盘需要 .hwt 文件，选中的是：\n" + name)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        Bridge.init(this);
        if (!Bridge.ready()) {
            tip.setText("底层未就绪：" + Bridge.error());
            return;
        }
        if (!Bridge.install(addr, uri, this)) {
            tip.setText("启动安装失败：" + Bridge.error());
            return;
        }
        tip.setText("已提交表盘安装，等待手表响应…");
    }

    /** 签名后再安装：把本地包交给 Dl 走与「获取」完全相同的 DevEco 签名流程 */
    private void signAndInstall(final Uri uri) {
        // appKey 必须和「获取」里对同一个软件用的值一致，才能复用同一个 AGC 应用/包名。
        // 本地导入时没有 AppItem.id，就按包内 bundleName 派生，得到 stable 的 u_<bundle> 与固定 com.wlaz.* 包名。
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在准备…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File src = null;
                try {
                    File dir = new File(getCacheDir(), "hap");
                    if (!dir.exists()) dir.mkdirs();
                    String fname = fileNameOf(uri);
                    if (fname.length() == 0) fname = "upload_" + System.currentTimeMillis() + ".app";
                    src = new File(dir, fname);
                    copyTo(uri, src);
                    if (src.length() < 1000) throw new Exception("文件内容异常（" + src.length() + " 字节）");
                } catch (final Exception e) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            tip.setText("读取安装包失败：" + e.getMessage());
                        }
                    });
                    return;
                }
                final File f = src;
                // 读包名只是为了给 appKey/appName 起名，签名本身完全交给 Dl
                String appKey = "u" + f.length();
                String appName = "app";
                try {
                    java.util.LinkedHashMap<String, byte[]> files = HapSigner.collectFromApp(f);
                    byte[] cfg = files.get("config.json");
                    if (cfg != null) {
                        Object root = MiniJson.parse(new String(cfg, "UTF-8"));
                        String bundle = MiniJson.str(root, "app.bundleName");
                        if (bundle.length() == 0) bundle = MiniJson.str(root, "bundleName");
                        if (bundle.length() > 0) {
                            appKey = bundle;
                            appName = bundle.contains(".")
                                    ? bundle.substring(bundle.lastIndexOf('.') + 1) : bundle;
                        }
                    }
                } catch (Throwable ignore) {
                }
                final String fkey = appKey;
                final String fname2 = appName;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        Dl.signAndInstallFile(AppManagerActivity.this, f, fkey, fname2, f.getName());
                    }
                });
            }
        }).start();
    }

    private String fileNameOf(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int i = c.getColumnIndex("_display_name");
                        if (i >= 0) {
                            String n = c.getString(i);
                            if (n != null && n.length() > 0) return n;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignore) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "" : p;
    }

    private void copyTo(Uri uri, File dst) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取所选文件");
        java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.close();
        in.close();
    }


    private void actions(final Object item) {
        final String[] items = type == 2
                ? new String[]{"设为当前表盘", "删除表盘", "取消"}
                : new String[]{"打开应用", "卸载应用", "取消"};
        new AlertDialog.Builder(this)
                .setTitle(Bridge.titleOf(item))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            tip.setText(Bridge.activateItem(addr, item) ? "已发送激活命令" : "激活失败：" + Bridge.error());
                        } else if (which == 1) {
                            new AlertDialog.Builder(AppManagerActivity.this)
                                    .setTitle("确认删除")
                                    .setMessage(Bridge.titleOf(item))
                                    .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dd, int w) {
                                            tip.setText(Bridge.deleteItem(addr, item) ? "已发送删除命令，等待手表执行…" : "删除失败：" + Bridge.error());
                                            h.postDelayed(new Runnable() {
                                                @Override
                                                public void run() {
                                                    load();
                                                }
                                            }, 2500);
                                        }
                                    })
                                    .setNegativeButton("取消", null).show();
                        }
                    }
                }).show();
    }

    private void load() {
        tip.setText("正在读取…");
        Bridge.init(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bridge.requestApps(addr);
                try {
                    Thread.sleep(2000);
                } catch (Exception ignore) {
                }
                final List<com.watch.assistant.bridge.WatchInstalledItem> got = Bridge.items(addr, type);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        data.clear();
                        data.addAll(got);
                        adapter.notifyDataSetChanged();
                        tip.setText(got.isEmpty()
                                ? (type == 2 ? "暂无已安装表盘，点右上安装可导入。" : "暂无已安装应用，点右上安装可导入。")
                                : "共 " + got.size() + (type == 2 ? " 个表盘（点击可管理）" : " 个应用（点击可管理）"));
                    }
                });
            }
        }).start();
    }
}