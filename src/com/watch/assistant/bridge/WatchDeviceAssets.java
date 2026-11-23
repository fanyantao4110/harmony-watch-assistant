package com.watch.assistant.bridge;

/**
 * 设备图标资源解析（原版 WatchDeviceAssets 的替身）。
 *
 * 原版按机型名（FIT2/FIT3/GT2/GT3/GT4/GT5/GT6/WATCH D…）返回对应的
 * gb_wa_watch_xxx 图片。本项目没有搬那套机型图（几十张 png + 依赖 glide），
 * 设备列表统一用 com.wlaz 的 ic_watch 图标，因此这里直接返回它。
 *
 * 保留这个类是因为 WatchBridgeProvider.resolveDeviceImageResource /
 * NativeWatchBridge.resolveDeviceImageResource 对外仍暴露该能力，
 * 删掉会让 Bridge 的反射契约变化。
 */
public final class WatchDeviceAssets {

    private WatchDeviceAssets() {
    }

    public static int resolveImageResource(String str) {
        return com.wlaz.R.drawable.ic_watch;
    }

    /** 全部视为通用图标：本工程没有区分机型的图标资源 */
    public static boolean isGenericResource(int i) {
        return true;
    }
}