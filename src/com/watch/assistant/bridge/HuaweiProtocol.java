package com.watch.assistant.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/* loaded from: classes4.dex */
final class HuaweiProtocol {
    private static final byte MAGIC = 90;

    /* JADX INFO: Access modifiers changed from: private */
    public static int varIntSize(int i) {
        int i2 = 0;
        do {
            i2++;
            i >>>= 7;
        } while (i != 0);
        return i2;
    }

    private HuaweiProtocol() {
    }

    static byte[] frame(byte b, byte b2, Tlv tlv) {
        return frameRaw(b, b2, tlv.serialize());
    }

    static List<byte[]> frames(byte b, byte b2, byte[] bArr, int i) {
        int i2 = i > 8 ? i : 244;
        int i3 = i2 - 7;
        if (i3 <= 0) {
            ArrayList arrayList = new ArrayList();
            arrayList.add(frameRaw(b, b2, bArr));
            return arrayList;
        }
        int ceil = (int) Math.ceil((bArr.length + 2) / i3);
        if (ceil <= 1) {
            ArrayList arrayList2 = new ArrayList();
            arrayList2.add(frameRaw(b, b2, bArr));
            return arrayList2;
        }
        ArrayList arrayList3 = new ArrayList();
        ByteBuffer wrap = ByteBuffer.wrap(bArr);
        byte b3 = 1;
        int i4 = 0;
        byte b4 = 0;
        while (i4 < ceil) {
            int min = Math.min(i2, wrap.remaining() + 7);
            int i5 = min - 7;
            ByteBuffer allocate = ByteBuffer.allocate(min);
            allocate.put(MAGIC);
            allocate.putShort((short) (min - 5));
            if (i4 == ceil - 1) {
                b3 = 3;
            }
            allocate.put(b3);
            byte b5 = (byte) (b4 + 1);
            allocate.put(b4);
            if (b3 == 1) {
                allocate.put(b);
                allocate.put(b2);
                i5 = min - 9;
                b3 = 2;
            }
            byte[] bArr2 = new byte[i5];
            wrap.get(bArr2);
            allocate.put(bArr2);
            int position = allocate.position();
            byte[] bArr3 = new byte[position];
            allocate.position(0);
            allocate.get(bArr3);
            allocate.position(position);
            allocate.putShort((short) crc16(bArr3));
            arrayList3.add(allocate.array());
            i4++;
            b4 = b5;
        }
        return arrayList3;
    }

    static List<byte[]> transportChunks(List<byte[]> list, int i) {
        ArrayList arrayList = new ArrayList();
        if (i > 0) {
            for (byte[] bArr : list) {
                if (bArr != null && bArr.length != 0) {
                    int i2 = 0;
                    while (i2 < bArr.length) {
                        int i3 = i2 + i;
                        arrayList.add(Arrays.copyOfRange(bArr, i2, Math.min(bArr.length, i3)));
                        i2 = i3;
                    }
                }
            }
        }
        return arrayList;
    }

    private static byte[] frameRaw(byte b, byte b2, byte[] bArr) {
        int length = bArr.length;
        ByteBuffer allocate = ByteBuffer.allocate(length + 6);
        allocate.put(MAGIC);
        allocate.putShort((short) (length + 3));
        allocate.put((byte) 0);
        allocate.put(b);
        allocate.put(b2);
        allocate.put(bArr);
        byte[] array = allocate.array();
        ByteBuffer allocate2 = ByteBuffer.allocate(array.length + 2);
        allocate2.put(array);
        allocate2.putShort((short) crc16(array));
        return allocate2.array();
    }

    static Packet parse(byte[] bArr) {
        if (bArr != null && bArr.length >= 7 && bArr[0] == 90) {
            ByteBuffer wrap = ByteBuffer.wrap(bArr);
            wrap.get();
            int i = wrap.getShort() & 0xFFFF;
            if (i + 5 <= bArr.length && i >= 3) {
                int position = wrap.position();
                byte b = wrap.get();
                byte b2 = (b == 1 || b == 2 || b == 3) ? wrap.get() : (byte) 0;
                int position2 = i - (wrap.position() - position);
                byte[] bArr2 = new byte[position2];
                wrap.get(bArr2);
                short s = wrap.getShort();
                int position3 = wrap.position() - 2;
                boolean z = b == 1 || b == 2 || b == 3;
                if (((short) crc16(Arrays.copyOf(bArr, position3))) == s && ((z && b != 1) || position2 >= 2)) {
                    return new Packet(position2 >= 1 ? bArr2[0] : (byte) 0, position2 >= 2 ? bArr2[1] : (byte) 0, Tlv.parse(position2 >= 2 ? Arrays.copyOfRange(bArr2, 2, position2) : new byte[0]), bArr2, b, b2);
                }
            }
        }
        return null;
    }

    static Packet parsePayload(byte[] bArr) {
        if (bArr == null || bArr.length < 2) {
            return null;
        }
        return new Packet(bArr[0], bArr[1], Tlv.parse(Arrays.copyOfRange(bArr, 2, bArr.length)), bArr, (byte) 0, (byte) 0);
    }

    private static int crc16(byte[] bArr) {
        int i = 0;
        for (byte b : bArr) {
            i ^= (b & 0xFF) << 8;
            for (int i2 = 0; i2 < 8; i2++) {
                i = ((32768 & i) != 0 ? (i << 1) ^ 4129 : i << 1) & 65535;
            }
        }
        return i & 65535;
    }

    static final class Packet {
        final byte commandId;
        final byte[] payload;
        final byte serviceId;
        final byte sliceFlag;
        final byte sliceIndex;
        final Tlv tlv;

        Packet(byte b, byte b2, Tlv tlv, byte[] bArr, byte b3, byte b4) {
            this.serviceId = b;
            this.commandId = b2;
            this.tlv = tlv;
            this.payload = bArr;
            this.sliceFlag = b3;
            this.sliceIndex = b4;
        }
    }

    static final class Tlv {
        private final List<Entry> entries = new ArrayList();

        Tlv() {
        }

        Tlv put(int i, byte b) {
            return put(i, new byte[]{b});
        }

        Tlv put(int i, byte[] bArr) {
            if (bArr != null) {
                this.entries.add(new Entry((byte) i, (byte[]) bArr.clone()));
            }
            return this;
        }

        byte[] first(int i) {
            for (Entry entry : this.entries) {
                if ((entry.tag & 0xFF) == i) {
                    return (byte[]) entry.value.clone();
                }
            }
            return null;
        }

        List<byte[]> all(int i) {
            ArrayList arrayList = new ArrayList();
            for (Entry entry : this.entries) {
                if ((entry.tag & 0xFF) == i) {
                    arrayList.add((byte[]) entry.value.clone());
                }
            }
            return arrayList;
        }

        boolean contains(int i) {
            return first(i) != null;
        }

        int size() {
            return this.entries.size();
        }

        List<Integer> tags() {
            ArrayList arrayList = new ArrayList(this.entries.size());
            Iterator<Entry> it = this.entries.iterator();
            while (it.hasNext()) {
                arrayList.add(Integer.valueOf(it.next().tag & 0xFF));
            }
            return arrayList;
        }

        int countObjects(int i, int i2) {
            return objects(i, i2).size();
        }

        List<Tlv> objects(int i, int i2) {
            ArrayList arrayList = new ArrayList();
            byte[] first = first(i);
            if (first != null) {
                for (Entry entry : parse(first).entries) {
                    if ((entry.tag & 0xFF) == i2) {
                        arrayList.add(parse(entry.value));
                    }
                }
            }
            return arrayList;
        }

        int firstByte(int i, int i2) {
            byte[] first = first(i);
            return (first == null || first.length <= 0) ? i2 : first[0] & 0xFF;
        }

        int firstShort(int i, int i2) {
            byte[] first = first(i);
            return (first == null || first.length < 2) ? i2 : ByteBuffer.wrap(first).getShort() & 0xFFFF;
        }

        int firstInt(int i, int i2) {
            byte[] first = first(i);
            return (first == null || first.length < 4) ? i2 : ByteBuffer.wrap(first).getInt();
        }

        int firstAsInteger(int i, int i2) {
            byte[] first = first(i);
            if (first == null || first.length == 0) {
                return i2;
            }
            if (first.length == 1) {
                return first[0] & 0xFF;
            }
            if (first.length == 2) {
                return ByteBuffer.wrap(first).getShort() & 0xFFFF;
            }
            if (first.length >= 4) {
                return ByteBuffer.wrap(first).getInt();
            }
            int i3 = 0;
            for (byte b : first) {
                i3 = (i3 << 8) | (b & 0xFF);
            }
            return i3;
        }

        long firstUnsignedInt(int i, long j) {
            byte[] first = first(i);
            return (first == null || first.length < 4) ? j : ByteBuffer.wrap(first).getInt() & 4294967295L;
        }

        String firstString(int i) {
            byte[] first = first(i);
            if (first != null) {
                return new String(first, StandardCharsets.UTF_8);
            }
            return null;
        }

        byte[] serialize() {
            int i = 0;
            for (Entry entry : this.entries) {
                i += HuaweiProtocol.varIntSize(entry.value.length) + 1 + entry.value.length;
            }
            ByteBuffer allocate = ByteBuffer.allocate(i);
            for (Entry entry2 : this.entries) {
                allocate.put(entry2.tag);
                allocate.put(HuaweiProtocol.encodeVarInt(entry2.value.length));
                allocate.put(entry2.value);
            }
            return allocate.array();
        }

        String summary() {
            if (this.entries.isEmpty()) {
                return "empty";
            }
            StringBuilder sb = new StringBuilder();
            for (Entry entry : this.entries) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.US, "0x%02X", Integer.valueOf(entry.tag & 0xFF)));
                sb.append('[');
                sb.append(entry.value.length);
                sb.append("]=");
                int min = Math.min(entry.value.length, 8);
                for (int i = 0; i < min; i++) {
                    sb.append(String.format(Locale.US, "%02X", Integer.valueOf(entry.value[i] & 0xFF)));
                }
                if (entry.value.length > min) {
                    sb.append("...");
                }
            }
            return sb.toString();
        }

        static Tlv parse(byte[] bArr) {
            Tlv tlv = new Tlv();
            if (bArr != null) {
                int i = 0;
                while (i < bArr.length) {
                    int i2 = i + 1;
                    byte b = bArr[i];
                    VarInt decodeVarInt = HuaweiProtocol.decodeVarInt(bArr, i2);
                    int i3 = i2 + decodeVarInt.size;
                    if (decodeVarInt.value < 0 || decodeVarInt.value > bArr.length - i3) {
                        break;
                    }
                    byte[] copyOfRange = Arrays.copyOfRange(bArr, i3, decodeVarInt.value + i3);
                    int i4 = i3 + decodeVarInt.value;
                    tlv.entries.add(new Entry(b, copyOfRange));
                    i = i4;
                }
            }
            return tlv;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static byte[] encodeVarInt(int i) {
        int varIntSize = varIntSize(i);
        byte[] bArr = new byte[varIntSize];
        bArr[varIntSize - 1] = (byte) (i & 127);
        for (int i2 = varIntSize - 2; i2 >= 0; i2--) {
            i >>>= 7;
            bArr[i2] = (byte) ((i & 127) | 128);
        }
        return bArr;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static VarInt decodeVarInt(byte[] bArr, int i) {
        int i2 = 0;
        int i3 = 0;
        while (true) {
            int i4 = i + i2;
            if (i4 < bArr.length) {
                byte b = bArr[i4];
                int i5 = i3 + (b & 127);
                i2++;
                if ((b & -128) == 0) {
                    return new VarInt(i5, i2);
                }
                i3 = i5 << 7;
            } else {
                return new VarInt(-1, Math.max(i2, 1));
            }
        }
    }

    private static final class Entry {
        final byte tag;
        final byte[] value;

        Entry(byte b, byte[] bArr) {
            this.tag = b;
            this.value = bArr;
        }
    }

    private static final class VarInt {
        final int size;
        final int value;

        VarInt(int i, int i2) {
            this.value = i;
            this.size = i2;
        }
    }
}
