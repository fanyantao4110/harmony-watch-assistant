package com.wlaz;

import java.util.ArrayList;
import java.util.List;

/**
 * P7B(Profile) 解析：从 CMS SignedData 里取出 eContent(JSON) 与证书链。
 * 结构：ContentInfo{ OID signedData, [0]{ SignedData{
 *          version, digestAlgorithms, encapContentInfo{ OID, [0]{ OCTET STRING } },
 *          [0] certificates, signerInfos } } }
 */
public class P7b {

    public String json = "";
    public String bundleName = "";
    public String leafCertPem = "";
    public long notBefore = 0L;
    public long notAfter = 0L;
    public String type = "";
    public List<String> deviceIds = new ArrayList<String>();
    public List<String> certPems = new ArrayList<String>();

    public static P7b parse(byte[] p7b) throws Exception {
        P7b out = new P7b();
        Der.Node ci = Der.first(p7b);
        if (ci == null) throw new Exception("p7b 不是合法 DER");
        List<Der.Node> ciKids = Der.kids(p7b, ci);
        if (ciKids.size() < 2) throw new Exception("p7b ContentInfo 结构异常");

        Der.Node explicit = null;
        for (Der.Node n : ciKids) {
            if (n.isCtx()) {
                explicit = n;
                break;
            }
        }
        if (explicit == null) throw new Exception("p7b 缺少 [0] 内容");
        List<Der.Node> expKids = Der.kids(p7b, explicit);
        if (expKids.isEmpty()) throw new Exception("p7b [0] 为空");
        Der.Node signedData = expKids.get(0);

        List<Der.Node> sd = Der.kids(p7b, signedData);
        Der.Node encap = null;
        Der.Node certs = null;
        for (Der.Node n : sd) {
            if (n.isCtx()) {
                if (certs == null) certs = n;
                continue;
            }
            if (encap == null && n.tag == Der.SEQ) {
                List<Der.Node> k = Der.kids(p7b, n);
                if (k.size() >= 2 && k.get(0).tag == Der.OID) encap = n;
            }
        }
        if (encap == null) throw new Exception("p7b 缺少 encapContentInfo");

        List<Der.Node> ek = Der.kids(p7b, encap);
        byte[] content = null;
        for (Der.Node n : ek) {
            if (n.isCtx()) {
                for (Der.Node x : Der.kids(p7b, n)) {
                    if (x.tag == Der.OCTET) {
                        content = x.value();
                        break;
                    }
                }
                if (content == null) content = n.value();
            } else if (n.tag == Der.OCTET) {
                content = n.value();
            }
        }
        if (content == null) throw new Exception("p7b 里没有 eContent");
        out.json = new String(content, java.nio.charset.Charset.forName("UTF-8"));

        if (certs != null) {
            for (Der.Node c : Der.kids(p7b, certs)) {
                if (c.tag == Der.SEQ) out.certPems.add(toPem(c.full()));
            }
        }

        Object root = MiniJson.parse(out.json);
        out.bundleName = MiniJson.str(root, "bundle-info.bundle-name");
        out.type = MiniJson.str(root, "type");
        out.leafCertPem = MiniJson.str(root, "bundle-info.development-certificate");
        out.notBefore = (long) numOf(root, "validity.not-before");
        out.notAfter = (long) numOf(root, "validity.not-after");
        for (Object d : MiniJson.list(root, "debug-info.device-ids")) {
            out.deviceIds.add(String.valueOf(d));
        }
        if (out.leafCertPem.length() == 0 && !out.certPems.isEmpty()) {
            out.leafCertPem = out.certPems.get(0);
        }
        return out;
    }

    private static double numOf(Object root, String path) {
        Object v = MiniJson.get(root, path);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    public static String toPem(byte[] der) {
        String b = b64enc(der);
        StringBuilder s = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b.length(); i += 64) {
            s.append(b, i, Math.min(i + 64, b.length())).append('\n');
        }
        s.append("-----END CERTIFICATE-----\n");
        return s.toString();
    }

    public static byte[] fromPem(String pem) throws Exception {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return b64dec(body);
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String b64enc(byte[] d) {
        StringBuilder s = new StringBuilder();
        int i = 0;
        while (i + 3 <= d.length) {
            int v = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
            s.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append(B64[v & 63]);
            i += 3;
        }
        int rem = d.length - i;
        if (rem == 1) {
            int v = (d[i] & 0xFF) << 16;
            s.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append("==");
        } else if (rem == 2) {
            int v = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8);
            s.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append('=');
        }
        return s.toString();
    }

    public static byte[] b64dec(String s) {
        int[] map = new int[128];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < B64.length; i++) map[B64[i]] = i;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            int v = c < 128 ? map[c] : -1;
            if (v < 0) continue;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                o.write((buf >> bits) & 0xFF);
            }
        }
        return o.toByteArray();
    }
}