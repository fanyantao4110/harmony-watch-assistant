package com.watch.assistant.bridge;

import com.watch.assistant.bridge.HuaweiProtocol;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/* loaded from: classes4.dex */
final class HuaweiFileUploadProtocol {
    static final byte CMD_DEVICE_RESPONSE = 8;
    static final byte CMD_FILE_HASH = 3;
    static final byte CMD_FILE_INFO = 2;
    static final byte CMD_NEXT_CHUNK_PARAMS = 5;
    static final byte CMD_NEXT_CHUNK_SEND = 6;
    static final byte CMD_UPLOAD_CONSULT_ACK = 4;
    static final byte CMD_UPLOAD_RESULT = 7;
    static final byte FILE_TYPE_APP = 6;
    static final byte FILE_TYPE_WATCHFACE = 1;
    static final int RESULT_OK = 100000;
    static final byte SERVICE_FILE_UPLOAD = 40;

    private HuaweiFileUploadProtocol() {
    }

    static HuaweiProtocol.Tlv fileInfo(int i, String str, byte b, String str2, String str3, String str4, String str5) {
        HuaweiProtocol.Tlv put = new HuaweiProtocol.Tlv().put(1, str.getBytes(StandardCharsets.UTF_8)).put(2, intBytes(i)).put(3, b);
        if (b == 1 && str.contains("_")) {
            String[] split = str.split("_", 2);
            put.put(5, split[0].getBytes(StandardCharsets.UTF_8));
            put.put(6, split[1].getBytes(StandardCharsets.UTF_8));
        }
        if (str2 != null && str3 != null) {
            put.put(8, str2.getBytes(StandardCharsets.UTF_8));
            put.put(9, str3.getBytes(StandardCharsets.UTF_8));
            put.put(10, new byte[0]);
            if (str4 != null) {
                put.put(11, str4.getBytes(StandardCharsets.UTF_8));
            }
            if (str5 != null) {
                put.put(12, str5.getBytes(StandardCharsets.UTF_8));
            }
        }
        return put;
    }

    static HuaweiProtocol.Tlv fileHash(byte b, byte[] bArr) {
        return new HuaweiProtocol.Tlv().put(1, b).put(3, sha256(bArr));
    }

    static HuaweiProtocol.Tlv uploadConsultAck(byte b, boolean z) {
        HuaweiProtocol.Tlv put = new HuaweiProtocol.Tlv().put(127, intBytes(RESULT_OK)).put(1, b);
        if (z) {
            put.put(9, FILE_TYPE_WATCHFACE);
        }
        return put;
    }

    static HuaweiProtocol.Tlv uploadResult(byte b) {
        return new HuaweiProtocol.Tlv().put(127, intBytes(RESULT_OK)).put(1, b);
    }

    static UploadParams parseUploadParams(HuaweiProtocol.Tlv tlv) {
        boolean z;
        int i;
        int i2;
        byte firstByte = (byte) tlv.firstByte(1, 0);
        int firstAsInteger = tlv.firstAsInteger(3, 0);
        int firstAsInteger2 = tlv.firstAsInteger(5, 0);
        int firstAsInteger3 = tlv.firstAsInteger(6, 0);
        int firstAsInteger4 = tlv.firstAsInteger(7, 0);
        if (tlv.firstByte(9, 0) == 1) {
            i = firstAsInteger;
            i2 = firstAsInteger3;
            z = true;
        } else {
            z = false;
            i = firstAsInteger;
            i2 = firstAsInteger3;
        }
        return new UploadParams(firstByte, i, firstAsInteger2, i2, firstAsInteger4, z);
    }

    static NextChunk parseNextChunk(HuaweiProtocol.Tlv tlv) {
        return new NextChunk(tlv.firstAsInteger(2, 0), tlv.firstAsInteger(3, 0));
    }

    static byte parseFileId(HuaweiProtocol.Tlv tlv) {
        return (byte) tlv.firstByte(1, 0);
    }

    static int parseResult(HuaweiProtocol.Tlv tlv) {
        return tlv.firstInt(127, 0);
    }

    static List<byte[]> unencryptedChunkFrames(byte[] bArr, int i, int i2, int i3, int i4, byte b) {
        if (i < 0 || i >= bArr.length || i2 <= 0 || i3 <= 0) {
            return new ArrayList();
        }
        byte[] copyOfRange = Arrays.copyOfRange(bArr, i, Math.min(bArr.length, i2 + i));
        ArrayList arrayList = new ArrayList();
        int i5 = 0;
        byte b2 = 0;
        while (i5 < copyOfRange.length) {
            int min = Math.min(i3, copyOfRange.length - i5);
            ByteBuffer allocate = ByteBuffer.allocate(min + 6);
            allocate.put(b);
            byte b3 = (byte) (b2 + FILE_TYPE_WATCHFACE);
            allocate.put(b2);
            allocate.putInt(i + i5);
            allocate.put(copyOfRange, i5, min);
            arrayList.addAll(HuaweiProtocol.frames(SERVICE_FILE_UPLOAD, (byte) 6, allocate.array(), i4));
            i5 += min;
            b2 = b3;
        }
        return arrayList;
    }

    static List<byte[]> encryptedChunkFrames(byte[] bArr, int i, int i2, int i3, int i4, byte b, byte[] bArr2, int i5, int i6, EncryptionCounter encryptionCounter) {
        if (i < 0 || i >= bArr.length || i2 <= 0 || i3 <= 0) {
            return new ArrayList();
        }
        byte[] copyOfRange = Arrays.copyOfRange(bArr, i, Math.min(bArr.length, i + i2));
        ArrayList arrayList = new ArrayList();
        int i7 = 0;
        byte b2 = 0;
        while (i7 < copyOfRange.length) {
            int min = Math.min(i3, copyOfRange.length - i7);
            ByteBuffer allocate = ByteBuffer.allocate(min + 6);
            allocate.put(b);
            byte b3 = (byte) (b2 + FILE_TYPE_WATCHFACE);
            allocate.put(b2);
            allocate.putInt(i + i7);
            allocate.put(copyOfRange, i7, min);
            byte[] transactionIv = HuaweiCryptoSupport.transactionIv(encryptionCounter.value, i6);
            byte[] encryptTransaction = HuaweiCryptoSupport.encryptTransaction(allocate.array(), bArr2, transactionIv, i5, i6);
            if (encryptTransaction == null) {
                return new ArrayList();
            }
            if (i6 != 4) {
                encryptionCounter.value = HuaweiCryptoSupport.counterFromIv(transactionIv);
            }
            arrayList.addAll(HuaweiProtocol.frames(SERVICE_FILE_UPLOAD, (byte) 6, new HuaweiProtocol.Tlv().put(124, FILE_TYPE_WATCHFACE).put(125, transactionIv).put(126, encryptTransaction).serialize(), i4));
            i7 += min;
            copyOfRange = copyOfRange;
            b2 = b3;
        }
        return arrayList;
    }

    private static byte[] intBytes(int i) {
        return ByteBuffer.allocate(4).putInt(i).array();
    }

    private static byte[] sha256(byte[] bArr) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bArr);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static final class UploadParams {
        final int appWaitTimeMs;
        final byte fileId;
        final int intervalMs;
        final int maxApplyDataSize;
        final boolean noEncryption;
        final int unitSize;

        UploadParams(byte b, int i, int i2, int i3, int i4, boolean z) {
            this.fileId = b;
            this.appWaitTimeMs = i;
            this.unitSize = i2;
            this.maxApplyDataSize = i3;
            this.intervalMs = i4;
            this.noEncryption = z;
        }
    }

    static final class NextChunk {
        final int bytesUploaded;
        final int nextChunkSize;

        NextChunk(int i, int i2) {
            this.bytesUploaded = i;
            this.nextChunkSize = i2;
        }
    }

    static final class EncryptionCounter {
        long value;

        EncryptionCounter(long j) {
            this.value = j;
        }
    }
}
