package com.wlaz;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_HUAWEI_ID = 1001;
    private static final int REQ_AGC_FILE = 1002;

    private android.view.ViewGroup host;
    private LinearLayout navStore, navDevice, navMe, bleList;
    private ImageView navStoreIcon, navDeviceIcon, navMeIcon;
    private TextView navStoreText, navDeviceText, navMeText;

    private View pageStore;
    private EditText search;
    private ListView list;
    private ProgressBar loading;
    private LinearLayout errBox;
    private TextView btnRetry, segApp, segFace;

    private View pageDevice, hasDev;
    private TextView btnGo, btnScan, scanHint, devName, devAddr, devBattery, devRssi, devState, devBond, devMem, devCounts, accValue, btnAcc;
    private View statsRow;
    private TextView btnDisconnect, btnPair, btnApps, btnFaces;
    private ProgressBar scanProgress;
    private View emptyView;
    private Handler poll;
    private Handler tick;
    private String curAddr = "";
    private String pendingAddr = "";
    private String pendingName = "";
    private EditText currentIdInput;
    private boolean manualDisconnected = false;
    private long lastAutoConnect = 0L;
    private int infoTick = 0;
    private String registeredAddr = "";

    private View pageMe;
    private TextView modeSystem, modeDay, modeNight, meServer, meCount;
    private TextView meAgcState, meDeviceCount;

    private Api.Store store;
    private Adapter adapter;
    private Ble ble;
    private int tab = 0;

    private final HashMap<String, View> bleRows = new HashMap<String, View>();

    @Override
    protected void onCreate(Bundle b) {
        Theme.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        HuaweiAuth.migrate(this);
        Bridge.init(this);
        Img.init(getApplicationContext());
        Bridge.log("MainActivity onCreate bridgeReady=" + Bridge.ready() + " error=" + Bridge.error());

        host = (android.view.ViewGroup) findViewById(R.id.pageHost);
        navStore = (LinearLayout) findViewById(R.id.navStore);
        navDevice = (LinearLayout) findViewById(R.id.navDevice);
        navMe = (LinearLayout) findViewById(R.id.navMe);
        navStoreIcon = (ImageView) findViewById(R.id.navStoreIcon);
        navDeviceIcon = (ImageView) findViewById(R.id.navDeviceIcon);
        navMeIcon = (ImageView) findViewById(R.id.navMeIcon);
        navStoreText = (TextView) findViewById(R.id.navStoreText);
        navDeviceText = (TextView) findViewById(R.id.navDeviceText);
        navMeText = (TextView) findViewById(R.id.navMeText);

        LayoutInflater inf = LayoutInflater.from(this);
        pageStore = inf.inflate(R.layout.page_store, host, false);
        pageDevice = inf.inflate(R.layout.page_device, host, false);
        pageMe = inf.inflate(R.layout.page_me, host, false);
        host.addView(pageStore);
        host.addView(pageDevice);
        host.addView(pageMe);

        navStore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(0);
            }
        });
        navDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(1);
            }
        });
        navMe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(2);
            }
        });

        bindStore();
        bindDevice();
        bindMe();
        show(0);
        load();
        Dl.askPerm(this);

        tick = new Handler();
        tick.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (store != null) return;
                load();
            }
        }, 15000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPoll();
        try {
            syncCurDevice();
            if (curAddr.length() == 0) {
                String a = HuaweiAuth.p(this).getString("last_device_", "");
                if (a != null && a.length() > 0) curAddr = a;
            }
            if (curAddr.length() > 0) {
                Bridge.init(this);
                if (!manualDisconnected && isReallyConnected(curAddr)) paintConnected(nameOf(curAddr), curAddr);
                else {
                    paintDisconnected();
                    autoReconnect();
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private void show(int idx) {
        pageStore.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageDevice.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageMe.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);

        int on = getResources().getColor(R.color.blue);
        int off = getResources().getColor(R.color.tx2);
        ImageView[] icons = {navStoreIcon, navDeviceIcon, navMeIcon};
        TextView[] texts = {navStoreText, navDeviceText, navMeText};
        for (int i = 0; i < 3; i++) {
            icons[i].setColorFilter(idx == i ? on : off);
            texts[i].setTextColor(idx == i ? on : off);
            texts[i].setTypeface(null, idx == i ? Typeface.BOLD : Typeface.NORMAL);
        }

        if (idx == 1) {
            paintAcc();
            boolean devOn = ble != null && ble.connected;
            if (!devOn && curAddr.length() > 0 && Bridge.ready()) {
                Object[] ci = Bridge.info(curAddr);
                devOn = ci != null && Boolean.TRUE.equals(ci[3]);
            }
            if (devOn) {
                paintConnected(ble != null && ble.connected ? ble.name : nameOf(curAddr),
                        ble != null && ble.connected ? ble.addr : curAddr);
            } else {
                paintDisconnected();
                if (ble != null && ble.permsOk() && ble.enabled()) {
                    addBondedHuawei();
                    if (!ble.connected) {
                        ble.startScan();
                        updateScanUi();
                        setStatus("正在扫描蓝牙智能手表");
                    }
                } else {
                    tryReconnect();
                }
            }
        }
    }

    // ================= 应用页 =================
    private void bindStore() {
        search = (EditText) pageStore.findViewById(R.id.search);
        list = (ListView) pageStore.findViewById(R.id.list);
        loading = (ProgressBar) pageStore.findViewById(R.id.loading);
        errBox = (LinearLayout) pageStore.findViewById(R.id.errBox);
        btnRetry = (TextView) pageStore.findViewById(R.id.btnRetry);
        segApp = (TextView) pageStore.findViewById(R.id.segApp);
        segFace = (TextView) pageStore.findViewById(R.id.segFace);

        View btnTheme = pageStore.findViewById(R.id.btnTheme);
        if (btnTheme != null) btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                themeDialog();
            }
        });
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        segApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tab = 0;
                paintSeg();
                render();
            }
        });
        segFace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tab = 1;
                paintSeg();
                render();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });

        adapter = new Adapter(this, new Adapter.Listener() {
            @Override
            public void onOpen(AppItem a) {
                Intent i = new Intent(MainActivity.this, DetailActivity.class);
                i.putExtra("id", a.id);
                startActivity(i);
            }

            @Override
            public void onGet(AppItem a) {
                Dl.pick(MainActivity.this, a);
            }
        });
        list.setAdapter(adapter);
        paintSeg();
    }

    private void paintSeg() {
        segApp.setBackgroundResource(tab == 0 ? R.drawable.seg_on : 0);
        segFace.setBackgroundResource(tab == 1 ? R.drawable.seg_on : 0);
        segApp.setTextColor(getResources().getColor(tab == 0 ? R.color.tx : R.color.tx2));
        segFace.setTextColor(getResources().getColor(tab == 1 ? R.color.tx : R.color.tx2));
        segApp.setTypeface(null, tab == 0 ? Typeface.BOLD : Typeface.NORMAL);
        segFace.setTypeface(null, tab == 1 ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void themeDialog() {
        final int m = Theme.mode(this);
        String[] items = {"跟随系统", "日间", "夜间"};
        new AlertDialog.Builder(this)
                .setTitle("外观")
                .setSingleChoiceItems(items, m, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        setMode(which);
                    }
                })
                .show();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        errBox.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Api.Store s = Api.load();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            store = s;
                            loading.setVisibility(View.GONE);
                            if (meCount != null) meCount.setText(s.downloadable().size() + " 个可下载");
                            render();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loading.setVisibility(View.GONE);
                            errBox.setVisibility(View.VISIBLE);
                            TextView t = (TextView) errBox.findViewById(R.id.errText);
                            if (t != null) t.setText("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void render() {
        if (adapter == null) return;
        if (store == null) {
            adapter.set(new ArrayList<AppItem>());
            return;
        }
        String q = search.getText().toString().trim();
        ArrayList<AppItem> out = new ArrayList<AppItem>();
        for (AppItem a : store.apps) {
            if (!a.match(q)) continue;
            if (tab == 0) {
                // 「可下载应用」：apps.json 只是应用目录（156 条），
                // pkgs.json 才是真有包的（目前 5 条）。没有包的直接不列。
                if (!a.downloadable()) continue;
            } else {
                // 「悬赏应用」：目录里有、但还没人做出包的 —— 也就是有缺口机型。
                if (a.downloadable()) continue;
                if (a.wantedDevs().isEmpty()) continue;
            }
            out.add(a);
        }
        adapter.set(out);
    }

    // ================= 设备页 =================
    private void bindDevice() {
        hasDev = pageDevice.findViewById(R.id.hasDev);
        btnScan = (TextView) pageDevice.findViewById(R.id.btnScan);
        btnGo = (TextView) pageDevice.findViewById(R.id.btnGo);
        btnGo.bringToFront();
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPairPage();
            }
        });
        scanHint = (TextView) pageDevice.findViewById(R.id.scanHint);
        bleList = (LinearLayout) pageDevice.findViewById(R.id.bleList);
        statsRow = pageDevice.findViewById(R.id.statsRow);
        devName = (TextView) pageDevice.findViewById(R.id.devName);
        devAddr = (TextView) pageDevice.findViewById(R.id.devAddr);
        devBattery = (TextView) pageDevice.findViewById(R.id.devBattery);
        devRssi = (TextView) pageDevice.findViewById(R.id.devRssi);
        devState = (TextView) pageDevice.findViewById(R.id.devState);
        devBond = (TextView) pageDevice.findViewById(R.id.devBond);
        devMem = (TextView) pageDevice.findViewById(R.id.devMem);
        devCounts = (TextView) pageDevice.findViewById(R.id.devCounts);
        accValue = (TextView) pageDevice.findViewById(R.id.accValue);
        btnAcc = (TextView) pageDevice.findViewById(R.id.btnAcc);
        btnAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAccountDialog(curAddr, true);
            }
        });
        btnDisconnect = (TextView) pageDevice.findViewById(R.id.btnDisconnect);
        btnApps = (TextView) pageDevice.findViewById(R.id.btnApps);
        btnApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, AppManagerActivity.class);
                i.putExtra("addr", curAddr);
                i.putExtra("type", 1);
                startActivity(i);
            }
        });
        btnFaces = (TextView) pageDevice.findViewById(R.id.btnFaces);
        btnFaces.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, AppManagerActivity.class);
                i.putExtra("addr", curAddr);
                i.putExtra("type", 2);
                startActivity(i);
            }
        });
        btnPair = (TextView) pageDevice.findViewById(R.id.btnPair);
        btnPair.setVisibility(View.GONE);
        View accRow = pageDevice.findViewById(R.id.accRow);
        if (accRow != null) accRow.setVisibility(View.GONE);

        scanProgress = (ProgressBar) pageDevice.findViewById(R.id.scanProgress);
        scanHint.setVisibility(View.GONE);
        btnScan.setVisibility(View.GONE);
        bleList.setVisibility(View.GONE);
        scanProgress.setVisibility(View.GONE);

        ble = new Ble(this);
        ble.setCb(new Ble.Cb() {
            @Override
            public void onState(String text) {
                setStatus(text);
            }

            @Override
            public void onFound(String name, String addr, int rssi, int bond) {
                addRow(name, addr, rssi, bond);
            }

            @Override
            public void onBond(int bond) {
                if (curAddr != null && curAddr.length() > 0 && devBond != null) {
                    devBond.setText(bondLabel(bond));
                }
            }

            @Override
            public void onConnected(String name, String addr) {
                curAddr = addr;
                pendingAddr = "";
                manualDisconnected = false;
                HuaweiAuth.putKnown(MainActivity.this, addr, name);
                paintConnected(name, addr);
                startPoll();
            }

            @Override
            public void onBattery(int pct) {
                if (devBattery != null && pct >= 0) devBattery.setText(pct + "%");
            }

            @Override
            public void onRssi(int rssi) {
                if (devRssi != null) devRssi.setText(rssi != 0 ? rssi + " dBm" : "--");
            }

            @Override
            public void onDisconnected() {
                paintDisconnected();
                if (!manualDisconnected) autoReconnect();
            }
        });

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ble.permsOk()) {
                    ble.askPerms();
                    return;
                }
                if (ble.scanning) {
                    ble.stopScan();
                } else {
                    bleList.removeAllViews();
                    bleRows.clear();
                    ble.startScan();
                    updateScanUi();
                }
            }
        });

        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualDisconnected = true;
                if (ble != null) ble.disconnect();
                Bridge.disconnect(MainActivity.this, curAddr);
                paintDisconnected();
                setStatus("已断开与 " + nameOf(curAddr) + " 的连接，点“前往连接”可重新连接。");
            }
        });

        btnPair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPairPage();
            }
        });

        restoreKnown();
        paintAcc();
        paintDisconnected();
    }

    private void restoreKnown() {
        Map<String, String> known = HuaweiAuth.knownDevices(this);
        for (String mac : known.keySet()) {
            addRow(known.get(mac), mac, 0, ble == null ? 10 : ble.bondState(mac));
        }
        addBondedHuawei();
        renderRows();
    }

    private void addBondedHuawei() {
        if (ble == null) return;
        for (String[] d : ble.bondedDevices()) {
            if (!HuaweiAuth.looksLikeHuawei(d[0])) continue;
            addRow(d[0], d[1], 0, bondStateOf(d[1]));
        }
    }

    private void addRow(String name, String addr, int rssi, int bond) {
        if (addr.equals(curAddr) && hasDev != null && hasDev.getVisibility() == View.VISIBLE) return;
        boolean known = HuaweiAuth.isKnown(this, addr) || bleRows.containsKey(addr);
        if (!known && !HuaweiAuth.looksLikeHuawei(name)) return;
        if (!known) HuaweiAuth.putKnown(this, addr, name);

        View row = bleRows.get(addr);
        if (row == null) {
            row = LayoutInflater.from(this).inflate(R.layout.item_ble, bleList, false);
            if (emptyView != null) {
                bleList.removeView(emptyView);
                emptyView = null;
            }
            final String fAddr = addr;
            final String fName = name;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    connectCandidate(fName, fAddr);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showAccountDialog(fAddr, false);
                    return true;
                }
            });
            bleRows.put(addr, row);
            bleList.addView(row);
        }
        ((TextView) row.findViewById(R.id.name)).setText(name);
        ((TextView) row.findViewById(R.id.status)).setText(bondLabel(bond));
        renderRow(row, addr);
    }

    private void renderRow(View row, String addr) {
        if (row == null) return;
        boolean hide = hasDev != null && hasDev.getVisibility() == View.VISIBLE && addr.equals(curAddr);
        row.setVisibility(hide ? View.GONE : View.VISIBLE);
        TextView det = (TextView) row.findViewById(R.id.details);
        String acc = HuaweiAuth.account(this, addr);
        if (acc.length() == 0) det.setText(addr + "\n未设置华为账号");
        else det.setText(addr + "\n华为账号: " + HuaweiAuth.mask(acc));
        ((TextView) row.findViewById(R.id.status)).setText(bondLabel(bondStateOf(addr)));
    }

    private void renderRows() {
        for (String a : bleRows.keySet()) {
            View v = bleRows.get(a);
            if (v != null) renderRow(v, a);
        }
        if (bleRows.isEmpty()) {
            if (emptyView == null) {
                TextView t = new TextView(this);
                t.setText("暂未发现设备，请保持手表处于配对模式。");
                t.setTextSize(14);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(getResources().getColor(R.color.tx2));
                t.setPadding(0, dp(24), 0, dp(24));
                emptyView = t;
                bleList.addView(t);
            }
        } else {
            if (ble != null && ble.scanning) setStatus("正在搜索附近的手表…");
            else setStatus("选择设备开始连接，长按可修改华为账号。");
        }
        if (curAddr.length() > 0) {
            devBond.setText(bondLabel(bondStateOf(curAddr)));
            btnPair.setVisibility(View.GONE);
        }
    }

    private void connectCandidate(final String name, final String addr) {
        pendingAddr = addr;
        pendingName = name;
        String acc = HuaweiAuth.account(this, addr);
        if (acc.length() == 0) {
            showAccountDialog(addr, true);
            return;
        }
        connectTransport(name, addr, acc);
    }

    private void connectTransport(String name, String addr, String account) {
        HuaweiAuth.putKnown(this, addr, name);
        HuaweiAuth.p(this).edit().putString("last_device_", addr).apply();
        if (account != null && account.length() > 0) HuaweiAuth.putAccount(this, addr, account);
        Bridge.init(this);
        if (account != null && account.length() > 0) Bridge.setAccount(addr, account, name);
        boolean ok = Bridge.connect(this, addr);
        Bridge.log("connect addr=" + addr + " accountLen=" + (account == null ? 0 : account.length()) + " ok=" + ok + " err=" + Bridge.error());
        if (ble != null) ble.connect(name, addr);
        setStatus("正在连接 " + name + "…");
        startPoll();
    }

    private void openPairPage() {
        manualDisconnected = false;
        startActivity(new Intent(this, PairActivity.class));
    }

    private void tryReconnect() {
        if (ble == null || ble.connected) return;
        if (!ble.permsOk()) return;
        String a = ble.savedAddr();
        if (a != null && a.length() > 0) {
            setStatus("正在重连上次的手表…");
            ble.connectSaved();
        }
    }

    private void updateScanUi() {
        if (btnScan == null || ble == null) return;
        btnScan.setText(ble.scanning ? "停止扫描" : "开始扫描");
        if (scanProgress != null) scanProgress.setVisibility(ble.scanning ? View.VISIBLE : View.GONE);
    }

    private String bondLabel(int s) {
        if (s == 12) return "已配对";
        if (s == 11) return "配对中";
        return "未配对";
    }

    private int bondStateOf(String addr) {
        return ble == null ? 10 : ble.bondState(addr);
    }

    private void startPoll() {
        if (poll != null) return;
        poll = new Handler(Looper.getMainLooper());
        poll.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollInfo();
                if (poll != null) poll.postDelayed(this, 1500);
            }
        }, 800);
    }

    private boolean isReallyConnected(String addr) {
        if (ble != null && ble.connected) return true;
        if (!Bridge.ready()) return false;
        Object[] in = Bridge.info(addr);
        return in != null && Boolean.TRUE.equals(in[3]);
    }

    private void pollInfo() {
        syncCurDevice();
        if (curAddr.length() == 0 || !Bridge.ready()) return;
        Object[] in = Bridge.info(curAddr);
        if (in == null) return;
        boolean on = !manualDisconnected && (Boolean.TRUE.equals(in[3]) || (ble != null && ble.connected));
        if (!on) {
            paintDisconnected();
            if (manualDisconnected) setStatus("已断开与 " + nameOf(curAddr) + " 的连接，点“前往连接”可重新连接。");
            else {
                setStatus("未连接 " + nameOf(curAddr) + "，正在自动重连…");
                autoReconnect();
            }
            return;
        }
        paintConnected(String.valueOf(in[0]), curAddr);
        if (!curAddr.equals(registeredAddr) && infoTick % 8 == 0) {
            registeredAddr = curAddr;
            final String a = curAddr;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        OneKeyInstall.autoRegister(MainActivity.this, a);
                    } catch (Throwable ignore) {
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            paintAgc();
                        }
                    });
                }
            }).start();
        }
        Object bv = in[1];
        if (bv instanceof Integer && ((Integer) bv) >= 0) devBattery.setText(bv + "%");
        infoTick++;
        if (infoTick % 3 == 1) Bridge.requestApps(curAddr);
        if (infoTick == 1 || infoTick % 10 == 0) Bridge.requestStatus(curAddr);
        if (infoTick % 2 == 0) setStatus("已连接 " + in[0] + "（华为协议原实现）");
    }

    private boolean syncCurDevice() {
        String last = HuaweiAuth.p(this).getString("last_device_", "");
        if (last == null) last = "";
        if (last.length() == 0) return false;
        if (last.equalsIgnoreCase(curAddr)) return false;
        String prev = curAddr;
        curAddr = last;
        manualDisconnected = false;
        lastAutoConnect = 0;
        infoTick = 0;
        Bridge.log("switch device " + prev + " -> " + last);
        if (prev != null && prev.length() > 0 && !prev.equalsIgnoreCase(last)) {
            Bridge.disconnect(this, prev);
            if (ble != null && ble.connected) ble.disconnect();
        }
        if (bleRows != null) bleRows.clear();
        return true;
    }

    private void autoReconnect() {
        if (manualDisconnected) return;
        if (curAddr.length() == 0 || !Bridge.ready()) return;
        if (ble != null && ble.connected) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoConnect < 20000) return;
        lastAutoConnect = now;

        String acc = HuaweiAuth.account(this, curAddr);
        if (acc.length() == 0) {
            String b = Bridge.getAccount(curAddr);
            if (b.length() > 0) {
                HuaweiAuth.putAccount(this, curAddr, b);
                acc = b;
            }
        }
        Bridge.log("autoReconnect addr=" + curAddr + " accountLen=" + acc.length());
        if (acc.length() > 0) Bridge.setAccount(curAddr, acc, nameOf(curAddr));
        if (Bridge.connect(this, curAddr)) {
            setStatus("正在重连 " + nameOf(curAddr) + "...");
            startPoll();
        } else {
            setStatus("未连接 " + nameOf(curAddr) + "：正在尝试重连（" + Bridge.error() + "）");
        }
    }

    private void paintConnected(String name, String addr) {
        curAddr = addr;
        manualDisconnected = false;
        hasDev.setVisibility(View.VISIBLE);
        if (btnGo != null) btnGo.setVisibility(View.GONE);

        Object[] in = Bridge.info(addr);
        String dn = in == null || in[0] == null || String.valueOf(in[0]).length() == 0 ? name : String.valueOf(in[0]);
        devName.setText(dn);
        devAddr.setText(addr);
        if (statsRow != null) statsRow.setVisibility(View.VISIBLE);
        if (devCounts != null) devCounts.setVisibility(View.VISIBLE);
        if (btnDisconnect != null) btnDisconnect.setVisibility(View.VISIBLE);
        if (btnApps != null) btnApps.setVisibility(View.VISIBLE);
        if (btnFaces != null) btnFaces.setVisibility(View.VISIBLE);
        setDevActionsEnabled(true);
        if (btnPair != null) btnPair.setVisibility(View.GONE);
        if (btnGo != null) {
            btnGo.setVisibility(View.GONE);
            btnGo.setText("前往连接");
            btnGo.setEnabled(true);
            btnGo.setAlpha(1f);
        }
        OneKeyInstall.setCurrent(addr, dn);

        int bat = ble != null ? ble.battery : -1;
        if (bat < 0 && in != null && in[1] instanceof Integer) bat = (Integer) in[1];
        devBattery.setText(bat >= 0 ? bat + "%" : "--");

        int mem = -1;
        if (in != null && in[6] instanceof Integer) mem = (Integer) in[6];
        if (mem > 0) devMem.setText(mem < 1024 ? mem + " MB" : String.format(Locale.US, "%.2f GB", mem / 1024.0));
        else devMem.setText("--");

        int apps = 0;
        if (in != null && in[7] instanceof Integer) apps = (Integer) in[7];
        int faces = 0;
        if (in != null && in[8] instanceof Integer) faces = (Integer) in[8];
        devCounts.setText(apps + " 个应用 · " + faces + " 个表盘");
        devState.setText("当前设备已连接，可进行设备管理与应用安装。");

        String acc = HuaweiAuth.account(this, addr);
        accValue.setText(acc.length() == 0 ? "华为账号：未设置" : "华为账号：" + HuaweiAuth.mask(acc));
        devBond.setText(bondLabel(bondStateOf(addr)));
        if (btnPair != null) btnPair.setVisibility(View.GONE);

        int rssi = ble != null ? ble.rssi : 0;
        devRssi.setText(rssi != 0 ? rssi + " dBm" : "--");

        renderRows();
        if (ble != null && ble.huaweiReady()) setStatus("已连接 " + dn + "（华为 FE86 已就绪）");
        else setStatus("已连接 " + dn);
        paintAcc();
        if (ble != null) ble.refresh();
    }

    private void paintDisconnected() {
        String a = curAddr;
        if (a == null || a.length() == 0) a = HuaweiAuth.p(this).getString("last_device_", "");
        if (a != null && a.length() > 0) {
            curAddr = a;
            hasDev.setVisibility(View.VISIBLE);
            devName.setText(nameOf(a));
            devAddr.setText(a);
            if (statsRow != null) statsRow.setVisibility(View.GONE);
            if (devCounts != null) devCounts.setVisibility(View.GONE);
            devBattery.setText("--");
            devMem.setText("--");
            devRssi.setText("--");
            devState.setText("当前设备未连接，请前往配对页面重新连接。");
            String acc = HuaweiAuth.account(this, a);
            accValue.setText(acc.length() == 0 ? "华为账号：未设置" : "华为账号：" + HuaweiAuth.mask(acc));
            devBond.setText(bondLabel(bondStateOf(a)));
        } else {
            if (hasDev != null) hasDev.setVisibility(View.GONE);
            if (statsRow != null) statsRow.setVisibility(View.GONE);
            if (devCounts != null) devCounts.setVisibility(View.GONE);
            if (devBattery != null) devBattery.setText("--");
            if (devRssi != null) devRssi.setText("--");
            if (devState != null) devState.setText("当前还没有可管理的手表，请先前往配对页面连接设备。");
            if (devMem != null) devMem.setText("--");
            if (devBond != null) devBond.setText("未配对");
        }
        if (btnPair != null) btnPair.setVisibility(View.GONE);
        if (btnDisconnect != null) btnDisconnect.setVisibility(View.GONE);
        if (btnApps != null) btnApps.setVisibility(View.GONE);
        if (btnFaces != null) btnFaces.setVisibility(View.GONE);
        setDevActionsEnabled(false);
        if (btnGo != null) {
            btnGo.setVisibility(View.VISIBLE);
            btnGo.setText("前往连接");
            btnGo.setEnabled(true);
            btnGo.setAlpha(1f);
        }
        if (btnScan != null && ble != null && !ble.scanning) btnScan.setText("开始扫描");
    }

    private void setDevActionsEnabled(boolean on) {
        View[] vs = {btnDisconnect, btnApps == null ? null : (View) btnApps.getParent(),
                btnFaces == null ? null : (View) btnFaces.getParent()};
        for (View v : vs) {
            if (v == null) continue;
            v.setEnabled(on);
            v.setAlpha(on ? 1f : 0.35f);
            v.setClickable(on);
        }
        if (btnDisconnect != null) btnDisconnect.setEnabled(on);
        if (btnApps != null) btnApps.setEnabled(on);
        if (btnFaces != null) btnFaces.setEnabled(on);
    }

    private void setStatus(String s) {
        if (scanHint != null) scanHint.setText(s);
    }

    private String nameOf(String addr) {
        View row = bleRows.get(addr);
        if (row != null) return ((TextView) row.findViewById(R.id.name)).getText().toString();
        Map<String, String> known = HuaweiAuth.knownDevices(this);
        if (known.containsKey(addr)) return known.get(addr);
        return "HUAWEI Watch";
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private AlertDialog showAccountDialog(final String addr, final boolean connectAfter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(18), p, dp(14));
        box.setBackgroundColor(0xffffffff);

        TextView title = new TextView(this);
        title.setText("华为账号");
        title.setGravity(Gravity.CENTER);
        title.setTextSize(20);
        title.setTextColor(getResources().getColor(R.color.tx));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("连接前必须填写正确的华为账号 ID。它不是手机号、邮箱、设备 UDID 或随意填写的数字。不知道账号 ID 时请点击下方“获取”自行查询；因乱填信息造成的连接或设备问题需由用户自行承担。");
        desc.setGravity(Gravity.CENTER);
        desc.setTextSize(13);
        desc.setTextColor(getResources().getColor(R.color.tx2));
        desc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(10);
        box.addView(desc, dlp);

        final EditText et = new EditText(this);
        currentIdInput = et;
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setHint("华为账号ID");
        et.setText(HuaweiAuth.account(this, addr));
        et.setSelection(et.getText().length());
        et.setPadding(dp(16), dp(8), dp(16), dp(8));
        et.setTextColor(getResources().getColor(R.color.tx));
        et.setHintTextColor(getResources().getColor(R.color.tx2));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, dp(52));
        elp.topMargin = dp(16);
        box.addView(et, elp);

        TextView get = new TextView(this);
        get.setText("获取");
        get.setGravity(Gravity.CENTER);
        get.setTextSize(15);
        get.setTypeface(Typeface.DEFAULT_BOLD);
        get.setTextColor(getResources().getColor(R.color.blue));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(52));
        glp.topMargin = dp(12);
        box.addView(get, glp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextSize(15);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(getResources().getColor(R.color.blue));
        TextView save = new TextView(this);
        save.setText("保存");
        save.setGravity(Gravity.CENTER);
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(0xffffffff);
        save.setBackgroundResource(R.drawable.get_btn);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        slp.leftMargin = dp(12);
        row.addView(cancel, clp);
        row.addView(save, slp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(48));
        rlp.topMargin = dp(18);
        box.addView(row, rlp);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String id = et.getText().toString().trim();
                if (!HuaweiAuth.validId(id)) {
                    Toast.makeText(MainActivity.this, "华为账号ID 只能填数字", Toast.LENGTH_SHORT).show();
                    return;
                }
                HuaweiAuth.putAccount(MainActivity.this, addr, id);
                dlg.dismiss();
                if (connectAfter) {
                    connectTransport(pendingName.length() == 0 ? nameOf(addr) : pendingName, addr, id);
                }
            }
        });
        get.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, HuaweiIdActivity.class), REQ_HUAWEI_ID);
            }
        });
        dlg.show();
        return dlg;
    }

    // ================= 我的 =================
    private void bindMe() {
        modeSystem = (TextView) pageMe.findViewById(R.id.modeSystem);
        modeDay = (TextView) pageMe.findViewById(R.id.modeDay);
        modeNight = (TextView) pageMe.findViewById(R.id.modeNight);
        meServer = (TextView) pageMe.findViewById(R.id.meServer);
        meCount = (TextView) pageMe.findViewById(R.id.meCount);

        meServer.setText("fanyantao4110.github.io");
        // 长按可申请「所有文件访问」：授权后签名包会存到 /sdcard/腕连助手/签名包，
        // 未授权则自动落到应用专属目录，签名流程不受影响。
        if (meCount != null) {
            meCount.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Dl.askAllFiles(MainActivity.this);
                    return true;
                }
            });
        }
        paintMode();
        modeSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(Theme.SYSTEM);
            }
        });
        modeDay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(Theme.DAY);
            }
        });
        modeNight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(Theme.NIGHT);
            }
        });
        View refresh = pageMe.findViewById(R.id.meRefresh);
        if (refresh != null) refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
                Toast.makeText(MainActivity.this, "正在重新获取…", Toast.LENGTH_SHORT).show();
            }
        });
        meAgcState = (TextView) pageMe.findViewById(R.id.meAgcState);
        meDeviceCount = (TextView) pageMe.findViewById(R.id.meDeviceCount);
        pageMe.findViewById(R.id.meAgcImport).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importAgc();
            }
        });
        pageMe.findViewById(R.id.meDevices).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DevicesActivity.class));
            }
        });
        paintAgc();
    }

    private void paintAgc() {
        if (meAgcState == null) return;
        boolean ok = AgcApi.hasCredential(this);
        if (AgcApi.hasDevEcoLogin(this)) {
            meAgcState.setText("DevEco 已登录 " + AgcApi.accountLabel(this));
        } else if (ok) {
            meAgcState.setText("已导入 " + AgcApi.clientId(this));
        } else {
            meAgcState.setText("未登录");
        }
        meAgcState.setTextColor(getResources().getColor(ok ? R.color.blue : R.color.tx2));
        if (meDeviceCount != null) {
            int n = DeviceStore.all(this).size();
            meDeviceCount.setText(n == 0 ? "暂无设备" : n + " 台");
        }
    }

    private void importAgc() {
        new AlertDialog.Builder(this)
                .setTitle("DevEco 账号")
                .setItems(new String[]{"登录 DevEco 账号", "填写登录地址", "退出登录"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    devEcoLogin();
                                } else if (which == 1) {
                                    editLoginAddress();
                                } else {
                                    DevEco.logout(MainActivity.this);
                                    paintAgc();
                                    Toast.makeText(MainActivity.this, "已退出登录", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                .show();
    }

    /** DevEco 方式登录：拉起浏览器 → 本地回环收回 tempToken → 换 accessToken */
    private void devEcoLogin() {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("DevEco 登录");
        pd.setMessage("正在启动…");
        pd.setCancelable(true);
        pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                DevEco.stop();
            }
        });
        pd.show();
        DevEco.login(this, new DevEco.Listener() {
            @Override
            public void onProgress(String text) {
                if (pd.isShowing()) pd.setMessage(text);
            }

            @Override
            public void onDone(boolean ok, String message) {
                if (pd.isShowing()) pd.dismiss();
                paintAgc();
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 填写登录地址（留空 = 用 CN 站点默认授权页） */
    private void editLoginAddress() {
        final EditText et = new EditText(this);
        et.setHint(DevEco.HOST_CN + DevEco.AUTH_PATH);
        et.setText(AgcApi.p(this).getString(AgcApi.K_LOGIN_ADDR, ""));
        et.setMinLines(2);
        et.setGravity(Gravity.TOP | Gravity.LEFT);
        new AlertDialog.Builder(this)
                .setTitle("登录地址")
                .setMessage("留空使用默认：" + DevEco.HOST_CN + DevEco.AUTH_PATH + "\n"
                        + "只填域名会自动补授权页路径，?port= 由程序自动拼接。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        AgcApi.p(MainActivity.this).edit()
                                .putString(AgcApi.K_LOGIN_ADDR, et.getText().toString().trim())
                                .apply();
                        Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pasteAgc() {
        final EditText et = new EditText(this);
        et.setHint("把 agc-apiclient-*.json 的内容粘到这里");
        et.setMinLines(6);
        et.setGravity(Gravity.TOP | Gravity.LEFT);
        new AlertDialog.Builder(this)
                .setTitle("粘贴 AGC 密钥 JSON")
                .setView(et)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String err = AgcApi.importCredential(MainActivity.this, et.getText().toString());
                        if (err.length() > 0) Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                        else {
                            paintAgc();
                            Toast.makeText(MainActivity.this, "已导入", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void paintAcc() {
    }

    private void paintMode() {
        int m = Theme.mode(this);
        paintOne(modeSystem, m == Theme.SYSTEM);
        paintOne(modeDay, m == Theme.DAY);
        paintOne(modeNight, m == Theme.NIGHT);
    }

    private void paintOne(TextView v, boolean on) {
        v.setBackgroundResource(on ? R.drawable.seg_on : R.drawable.seg_bg);
        v.setTextColor(getResources().getColor(on ? R.color.tx : R.color.tx2));
    }

    private void setMode(int m) {
        Theme.setMode(this, m);
        paintMode();
        recreate();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_AGC_FILE && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                String text = new String(HapSigner.readAll(in), "UTF-8");
                String err = AgcApi.importCredential(this, text);
                if (err.length() > 0) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                else {
                    paintAgc();
                    Toast.makeText(this, "AGC 密钥已导入", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (req == REQ_HUAWEI_ID && res == RESULT_OK && data != null) {
            String id = data.getStringExtra(HuaweiIdActivity.EXTRA_HUAWEI_ID);
            if (id != null && id.length() > 0 && currentIdInput != null) {
                currentIdInput.setText(id);
                currentIdInput.setSelection(id.length());
            } else {
                Toast.makeText(this, "没有读取到华为 ID。", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == 21) {
            if (ble != null && ble.permsOk()) {
                scanHint.setText("权限已给，开始搜索…");
                btnScan.setText("停止扫描");
                bleList.removeAllViews();
                bleRows.clear();
                ble.startScan();
            } else if (scanHint != null) {
                scanHint.setText("没有蓝牙权限，扫不到设备");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tick != null) tick.removeCallbacksAndMessages(null);
        if (ble != null) ble.destroy();
    }
}