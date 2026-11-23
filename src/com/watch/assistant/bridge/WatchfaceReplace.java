package com.watch.assistant.bridge;

import android.text.TextUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 表盘「替换法」安装的纯逻辑部分。
 *
 * 移植自 WristMirror-1.1.0（Apache-2.0）的 protocol/VersionPolicy.java、
 * protocol/FacePolicy.java、protocol/ReplacementState.java，
 * 只保留本工程需要的判定与状态机，去掉它的 UI / 日志 / 请求令牌那一套。
 *
 * ── 为什么需要替换法 ─────────────────────────────────────────────
 * 鸿蒙 6.0.0.32 起，普通「新增表盘」流程除了传文件，还会校验服务器授权、
 * 账户/设备绑定和内容摘要。本地 HWT 传完也不会变成可用表盘。
 *
 * 替换法不建新 ID，而是拿手表上**已经登记、允许操做的普通表盘槽**，
 * 用同一个资源 ID + 递增一个版本号发起「新版本替换」事务。
 * 固件如果允许这条路径，既有资源的许可上下文就被沿用，
 * 于是本地内容能通过「更新已有资源」部署下去。
 * 这不是关固件验签，也不是在手机上伪造服务器签名。
 *
 * ── 完整流程 ─────────────────────────────────────────────────────
 *  1. 建会话：蓝牙认证 + 会话密钥，读 0x27/0x02 真实表盘列表，保留 ID/版本/type 位；
 *  2. 备载荷：从 HWT 取 watchface.bin，冻结一份副本，不改写 XML/BIN 头/资源字节；
 *  3. 协商替换：协议版本末位十进制字段 +1，用 0x27/0x03 (tag3=1) 请求；
 *     只有真实回包 **error=0 且 need=1** 才上传；need=0 表示设备不要文件，直接结束不重试；
 *  4. 传输：走 0x28 文件登记/分块/摘要/终结，文件名用「资源ID_新版本」；
 *  5. 核验：传输结果与表盘应用报告分开记录，完成后重新拉列表核对同一 ID 的新版本。
 */
public final class WatchfaceReplace {

    /** 0x27/0x02 tag5 的 type 位定义（与 FacePolicy 一致） */
    static final int TYPE_CURRENT = 0x01;
    static final int TYPE_PRESET = 0x02;
    static final int TYPE_SUPPORT_DELETE = 0x04;
    static final int TYPE_EDITABLE = 0x08;
    static final int TYPE_VIDEO = 0x10;
    static final int TYPE_PHOTO = 0x20;
    static final int TYPE_TRIAL = 0x40;
    static final int TYPE_KALEIDOSCOPE = 0x80;

    /** tag7 expandType：0/缺省=普通，1..5 是视频/相册/万花筒/穿戴/贴纸 */
    static final int EXPAND_NONE = 0;

    private static final int KNOWN_RAW_BITS = 0xFF;
    /** 预置 / 视频 / 相册 / 试用 / 万花筒 都不能当替换目标 */
    private static final int INELIGIBLE_BITS =
            TYPE_PRESET | TYPE_VIDEO | TYPE_PHOTO | TYPE_TRIAL | TYPE_KALEIDOSCOPE;

    /** 设备对 0x27/0x03 的通用成功码（与原版 handleWatchfaceOperationResponse 一致） */
    static final long OPERATION_OK = 0;
    static final long OPERATION_OK_ALT = 100000;

    // 注意：这个类既是「工具方法集合」又是「一次替换事务的状态」，
    // 所以不能有 private 无参构造（final 字段无法初始化）。

    // ─────────────────────────────────────────────────────────────
    // 1. 目标可替换判定（FacePolicy.eligible）
    // ─────────────────────────────────────────────────────────────

    /**
     * 这一条表盘能不能作为替换目标（严格模式，对齐 WristMirror FacePolicy.eligible）。
     *
     * 条件（全部满足）：
     *  - 读到过 type；
     *  - type 只含已知 0xFF 位；
     *  - 带 SUPPORT_DELETE（0x04）——固件允许对它做增删；
     *  - 不含预置/视频/相册/试用/万花筒这些特殊位；
     *  - expandType 缺省或 0（特殊扩展类型是另一套业务，不能走普通替换）。
     *
     * 注意：出厂表盘全部带 PRESET(0x02) 位，所以严格模式下“全是预置表盘”的设备
     * 选不出目标 —— 那种情况请用 {@link #isReplaceableLenient} 放宽。
     */
    public static boolean isReplaceable(WatchInstalledItem item) {
        if (item == null || item.getRequestType() != 2) return false;
        int type = item.getRawType();
        if (type < 0) return false;                       // 没读到 type
        if ((type & ~KNOWN_RAW_BITS) != 0) return false;  // 有未知高位
        if ((type & TYPE_SUPPORT_DELETE) == 0) return false;
        if ((type & INELIGIBLE_BITS) != 0) return false;
        // 官方两套 27/02 接收模型对缺失 07 都用 int 默认 0；这里 -1 表示字段缺失
        int expand = item.getRawExpandType();
        if (expand == -1 || expand == EXPAND_NONE) return true;
        return false;
    }

    /**
     * 放宽模式：允许预置表盘当替换目标。
     *
     * 对齐 WristMirror FacePolicy.canActivate 的思路 —— 它明确写着
     * 「预置、试用及特殊表盘可以走原 ID/原版本 op1」，也就是预置表盘
     * 同样能参与同一资源的事务，只是不当成「普通可删除表盘」看待。
     *
     * 实际能不能替换由固件决定：发 0x27/0x03 后设备回 need=1 才算同意，
     * 回 need=0 就一次都不传（这条状态机已经保证了）。
     *
     * 这里只再排掉真正没法当目标的：
     *  - 相册/视频/万花筒/贴纸这类扩展表盘（expandType 1..5，走另一套资源管理）；
     *  - type 里有未知高位。
     */
    public static boolean isReplaceableLenient(WatchInstalledItem item) {
        if (item == null || item.getRequestType() != 2) return false;
        int type = item.getRawType();
        if (type < 0) return false;
        if ((type & ~KNOWN_RAW_BITS) != 0) return false;
        // 视频(0x10)/相册(0x20)/万花筒(0x80) 是另一套资源管理，即使 expand 字段缺失也不能当普通槽替换
        if ((type & (TYPE_VIDEO | TYPE_PHOTO | TYPE_KALEIDOSCOPE)) != 0) return false;
        int expand = item.getRawExpandType();
        return expand == -1 || expand == EXPAND_NONE;
    }

    /** 给界面用的一行说明：这条能不能选、为什么 */
    public static String describe(WatchInstalledItem item) {
        if (item == null) return "无效";
        int type = item.getRawType();
        if (type < 0) return "类型未读到";
        int expand = item.getRawExpandType();
        if (expand > EXPAND_NONE) return "扩展类型表盘（视频/相册/贴纸等），不能替换";
        if ((type & TYPE_KALEIDOSCOPE) != 0) return "万花筒表盘，不能替换";
        if ((type & TYPE_VIDEO) != 0 || (type & TYPE_PHOTO) != 0) return "视频/相册表盘，不能替换";
        if ((type & TYPE_PRESET) != 0) return "预置表盘（替换需固件允许，可能失败）";
        if ((type & TYPE_TRIAL) != 0) return "试用表盘，可能被固件拒绝";
        if ((type & TYPE_SUPPORT_DELETE) == 0) return "固件未标可删除，可能被拒绝";
        return "普通表盘，可替换";
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 版本递增（VersionPolicy.next）
    // ─────────────────────────────────────────────────────────────

    /**
     * 只把 baseline 的**最后一个十进制字段**加一，绝不追加新字段。
     * 前面的字段和末字段已有的前导零逐字节保留；不向前缀进位、不回绕。
     *
     *   1.0.0     -> 1.0.1
     *   1.0.9     -> 1.0.10
     *   1.0.099   -> 1.0.100
     *   9         -> 10
     *   v1        -> v1  （末字段没有数字，原样返回，调用方应拒绝）
     */
    public static String nextVersion(String baseline) {
        if (TextUtils.isEmpty(baseline)) return null;
        int start = baseline.lastIndexOf('.') + 1;
        String prefix = baseline.substring(0, start);
        String tail = baseline.substring(start);
        if (tail.length() == 0) return null;
        char[] digits = tail.toCharArray();
        for (int i = digits.length - 1; i >= 0; i--) {
            if (digits[i] < '0' || digits[i] > '9') return null;   // 末字段不是纯数字
            if (digits[i] < '9') {
                digits[i]++;
                return prefix + new String(digits);
            }
            digits[i] = '0';
        }
        // 全是 9：整体进一位
        return prefix + "1" + new String(digits);
    }

    /**
     * 生成替换用的传输文件名：「资源ID_新版本」。
     *
     * 这是原版 splitWatchfaceFileName 的逆运算——原版把文件名拆成
     * name_version 再分别塞进 0x27/0x03 的 tag1/tag2，所以这里必须用下划线拼回去，
     * 否则 0x27/0x03 发出的 ID/版本和对不齐。
     */
    public static String replacementFileName(String resourceId, String newVersion) {
        if (TextUtils.isEmpty(resourceId) || TextUtils.isEmpty(newVersion)) return null;
        // 资源 ID 本身含下划线时，原版 split(2) 只切第一个，所以这里不能再多塞下划线
        return resourceId + "_" + newVersion;
    }

    /** 从 identifier（可能是 name_version）里取纯资源 ID */
    public static String resourceIdOf(String identifier) {
        if (identifier == null) return "";
        int i = identifier.indexOf('_');
        return i <= 0 ? identifier : identifier.substring(0, i);
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 替换事务状态机（ReplacementState）
    // ─────────────────────────────────────────────────────────────

    public enum Phase {
        /** 已发 0x27/0x03，等设备回包 */
        WAIT_OPERATION,
        /** 设备回了 need=1，正在传文件 */
        TRANSFERRING,
        /** 文件传完，等设备上报 need=2 */
        WAIT_REPORT,
        /** 已发后置 0x27/0x03，等确认 */
        WAIT_POST,
        /** 等重新拉取的列表 */
        WAIT_LIST,
        /** 列表里出现了新版本 —— 成功 */
        LISTED,
        /** 设备说不需要文件（need=0），按原版语义直接结束，不重试 */
        NO_TRANSFER,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    public enum Action { NONE, UPLOAD, ACTIVATE, QUERY }

    private final String id;
    private final String baseline;
    private final String version;

    private Phase phase = Phase.WAIT_OPERATION;
    private String reason = "WAITING_DEVICE_OPERATION_RESPONSE";

    private boolean uploadIssued;
    private boolean reportReceived;
    private boolean postIssued;
    private boolean postObserved;
    private boolean listed;
    private boolean freshList;
    private boolean nativeRespondObserved;

    private Integer nativeRespond;
    private Integer progress;
    private Integer report;

    private long firstError = Long.MIN_VALUE;
    private long initialError = Long.MIN_VALUE;
    private long initialNeed = Long.MIN_VALUE;
    private long postError = Long.MIN_VALUE;
    private long postNeed = Long.MIN_VALUE;

    private int postGeneration;
    private int listObservations;

    public WatchfaceReplace(String id, String baseline, String version) {
        this.id = id == null ? "" : id;
        this.baseline = baseline == null ? "" : baseline;
        this.version = version == null ? "" : version;
    }

    public String id() {
        return id;
    }

    public String baseline() {
        return baseline;
    }

    public String version() {
        return version;
    }

    public Phase phase() {
        return phase;
    }

    public String reason() {
        return reason;
    }

    public Integer progress() {
        return progress;
    }

    public boolean terminal() {
        return phase == Phase.LISTED || phase == Phase.NO_TRANSFER || phase == Phase.FAILED
                || phase == Phase.CANCELLED || phase == Phase.TIMED_OUT;
    }

    public boolean succeeded() {
        return phase == Phase.LISTED;
    }

    /**
     * 收到 0x27/0x03 回包。
     *
     * @param error tag 0x7F（设备错误码）
     * @param need  tag 4（1=设备要文件，0=不要）
     */
    public Action onOperationResponse(Long error, Long need) {
        if (error != null && error.longValue() != OPERATION_OK && error.longValue() != OPERATION_OK_ALT) {
            // 100000 只是「进行中」的同义成功码，真正的失败码在这里记下
            fail("DEVICE_OPERATION_ERROR(" + error.longValue() + ")", error.longValue());
            return Action.NONE;
        }
        if (terminal()) return Action.NONE;

        if (phase == Phase.WAIT_OPERATION) {
            initialError = (error == null) ? Long.MIN_VALUE : error.longValue();
            initialNeed = (need == null) ? Long.MIN_VALUE : need.longValue();
            if (error == null || need == null) {
                fail("OPERATION_FIELDS_MISSING", 0L);
                return Action.NONE;
            }
            if (need.longValue() == 0L) {
                phase = Phase.NO_TRANSFER;
                reason = "DEVICE_SAID_NEED_ZERO_NO_RETRY";
                return Action.NONE;
            }
            if (need.longValue() != 1L) {
                fail("OPERATION_NEED_INVALID(" + need.longValue() + ")", 0L);
                return Action.NONE;
            }
            uploadIssued = true;
            phase = Phase.TRANSFERRING;
            reason = "DEVICE_REQUESTED_FILE";
            return Action.UPLOAD;
        }

        if (postIssued) {
            postError = (error == null) ? Long.MIN_VALUE : error.longValue();
            postNeed = (need == null) ? Long.MIN_VALUE : need.longValue();
            if (error == null || need == null) {
                fail("POST_OPERATION_FIELDS_MISSING", 0L);
                return Action.NONE;
            }
            if (need.longValue() != 0L) {
                fail("POST_OPERATION_ASKED_MORE_FILE(" + need.longValue() + ")", 0L);
                return Action.NONE;
            }
            if (!postObserved) {
                postObserved = true;
                postGeneration++;
            }
            phase = Phase.WAIT_LIST;
            reason = "POST_OPERATION_CONFIRMED_WAIT_LIST";
            return Action.QUERY;
        }
        return Action.NONE;
    }

    /**
     * 收到 0x27/0x05 表盘应用上报。
     *
     * @param type  tag3 的报告类型（2=需要后置激活，3=失败）
     */
    public Action onReport(Integer type, Long error) {
        if (error != null && error.longValue() != OPERATION_OK && error.longValue() != OPERATION_OK_ALT) {
            fail("WATCH_REPORT_ERROR(" + error.longValue() + ")", error.longValue());
            return Action.NONE;
        }
        report = type;
        if (type != null && type.intValue() == 3) {
            fail("WATCH_REPORT_FAILURE", 0L);
            return Action.NONE;
        }
        if (terminal()) return Action.NONE;
        if (type != null && type.intValue() == 2 && uploadIssued) {
            reportReceived = true;
            if (!postIssued) {
                postIssued = true;
                phase = Phase.WAIT_POST;
                reason = "REPORT_TWO_SEND_POST_OPERATION";
                return Action.ACTIVATE;
            }
        }
        return Action.NONE;
    }

    /** 0x28 文件传输结果：1=成功 */
    public void onNativeResult(int raw) {
        nativeRespondObserved = true;
        nativeRespond = raw;
        if (raw == 0) {
            fail("NATIVE_FILE_RESULT_ZERO", 0);
            return;
        }
        if (terminal()) return;
        if (raw == 1 && phase == Phase.TRANSFERRING) {
            phase = Phase.WAIT_REPORT;
            reason = "TRANSFER_DONE_WAIT_REPORT";
        }
        maybeListed();
    }

    public void onNativeProgress(int raw) {
        if (raw >= 0 && raw <= 100) progress = raw;
    }

    /** 新列表到了 */
    public void onList(List<WatchInstalledItem> faces, int queryGeneration, boolean ownQuery) {
        listObservations++;
        listed = false;
        if (faces != null) {
            for (WatchInstalledItem f : faces) {
                if (f == null) continue;
                if (id.equals(resourceIdOf(f.getIdentifier())) && version.equals(f.getVersion())) {
                    listed = true;
                    break;
                }
            }
        }
        if (terminal()) return;
        freshList = ownQuery && postObserved && postGeneration > 0 && queryGeneration == postGeneration;
        maybeListed();
    }

    private void maybeListed() {
        if (terminal()) return;
        if (uploadIssued && reportReceived && postIssued && postObserved && listed && freshList
                && nativeRespondObserved && nativeRespond != null && nativeRespond == 1) {
            phase = Phase.LISTED;
            reason = "REPLACEMENT_LISTED_CHECK_ON_WATCH";
        }
    }

    public void fail(String why, long error) {
        if (firstError == Long.MIN_VALUE) firstError = error;
        reason = why;
        phase = Phase.FAILED;
    }

    public void cancel() {
        if (!terminal()) {
            phase = Phase.CANCELLED;
            reason = "USER_CANCELLED";
        }
    }

    public void timeout() {
        if (!terminal()) {
            phase = Phase.TIMED_OUT;
            reason = "TIMEOUT_NOT_INSTALL_VERDICT";
        }
    }

    /** 给日志和界面用的一行摘要 */
    public String summary() {
        return "phase=" + phase + " reason=" + reason
                + " id=" + id + " baseline=" + baseline + " version=" + version
                + " upload_issued=" + uploadIssued
                + " native_result=" + nativeRespond + " percent=" + progress
                + " report=" + report + " report_two=" + reportReceived
                + " post_issued=" + postIssued + " listed=" + listed
                + " generation=" + postGeneration + " list_observations=" + listObservations;
    }
}