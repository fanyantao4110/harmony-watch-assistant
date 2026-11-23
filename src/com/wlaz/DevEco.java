package com.wlaz;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DevEco 模式的华为开发者账号登录。
 *
 * 流程（与 HdcKit / hdccore 的 HuaweiAuthService 完全一致）：
 *   1. 本机 127.0.0.1 随机端口起一个极简 HTTP 服务
 *   2. 用系统浏览器打开授权页：https://cn.devecostudio.huawei.com/console/DevEcoIDE/apply?port=<本地端口>
 *      （应用不内置登录地址，由用户填写；默认落在 CN 站点）
 *   3. 华为站点授权完成后 POST /callback（表单）带 tempToken
 *   4. GET  .../authrouter/auth/api/temptoken/check?site=1&tempToken=..&appid=1007&version=4.2.0
 *      —— 响应体是**明文 jwtToken**（也可能是 JSON 包裹）
 *   5. GET  .../authrouter/auth/api/jwToken/check  请求头 jwtToken + refresh:true
 *      —— 返回 userInfo{ name,userId,accessToken,realName,... }
 *   6. POST account.cloud.huawei.com/rest.php?nsp_svc=GOpen.User.getInfo 补全昵称
 *
 * 之后所有 CloudGW 业务接口（证书/设备/Profile/应用/项目）都带
 *   oauth2Token + uid + teamId + User-Agent: DevEcoStudio/5.0.3 (WindowsNT)
 * 而不是旧的 client_id/client_secret —— 这正是「DevEco 方式」。
 */
public class DevEco {

    // ---- 站点（与 hdccore BackendConfig 对齐）----
    public static final String HOST_CN = "https://cn.devecostudio.huawei.com";
    public static final String HOST_SG = "https://sg.devecostudio.huawei.com";
    public static final String HOST_DE = "https://de.devecostudio.huawei.com";
    public static final String HOST_RU = "https://ru.devecostudio.huawei.com";

    /** 授权页路径 */
    public static final String AUTH_PATH = "/console/DevEcoIDE/apply";
    /** DevEco 侧固定的应用标识 */
    public static final String DEVECO_APP_ID = "1007";
    public static final String DEVECO_VERSION = "4.2.0";
    /** CloudGW 校验 UA，必须伪装成 DevEco Studio */
    public static final String UA = "DevEcoStudio/5.0.3 (WindowsNT)";

    public static final String PATH_TEMP_TOKEN_CHECK = "/authrouter/auth/api/temptoken/check";
    public static final String PATH_JWT_CHECK = "/authrouter/auth/api/jwToken/check";
    public static final String USER_INFO_URL =
            "https://account.cloud.huawei.com/rest.php?nsp_svc=GOpen.User.getInfo";

    /** 登录进度回调（主线程） */
    public interface Listener {
        void onProgress(String text);

        void onDone(boolean ok, String message);
    }

    private static ServerSocket server;
    private static Thread serverThread;
    private static volatile boolean running = false;
    private static volatile Listener listener;
    private static volatile int callbackPort = 0;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---------------- 登录地址 ----------------

    /** 用户填写的登录地址（未填则用 CN 站点默认） */
    public static String loginBase(Context c) {
        String s = AgcApi.p(c).getString(AgcApi.K_LOGIN_ADDR, "").trim();
        return s.length() == 0 ? HOST_CN : s;
    }

    /** 归一化：补协议、去掉 ?port=、只填域名时补授权页路径 */
    public static String normalizeLoginAddress(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.length() == 0) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // 只填域名（没有路径部分）→ 补授权页路径
        String afterScheme = u.substring(u.indexOf("://") + 3);
        if (afterScheme.indexOf('/') < 0) u = u + AUTH_PATH;
        return u;
    }

    /** 拼授权页 URL：<登录地址>?port=<本地回调端口> */
    public static String buildAuthorizationUrl(Context c, int port) {
        String base = normalizeLoginAddress(loginBase(c));
        if (base.length() == 0) base = HOST_CN + AUTH_PATH;
        return base + "?port=" + port;
    }

    // ---------------- 登录 ----------------

    /**
     * 启动登录：起本地回调服务 + 拉起浏览器。
     * 结果通过 listener 回到主线程。
     */
    public static void login(final Context c, final Listener l) {
        listener = l;
        if (running) {
            post("已有登录在进行中", false, "已有登录在进行中");
            return;
        }
        final Context app = c.getApplicationContext();
        postProgress("正在启动认证服务…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", 0));
                    server = ss;
                    running = true;
                    callbackPort = ss.getLocalPort();

                    final String url = buildAuthorizationUrl(app, callbackPort);
                    postProgress("正在打开浏览器…");
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            openBrowser(app, url, l);
                        }
                    });
                    acceptLoop(app);
                } catch (Exception e) {
                    running = false;
                    post("无法启动本地回调服务：" + e.getMessage(), false,
                            "无法启动本地回调服务：" + e.getMessage());
                }
            }
        }, "deveco-callback").start();
    }

    /** 拉起浏览器；失败则把链接抛给用户手动打开 */
    private static void openBrowser(Context c, final String url, final Listener l) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            postProgress("等待浏览器授权；若未弹出，请手动打开：\n" + url);
        } catch (Exception e) {
            postProgress("无法自动打开浏览器，请手动打开：\n" + url);
        }
    }

    private static void acceptLoop(Context c) {
        while (running && server != null && !server.isClosed()) {
            Socket s = null;
            try {
                s = server.accept();
                handleConn(c, s);
            } catch (Exception e) {
                if (running) post("回调服务异常：" + e.getMessage(), false, "回调服务异常：" + e.getMessage());
                break;
            } finally {
                try {
                    if (s != null) s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 处理一次回调请求（极简 HTTP 解析，够用即可） */
    private static void handleConn(Context c, Socket s) throws Exception {
        s.setSoTimeout(20000);
        InputStream in = s.getInputStream();
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        int contentLength = 0;
        int headerEnd = -1;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, "UTF-8"));
            String cur = sb.toString();
            if (headerEnd < 0) {
                headerEnd = cur.indexOf("\r\n\r\n");
                if (headerEnd >= 0) {
                    String head = cur.substring(0, headerEnd).toLowerCase();
                    int p = head.indexOf("content-length:");
                    if (p >= 0) {
                        int end = head.indexOf("\r\n", p);
                        String v = (end < 0 ? head.substring(p + 15) : head.substring(p + 15, end)).trim();
                        try {
                            contentLength = Integer.parseInt(v);
                        } catch (Exception ignored) {
                        }
                    }
                    if (contentLength == 0) break;
                }
            }
            if (headerEnd >= 0 && cur.length() - (headerEnd + 4) >= contentLength) break;
        }

        String req = sb.toString();
        int firstLineEnd = req.indexOf("\r\n");
        String firstLine = firstLineEnd > 0 ? req.substring(0, firstLineEnd) : "";
        String method = firstLine.split(" ")[0];
        String target = firstLine.contains(" ") ? firstLine.split(" ")[1] : "/";
        String body = "";
        int he = req.indexOf("\r\n\r\n");
        if (he >= 0) body = req.substring(he + 4);

        Map<String, String> params = new HashMap<String, String>();
        int qi = target.indexOf('?');
        if (qi >= 0) parseForm(target.substring(qi + 1), params);
        if ("POST".equalsIgnoreCase(method)) parseForm(body, params);

        String tempToken = first(params, "tempToken", "temptoken", "temp_token", "code");
        if (tempToken.length() == 0) {
            // 首页/图标等无关请求
            writeHtml(s, 200, "HdcKit 授权服务运行中");
            return;
        }

        String siteId = first(params, "siteId", "siteid", "site");
        postProgress("已收到授权码，正在换取登录令牌…");
        String err = exchange(c, tempToken, siteId);
        if (err.length() == 0) {
            writeHtml(s, 200, "授权成功，请返回鸿蒙手表助手继续使用。");
            stop();
            post("登录成功", true, "登录成功");
        } else {
            writeHtml(s, 500, "授权未完成：" + err);
            stop();
            post("登录失败：" + err, false, err);
        }
    }

    private static String first(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && v.trim().length() > 0) return v.trim();
        }
        return "";
    }

    private static void parseForm(String s, Map<String, String> out) {
        if (s == null) return;
        for (String kv : s.split("&")) {
            if (kv.length() == 0) continue;
            int e = kv.indexOf('=');
            String k = e < 0 ? kv : kv.substring(0, e);
            String v = e < 0 ? "" : kv.substring(e + 1);
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            out.put(k, v);
        }
    }

    private static void writeHtml(Socket s, int code, String msg) {
        try {
            String html = "<html><meta charset=\"utf-8\"><body style=\"font-family:sans-serif;"
                    + "padding:40px;text-align:center\"><h3>" + msg + "</h3></body></html>";
            byte[] b = html.getBytes("UTF-8");
            String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(head.getBytes("UTF-8"));
            o.write(b);
            o.flush();
        } catch (Exception ignored) {
        }
    }

    /** 停止本地回调服务 */
    public static void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        serverThread = null;
    }

    public static boolean isRunning() {
        return running;
    }

    public static int callbackPort() {
        return callbackPort;
    }

    // ---------------- 换票 ----------------

    /** 站点 → DevEco 域名 */
    public static String devecoHost(String siteId) {
        if ("5".equals(siteId)) return HOST_SG;
        if ("7".equals(siteId)) return HOST_DE;
        if ("8".equals(siteId)) return HOST_RU;
        return HOST_CN;
    }

    /** 站点 ID（CN=1 / SG=5 / DE=7 / RU=8） */
    public static String siteIdOf(String host) {
        if (host == null) return "1";
        if (host.contains("//sg.")) return "5";
        if (host.contains("//de.")) return "7";
        if (host.contains("//ru.")) return "8";
        return "1";
    }

    /** 由登录地址推站点 ID */
    public static String siteIdOfLogin(Context c) {
        return siteIdOf(loginBase(c));
    }

    /**
     * tempToken → jwtToken → userInfo → GOpen 补全 → 落盘。
     * 返回空串表示成功，否则是错误文案。
     */
    public static String exchange(Context c, String tempToken, String siteIdHint) {
        try {
            String site = siteIdHint != null && siteIdHint.length() > 0 ? siteIdHint : siteIdOfLogin(c);
            String host = devecoHost(site);
            String jwt = fetchJwt(host, tempToken, site);
            if (jwt.length() == 0 && !HOST_CN.equals(host)) {
                host = HOST_CN;
                site = "1";
                jwt = fetchJwt(host, tempToken, site);
            }
            if (jwt.length() == 0) return "未能换取登录令牌（tempToken 校验失败）";

            Map<String, Object> info = fetchUserInfo(host, jwt);
            if (info == null) return "云端未返回有效身份信息（请确认账号已实名）";

            String accessToken = MiniJson.str(info, "accessToken");
            if (accessToken.length() == 0) accessToken = MiniJson.str(info, "userInfo.accessToken");
            if (accessToken.length() == 0) return "云端未返回 accessToken";
            String uid = MiniJson.str(info, "userId");
            if (uid.length() == 0) uid = MiniJson.str(info, "userInfo.userId");
            String name = MiniJson.str(info, "nickName");
            if (name.length() == 0) name = MiniJson.str(info, "name");
            if (name.length() == 0) name = MiniJson.str(info, "userInfo.nickName");
            if (name.length() == 0) name = MiniJson.str(info, "userInfo.name");
            String teamId = MiniJson.str(info, "teamId");
            if (teamId.length() == 0) teamId = MiniJson.str(info, "userInfo.teamId");

            String real = fetchDisplayName(accessToken);
            if (real.length() > 0) name = real;
            if (name.length() == 0) name = "开发者";

            AgcApi.p(c).edit()
                    .putString(AgcApi.K_ACCESS_TOKEN, accessToken)
                    .putString(AgcApi.K_JWT_TOKEN, jwt)
                    .putString(AgcApi.K_UID, uid)
                    .putString(AgcApi.K_TEAM_ID, teamId)
                    .putString(AgcApi.K_DISPLAY_NAME, name)
                    .putString(AgcApi.K_SITE_ID, site)
                    .putLong(AgcApi.K_ACCESS_EXP, System.currentTimeMillis() + 50L * 60 * 1000)
                    .apply();
            android.util.Log.i("DevEco", "登录成功 uid=" + uid + " name=" + name + " site=" + site);
            return "";
        } catch (Exception e) {
            android.util.Log.w("DevEco", "exchange 失败", e);
            return "登录失败：" + e.getMessage();
        }
    }

    /** 第 5 步：tempToken → jwtToken（响应是明文，也兼容 JSON） */
    private static String fetchJwt(String host, String tempToken, String site) throws Exception {
        String url = host + PATH_TEMP_TOKEN_CHECK
                + "?site=" + site
                + "&tempToken=" + java.net.URLEncoder.encode(tempToken, "UTF-8")
                + "&appid=" + DEVECO_APP_ID
                + "&version=" + DEVECO_VERSION;
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        AgcApi.HttpReply r = AgcApi.http(url, null, h, "GET");
        if (r.code != 200) return "";
        String raw = r.text().trim();
        if (raw.length() == 0) return "";
        if (raw.startsWith("{")) {
            Object j = MiniJson.parse(raw);
            String v = MiniJson.str(j, "jwtToken");
            if (v.length() > 0) return v;
            v = MiniJson.str(j, "data.jwtToken");
            if (v.length() > 0) return v;
            v = MiniJson.str(j, "userInfo.jwtToken");
            if (v.length() > 0) return v;
        }
        return stripQuotes(raw);
    }

    /** 第 6 步：jwtToken → userInfo */
    private static Map<String, Object> fetchUserInfo(String host, String jwt) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("jwtToken", jwt);
        h.put("refresh", "true");
        AgcApi.HttpReply r = AgcApi.http(host + PATH_JWT_CHECK, null, h, "GET");
        if (r.code != 200) return null;
        String t = r.text().trim();
        if (t.length() == 0) return null;
        Object j = MiniJson.parse(t);
        String status = MiniJson.str(j, "status");
        if ("false".equals(status)) return null;
        Object data = MiniJson.get(j, "data");
        if (data instanceof Map) return castMap(data);
        Object ui = MiniJson.get(j, "userInfo");
        if (ui instanceof Map) return castMap(ui);
        if (j instanceof Map) return castMap(j);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** 补全昵称（失败不影响登录） */
    private static String fetchDisplayName(String accessToken) {
        try {
            String body = "access_token=" + java.net.URLEncoder.encode(accessToken, "UTF-8") + "&getNickName=1";
            Map<String, String> h = new HashMap<String, String>();
            h.put("Content-Type", "application/x-www-form-urlencoded");
            AgcApi.HttpReply r = AgcApi.http(USER_INFO_URL, body.getBytes("UTF-8"), h, "POST");
            if (r.code != 200) return "";
            Object j = MiniJson.parse(r.text());
            String n = MiniJson.str(j, "nickName");
            if (n.length() == 0) n = MiniJson.str(j, "displayName");
            if (n.length() == 0) n = MiniJson.str(j, "userInfo.nickName");
            return n;
        } catch (Exception e) {
            return "";
        }
    }

    /** accessToken 过期时用 jwtToken 刷新；成功返回新 token */
    public static String refresh(Context c) {
        String jwt = AgcApi.p(c).getString(AgcApi.K_JWT_TOKEN, "");
        if (jwt.length() == 0) return "";
        String site = AgcApi.p(c).getString(AgcApi.K_SITE_ID, siteIdOfLogin(c));
        try {
            Map<String, Object> info = fetchUserInfo(devecoHost(site), jwt);
            if (info == null) return "";
            String tok = MiniJson.str(info, "accessToken");
            if (tok.length() == 0) tok = MiniJson.str(info, "userInfo.accessToken");
            if (tok.length() == 0) return "";
            String newJwt = MiniJson.str(info, "jwtToken");
            if (newJwt.length() == 0) newJwt = MiniJson.str(info, "userInfo.jwtToken");
            android.content.SharedPreferences.Editor e = AgcApi.p(c).edit();
            e.putString(AgcApi.K_ACCESS_TOKEN, tok);
            if (newJwt.length() > 0) e.putString(AgcApi.K_JWT_TOKEN, newJwt);
            e.putLong(AgcApi.K_ACCESS_EXP, System.currentTimeMillis() + 50L * 60 * 1000);
            e.apply();
            return tok;
        } catch (Exception e) {
            return "";
        }
    }

    /** 退出登录：清掉 DevEco 凭据（不影响已绑定的项目记录） */
    public static void logout(Context c) {
        AgcApi.p(c).edit()
                .remove(AgcApi.K_ACCESS_TOKEN)
                .remove(AgcApi.K_JWT_TOKEN)
                .remove(AgcApi.K_UID)
                .remove(AgcApi.K_TEAM_ID)
                .remove(AgcApi.K_DISPLAY_NAME)
                .remove(AgcApi.K_ACCESS_EXP)
                .apply();
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) t = t.substring(1, t.length() - 1);
        return t;
    }

    private static void postProgress(final String text) {
        final Listener l = listener;
        if (l == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                l.onProgress(text);
            }
        });
    }

    private static void post(String progress, final boolean ok, final String msg) {
        postProgress(progress);
        final Listener l = listener;
        if (l == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                l.onDone(ok, msg);
            }
        });
    }
}