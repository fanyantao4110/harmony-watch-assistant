package com.watch.assistant.bridge;

/* loaded from: classes4.dex */
public final class WatchInstalledItem {
    private final boolean active;
    private final String detail;
    private final boolean factory;
    private final String identifier;
    private final int requestType;
    private final long sizeBytes;
    private final String statusLabel;
    private final String title;
    private final String version;
    /**
     * 表盘原始 type 位集合（0x27/0x02 里 tag5 的那个字节）。
     *
     * 原版只把它拆成 active/factory 两个 boolean 给界面用，替换法还需要
     * 完整的位来判断“是不是普通可替换表盘”（见 {@link WatchfaceReplace#isReplaceable}）。
     * 默认 -1 表示这一项没读到 type（应用条目本来就没有）。
     */
    private final int rawType;
    /** 表盘原始 expandType（tag7），缺失时为 -1。替换法要求它为 0 或缺省。 */
    private final int rawExpandType;

    public WatchInstalledItem(int i, String str, String str2, String str3, long j, String str4, String str5) {
        this(i, str, str2, str3, j, str4, str5, false, false);
    }

    public WatchInstalledItem(int i, String str, String str2, String str3, long j, String str4, String str5, boolean z, boolean z2) {
        this(i, str, str2, str3, j, str4, str5, z, z2, -1, -1);
    }

    public WatchInstalledItem(int i, String str, String str2, String str3, long j, String str4, String str5,
                              boolean z, boolean z2, int rawType, int rawExpandType) {
        this.requestType = i;
        this.identifier = str;
        this.title = str2;
        this.version = str3;
        this.sizeBytes = j;
        this.detail = str4;
        this.statusLabel = str5;
        this.active = z;
        this.factory = z2;
        this.rawType = rawType;
        this.rawExpandType = rawExpandType;
    }

    public int getRequestType() {
        return this.requestType;
    }

    public String getIdentifier() {
        return this.identifier;
    }

    public String getTitle() {
        return this.title;
    }

    public String getVersion() {
        return this.version;
    }

    public long getSizeBytes() {
        return this.sizeBytes;
    }

    public String getDetail() {
        return this.detail;
    }

    public String getStatusLabel() {
        return this.statusLabel;
    }

    public boolean isActive() {
        return this.active;
    }

    public boolean isFactory() {
        return this.factory;
    }

    /** 原始 type 位集合，-1 表示未读到 */
    public int getRawType() {
        return this.rawType;
    }

    /** 原始 expandType，-1 表示未读到 */
    public int getRawExpandType() {
        return this.rawExpandType;
    }
}