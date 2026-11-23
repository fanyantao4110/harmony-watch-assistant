package com.wlaz;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小 DER 编解码。只做 P7B 解析与 CMS 签名需要的那部分：
 * SEQUENCE(0x30) / SET(0x31) / INTEGER(0x02) / OCTET STRING(0x04) /
 * OID(0x06) / UTCTime(0x17) / NULL(0x05) / 上下文标签(0xA0/0xA1/0x80 …)
 */
public class Der {

    public static final int SEQ = 0x30;
    public static final int SET = 0x31;
    public static final int INT = 0x02;
    public static final int OCTET = 0x04;
    public static final int OID = 0x06;
    public static final int UTC = 0x17;
    public static final int NULL = 0x05;
    public static final int CTX0 = 0xA0;   // [0] 构造
    public static final int CTX1 = 0xA1;   // [1] 构造

    // ---------------- 编码 ----------------

    public static byte[] len(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        if (n <= 0xFF) return new byte[]{(byte) 0x81, (byte) n};
        if (n <= 0xFFFF) return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        return new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }

    public static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        byte[] l = len(content.length);
        o.write(l, 0, l.length);
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    public static byte[] seq(byte[]... parts) {
        return tlv(SEQ, cat(parts));
    }

    public static byte[] set(byte[]... parts) {
        return tlv(SET, cat(parts));
    }

    public static byte[] ctx(int tag, byte[] content) {
        return tlv(tag, content);
    }

    public static byte[] integer(long v) {
        byte[] b = java.math.BigInteger.valueOf(v).toByteArray();
        return tlv(INT, b);
    }

    public static byte[] octet(byte[] d) {
        return tlv(OCTET, d);
    }

    public static byte[] utf8Time(String yyMMddHHmmssZ) {
        return tlv(UTC, yyMMddHHmmssZ.getBytes(java.nio.charset.Charset.forName("US-ASCII")));
    }

    public static byte[] nullValue() {
        return new byte[]{NULL, 0};
    }

    public static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) if (p != null) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** OID 点串 -> DER（支持 2.x.y 与任意长度分量） */
    public static byte[] oid(String dotted) {
        String[] s = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        long first = Long.parseLong(s[0]) * 40 + Long.parseLong(s[1]);
        writeBase128(body, first);
        for (int i = 2; i < s.length; i++) writeBase128(body, Long.parseLong(s[i]));
        return tlv(OID, body.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream o, long v) {
        if (v < 0x80) {
            o.write((int) v);
            return;
        }
        int groups = 0;
        long t = v;
        while (t > 0) {
            groups++;
            t >>= 7;
        }
        for (int i = groups - 1; i >= 0; i--) {
            int b = (int) ((v >> (7 * i)) & 0x7F);
            if (i != 0) b |= 0x80;
            o.write(b);
        }
    }

    // ---------------- 解码 ----------------

    /** 一个 TLV 节点：tag、value（不含头）、以及整段（含头） */
    public static class Node {
        public int tag;
        public int off;        // 头起点（相对父节点 value 的起点 + 父偏移，已换算成绝对）
        public int headLen;    // 头长度
        public int valueOff;   // value 绝对起点
        public int valueLen;
        public byte[] raw;     // 整段原始数据

        public byte[] value() {
            byte[] v = new byte[valueLen];
            System.arraycopy(raw, valueOff, v, 0, valueLen);
            return v;
        }

        public byte[] full() {
            byte[] v = new byte[headLen + valueLen];
            System.arraycopy(raw, off, v, 0, v.length);
            return v;
        }

        public boolean isCtx() {
            return (tag & 0xC0) == 0x80;
        }
    }

    /**
     * 解析一段 DER 的顶层节点。
     * base 为 raw 在真实数据中的偏移（一般传 0，raw 就是完整数据）。
     */
    public static List<Node> children(byte[] raw, int from, int to, int base) {
        ArrayList<Node> out = new ArrayList<Node>();
        int p = from;
        while (p + 2 <= to) {
            Node n = new Node();
            n.tag = raw[p] & 0xFF;
            n.off = p;
            n.raw = raw;
            int lp = p + 1;
            if (lp >= to) break;
            int l0 = raw[lp] & 0xFF;
            int headLen;
            int vlen;
            if (l0 < 0x80) {
                headLen = 2;
                vlen = l0;
            } else {
                int cnt = l0 & 0x7F;
                if (cnt <= 0 || cnt > 4) break;
                headLen = 2 + cnt;
                long v = 0;
                for (int i = 0; i < cnt; i++) v = (v << 8) | (raw[lp + 1 + i] & 0xFF);
                vlen = (int) v;
            }
            n.headLen = headLen;
            n.valueOff = p + headLen;
            n.valueLen = vlen;
            int end = n.valueOff + vlen;
            if (end > to) break;
            out.add(n);
            p = end;
        }
        return out;
    }

    public static List<Node> top(byte[] der) {
        return children(der, 0, der.length, 0);
    }

    public static Node first(byte[] der) {
        List<Node> l = top(der);
        return l.isEmpty() ? null : l.get(0);
    }

    public static List<Node> kids(byte[] raw, Node parent) {
        return children(raw, parent.valueOff, parent.valueOff + parent.valueLen, 0);
    }

    /** 取 INTEGER 的 long 值 */
    public static long toLong(byte[] raw, Node n) {
        long v = 0;
        for (int i = 0; i < n.valueLen; i++) v = (v << 8) | (raw[n.valueOff + i] & 0xFF);
        return v;
    }

    public static String toAscii(byte[] raw, Node n) {
        return new String(raw, n.valueOff, n.valueLen, java.nio.charset.Charset.forName("US-ASCII"));
    }
}