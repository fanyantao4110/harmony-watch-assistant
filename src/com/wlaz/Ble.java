package com.wlaz;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/** 蓝牙：扫描 / 配对 / 连接 / 读电量，真机可用 */
public class Ble {

    public interface Cb {
        void onState(String text);

        void onFound(String name, String addr, int rssi, int bond);

        void onBond(int bond);

        void onConnected(String name, String addr);

        void onBattery(int pct);

        void onRssi(int rssi);

        void onDisconnected();

        default void onPacket(String summary) {
        }
    }

    private static final UUID SVC_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR_BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb");
    private static final UUID SVC_HUAWEI = uuid16("FE86");
    private static final UUID CHR_HW_WRITE = uuid16("FE01");
    private static final UUID CHR_HW_READ = uuid16("FE02");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private Cb cb;

    public boolean scanning;
    private boolean discovering;
    public boolean connected;
    public String name = "";
    public String addr = "";
    public int battery = -1;
    public int rssi = 0;
    private int bond = BluetoothDevice.BOND_NONE;
    private BluetoothGattCharacteristic hwWrite, hwRead;
    private int hwMtu = 23;
    private final java.io.ByteArrayOutputStream hwBuf = new java.io.ByteArrayOutputStream();
    private int hwCmd = -1, hwSub = -1, hwFlag = -1;

    private final BroadcastReceiver recv = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent it) {
            String a = it.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                String nm = null;
                try {
                    nm = d.getName();
                } catch (Exception ignore) {
                }
                if (nm == null || nm.trim().length() == 0) return;
                final String fn = nm.trim();
                final String fa = d.getAddress();
                final int fr = it.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0);
                int bb = BluetoothDevice.BOND_NONE;
                try {
                    bb = d.getBondState();
                } catch (Exception ignore) {
                }
                final int fb = bb;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onFound(fn, fa, fr, fb);
                    }
                });
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                discovering = false;
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                BluetoothDevice d = it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final int st = it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (d != null && d.getAddress().equals(addr)) bond = st;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onBond(st);
                    }
                });
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int st = it.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (st == BluetoothAdapter.STATE_OFF) {
                    stopScan();
                    closeGatt();
                    toast("蓝牙已关闭");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (cb != null) cb.onDisconnected();
                        }
                    });
                }
            }
        }
    };

    public Ble(Activity a) {
        this.act = a;
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                a.registerReceiver(recv, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                a.registerReceiver(recv, f);
            }
        } catch (Exception ignore) {
        }
    }

    public void setCb(Cb c) {
        this.cb = c;
    }

    private static UUID uuid16(String short16) {
        return UUID.fromString("0000" + short16.toLowerCase(java.util.Locale.US) + "-0000-1000-8000-00805f9b34fb");
    }

    private SharedPreferences sp() {
        return act.getSharedPreferences("wl", Context.MODE_PRIVATE);
    }

    public String savedAddr() {
        return sp().getString("dev_addr", "");
    }

    public String savedName() {
        return sp().getString("dev_name", "");
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (cb != null) cb.onState(s);
            }
        });
    }

    public boolean supported() {
        return adapter != null;
    }

    public boolean enabled() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public static String[] perms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public boolean permsOk() {
        for (String p : perms()) {
            if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public void askPerms() {
        act.requestPermissions(perms(), 21);
    }

    private boolean locOn() {
        try {
            LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    public void startScan() {
        if (!permsOk()) {
            toast("需要蓝牙权限，正在申请…");
            askPerms();
            return;
        }
        if (!enabled()) {
            toast("蓝牙没开，请先打开蓝牙");
            return;
        }
        try {
            if (scanner == null) scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                toast("这台手机不支持 BLE 扫描");
                return;
            }
            if (scanning) return;
            scanning = true;
            toast("正在扫描附近设备…");
            ScanSettings st = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, st, scanCb);
            android.util.Log.i("WlazBle", "ble scan started");
            try {
                if (adapter.isDiscovering()) adapter.cancelDiscovery();
                discovering = adapter.startDiscovery();
            } catch (Exception ignore) {
            }
            ui.postDelayed(stopTask, 25000);
        } catch (SecurityException e) {
            toast("缺少蓝牙权限");
        } catch (Exception e) {
            toast("扫描失败：" + e.getMessage());
        }
    }

    private final Runnable stopTask = new Runnable() {
        @Override
        public void run() {
            if (scanning) {
                stopScan();
                toast("扫描结束，点「开始扫描」可再扫一次");
            }
        }
    };

    public void stopScan() {
        ui.removeCallbacks(stopTask);
        try {
            if (scanner != null && scanning) scanner.stopScan(scanCb);
        } catch (Exception ignore) {
        }
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignore) {
        }
        discovering = false;
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            final BluetoothDevice d = r.getDevice();
            if (d == null) return;
            android.util.Log.i("WlazBle", "adv " + d.getAddress() + " rssi=" + r.getRssi());
            String n = null;
            try {
                n = d.getName();
            } catch (SecurityException ignore) {
            }
            if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
            String fname = (n == null || n.trim().length() == 0) ? "" : n.trim();
            final String fn = fname;
            final String fa = d.getAddress();
            final int fr = r.getRssi();
            int b = BluetoothDevice.BOND_NONE;
            try {
                b = d.getBondState();
            } catch (Exception ignore) {
            }
            final int fb = b;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (cb != null) cb.onFound(fn, fa, fr, fb);
                }
            });
        }

        @Override
        public void onScanFailed(int code) {
            scanning = false;
            String s = "扫描失败（错误码 " + code + "）";
            if (code == 2) s = "扫描失败：蓝牙状态异常，关开蓝牙再试";
            if (code == 3) s = "扫描失败：权限不足，请到设置里打开「附近的设备」权限";
            toast(s);
        }
    };

    public java.util.List<String[]> bondedDevices() {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        try {
            if (adapter == null) return out;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n == null || n.trim().length() == 0) continue;
                out.add(new String[]{n.trim(), d.getAddress()});
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public int bondState(String address) {
        try {
            return adapter.getRemoteDevice(address).getBondState();
        } catch (Exception e) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    public void pair(String address) {
        try {
            if (adapter == null) return;
            BluetoothDevice d = adapter.getRemoteDevice(address);
            if (d.getBondState() == BluetoothDevice.BOND_NONE) {
                toast("正在发起配对…");
                d.createBond();
            } else if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                toast("已经配对过了");
            }
        } catch (SecurityException e) {
            toast("缺少蓝牙权限");
        } catch (Exception e) {
            toast("配对失败：" + e.getMessage());
        }
    }

    public void connect(final String address, final String shownName) {
        if (!permsOk()) {
            askPerms();
            return;
        }
        if (!enabled()) {
            toast("蓝牙没开");
            return;
        }
        stopScan();
        closeGatt();
        try {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            addr = address;
            name = (shownName == null || shownName.length() == 0) ? "未命名设备" : shownName;
            bond = d.getBondState();
            toast("正在连接 " + name + " …");
            gatt = d.connectGatt(act, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            ui.postDelayed(timeout, 15000);
        } catch (SecurityException e) {
            toast("缺少蓝牙权限");
        } catch (Exception e) {
            toast("连接失败：" + e.getMessage());
        }
    }

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (!connected) {
                toast("连接超时，把手表靠近手机再试一次");
                closeGatt();
                if (cb != null) cb.onDisconnected();
            }
        }
    };

    public void connectSaved() {
        String a = savedAddr();
        if (a != null && a.length() > 0) connect(a, savedName());
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g != gatt) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ui.removeCallbacks(timeout);
                connected = true;
                try {
                    g.discoverServices();
                } catch (SecurityException ignore) {
                }
                sp().edit().putString("dev_addr", addr).putString("dev_name", name).apply();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onConnected(name, addr);
                    }
                });
                try {
                    g.readRemoteRssi();
                } catch (SecurityException ignore) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ui.removeCallbacks(timeout);
                boolean was = connected;
                connected = false;
                battery = -1;
                if (status != 0 && was) toast("连接断开了（" + status + "）");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onDisconnected();
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) return;
            BluetoothGattService s = g.getService(SVC_BATTERY);
            if (s != null) {
                BluetoothGattCharacteristic c = s.getCharacteristic(CHR_BATTERY);
                if (c != null) {
                    try {
                        g.setCharacteristicNotification(c, true);
                        BluetoothGattDescriptor dd = c.getDescriptor(CCCD);
                        if (dd != null) {
                            dd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(dd);
                        }
                    } catch (Exception ignore) {
                    }
                    read(g, c);
                }
            }
            BluetoothGattService hs = g.getService(SVC_HUAWEI);
            if (hs != null) {
                hwWrite = hs.getCharacteristic(CHR_HW_WRITE);
                hwRead = hs.getCharacteristic(CHR_HW_READ);
                if (hwWrite != null && hwRead != null) {
                    try {
                        g.requestMtu(247);
                        g.setCharacteristicNotification(hwRead, true);
                        BluetoothGattDescriptor dd = hwRead.getDescriptor(CCCD);
                        if (dd != null) {
                            dd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(dd);
                        }
                    } catch (Exception ignore) {
                    }
                }
            }
            BluetoothGattService ds = g.getService(UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"));
            if (ds != null) {
                BluetoothGattCharacteristic c = ds.getCharacteristic(CHR_NAME);
                if (c != null) read(g, c);
            }
        }

        private void read(BluetoothGatt g, BluetoothGattCharacteristic c) {
            try {
                g.readCharacteristic(c);
            } catch (SecurityException ignore) {
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (c == null || c.getValue() == null) return;
            handle(c.getUuid(), c.getValue());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (value == null) return;
            handle(c == null ? null : c.getUuid(), value);
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (g == gatt && status == 0) hwMtu = mtu;
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value == null) return;
            if (c != null && CHR_HW_READ.equals(c.getUuid())) {
                feed(value);
                return;
            }
            handle(c == null ? null : c.getUuid(), value);
        }

        private void handle(UUID u, byte[] v) {
            if (v.length == 0) return;
            if (u != null && CHR_NAME.equals(u)) {
                try {
                    String s = new String(v, "UTF-8").trim();
                    if (s.length() > 0) {
                        name = s;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (cb != null) cb.onConnected(name, addr);
                            }
                        });
                    }
                } catch (Exception ignore) {
                }
                return;
            }
            final int pct = v[0] & 0xff;
            if (pct > 100) return;
            battery = pct;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (cb != null) cb.onBattery(pct);
                }
            });
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, final int r, int status) {
            rssi = r;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (cb != null) cb.onRssi(r);
                }
            });
        }
    };

    private void feed(byte[] data) {
        if (data.length < 5) return;
        int flag = data[3] & 0xff;
        int bodyLen = data.length - 7;
        if (bodyLen < 0) return;
        if (flag == 1) {
            hwBuf.reset();
            hwFlag = 1;
            hwCmd = data[5] & 0xff;
            hwSub = data[6] & 0xff;
            hwBuf.write(data, 7, data.length - 7);
            return;
        }
        if (flag == 2) {
            if (hwFlag == 1 || hwFlag == 2) hwBuf.write(data, 5, data.length - 7);
            hwFlag = 2;
            return;
        }
        if (flag == 3) {
            if (hwFlag == 1 || hwFlag == 2) hwBuf.write(data, 5, bodyLen);
            byte[] payload = hwBuf.toByteArray();
            hwBuf.reset();
            hwFlag = -1;
            final int cmd = hwCmd, sub = hwSub;
            final HuaweiProto.Tlv tl = HuaweiProto.parseTlv(payload);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (cb != null) cb.onPacket(String.format(java.util.Locale.US, "CMD %02X/%02X %s", cmd, sub, tl.summary()));
                }
            });
            return;
        }
        HuaweiProto.Packet p = HuaweiProto.parse(data);
        if (p != null) {
            final String sum = String.format(java.util.Locale.US, "CMD %02X/%02X %s", p.cmd, p.sub, p.tlv.summary());
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (cb != null) cb.onPacket(sum);
                }
            });
        }
    }

    /** 按 MTU 分帧写 FE01 */
    public boolean sendHuawei(int cmd, int sub, HuaweiProto.Tlv t) {
        if (gatt == null || hwWrite == null) return false;
        try {
            java.util.List<byte[]> chunks = HuaweiProto.frames(cmd, sub, t.serialize(), hwMtu - 3);
            for (byte[] c : chunks) {
                hwWrite.setValue(c);
                gatt.writeCharacteristic(hwWrite);
                try {
                    Thread.sleep(20);
                } catch (Exception ignore) {
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean huaweiReady() {
        return hwWrite != null && hwRead != null;
    }

    public void refresh() {
        if (gatt == null || !connected) return;
        try {
            gatt.readRemoteRssi();
            BluetoothGattService s = gatt.getService(SVC_BATTERY);
            if (s != null) {
                BluetoothGattCharacteristic c = s.getCharacteristic(CHR_BATTERY);
                if (c != null) gatt.readCharacteristic(c);
            }
        } catch (Exception ignore) {
        }
    }

    private void closeGatt() {
        ui.removeCallbacks(timeout);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignore) {
            }
            gatt = null;
        }
        connected = false;
        battery = -1;
    }

    public void disconnect() {
        stopScan();
        closeGatt();
        sp().edit().remove("dev_addr").remove("dev_name").apply();
        if (cb != null) cb.onDisconnected();
    }

    /** 原版 connectCandidate 里的 createBond，返回是否成功发起 */
    public boolean createBond(String address) {
        try {
            if (adapter == null || !permsOk()) return false;
            BluetoothDevice d = adapter.getRemoteDevice(address);
            if (d.getBondState() == BluetoothDevice.BOND_BONDED) return true;
            if (d.getBondState() == BluetoothDevice.BOND_BONDING) return true;
            return d.createBond();
        } catch (Exception e) {
            return false;
        }
    }

    public void destroy() {
        stopScan();
        closeGatt();
        cb = null;
        try {
            act.unregisterReceiver(recv);
        } catch (Exception ignore) {
        }
    }
}