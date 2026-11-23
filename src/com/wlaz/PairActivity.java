package com.wlaz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class PairActivity extends Activity {

    private Ble ble;
    private LinearLayout list;
    private TextView scanBtn, hint;
    private ProgressBar progress;
    private final java.util.HashMap<String, View> rows = new java.util.HashMap<String, View>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_pair);
        final long touchGuard = System.currentTimeMillis();
        findViewById(android.R.id.content).setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return System.currentTimeMillis() - touchGuard < 600;
            }
        });
        HuaweiAuth.cleanupBogus(this);
        list = (LinearLayout) findViewById(R.id.pairList);
        scanBtn = (TextView) findViewById(R.id.pairScan);
        hint = (TextView) findViewById(R.id.pairHint);
        progress = (ProgressBar) findViewById(R.id.pairProgress);
        findViewById(R.id.pairBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ble == null) return;
                if (ble.scanning) {
                    ble.stopScan();
                    paintScan();
                } else {
                    if (!ble.permsOk()) {
                        ble.askPerms();
                        return;
                    }
                    addBonded();
                    ble.startScan();
                    paintScan();
                }
            }
        });
        ble = new Ble(this);
        ble.setCb(new Ble.Cb() {
            @Override
            public void onState(String text) {
                hint.setText(text);
            }

            @Override
            public void onFound(String name, String addr, int rssi, int bond) {
                addRow(name, addr, rssi, bond);
            }

            @Override
            public void onBond(int bond) {
                paintScan();
            }

            @Override
            public void onConnected(String name, String addr) {
                new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { finish(); }
                }, 800);
            }

            @Override
            public void onBattery(int pct) {
            }

            @Override
            public void onRssi(int rssi) {
            }

            @Override
            public void onDisconnected() {
            }
        });
        if (!ble.permsOk()) ble.askPerms();
        else {
            addBonded();
            ble.startScan();
            paintScan();
        }
    }

    private void paintScan() {
        scanBtn.setText(ble != null && ble.scanning ? "停止扫描" : "开始扫描");
        progress.setVisibility(ble != null && ble.scanning ? View.VISIBLE : View.GONE);
    }

    private void addBonded() {
        try {
            for (String[] d : ble.bondedDevices()) addRow(d[0], d[1], 0, 12);
        } catch (Throwable ignore) {
        }
    }

    private void addRow(String name, final String addr, int rssi, int bond) {
        if (addr == null || addr.length() == 0) return;
        boolean known = HuaweiAuth.isKnown(this, addr);
        String nm = name == null ? "" : name.trim();
        if ("未命名设备".equals(nm)) nm = "";
        // 完全照原版 looksLikeHuaweiDevice：空名不收、只有已知设备或名字含华为关键词才收
        if (nm.length() == 0) {
            if (!known) return;
            nm = "HUAWEI Watch";
        }
        if (!known && !HuaweiAuth.looksLikeHuawei(nm)) return;
        if (HuaweiAuth.looksLikeHuawei(nm) || known) HuaweiAuth.putKnown(this, addr, nm);
        View v = rows.get(addr);
        if (v == null) {
            v = LayoutInflater.from(this).inflate(R.layout.item_ble, list, false);
            rows.put(addr, v);
            list.addView(v);
        }
        ((TextView) v.findViewById(R.id.name)).setText(nm);
        String acc = HuaweiAuth.account(this, addr);
        ((TextView) v.findViewById(R.id.details)).setText(addr + "  信号 " + rssi + "dBm\n"
                + (acc.length() == 0 ? "未设置华为账号" : "华为账号: " + HuaweiAuth.mask(acc)));
        TextView st = (TextView) v.findViewById(R.id.status);
        st.setText(bond == 12 ? "已配对" : (bond == 11 ? "配对中" : "连接"));
        final String dn = nm;
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                // 不要在这里提前写 last_device_，否则 connect() 里就查不到“上一台设备”，
                // 换手表时旧设备不会被断开，会出现连上新手表却仍显示旧设备信息的问题。
                connect(addr, dn);
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View x) {
                askAccount(addr, dn, false);
                return true;
            }
        });
    }

    private void connect(String addr, String name) {
        Bridge.init(this);
        String acc = HuaweiAuth.account(this, addr);
        if (acc.length() == 0) {
            // 原版 store 里可能已经有配对时的账号，优先复用，避免下发空账号导致手表提示账号不同
            String fromStore = Bridge.getAccount(addr);
            if (fromStore.length() > 0) {
                HuaweiAuth.putAccount(this, addr, fromStore);
                acc = fromStore;
            }
        }
        if (acc.length() == 0) {
            askAccount(addr, name, true);
            return;
        }
        Bridge.setAccount(addr, acc, name);
        HuaweiAuth.putKnown(this, addr, name);
        String prev = HuaweiAuth.p(this).getString("last_device_", "");
        if (prev != null && prev.length() > 0 && !prev.equalsIgnoreCase(addr)) {
            // 换新手表前先把上一台彻底断开，否则后端会继续挂在旧设备上
            Bridge.log("pair switch " + prev + " -> " + addr);
            Bridge.disconnect(this, prev);
        }
        // 再把后端里其它已登记设备一并断掉，保证只有当前这台在线
        Bridge.disconnectOthers(this, addr);
        HuaweiAuth.p(this).edit().putString("last_device_", addr)
                .putString("last_device_name_", name).apply();
        hint.setText("正在连接 " + name + "...");
        if (ble != null && ble.scanning) ble.stopScan();
        if (Bridge.connect(this, addr)) {
            Toast.makeText(this, "正在连接设备", Toast.LENGTH_SHORT).show();
            hint.setText("正在连接 " + name + "，请稍候...");
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { if (!isFinishing()) finish(); }
            }, 2000);
        } else {
            hint.setText("连接失败：" + Bridge.error());
        }
    }

    private EditText currentEt;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView dlgBtn(String text, boolean primary) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTextSize(15);
        b.setTextColor(primary ? 0xFFFFFFFF : getResources().getColor(R.color.blue));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(primary ? getResources().getColor(R.color.blue) : 0x22FFFFFF);
        b.setBackground(g);
        return b;
    }

    private void askAccount(final String addr, final String name, final boolean connectAfter) {
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this).create();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(18));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF20242C);
        bg.setCornerRadius(dp(22));
        box.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("华为账号");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(getResources().getColor(R.color.tx));
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("连接前必须填写正确的华为账号 ID。它不是手机号、邮箱、设备 UDID 或随意填写的数字。不知道账号 ID 时请点击下方“获取”自行查询；因乱填信息造成的连接或设备问题需由用户自行承担。");
        desc.setGravity(android.view.Gravity.CENTER);
        desc.setTextColor(getResources().getColor(R.color.tx2));
        desc.setTextSize(13);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(10);
        box.addView(desc, dlp);

        final EditText et = new EditText(this);
        currentEt = et;
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setHint("华为账号ID");
        String old = HuaweiAuth.account(this, addr);
        if (old.length() > 0) et.setText(old);
        et.setSelection(et.getText().length());
        et.setPadding(dp(16), dp(8), dp(16), dp(8));
        et.setTextColor(getResources().getColor(R.color.tx));
        et.setHintTextColor(getResources().getColor(R.color.tx2));
        android.graphics.drawable.GradientDrawable eb = new android.graphics.drawable.GradientDrawable();
        eb.setColor(0x22FFFFFF);
        eb.setCornerRadius(dp(12));
        et.setBackground(eb);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, dp(52));
        elp.topMargin = dp(16);
        box.addView(et, elp);

        TextView get = dlgBtn("获取", false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(52));
        glp.topMargin = dp(12);
        box.addView(get, glp);
        get.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new android.content.Intent(PairActivity.this, HuaweiIdActivity.class), 21);
            }
        });

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(48));
        blp.topMargin = dp(18);
        TextView cancel = dlgBtn("取消", false);
        TextView save = dlgBtn("保存", true);
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, -1, 1f);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, -1, 1f);
        c2.leftMargin = dp(12);
        bottom.addView(cancel, c1);
        bottom.addView(save, c2);
        box.addView(bottom, blp);

        dlg.setView(box);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dlg.dismiss(); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String id = et.getText() == null ? "" : et.getText().toString().trim();
                if (!HuaweiAuth.validId(id)) {
                    Toast.makeText(PairActivity.this, "华为账号 ID 需要是 1-19 位数字。", Toast.LENGTH_SHORT).show();
                    return;
                }
                HuaweiAuth.putAccount(PairActivity.this, addr, id);
                HuaweiAuth.putKnown(PairActivity.this, addr, name);
                Bridge.init(PairActivity.this);
                Bridge.setAccount(addr, id, name);
                dlg.dismiss();
                rows.clear();
                list.removeAllViews();
                addBonded();
                if (connectAfter) connect(addr, name);
            }
        });
        dlg.show();
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 21 && res == RESULT_OK && data != null && currentEt != null) {
            String id = data.getStringExtra(HuaweiIdActivity.EXTRA_HUAWEI_ID);
            if (id != null && id.length() > 0) {
                currentEt.setText(id);
                currentEt.setSelection(id.length());
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (ble != null) ble.destroy();
        super.onDestroy();
    }
}