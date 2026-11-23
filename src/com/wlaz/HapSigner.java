package com.wlaz;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * HAP 重签名（纯 Java，等价于 hap-sign-utils / sign_hap.py）
 *
 * 流程：
 *   1. 从源 HAP 里取出 signed.bin，解析出 bundleName + 全部文件
 *   2. 改 config.json / pack.info 的 bundleName
 *   3. 重新拼 bin（魔数 0xBE + bundle + 文件表）
 *   4. 追加 proBlock(p7b) + signBlock 占位 + p7b
 *   5. slicedSHA256 -> CMS(SHA256/ECDSA) 签名
 *   6. 追加 signHead，输出 signed.bin 打包成 HAP
 */
public class HapSigner {

    private static final int MAGIC = 0xBE;
    private static final byte[] MARKER = "hw signed app   1000".getBytes();

    private static final String OID_SHA256 = "2.16.840.1.101.3.4.2.1";
    private static final String OID_DATA = "1.2.840.113549.1.7.1";
    private static final String OID_SIGNED_DATA = "1.2.840.113549.1.7.2";
    private static final String OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2";
    private static final String OID_SIGNING_TIME = "1.2.840.113549.1.9.5";
    private static final String OID_CONTENT_TYPE = "1.2.840.113549.1.9.3";
    private static final String OID_MESSAGE_DIGEST = "1.2.840.113549.1.9.4";

    public static class Result {
        public File out;
        public String bundle = "";
        public int fileCount = 0;
        public String message = "";
    }

    // ---------------- bin 解析 ----------------

    public static class Entry {
        public String path;
        public String name;
        public byte[] content;
    }

    public static class Bin {
        public String bundle = "";
        public List<Entry> files = new ArrayList<Entry>();
        public byte[] p7b;
    }

    public static Bin parseBin(byte[] data) throws Exception {
        if (data.length < 6 || (data[0] & 0xFF) != MAGIC) throw new Exception("signed.bin 魔数不对");
        Bin out = new Bin();
        int n = readInt(data, 1, 4);
        out.bundle = new String(data, 5, n, "UTF-8");
        int unsignedEnd = data.length;
        int base = 5 + n;

        int markerAt = data.length - 32;
        if (markerAt >= 0) {
            boolean ok = true;
            for (int i = 0; i < MARKER.length; i++) {
                if (data[markerAt + i] != MARKER[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                int sub = readInt(data, data.length - 12, 4);
                int[] cands = {data.length - sub, data.length - sub - 8, data.length - sub - 16, data.length - sub + 8};
                for (int pos : cands) {
                    if (pos < 10 || pos + 8 > data.length - 32) continue;
                    boolean tagOk = (data[pos] & 0xFF) == 0x02
                            || ((data[pos] & 0xFF) == 0x01 && (data[pos + 1] & 0xFF) == 0x00);
                    if (!tagOk) continue;
                    int ln = readInt(data, pos + 2, 2);
                    int off = readInt(data, pos + 4, 4);
                    if (off == pos + 16 && off + ln <= data.length - 32) {
                        unsignedEnd = pos;
                        out.p7b = new byte[ln];
                        System.arraycopy(data, off, out.p7b, 0, ln);
                        break;
                    }
                }
            }
        }

        int pos = base;
        while (pos + 4 <= unsignedEnd) {
            int nl = readInt(data, pos, 4);
            if (nl == 0 || nl > 4096 || pos + 4 + nl > unsignedEnd) break;
            String name = new String(data, pos + 4, nl, "UTF-8");
            pos += 4 + nl;
            if (pos + 4 > unsignedEnd) break;
            int pl = readInt(data, pos, 4);
            if (pos + 4 + pl > unsignedEnd) break;
            String path = new String(data, pos + 4, pl, "UTF-8");
            pos += 4 + pl;
            if (pos + 8 > unsignedEnd) break;
            long cl = readLong8(data, pos);
            pos += 8;
            if (cl < 0 || pos + cl > unsignedEnd) break;
            Entry e = new Entry();
            e.path = path;
            e.name = name;
            e.content = new byte[(int) cl];
            System.arraycopy(data, pos, e.content, 0, (int) cl);
            pos += cl;
            out.files.add(e);
        }
        return out;
    }

    private static long readLong8(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static int readInt(byte[] b, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return (int) (v & 0xFFFFFFFFL);
    }

    // ---------------- bin 构建 ----------------

    public static byte[] binBuild(Map<String, byte[]> files, String bundle) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(MAGIC);
        byte[] bb = bundle.getBytes("UTF-8");
        o.write(int4(bb.length));
        o.write(bb);
        // ★★ 必须保留【原始文件顺序】！
        // 实测基准可安装包（2048新版）bin 里的文件序是 hvigor 构架原生顺序，
        // 例如 base/media 下是 icon_small.bin -> icon_small.png -> icon.bin -> icon.png，
        // 不是字典序。之前这里强制按(层级,路径)重排，文件集合与内容都对、
        // CMS 也验得过，但手表端按顺序校验资源表后拒装。
        // collectFromApp 返回 LinkedHashMap（即原 bin 解析顺序），直接用它。
        List<String> keys = new ArrayList<String>(files.keySet());
        for (String full : keys) {
            byte[] content = files.get(full);
            String fn = full;
            String rel = "";
            int slash = full.lastIndexOf('/');
            if (slash >= 0) {
                fn = full.substring(slash + 1);
                rel = "/" + full.substring(0, slash);
            }
            byte[] fb = fn.getBytes("UTF-8");
            byte[] rb = rel.getBytes("UTF-8");
            o.write(int4(fb.length));
            o.write(fb);
            o.write(int4(rb.length));
            o.write(rb);
            o.write(int8(content.length));
            o.write(content);
        }
        return o.toByteArray();
    }

    private static byte[] int4(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] int8(int v) {
        return new byte[]{0, 0, 0, 0, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] blockHead(int t, int tag, int len, int off) {
        return new byte[]{(byte) t, (byte) tag,
                (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF),
                (byte) (off >>> 24), (byte) (off >>> 16), (byte) (off >>> 8), (byte) off};
    }

    private static byte[] signHead(int sub) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(MARKER, 0, MARKER.length);
        o.write(sub >> 24);
        o.write(sub >> 16);
        o.write(sub >> 8);
        o.write(sub);
        o.write(new byte[]{0, 0, 0, 2, 0, 0, 0, 0}, 0, 8);
        return o.toByteArray();
    }

    private static byte[] slicedSha256(byte[] data, int chunk) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int off = 0; off < data.length; off += chunk) {
            int len = Math.min(chunk, data.length - off);
            md.reset();
            md.update(data, off, len);
            all.write(md.digest());
        }
        md.reset();
        return md.digest(all.toByteArray());
    }

    private static byte[] byteContent(byte[] digest) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('1');
        o.write('0');
        o.write('0');
        o.write('0');
        int size = 8 + 8 + digest.length;
        o.write((size >> 8) & 0xFF);
        o.write(size & 0xFF);
        o.write(new byte[]{0x00, 0x01, 0x00, (byte) 0x88, 0x00, 0x06}, 0, 6);
        o.write(digest.length >> 24);
        o.write(digest.length >> 16);
        o.write(digest.length >> 8);
        o.write(digest.length);
        o.write(digest, 0, digest.length);
        return o.toByteArray();
    }

    // ---------------- CMS 签名 ----------------

    private static byte[] buildCms(byte[] econtent, List<String> chainPems, PrivateKey key, String signTime) throws Exception {
        byte[] shaDigest = java.security.MessageDigest.getInstance("SHA-256").digest(econtent);

        // ★★ ATTRIBUTE ::= SEQUENCE { type OID, values SET OF AttributeValue }
        // 每个属性的值必须用 SET 包裹！原版网页签名工具产出的包就是这么编的。
        // 之前这里写成了 SEQUENCE{ OID, 裸值 }，少了 SET 层，整体 DER 非法：
        //   openssl cms -verify 直接报 "wrong tag / nested asn1 error"，
        //   手表能过安装时的容器校验（所以图标会出现），
        //   但运行/启动阶段要做完整性校验时 CMS 解析失败 ->
        //   系统判定坏包并静默回滚，表现就是「图标出现几秒后又消失」。
        // 顺序也与原包一致：contentType -> signingTime -> messageDigest。
        byte[][] attrs = new byte[][]{
                Der.seq(Der.oid(OID_CONTENT_TYPE), Der.set(Der.oid(OID_DATA))),
                Der.seq(Der.oid(OID_SIGNING_TIME), Der.set(Der.utf8Time(signTime))),
                Der.seq(Der.oid(OID_MESSAGE_DIGEST), Der.set(Der.octet(shaDigest)))
        };
        byte[] signedAttrsSet = Der.set(attrs);       // 签名输入（0x31 SET）
        byte[] signedAttrsField = Der.ctx(0xA0, Der.cat(attrs)); // 存进 SignerInfo 的 [0]
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(key);
        sig.update(signedAttrsSet);
        byte[] signatureValue = sig.sign();

        // 叶子证书 = chainPems[0]
        byte[] leafDer = P7b.fromPem(chainPems.get(0));
        Der.Node leaf = Der.first(leafDer);
        List<Der.Node> leafKids = Der.kids(leafDer, leaf);
        // Certificate ::= SEQ { tbsCertificate, sigAlg, sigValue }
        Der.Node tbs = leafKids.get(0);
        List<Der.Node> tbsKids = Der.kids(leafDer, tbs);
        int idx = 0;
        if (tbsKids.get(0).tag == 0xA0) idx = 1;   // version [0]
        // tbs（有 version 时）：[0]version, INTEGER serial, sigAlg, issuer, validity, subject ...
        byte[] serialDer = tbsKids.get(idx).full();
        byte[] issuerDer = tbsKids.get(idx + 2).full();

        byte[] issuerAndSerial = Der.seq(issuerDer, serialDer);

        byte[] digestAlg = Der.seq(Der.oid(OID_SHA256), Der.nullValue());
        // ★ 原包 signerInfo 的 signatureAlgorithm 是「OID 后面不加 NULL」，
        //   只有 digestAlgorithms 那处的 sha256 才带 NULL。照抄原包。
        byte[] sigAlg = Der.seq(Der.oid(OID_ECDSA_SHA256));

        byte[] signerInfo = Der.seq(
                Der.integer(1),
                issuerAndSerial,
                digestAlg,
                signedAttrsField,
                sigAlg,
                Der.octet(signatureValue)
        );

        List<byte[]> certList = new ArrayList<byte[]>();
        for (String pem : chainPems) certList.add(P7b.fromPem(pem));

        byte[] encap = Der.seq(
                Der.oid(OID_DATA),
                Der.ctx(0xA0, Der.octet(econtent))
        );

        byte[] digestAlgs = Der.set(Der.seq(Der.oid(OID_SHA256), Der.nullValue()));
        byte[] certSet = Der.ctx(0xA0, Der.cat(certList.toArray(new byte[0][])));

        byte[] signedData = Der.seq(
                Der.integer(1),
                digestAlgs,
                encap,
                certSet,
                Der.set(signerInfo)
        );

        return Der.seq(Der.oid(OID_SIGNED_DATA), Der.ctx(0xA0, signedData));
    }

    /** 把 "YYYYMMDDHHMMSSZ" 转成 UTCTime 的 "YYMMDDHHMMSSZ" */
    private static String cmsTime(String s) {
        return s.length() == 15 ? s.substring(2) : s;
    }

    private static String utcStamp(long millis) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    /** 按 subject/issuer 把证书链排成 叶子 -> 中间 -> 根 */
    private static List<String> orderChain(List<String> pems) throws Exception {
        int n = pems.size();
        String[] subj = new String[n];
        String[] iss = new String[n];
        for (int i = 0; i < n; i++) {
            byte[] d = P7b.fromPem(pems.get(i));
            Der.Node ci = Der.first(d);
            Der.Node tbs = Der.kids(d, ci).get(0);
            List<Der.Node> k = Der.kids(d, tbs);
            int idx = 0;
            if (k.get(0).tag == 0xA0) idx = 1;
            // tbs: [version] serial signature issuer validity subject ...
            iss[i] = hashOf(d, k.get(idx + 2));
            subj[i] = hashOf(d, k.get(idx + 4));
        }
        int leaf = -1;
        for (int i = 0; i < n; i++) {
            boolean used = false;
            for (int j = 0; j < n; j++) {
                if (i != j && iss[j].equals(subj[i])) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                leaf = i;
                break;
            }
        }
        List<String> out = new ArrayList<String>();
        if (leaf < 0) return pems;
        int cur = leaf;
        int guard = 0;
        while (cur >= 0 && guard++ < 8) {
            out.add(pems.get(cur));
            if (subj[cur].equals(iss[cur])) break;
            int nxt = -1;
            for (int j = 0; j < n; j++) {
                if (j != cur && subj[j].equals(iss[cur])) {
                    nxt = j;
                    break;
                }
            }
            cur = nxt;
        }
        return out;
    }

    private static String hashOf(byte[] d, Der.Node n) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return hex(md.digest(n.full()));
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    // ---------------- 主流程 ----------------

    /**
     * 按网页签名工具的逻辑收集文件：
     *   .app  -> 里面找 .hap -> 读该 hap 的全部 zip 条目
     *   .hap  -> 直接读全部 zip 条目（跳过 signed.bin / META-INF）
     */
    public static LinkedHashMap<String, byte[]> collectFromApp(File src) throws Exception {
        LinkedHashMap<String, byte[]> top = new LinkedHashMap<String, byte[]>();
        byte[] innerHap = null;
        ZipFile zf = new ZipFile(src);
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.endsWith(".hap")) {
                    innerHap = readAll(zf.getInputStream(e));
                } else {
                    top.put(name, readAll(zf.getInputStream(e)));
                }
            }
        } finally {
            zf.close();
        }

        if (innerHap != null) {
            LinkedHashMap<String, byte[]> inner = readZipEntries(innerHap);
            if (inner.containsKey("config.json")) return inner;
        }
        if (top.containsKey("config.json")) return top;
        return new LinkedHashMap<String, byte[]>();
    }

// 直接解码一段 zip 字节的所有文件条目
    public static LinkedHashMap<String, byte[]> readZipEntries(byte[] zipBytes) throws Exception {
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes));
        try {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.equals("signed.bin") || n.startsWith("META-INF/")) continue;
                // 注意：不能调用会 close 的 readAll，否则 ZipInputStream 会被关掉
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[65536];
                int len;
                while ((len = zi.read(b)) > 0) bo.write(b, 0, len);
                bo.close();
                out.put(n, bo.toByteArray());
            }
        } finally {
            zi.close();
        }
        return out;
    }

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    /**
     * 用指定 Profile 重签 HAP。
     *
     * @param srcHap   源 HAP
     * @param dstHap   输出 HAP
     * @param profile  AGC 调试/发布 Profile（p7b）
     * @param keyPem   PKCS#8 EC 私钥 PEM（与 Profile 里的开发证书成对）
     * @param caPem    华为根 + 中间 CA（工具内置）
     */
    public static Result sign(File srcHap, File dstHap, byte[] profile, String keyPem, String caPem) throws Exception {
        Result r = new Result();
        P7b p = P7b.parse(profile);

        // ★★ 硬校验 1：只接受「调试 Profile」。
        // 手表只认 debug profile（带 device-ids 白名单），
        // 发布 profile（type=release）没有 debug-info.device-ids，
        // 签出来的包手表会直接拒装，且报错很含糊，所以在这里提前拦住。
        String ptype = p.type == null ? "" : p.type.trim();
        if (!"debug".equalsIgnoreCase(ptype)) {
            throw new Exception("只接受调试 Profile（type=debug），"
                    + "当前 type=" + (ptype.length() == 0 ? "空" : ptype)
                    + "，请重新申请调试 Profile");
        }
        // ★★ 硬校验 2：调试 Profile 必须绑定设备白名单，否则等于没绑定
        if (p.deviceIds == null || p.deviceIds.isEmpty()) {
            throw new Exception("该调试 Profile 没有绑定任何设备（debug-info.device-ids 为空），"
                    + "手表会拒绝安装，请为本机手表重新申请");
        }

        String bundle = p.bundleName;
        r.bundle = bundle;

        String signStamp = utcStamp(p.notBefore > 100000000000L ? p.notBefore : System.currentTimeMillis());

        // 完全照网页签名工具的做法：
        //   .app -> 取出内层 hap -> 读出全部 zip 条目 -> 改 config.json(bundleName + version.code=0)
        //   -> 重新拼 unsigned bin -> 加 proBlock/p7b -> CMS 签名 -> 输出只含 signed.bin 的 hap
        LinkedHashMap<String, byte[]> files = collectFromApp(srcHap);
        if (files.isEmpty()) throw new Exception("不是有效的 .app / .hap（没找到 config.json）");

        byte[] cfgBytes = files.get("config.json");
        if (cfgBytes == null) throw new Exception("hap 中未找到 config.json");
        String cfgText = new String(cfgBytes, "UTF-8");
        String newCfg = replaceJsonString(cfgText, "bundleName", bundle);
        newCfg = replaceVersionCode(newCfg, 0);
        if (!newCfg.equals(cfgText)) files.put("config.json", newCfg.getBytes("UTF-8"));

        // ★★ pack.info 绝对不能动！
        // 实测基准可安装包（2048新版）的 pack.info 里 bundleName 仍是原始包名，
        // 网页签名工具原样保留整段字节（连缩进换行都不改）。
        // 之前这里改了 bundleName，手表端校验 pack.info 对不上就拒装。
        // 保留原逻辑注释掉，切勿恢复。
        // byte[] packBytes = files.get("pack.info");
        // if (packBytes != null) { ... replaceJsonString(packText, "bundleName", bundle) ... }

        r.fileCount = files.size();

        byte[] ub = binBuild(files, bundle);
        int offPro = ub.length + 8 + 8;
        int offSign = offPro + profile.length;

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(ub);
        blob.write(blockHead(0x02, 0x00, profile.length, offPro));
        blob.write(blockHead(0x00, 0x00, 0, offSign));
        blob.write(profile);
        byte[] head = blob.toByteArray();
        if (head.length != offSign) throw new Exception("块偏移计算错误");

        byte[] digest = slicedSha256(head, 4096);
        byte[] econtent = byteContent(digest);

        PrivateKey key = loadKey(keyPem);
        List<String> chain = new ArrayList<String>();
        chain.add(p.leafCertPem);
        for (String pem : pemCerts(caPem)) chain.add(pem);
        chain = orderChain(chain);

        byte[] cms = buildCms(econtent, chain, key, cmsTime(signStamp));

        int total = offSign + cms.length;
        int sub = total - ub.length + 32;

        ByteArrayOutputStream signedBin = new ByteArrayOutputStream();
        signedBin.write(head);
        signedBin.write(cms);
        signedBin.write(signHead(sub));
        byte[] finalBin = signedBin.toByteArray();

        // 输出 HAP：只有 signed.bin，STORED
        // ★★ 必须手写 zip，不能用 ZipOutputStream：
        //   Java 的 ZipOutputStream 会写 general purpose flags = 0x0808（bit3 数据描述符）
        //   并且 CDH 的 "version made by" 是 0x0014(Unix)，而华为手表认的基准包是
        //   flags=0x0000、version made by=0x0000(DOS)。容器头不一致时手表安装阶段
        //   能过（图标会出现），但随后完整性校验失败会被静默回滚卸载。
        byte[] hapZip = buildHapZip("signed.bin", finalBin);
        // 输出目录可能还不存在（公共目录未授权时会落到应用专属目录），先确保父目录在
        File parentDir = dstHap.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();
        FileOutputStream fo = new FileOutputStream(dstHap);
        fo.write(hapZip);
        fo.flush();
        fo.close();

        r.out = dstHap;
        r.message = "bundle=" + bundle + " 文件 " + r.fileCount + " 个，签名块 " + finalBin.length + " 字节";
        return r;
    }

    /** config.json 里 app.version.code 归零（网页工具同样处理） */
    private static String replaceVersionCode(String json, int code) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"code\"\\s*:\\s*\\d+")
                .matcher(json);
        if (!m.find()) return json;
        return json.substring(0, m.start()) + "\"code\":" + code + json.substring(m.end());
    }

    /** 只替换 JSON 里第一个 "key":"value" 的 value，保持其它字节不动 */
    private static String replaceJsonString(String json, String key, String value) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"")
                .matcher(json);
        if (!m.find()) return json;
        String hit = m.group();
        int c = hit.indexOf(':');
        String replaced = hit.substring(0, c + 1) + "\"" + value + "\"";
        return json.substring(0, m.start()) + replaced + json.substring(m.end());
    }

    private static List<String> pemCerts(String pemText) {
        List<String> out = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
                .matcher(pemText);
        while (m.find()) out.add(m.group());
        return out;
    }
    /**
     * 手写 zip（单条目 STORED），逐字段对齐华为手表认的基准包：
     *   LFH/CDH extract_version = 10、create_system = 0(DOS)、flags = 0、extra = 0、comment = 0。
     * Java 的 ZipOutputStream 会强写 extract_version=20 / create_system=3(Unix) / flags=0x0808，
     * 手表对此敏感，所以这里完全自己拼字节。
     */
    private static byte[] buildHapZip(String name, byte[] data) throws Exception {
        byte[] nb = name.getBytes("UTF-8");
        CRC32 crc = new CRC32();
        crc.update(data);
        long crcVal = crc.getValue();
        int sz = data.length;

        // 2026-11-21 12:35:14（与原版签名工具一致即可，不影响校验）
        int dt = ((2026 - 1980) << 9) | (11 << 5) | 21;
        int tm = (12 << 11) | (35 << 5) | (14 / 2);

        ByteArrayOutputStream lfh = new ByteArrayOutputStream();
        writeLE32(lfh, 0x04034B50L);
        writeLE16(lfh, 10);            // version needed
        writeLE16(lfh, 0);             // flags = 0
        writeLE16(lfh, 0);             // method = STORED
        writeLE16(lfh, tm);
        writeLE16(lfh, dt);
        writeLE32(lfh, crcVal);
        writeLE32(lfh, sz);
        writeLE32(lfh, sz);
        writeLE16(lfh, nb.length);
        writeLE16(lfh, 0);             // extra len
        lfh.write(nb, 0, nb.length);
        lfh.write(data, 0, data.length);

        ByteArrayOutputStream cdh = new ByteArrayOutputStream();
        writeLE32(cdh, 0x02014B50L);
        writeLE16(cdh, 0);             // version made by = DOS
        writeLE16(cdh, 10);            // version needed
        writeLE16(cdh, 0);             // flags
        writeLE16(cdh, 0);             // method
        writeLE16(cdh, tm);
        writeLE16(cdh, dt);
        writeLE32(cdh, crcVal);
        writeLE32(cdh, sz);
        writeLE32(cdh, sz);
        writeLE16(cdh, nb.length);
        writeLE16(cdh, 0);             // extra
        writeLE16(cdh, 0);             // comment
        writeLE16(cdh, 0);             // disk
        writeLE16(cdh, 0);             // internal attr
        writeLE32(cdh, 0L);            // external attr
        writeLE32(cdh, 0L);            // local header offset
        cdh.write(nb, 0, nb.length);

        byte[] body = lfh.toByteArray();
        byte[] cdir = cdh.toByteArray();

        ByteArrayOutputStream eocd = new ByteArrayOutputStream();
        writeLE32(eocd, 0x06054B50L);
        writeLE16(eocd, 0);
        writeLE16(eocd, 0);
        writeLE16(eocd, 1);            // entries on disk
        writeLE16(eocd, 1);            // total entries
        writeLE32(eocd, cdir.length);
        writeLE32(eocd, body.length);
        writeLE16(eocd, 0);            // comment len

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(body, 0, body.length);
        all.write(cdir, 0, cdir.length);
        all.write(eocd.toByteArray(), 0, eocd.size());
        return all.toByteArray();
    }

    private static void writeLE16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void writeLE32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF));
        o.write((int) ((v >> 24) & 0xFF));
    }

    public static PrivateKey loadKey(String pem) throws Exception {
        byte[] der = P7b.fromPem(pem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        } catch (Exception e) {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        }
    }

    // ---------------- 公钥比对（校验证书是否与内置私钥成对） ----------------

    /** PEM 文本里第一张证书的 DER 字节；没有返回 null */
    public static byte[] pemToDer(String pemText) {
        if (pemText == null) return null;
        int a = pemText.indexOf("-----BEGIN CERTIFICATE-----");
        if (a < 0) return null;
        int b = pemText.indexOf("-----END CERTIFICATE-----", a);
        if (b < 0) return null;
        String b64 = pemText.substring(a + "-----BEGIN CERTIFICATE-----".length(), b).replaceAll("\\s", "");
        try {
            return P7b.fromPem("-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 AGC 下载的 .cer 证书链里取出【叶子证书】的 DER。
     *
     * ⚠️ AGC 的 certDownloadUrl 给的是整条链：
     *      [0] Huawei CBG Root CA G2
     *      [1] Huawei CBG Developer Relations CA G2
     *      [2] 开发者调试证书（叶子，公钥就是我们的 wl_key.pem）
     * 签名要用的、要验公钥的，都是最后这张叶子证书。
     */
    public static byte[] leafCertDer(String pemText) {
        if (pemText == null) return null;
        java.util.List<String> all = pemCerts(pemText);
        if (all.isEmpty()) return null;
        String leaf = all.get(all.size() - 1);
        try {
            return P7b.fromPem(leaf);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从证书 DER 里取出 EC 公钥点（04||X||Y，65 字节）。
     *
     * 不能硬编码 BIT STRING 长度（不同 CA 签出的证书长度不同）：
     *   Certificate = SEQ{ tbsCertificate, sigAlg, sigValue }
     *   tbsCertificate = SEQ{ [0]version?, serial, sigAlg, issuer, validity, subject, spki, ... }
     *   spki = SEQ{ algId, BIT STRING(00 || 04 || X || Y) }
     * 所以按 TLV 逐层走，走到 tbs 里第 7 个元素就是 spki。
     */
    public static byte[] certPublicPoint(byte[] certDer) {
        if (certDer == null) return null;
        try {
            // 外层 SEQ
            int[] r = readTlv(certDer, 0);
            if (r == null || r[0] != 0x30) return null;      // tag, start, hdrLen, len
            int tbsStart = r[1];
            int[] tbs = readTlv(certDer, tbsStart);
            if (tbs == null || tbs[0] != 0x30) return null;
            int p = tbs[1];
            int end = tbs[1] + tbs[3];
            int idx = 0;
            while (p < end) {
                int[] t = readTlv(certDer, p);
                if (t == null) return null;
                idx++;
                // tbs 顺序: [0]version(可选), serial, sigAlg, issuer, validity, subject, spki
                int field = idx;
                if (idx == 1 && t[0] == 0xA0) {
                    // 有 version，整体后移一位
                    p = t[1] + t[3];
                    idx = 0;
                    continue;
                }
                if (field == 6) {
                    // t[0] 应为 0x30 (SubjectPublicKeyInfo)
                    if (t[0] != 0x30) return null;
                    int q = t[1];
                    int spkiEnd = t[1] + t[3];
                    // spki 内第一个是 AlgorithmIdentifier，第二个是 BIT STRING
                    int[] alg = readTlv(certDer, q);
                    if (alg == null) return null;
                    q = alg[1] + alg[3];
                    int[] bs = readTlv(certDer, q);
                    if (bs == null || bs[0] != 0x03) return null;
                    int body = bs[1];
                    int bodyLen = bs[3];
                    // BIT STRING: 第 1 字节是 unused bits，之后是 04||X||Y
                    if (bodyLen < 66) return null;
                    if (certDer[body] != 0x00) return null;
                    if ((certDer[body + 1] & 0xFF) != 0x04) return null;
                    byte[] pt = new byte[65];
                    System.arraycopy(certDer, body + 1, pt, 0, 65);
                    return pt;
                }
                p = t[1] + t[3];
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 读一个 DER TLV。
     *
     * @return int[]{tag, contentStart, headerLen, contentLen}；失败返回 null
     */
    private static int[] readTlv(byte[] d, int off) {
        try {
            if (off + 2 > d.length) return null;
            int tag = d[off] & 0xFF;
            int p = off + 1;
            int l0 = d[p] & 0xFF;
            int len;
            int hdr;
            if (l0 < 0x80) {
                len = l0;
                hdr = 2;
            } else {
                int n = l0 & 0x7F;
                if (n == 0 || n > 4) return null;
                if (p + 1 + n > d.length) return null;
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (d[p + 1 + i] & 0xFF);
                hdr = 2 + n;
            }
            int cs = off + hdr;
            if (cs + len > d.length) return null;
            return new int[]{tag, cs, hdr, len};
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从 CSR（PEM）里取出 EC 公钥点 04||X||Y（65 字节） */
    public static byte[] csrPublicPoint(String csrPem) {
        try {
            byte[] der = P7b.fromPem(csrPem);
            // CSR = SEQ{ CertificationRequestInfo, sigAlg, sigValue }
            int[] outer = readTlv(der, 0);
            if (outer == null || outer[0] != 0x30) return null;
            int[] cri = readTlv(der, outer[1]);
            if (cri == null || cri[0] != 0x30) return null;
            // CRI = SEQ{ [0]version, subject, spki, [0]attributes }
            int p = cri[1];
            int end = cri[1] + cri[3];
            while (p < end) {
                int[] t = readTlv(der, p);
                if (t == null) return null;
                if (t[0] == 0xA0) {   // version 或 attributes，跳过
                    p = t[1] + t[3];
                    continue;
                }
                if (t[0] == 0x30) {   // subject=SEQ 或 spki=SEQ；第二个 SEQ 才是 spki
                    int[] inner1 = readTlv(der, t[1]);
                    if (inner1 != null && inner1[0] == 0x30) {
                        // 这是 spki：内部是 algId + BIT STRING
                        int q = inner1[1] + inner1[3];
                        int[] bs = readTlv(der, q);
                        if (bs != null && bs[0] == 0x03 && bs[3] >= 66
                                && der[bs[1]] == 0x00 && (der[bs[1] + 1] & 0xFF) == 0x04) {
                            byte[] pt = new byte[65];
                            System.arraycopy(der, bs[1] + 1, pt, 0, 65);
                            return pt;
                        }
                    }
                }
                p = t[1] + t[3];
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}