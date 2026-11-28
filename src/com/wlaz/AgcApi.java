package com.wlaz;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * AGC Connect API 客户端（纯 Java）。
 *
 * 鉴权：AGC 控制台「用户与访问 → API 密钥 → ConnectAPI」下载的
 *       agc-apiclient-*.json，POST /api/oauth2/v1/token（client_credentials）
 *       换 client token，后续请求带 Authorization: Bearer + client_id 头。
 *
 * 实测可用的端点（腕连助手历史验证）：
 *   GET  /api/publish/v2/device/list        已登记调试设备
 *   POST /api/publish/v2/device             添加设备 {deviceList:[{udid,deviceName,deviceType}]}
 *   POST /api/publish/v2/cert/list          证书列表
 *   POST /api/publish/v3/cert               申请调试证书 {certName,certType:1,csr}
 *   POST /api/publish/v3/app                创建应用 {appName,packageName,parentType,installationFree}
 *   GET  /api/publish/v2/provision/list     Profile 列表
 *   POST /api/publish/v3/provision          申请 Profile {provisionName,provisionType,certId,appId,deviceIdList}
 *   GET  /api/publish/v2/device/list
 */
public class AgcApi {

    public static final String BASE = "https://connect-api.cloud.huawei.com";

    /** 调试设备在 AGC 里的 deviceType：手表 = 1 */
    public static final int DEVICE_TYPE_WATCH = 1;

    private static final String PREFS = "wl_agc";

    // ---- DevEco 模式凭据键 ----
    public static final String K_ACCESS_TOKEN = "dev_access_token";
    public static final String K_JWT_TOKEN = "dev_jwt_token";
    public static final String K_UID = "dev_uid";
    public static final String K_TEAM_ID = "dev_team_id";
    public static final String K_DISPLAY_NAME = "dev_display_name";
    public static final String K_SITE_ID = "dev_site_id";
    public static final String K_ACCESS_EXP = "dev_access_exp";
    /** 登录地址（空 = 用 CN 站点默认） */
    public static final String K_LOGIN_ADDR = "dev_login_addr";

    // CAManager 用的 CSR（历史验证可用，与内置私钥配对）
    private static final String CSR =
            "-----BEGIN NEW CERTIFICATE REQUEST-----\n"
                    + "MIH+MIGkAgEAMBIxEDAOBgNVBAMMB2RlZmF1bHQwWTATBgcqhkjOPQIBBggqhkjO\n"
                    + "PQMBBwNCAAR1VIBWnhZckS1Xr/GvhEpgr+HpSdlP48szkRw5xes+5+D2pAXts0Or\n"
                    + "sXQ4Y0u/RIWqF7oF7tAtSt92rlhsGUSLoDAwLgYJKoZIhvcNAQkOMSEwHzAdBgNV\n"
                    + "HQ4EFgQUIHP+3xCgek7Vv5FaHLI4sMRIpVMwCgYIKoZIzj0EAwIDSQAwRgIhAPIs\n"
                    + "6WmMIQ+uLZAKEC4scBCNMXa2kZmlZcjFZo/L8S+6AiEAsbsz8jEJMXEL0SNXTAi3\n"
                    + "KSHU01xBcwv1Q2Oi5zRlxos=\n"
                    + "-----END NEW CERTIFICATE REQUEST-----";

    public static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 凭证（DevEco 模式 / SA 模式兼容） ----------------

    /**
     * 是否已经具备调用条件。
     *   · DevEco 模式：userscript 式的 jwtToken/accessToken（登录后落盘）
     *   · SA 模式（旧）：client_id + client_secret
     */
    public static boolean hasCredential(Context c) {
        if (p(c).getString(K_ACCESS_TOKEN, "").length() > 0) return true;
        return p(c).getString("client_id", "").length() > 0
                && p(c).getString("client_secret", "").length() > 0;
    }

    /** 是否处于 DevEco 模式（已登录） */
    public static boolean hasDevEcoLogin(Context c) {
        return p(c).getString(K_ACCESS_TOKEN, "").length() > 0;
    }

    /** 当前账号展示名 */
    public static String accountLabel(Context c) {
        String n = p(c).getString(K_DISPLAY_NAME, "");
        if (n.length() > 0) return n;
        String uid = p(c).getString(K_UID, "");
        if (uid.length() > 0) return "UID " + uid;
        String cid = p(c).getString("client_id", "");
        return cid.length() > 0 ? "SA " + cid : "";
    }

    /** 导入 AGC 下载的 agc-apiclient-*.json（整段文本，旧 SA 模式，保留兼容） */
    public static String importCredential(Context c, String json) {
        try {
            Object o = MiniJson.parse(json);
            String id = MiniJson.str(o, "client_id");
            String sec = MiniJson.str(o, "client_secret");
            if (id.length() == 0 || sec.length() == 0) return "这不是 AGC API 密钥文件（缺 client_id / client_secret）";
            p(c).edit()
                    .putString("client_id", id)
                    .putString("client_secret", sec)
                    .putString("developer_id", MiniJson.str(o, "developer_id"))
                    .putString("raw", json.trim())
                    .remove("token").remove("token_exp")
                    .apply();
            return "";
        } catch (Exception e) {
            return "JSON 解析失败：" + e.getMessage();
        }
    }

    public static String clientId(Context c) {
        return p(c).getString("client_id", "");
    }

    private static String token(Context c, boolean force) throws Exception {
        // DevEco 模式：直接用登录时落盘的 accessToken（用 jwtToken 换来）
        String dev = p(c).getString(K_ACCESS_TOKEN, "");
        if (dev.length() > 0) {
            long exp = p(c).getLong(K_ACCESS_EXP, 0);
            if (force || exp == 0 || exp > System.currentTimeMillis() + 60000) return dev;
            // 过期 → 用 jwtToken 刷新
            String fresh = DevEco.refresh(c);
            if (fresh.length() > 0) return fresh;
            throw new Exception("登录已过期，请重新登录 DevEco 账号");
        }

        long exp = p(c).getLong("token_exp", 0);
        String cached = p(c).getString("token", "");
        if (!force && cached.length() > 0 && exp > System.currentTimeMillis() + 60000) return cached;

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("grant_type", "client_credentials");
        body.put("client_id", clientId(c));
        body.put("client_secret", p(c).getString("client_secret", ""));
        HttpReply r = http(BASE + "/api/oauth2/v1/token", MiniJson.write(body).getBytes("UTF-8"), null, "POST");
        if (r.code != 200) throw new Exception("取 token 失败 HTTP " + r.code + " " + r.text().substring(0, Math.min(150, r.text().length())));
        Object j = MiniJson.parse(r.text());
        String tok = MiniJson.str(j, "access_token");
        if (tok.length() == 0) tok = MiniJson.str(j, "accessToken");
        if (tok.length() == 0) throw new Exception("响应里没有 access_token");
        long expires = (long) numOf(MiniJson.get(j, "expires_in"), 3600);
        p(c).edit().putString("token", tok)
                .putLong("token_exp", System.currentTimeMillis() + (expires - 120) * 1000)
                .apply();
        return tok;
    }

    private static double numOf(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    // ---------------- HTTP ----------------

    public static class HttpReply {
        public int code;
        public byte[] body;

        public String text() {
            try {
                return new String(body, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }

    public static HttpReply http(String url, byte[] body, Map<String, String> headers, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.flush();
            o.close();
        }
        HttpReply r = new HttpReply();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? new byte[0] : readAll(in);
        c.disconnect();
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[32768];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    /** 带鉴权的调用；401 自动重取 token 重试一次 */
    private static Object call(Context c, String path, Object body, String method) throws Exception {
        return call(c, path, body, method, true);
    }

    private static Object call(Context c, String path, Object body, String method, boolean retry) throws Exception {
        String tok = token(c, false);
        HashMap<String, String> h = new HashMap<String, String>();
        if (hasDevEcoLogin(c)) {
            // DevEco 模式：oauth2Token + uid + teamId + DevEcoStudio UA
            h.put("oauth2Token", tok);
            h.put("uid", p(c).getString(K_UID, ""));
            String team = p(c).getString(K_TEAM_ID, "");
            if (team.length() == 0) team = p(c).getString(K_UID, "");
            h.put("teamId", team);
            h.put("User-Agent", DevEco.UA);
        } else {
            h.put("Authorization", "Bearer " + tok);
            h.put("client_id", clientId(c));
        }
        byte[] payload = body == null ? null : MiniJson.write(body).getBytes("UTF-8");
        HttpReply r = http(BASE + path, payload, h, method);
        if (r.code == 401 && retry) {
            token(c, true);
            return call(c, path, body, method, false);
        }
        if (r.code == 404) throw new Exception("接口不存在：" + path);
        if (r.code != 200) throw new Exception("HTTP " + r.code + " " + path + " " + r.text().substring(0, Math.min(160, r.text().length())));
        String t = r.text();
        if (t.trim().length() == 0) return new LinkedHashMap<String, Object>();
        return MiniJson.parse(t);
    }

    private static int retCode(Object j) {
        return MiniJson.integer(j, "ret.code", -1);
    }

    private static String retMsg(Object j) {
        return MiniJson.str(j, "ret.msg");
    }

    // ---------------- DevEco 通道（与 publish/* 并行的 cps/amis 接口） ----------------

    /** 是否走 DevEco 通道（已登录）+ 调用失败自动回退 publish/* */
    private static boolean devCh() {
        return true;
    }

    /**
     * DevEco 通道的 HTTP 调用（GET/POST）。
     * 带 oauth2Token + uid + teamId + DevEcoStudio UA；失败返回 null 由上层回退。
     */
    private static Object devCall(Context c, String path, Object body, String method) throws Exception {
        if (!hasDevEcoLogin(c)) return null;
        String tok = token(c, false);
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("oauth2Token", tok);
        h.put("uid", p(c).getString(K_UID, ""));
        String team = p(c).getString(K_TEAM_ID, "");
        h.put("teamId", team.length() > 0 ? team : p(c).getString(K_UID, ""));
        h.put("User-Agent", DevEco.UA);
        h.put("Content-Type", "application/json");
        byte[] payload = body == null ? null : MiniJson.write(body).getBytes("UTF-8");
        HttpReply r = http(BASE + path, payload, h, method);
        if (r.code != 200) {
            android.util.Log.w("AgcApi", "devCall " + path + " HTTP " + r.code);
            return null;
        }
        String t = r.text();
        if (t.trim().length() == 0) return new LinkedHashMap<String, Object>();
        return MiniJson.parse(t);
    }

    public static class Device {
        public String id = "";
        public String name = "";
        public String udid = "";
        public int type = DEVICE_TYPE_WATCH;
    }

    public static List<Device> listDevices(Context c) throws Exception {
        List<Device> out = new ArrayList<Device>();
        // DevEco 通道：GET /api/cps/device-manage/v1/device/list
        if (hasDevEcoLogin(c)) {
            Object j = devCall(c, "/api/cps/device-manage/v1/device/list?start=1&pageSize=100&encodeFlag=0",
                    null, "GET");
            if (j != null) {
                for (Object o : firstList(j, "deviceList", "list", "devices", "data.list")) {
                    Device d = new Device();
                    d.id = firstStr(o, "id", "deviceId");
                    d.name = firstStr(o, "deviceName", "name");
                    d.udid = firstStr(o, "udid", "sn");
                    d.type = MiniJson.integer(o, "deviceType", DEVICE_TYPE_WATCH);
                    if (d.udid.length() > 0 || d.id.length() > 0) out.add(d);
                }
                if (!out.isEmpty()) return out;
            }
        }
        Object j = call(c, "/api/publish/v2/device/list", null, "GET");
        for (Object o : MiniJson.list(j, "deviceList")) {
            Device d = new Device();
            d.id = MiniJson.str(o, "id");
            d.name = MiniJson.str(o, "deviceName");
            d.udid = MiniJson.str(o, "udid");
            d.type = MiniJson.integer(o, "deviceType", DEVICE_TYPE_WATCH);
            out.add(d);
        }
        return out;
    }

    /** 在多个候选路径里取第一个非空列表 */
    private static List<Object> firstList(Object root, String... paths) {
        for (String p : paths) {
            List<Object> l = MiniJson.list(root, p);
            if (!l.isEmpty()) return l;
        }
        return new ArrayList<Object>();
    }

    /** 在多个候选字段里取第一个非空字符串 */
    private static String firstStr(Object o, String... keys) {
        for (String k : keys) {
            String v = MiniJson.str(o, k);
            if (v.length() > 0 && !"null".equals(v)) return v;
        }
        return "";
    }

    /**
     * 把 UDID 加进 AGC 调试设备列表；已存在直接返回已有条目。
     * 返回的 Device.id 是后面申请 Profile 时 deviceIdList 要用的值。
     */
    public static Device ensureDevice(Context c, String udid, String name) throws Exception {
        String u = udid == null ? "" : udid.trim().toUpperCase(Locale.US);
        if (u.length() == 0) throw new Exception("UDID 为空");
        if (TextUtils.isEmpty(name)) name = "HUAWEI Watch";

        for (Device d : listDevices(c)) {
            if (d.udid != null && d.udid.equalsIgnoreCase(u)) return d;
        }

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("udid", u);
        item.put("deviceName", name);
        item.put("deviceType", DEVICE_TYPE_WATCH);
        List<Object> l = new ArrayList<Object>();
        l.add(item);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("deviceList", l);
        Object j = call(c, "/api/publish/v2/device", body, "POST");

        // 重名会返回 failedCount=1 + "The name of device is duplicate."，此时按 UDID 再查一次
        for (Device d : listDevices(c)) {
            if (d.udid != null && d.udid.equalsIgnoreCase(u)) return d;
        }
        int failed = MiniJson.integer(j, "failedCount", 0);
        String detail = "";
        List<Object> fd = MiniJson.list(j, "failedAddDeviceDetail");
        if (!fd.isEmpty()) detail = MiniJson.str(fd.get(0), "result.msg");
        throw new Exception("添加设备失败" + (detail.length() > 0 ? "：" + detail : "")
                + (failed > 0 ? "（失败 " + failed + "）" : ""));
    }

    public static boolean removeDevice(Context c, List<String> ids) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        List<Object> l = new ArrayList<Object>(ids);
        body.put("deviceIdList", l);
        Object j = call(c, "/api/publish/v2/device/delete", body, "POST");
        return retCode(j) == 0;
    }

    // ---------------- 证书 ----------------

    public static class Cert {
        public String id = "";
        public String name = "";
        public int type = 1;
        public long expire = 0;
        public String downloadUrl = "";
        /** DevEco 通道返回的对象 ID，需 reapply 换成直链 */
        public String objectId = "";
    }

    public static List<Cert> listCerts(Context c) throws Exception {
        List<Cert> out = new ArrayList<Cert>();
        // DevEco 通道：POST /api/cps/harmony-cert-manage/v1/cert/list
        if (hasDevEcoLogin(c)) {
            Object j = devCall(c, "/api/cps/harmony-cert-manage/v1/cert/list",
                    new LinkedHashMap<String, Object>(), "POST");
            if (j != null) {
                String be = bizError(j);
                if (be.length() > 0) {
                    android.util.Log.w("AgcApi", "DevEco 证书列表返回业务错误：" + be);
                } else {
                    for (Object o : firstList(j, "certList", "certificateList", "list", "data.list")) {
                        out.add(toCert(o));
                    }
                    if (!out.isEmpty()) {
                        android.util.Log.i("AgcApi", "DevEco 证书列表：" + out.size() + " 张");
                        return out;
                    }
                }
            }
        }
        Object j = call(c, "/api/publish/v2/cert/list", new LinkedHashMap<String, Object>(), "POST");
        for (Object o : MiniJson.list(j, "certList")) out.add(toCert(o));
        android.util.Log.i("AgcApi", "publish 证书列表：" + out.size() + " 张");
        return out;
    }

    private static Cert toCert(Object o) {
        Cert t = new Cert();
        t.id = firstStr(o, "id", "certId");
        t.name = firstStr(o, "certName", "name");
        t.type = MiniJson.integer(o, "certType", 2);
        t.expire = (long) numOf(MiniJson.get(o, "expireTime"), 0);
        t.downloadUrl = firstStr(o, "certDownloadUrl", "downloadUrl", "certFileUrl", "certUrl");
        t.objectId = firstStr(o, "certObjectId", "objectId");
        // 有些通道把下载地址/对象 ID 放在 sourceUrls 里
        if (t.downloadUrl.length() == 0 && t.objectId.length() == 0) {
            Object src = MiniJson.get(o, "sourceUrls");
            if (src instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) src;
                if (!l.isEmpty() && l.get(0) != null) {
                    String v = String.valueOf(l.get(0));
                    if (v.startsWith("http")) t.downloadUrl = v;
                    else t.objectId = v;
                }
            }
        }
        return t;
    }

/**
     * 已有调试证书就复用，没有才申请（type=1 调试证书）。
     *
     * ⚠️ 关键：必须选「公钥与内置私钥 wl_key.pem 配对」的那张证书。
     * AGC 团队里可能已有别人（原版腕连）建的调试证书，我们拿不到它的私钥，
     * 用它的 Profile 签出来的包 CMS 验不过，手表直接拒绝安装。
     * 所以这里逐张下载证书、比对 EC 公钥点，只复用真正配对的那张。
     */
public static Cert ensureDebugCert(Context c) throws Exception {
        byte[] myPoint = myPublicPoint(c);
        android.util.Log.i("AgcApi", "ensureDebugCert 开始，内置公钥点=" + (myPoint == null ? "null" : myPoint.length + "字节"));
        if (myPoint != null) {
            String saved = p(c).getString("cert_id", "");
            List<Cert> all = listCerts(c);
            android.util.Log.i("AgcApi", "证书数量=" + all.size());
            // 先看上次存的那张
            for (int pass = 0; pass < 2; pass++) {
                for (Cert t : all) {
                    if (t.type != 1) continue;
                    if (t.expire > 0 && t.expire < System.currentTimeMillis()) {
                        android.util.Log.i("AgcApi", "跳过已过期证书 " + t.name);
                        continue;
                    }
                    if (pass == 0 && !t.id.equals(saved)) continue;
                    if (pass == 1 && t.id.equals(saved)) continue;
                    boolean match = samePublicKey(c, t, myPoint);
                    android.util.Log.i("AgcApi", "证书 " + t.name + "/" + t.id
                            + " 公钥配对=" + match + " url=" + clip(t.downloadUrl));
                    if (!match) continue;
                    p(c).edit().putString("cert_id", t.id).putString("cert_name", t.name).apply();
                    return t;
                }
            }
        }

        // 没有配对证书（或读不到私钥）：用与私钥成对的 CSR 申请一张
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("certName", "wl_dbg_" + rand(4));
        body.put("certType", 1);
        body.put("csr", CSR);
        Object j = null;
        String err = "";
        // DevEco 通道：POST /api/cps/harmony-cert-manage/v1/cert/add（必须 reqSource=IDE）
        if (hasDevEcoLogin(c)) {
            Map<String, Object> ide = new LinkedHashMap<String, Object>(body);
            ide.put("certType", "1");
            ide.put("reqSource", "IDE");
            j = devCall(c, "/api/cps/harmony-cert-manage/v1/cert/add", ide, "POST");
            if (j != null) err = bizError(j);
        }
        if (j == null || err.length() > 0) {
            Object j2 = call(c, "/api/publish/v3/cert", body, "POST");
            String err2 = bizError(j2);
            if (err2.length() == 0) {
                j = j2;
                err = "";
            } else {
                err = err2;
            }
        }
        if (err.length() > 0) {
            // 证书配额满 / 同公钥重复是最常见的两种，把话说到点子上
            if (err.contains("exceeds limit") || err.contains("204144856")) {
                throw new Exception("申请调试证书失败：账号调试证书数量已达上限。"
                        + "请到 AGC 控制台 → 证书、APP ID 和 Profile → 证书 删除不用的证书后重试。"
                        + "（云端原文：" + err + "）");
            }
            if (err.contains("same public key") || err.contains("public key")) {
                throw new Exception("申请调试证书失败：账号里已有相同公钥的证书。"
                        + "（云端原文：" + err + "）");
            }
            throw new Exception("申请调试证书失败：" + err);
        }
        Cert t = new Cert();
        t.id = firstStr(MiniJson.get(j, "certInfo"), "id", "certId");
        if (t.id.length() == 0) t.id = firstStr(j, "id", "certId", "certInfo.id");
        t.name = MiniJson.str(j, "certInfo.certName");
        if (t.name.length() == 0) t.name = firstStr(j, "certName", "name");
        t.type = 1;
        t.downloadUrl = firstStr(MiniJson.get(j, "certInfo"), "certDownloadUrl", "downloadUrl");
        t.objectId = firstStr(MiniJson.get(j, "certInfo"), "certObjectId", "objectId");
        if (t.id.length() == 0) {
            String s = MiniJson.write(j);
            throw new Exception("申请调试证书失败：" + s.substring(0, Math.min(200, s.length())));
        }
        p(c).edit().putString("cert_id", t.id).putString("cert_name", t.name).apply();
        return t;
    }

    /** 提取业务错误信息（HTTP 200 但 ret.code != 0 / code != 0），没有错误返回 "" */
    private static String bizError(Object j) {
        if (j == null) return "";
        int rc = MiniJson.integer(j, "ret.code", 0);
        if (rc != 0) {
            String m = MiniJson.str(j, "ret.msg");
            return m.length() > 0 ? m : ("错误码 " + rc);
        }
        int code = MiniJson.integer(j, "code", 0);
        if (code != 0) {
            String m = MiniJson.str(j, "msg");
            if (m.length() == 0) m = MiniJson.str(j, "message");
            return m.length() > 0 ? m : ("错误码 " + code);
        }
        return "";
    }

    /** 内置私钥对应的 EC 公钥点（04||X||Y，65 字节）；失败返回 null */
    private static byte[] myPublicPoint(Context c) {
        try {
            // 内置 CSR 与 wl_key.pem 是同一对密钥（已实测公钥点一致），
            // 直接从 CSR 里取公钥点，避免在 Java 里做 EC 标量乘法。
            return HapSigner.csrPublicPoint(CSR);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 该证书的叶子公钥点是否与内置私钥一致 */
    private static boolean samePublicKey(Context c, Cert t, byte[] mine) {
        try {
            String url = certUrl(c, t);
            if (url.length() == 0) {
                android.util.Log.w("AgcApi", "证书 " + t.name + " 拿不到下载地址（certDownloadUrl/certObjectId 都为空）");
                return false;
            }
            HttpReply r = http(url, null, null, "GET");
            if (r.code != 200 || r.body.length == 0) {
                android.util.Log.w("AgcApi", "下载证书失败 HTTP " + r.code + " " + t.name);
                return false;
            }
            String pem = new String(r.body, "UTF-8");
            // AGC 下发的是整条链（根 CA + 中间 CA + 开发者叶子证书），
            // 必须取【叶子】证书，否则比对的是根 CA 的公钥，永远不匹配。
            byte[] leaf = HapSigner.leafCertDer(pem);
            if (leaf == null) return false;
            byte[] pt = HapSigner.certPublicPoint(leaf);
            if (pt == null || pt.length != mine.length) return false;
            for (int i = 0; i < pt.length; i++) if (pt[i] != mine[i]) return false;
            return true;
        } catch (Throwable e) {
            android.util.Log.w("AgcApi", "samePublicKey 异常 " + t.name + "：" + e);
            return false;
        }
    }

    /**
     * 取证书的可下载地址。
     *   · 旧通道直接给 certDownloadUrl（带签名的 OBS 直链）；
     *   · DevEco 通道只给 certObjectId，必须经 amis/objects/url/reapply 换直链。
     */
    private static String certUrl(Context c, Cert t) {
        if (t.downloadUrl != null && t.downloadUrl.startsWith("http")) return t.downloadUrl;
        if (t.objectId == null || t.objectId.length() == 0) return "";
        String url = reapplyUrl(c, t.objectId);
        if (url.length() > 0) t.downloadUrl = url;
        return url;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    // ---------------- 应用 ----------------

    /**
     * 确保某个软件在 AGC 下有应用实体。
     *
     * ★ DevEco 方式（本次改动）：
     *   1. 包名**固定**，由软件名确定性推导（旧版 `+ 随机5位` 已彻底废弃）：
     *        com.wlaz.<sanitize(name)>
     *      包名固定后，同一个软件每次算出来都是同一个，AGC 里永远是同一个 appId，
     *      Profile 也能复用，不会每次重签都产出一堆垃圾应用。
     *   2. 先按包名查（`publish/v2/appid-list?packageName=`），查到就直接用；
     *   3. 查不到才创建，且**固定挂到 hdckit 项目**下，绝不触发自动建项目。
     *
     * @param appKey  稳定标识（用 AppItem.id），用来持久化 appId/bundle
     * @param appName 软件名，用来推导固定包名
     */
    public static String ensureApp(Context c, String appKey, String appName) throws Exception {
        String key = "app_" + (appKey == null ? "" : appKey.trim());
        String saved = p(c).getString(key, "");
        if (saved.length() > 0) return saved;

        String fixed = fixedBundleOf(appName);

        // ① 先按固定包名查已登记的 appId（DevEco/SA 同一套接口）
        String existed = appIdOfPackage(c, fixed);
        if (existed.length() > 0) {
            android.util.Log.i("AgcApi", "ensureApp 命中已有应用 " + fixed + " -> " + existed);
            p(c).edit().putString(key, existed).putString(key + "_bundle", fixed).apply();
            return existed;
        }

        // ② 查不到 → 创建（固定包名，固定项目）
        String projectId = pickProject(c, appName);
        if (projectId.length() == 0) {
            throw new Exception("没找到名为 " + PROJECT_NAME + " 的项目，无法创建应用 " + fixed);
        }
        android.util.Log.i("AgcApi", "ensureApp 创建 bundle=" + fixed + " projectId=" + projectId);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("appName", trimAppName(appName));
        body.put("packageName", fixed);
        body.put("parentType", 13);
        body.put("installationFree", 0);
        // projectId 必须走 query string，放 body 会被忽略 →
        // 忽略后 AGC 会「自动新建一个项目」→ 204144685 项目日配额超限
        Object j = call(c, "/api/publish/v3/app?projectId=" + projectId, body, "POST");
        String full = MiniJson.write(j);
        android.util.Log.i("AgcApi", "ensureApp resp=" + full);
        String appId = MiniJson.str(j, "appId");
        if (appId.length() == 0) {
            throw new Exception("创建应用失败 [bundle=" + fixed + "]：" + full.substring(0, Math.min(220, full.length())));
        }
        p(c).edit().putString(key, appId).putString(key + "_bundle", fixed)
                .putString(BOUND_PROJECT, projectId).apply();
        return appId;
    }

    /**
     * 软件 → **固定**包名。首次按软件名推导并落盘，之后永远用同一个值，
     * 即使软件改名也不会变（改名会导致 appId/Profile 全部对不上）。
     */
    public static String bundleForApp(Context c, String appKey, String appName) {
        String key = "app_" + (appKey == null ? "" : appKey.trim());
        String saved = p(c).getString(key + "_bundle", "");
        if (saved.length() > 0) return saved;
        String b = fixedBundleOf(appName);
        p(c).edit().putString(key + "_bundle", b).apply();
        return b;
    }

    /**
     * 软件名 → **固定**的 AGC 包名。
     * 同一个软件名永远得到同一个包名，不做任何随机。
     *
     * ★★ 注意：这个函数只能用「本来就是 ASCII 的名字」当输入。
     * 真实链路里必须优先用安装包自带的原始 bundleName（见 bundleForApp 的 3 参重载），
     * 因为中文软件名经 sanitizeAppName 会被清成空串、回退成 "app"，
     * 导致所有中文名软件共用 com.wlaz.app 互相覆盖。仅在没有原始包名时兜底。
     */
    public static String fixedBundleOf(String appName) {
        String base = sanitizeAppName(appName);
        if (base.length() == 0) base = "app";
        return "com.wlaz." + base;
    }

    /**
     * 取一个软件要用的包名（**唯一性优先**）。
     *
     * 优先级：
     *   ① prefs 已落盘过 → 直接用（保证同一软件永远同一包名，Profile 可复用）
     *   ② 安装包自带的原始 bundleName（最可靠，天然唯一：
     *      com.example.dazhuankuai / com.crab.jump3D / com.github.kqakqakqa...）
     *   ③ 原始包名也没有 → 用软件名推导
     *
     * @param rawBundle 安装包 config.json 里读出来的原始 bundleName，可为空
     */
    public static String bundleForApp(Context c, String appKey, String appName, String rawBundle) {
        String key = "app_" + (appKey == null ? "" : appKey.trim());

        // ② 先算出「本次应该用的包名」：安装包原始包名优先
        String b = normalizeRawBundle(rawBundle);
        // ③ 兜底：原始包名读不到时才退回按软件名推导
        if (b.length() == 0) b = fixedBundleOf(appName);

        String saved = p(c).getString(key + "_bundle", "");
        if (saved.length() > 0) {
            // ★ 历史脏值迁移：旧版用中文名推导，全部撞成 com.wlaz.app，
            //   导致不同软件互相覆盖。只要现在能从安装包读到真包名，就覆盖掉它。
            if (isLegacySharedBundle(saved) && b.length() > 0
                    && !isLegacySharedBundle(b) && !b.equals(saved)) {
                android.util.Log.i("AgcApi", "包名迁移 " + key + " : " + saved + " -> " + b);
                p(c).edit().putString(key + "_bundle", b).apply();
                return b;
            }
            return saved;
        }

        p(c).edit().putString(key + "_bundle", b).apply();
        return b;
    }

    /**
     * 是否是「退化包名」——旧版中文软件名被 sanitize 清空后回退成 app 的产物。
     * 这类包名被多个软件共用，必须迁移。
     */
    static boolean isLegacySharedBundle(String b) {
        if (b == null) return false;
        String t = b.trim().toLowerCase(Locale.US);
        return t.equals("com.wlaz.app") || t.equals("com.wlaz.app.app");
    }
    /**
     * 校验 / 规范化原始 bundleName。
     * 只接受「像包名」的值（至少一个点、只含合法字符、不含中文），
     * 不合格一律返回 ""，交给上层兜底，避免把脏值写进 Profile 导致申请失败。
     */
    public static String normalizeRawBundle(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toLowerCase(Locale.US);
        if (t.length() < 3 || t.length() > 120) return "";
        // 必须只含合法字符
        if (!t.matches("[a-z0-9_.]+")) return "";
        // 必须至少有一个点（a.b），且不以点开头/结尾、无连续点
        if (t.indexOf('.') < 0) return "";
        if (t.startsWith(".") || t.endsWith(".") || t.contains("..")) return "";
        return t;
    }


    /**
     * 按包名查 appId：GET `api/publish/v2/appid-list?packageName=`。
     * 返回 `{ret, appids:[{key:应用名, value:appId}]}`；查不到返回 ""。
     */
    public static String appIdOfPackage(Context c, String bundle) {
        try {
            Object j = call(c, "/api/publish/v2/appid-list?packageName="
                    + java.net.URLEncoder.encode(bundle, "UTF-8"), null, "GET");
            List<Object> list = MiniJson.list(j, "appids");
            for (Object o : list) {
                String v = MiniJson.str(o, "value");
                if (v.length() > 0) return v;
            }
        } catch (Exception e) {
            android.util.Log.w("AgcApi", "appIdOfPackage(" + bundle + ") 失败：" + e.getMessage());
        }
        return "";
    }

    // ---------------- 项目（Project） ----------------

    /**
     * 复用的项目名（精确匹配、区分大小写）：AGC 控制台里小写的 hdckit 项目。
     * 大写的 HdcKit 是别人的项目（com.fyt.hapinstaller），不能动。
     */
    private static final String PROJECT_NAME = "hdckit";

    /** prefs 键：已经绑定过的项目 id（后续创建应用直接沿用，不再挑项目） */
    private static final String BOUND_PROJECT = "bound_project_id";

    /** 取账号下全部项目 */
    private static List<Object> listProjects(Context c) throws Exception {
        Object j = call(c, "/api/project-service/v1/projects", null, "GET");
        return MiniJson.list(j, "projectList");
    }

    /** 查项目下应用列表 */
    private static List<Object> appsOfProject(Context c, String projectId) throws Exception {
        Object j = call(c, "/api/project-service/v1/projects/" + projectId
                + "?queryFlag=1&pageIndex=1&pageSize=50", null, "GET");
        return MiniJson.list(j, "project.appList");
    }

    /**
     * 挑一个项目来强制绑定（按项目名，不按项目 id）。
     *   0. 之前已经绑定过的项目（prefs 里的 bound_project_id）优先复用，一个项目可挂多个应用
     *   1. 否则找名字**恰好等于 hdckit（小写）** 的项目，取创建时间最早的那个
     * 不再检查项目下已有多少应用：小写 hdckit 就是自己的项目，随便往里塞。
     * 注意：大写 HdcKit 是别人的（里面有 com.fyt.hapinstaller），一律不选。
     */
    public static String pickProject(Context c, String appName) {
        try {
            List<Object> projects = listProjects(c);

            // 0) 已绑定的项目
            String bound = p(c).getString(BOUND_PROJECT, "");
            if (bound.length() > 0) {
                for (Object o : projects) {
                    if (bound.equals(MiniJson.str(o, "projectId"))) {
                        android.util.Log.i("AgcApi", "pickProject 沿用已绑定项目 " + bound);
                        return bound;
                    }
                }
            }

            String best = "";
            long bestTime = Long.MAX_VALUE;
            for (Object o : projects) {
                String name = MiniJson.str(o, "name");
                if (!PROJECT_NAME.equals(name)) continue;
                String pid = MiniJson.str(o, "projectId");
                if (pid.length() == 0) continue;
                long t = (long) MiniJson.integer(o, "createTime", 0);
                android.util.Log.i("AgcApi", "pickProject 命中项目 " + name + "/" + pid);
                if (t < bestTime) { bestTime = t; best = pid; }
            }
            return best;
        } catch (Exception e) {
            android.util.Log.w("AgcApi", "pickProject 失败：" + e);
            return "";
        }
    }

    /** 某软件对应的 AGC 包名（bundleName） */
    public static String bundleOf(Context c, String appKey) {
        String key = "app_" + (appKey == null ? "" : appKey.trim());
        return p(c).getString(key + "_bundle", "");
    }

    /** 兼容旧调用 */
    public static String ensureApp(Context c) throws Exception {
        return ensureApp(c, "default", "腕连助手");
    }

    /** 原版 trimAppName：去掉首尾空白，空则 app */
    private static String trimAppName(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() == 0 ? "app" : t;
    }

    /** 原版规范：小写、只留 a-z0-9_.，合并多余点，去首尾点 */
    public static String sanitizeAppName(String s) {
        String t = trimAppName(s).toLowerCase(Locale.US);
        t = t.replaceAll("[^a-z0-9_.]", "");
        t = t.replaceAll("\\.{2,}", ".");
        t = t.replaceAll("^\\.|\\.$", "");
        if (t.length() == 0) t = "app";
        return t;
    }

    public static String bundle(Context c) {
        return bundleOf(c, "default");
    }

    // ---------------- Profile ----------------

    public static class Profile {
        public String id = "";
        public String name = "";
        public String downloadUrl = "";
        public String certName = "";
        public String appId = "";
        public int type = 1;
    }

    public static List<Profile> listProfiles(Context c) throws Exception {
        Object j = call(c, "/api/publish/v2/provision/list", null, "GET");
        List<Profile> out = new ArrayList<Profile>();
        for (Object o : MiniJson.list(j, "provisionList")) {
            Profile p1 = new Profile();
            p1.id = MiniJson.str(o, "id");
            p1.name = MiniJson.str(o, "provisionName");
            p1.certName = MiniJson.str(o, "certName");
            p1.appId = MiniJson.str(o, "appId");
            p1.type = MiniJson.integer(o, "provisionType", 1);
            p1.downloadUrl = MiniJson.str(o, "provisionDownloadUrl");
            out.add(p1);
        }
        return out;
    }

    /** 申请调试 Profile（绑定应用 + 这台手表），返回下载地址 */
    public static Profile createDebugProfile(Context c, String appId, String certId, String deviceId) throws Exception {
        return createDebugProfile(c, appId, certId, deviceId, "", "");
    }

    /**
     * 申请调试 Profile。
     *   · DevEco 通道：POST /api/cps/provision-manage/v1/provision/add
     *     走「包名」+ certList/deviceList，不需要先建应用；
     *   · 旧通道（SA）：POST /api/publish/v3/provision，用 appId + deviceIdList。
     */
    public static Profile createDebugProfile(Context c, String appId, String certId, String deviceId,
                                             String bundle, String provisionName) throws Exception {
        if (hasDevEcoLogin(c) && bundle != null && bundle.length() > 0) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            List<Object> certs = new ArrayList<Object>();
            certs.add(certId);
            List<Object> devs = new ArrayList<Object>();
            devs.add(deviceId);
            body.put("certList", certs);
            body.put("packageName", bundle);
            body.put("deviceList", devs);
            body.put("provisionName", provisionName != null && provisionName.length() > 0
                    ? provisionName : bundle);
            body.put("aclPermissionList", new ArrayList<Object>());
            Object j = devCall(c, "/api/cps/provision-manage/v1/ide/test/provision/add", body, "POST");
            if (j != null) {
                Profile p1 = parseProfile(j, appId);
                if (p1.downloadUrl.length() > 0) return p1;
                // 拿到 id 但没有直链 → 尝试换取下载地址
                if (p1.id.length() > 0) {
                    String url = reapplyUrl(c, p1.downloadUrl);
                    if (url.length() > 0) {
                        p1.downloadUrl = url;
                        return p1;
                    }
                }
                android.util.Log.w("AgcApi", "DevEco Profile 响应无下载地址：" + MiniJson.write(j));
            }
        }

        // 旧通道
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("provisionName", provisionName != null && provisionName.length() > 0
                ? provisionName : ("wl_dbg_" + rand(4)));
        body.put("provisionType", 1);
        body.put("certId", certId);
        body.put("appId", appId);
        List<Object> devs = new ArrayList<Object>();
        devs.add(deviceId);
        body.put("deviceIdList", devs);
        Object j = call(c, "/api/publish/v3/provision", body, "POST");
        Profile p1 = parseProfile(MiniJson.get(j, "provisionInfo"), appId);
        p1.type = 1;
        if (p1.id.length() == 0 || p1.downloadUrl.length() == 0) {
            String s = MiniJson.write(j);
            throw new Exception("申请 Profile 失败：" + s.substring(0, Math.min(240, s.length())));
        }
        return p1;
    }

    /** 解析 Profile 响应（兼容 provisionInfo / data / ret / 平铺） */
    private static Profile parseProfile(Object j, String appId) {
        Profile p1 = new Profile();
        p1.appId = appId;
        p1.type = 1;
        String[] paths = {"provisionInfo", "data", "ret"};
        for (String k : paths) {
            Object o = MiniJson.get(j, k);
            if (o == null) continue;
            p1.id = firstStr(o, "id", "provisionId");
            if (p1.id.length() > 0) {
                p1.name = firstStr(o, "provisionName", "name");
                p1.certName = firstStr(o, "certName");
                p1.downloadUrl = firstStr(o, "provisionDownloadUrl", "provisionFileUrl",
                        "downloadUrl", "url", "fileUrl");
                if (p1.downloadUrl.length() > 0) return p1;
            }
        }
        p1.id = firstStr(j, "id", "provisionId");
        p1.name = firstStr(j, "provisionName", "name");
        p1.certName = firstStr(j, "certName");
        p1.downloadUrl = firstStr(j, "provisionDownloadUrl", "provisionFileUrl",
                "downloadUrl", "url", "fileUrl");
        return p1;
    }

    /** objectId → 可下载直链（DevEco 通道需要 reapply） */
    private static String reapplyUrl(Context c, String objectId) {
        if (objectId == null || objectId.length() == 0) return "";
        if (objectId.startsWith("http")) return objectId;
        try {
            if (!hasDevEcoLogin(c)) return "";
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            List<Object> arr = new ArrayList<Object>();
            arr.add(objectId);
            body.put("sourceUrls", arr);
            Object j = devCall(c, "/api/amis/app-manage/v1/objects/url/reapply", body, "POST");
            if (j == null) return "";
            for (Object o : firstList(j, "urlsInfo", "data.urlsInfo", "list")) {
                String u = firstStr(o, "newUrl", "url", "realUrl");
                if (u.length() > 0) return u;
            }
        } catch (Exception e) {
            android.util.Log.w("AgcApi", "reapplyUrl 失败：" + e.getMessage());
        }
        return "";
    }

    /** 下载 p7b 字节 */
    public static byte[] downloadProfile(String url) throws Exception {
        HttpReply r = http(url, null, null, "GET");
        if (r.code != 200) throw new Exception("下载 Profile 失败 HTTP " + r.code);
        return r.body;
    }

    /**
     * 一站式：设备 → 证书 → 应用 → Profile → 下载 p7b。
     *
     * @param udid      手表 UDID
     * @param watchName 手表名
     * @param appKey    软件稳定标识（AppItem.id），决定用哪个 AGC 应用
     * @param appName   软件名，用来派生 bundleName
     */
    public static byte[] provisionForWatch(Context c, String udid, String watchName,
                                          String appKey, String appName, StringBuilder log) throws Exception {
        return provisionForWatch(c, udid, watchName, appKey, appName, "", log);
    }

    /**
     * 一站式（带原始包名）：设备 → 证书 → 应用 → Profile → 下载 p7b。
     *
     * @param rawBundle 安装包 config.json 里的原始 bundleName（决定签名后包名，
     *                  为空时才会退回按软件名推导 —— 中文名软件推导结果会撞成 com.wlaz.app）
     */
    public static byte[] provisionForWatch(Context c, String udid, String watchName,
                                          String appKey, String appName, String rawBundle,
                                          StringBuilder log) throws Exception {
        if (!hasCredential(c)) throw new Exception("还没有登录开发者账号（我的 → DevEco 账号 → 登录）");

        // ★ 包名：优先用安装包原始 bundleName，保证每个软件唯一、互不覆盖
        String bundle = bundleForApp(c, appKey, appName, rawBundle);
        log(log, "包名：" + bundle + (rawBundle != null && rawBundle.length() > 0
                ? "（取自安装包原始包名）" : "（按软件名推导）"));

        Device dev = ensureDevice(c, udid, watchName);
        log(log, "设备已登记：" + dev.name + " (" + dev.id + ")");

        Cert cert = ensureDebugCert(c);
        log(log, "调试证书：" + cert.name);

        // DevEco 通道按包名申请 Profile，不需要先建应用；旧通道仍需要 appId
        String appId = "";
        if (!hasDevEcoLogin(c)) {
            appId = ensureApp(c, appKey, appName);
            log(log, "应用 " + bundle + " -> " + appId);
        }

        Profile prof = createDebugProfile(c, appId, cert.id, dev.id,
                bundle, "wl_" + safe(appKey) + "_" + safe(bundle));
        log(log, "Profile 已申请：" + prof.name);

        byte[] p7b = downloadProfile(prof.downloadUrl);
        if (p7b.length < 100) throw new Exception("Profile 下载内容异常（" + p7b.length + " 字节）");

        java.io.File f = new java.io.File(profileDir(c), "wl_" + safe(appKey) + "_" + safe(bundle) + ".p7b");
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
        fo.write(p7b);
        fo.close();
        p(c).edit().putString("profile_path", f.getAbsolutePath())
                .putString("profile_id", prof.id)
                .putString("profile_name", prof.name)
                .putString("profile_udid", udid)
                .putString("profile_bundle", bundle)
                .putLong("profile_time", System.currentTimeMillis())
                .apply();
        log(log, "Profile 已保存：" + f.getAbsolutePath());
        return p7b;
    }

    /** 兼容旧签名（固定载体应用） */
    public static byte[] provisionForWatch(Context c, String udid, String watchName, StringBuilder log) throws Exception {
        return provisionForWatch(c, udid, watchName, "default", "腕连助手", log);
    }

    private static String safe(String s) {
        if (s == null) return "x";
        String t = s.replaceAll("[^A-Za-z0-9_.-]", "_");
        return t.length() == 0 ? "x" : t;
    }

    public static java.io.File profileDir(Context c) {
        java.io.File d = new java.io.File(c.getFilesDir(), "agc");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void log(StringBuilder b, String s) {
        if (b != null) b.append(s).append('\n');
    }

    public static String rand(int n) {
        String abc = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random r = new Random();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(abc.charAt(r.nextInt(abc.length())));
        return s.toString();
    }
}