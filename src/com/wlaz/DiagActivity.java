package com.wlaz;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.watch.assistant.bridge.WatchDevice;
import com.watch.assistant.bridge.WatchInstalledItem;

/**
 * 诊断页。
 *
 * 原来这里用反射枚举 DexClassLoader 加载出来的原版类签名，用来确认真机上的
 * 字段/方法名。现在原版源码已经编译进本工程（Bridge 不再加载 dex），
 * 反射枚举就没意义了，改成直接打印运行时状态：
 *   后端是否就绪、设备表、当前设备的连接/电量/序列号等。
 */
public class DiagActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setTextSize(10);
        tv.setPadding(16, 16, 16, 16);
        tv.setText(build());
        setContentView(tv);
    }

    private String build() {
        StringBuilder s = new StringBuilder();
        Bridge.init(this);
        s.append("后端类型：编译期内置（无 dex 加载）\n");
        s.append("就绪：").append(Bridge.ready()).append('\n');
        if (!Bridge.ready()) {
            s.append("错误：").append(Bridge.error()).append('\n');
            return s.toString();
        }

        java.util.List<WatchDevice> devices = Bridge.devices();
        s.append("设备数：").append(devices == null ? 0 : devices.size()).append('\n');
        if (devices != null) {
            for (WatchDevice d : devices) {
                s.append("\n===== ").append(d.getAddress()).append('\n');
                s.append("  名称: ").append(d.getDisplayName()).append('\n');
                s.append("  序列号: ").append(d.getSerialNumber()).append('\n');
                s.append("  UID: ").append(d.getUid()).append('\n');
                s.append("  状态: ").append(d.getStateLabel()).append('\n');
                s.append("  已连接: ").append(d.isConnected()).append('\n');
                s.append("  已就绪: ").append(d.isReady()).append('\n');
                s.append("  电量: ").append(d.getBatteryLevel()).append('\n');
                s.append("  剩余MB: ").append(d.getAvailableSpaceMb()).append('\n');
                s.append("  应用数: ").append(d.getInstalledAppsCount()).append('\n');
                s.append("  表盘数: ").append(d.getInstalledWatchfacesCount()).append('\n');

                java.util.List<WatchInstalledItem> apps = Bridge.items(d.getAddress(), 1);
                s.append("  已读应用: ").append(apps.size()).append('\n');
                for (int i = 0; i < apps.size() && i < 10; i++) {
                    WatchInstalledItem it = apps.get(i);
                    s.append("    - ").append(it.getTitle())
                            .append(" v").append(it.getVersion())
                            .append(" [").append(it.getStatusLabel()).append("]\n");
                }
                java.util.List<WatchInstalledItem> faces = Bridge.items(d.getAddress(), 2);
                s.append("  已读表盘: ").append(faces.size()).append('\n');
            }
        }
        return s.toString();
    }
}