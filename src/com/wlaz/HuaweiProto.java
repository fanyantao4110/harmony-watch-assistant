package com.wlaz;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 华为手表 FE86 私有协议：0x5A 帧 + TLV，算法照腕连 HuaweiProtocol */
public class HuaweiProto {

    public static final byte MAGIC = 90;

    public static class Tlv {
        public final Map<Integer, byte[]> map = new LinkedHashMap<Integer, byte[]>();

        public Tlv put(int tag, byte[] v) {
            if (v != null) map.put(tag & 0xff, v.clone());
            return this;
        }

        public Tlv put(int tag, int v) {
            return put(tag, new byte[]{(byte) v});
        }

        public Tlv putShort(int tag, int v) {
            return put(tag, new byte[]{(byte) (v >> 8), (byte) v});
        }

        public Tlv putString(int tag, String s) {
            return put(tag, s == null ? new byte[0] : s.getBytes(Charset.forName("UTF-8")));
        }

        public byte[] first(int tag) {
            return map.get(tag & 0xff);
        }

        public int firstByte(int tag, int def) {
            byte[] b = first(tag);
            return b == null || b.length < 1 ? def : (b[0] & 0xff);
        }

        public int firstShort(int tag, int def) {
            byte[] b = first(tag);
            return b == null || b.length < 2 ? def : (((b[0] & 0xff) << 8) | (b[1] & 0xff));
        }

        public String firstString(int tag) {
            byte[] b = first(tag);
            return b == null ? null : new String(b, Charset.forName("UTF-8"));
        }

        public byte[] serialize() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            for (Map.Entry<Integer, byte[]> e : map.entrySet()) {
                o.write(e.getKey() & 0xff);
                byte[] len = encodeVarInt(e.getValue().length);
                o.write(len, 0, len.length);
                o.write(e.getValue(), 0, e.getValue().length);
            }
            return o.toByteArray();
        }

        public String summary() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<Integer, byte[]> e : map.entrySet()) {
                if (b.length() > 0) b.append(' ');
                b.append(String.format(Locale.US, "0x%02X[%d]=", e.getKey(), e.getValue().length));
                int n = Math.min(e.getValue().length, 4);
                for (int i = 0; i < n; i++) b.append(String.format(Locale.US, "%02X", e.getValue()[i]));
                if (e.getValue().length > n) b.append("..");
            }
            return b.length() == 0 ? "empty" : b.toString();
        }
    }

    public static class Packet {
        public int flag;
        public int seq;
        public int cmd;
        public int sub;
        public Tlv tlv;
        public byte[] raw;
    }

    public static Tlv tlv() {
        return new Tlv();
    }

    public static Tlv parseTlv(byte[] b) {
        Tlv t = new Tlv();
        if (b == null) return t;
        int i = 0;
        while (i < b.length) {
            int tag = b[i++] & 0xff;
            int[] vr = decodeVarInt(b, i);
            int len = vr[0];
            int used = vr[1];
            if (len < 0) break;
            i += used;
            if (len > b.length - i) break;
            t.map.put(tag, Arrays.copyOfRange(b, i, i + len));
            i += len;
        }
        return t;
    }

    public static byte[] frame(int cmd, int sub, Tlv t) {
        return frameRaw(cmd, sub, t.serialize());
    }

    private static byte[] frameRaw(int cmd, int sub, byte[] payload) {
        int n = payload.length;
        ByteBuffer b = ByteBuffer.allocate(n + 8);
        b.put(MAGIC);
        b.putShort((short) (n + 3));
        b.put((byte) 0);
        b.put((byte) cmd);
        b.put((byte) sub);
        b.put(payload);
        byte[] head = Arrays.copyOf(b.array(), b.position());
        byte[] out = new byte[head.length + 2];
        System.arraycopy(head, 0, out, 0, head.length);
        int c = crc16(head);
        out[head.length] = (byte) (c >> 8);
        out[head.length + 1] = (byte) c;
        return out;
    }

    /** 大包按 MTU 切帧 */
    public static List<byte[]> frames(int cmd, int sub, byte[] payload, int mtu) {
        int chunk = mtu > 8 ? mtu : 244;
        int room = chunk - 7;
        if (room <= 0) room = 237;
        int count = (int) Math.ceil(((double) (payload.length + 2)) / room);
        List<byte[]> out = new ArrayList<byte[]>();
        if (count <= 1) {
            out.add(frameRaw(cmd, sub, payload));
            return out;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        for (int idx = 0; idx < count; idx++) {
            int size = Math.min(chunk, buf.remaining() + 7);
            ByteBuffer one = ByteBuffer.allocate(size);
            one.put(MAGIC);
            one.putShort((short) (size - 5));
            one.put((byte) (idx == count - 1 ? 3 : 1));
            one.put((byte) idx);
            int take = size - 7;
            if (idx == 0) {
                one.put((byte) cmd);
                one.put((byte) sub);
                take = size - 9;
            }
            byte[] part = new byte[take];
            buf.get(part);
            one.put(part);
            byte[] head = Arrays.copyOf(one.array(), one.position());
            byte[] all = new byte[head.length + 2];
            System.arraycopy(head, 0, all, 0, head.length);
            int c = crc16(head);
            all[head.length] = (byte) (c >> 8);
            all[head.length + 1] = (byte) c;
            out.add(all);
        }
        return out;
    }

    public static Packet parse(byte[] data) {
        if (data == null || data.length < 7 || data[0] != MAGIC) return null;
        ByteBuffer b = ByteBuffer.wrap(data);
        b.get();
        int len = b.getShort() & 0xffff;
        if (len < 3 || len + 5 > data.length) return null;
        int start = b.position();
        int flag = b.get() & 0xff;
        int seq = 0;
        if (flag == 1 || flag == 2 || flag == 3) seq = b.get() & 0xff;
        int rest = len - (b.position() - start);
        byte[] payload = new byte[rest];
        b.get(payload);
        int crcPos = b.position();
        short crc = b.getShort();
        byte[] head = Arrays.copyOf(data, crcPos);
        if ((short) crc16(head) != crc) return null;
        if (payload.length < 2) return null;
        Packet p = new Packet();
        p.flag = flag;
        p.seq = seq;
        p.cmd = payload[0] & 0xff;
        p.sub = payload[1] & 0xff;
        p.tlv = parseTlv(Arrays.copyOfRange(payload, 2, payload.length));
        p.raw = payload;
        return p;
    }

    static int crc16(byte[] data) {
        int crc = 0;
        for (byte d : data) {
            crc ^= (d & 0xff) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xffff;
            }
        }
        return crc & 0xffff;
    }

    static byte[] encodeVarInt(int v) {
        int size = 1;
        for (int x = v >>> 7; x != 0; x >>>= 7) size++;
        byte[] out = new byte[size];
        out[size - 1] = (byte) (v & 0x7f);
        for (int i = size - 2; i >= 0; i--) {
            v >>>= 7;
            out[i] = (byte) ((v & 0x7f) | 0x80);
        }
        return out;
    }

    static int[] decodeVarInt(byte[] b, int off) {
        int value = 0;
        int used = 0;
        while (off + used < b.length) {
            int x = b[off + used] & 0xff;
            value += x & 0x7f;
            used++;
            if ((x & 0x80) == 0) return new int[]{value, used};
            value <<= 7;
        }
        return new int[]{-1, Math.max(used, 1)};
    }
}