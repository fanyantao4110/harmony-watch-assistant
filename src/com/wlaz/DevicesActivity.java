package com.wlaz;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备管理：列出本机连过的手表（UDID / 名称 / 是否已进 AGC），
 * 支持同步到 AGC 调试设备列表、一键申请 Profile、删除条目。
 */
public class DevicesActivity extends Activity {

    private final List<DeviceStore.Dev> data = new ArrayList<DeviceStore.Dev>();
    private BaseAdapter adapter;
    private TextView tip;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_devices);
        tip = (TextView) findViewById(R.id.tip);
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.sync).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncAll();
            }
        });

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Object getItem(int i) {
                return data.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                if (cv == null) cv = LayoutInflater.from(DevicesActivity.this).inflate(R.layout.item_ble, parent, false);
                DeviceStore.Dev d = data.get(i);
                ((TextView) cv.findViewById(R.id.name)).setText(d.name.length() == 0 ? "HUAWEI Watch" : d.name);
                ((TextView) cv.findViewById(R.id.details)).setText(shortUdid(d.udid)
                        + (d.mac.length() > 0 ? "  ·  " + d.mac : ""));
                ((TextView) cv.findViewById(R.id.status)).setText(d.inAgc ? "AGC 已登记" : "未同步");
                final DeviceStore.Dev dev = d;
                cv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        actions(dev);
                    }
                });
                return cv;
            }
        };
        ((ListView) findViewById(R.id.list)).setAdapter(adapter);
        reload();
    }

    private static String shortUdid(String u) {
        if (u == null) return "";
        if (u.length() <= 16) return "UDID " + u;
        return "UDID " + u.substring(0, 8) + "…" + u.substring(u.length() - 6);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(DeviceStore.all(this));
        adapter.notifyDataSetChanged();
        tip.setText(data.isEmpty()
                ? "还没有设备。连接手表后会自动登记，这里就会出现。"
                : "共 " + data.size() + " 台设备，点击可管理。连新手表会自动添加。");
    }

    private void actions(final DeviceStore.Dev d) {
        String[] items = {"同步到 AGC 并申请 Profile", "复制 UDID", "删除记录"};
        new AlertDialog.Builder(this)
                .setTitle(d.name.length() == 0 ? "HUAWEI Watch" : d.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        if (which == 0) provisionOne(d);
                        else if (which == 1) {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("udid", d.udid));
                            Toast.makeText(DevicesActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                        } else {
                            DeviceStore.remove(DevicesActivity.this, d.udid);
                            reload();
                        }
                    }
                }).show();
    }

    private void provisionOne(final DeviceStore.Dev d) {
        if (!AgcApi.hasCredential(this)) {
            Toast.makeText(this, "请先在「我的 → DevEco 账号」登录开发者账号", Toast.LENGTH_LONG).show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在申请 Profile…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = "";
                try {
                    StringBuilder log = new StringBuilder();
                    byte[] p7b = AgcApi.provisionForWatch(DevicesActivity.this, d.udid, d.name, log);
                    saveP7b(p7b);
                    DeviceStore.markAgc(DevicesActivity.this, d.udid, AgcApi.p(DevicesActivity.this).getString("profile_id", ""));
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fe = err;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        if (fe.length() == 0) {
                            Toast.makeText(DevicesActivity.this, "Profile 已申请并保存", Toast.LENGTH_LONG).show();
                            reload();
                        } else {
                            Toast.makeText(DevicesActivity.this, "失败：" + fe, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void saveP7b(byte[] p7b) throws Exception {
        File f = new File(AgcApi.profileDir(this), "wl_debug.p7b");
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(p7b);
        fo.close();
    }

    private void syncAll() {
        if (!AgcApi.hasCredential(this)) {
            Toast.makeText(this, "请先在「我的 → DevEco 账号」登录开发者账号", Toast.LENGTH_LONG).show();
            return;
        }
        final List<DeviceStore.Dev> todo = new ArrayList<DeviceStore.Dev>(data);
        if (todo.isEmpty()) {
            Toast.makeText(this, "没有可同步的设备", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在同步 " + todo.size() + " 台设备…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                final StringBuilder errs = new StringBuilder();
                for (DeviceStore.Dev d : todo) {
                    try {
                        AgcApi.Device dev = AgcApi.ensureDevice(DevicesActivity.this, d.udid, d.name);
                        DeviceStore.markAgc(DevicesActivity.this, d.udid, dev.id);
                        ok++;
                    } catch (Exception e) {
                        errs.append(d.name).append(": ").append(e.getMessage()).append('\n');
                    }
                }
                final int fok = ok;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        reload();
                        String m = "已同步 " + fok + "/" + todo.size() + " 台";
                        if (errs.length() > 0) m += "\n" + errs;
                        new AlertDialog.Builder(DevicesActivity.this)
                                .setTitle("同步结果").setMessage(m)
                                .setPositiveButton("好", null).show();
                    }
                });
            }
        }).start();
    }
}