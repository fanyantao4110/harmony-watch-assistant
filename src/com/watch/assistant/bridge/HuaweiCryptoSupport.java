package com.watch.assistant.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/* loaded from: classes4.dex */
final class HuaweiCryptoSupport {
    private static final byte[] DIGEST_SECRET_V1 = {112, -5, 108, 36, 3, 95, -37, 85, 47, 56, -119, -118, -18, -34, (byte)63, 105};
    private static final byte[] DIGEST_SECRET_V2 = {-109, -84, -34, -9, 106, -53, 9, -123, 125, -65, -27, 38, 26, -85, -51, 120};
    private static final byte[] DIGEST_SECRET_V3 = {-100, 39, 99, -87, -52, -31, 52, 118, 109, -29, -1, 97, 24, 32, 5, 83};
    private static final byte[] MESSAGE_CHALLENGE = {1, 0};
    private static final byte[] MESSAGE_CHALLENGE_V4 = {4, 0};
    private static final byte[] MESSAGE_RESPONSE = {1, 16};
    private static final byte[] SECRET_KEY_1_V1 = {111, 117, 106, 121, 109, 119, 113, 52, 99, 108, 118, 57, 51, 55, 56, 121};
    private static final byte[] SECRET_KEY_2_V1 = {98, 49, 48, 106, 103, 102, 100, 57, 121, 55, 118, 115, 117, 100, 97, 57};
    private static final byte[] SECRET_KEY_1_V23 = {85, 83, -122, -4, 99, 32, 7, -86, -122, 73, 53, 34, -72, 106, -30, 92};
    private static final byte[] SECRET_KEY_2_V23 = {51, 7, -101, -59, 122, -120, 109, 60, -11, 97, 55, 9, 111, 34, -128, 0};

    private HuaweiCryptoSupport() {
    }

    static byte[] nonce() {
        byte[] bArr = new byte[16];
        new SecureRandom().nextBytes(bArr);
        return bArr;
    }

    static byte[] transactionIv(long j, int i) {
        if (i == 4) {
            return nonce();
        }
        long j2 = j != 4294967295L ? 1 + j : 1L;
        byte[] nonce = nonce();
        nonce[12] = (byte) ((j2 >> 24) & 255);
        nonce[13] = (byte) ((j2 >> 16) & 255);
        nonce[14] = (byte) ((j2 >> 8) & 255);
        nonce[15] = (byte) (j2 & 255);
        return nonce;
    }

    static long counterFromIv(byte[] bArr) {
        if (bArr.length < 16) {
            return 0L;
        }
        return ((bArr[13] & 0xFF) << 16) | ((bArr[12] & 0xFF) << 24) | ((bArr[14] & 0xFF) << 8) | (bArr[15] & 0xFF);
    }

    static byte[] encryptTransaction(byte[] bArr, byte[] bArr2, byte[] bArr3, int i, int i2) {
        return cryptTransaction(1, bArr, bArr2, bArr3, i, i2);
    }

    static byte[] decryptTransaction(byte[] bArr, byte[] bArr2, byte[] bArr3, int i, int i2) {
        return cryptTransaction(2, bArr, bArr2, bArr3, i, i2);
    }

    static byte[] createBondingSecret(int i, String str) {
        try {
            byte[] bArr = i == 1 ? SECRET_KEY_1_V1 : SECRET_KEY_1_V23;
            byte[] bArr2 = i == 1 ? SECRET_KEY_2_V1 : SECRET_KEY_2_V23;
            byte[] bytes = (str.replace(":", "") + "0000").getBytes(StandardCharsets.UTF_8);
            byte[] bArr3 = new byte[16];
            for (int i2 = 0; i2 < 16; i2++) {
                bArr3[i2] = (byte) ((((bArr[i2] & 0xFF) << 4) ^ (bArr2[i2] & 0xFF)) & 255);
            }
            byte[] sha256 = sha256(bArr3);
            byte[] bArr4 = new byte[16];
            for (int i3 = 0; i3 < 16; i3++) {
                bArr4[i3] = (byte) ((((sha256[i3] & 0xFF) >> 6) ^ (bytes[i3] & 0xFF)) & 255);
            }
            return Arrays.copyOfRange(sha256(bArr4), 0, 16);
        } catch (RuntimeException | NoSuchAlgorithmException unused) {
            return null;
        }
    }

    static byte[] pinDigest(byte[] bArr) {
        if (bArr != null && bArr.length != 0) {
            try {
                return sha256(hex(bArr).getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException unused) {
            }
        }
        return null;
    }

    static byte[] sha256Digest(byte[] bArr) {
        try {
            return sha256(bArr);
        } catch (NoSuchAlgorithmException unused) {
            return null;
        }
    }

    static byte[] hmacSha256Digest(byte[] bArr, byte[] bArr2) {
        try {
            return hmacSha256(bArr, bArr2);
        } catch (InvalidKeyException | NoSuchAlgorithmException unused) {
            return null;
        }
    }

    static byte[] hkdfSha256(byte[] bArr, byte[] bArr2, byte[] bArr3, int i) {
        try {
            byte[] hmacSha256 = hmacSha256(bArr2, bArr);
            byte[] bArr4 = new byte[0];
            byte[] bArr5 = new byte[0];
            int i2 = (i + 31) / 32;
            for (int i3 = 1; i3 <= i2; i3++) {
                bArr5 = hmacSha256(hmacSha256, ByteBuffer.allocate(bArr5.length + bArr3.length + 1).put(bArr5).put(bArr3).put((byte) i3).array());
                bArr4 = ByteBuffer.allocate(bArr4.length + bArr5.length).put(bArr4).put(bArr5).array();
            }
            return Arrays.copyOfRange(bArr4, 0, i);
        } catch (RuntimeException | InvalidKeyException | NoSuchAlgorithmException unused) {
            return null;
        }
    }

    static byte[] encryptAesGcm(byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4) {
        return cryptAesGcm(1, bArr, bArr2, bArr3, bArr4);
    }

    static byte[] decryptAesGcm(byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4) {
        return cryptAesGcm(2, bArr, bArr2, bArr3, bArr4);
    }

    static byte[] decryptPinCode(int i, int i2, int i3, byte[] bArr, byte[] bArr2) {
        return cryptTransaction(2, bArr, digestSecret(i), bArr2, i2, 0);
    }

    static byte[] encryptBondingKey(byte[] bArr, byte[] bArr2, byte[] bArr3, int i) {
        return cryptTransaction(1, bArr, bArr2, bArr3, i, 0);
    }

    static AuthDigest createAuthDigest(int i, int i2, int i3, byte[] bArr, byte[] bArr2, byte[] bArr3) {
        byte[] array = ByteBuffer.allocate(32).put(bArr2).put(bArr3).array();
        try {
            byte[] digestChallenge = digestChallenge(i, i2, i3, bArr, array);
            byte[] digestResponse = digestResponse(i, i2, i3, bArr, array);
            if (digestChallenge != null && digestResponse != null) {
                return new AuthDigest(Arrays.copyOfRange(digestChallenge, 0, 32), Arrays.copyOfRange(digestChallenge, 32, 48), Arrays.copyOfRange(digestResponse, 0, 32));
            }
        } catch (Exception unused) {
        }
        return null;
    }

    private static byte[] digestChallenge(int i, int i2, int i3, byte[] bArr, byte[] bArr2) throws Exception {
        if (i3 != 2) {
            return computeDigest(i, i == 4 ? MESSAGE_CHALLENGE_V4 : MESSAGE_CHALLENGE, bArr2);
        }
        if (bArr == null) {
            return null;
        }
        if (i == 2) {
            byte[] array = ByteBuffer.allocate(18).put(bArr).put(MESSAGE_CHALLENGE).array();
            return ByteBuffer.allocate(64).put(hmacSha256(array, bArr2)).put(array).array();
        }
        return computeDigestHiChainLite(i, i2, MESSAGE_CHALLENGE, bArr, bArr2);
    }

    private static byte[] digestResponse(int i, int i2, int i3, byte[] bArr, byte[] bArr2) throws Exception {
        if (i3 != 2) {
            return computeDigest(i, MESSAGE_RESPONSE, bArr2);
        }
        if (bArr == null) {
            return null;
        }
        if (i == 2) {
            byte[] array = ByteBuffer.allocate(18).put(bArr).put(MESSAGE_RESPONSE).array();
            return ByteBuffer.allocate(64).put(hmacSha256(array, bArr2)).put(array).array();
        }
        return computeDigestHiChainLite(i, i2, MESSAGE_RESPONSE, bArr, bArr2);
    }

    private static byte[] computeDigest(int i, byte[] bArr, byte[] bArr2) throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] hmacSha256 = hmacSha256(ByteBuffer.allocate(bArr.length + 16).put(digestSecret(i)).put(bArr).array(), bArr2);
        return ByteBuffer.allocate(64).put(hmacSha256(hmacSha256, bArr2)).put(hmacSha256).array();
    }

    private static byte[] computeDigestHiChainLite(int i, int i2, byte[] bArr, byte[] bArr2, byte[] bArr3) throws Exception {
        byte[] hmacSha256;
        byte[] sha256 = sha256(hex(bArr2).getBytes(StandardCharsets.UTF_8));
        byte[] digestSecret = digestSecret(i);
        for (int i3 = 0; i3 < digestSecret.length; i3++) {
            digestSecret[i3] = (byte) ((sha256[i3] & 0xFF) ^ (digestSecret[i3] & 0xFF));
        }
        byte[] array = ByteBuffer.allocate(18).put(digestSecret).put(bArr).array();
        if (i2 == 1) {
            hmacSha256 = pbkdf2Sha256(hex(array), hex(bArr3), 1000, 256);
        } else {
            hmacSha256 = hmacSha256(array, bArr3);
        }
        return ByteBuffer.allocate(64).put(hmacSha256(hmacSha256, bArr3)).put(hmacSha256).array();
    }

    private static byte[] digestSecret(int i) {
        if (i == 1 || i == 4) {
            return (byte[]) DIGEST_SECRET_V1.clone();
        }
        if (i == 2) {
            return (byte[]) DIGEST_SECRET_V2.clone();
        }
        return (byte[]) DIGEST_SECRET_V3.clone();
    }

    private static byte[] hmacSha256(byte[] bArr, byte[] bArr2) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bArr, "HmacSHA256"));
        return mac.doFinal(bArr2);
    }

    private static byte[] pbkdf2Sha256(String str, String str2, int i, int i2) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(str.toCharArray(), str2.getBytes(StandardCharsets.UTF_8), i, i2)).getEncoded();
    }

    private static byte[] sha256(byte[] bArr) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(bArr);
    }

    private static byte[] cryptTransaction(int i, byte[] bArr, byte[] bArr2, byte[] bArr3, int i2, int i3) {
        boolean z = true;
        if (i2 != 1 && i3 != 4) {
            z = false;
        }
        try {
            Cipher cipher = Cipher.getInstance(z ? "AES/GCM/NoPadding" : "AES/CBC/PKCS5Padding");
            SecretKeySpec secretKeySpec = new SecretKeySpec(bArr2, "AES");
            if (z) {
                cipher.init(i, secretKeySpec, new GCMParameterSpec(128, bArr3));
            } else {
                cipher.init(i, secretKeySpec, new IvParameterSpec(bArr3));
            }
            return cipher.doFinal(bArr);
        } catch (IllegalArgumentException | InvalidAlgorithmParameterException | InvalidKeyException | NoSuchAlgorithmException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException unused) {
            return null;
        }
    }

    private static byte[] cryptAesGcm(int i, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(i, new SecretKeySpec(bArr2, "AES"), new GCMParameterSpec(128, bArr3));
            if (bArr4 != null) {
                cipher.updateAAD(bArr4);
            }
            return cipher.doFinal(bArr);
        } catch (IllegalArgumentException | InvalidAlgorithmParameterException | InvalidKeyException | NoSuchAlgorithmException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException unused) {
            return null;
        }
    }

    private static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder(bArr.length * 2);
        for (byte b : bArr) {
            sb.append(String.format(Locale.US, "%02x", Byte.valueOf(b)));
        }
        return sb.toString();
    }

    static final class AuthDigest {
        final byte[] challenge;
        final byte[] expectedResponse;
        final byte[] firstKey;

        AuthDigest(byte[] bArr, byte[] bArr2, byte[] bArr3) {
            this.challenge = bArr;
            this.firstKey = bArr2;
            this.expectedResponse = bArr3;
        }
    }
}
