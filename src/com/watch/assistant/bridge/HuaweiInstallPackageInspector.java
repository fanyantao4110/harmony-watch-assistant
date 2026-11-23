package com.watch.assistant.bridge;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/* loaded from: classes4.dex */
final class HuaweiInstallPackageInspector {
    private static final byte HUAWEI_APP_BIN_MAGIC = -66;
    private static final int SIGN_HEADER_LEN = 32;
    private static final byte[] SIGN_MAGIC = "hw signed app   ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIGN_VERSION = "1000".getBytes(StandardCharsets.UTF_8);
    private final Context context;

    enum PackageType {
        APP,
        WATCHFACE,
        UNKNOWN
    }

    static final class InspectionResult {
        final String name;
        final byte[] payload;
        final String payloadName;
        final String payloadSha256;
        final String sourceSha256;
        final int sourceSize;
        final PackageType type;
        final String vendor;
        final String version;

        InspectionResult(PackageType packageType, String str, String str2, String str3, byte[] bArr, String str4, byte[] bArr2) {
            this.type = packageType;
            this.name = str;
            this.version = str2;
            this.vendor = str3;
            this.payload = bArr;
            this.payloadName = str4;
            this.sourceSize = bArr2 != null ? bArr2.length : 0;
            this.sourceSha256 = bArr2 != null ? HuaweiInstallPackageInspector.sha256Hex(bArr2) : "unavailable";
            this.payloadSha256 = HuaweiInstallPackageInspector.sha256Hex(bArr);
        }

        boolean isInstallable() {
            return this.type == PackageType.APP || this.type == PackageType.WATCHFACE;
        }
    }

    HuaweiInstallPackageInspector(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean canInstall(Uri uri) {
        return inspect(uri).isInstallable();
    }

    InspectionResult inspect(Uri uri) {
        byte[] readUri;
        try {
            readUri = readUri(uri);
        } catch (IOException | RuntimeException unused) {
            // ★ jadx 丢了这里的 return：readUri 未初始化会编译不过，
            //   语义上读不出内容就是不可安装，原版同样返回 UNKNOWN。
            return new InspectionResult(PackageType.UNKNOWN, null, null, null, new byte[0], "unknown", null);
        }
        if (isHuaweiAppBin(readUri)) {
            HuaweiAppBinConfig parseAppConfig = parseAppConfig(readUri);
            String str = null;
            PackageType packageType = PackageType.APP;
            String str2 = parseAppConfig != null ? parseAppConfig.bundleName : null;
            String str3 = parseAppConfig != null ? parseAppConfig.version : null;
            if (parseAppConfig != null) {
                str = parseAppConfig.vendor;
            }
            return new InspectionResult(packageType, str2, str3, str, readUri, appPayloadName(parseAppConfig), readUri);
        }
        String str4 = null;
        WatchfacePayload extractHuaweiWatchfacePayload = extractHuaweiWatchfacePayload(readUri);
        if (extractHuaweiWatchfacePayload != null) {
            return new InspectionResult(PackageType.WATCHFACE, extractHuaweiWatchfacePayload.name, extractHuaweiWatchfacePayload.version, null, extractHuaweiWatchfacePayload.payload, extractHuaweiWatchfacePayload.payloadName, readUri);
        }
        byte[] extractHuaweiAppBin = extractHuaweiAppBin(readUri);
        if (extractHuaweiAppBin != null && isHuaweiAppBin(extractHuaweiAppBin)) {
            HuaweiAppBinConfig parseAppConfig2 = parseAppConfig(extractHuaweiAppBin);
            PackageType packageType2 = PackageType.APP;
            String str5 = parseAppConfig2 != null ? parseAppConfig2.bundleName : null;
            String str6 = parseAppConfig2 != null ? parseAppConfig2.version : null;
            if (parseAppConfig2 != null) {
                str4 = parseAppConfig2.vendor;
            }
            return new InspectionResult(packageType2, str5, str6, str4, extractHuaweiAppBin, appPayloadName(parseAppConfig2), readUri);
        }
        return new InspectionResult(PackageType.UNKNOWN, null, null, null, new byte[0], "unknown", null);
    }

    private byte[] readUri(Uri uri) throws IOException {
        InputStream openInputStream = this.context.getContentResolver().openInputStream(uri);
        try {
            if (openInputStream == null) {
                throw new IOException("Unable to open install package");
            }
            byte[] readAllBytes = readAllBytes(openInputStream);
            if (openInputStream != null) {
                openInputStream.close();
            }
            return readAllBytes;
        } catch (Throwable th) {
            if (openInputStream != null) {
                try {
                    openInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private WatchfacePayload extractHuaweiWatchfacePayload(byte[] bArr) {
        byte[] bArr2;
        byte[] bArr3;
        byte[] bArr4;
        String str;
        String str2;
        Boolean bool;
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bArr));
            bArr2 = null;
            bArr3 = null;
            bArr4 = null;
            str = null;
            str2 = null;
            bool = null;
            while (true) {
                try {
                    try {
                        ZipEntry nextEntry = zipInputStream.getNextEntry();
                        if (nextEntry == null) {
                            break;
                        }
                        if (!nextEntry.isDirectory() && nextEntry.getName() != null) {
                            String name = nextEntry.getName();
                            if ("description.xml".equals(name)) {
                                String stripUtf8Bom = stripUtf8Bom(new String(readAllBytes(zipInputStream), StandardCharsets.UTF_8));
                                bool = isHonorWatchfaceDescription(stripUtf8Bom);
                                str = xmlText(stripUtf8Bom, "title");
                                str2 = xmlText(stripUtf8Bom, "version");
                            } else if ("com.huawei.watchface".equals(name)) {
                                bArr2 = readAllBytes(zipInputStream);
                            } else if ("com.honor.watchface".equals(name)) {
                                bArr3 = readAllBytes(zipInputStream);
                            } else if (name.toLowerCase(Locale.US).endsWith("watchface.bin")) {
                                bArr4 = readAllBytes(zipInputStream);
                            }
                        }
                    } finally {
                    }
                } catch (IOException unused) {
                }
            }
            zipInputStream.close();
        } catch (IOException unused2) {
            bArr2 = null;
            bArr3 = null;
            bArr4 = null;
            str = null;
            str2 = null;
            bool = null;
        }
        byte[] chooseWatchfacePayload = chooseWatchfacePayload(bool, bArr2, bArr3, bArr4);
        if (chooseWatchfacePayload == null || chooseWatchfacePayload.length == 0) {
            return null;
        }
        byte[] extractNestedWatchfaceBin = extractNestedWatchfaceBin(chooseWatchfacePayload);
        if (extractNestedWatchfaceBin != null) {
            chooseWatchfacePayload = extractNestedWatchfaceBin;
        }
        return new WatchfacePayload(str, str2, chooseWatchfacePayload, randomWatchfacePayloadName());
    }

    private byte[] chooseWatchfacePayload(Boolean bool, byte[] bArr, byte[] bArr2, byte[] bArr3) {
        if (!Boolean.TRUE.equals(bool) || bArr2 == null || bArr2.length <= 0) {
            if ((Boolean.FALSE.equals(bool) && bArr != null && bArr.length > 0) || (bArr != null && bArr.length > 0)) {
                return bArr;
            }
            if (bArr2 == null || bArr2.length <= 0) {
                if (bArr3 == null || bArr3.length <= 0) {
                    return null;
                }
                return bArr3;
            }
        }
        return bArr2;
    }

    private String stripUtf8Bom(String str) {
        return str.startsWith("\ufeff") ? str.substring(1) : str;
    }

    private Boolean isHonorWatchfaceDescription(String str) {
        String trim = str.trim();
        if (trim.startsWith("<HnTheme")) {
            return true;
        }
        return trim.startsWith("<HwTheme") ? false : null;
    }

    private byte[] extractNestedWatchfaceBin(byte[] bArr) {
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bArr));
            while (true) {
                try {
                    ZipEntry nextEntry = zipInputStream.getNextEntry();
                    if (nextEntry == null) {
                        zipInputStream.close();
                        return null;
                    }
                    if (!nextEntry.isDirectory() && nextEntry.getName() != null && "watchface.bin".equals(nextEntry.getName())) {
                        byte[] readAllBytes = readAllBytes(zipInputStream);
                        zipInputStream.close();
                        return readAllBytes;
                    }
                } finally {
                }
            }
        } catch (IOException unused) {
            return null;
        }
    }

    private String xmlText(String str, String str2) {
        int length;
        int indexOf;
        String str3 = "<" + str2 + ">";
        String str4 = "</" + str2 + ">";
        int indexOf2 = str.indexOf(str3);
        if (indexOf2 < 0 || (indexOf = str.indexOf(str4, (length = indexOf2 + str3.length()))) < 0) {
            return null;
        }
        String trim = str.substring(length, indexOf).trim();
        if (TextUtils.isEmpty(trim)) {
            return null;
        }
        return trim;
    }

    private String randomWatchfacePayloadName() {
        return String.format(Locale.US, "%09d_1.0.0", Integer.valueOf(new Random().nextInt(1000000000)));
    }

    private byte[] extractHuaweiAppBin(byte[] bArr) {
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bArr));
            while (true) {
                try {
                    ZipEntry nextEntry = zipInputStream.getNextEntry();
                    if (nextEntry == null) {
                        zipInputStream.close();
                        return null;
                    }
                    if (!nextEntry.isDirectory() && nextEntry.getName() != null) {
                        String lowerCase = nextEntry.getName().toLowerCase(Locale.US);
                        if (isKnownHuaweiAppBinPath(lowerCase) || lowerCase.endsWith(".bin")) {
                            byte[] readAllBytes = readAllBytes(zipInputStream);
                            if (isHuaweiAppBin(readAllBytes)) {
                                zipInputStream.close();
                                return readAllBytes;
                            }
                        }
                    }
                } finally {
                }
            }
        } catch (IOException unused) {
            return null;
        }
    }

    private boolean isKnownHuaweiAppBinPath(String str) {
        return TextUtils.equals(str, "app.bin") || TextUtils.equals(str, "entry/bin/app.bin") || TextUtils.equals(str, "ets/app.bin") || TextUtils.equals(str, "watchapp.bin");
    }

    private boolean isHuaweiAppBin(byte[] bArr) {
        if (bArr != null && bArr.length >= 9 && bArr[0] == -66) {
            try {
                HuaweiAppBinConfig parseAppConfig = parseAppConfig(bArr);
                if (parseAppConfig != null && !TextUtils.isEmpty(parseAppConfig.bundleName)) {
                    if (!TextUtils.isEmpty(parseAppConfig.version)) {
                        return true;
                    }
                }
            } catch (RuntimeException unused) {
            }
        }
        return false;
    }

    private HuaweiAppBinConfig parseAppConfig(byte[] bArr) {
        java.util.Map<?, ?> versionMap = null;
        String readString;
        byte[] readData;
        String stringValue;
        int signatureLength = getSignatureLength(bArr);
        ByteBuffer wrap = ByteBuffer.wrap(bArr);
        wrap.get();
        readString(wrap);
        do {
            if (wrap.remaining() <= signatureLength) {
                return null;
            }
            readString = readString(wrap);
            readStringAllowEmpty(wrap);
            readData = readData(wrap);
        } while (!"config.json".equals(readString));

        // 原版用 Gson 解析，这里改用项目内置 MiniJson，行为等价：
        //   顶层若有 "app" 对象则下钻到 app；app 里若有 "version" 对象则取它的 name 当版本号
        java.util.Map<?, ?> root = asMap(com.wlaz.MiniJson.parse(new String(readData, StandardCharsets.UTF_8)));
        java.util.Map<?, ?> app = root;
        Object appObj = root == null ? null : root.get("app");
        if (appObj instanceof java.util.Map) app = (java.util.Map<?, ?>) appObj;
        Object verObj = app == null ? null : app.get("version");
        if (verObj instanceof java.util.Map) versionMap = (java.util.Map<?, ?>) verObj;
        String stringValue2 = stringValue(app, "bundleName");
        String stringValue3 = stringValue(app, "vendor");
        if (versionMap == null) {
            stringValue = stringValue(app, "version");
        } else {
            stringValue = stringValue(versionMap, "name");
        }
        return new HuaweiAppBinConfig(stringValue2, stringValue3, stringValue);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<?, ?> asMap(Object o) {
        return o instanceof java.util.Map ? (java.util.Map<?, ?>) o : null;
    }

    private String appPayloadName(HuaweiAppBinConfig huaweiAppBinConfig) {
        if (huaweiAppBinConfig != null && !TextUtils.isEmpty(huaweiAppBinConfig.bundleName)) {
            return huaweiAppBinConfig.bundleName + "_INSTALL";
        }
        return "app_INSTALL";
    }

    private int getSignatureLength(byte[] bArr) {
        if (bArr.length < 32) {
            return 0;
        }
        ByteBuffer wrap = ByteBuffer.wrap(Arrays.copyOfRange(bArr, bArr.length - 32, bArr.length));
        byte[] bArr2 = SIGN_MAGIC;
        byte[] bArr3 = new byte[bArr2.length];
        wrap.get(bArr3);
        if (!Arrays.equals(bArr2, bArr3)) {
            return 0;
        }
        byte[] bArr4 = SIGN_VERSION;
        byte[] bArr5 = new byte[bArr4.length];
        wrap.get(bArr5);
        if (Arrays.equals(bArr4, bArr5)) {
            return wrap.getInt();
        }
        return 0;
    }

    private String readString(ByteBuffer byteBuffer) {
        int i = byteBuffer.getInt();
        if (i <= 0 || i > byteBuffer.remaining()) {
            throw new IllegalArgumentException("Invalid string length");
        }
        return readString(byteBuffer, i);
    }

    private String readStringAllowEmpty(ByteBuffer byteBuffer) {
        int i = byteBuffer.getInt();
        if (i < 0 || i > byteBuffer.remaining()) {
            throw new IllegalArgumentException("Invalid string length");
        }
        return readString(byteBuffer, i);
    }

    private String readString(ByteBuffer byteBuffer, int i) {
        byte[] bArr = new byte[i];
        byteBuffer.get(bArr);
        return new String(bArr, StandardCharsets.UTF_8);
    }

    private byte[] readData(ByteBuffer byteBuffer) {
        long j = byteBuffer.getLong();
        if (j <= 0 || j > 2147483647L || j > byteBuffer.remaining()) {
            throw new IllegalArgumentException("Invalid data length");
        }
        byte[] bArr = new byte[(int) j];
        byteBuffer.get(bArr);
        return bArr;
    }

    private String stringValue(java.util.Map<?, ?> jsonObject, String str) {
        if (jsonObject == null || !jsonObject.containsKey(str)) {
            return null;
        }
        Object v = jsonObject.get(str);
        if (v == null) return null;
        return String.valueOf(v);
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] bArr = new byte[8192];
        while (true) {
            int read = inputStream.read(bArr);
            if (read != -1) {
                byteArrayOutputStream.write(bArr, 0, read);
            } else {
                return byteArrayOutputStream.toByteArray();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String sha256Hex(byte[] bArr) {
        byte[] sha256Digest = HuaweiCryptoSupport.sha256Digest(bArr);
        StringBuilder sb = new StringBuilder(sha256Digest.length * 2);
        for (byte b : sha256Digest) {
            sb.append(String.format(Locale.US, "%02x", Integer.valueOf(b & 0xFF)));
        }
        return sb.toString();
    }

    private static final class HuaweiAppBinConfig {
        final String bundleName;
        final String vendor;
        final String version;

        HuaweiAppBinConfig(String str, String str2, String str3) {
            this.bundleName = str;
            this.vendor = str2;
            this.version = str3;
        }
    }

    private static final class WatchfacePayload {
        final String name;
        final byte[] payload;
        final String payloadName;
        final String version;

        WatchfacePayload(String str, String str2, byte[] bArr, String str3) {
            this.name = str;
            this.version = str2;
            this.payload = bArr;
            this.payloadName = str3;
        }
    }
}