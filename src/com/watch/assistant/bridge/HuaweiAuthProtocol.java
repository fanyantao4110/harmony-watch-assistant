package com.watch.assistant.bridge;

import android.os.Build;
import com.watch.assistant.bridge.HuaweiProtocol;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes4.dex */
final class HuaweiAuthProtocol {
    static final int AUTH_MODE_HICHAIN = 1;
    static final int AUTH_MODE_HICHAIN3 = 4;
    static final int AUTH_MODE_HICHAIN_LITE = 2;
    static final int AUTH_MODE_NONE = 0;
    static final int AUTH_TYPE_HICHAIN = 100000;
    static final byte CMD_ACCEPT_AGREEMENT = 48;
    static final byte CMD_AUTH = 19;
    static final byte CMD_BOND = 14;
    static final byte CMD_BOND_PARAMS = 15;
    static final byte CMD_HICHAIN = 40;
    static final byte CMD_SECURITY_NEGOTIATION = 51;
    static final String GROUP_ID = "7B0BC0CBCE474F6C238D9661C63400B797B166EA7849B3A370FC73A9A236E989";
    private static final byte OP_AUTH = 1;
    private static final byte OP_BIND = 2;
    static final byte SERVICE_DEVICE_CONFIG = 1;
    private static final byte[] AAD_EXCHANGE = "hichain_iso_exchange".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_RESULT = "hichain_iso_result".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_SESSION_KEY = "hichain_iso_session_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_RETURN_KEY = "hichain_return_key".getBytes(StandardCharsets.UTF_8);

    static boolean isFullHiChainAuthType(int i) {
        return i == AUTH_TYPE_HICHAIN || (i ^ 1) == 4 || (i ^ 2) == 4;
    }

    static boolean isHiChainLite(int i) {
        return i == 2;
    }

    static boolean isHiChainLiteAuthType(int i) {
        return i == 1 || i == 2;
    }

    static boolean supportsHiChain(int i) {
        return i == 1 || i == 2 || i == 3 || i == 4 || i == 8;
    }

    private HuaweiAuthProtocol() {
    }

    static int preferredAuthMode(int i) {
        if (i == 4) {
            return 4;
        }
        return supportsHiChain(i) ? 2 : 0;
    }

    static HuaweiProtocol.Tlv securityNegotiation(int i, byte[] bArr, int i2) {
        HuaweiProtocol.Tlv put = new HuaweiProtocol.Tlv().put(1, (byte) i).put(5, bArr).put(3, (byte) 1).put(4, (byte) 0);
        if (i == 2 || i == 4) {
            put.put(2, (byte) 1);
        }
        if (i == 4) {
            put.put(6, new byte[0]);
            put.put(7, Build.MODEL.getBytes(StandardCharsets.UTF_8));
        }
        if (i2 == 1) {
            put.put(13, (byte) 1);
        }
        return put;
    }

    static int parseSecurityAuthType(HuaweiProtocol.Tlv tlv) {
        int i;
        int i2;
        if (tlv.contains(1)) {
            int firstByte = tlv.firstByte(1, 0);
            i = firstByte == 1 ? AUTH_TYPE_HICHAIN : -1;
            i2 = 4;
            if (firstByte != 4) {
                i2 = -1;
            }
        } else {
            i = -1;
            i2 = -1;
        }
        if (tlv.contains(2)) {
            i = tlv.firstByte(2, 0);
            if (i2 != -1) {
                i ^= i2;
            }
        }
        return tlv.contains(127) ? tlv.firstByte(127, i) : i;
    }

    static HuaweiProtocol.Tlv acceptAgreements(long j) {
        return new HuaweiProtocol.Tlv().put(129, new HuaweiProtocol.Tlv().put(130, agreement("software_update_service_statement", j).serialize()).put(130, agreement("device_information_management", j).serialize()).put(130, agreement("user_license_agreement", j).serialize()).serialize());
    }

    private static HuaweiProtocol.Tlv agreement(String str, long j) {
        return new HuaweiProtocol.Tlv().put(3, str.getBytes(StandardCharsets.UTF_8)).put(4, (byte) 1).put(5, "20230508-20230508-0-0".getBytes(StandardCharsets.UTF_8)).put(6, Long.toString(j).getBytes(StandardCharsets.UTF_8));
    }

    static HuaweiProtocol.Tlv bondParams(byte[] bArr, byte[] bArr2) {
        return new HuaweiProtocol.Tlv().put(1, new byte[0]).put(3, bArr).put(4, OP_BIND).put(5, new byte[0]).put(7, bArr2).put(9, new byte[0]);
    }

    static int parseBondParamsStatus(HuaweiProtocol.Tlv tlv) {
        return tlv.firstByte(1, 0);
    }

    static long parseBondParamsEncryptionCounter(HuaweiProtocol.Tlv tlv) {
        return tlv.firstUnsignedInt(9, 0L);
    }

    static HuaweiProtocol.Tlv bond(int i, int i2, int i3, String str, byte[] bArr, byte[] bArr2, byte[] bArr3, long j) {
        byte[] transactionIv;
        byte[] encryptBondingKey;
        if (i2 != 2) {
            bArr3 = HuaweiCryptoSupport.createBondingSecret(i, str);
        }
        if (bArr3 == null || (encryptBondingKey = HuaweiCryptoSupport.encryptBondingKey(bArr2, bArr3, (transactionIv = HuaweiCryptoSupport.transactionIv(j, 0)), i3)) == null) {
            return null;
        }
        return new HuaweiProtocol.Tlv().put(1, new byte[0]).put(3, (byte) 0).put(5, bArr).put(6, encryptBondingKey).put(7, transactionIv);
    }

    static HiChainSession newHiChainSession(boolean z, byte[] bArr, byte[] bArr2, byte[] bArr3) {
        return new HiChainSession(z ? (byte) 1 : OP_BIND, System.currentTimeMillis(), bArr, bArr2, bArr3);
    }

    static HuaweiProtocol.Tlv hiChainStepOne(HiChainSession hiChainSession) {
        try {
            new SecureRandom().nextBytes(hiChainSession.seed);
            new SecureRandom().nextBytes(hiChainSession.randSelf);
            JSONObject basePayload = basePayload();
            basePayload.put("isoSalt", hex(hiChainSession.randSelf));
            basePayload.put("peerAuthId", hex(hiChainSession.selfAuthId));
            basePayload.put("operationCode", (int) hiChainSession.operationCode);
            basePayload.put("seed", hex(hiChainSession.seed));
            basePayload.put("peerUserType", 0);
            JSONObject baseValue = baseValue(hiChainSession, 1, basePayload);
            if (hiChainSession.operationCode == 2) {
                basePayload.put("pkgName", "com.huawei.devicegroupmanage");
                basePayload.put("serviceType", GROUP_ID);
                basePayload.put("keyLength", 32);
                baseValue.put("isDeviceLevel", false);
            }
            return hiChainTlv(hiChainSession, baseValue);
        } catch (JSONException unused) {
            return null;
        }
    }

    static HuaweiProtocol.Tlv hiChainStepTwo(HiChainSession hiChainSession) {
        if (hiChainSession.psk != null && hiChainSession.randPeer != null && hiChainSession.authIdPeer != null) {
            byte[] hmacSha256Digest = HuaweiCryptoSupport.hmacSha256Digest(hiChainSession.psk, ByteBuffer.allocate(hiChainSession.randSelf.length + hiChainSession.randPeer.length + hiChainSession.authIdPeer.length + hiChainSession.selfAuthId.length).put(hiChainSession.randSelf).put(hiChainSession.randPeer).put(hiChainSession.authIdPeer).put(hiChainSession.selfAuthId).array());
            if (hmacSha256Digest == null) {
                return null;
            }
            try {
                JSONObject baseValue = baseValue(hiChainSession, 2, basePayload().put("peerAuthId", hex(hiChainSession.selfAuthId)).put("token", hex(hmacSha256Digest)));
                if (hiChainSession.operationCode == 2) {
                    baseValue.put("isDeviceLevel", false);
                }
                return hiChainTlv(hiChainSession, baseValue);
            } catch (JSONException unused) {
            }
        }
        return null;
    }

    static HuaweiProtocol.Tlv hiChainStepThree(HiChainSession hiChainSession) {
        if (hiChainSession.operationCode != 1) {
            return hiChainStepFour(hiChainSession, 3);
        }
        if (!ensureSessionKey(hiChainSession)) {
            return null;
        }
        byte[] bArr = new byte[12];
        hiChainSession.challenge = new byte[16];
        new SecureRandom().nextBytes(bArr);
        new SecureRandom().nextBytes(hiChainSession.challenge);
        byte[] encryptAesGcm = HuaweiCryptoSupport.encryptAesGcm(hiChainSession.challenge, hiChainSession.sessionKey, bArr, AAD_EXCHANGE);
        if (encryptAesGcm == null) {
            return null;
        }
        try {
            return hiChainTlv(hiChainSession, baseValue(hiChainSession, 3, basePayload().put("nonce", hex(bArr)).put("encData", hex(encryptAesGcm))));
        } catch (JSONException unused) {
            return null;
        }
    }

    static HuaweiProtocol.Tlv hiChainStepFour(HiChainSession hiChainSession) {
        return hiChainStepFour(hiChainSession, 4);
    }

    private static HuaweiProtocol.Tlv hiChainStepFour(HiChainSession hiChainSession, int i) {
        if (!ensureSessionKey(hiChainSession)) {
            return null;
        }
        byte[] bArr = new byte[12];
        new SecureRandom().nextBytes(bArr);
        byte[] encryptAesGcm = HuaweiCryptoSupport.encryptAesGcm(new byte[]{0, 0, 0, 0}, hiChainSession.sessionKey, bArr, AAD_RESULT);
        if (encryptAesGcm == null) {
            return null;
        }
        try {
            return hiChainTlv(hiChainSession, baseValue(hiChainSession, i, basePayload().put("nonce", hex(bArr)).put("encResult", hex(encryptAesGcm)).put("operationCode", (int) hiChainSession.operationCode)));
        } catch (JSONException unused) {
            return null;
        }
    }

    static HiChainResponse parseHiChainResponse(HuaweiProtocol.Tlv tlv) {
        if (tlv.firstByte(4, 0) != 0) {
            return new HiChainResponse((byte) 4, null, null, null, null, 0);
        }
        String firstString = tlv.firstString(1);
        if (firstString == null) {
            return null;
        }
        // ★ jadx 这里把 catch 里的 `return null` 丢了，导致 jSONObject/optInt 可能未初始化、
        //   且后面的 getString 抛 JSONException 无人处理。按原语义补回：解析失败整段返回 null。
        JSONObject jSONObject;
        int optInt;
        try {
            jSONObject = new JSONObject(firstString).getJSONObject("payload");
            optInt = jSONObject.optInt("errorCode", 0);
        } catch (IllegalArgumentException | JSONException unused) {
            return null;
        }
        try {
            if (jSONObject.has("isoSalt")) {
                return new HiChainResponse((byte) 1, hexToBytes(jSONObject.getString("isoSalt")), hexToBytes(jSONObject.getString("peerAuthId")), hexToBytes(jSONObject.getString("token")), null, optInt);
            }
            if (jSONObject.has("returnCodeMac")) {
                return new HiChainResponse(OP_BIND, null, null, hexToBytes(jSONObject.getString("returnCodeMac")), null, optInt);
            }
            if (jSONObject.has("encAuthToken")) {
                return new HiChainResponse((byte) 3, hexToBytes(jSONObject.getString("nonce")), null, null, hexToBytes(jSONObject.getString("encAuthToken")), optInt);
            }
        } catch (JSONException unused) {
            return null;
        }
        return null;
    }

    static boolean handleHiChainStepOne(HiChainSession hiChainSession, HiChainResponse hiChainResponse) {
        byte[] bArr;
        if (hiChainResponse.step != 1 || hiChainResponse.peerAuthId == null || hiChainResponse.isoSalt == null || hiChainResponse.token == null) {
            return false;
        }
        hiChainSession.authIdPeer = hiChainResponse.peerAuthId;
        hiChainSession.randPeer = hiChainResponse.isoSalt;
        if (hiChainSession.operationCode == 1) {
            bArr = HuaweiCryptoSupport.pinDigest(hiChainSession.pinCode);
        } else {
            bArr = hiChainSession.savedSecretKey;
        }
        if (bArr == null) {
            return false;
        }
        hiChainSession.psk = HuaweiCryptoSupport.hmacSha256Digest(bArr, hiChainSession.seed);
        if (hiChainSession.psk == null) {
            return false;
        }
        return Arrays.equals(HuaweiCryptoSupport.hmacSha256Digest(hiChainSession.psk, ByteBuffer.allocate(hiChainSession.randPeer.length + hiChainSession.randSelf.length + hiChainSession.selfAuthId.length + hiChainSession.authIdPeer.length).put(hiChainSession.randPeer).put(hiChainSession.randSelf).put(hiChainSession.selfAuthId).put(hiChainSession.authIdPeer).array()), hiChainResponse.token);
    }

    static boolean handleHiChainStepTwo(HiChainSession hiChainSession, HiChainResponse hiChainResponse) {
        if (hiChainResponse.step != 2 || hiChainResponse.token == null || hiChainSession.psk == null) {
            return false;
        }
        return Arrays.equals(HuaweiCryptoSupport.hmacSha256Digest(hiChainSession.psk, new byte[]{0, 0, 0, 0}), hiChainResponse.token);
    }

    static boolean handleHiChainStepThree(HiChainSession hiChainSession, HiChainResponse hiChainResponse) {
        byte[] decryptAesGcm;
        if (hiChainResponse.step != 3 || hiChainResponse.nonce == null || hiChainResponse.encAuthToken == null || hiChainSession.sessionKey == null || hiChainSession.challenge == null || (decryptAesGcm = HuaweiCryptoSupport.decryptAesGcm(hiChainResponse.encAuthToken, hiChainSession.sessionKey, hiChainResponse.nonce, hiChainSession.challenge)) == null) {
            return false;
        }
        hiChainSession.savedSecretKey = decryptAesGcm;
        return true;
    }

    static byte[] finalHiChainReturnKey(HiChainSession hiChainSession) {
        if (hiChainSession.sessionKey == null || hiChainSession.randSelf == null || hiChainSession.randPeer == null) {
            return null;
        }
        return HuaweiCryptoSupport.hkdfSha256(hiChainSession.sessionKey, ByteBuffer.allocate(hiChainSession.randSelf.length + hiChainSession.randPeer.length).put(hiChainSession.randSelf).put(hiChainSession.randPeer).array(), INFO_RETURN_KEY, 32);
    }

    private static boolean ensureSessionKey(HiChainSession hiChainSession) {
        if (hiChainSession.sessionKey != null) {
            return true;
        }
        if (hiChainSession.psk != null && hiChainSession.randSelf != null && hiChainSession.randPeer != null) {
            hiChainSession.sessionKey = HuaweiCryptoSupport.hkdfSha256(hiChainSession.psk, ByteBuffer.allocate(hiChainSession.randSelf.length + hiChainSession.randPeer.length).put(hiChainSession.randSelf).put(hiChainSession.randPeer).array(), INFO_SESSION_KEY, 32);
            if (hiChainSession.sessionKey != null) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject basePayload() throws JSONException {
        return new JSONObject().put("version", new JSONObject().put("minVersion", "1.0.0").put("currentVersion", "2.0.16"));
    }

    private static JSONObject baseValue(HiChainSession hiChainSession, int i, JSONObject jSONObject) throws JSONException {
        if (hiChainSession.operationCode == 2) {
            i |= 16;
        }
        JSONObject put = new JSONObject().put("authForm", 0).put("payload", jSONObject).put("groupAndModuleVersion", "2.0.1").put(WatchBridge.EXTRA_MESSAGE, i);
        if (hiChainSession.operationCode == 1) {
            String str = new String(hiChainSession.selfAuthId, StandardCharsets.UTF_8);
            put.put("requestId", Long.toString(hiChainSession.requestId)).put("groupId", GROUP_ID).put("groupName", "health_group_name").put("groupOp", 2).put("groupType", 256).put("peerDeviceId", str).put("connDeviceId", str).put("appId", "com.huawei.health").put("ownerName", "");
        }
        return put;
    }

    private static HuaweiProtocol.Tlv hiChainTlv(HiChainSession hiChainSession, JSONObject jSONObject) {
        return new HuaweiProtocol.Tlv().put(1, jSONObject.toString().getBytes(StandardCharsets.UTF_8)).put(2, hiChainSession.operationCode).put(3, ByteBuffer.allocate(8).putLong(hiChainSession.requestId).array());
    }

    private static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder(bArr.length * 2);
        for (byte b : bArr) {
            sb.append(String.format(Locale.US, "%02x", Byte.valueOf(b)));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String str) {
        if (str.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex length");
        }
        int length = str.length() / 2;
        byte[] bArr = new byte[length];
        for (int i = 0; i < length; i++) {
            int i2 = i * 2;
            bArr[i] = (byte) Integer.parseInt(str.substring(i2, i2 + 2), 16);
        }
        return bArr;
    }

    static final class HiChainSession {
        byte[] authIdPeer;
        byte[] challenge;
        final byte operationCode;
        byte[] pinCode;
        byte[] psk;
        byte[] randPeer;
        final long requestId;
        byte[] savedSecretKey;
        final byte[] selfAuthId;
        byte[] sessionKey;
        final byte[] seed = new byte[32];
        final byte[] randSelf = new byte[16];

        HiChainSession(byte b, long j, byte[] bArr, byte[] bArr2, byte[] bArr3) {
            this.operationCode = b;
            this.requestId = j;
            this.selfAuthId = bArr;
            this.savedSecretKey = bArr2;
            this.pinCode = bArr3;
        }
    }

    static final class HiChainResponse {
        final byte[] encAuthToken;
        final int errorCode;
        final byte[] isoSalt;
        final byte[] nonce;
        final byte[] peerAuthId;
        final byte step;
        final byte[] token;

        HiChainResponse(byte b, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4, int i) {
            this.step = b;
            this.isoSalt = b == 1 ? bArr : null;
            this.peerAuthId = bArr2;
            this.token = bArr3;
            this.encAuthToken = bArr4;
            this.nonce = b != 3 ? null : bArr;
            this.errorCode = i;
        }
    }
}