package com.watch.assistant.bridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.watch.assistant.bridge.HuaweiAuthProtocol;
import com.watch.assistant.bridge.HuaweiCryptoSupport;
import com.watch.assistant.bridge.HuaweiFileUploadProtocol;
import com.watch.assistant.bridge.HuaweiInstallPackageInspector;
import com.watch.assistant.bridge.HuaweiProtocol;
import com.watch.assistant.bridge.HuaweiRfcommWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/* loaded from: classes4.dex */
public final class NativeHuaweiWatchBackend implements WatchBackend {
    private static final int APP_STATUS_REFRESH_DELETED = 105;
    private static final int APP_STATUS_REFRESH_INSTALLED = 102;
    private static final byte MAGIC_BYTE = 90;

    private static final byte CMD_ACCOUNT_EXTENDED_SEND = 5;
    private static final byte CMD_APP_DELETE = 1;
    private static final byte CMD_APP_LIST = 3;
    private static final byte CMD_APP_STATUS = 2;
    private static final byte CMD_LINK_PARAMS = 1;
    private static final byte CMD_PERMISSION_CHECK = 56;
    private static final byte CMD_PIN_CODE = 44;
    private static final byte CMD_REVERSE_CAPABILITIES = 63;
    private static final byte CMD_SETUP_DEVICE_STATUS = 62;
    private static final byte CMD_SET_TIME = 5;
    private static final byte CMD_SUPPORTED_COMMANDS = 3;
    private static final byte CMD_SUPPORTED_SERVICES = 2;
    private static final byte CMD_WATCHFACE_CONFIRM = 5;
    private static final byte CMD_WATCHFACE_LIST = 2;
    private static final byte CMD_WATCHFACE_OPERATION = 3;
    private static final byte CMD_WEAR_STATUS = 61;
    private static final int DEFAULT_GATT_WRITE_SIZE = 20;
    private static final boolean ENABLE_NATIVE_INSTALL_TRANSFER = true;
    private static final long GATT_WRITE_CALLBACK_TIMEOUT_MS = 8000;
    private static final int GATT_WRITE_MAX_ATTEMPTS = 4;
    private static final long GATT_WRITE_RETRY_DELAY_MS = 60;
    private static final long MANAGEMENT_COMMAND_TIMEOUT_MS = 8000;
    private static final long MANAGEMENT_NEXT_COMMAND_DELAY_MS = 150;
    private static final long MANAGEMENT_REFRESH_DELAY_MS = 1500;
    private static final long PAIRING_FINALIZATION_FALLBACK_MS = 1500;
    private static final long POST_WATCHFACE_OPERATION_REFRESH_DELAY_MS = 6000;
    /** 替换前置：发 0x27/0x01 后等参数回包 */
    private static final long WATCHFACE_PRE_OPERATION_PARAMS_WAIT_MS = 600;
    /** 替换前置：拉新鲜表盘列表后等列表回包，再发 0x27/0x03 */
    private static final long WATCHFACE_PRE_OPERATION_LIST_WAIT_MS = 1500;
    private static final long POST_WATCHFACE_UPLOAD_COOLDOWN_MS = 1500;
    private static final long POST_WATCHFACE_UPLOAD_QUIET_MS = 30000;
    private static final long RECOVERY_RETRY_DELAY_MS = 800;
    private static final long RFCOMM_INTER_FRAME_DELAY_MS = 4;
    private static final int RFCOMM_MAX_CONNECT_ATTEMPTS = 5;
    private static final long RFCOMM_RETRY_DELAY_MS = 5000;
    private static final long RFCOMM_UPLOAD_INTER_FRAME_DELAY_MS = 0;
    private static final long RFCOMM_WRITE_DRAIN_DELAY_MS = 0;
    private static final byte SERVICE_DEVICE_CONFIG = 1;
    private static final String TAG = "NativeHuawei";
    private static final long UPLOAD_INACTIVITY_TIMEOUT_MS = 30000;
    private static final long WATCHFACE_CONFIRM_TIMEOUT_MS = 90000;
    private static final long WATCHFACE_OPERATION_RECONNECT_DELAY_MS = 1200;
    private final Context appContext;
    private final HuaweiAuthStore authStore;
    private final HuaweiInstallPackageInspector installPackageInspector;
    private final WatchDeviceStore store;
    private static final UUID HUAWEI_SERVICE_UUID = uuid16("FE86");
    private static final UUID HUAWEI_WRITE_UUID = uuid16("FE01");
    private static final UUID HUAWEI_READ_UUID = uuid16("FE02");
    private static final UUID HUAWEI_RFCOMM_UUID = UUID.fromString("82FF3820-8411-400C-B85A-55BDB32CF060");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = uuid16("2902");
    private static final long[] SINGLE_MANAGEMENT_REFRESH_DELAY_MS = {1500};
    private static final long[] POST_INSTALL_REFRESH_DELAYS_MS = {30000};
    private static final byte CMD_MUSIC_INFO_PARAMS = 4;
    private static final byte CMD_WATCHFACE_NAME_INFO = 6;
    private static final byte CMD_PRODUCT_INFO = 7;
    private static final byte CMD_BATTERY_LEVEL = 8;
    private static final byte CMD_EXTENDED_MUSIC_INFO_PARAMS = 13;
    private static final byte CMD_BOND = 14;
    private static final byte CMD_BOND_PARAMS = 15;
    private static final byte CMD_PHONE_INFO = 16;
    private static final byte CMD_ACTIVITY_TYPE = 18;
    private static final byte CMD_AUTH = 19;
    private static final byte CMD_DEVICE_STATUS = 22;
    private static final byte SERVICE_ACCOUNT = 26;
    private static final byte SERVICE_MUSIC = 37;
    private static final byte SERVICE_WATCHFACE = 39;
    private static final byte SERVICE_APP = 42;
    private static final byte CMD_ACCEPT_AGREEMENT = 48;
    private static final byte CMD_SECURITY_NEGOTIATION = 51;
    private static final byte CMD_CONNECT_STATUS = 53;
    private static final byte[] KNOWN_SUPPORTED_SERVICES = {2, 3, CMD_MUSIC_INFO_PARAMS, 5, CMD_WATCHFACE_NAME_INFO, CMD_PRODUCT_INFO, CMD_BATTERY_LEVEL, 9, 10, 11, 12, CMD_EXTENDED_MUSIC_INFO_PARAMS, CMD_BOND, CMD_BOND_PARAMS, CMD_PHONE_INFO, 17, CMD_ACTIVITY_TYPE, CMD_AUTH, 20, 21, CMD_DEVICE_STATUS, 23, 24, 25, SERVICE_ACCOUNT, 27, 29, 32, 34, 35, 36, SERVICE_MUSIC, 38, SERVICE_WATCHFACE, SERVICE_APP, 43, 45, 46, CMD_ACCEPT_AGREEMENT, 50, CMD_SECURITY_NEGOTIATION, 52, CMD_CONNECT_STATUS};
    private static final byte CMD_SETTING_RELATED = 49;
    private static final byte CMD_EXPAND_CAPABILITY = 55;
    private static final byte CMD_HICHAIN = 40;
    private static final byte[][] KNOWN_SUPPORTED_COMMANDS = {new byte[]{1, CMD_MUSIC_INFO_PARAMS, CMD_PRODUCT_INFO, CMD_BATTERY_LEVEL, 9, 10, CMD_EXTENDED_MUSIC_INFO_PARAMS, CMD_BOND, CMD_PHONE_INFO, 17, CMD_ACTIVITY_TYPE, CMD_AUTH, 20, 27, SERVICE_ACCOUNT, 29, 33, 34, 35, 36, 41, SERVICE_APP, 43, 50, 46, CMD_SETTING_RELATED, CMD_ACCEPT_AGREEMENT, CMD_CONNECT_STATUS, 54, CMD_EXPAND_CAPABILITY, 47}, new byte[]{2, 1, CMD_MUSIC_INFO_PARAMS, 5, CMD_WATCHFACE_NAME_INFO, CMD_PRODUCT_INFO, CMD_BATTERY_LEVEL}, new byte[]{3, 1, 3, CMD_MUSIC_INFO_PARAMS}, new byte[]{CMD_MUSIC_INFO_PARAMS, 1}, new byte[]{5, 1}, new byte[]{CMD_WATCHFACE_NAME_INFO, 1}, new byte[]{CMD_PRODUCT_INFO, 1, 3, 5, CMD_PRODUCT_INFO, CMD_BATTERY_LEVEL, 9, 10, CMD_BOND, CMD_PHONE_INFO, CMD_AUTH, CMD_DEVICE_STATUS, 21, 23, 24, 27, 28, 29, 30, 33, 34, 35, 36, SERVICE_MUSIC, CMD_HICHAIN, 41, CMD_WATCHFACE_NAME_INFO, 31}, new byte[]{CMD_BATTERY_LEVEL, 1, 2, 3}, new byte[]{9, 1, 11, 12, CMD_EXTENDED_MUSIC_INFO_PARAMS, CMD_BOND, CMD_BOND_PARAMS}, new byte[]{10, 1, 9, 10}, new byte[]{11, 1, 3}, new byte[]{12, 1}, new byte[]{CMD_EXTENDED_MUSIC_INFO_PARAMS, 1}, new byte[]{CMD_BOND_PARAMS, 1, 3, 5, CMD_WATCHFACE_NAME_INFO, CMD_PRODUCT_INFO, CMD_BATTERY_LEVEL, 10, 11, 12}, new byte[]{CMD_PHONE_INFO, 1}, new byte[]{17, 1}, new byte[]{CMD_ACTIVITY_TYPE, 1}, new byte[]{CMD_AUTH, 1}, new byte[]{20, 1}, new byte[]{21, 1}, new byte[]{CMD_DEVICE_STATUS, 1, 3, CMD_PRODUCT_INFO}, new byte[]{23, 1, CMD_MUSIC_INFO_PARAMS, CMD_WATCHFACE_NAME_INFO, CMD_PRODUCT_INFO, 11, 12, CMD_PHONE_INFO, CMD_ACTIVITY_TYPE, 21, 23}, new byte[]{24, 1, 2, CMD_MUSIC_INFO_PARAMS, 5, CMD_WATCHFACE_NAME_INFO, 9}, new byte[]{25, 1, CMD_MUSIC_INFO_PARAMS}, new byte[]{SERVICE_ACCOUNT, 1, 3, CMD_PRODUCT_INFO, 5, CMD_WATCHFACE_NAME_INFO}, new byte[]{27, 1, CMD_BOND_PARAMS, 25, SERVICE_ACCOUNT}, new byte[]{29, 1, 2, 3, CMD_MUSIC_INFO_PARAMS, 5, CMD_WATCHFACE_NAME_INFO, CMD_PRODUCT_INFO, CMD_BATTERY_LEVEL, 9, 10}, new byte[]{32, 1, 2, 3, CMD_MUSIC_INFO_PARAMS, 9, 10}, new byte[]{34, 1}, new byte[]{35, 2, 11}, new byte[]{36, 1, 2, 3, CMD_MUSIC_INFO_PARAMS, 5, CMD_WATCHFACE_NAME_INFO, CMD_PRODUCT_INFO, CMD_BATTERY_LEVEL, 9, 10, 11, 12}, new byte[]{SERVICE_MUSIC, 2, CMD_MUSIC_INFO_PARAMS, CMD_BOND}, new byte[]{38, 2, 3}, new byte[]{SERVICE_WATCHFACE, 1, CMD_BOND}, new byte[]{SERVICE_APP, 1, CMD_WATCHFACE_NAME_INFO}, new byte[]{43, CMD_ACTIVITY_TYPE}, new byte[]{45, 1}, new byte[]{46, 1, 2, 3}, new byte[]{CMD_ACCEPT_AGREEMENT, 1}, new byte[]{50, 1}, new byte[]{CMD_SECURITY_NEGOTIATION, 1, 2}, new byte[]{52, 1}, new byte[]{CMD_CONNECT_STATUS, 3, CMD_MUSIC_INFO_PARAMS}};
    private final Map<String, BluetoothGatt> gattsByAddress = new ConcurrentHashMap();
    private final Map<String, HuaweiGattSession> sessionsByAddress = new ConcurrentHashMap();
    private final Map<String, byte[]> pendingPinsByAddress = new ConcurrentHashMap();
    private final Map<String, NativeUploadSession> uploadsByAddress = new ConcurrentHashMap();
    private final Map<String, NativeUploadSession> pendingUploadsByAddress = new ConcurrentHashMap();
    private final Map<String, NativeUploadSession> awaitingWatchfaceInstallsByAddress = new ConcurrentHashMap();
    private final Map<String, PendingWatchfaceOperation> pendingWatchfaceOperationsByAddress = new ConcurrentHashMap();
    private final Map<String, ManagementQueue> managementQueuesByAddress = new ConcurrentHashMap();
    private final Map<String, Integer> managementGenerationsByAddress = new ConcurrentHashMap();
    private final Map<String, Long> managementCooldownUntilByAddress = new ConcurrentHashMap();
    private final Map<String, Long> managementQuietUntilByAddress = new ConcurrentHashMap();
    private final Map<String, Long> uploadCooldownUntilByAddress = new ConcurrentHashMap();
    private final Map<String, Integer> watchfaceOperationRejectsByAddress = new ConcurrentHashMap();
    private final Map<String, PendingRecoveryAction> recoveryActionsByAddress = new ConcurrentHashMap();
    /** 每个地址上正在进行的表盘替换法事务（鸿蒙 6.0.0.32+ 用） */
    private final Map<String, WatchfaceReplacement> watchfaceReplacementByAddress = new ConcurrentHashMap();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isRecoverableWatchBusyResult(int i) {
        return i == 100004 || i == 10004;
    }

    NativeHuaweiWatchBackend(Context context, WatchDeviceStore watchDeviceStore) {
        Context applicationContext = context.getApplicationContext();
        this.appContext = applicationContext;
        this.store = watchDeviceStore;
        this.installPackageInspector = new HuaweiInstallPackageInspector(applicationContext);
        this.authStore = new HuaweiAuthStore(applicationContext);
        lastInstance = this;
    }

    /**
     * 最近创建的后端实例。
     *
     * 原版 bridge 接口上没有 requestNativeStatus 这类内部方法，
     * 老代码是靠反射从 NativeWatchBridge 的私有字段 backend 里捞出来的。
     * 现在同一个进程里就一个后端，直接记下来更稳，也不用碰私有字段。
     */
    private static volatile NativeHuaweiWatchBackend lastInstance;

    public static NativeHuaweiWatchBackend lastInstance() {
        return lastInstance;
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public WatchDevice resolveConnectableDevice(String str, Context context) {
        boolean z;
        boolean z2;
        boolean z3;
        if (!TextUtils.isEmpty(str) && hasConnectPermission(context)) {
            BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService("bluetooth");
            BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
            if (adapter == null) {
                return null;
            }
            try {
                BluetoothDevice remoteDevice = adapter.getRemoteDevice(str);
                WatchDevice findByAddress = this.store.findByAddress(str);
                String address = remoteDevice.getAddress();
                String displayName = displayName(remoteDevice);
                String uid = findByAddress != null ? findByAddress.getUid() : null;
                String serialNumber = findByAddress != null ? findByAddress.getSerialNumber() : null;
                String stateLabel = findByAddress != null ? findByAddress.getStateLabel() : "Discovered";
                int batteryLevel = findByAddress != null ? findByAddress.getBatteryLevel() : -1;
                int availableSpaceMb = findByAddress != null ? findByAddress.getAvailableSpaceMb() : -1;
                boolean z4 = false;
                if (findByAddress == null || !findByAddress.isConnected()) {
                    z = false;
                } else {
                    z = false;
                    z4 = true;
                }
                if (findByAddress == null || !findByAddress.isInitialized()) {
                    z2 = z;
                } else {
                    z2 = z;
                    z = true;
                }
                if (findByAddress == null || !findByAddress.isConnecting()) {
                    z3 = z2;
                } else {
                    z3 = z2;
                    z2 = true;
                }
                if (findByAddress != null && findByAddress.isAuthenticationRequired()) {
                    z3 = true;
                }
                WatchDevice watchDevice = new WatchDevice(remoteDevice, address, displayName, uid, serialNumber, stateLabel, batteryLevel, availableSpaceMb, z4, z, z2, z3, true, true, true, findByAddress != null ? findByAddress.getInstalledAppsCount() : -1, findByAddress != null ? findByAddress.getInstalledWatchfacesCount() : -1, fingerprints(remoteDevice));
                this.store.upsert(watchDevice);
                return watchDevice;
            } catch (IllegalArgumentException | SecurityException unused) {
            }
        }
        return null;
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public void connect(WatchDevice watchDevice) {
        BluetoothDevice resolveBluetoothDevice;
        String huaweiAccount = this.authStore.getHuaweiAccount(watchDevice.getAddress());
        StringBuilder sb = new StringBuilder("connect start address=");
        sb.append(safeAddress(watchDevice.getAddress()));
        sb.append(" name=");
        sb.append(watchDevice.getDisplayName());
        sb.append(" huaweiId=");
        if (TextUtils.isEmpty(huaweiAccount)) {
            huaweiAccount = "<empty>";
        }
        sb.append(huaweiAccount);
        Log.i(TAG, sb.toString());
        if (!isNativeDevice(watchDevice)) {
            throw new UnsupportedOperationException("Device belongs to another backend");
        }
        if (TextUtils.isEmpty(watchDevice.getAddress()) || !hasConnectPermission(this.appContext)) {
            Log.w(TAG, "connect unavailable address=" + safeAddress(watchDevice.getAddress()));
            throw new UnsupportedOperationException("Native BLE connection is unavailable");
        }
        Object nativeDevice = watchDevice.getNativeDevice();
        if (nativeDevice instanceof BluetoothDevice) {
            resolveBluetoothDevice = (BluetoothDevice) nativeDevice;
        } else {
            resolveBluetoothDevice = resolveBluetoothDevice(watchDevice.getAddress());
        }
        if (resolveBluetoothDevice == null) {
            Log.w(TAG, "connect failed: remote device unavailable address=" + safeAddress(watchDevice.getAddress()));
            throw new UnsupportedOperationException("Native BLE device is unavailable");
        }
        StringBuilder sb2 = new StringBuilder("connect Bluetooth bond state address=");
        sb2.append(safeAddress(watchDevice.getAddress()));
        sb2.append(" androidBondState=");
        sb2.append(resolveBluetoothDevice.getBondState());
        sb2.append(" hasStoredAuthKey=");
        sb2.append(this.authStore.getAuthKey(watchDevice.getAddress()) != null);
        Log.i(TAG, sb2.toString());
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(watchDevice.getAddress());
        if (huaweiGattSession != null && huaweiGattSession.authenticated) {
            Log.i(TAG, "connect skipped: already authenticated address=" + safeAddress(watchDevice.getAddress()));
            completePostAuthentication(watchDevice.getAddress());
            return;
        }
        this.store.upsert(copyWithConnectionState(watchDevice, "Connecting", false, false, true, false));
        sendDeviceChanged(watchDevice.getAddress());
        closeNativeTransports(watchDevice.getAddress());
        try {
            if (shouldUseRfcomm(resolveBluetoothDevice) && startRfcommConnection(resolveBluetoothDevice, watchDevice.getAddress())) {
                return;
            }
            startGattConnection(resolveBluetoothDevice, watchDevice.getAddress());
        } catch (SecurityException e) {
            Log.w(TAG, "connect failed: missing Bluetooth permission address=" + safeAddress(watchDevice.getAddress()), e);
            throw new UnsupportedOperationException("Missing Bluetooth permission", e);
        }
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public void disconnect(WatchDevice watchDevice) {
        Log.i(TAG, "disconnect address=" + safeAddress(watchDevice.getAddress()));
        if (TextUtils.isEmpty(watchDevice.getAddress())) {
            throw new UnsupportedOperationException("Missing device address");
        }
        if (!closeNativeTransports(watchDevice.getAddress())) {
            throw new UnsupportedOperationException("Native Huawei connection is not active");
        }
        this.store.upsert(copyWithConnectionState(watchDevice, "Disconnected", false, false, false, false));
        sendDeviceChanged(watchDevice.getAddress());
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public void requestAppList(WatchDevice watchDevice) {
        if (sessionForReadyDevice(watchDevice) == null) {
            Log.w(TAG, "app list skipped: session not ready address=" + safeAddress(watchDevice.getAddress()));
            throw new UnsupportedOperationException("Native Huawei session is not ready");
        }
        if (isNativeUploadOrInstallBusy(watchDevice.getAddress())) {
            Log.d(TAG, "app list skipped while upload is active address=" + safeAddress(watchDevice.getAddress()));
        } else {
            Log.d(TAG, "request app/watchface management lists address=" + safeAddress(watchDevice.getAddress()));
            if (!requestNativeManagementLists(watchDevice.getAddress())) {
                throw new UnsupportedOperationException("Unable to request native app list");
            }
        }
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public void deleteInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem) {
        boolean enqueueAppDelete;
        String requireReadyAddress = requireReadyAddress(watchDevice);
        bumpManagementGeneration(requireReadyAddress);
        this.managementQuietUntilByAddress.remove(requireReadyAddress);
        if (watchInstalledItem.getRequestType() == 2) {
            String normalizedWatchfaceOperationFileName = normalizedWatchfaceOperationFileName(watchInstalledItem);
            Log.i(TAG, "watchface delete request address=" + safeAddress(requireReadyAddress) + " identifier=" + watchInstalledItem.getIdentifier() + " operationFile=" + normalizedWatchfaceOperationFileName);
            enqueueAppDelete = enqueueWatchfaceOperation(requireReadyAddress, new PendingWatchfaceOperation(normalizedWatchfaceOperationFileName, (byte) 2), true);
        } else {
            Log.i(TAG, "app delete request address=" + safeAddress(requireReadyAddress) + " package=" + watchInstalledItem.getIdentifier());
            enqueueAppDelete = enqueueAppDelete(requireReadyAddress, watchInstalledItem.getIdentifier(), true);
        }
        if (!enqueueAppDelete) {
            throw new UnsupportedOperationException("Unable to send delete request");
        }
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public void activateInstalledItem(WatchDevice watchDevice, WatchInstalledItem watchInstalledItem) {
        if (watchInstalledItem.getRequestType() != 2) {
            throw new UnsupportedOperationException("Only watchfaces can be activated");
        }
        String requireReadyAddress = requireReadyAddress(watchDevice);
        bumpManagementGeneration(requireReadyAddress);
        this.managementQuietUntilByAddress.remove(requireReadyAddress);
        String normalizedWatchfaceOperationFileName = normalizedWatchfaceOperationFileName(watchInstalledItem);
        Log.i(TAG, "watchface activate request address=" + safeAddress(requireReadyAddress) + " identifier=" + watchInstalledItem.getIdentifier() + " operationFile=" + normalizedWatchfaceOperationFileName);
        if (!enqueueWatchfaceOperation(requireReadyAddress, new PendingWatchfaceOperation(normalizedWatchfaceOperationFileName, (byte) 1), true)) {
            throw new UnsupportedOperationException("Unable to send watchface activation request");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 表盘替换法安装（鸿蒙 6.0.0.32+）
    //
    // 普通「新增表盘」在新固件上会被服务器授权/绑定/摘要校验拦掉，
    // 所以改成挑一个手表上已登记、允许改动的普通表盘槽，
    // 用同一个资源 ID + 版本号 +1 发 0x27/0x03(tag3=1) 申请「新版本替换」。
    // 只有设备真的回了 error=0 且 need=1 才传文件（原版这里不读 need，是本工程补的）。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发起一次表盘替换。
     *
     * @param watchDevice  当前设备
     * @param target       被替换的目标表盘（必须是普通可替换表盘）
     * @param uri          本地 .hwt
     */
    public void startWatchfaceReplacement(WatchDevice watchDevice, WatchInstalledItem target, Uri uri) {
        com.wlaz.Bridge.log("REPLACE step3 backend ENTER device=" + (watchDevice == null ? "null" : safeAddress(watchDevice.getAddress()))
                + " target=" + (target == null ? "null" : target.getIdentifier() + "/" + target.getVersion())
                + " rawType=" + (target == null ? "?" : Integer.toHexString(target.getRawType() & 0xFF))
                + " expand=" + (target == null ? "?" : String.valueOf(target.getRawExpandType())));
        Log.i(TAG, "watchface replace ENTER device=" + (watchDevice == null ? "null" : safeAddress(watchDevice.getAddress()))
                + " target=" + (target == null ? "null" : target.getIdentifier() + "/" + target.getVersion())
                + " uri=" + uri);
        if (watchDevice == null || target == null || uri == null) {
            com.wlaz.Bridge.log("REPLACE step3 REJECT missing arguments");
            Log.w(TAG, "watchface replace REJECT missing arguments");
            throw new UnsupportedOperationException("Missing replacement arguments");
        }
        String address = watchDevice.getAddress();
        String ready = requireReadyAddress(watchDevice);
        if (sessionForReadyDevice(watchDevice) == null) {
            com.wlaz.Bridge.log("REPLACE step3 REJECT session not ready");
            Log.w(TAG, "watchface replace REJECT session not ready");
            throw new UnsupportedOperationException("Native Huawei session is not ready");
        }
        if (watchfaceReplacementByAddress.get(ready) != null) {
            com.wlaz.Bridge.log("REPLACE step3 REJECT another replacement in progress");
            Log.w(TAG, "watchface replace REJECT another replacement in progress");
            throw new UnsupportedOperationException("Another watchface replacement is in progress");
        }
        // 目标可替换性：严格对齐原版 FacePolicy.eligible。
        // 放宽放行预置表盘会被固件回 100014 / need=0，实测已验证，不能再放宽。
        if (!WatchfaceReplace.isReplaceable(target)) {
            com.wlaz.Bridge.log("REPLACE step3 REJECT not replaceable type="
                    + Integer.toHexString(target.getRawType() & 0xFF)
                    + " expand=" + target.getRawExpandType()
                    + " why=" + WatchfaceReplace.describe(target));
            Log.w(TAG, "watchface replace REJECT not replaceable target=" + target.getIdentifier()
                    + " rawType=0x" + Integer.toHexString(target.getRawType() & 0xFF)
                    + " expand=" + target.getRawExpandType()
                    + " reason=" + WatchfaceReplace.describe(target));
            throw new UnsupportedOperationException("Target watchface is not replaceable: " + WatchfaceReplace.describe(target));
        }

        // 目标：原 ID + 原版本（baseline）
        String resourceId = WatchfaceReplace.resourceIdOf(target.getIdentifier());
        String baseline = target.getVersion();
        if (TextUtils.isEmpty(resourceId) || TextUtils.isEmpty(baseline)) {
            throw new UnsupportedOperationException("Target watchface has no resource id/version");
        }
        // 协议版本 = baseline 的最后一段十进制 +1
        String nextVersion = WatchfaceReplace.nextVersion(baseline);
        if (TextUtils.isEmpty(nextVersion)) {
            throw new UnsupportedOperationException("Unable to derive replacement version from " + baseline);
        }

        // 载荷：和「新增表盘」完全同一套检查，取 watchface.bin
        HuaweiInstallPackageInspector.InspectionResult inspect = this.installPackageInspector.inspect(uri);
        if (!inspect.isInstallable()) {
            throw new UnsupportedOperationException("Native Huawei install package is not supported");
        }
        if (inspect.type != HuaweiInstallPackageInspector.PackageType.WATCHFACE) {
            throw new UnsupportedOperationException("Selected file is not a watchface package");
        }

        final WatchfaceReplacement repl = new WatchfaceReplacement(
                new WatchfaceReplace(resourceId, baseline, nextVersion),
                inspect.payload, inspect.name);
        watchfaceReplacementByAddress.put(ready, repl);

        Log.i(TAG, "watchface replace start address=" + safeAddress(ready)
                + " target=" + target.getIdentifier() + " baseline=" + baseline
                + " next=" + nextVersion + " payloadBytes=" + inspect.payload.length);

        bumpManagementGeneration(ready);
        this.managementQuietUntilByAddress.remove(ready);
        this.watchfaceOperationRejectsByAddress.remove(ready);

        // 文件名必须用「资源ID_新版本」，原版 splitWatchfaceFileName 会把它拆回 tag1/tag2
        final String fileName = WatchfaceReplace.replacementFileName(resourceId, nextVersion);
        PendingWatchfaceOperation op = new PendingWatchfaceOperation(fileName, (byte) 1);
        this.pendingWatchfaceOperationsByAddress.put(ready, op);
        sendInstallProgress(0, true);
        sendInstallMessage("正在获取手表表盘列表…");
        if (address != null) pauseUploads(address, 0L);
        scheduleWatchfaceReplacementTimeout(ready, repl);
        // ── 与原版一致：事件驱动，不用固定延时 ──────────────────────
        // 原版 WatchfaceManager.beginReplacement() 的前置是 validate(face,epoch,REPLACEMENT_LIST_AGE)：
        //   listAt!=0 且 listEpoch==epoch 且 age<=5min 且 query==null 才准发 0x27/0x03，
        //   否则直接抛 FRESH_DEVICE_LIST_REQUIRED，一个字节都不发。
        // 所以这里只负责「请求一次新鲜列表」，把发 0x27/0x03 的时机交给列表回包
        // （handleWatchfaceList → checkFreshList 通过 → sendWatchfaceReplaceOperation）。
        // 实测固定 sleep 1500ms 会与队列里的 0x27/0x02 抢跑，列表还没回就发操作，
        // 固件没有本次列表上下文，只能回 need=0。
        repl.waitingPreList = true;
        // 原版 validate(face, epoch, REPLACEMENT_LIST_AGE=300000, false) 的前置：
        //   listAt!=0 && listEpoch==epoch && age<=5min && query==null，
        // 且目标 ID 必须在这份列表里「恰好出现一次、版本等于 baseline、仍然 eligible」。
        // 所以必须拿到一份「本次请求刚回来的」列表再发 27/03 —— 否则 ID 可能已不在手表上，
        // 固件会直接回 need=0（实测：幽灵 ID 2179253551 就得到 need=0）。
        boolean queued = enqueueManagementCommand(ready, new ManagementCommand(SERVICE_WATCHFACE,
                CMD_WATCHFACE_LIST, new HuaweiProtocol.Tlv().put(1, new byte[0]).put(6, (byte) 2),
                false, false), true);
        com.wlaz.Bridge.log("REPLACE step4 request fresh list queued=" + queued + " id=" + resourceId + " next=" + nextVersion);
        if (!queued) {
            failWatchfaceReplacement(ready, repl, "手表连接未就绪，无法获取表盘列表，请重连后重试");
        }
    }

    /** 列表复核通过后，真正发出 0x27/0x03（tag3=1 申请替换 / 上传新版本） */
    private void sendWatchfaceReplaceOperation(String address, WatchfaceReplacement repl) {
        if (watchfaceReplacementByAddress.get(address) != repl) return;
        if (repl.state.terminal()) return;
        String fileName = WatchfaceReplace.replacementFileName(repl.state.id(), repl.state.version());
        PendingWatchfaceOperation pending = new PendingWatchfaceOperation(fileName, (byte) 1);
        this.pendingWatchfaceOperationsByAddress.put(address, pending);
        Log.i(TAG, "watchface replace send operation address=" + safeAddress(address)
                + " id=" + repl.state.id() + " version=" + repl.state.version() + " file=" + fileName);
        com.wlaz.Bridge.log("REPLACE step6 TX 27/03 file=" + fileName);
        if (!writeHuaweiPacket(address, SERVICE_WATCHFACE, CMD_WATCHFACE_OPERATION,
                watchfaceOperationTlv(fileName, (byte) 1), true)) {
            failWatchfaceReplacement(address, repl, "替换请求发送失败，请重连手表后重试");
        }
    }

    /** 替换事务超时兜底：设备一直不回 0x27/0x03 就别一直挂着 */
    private void scheduleWatchfaceReplacementTimeout(final String address, final WatchfaceReplacement repl) {
        this.mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (watchfaceReplacementByAddress.get(address) != repl) return;
                if (!repl.state.terminal()) {
                    repl.state.timeout();
                    failWatchfaceReplacement(address, repl, "等待手表响应超时");
                }
            }
        }, WATCHFACE_CONFIRM_TIMEOUT_MS);
    }

    /** 替换失败收尾：统一广播给界面 */
    private void failWatchfaceReplacement(String address, WatchfaceReplacement repl, String message) {
        Log.w(TAG, "watchface replace failed address=" + safeAddress(address) + " " + repl.state.summary());
        WatchfaceReplacement cur = watchfaceReplacementByAddress.get(address);
        if (cur == repl) {
            watchfaceReplacementByAddress.remove(address);
            clearPendingWatchfaceOperation(address, null);
        }
        sendInstallMessage(message);
        sendInstallFailed(address, message);
    }

    /** 替换成功收尾 */
    private void succeedWatchfaceReplacement(String address, WatchfaceReplacement repl) {
        Log.i(TAG, "watchface replace done address=" + safeAddress(address) + " " + repl.state.summary());
        WatchfaceReplacement cur = watchfaceReplacementByAddress.get(address);
        if (cur == repl) watchfaceReplacementByAddress.remove(address);
        quietManagementRequests(address, 30000L);
        pauseUploads(address, 1500L);
        sendInstallProgress(100, false);
        sendInstallMessage("表盘替换完成，请在手表上确认画面");
        sendInstallFinished(address);
        requestWatchfaceListRepeated(address, new long[]{POST_WATCHFACE_OPERATION_REFRESH_DELAY_MS});
    }

    /** 事务中断（换设备 / 断链 / 用户取消） */
    private void finishWatchfaceReplacement(String address, WatchfaceReplacement repl) {
        WatchfaceReplacement cur = watchfaceReplacementByAddress.get(address);
        if (cur == repl) watchfaceReplacementByAddress.remove(address);
    }

    public void cancelWatchfaceReplacement(String address) {
        WatchfaceReplacement repl = watchfaceReplacementByAddress.get(address);
        if (repl != null) {
            repl.state.cancel();
            finishWatchfaceReplacement(address, repl);
        }
    }

    public boolean isWatchfaceReplacing(String address) {
        return watchfaceReplacementByAddress.containsKey(address);
    }

    /**
     * 设备确认 need=1 之后，用冻结的载荷走一次正常的 0x28 文件传输。
     *
     * 文件名用「资源ID_新版本」，这样设备会把这份内容登记到那个已有的资源槽上，
     * 而不是新建一个需要授权的新 ID。
     */
    private void startReplacementUpload(String address, WatchfaceReplacement repl) {
        Log.i(TAG, "watchface replace uploading address=" + safeAddress(address)
                + " file=" + repl.state.id() + "_" + repl.state.version()
                + " bytes=" + repl.payload.length);
        // fileType=1 是表盘；payload 是已冻结的原始 watchface.bin
        NativeUploadSession session = new NativeUploadSession(
                WatchfaceReplace.replacementFileName(repl.state.id(), repl.state.version()),
                (byte) 1, repl.payload, repl.sourceName);
        session.lastProgress = 0;
        // 这个 session 标记成替换事务的，0x28 结果回来时按替换状态机处理
        session.replacement = repl;
        repl.uploadSession = session;

        sendInstallMessage("手表已接受替换请求，开始传输表盘文件…");
        if (!startNativeUpload(address, session)) {
            repl.state.fail("REPLACEMENT_UPLOAD_NOT_STARTED", 0);
            failWatchfaceReplacement(address, repl, "无法启动表盘文件传输");
        }
    }

    /** 0x28 文件传输进度/结果，替换事务部分 */
    private void onReplacementFileResult(String address, WatchfaceReplacement repl, int raw) {
        Log.i(TAG, "watchface replace file result address=" + safeAddress(address)
                + " raw=" + raw + " " + repl.state.summary());
        repl.state.onNativeResult(raw);
        WatchfaceReplace.Phase phase = repl.state.phase();
        if (phase == WatchfaceReplace.Phase.FAILED) {
            failWatchfaceReplacement(address, repl, "表盘文件传输失败：" + repl.state.reason());
            return;
        }
        if (phase == WatchfaceReplace.Phase.LISTED) {
            succeedWatchfaceReplacement(address, repl);
        }
        // 其余（WAIT_REPORT）等 0x27/0x05 上报继续驱动
    }

    /** 0x27/0x05 表盘应用上报，替换事务部分 */
    private void onReplacementReport(String address, WatchfaceReplacement repl, Integer type, Long error) {
        Log.i(TAG, "watchface replace report address=" + safeAddress(address)
                + " type=" + type + " error=" + error + " " + repl.state.summary());
        WatchfaceReplace.Action action = repl.state.onReport(type, error);
        if (repl.state.phase() == WatchfaceReplace.Phase.FAILED) {
            failWatchfaceReplacement(address, repl, "表盘应用失败：" + repl.state.reason());
            return;
        }
        if (action == WatchfaceReplace.Action.ACTIVATE) {
            // 设备要求后置一次 0x27/0x03 确认（与删除后置同形，tag3=1）
            repl.waitingList = true;
            String fileName = WatchfaceReplace.replacementFileName(repl.state.id(), repl.state.version());
            PendingWatchfaceOperation op = new PendingWatchfaceOperation(fileName, (byte) 1);
            this.pendingWatchfaceOperationsByAddress.put(address, op);
            if (!writeHuaweiPacket(address, SERVICE_WATCHFACE, CMD_WATCHFACE_OPERATION,
                    watchfaceOperationTlv(fileName, (byte) 1), true)) {
                repl.state.fail("POST_OPERATION_SEND_FAILED", 0);
                failWatchfaceReplacement(address, repl, "后置确认发送失败");
            }
        }
    }

    /** 一个进行中的替换事务：状态机 + 冻结的载荷 */
    static final class WatchfaceReplacement {
        final WatchfaceReplace state;
        final byte[] payload;
        final String sourceName;
        /** 已发出前置 0x27/0x02，等这份新鲜列表回来复核后才准发 0x27/0x03 */
        boolean waitingPreList;
        /** 已发出后置 0x27/0x03，等新的列表 */
        boolean waitingList;
        /** 本次替换启动的上传会话（need=1 之后才有） */
        NativeUploadSession uploadSession;

        WatchfaceReplacement(WatchfaceReplace state, byte[] payload, String sourceName) {
            this.state = state;
            this.payload = payload;
            this.sourceName = sourceName;
        }
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public void submitAuthenticationPin(WatchDevice watchDevice, String str) {
        Log.i(TAG, "PIN submitted for native auth address=" + safeAddress(watchDevice.getAddress()) + " length=" + str.length());
        if (TextUtils.isEmpty(watchDevice.getAddress()) || TextUtils.isEmpty(str)) {
            throw new UnsupportedOperationException("Missing Huawei authentication PIN");
        }
        if (!isNativeDevice(watchDevice)) {
            throw new UnsupportedOperationException("Device belongs to another backend");
        }
        this.pendingPinsByAddress.put(watchDevice.getAddress(), str.getBytes(StandardCharsets.UTF_8));
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(watchDevice.getAddress());
        if (huaweiGattSession == null) {
            connect(watchDevice);
            return;
        }
        huaweiGattSession.pinCode = this.pendingPinsByAddress.get(watchDevice.getAddress());
        huaweiGattSession.authRequestSent = false;
        sendAuthIfPossible(watchDevice.getAddress());
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public boolean canInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context) {
        return sessionForReadyDevice(watchDevice) != null && this.installPackageInspector.canInstall(uri);
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public void startInstall(WatchDevice watchDevice, Uri uri, Bundle bundle, Context context) {
        HuaweiInstallPackageInspector.InspectionResult inspect = this.installPackageInspector.inspect(uri);
        if (!inspect.isInstallable()) {
            throw new UnsupportedOperationException("Native Huawei install package is not supported");
        }
        if (sessionForReadyDevice(watchDevice) == null) {
            sendInstallMessage("Native Huawei session is not ready; using compatibility installer.");
            throw new UnsupportedOperationException("Native Huawei session is not ready");
        }
        if (TextUtils.isEmpty(watchDevice.getAddress())) {
            throw new UnsupportedOperationException("Missing device address");
        }
        NativeUploadSession nativeUploadSession = new NativeUploadSession(inspect.payloadName, inspect.type == HuaweiInstallPackageInspector.PackageType.WATCHFACE ? (byte) 1 : CMD_WATCHFACE_NAME_INFO, inspect.payload, inspect.name);
        StringBuilder sb = new StringBuilder("install package inspected type=");
        sb.append(inspect.type);
        sb.append(" file=");
        sb.append(inspect.payloadName);
        sb.append(" sourceBytes=");
        sb.append(inspect.sourceSize);
        sb.append(" sourceSha256=");
        sb.append(inspect.sourceSha256);
        sb.append(" payloadBytes=");
        sb.append(inspect.payload.length);
        sb.append(" payloadSha256=");
        sb.append(inspect.payloadSha256);
        sb.append(" payloadMatchesSource=");
        sb.append(inspect.sourceSize == inspect.payload.length && inspect.sourceSha256.equals(inspect.payloadSha256));
        Log.i(TAG, sb.toString());
        bumpManagementGeneration(watchDevice.getAddress());
        nativeUploadSession.lastProgress = 0;
        sendInstallProgress(0, true);
        sendInstallMessage("Prepared " + inspect.payloadName + " (" + inspect.payload.length + " bytes).");
        this.pendingUploadsByAddress.put(watchDevice.getAddress(), nativeUploadSession);
        clearPendingManagementCommands(watchDevice.getAddress());
        if (isManagementBusy(watchDevice.getAddress()) || isUploadCoolingDown(watchDevice.getAddress())) {
            sendInstallMessage("Waiting for watch management request to finish before upload.");
            schedulePendingUploadStart(watchDevice.getAddress(), nativeUploadSession);
        } else {
            if (startNativeUpload(watchDevice.getAddress(), nativeUploadSession)) {
                return;
            }
            this.pendingUploadsByAddress.remove(watchDevice.getAddress());
            throw new UnsupportedOperationException("Unable to start native upload");
        }
    }

    @Override // com.watch.assistant.bridge.WatchBackend
    public Intent createAppManagerIntent(Context context, WatchDevice watchDevice, int i) {
        // 原版这里指向它自己的 WatchDeviceContentActivity。
        // 本工程不搬那个 Activity（依赖 androidx/recyclerview/glide 一大片），
        // 改用项目自己的 AppManagerActivity，参数名按它的约定传。
        Intent intent = new Intent(context, (Class<?>) com.wlaz.AppManagerActivity.class);
        intent.putExtra("addr", watchDevice.getAddress());
        intent.putExtra("type", i);
        return intent;
    }

    private boolean isNativeDevice(WatchDevice watchDevice) {
        Object nativeDevice = watchDevice.getNativeDevice();
        return nativeDevice == null || (nativeDevice instanceof BluetoothDevice);
    }

    private HuaweiGattSession sessionForReadyDevice(WatchDevice watchDevice) {
        HuaweiGattSession huaweiGattSession;
        if (isNativeDevice(watchDevice) && !TextUtils.isEmpty(watchDevice.getAddress()) && (huaweiGattSession = this.sessionsByAddress.get(watchDevice.getAddress())) != null && huaweiGattSession.authenticated && huaweiGattSession.protocolInitializationReady) {
            return huaweiGattSession;
        }
        return null;
    }

    private BluetoothDevice resolveBluetoothDevice(String str) {
        if (!TextUtils.isEmpty(str) && hasConnectPermission(this.appContext)) {
            BluetoothManager bluetoothManager = (BluetoothManager) this.appContext.getSystemService("bluetooth");
            BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
            if (adapter == null) {
                return null;
            }
            try {
                return adapter.getRemoteDevice(str);
            } catch (IllegalArgumentException | SecurityException unused) {
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public WatchDevice copyWithConnectionState(WatchDevice watchDevice, String str, boolean z, boolean z2, boolean z3, boolean z4) {
        return new WatchDevice(watchDevice.getNativeDevice(), watchDevice.getAddress(), watchDevice.getDisplayName(), watchDevice.getUid(), watchDevice.getSerialNumber(), str, watchDevice.getBatteryLevel(), watchDevice.getAvailableSpaceMb(), z, z2, z3, z4, watchDevice.supportsApps(), watchDevice.supportsWatchfaces(), watchDevice.supportsAppListFetching(), watchDevice.getInstalledAppsCount(), watchDevice.getInstalledWatchfacesCount(), watchDevice.getFingerprints());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendDeviceChanged(String str) {
        Intent intent = new Intent(WatchBridge.ACTION_DEVICE_CHANGED);
        intent.putExtra("device_address", str);
        LocalBroadcastManager.getInstance(this.appContext).sendBroadcast(intent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInstallProgress(int i, boolean z) {
        Intent intent = new Intent(WatchBridge.ACTION_INSTALL_PROGRESS);
        intent.putExtra("progress", i);
        intent.putExtra(WatchBridge.EXTRA_INDETERMINATE, z);
        LocalBroadcastManager.getInstance(this.appContext).sendBroadcast(intent);
    }

    private void sendUploadProgress(NativeUploadSession nativeUploadSession, int i, boolean z) {
        int max = Math.max(nativeUploadSession.lastProgress, Math.max(0, Math.min(100, i)));
        nativeUploadSession.lastProgress = max;
        sendInstallProgress(max, z);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInstallMessage(String str) {
        Intent intent = new Intent(WatchBridge.ACTION_INSTALL_MESSAGE);
        intent.putExtra(WatchBridge.EXTRA_MESSAGE, str);
        LocalBroadcastManager.getInstance(this.appContext).sendBroadcast(intent);
    }

    private void sendInstallFinished(String str) {
        Intent intent = new Intent(WatchBridge.ACTION_INSTALL_FINISHED);
        intent.putExtra("device_address", str);
        LocalBroadcastManager.getInstance(this.appContext).sendBroadcast(intent);
        sendDeviceChanged(str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInstallFailed(String str, String str2) {
        Intent intent = new Intent(WatchBridge.ACTION_INSTALL_FAILED);
        intent.putExtra("device_address", str);
        intent.putExtra(WatchBridge.EXTRA_MESSAGE, str2);
        LocalBroadcastManager.getInstance(this.appContext).sendBroadcast(intent);
        sendDeviceChanged(str);
    }

    private String displayName(BluetoothDevice bluetoothDevice) {
        if (!hasConnectPermission(this.appContext)) {
            return bluetoothDevice.getAddress();
        }
        try {
            String name = bluetoothDevice.getName();
            return TextUtils.isEmpty(name) ? bluetoothDevice.getAddress() : name;
        } catch (SecurityException unused) {
            return bluetoothDevice.getAddress();
        }
    }

    private List<String> fingerprints(BluetoothDevice bluetoothDevice) {
        ArrayList arrayList = new ArrayList();
        arrayList.add(bluetoothDevice.getAddress());
        if (hasConnectPermission(this.appContext)) {
            try {
                String name = bluetoothDevice.getName();
                if (!TextUtils.isEmpty(name)) {
                    arrayList.add(name.toUpperCase(Locale.US));
                }
            } catch (SecurityException unused) {
            }
        }
        return arrayList;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean hasConnectPermission(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0;
    }

    private boolean shouldUseRfcomm(BluetoothDevice bluetoothDevice) {
        if (!hasConnectPermission(this.appContext)) {
            return false;
        }
        try {
            ParcelUuid[] uuids = bluetoothDevice.getUuids();
            if (uuids != null) {
                for (ParcelUuid parcelUuid : uuids) {
                    if (parcelUuid != null && HUAWEI_RFCOMM_UUID.equals(parcelUuid.getUuid())) {
                        return true;
                    }
                }
            }
            String name = bluetoothDevice.getName();
            if (!TextUtils.isEmpty(name)) {
                if (name.toUpperCase(Locale.US).contains("HUAWEI WATCH")) {
                    return true;
                }
            }
        } catch (SecurityException unused) {
        }
        return false;
    }

    private boolean startRfcommConnection(final BluetoothDevice bluetoothDevice, final String str) {
        BluetoothManager bluetoothManager = (BluetoothManager) this.appContext.getSystemService("bluetooth");
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter != null) {
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException e) {
                // 没有 BLUETOOTH_SCAN 权限时 cancelDiscovery 会抛 SecurityException
                Log.w(TAG, "cancelDiscovery denied address=" + safeAddress(str), e);
            }
        }
        BluetoothSocket rfcommSocket;
        try {
            rfcommSocket = bluetoothDevice.createRfcommSocketToServiceRecord(HUAWEI_RFCOMM_UUID);
        } catch (IOException e) {
            Log.w(TAG, "RFCOMM socket create failed address=" + safeAddress(str), e);
            this.sessionsByAddress.remove(str);
            return false;
        }
        HuaweiGattSession forSocket = HuaweiGattSession.forSocket(str, rfcommSocket);
        forSocket.secretKey = this.authStore.getAuthKey(str);
        forSocket.pinCode = this.pendingPinsByAddress.get(str);
        this.sessionsByAddress.put(str, forSocket);
        Thread thread = new Thread(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$startRfcommConnection$0(str, bluetoothDevice);
            }
        }, "NativeHuaweiRfcommConnect");
        forSocket.socketThread = thread;
        thread.start();
        StringBuilder sb = new StringBuilder("RFCOMM connect scheduled address=");
        sb.append(safeAddress(str));
        sb.append(" androidBondState=");
        sb.append(bluetoothDevice.getBondState());
        sb.append(" hasStoredKey=");
        sb.append(forSocket.secretKey != null);
        sb.append(" storedKeyId=");
        sb.append(keyFingerprint(forSocket.secretKey));
        sb.append(" hasStoredPin=");
        sb.append(forSocket.pinCode != null);
        Log.i(TAG, sb.toString());
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: connectRfcommSocket, reason: merged with bridge method [inline-methods] */
    public void lambda$startRfcommConnection$0(final String str, BluetoothDevice bluetoothDevice) {
        final HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null) {
            return;
        }
        for (int i = 1; i <= 5; i++) {
            BluetoothSocket bluetoothSocket = huaweiGattSession.socket;
            if (i > 1) {
                try {
                    sleepBeforeRfcommRetry(str, i);
                    bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(HUAWEI_RFCOMM_UUID);
                    huaweiGattSession.socket = bluetoothSocket;
                } catch (IOException e) {
                    Log.w(TAG, "RFCOMM connect failed address=" + safeAddress(str) + " attempt=" + i + "/5", e);
                    closeSocketQuietly(bluetoothSocket);
                } catch (InterruptedException unused) {
                    Thread.currentThread().interrupt();
                    closeSocketQuietly(bluetoothSocket);
                    return;
                } catch (SecurityException e2) {
                    Log.w(TAG, "RFCOMM connect failed address=" + safeAddress(str) + " attempt=" + i + "/5", e2);
                    closeSocketQuietly(bluetoothSocket);
                }
            }
            final BluetoothSocket bluetoothSocket2 = bluetoothSocket;
            try {
                if (this.sessionsByAddress.get(str) != huaweiGattSession) {
                    closeSocketQuietly(bluetoothSocket2);
                    return;
                }
                Log.i(TAG, "RFCOMM connecting address=" + safeAddress(str) + " attempt=" + i + "/5");
                bluetoothSocket2.connect();
                huaweiGattSession.notificationsReady = true;
                WatchDevice findByAddress = this.store.findByAddress(str);
                if (findByAddress != null) {
                    this.store.upsert(copyWithConnectionState(findByAddress, "RFCOMM connected", true, false, false, false));
                    sendDeviceChanged(str);
                }
                Log.i(TAG, "RFCOMM connected address=" + safeAddress(str) + " attempt=" + i);
                huaweiGattSession.rfcommWriter = new HuaweiRfcommWriter(str, bluetoothSocket2, new HuaweiRfcommWriter.FailureListener() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda32
                    @Override // com.watch.assistant.bridge.HuaweiRfcommWriter.FailureListener
                    public final void onWriteFailure(Exception exc) {
                        NativeHuaweiWatchBackend.this.lambda$connectRfcommSocket$2(str, huaweiGattSession, bluetoothSocket2, exc);
                    }
                });
                Thread thread = new Thread(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda33
                    @Override // java.lang.Runnable
                    public final void run() {
                        NativeHuaweiWatchBackend.this.lambda$connectRfcommSocket$3(str, bluetoothSocket2);
                    }
                }, "NativeHuaweiRfcommRead");
                huaweiGattSession.socketReadThread = thread;
                thread.start();
                sendInitialNativeQueries(str);
                return;
            } catch (IOException e3) {
                bluetoothSocket = bluetoothSocket2;
                Log.w(TAG, "RFCOMM connect failed address=" + safeAddress(str) + " attempt=" + i + "/5", e3);
                closeSocketQuietly(bluetoothSocket);
            } catch (SecurityException e4) {
                bluetoothSocket = bluetoothSocket2;
                Log.w(TAG, "RFCOMM connect failed address=" + safeAddress(str) + " attempt=" + i + "/5", e4);
                closeSocketQuietly(bluetoothSocket);
            }
        }
        if (this.sessionsByAddress.get(str) == huaweiGattSession) {
            this.sessionsByAddress.remove(str);
        }
        WatchDevice findByAddress2 = this.store.findByAddress(str);
        if (findByAddress2 != null) {
            this.store.upsert(copyWithConnectionState(findByAddress2, "RFCOMM failed", false, false, false, false));
            sendDeviceChanged(str);
        }
        try {
            startGattConnection(bluetoothDevice, str);
        } catch (RuntimeException e5) {
            Log.w(TAG, "GATT fallback failed address=" + safeAddress(str), e5);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$connectRfcommSocket$2(final String str, final HuaweiGattSession huaweiGattSession, final BluetoothSocket bluetoothSocket, Exception exc) {
        this.mainHandler.post(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda27
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$connectRfcommSocket$1(str, huaweiGattSession, bluetoothSocket);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$connectRfcommSocket$1(String str, HuaweiGattSession huaweiGattSession, BluetoothSocket bluetoothSocket) {
        if (this.sessionsByAddress.get(str) == huaweiGattSession && huaweiGattSession.socket == bluetoothSocket) {
            failHuaweiTransport(str, "Huawei RFCOMM write failed.");
        }
    }

    private void sleepBeforeRfcommRetry(String str, int i) throws InterruptedException {
        if (i <= 1) {
            return;
        }
        Log.i(TAG, "RFCOMM retry delay address=" + safeAddress(str) + " delayMs=5000");
        Thread.sleep(RFCOMM_RETRY_DELAY_MS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:96:0x0169  */
    /* renamed from: readRfcommSocket, reason: merged with bridge method [inline-methods] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public void lambda$connectRfcommSocket$3(final String str, final BluetoothSocket bluetoothSocket) {
        byte[] bArr = new byte[1032];
        try {
            InputStream inputStream = bluetoothSocket.getInputStream();
            while (this.sessionsByAddress.containsKey(str)) {
                int read = inputStream.read(bArr);
                if (read < 0) {
                    throw new IOException("RFCOMM end of stream");
                }
                byte[] copyOf = Arrays.copyOf(bArr, read);
                Log.v(TAG, "RFCOMM rx address=" + safeAddress(str) + " bytes=" + read);
                onHuaweiData(str, copyOf);
            }
        } catch (IOException e) {
            Log.w(TAG, "RFCOMM read stopped address=" + safeAddress(str), e);
        } catch (SecurityException e) {
            Log.w(TAG, "RFCOMM read denied address=" + safeAddress(str), e);
        } catch (Throwable th) {
            Log.w(TAG, "RFCOMM read failed address=" + safeAddress(str), th);
        }
        cleanupRfcommSession(str, bluetoothSocket);
    }

    /**
     * RFCOMM 读循环结束（正常退出 / 断链 / 异常）后的统一收尾。
     *
     * ★ 不能照抄 jadx 输出的那段：它把 try/finally 还原成了
     *   `if (session == null && session.socket == socket)`（永假）+ synchronized 里
     *   `while(true){ throw th; }`（死循环），整段运行起来必然错误。
     *   这里按本类 closeNativeTransports 的正确写法重写：
     *   仍是同一会话且 socket 未换 → 关 writer、关 socket；
     *   正在上传 → 交给主线程上报传输中断；否则移除会话并标记断连。
     */
    private void cleanupRfcommSession(final String str, final BluetoothSocket bluetoothSocket) {
        final HuaweiGattSession session = this.sessionsByAddress.get(str);
        if (session == null || session.socket != bluetoothSocket) {
            return;
        }
        if (session.rfcommWriter != null) {
            session.rfcommWriter.close();
        }
        closeSocketQuietly(bluetoothSocket);
        if (this.uploadsByAddress.containsKey(str) || this.pendingUploadsByAddress.containsKey(str)) {
            this.mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    NativeHuaweiWatchBackend.this.lambda$readRfcommSocket$4(str, session, bluetoothSocket);
                }
            });
            return;
        }
        this.sessionsByAddress.remove(str, session);
        synchronized (session.pendingWrites) {
            session.closed = true;
            session.pendingWrites.clear();
            session.pendingWrites.notifyAll();
        }
        WatchDevice dev = this.store.findByAddress(str);
        if (dev != null && !dev.isConnecting()) {
            this.store.upsert(copyWithConnectionState(dev, "Disconnected", false, false, false, false));
            sendDeviceChanged(str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$readRfcommSocket$4(String str, HuaweiGattSession huaweiGattSession, BluetoothSocket bluetoothSocket) {
        if (this.sessionsByAddress.get(str) == huaweiGattSession && huaweiGattSession.socket == bluetoothSocket) {
            failHuaweiTransport(str, "Huawei RFCOMM connection was lost during upload.");
        }
    }

    private void startGattConnection(BluetoothDevice bluetoothDevice, String str) {
        BluetoothGatt connectGatt = bluetoothDevice.connectGatt(this.appContext, false, new NativeGattCallback(bluetoothDevice));
        if (connectGatt == null) {
            Log.w(TAG, "connectGatt returned null address=" + safeAddress(str));
            throw new UnsupportedOperationException("connectGatt returned null");
        }
        this.gattsByAddress.put(str, connectGatt);
        Log.d(TAG, "connectGatt accepted address=" + safeAddress(str));
    }

    private boolean closeNativeTransports(String str) {
        return closeNativeTransports(str, true);
    }

    private boolean closeNativeTransports(String str, boolean z) {
        clearManagementQueue(str);
        this.managementGenerationsByAddress.remove(str);
        this.managementCooldownUntilByAddress.remove(str);
        this.managementQuietUntilByAddress.remove(str);
        this.uploadCooldownUntilByAddress.remove(str);
        this.uploadsByAddress.remove(str);
        this.awaitingWatchfaceInstallsByAddress.remove(str);
        if (z) {
            this.pendingUploadsByAddress.remove(str);
        }
        this.pendingWatchfaceOperationsByAddress.remove(str);
        this.watchfaceOperationRejectsByAddress.remove(str);
        // 断链/换设备时把没走完的替换事务作废，避免下次连上还在旧事务里
        WatchfaceReplacement repl = this.watchfaceReplacementByAddress.remove(str);
        if (repl != null) {
            repl.state.cancel();
            Log.w(TAG, "watchface replace aborted by transport close address=" + safeAddress(str) + " " + repl.state.summary());
        }
        HuaweiGattSession remove = this.sessionsByAddress.remove(str);
        boolean z2 = false;
        if (remove != null) {
            if (remove.rfcommWriter != null) {
                remove.rfcommWriter.close();
            }
            synchronized (remove.pendingWrites) {
                remove.closed = true;
                remove.pendingWrites.clear();
                remove.writeInFlight = false;
                remove.inFlightWrite = null;
                remove.pendingWrites.notifyAll();
            }
            closeSocketQuietly(remove.socket);
            z2 = true;
        }
        BluetoothGatt remove2 = this.gattsByAddress.remove(str);
        if (remove2 == null) {
            return z2;
        }
        try {
            Log.d(TAG, "closing previous GATT address=" + safeAddress(str));
            remove2.disconnect();
            remove2.close();
        } catch (SecurityException unused) {
        }
        return true;
    }

    private static void closeSocketQuietly(BluetoothSocket bluetoothSocket) {
        if (bluetoothSocket == null) {
            return;
        }
        try {
            bluetoothSocket.close();
        } catch (IOException unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String safeAddress(String str) {
        if (TextUtils.isEmpty(str) || str.length() < 5) {
            return "unknown";
        }
        return "**:**:**:**:" + str.substring(str.length() - 5);
    }

    private static String prefix(String str, int i) {
        if (TextUtils.isEmpty(str)) {
            return "";
        }
        return str.length() <= i ? str : str.substring(0, i);
    }

    private static UUID uuid16(String str) {
        return UUID.fromString("0000" + str + "-0000-1000-8000-00805f9b34fb");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:26:0x00ba A[Catch: SecurityException -> 0x00e7, TryCatch #0 {SecurityException -> 0x00e7, blocks: (B:17:0x0087, B:19:0x0095, B:21:0x009b, B:24:0x00b0, B:26:0x00ba, B:27:0x00be, B:30:0x00dc, B:34:0x00a5), top: B:16:0x0087 }] */
    /* JADX WARN: Removed duplicated region for block: B:29:0x00d9  */
    /* JADX WARN: Removed duplicated region for block: B:33:0x00db  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public boolean prepareHuaweiSession(BluetoothGatt bluetoothGatt, String str) {
        boolean z;
        HuaweiGattSession huaweiGattSession;
        int writeDescriptor;
        BluetoothGattService service = bluetoothGatt.getService(HUAWEI_SERVICE_UUID);
        if (service == null) {
            Log.w(TAG, "Huawei FE86 service missing address=" + safeAddress(str));
            return false;
        }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(HUAWEI_WRITE_UUID);
        BluetoothGattCharacteristic characteristic2 = service.getCharacteristic(HUAWEI_READ_UUID);
        if (characteristic == null || characteristic2 == null) {
            StringBuilder sb = new StringBuilder("Huawei characteristics missing address=");
            sb.append(safeAddress(str));
            sb.append(" write=");
            sb.append(characteristic != null);
            sb.append(" read=");
            sb.append(characteristic2 != null);
            Log.w(TAG, sb.toString());
            return false;
        }
        HuaweiGattSession huaweiGattSession2 = new HuaweiGattSession(str, bluetoothGatt, characteristic, characteristic2);
        huaweiGattSession2.secretKey = this.authStore.getAuthKey(str);
        huaweiGattSession2.pinCode = this.pendingPinsByAddress.get(str);
        this.sessionsByAddress.put(str, huaweiGattSession2);
        StringBuilder sb2 = new StringBuilder("Huawei service ready address=");
        sb2.append(safeAddress(str));
        sb2.append(" hasStoredKey=");
        sb2.append(huaweiGattSession2.secretKey != null);
        sb2.append(" hasStoredPin=");
        sb2.append(huaweiGattSession2.pinCode != null);
        Log.i(TAG, sb2.toString());
        try {
            bluetoothGatt.requestMtu(247);
            bluetoothGatt.setCharacteristicNotification(characteristic2, true);
            BluetoothGattDescriptor descriptor = characteristic2.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    // API33+ writeDescriptor(desc, value) 返回 int 状态码：0 == SUCCESS。
                    // 原字节码：if (ret != 0) 跳 Lb0（视为已发起），否则 z=1（失败）
                    int ret = bluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    z = (ret == 0);
                } else {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    z = bluetoothGatt.writeDescriptor(descriptor);
                }
            } else {
                z = false;
            }
            // 原字节码在 descriptor==null 时同样会走到 Lb0：notificationsReady = !z
            huaweiGattSession = this.sessionsByAddress.get(str);
            if (huaweiGattSession != null) {
                huaweiGattSession.notificationsReady = !z;
            }
            StringBuilder sb3 = new StringBuilder("notification setup address=");
            sb3.append(safeAddress(str));
            sb3.append(" descriptorWriteStarted=");
            sb3.append(z);
            // 原字节码这里打的是 descriptorPresent = (descriptor != null)
            sb3.append(" descriptorPresent=");
            sb3.append(descriptor != null);
            Log.d(TAG, sb3.toString());
            return true;
        } catch (SecurityException unused) {
            Log.w(TAG, "notification setup failed: missing Bluetooth permission address=" + safeAddress(str));
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onHuaweiData(String str, byte[] bArr) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || bArr == null) {
            return;
        }
        huaweiGattSession.lastNotification = (byte[]) bArr.clone();
        for (byte[] bArr2 : completeHuaweiFrames(huaweiGattSession, bArr)) {
            HuaweiProtocol.Packet parse = HuaweiProtocol.parse(bArr2);
            if (parse != null) {
                HuaweiProtocol.Packet completeSlicedHuaweiPacket = completeSlicedHuaweiPacket(huaweiGattSession, parse);
                if (completeSlicedHuaweiPacket != null) {
                    handleParsedHuaweiPacket(str, huaweiGattSession, completeSlicedHuaweiPacket);
                }
            } else {
                Log.w(TAG, "discard malformed Huawei frame address=" + safeAddress(str) + " bytes=" + bArr2.length);
            }
        }
    }

    private HuaweiProtocol.Packet completeSlicedHuaweiPacket(HuaweiGattSession huaweiGattSession, HuaweiProtocol.Packet packet) {
        int i = packet.sliceFlag & 0xFF;
        if (i != 1 && i != 2 && i != 3) {
            huaweiGattSession.slicedPayload = null;
            return packet;
        }
        if (i == 1) {
            huaweiGattSession.slicedPayload = (byte[]) packet.payload.clone();
            return null;
        }
        if (huaweiGattSession.slicedPayload == null) {
            Log.w(TAG, "discard Huawei continuation slice without start");
            return null;
        }
        byte[] bArr = huaweiGattSession.slicedPayload;
        byte[] bArr2 = new byte[bArr.length + packet.payload.length];
        System.arraycopy(bArr, 0, bArr2, 0, bArr.length);
        System.arraycopy(packet.payload, 0, bArr2, bArr.length, packet.payload.length);
        if (i == 2) {
            huaweiGattSession.slicedPayload = bArr2;
            return null;
        }
        huaweiGattSession.slicedPayload = null;
        return HuaweiProtocol.parsePayload(bArr2);
    }

    /* JADX WARN: Removed duplicated region for block: B:13:0x0028 A[Catch: all -> 0x0072, LOOP:1: B:13:0x0028->B:17:0x0031, LOOP_START, PHI: r2
      0x0028: PHI (r2v3 int) = (r2v1 int), (r2v7 int) binds: [B:12:0x0026, B:17:0x0031] A[DONT_GENERATE, DONT_INLINE], TryCatch #0 {, blocks: (B:4:0x0006, B:6:0x000b, B:9:0x000f, B:11:0x0025, B:13:0x0028, B:15:0x002b, B:17:0x0031, B:19:0x0034, B:21:0x003a, B:32:0x0057, B:36:0x0064, B:38:0x0067, B:39:0x006e, B:40:0x0070, B:50:0x001e), top: B:3:0x0006 }] */
    /* JADX WARN: Removed duplicated region for block: B:38:0x0067 A[Catch: all -> 0x0072, TryCatch #0 {, blocks: (B:4:0x0006, B:6:0x000b, B:9:0x000f, B:11:0x0025, B:13:0x0028, B:15:0x002b, B:17:0x0031, B:19:0x0034, B:21:0x003a, B:32:0x0057, B:36:0x0064, B:38:0x0067, B:39:0x006e, B:40:0x0070, B:50:0x001e), top: B:3:0x0006 }] */
    /* JADX WARN: Removed duplicated region for block: B:44:0x006d  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    private List<byte[]> completeHuaweiFrames(HuaweiGattSession huaweiGattSession, byte[] bArr) {
        ArrayList arrayList = new ArrayList();
        synchronized (huaweiGattSession) {
            byte[] prev = huaweiGattSession.receiveBuffer;
            byte[] buf;
            if (prev != null && prev.length != 0) {
                buf = new byte[prev.length + bArr.length];
                System.arraycopy(prev, 0, buf, 0, prev.length);
                System.arraycopy(bArr, 0, buf, prev.length, bArr.length);
            } else {
                buf = (byte[]) bArr.clone();
            }
            int i = 0;
            while (i < buf.length) {
                while (i < buf.length && buf[i] != MAGIC_BYTE) {
                    i++;
                }
                int remain = buf.length - i;
                if (remain < 5) {
                    break;
                }
                int bodyLen = ((buf[i + 1] & 0xFF) << 8) | (buf[i + 2] & 0xFF);
                int total = bodyLen + 5;
                if (bodyLen < 3 || total > 65540) {
                    // 不是真正的帧头，跳过这个 0x5A 继续找
                    i++;
                    continue;
                }
                if (remain < total) {
                    // 帧还不完整，剩下的留在缓冲里等下一包
                    break;
                }
                arrayList.add(Arrays.copyOfRange(buf, i, i + total));
                i += total;
            }
            huaweiGattSession.receiveBuffer = i >= buf.length ? null : Arrays.copyOfRange(buf, i, buf.length);
        }
        return arrayList;
    }

    private void handleParsedHuaweiPacket(String str, HuaweiGattSession huaweiGattSession, HuaweiProtocol.Packet packet) {
        byte[] decryptTransaction;
        if (packet != null) {
            Log.v(TAG, "rx packet address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.commandId & 0xFF)) + " encrypted=" + packet.tlv.contains(124));
            if (packet.tlv.contains(124) && huaweiGattSession.secretKey != null) {
                byte[] first = packet.tlv.first(125);
                byte[] first2 = packet.tlv.first(126);
                if (first != null && first2 != null && (decryptTransaction = HuaweiCryptoSupport.decryptTransaction(first2, huaweiGattSession.secretKey, first, huaweiGattSession.encryptMethod, huaweiGattSession.deviceSupportType)) != null) {
                    Log.d(TAG, "transaction decrypt ok address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.commandId & 0xFF)));
                    packet = new HuaweiProtocol.Packet(packet.serviceId, packet.commandId, HuaweiProtocol.Tlv.parse(decryptTransaction), huaweiPayload(packet.serviceId, packet.commandId, decryptTransaction), packet.sliceFlag, packet.sliceIndex);
                }
            }
            huaweiGattSession.lastPacket = packet;
            handleHuaweiPacket(str, packet);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInitialNativeQueries(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession != null && !huaweiGattSession.initialQueriesSent) {
            huaweiGattSession.initialQueriesSent = true;
        } else if (huaweiGattSession != null) {
            return;
        }
        Log.d(TAG, "send LinkParams request address=" + safeAddress(str));
        writeHuaweiPacket(str, (byte) 1, (byte) 1, new HuaweiProtocol.Tlv().put(1, new byte[0]).put(2, new byte[0]).put(3, new byte[0]).put(4, new byte[0]));
    }

    private boolean requestNativeStatus(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            Log.d(TAG, "native status skipped before authentication address=" + safeAddress(str));
            return false;
        }
        boolean writeHuaweiPacket = writeHuaweiPacket(str, (byte) 1, CMD_BATTERY_LEVEL, new HuaweiProtocol.Tlv().put(1, new byte[0]));
        boolean writeHuaweiPacket2 = writeHuaweiPacket(str, SERVICE_MUSIC, CMD_MUSIC_INFO_PARAMS, new HuaweiProtocol.Tlv().put(1, new byte[0]).put(2, new byte[0]).put(3, new byte[0]));
        boolean writeHuaweiPacket3 = writeHuaweiPacket(str, SERVICE_MUSIC, CMD_EXTENDED_MUSIC_INFO_PARAMS, new HuaweiProtocol.Tlv().put(1, new byte[0]).put(2, new byte[0]).put(3, new byte[0]).put(4, new byte[0]).put(5, new byte[0]));
        boolean isNativeUploadOrInstallBusy = isNativeUploadOrInstallBusy(str);
        return writeHuaweiPacket || writeHuaweiPacket2 || writeHuaweiPacket3 || (!isNativeUploadOrInstallBusy && requestNativeAppList(str)) || (!isNativeUploadOrInstallBusy && lambda$requestNativeManagementLists$5(str));
    }

    private boolean requestNativeManagementLists(final String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        if (isNativeUploadOrInstallBusy(str)) {
            Log.d(TAG, "management list request skipped while upload is active address=" + safeAddress(str));
            return true;
        }
        if (isManagementQuiet(str)) {
            Log.d(TAG, "management list request skipped during quiet period address=" + safeAddress(str));
            return true;
        }
        boolean requestNativeAppList = requestNativeAppList(str);
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda22
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$requestNativeManagementLists$5(str);
            }
        }, 400L);
        return requestNativeAppList;
    }

    private void handleHuaweiPacket(String str, HuaweiProtocol.Packet packet) {
        if (packet.serviceId != 1) {
            if (packet.serviceId == 42 && packet.commandId == 3) {
                handleAppList(str, packet.tlv);
                completeManagementCommand(str, packet.serviceId, packet.commandId);
                return;
            }
            if (packet.serviceId == 42 && packet.commandId == 1) {
                Log.i(TAG, "app delete response address=" + safeAddress(str) + " tlv=" + tlvSummary(packet.tlv));
                handleAppDeleteResponse(str, packet.tlv);
                completeManagementCommand(str, packet.serviceId, packet.commandId);
                return;
            }
            if (packet.serviceId == 42 && packet.commandId == 2) {
                handleAppStatus(str, packet.tlv);
                return;
            }
            if (packet.serviceId == 39 && packet.commandId == 2) {
                handleWatchfaceList(str, packet.tlv);
                completeManagementCommand(str, packet.serviceId, packet.commandId);
                return;
            }
            if (packet.serviceId == 39 && packet.commandId == 3) {
                handleWatchfaceOperationResponse(str, packet.tlv);
                completeManagementCommand(str, packet.serviceId, packet.commandId);
                return;
            }
            if (packet.serviceId == 39 && packet.commandId == 5) {
                handleWatchfaceConfirm(str, packet.tlv);
                return;
            }
            if (packet.serviceId == 39 && packet.commandId == 6) {
                handleWatchfaceNames(str, packet.tlv);
                completeManagementCommand(str, packet.serviceId, packet.commandId);
                return;
            }
            if (packet.serviceId == 37 && (packet.commandId == 4 || packet.commandId == 13)) {
                handleMusicInfoParams(str, packet.commandId, packet.tlv);
                return;
            }
            if (packet.serviceId == 26 && packet.commandId == 5) {
                handleHuaweiAccount(str, packet.tlv);
                return;
            }
            if (packet.serviceId == 40) {
                handleFileUploadPacket(str, packet);
                return;
            }
            Log.d(TAG, "unhandled Huawei packet address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.commandId & 0xFF)) + " tlv=" + tlvSummary(packet.tlv));
            return;
        }
        if (packet.commandId == 1) {
            handleLinkParams(str, packet.tlv);
            requestDeviceStatusBeforeAuthentication(str);
            return;
        }
        if (packet.commandId == 22) {
            handleDeviceStatusBeforeAuthentication(str, packet.tlv);
            return;
        }
        if (packet.commandId == 2) {
            handleSupportedServices(str, packet.tlv);
            return;
        }
        if (packet.commandId == 3) {
            handleSupportedCommands(str, packet.tlv);
            return;
        }
        if (packet.commandId == 55) {
            handleExpandCapability(str, packet.tlv);
            return;
        }
        if (packet.commandId == 49) {
            handleSettingRelated(str, packet.tlv);
            return;
        }
        if (packet.commandId == 48) {
            handleAcceptAgreement(str, packet.tlv);
            return;
        }
        if (packet.commandId == 63) {
            handleReverseCapabilities(str, packet.tlv);
            return;
        }
        if (packet.commandId == 62) {
            handleSetupDeviceStatus(str, packet.tlv);
            return;
        }
        if (packet.commandId == 18) {
            handleActivityType(str, packet.tlv);
            return;
        }
        if (packet.commandId == 61) {
            handleWearStatus(str, packet.tlv);
            return;
        }
        if (packet.commandId == 53) {
            handleConnectStatus(str, packet.tlv);
            return;
        }
        if (packet.commandId == 7) {
            handleProductInfo(str, packet.tlv);
            return;
        }
        if (packet.commandId == 8) {
            handleBatteryLevel(str, packet.tlv);
            return;
        }
        if (packet.commandId == 16) {
            handlePhoneInfo(str, packet.tlv);
            return;
        }
        if (packet.commandId == 19) {
            handleAuthResponse(str, packet.tlv);
            return;
        }
        if (packet.commandId == 40) {
            handleHiChain(str, packet.tlv);
            return;
        }
        if (packet.commandId == 44) {
            handlePinCode(str, packet.tlv);
            return;
        }
        if (packet.commandId == 51) {
            handleSecurityNegotiation(str, packet.tlv);
            return;
        }
        if (packet.commandId == 15) {
            handleBondParams(str, packet.tlv);
            return;
        }
        if (packet.commandId == 14) {
            handleBondResponse(str);
            return;
        }
        if (packet.commandId == 56) {
            handlePermissionCheck(str, packet.tlv);
            return;
        }
        Log.d(TAG, "unhandled device-config packet address=" + safeAddress(str) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.commandId & 0xFF)) + " tlv=" + tlvSummary(packet.tlv));
    }

    private void handlePhoneInfo(String str, HuaweiProtocol.Tlv tlv) {
        if (tlv.size() == 1 && tlv.firstAsInteger(127, -1) == 100000) {
            Log.i(TAG, "phone info acknowledged address=" + safeAddress(str));
            return;
        }
        HuaweiProtocol.Tlv tlv2 = new HuaweiProtocol.Tlv();
        Iterator<Integer> it = tlv.tags().iterator();
        while (it.hasNext()) {
            int intValue = it.next().intValue();
            if (intValue != 0) {
                if (intValue != 2 && intValue != 4) {
                    if (intValue == 8) {
                        tlv2.put(intValue, stringBytes("14"));
                    } else if (intValue != 15) {
                        if (intValue == 17) {
                            tlv2.put(intValue, intBytes(1600008300));
                        } else if (intValue != 21) {
                            tlv2.put(intValue, (byte) 0);
                        }
                    }
                }
                tlv2.put(intValue, new byte[0]);
            }
        }
        Log.i(TAG, "phone info response address=" + safeAddress(str) + " requestedTags=" + tlv.tags() + " sent=" + (tlv2.size() > 0 && writeHuaweiPacket(str, (byte) 1, CMD_PHONE_INFO, tlv2)) + " tlv=" + tlvSummary(tlv2));
    }

    private void handleSupportedServices(String str, HuaweiProtocol.Tlv tlv) {
        byte[] first = tlv.first(2);
        if (first == null) {
            Log.w(TAG, "supported services response missing bitmap address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
            return;
        }
        HuaweiProtocol.Tlv tlv2 = new HuaweiProtocol.Tlv();
        addSupportedCommands(tlv2, 1);
        int min = Math.min(first.length, KNOWN_SUPPORTED_SERVICES.length);
        int i = 1;
        for (int i2 = 0; i2 < min; i2++) {
            if (first[i2] != 0 && addSupportedCommands(tlv2, KNOWN_SUPPORTED_SERVICES[i2] & 0xFF)) {
                i++;
            }
        }
        Log.i(TAG, "supported commands request address=" + safeAddress(str) + " activeServices=" + i + " sent=" + writeHuaweiPacket(str, (byte) 1, (byte) 3, new HuaweiProtocol.Tlv().put(129, tlv2.serialize())));
    }

    private boolean addSupportedCommands(HuaweiProtocol.Tlv tlv, int i) {
        for (byte[] bArr : KNOWN_SUPPORTED_COMMANDS) {
            byte b = bArr[0];
            if ((b & 0xFF) == i) {
                tlv.put(2, b);
                tlv.put(3, Arrays.copyOfRange(bArr, 1, bArr.length));
                return true;
            }
        }
        return false;
    }

    private void handleSupportedCommands(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        boolean isSupportedCommand = isSupportedCommand(tlv, (byte) 1, CMD_CONNECT_STATUS);
        huaweiGattSession.connectStatusSupported = isSupportedCommand;
        huaweiGattSession.expandCapabilitySupported = isSupportedCommand(tlv, (byte) 1, CMD_EXPAND_CAPABILITY);
        huaweiGattSession.settingRelatedSupported = isSupportedCommand(tlv, (byte) 1, CMD_SETTING_RELATED);
        huaweiGattSession.acceptAgreementSupported = isSupportedCommand(tlv, (byte) 1, CMD_ACCEPT_AGREEMENT);
        huaweiGattSession.reverseCapabilitiesSupported = isSupportedCommand(tlv, (byte) 1, (byte) 63);
        huaweiGattSession.activityTypeSupported = isSupportedCommand(tlv, (byte) 1, CMD_ACTIVITY_TYPE);
        huaweiGattSession.accountJudgmentSupported = isSupportedCommand(tlv, SERVICE_ACCOUNT, (byte) 5);
        huaweiGattSession.accountSwitchSupported = isSupportedCommand(tlv, SERVICE_ACCOUNT, CMD_WATCHFACE_NAME_INFO);
        huaweiGattSession.accountCommandSupported = huaweiGattSession.accountJudgmentSupported && huaweiGattSession.accountSwitchSupported;
        Log.i(TAG, "supported commands response address=" + safeAddress(str) + " expandCapabilitySupported=" + huaweiGattSession.expandCapabilitySupported + " settingRelatedSupported=" + huaweiGattSession.settingRelatedSupported + " acceptAgreementSupported=" + huaweiGattSession.acceptAgreementSupported + " reverseCapabilitiesSupported=" + huaweiGattSession.reverseCapabilitiesSupported + " activityTypeSupported=" + huaweiGattSession.activityTypeSupported + " accountJudgmentSupported=" + huaweiGattSession.accountJudgmentSupported + " accountSwitchSupported=" + huaweiGattSession.accountSwitchSupported + " accountCommandSupported=" + huaweiGattSession.accountCommandSupported + " connectStatusSupported=" + isSupportedCommand + " tlv=" + tlvSummary(tlv));
        sendExpandCapabilityIfNeeded(str, huaweiGattSession);
    }

    private void sendExpandCapabilityIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.expandCapabilitySupported) {
            sendHuaweiAccountIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.expandCapabilityRequestSent) {
            return;
        }
        huaweiGattSession.expandCapabilityRequestSent = writeHuaweiPacket(str, (byte) 1, CMD_EXPAND_CAPABILITY, new HuaweiProtocol.Tlv().put(1, new byte[0]));
        Log.i(TAG, "expand capability request address=" + safeAddress(str) + " sent=" + huaweiGattSession.expandCapabilityRequestSent);
        if (!huaweiGattSession.expandCapabilityRequestSent) {
            sendHuaweiAccountIfNeeded(str, huaweiGattSession);
        } else {
            postPairingFinalizationFallback(str, huaweiGattSession, new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda13
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendExpandCapabilityIfNeeded$6(huaweiGattSession, str);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendExpandCapabilityIfNeeded$6(HuaweiGattSession huaweiGattSession, String str) {
        if (huaweiGattSession.expandCapabilityReceived) {
            return;
        }
        Log.w(TAG, "expand capability response timeout address=" + safeAddress(str));
        sendHuaweiAccountIfNeeded(str, huaweiGattSession);
    }

    private void handleExpandCapability(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.expandCapabilityReceived = true;
        huaweiGattSession.expandCapabilities = tlv.first(1);
        huaweiGattSession.multiDeviceSupported = supportsExpandCapability(huaweiGattSession.expandCapabilities, 109);
        huaweiGattSession.accountPairingOptimizationSupported = supportsExpandCapability(huaweiGattSession.expandCapabilities, 172);
        StringBuilder sb = new StringBuilder("expand capability response address=");
        sb.append(safeAddress(str));
        sb.append(" bytes=");
        sb.append(huaweiGattSession.expandCapabilities == null ? 0 : huaweiGattSession.expandCapabilities.length);
        sb.append(" multiDeviceSupported=");
        sb.append(huaweiGattSession.multiDeviceSupported);
        sb.append(" accountPairingOptimizationSupported=");
        sb.append(huaweiGattSession.accountPairingOptimizationSupported);
        Log.i(TAG, sb.toString());
        sendHuaweiAccountIfNeeded(str, huaweiGattSession);
    }

    private void sendHuaweiAccountIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.accountCommandSupported) {
            Log.i(TAG, "Huawei account request skipped address=" + safeAddress(str) + " accountCommandSupported=false");
            sendSettingRelatedIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.accountRequestSent) {
            return;
        }
        String huaweiAccount = this.authStore.getHuaweiAccount(str);
        huaweiGattSession.accountRequestSent = writeHuaweiPacket(str, SERVICE_ACCOUNT, (byte) 5, initialPairingAccountTlv(huaweiGattSession.accountPairingOptimizationSupported, huaweiAccount));
        StringBuilder sb = new StringBuilder("Huawei account request address=");
        sb.append(safeAddress(str));
        sb.append(" accountCommandSupported=true accountPairingOptimizationSupported=");
        sb.append(huaweiGattSession.accountPairingOptimizationSupported);
        sb.append(" accountSentAsEmpty=");
        sb.append(TextUtils.isEmpty(huaweiAccount));
        sb.append(" storedAccountPresent=");
        sb.append(!TextUtils.isEmpty(huaweiAccount));
        sb.append(" sent=");
        sb.append(huaweiGattSession.accountRequestSent);
        Log.i(TAG, sb.toString());
        if (!huaweiGattSession.accountRequestSent) {
            sendSettingRelatedIfNeeded(str, huaweiGattSession);
        } else {
            postPairingFinalizationFallback(str, huaweiGattSession, new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda20
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendHuaweiAccountIfNeeded$7(huaweiGattSession, str);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendHuaweiAccountIfNeeded$7(HuaweiGattSession huaweiGattSession, String str) {
        if (huaweiGattSession.accountResponseReceived) {
            return;
        }
        Log.w(TAG, "Huawei account response timeout address=" + safeAddress(str));
        sendSettingRelatedIfNeeded(str, huaweiGattSession);
    }

    static HuaweiProtocol.Tlv initialPairingAccountTlv(boolean z, String str) {
        String trim = str != null ? str.trim() : "";
        HuaweiProtocol.Tlv tlv = new HuaweiProtocol.Tlv();
        if (trim.isEmpty()) {
            tlv.put(1, (byte) 0);
        } else {
            tlv.put(1, trim.getBytes(StandardCharsets.UTF_8));
        }
        if (z) {
            tlv.put(3, (byte) 1);
        }
        return tlv;
    }

    private void handleHuaweiAccount(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.accountResponseReceived = true;
        Log.i(TAG, "Huawei account response address=" + safeAddress(str) + " result=" + tlv.firstByte(2, -1) + " tlv=" + tlvSummary(tlv));
        sendSettingRelatedIfNeeded(str, huaweiGattSession);
    }

    private void sendSettingRelatedIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.settingRelatedSupported) {
            sendAcceptAgreementIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.settingRelatedRequestSent) {
            return;
        }
        huaweiGattSession.settingRelatedRequestSent = writeHuaweiPacket(str, (byte) 1, CMD_SETTING_RELATED, new HuaweiProtocol.Tlv().put(1, new byte[0]).put(2, new byte[0]).put(3, new byte[0]).put(4, new byte[0]).put(5, new byte[0]).put(6, new byte[0]));
        Log.i(TAG, "setting related request address=" + safeAddress(str) + " sent=" + huaweiGattSession.settingRelatedRequestSent);
        if (!huaweiGattSession.settingRelatedRequestSent) {
            sendAcceptAgreementIfNeeded(str, huaweiGattSession);
        } else {
            postPairingFinalizationFallback(str, huaweiGattSession, new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda12
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendSettingRelatedIfNeeded$8(huaweiGattSession, str);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendSettingRelatedIfNeeded$8(HuaweiGattSession huaweiGattSession, String str) {
        if (huaweiGattSession.settingRelatedReceived) {
            return;
        }
        Log.w(TAG, "setting related response timeout address=" + safeAddress(str));
        sendAcceptAgreementIfNeeded(str, huaweiGattSession);
    }

    private void handleSettingRelated(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.settingRelatedReceived = true;
        Log.i(TAG, "setting related response address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
        sendAcceptAgreementIfNeeded(str, huaweiGattSession);
    }

    private void sendAcceptAgreementIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.acceptAgreementSupported) {
            sendReverseCapabilitiesIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.acceptAgreementRequestSent) {
            return;
        }
        huaweiGattSession.acceptAgreementRequestSent = writeHuaweiPacket(str, (byte) 1, CMD_ACCEPT_AGREEMENT, HuaweiAuthProtocol.acceptAgreements(System.currentTimeMillis()));
        Log.i(TAG, "accept agreements request address=" + safeAddress(str) + " sent=" + huaweiGattSession.acceptAgreementRequestSent);
        if (!huaweiGattSession.acceptAgreementRequestSent) {
            sendReverseCapabilitiesIfNeeded(str, huaweiGattSession);
        } else {
            this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda23
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendAcceptAgreementIfNeeded$9(str, huaweiGattSession);
                }
            }, 1500L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendAcceptAgreementIfNeeded$9(String str, HuaweiGattSession huaweiGattSession) {
        if (this.sessionsByAddress.get(str) != huaweiGattSession || huaweiGattSession.acceptAgreementReceived) {
            return;
        }
        Log.w(TAG, "accept agreements response timeout; continuing pairing finalization address=" + safeAddress(str));
        sendReverseCapabilitiesIfNeeded(str, huaweiGattSession);
    }

    private void handleAcceptAgreement(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.acceptAgreementReceived = true;
        Log.i(TAG, "accept agreements response address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
        sendReverseCapabilitiesIfNeeded(str, huaweiGattSession);
    }

    private void sendReverseCapabilitiesIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.reverseCapabilitiesSupported) {
            sendSetupDeviceStatusIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.reverseCapabilitiesRequestSent) {
            return;
        }
        huaweiGattSession.reverseCapabilitiesRequestSent = writeHuaweiPacket(str, (byte) 1, (byte) 63, new HuaweiProtocol.Tlv().put(1, new byte[]{-3, -9, 115, 122}));
        Log.i(TAG, "reverse capabilities request address=" + safeAddress(str) + " sent=" + huaweiGattSession.reverseCapabilitiesRequestSent);
        if (!huaweiGattSession.reverseCapabilitiesRequestSent) {
            sendSetupDeviceStatusIfNeeded(str, huaweiGattSession);
        } else {
            postPairingFinalizationFallback(str, huaweiGattSession, new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda28
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendReverseCapabilitiesIfNeeded$10(huaweiGattSession, str);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendReverseCapabilitiesIfNeeded$10(HuaweiGattSession huaweiGattSession, String str) {
        if (huaweiGattSession.reverseCapabilitiesReceived) {
            return;
        }
        Log.w(TAG, "reverse capabilities response timeout address=" + safeAddress(str));
        sendSetupDeviceStatusIfNeeded(str, huaweiGattSession);
    }

    private void handleReverseCapabilities(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.reverseCapabilitiesReceived = true;
        Log.i(TAG, "reverse capabilities response address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
        sendSetupDeviceStatusIfNeeded(str, huaweiGattSession);
    }

    private void sendSetupDeviceStatusIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        String str2;
        if (!huaweiGattSession.multiDeviceSupported) {
            Log.i(TAG, "setup device status skipped; multi-device unsupported address=" + safeAddress(str));
            sendActivityTypeIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.setupDeviceStatusRequestSent) {
            return;
        }
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress != null && !TextUtils.isEmpty(findByAddress.getDisplayName())) {
            str2 = findByAddress.getDisplayName();
        } else {
            str2 = "HUAWEI Watch";
        }
        huaweiGattSession.setupDeviceStatusRequestSent = writeHuaweiPacket(str, (byte) 1, CMD_SETUP_DEVICE_STATUS, new HuaweiProtocol.Tlv().put(1, (byte) 1).put(2, stringBytes(str2)).put(3, (byte) 0));
        Log.i(TAG, "setup device status request address=" + safeAddress(str) + " sent=" + huaweiGattSession.setupDeviceStatusRequestSent + " deviceName=" + str2);
        if (!huaweiGattSession.setupDeviceStatusRequestSent) {
            sendActivityTypeIfNeeded(str, huaweiGattSession);
        } else {
            this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda15
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendSetupDeviceStatusIfNeeded$11(str, huaweiGattSession);
                }
            }, 1500L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendSetupDeviceStatusIfNeeded$11(String str, HuaweiGattSession huaweiGattSession) {
        if (this.sessionsByAddress.get(str) != huaweiGattSession || huaweiGattSession.setupDeviceStatusReceived) {
            return;
        }
        Log.w(TAG, "setup device status response timeout; continuing pairing finalization address=" + safeAddress(str));
        sendActivityTypeIfNeeded(str, huaweiGattSession);
    }

    private void handleSetupDeviceStatus(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.setupDeviceStatusReceived = true;
        Log.i(TAG, "setup device status response address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
        sendActivityTypeIfNeeded(str, huaweiGattSession);
    }

    private void sendActivityTypeIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.activityTypeSupported) {
            sendWearStatusIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.activityTypeRequestSent) {
            return;
        }
        huaweiGattSession.activityTypeRequestSent = writeHuaweiPacket(str, (byte) 1, CMD_ACTIVITY_TYPE, new HuaweiProtocol.Tlv().put(1, new byte[0]));
        Log.i(TAG, "activity type request address=" + safeAddress(str) + " sent=" + huaweiGattSession.activityTypeRequestSent);
        if (!huaweiGattSession.activityTypeRequestSent) {
            sendWearStatusIfNeeded(str, huaweiGattSession);
        } else {
            postPairingFinalizationFallback(str, huaweiGattSession, new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda26
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendActivityTypeIfNeeded$12(huaweiGattSession, str);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendActivityTypeIfNeeded$12(HuaweiGattSession huaweiGattSession, String str) {
        if (huaweiGattSession.activityTypeReceived) {
            return;
        }
        Log.w(TAG, "activity type response timeout address=" + safeAddress(str));
        sendWearStatusIfNeeded(str, huaweiGattSession);
    }

    private void handleActivityType(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.activityTypeReceived = true;
        Log.i(TAG, "activity type response address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
        sendWearStatusIfNeeded(str, huaweiGattSession);
    }

    private void sendWearStatusIfNeeded(final String str, final HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.multiDeviceSupported) {
            sendConnectStatusIfNeeded(str, huaweiGattSession);
            return;
        }
        if (huaweiGattSession.wearStatusRequestSent) {
            return;
        }
        huaweiGattSession.wearStatusRequestSent = writeHuaweiPacket(str, (byte) 1, (byte) 61, new HuaweiProtocol.Tlv().put(1, new byte[0]));
        Log.i(TAG, "wear status request address=" + safeAddress(str) + " sent=" + huaweiGattSession.wearStatusRequestSent);
        if (!huaweiGattSession.wearStatusRequestSent) {
            sendConnectStatusIfNeeded(str, huaweiGattSession);
        } else {
            postPairingFinalizationFallback(str, huaweiGattSession, new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda8
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$sendWearStatusIfNeeded$13(huaweiGattSession, str);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$sendWearStatusIfNeeded$13(HuaweiGattSession huaweiGattSession, String str) {
        if (huaweiGattSession.wearStatusReceived) {
            return;
        }
        Log.w(TAG, "wear status response timeout address=" + safeAddress(str));
        sendConnectStatusIfNeeded(str, huaweiGattSession);
    }

    private void handleWearStatus(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.wearStatusReceived = true;
        Log.i(TAG, "wear status response address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
        sendConnectStatusIfNeeded(str, huaweiGattSession);
    }

    private void postPairingFinalizationFallback(final String str, final HuaweiGattSession huaweiGattSession, final Runnable runnable) {
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda9
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$postPairingFinalizationFallback$14(str, huaweiGattSession, runnable);
            }
        }, 1500L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$postPairingFinalizationFallback$14(String str, HuaweiGattSession huaweiGattSession, Runnable runnable) {
        if (this.sessionsByAddress.get(str) != huaweiGattSession || huaweiGattSession.closed) {
            return;
        }
        runnable.run();
    }

    private boolean supportsExpandCapability(byte[] bArr, int i) {
        if (bArr != null && i >= 0 && i < bArr.length * 8) {
            int i2 = 1 << (i % 8);
            if ((bArr[i / 8] & i2) == i2) {
                return true;
            }
        }
        return false;
    }

    private void sendConnectStatusIfNeeded(String str, HuaweiGattSession huaweiGattSession) {
        if (!huaweiGattSession.connectStatusSupported) {
            markNativeProtocolReady(str, huaweiGattSession, "connect status unsupported");
            return;
        }
        if (huaweiGattSession.connectStatusRequestSent) {
            return;
        }
        huaweiGattSession.connectStatusRequestSent = writeHuaweiPacket(str, (byte) 1, CMD_CONNECT_STATUS, new HuaweiProtocol.Tlv().put(1, (byte) 1));
        Log.i(TAG, "connect status request address=" + safeAddress(str) + " sent=" + huaweiGattSession.connectStatusRequestSent);
        if (huaweiGattSession.connectStatusRequestSent) {
            return;
        }
        markNativeProtocolReady(str, huaweiGattSession, "connect status request rejected");
    }

    private void handleConnectStatus(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            return;
        }
        huaweiGattSession.connectStatusReceived = true;
        Log.i(TAG, "connect status response address=" + safeAddress(str) + " tlv=" + tlvSummary(tlv));
        markNativeProtocolReady(str, huaweiGattSession, "connect status acknowledged");
    }

    static boolean isSupportedCommand(HuaweiProtocol.Tlv tlv, byte b, byte b2) {
        int i;
        byte[] first = tlv.first(129);
        if (first == null) {
            return false;
        }
        HuaweiProtocol.Tlv parse = HuaweiProtocol.Tlv.parse(first);
        List<byte[]> all = parse.all(2);
        List<byte[]> all2 = parse.all(4);
        for (int i2 = 0; i2 < Math.min(all.size(), all2.size()); i2++) {
            byte[] bArr = all.get(i2);
            if (bArr.length != 0) {
                int i3 = bArr[0] & 0xFF;
                int i4 = b & 0xFF;
                if (i3 != i4) {
                    continue;
                } else {
                    byte[] bArr2 = all2.get(i2);
                    for (byte[] bArr3 : KNOWN_SUPPORTED_COMMANDS) {
                        if ((bArr3[0] & 0xFF) == i4) {
                            for (int i5 = 1; i5 < bArr3.length && i5 - 1 < bArr2.length; i5++) {
                                if (bArr3[i5] == b2) {
                                    // 原版此处读的是 bArr2 里该命令的“支持位”，下标 i5-1 才是对应位置
                                    return bArr2[i5 - 1] != 0;
                                }
                            }
                            return false;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void markNativeProtocolReady(String str, HuaweiGattSession huaweiGattSession, String str2) {
        if (huaweiGattSession.protocolInitializationReady) {
            return;
        }
        huaweiGattSession.protocolInitializationReady = true;
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress != null) {
            this.store.upsert(copyWithConnectionState(findByAddress, "Native initialized", true, true, false, false));
            sendDeviceChanged(str);
        }
        Log.i(TAG, "native protocol ready address=" + safeAddress(str) + " reason=" + str2);
        // 对齐 WristMirror WatchfaceManager.refreshActor()：
        //   每个连接批次先发一次 0x27/0x01 Wire.params()，紧接着 0x27/0x02 拉列表并缓存。
        // 腕镜的表盘列表是「连接就绪」时拉好缓存的，用户点替换时直接用这份 ≤5 分钟的列表，不再临时重拉。
        // 本工程此前只在替换流程里临时拉列表，固件缺少 27/01 建立的参数上下文，27/03 一律回 need=0。
        if (!huaweiGattSession.watchfaceParamsSent) {
            huaweiGattSession.watchfaceParamsSent = true;
            enqueueManagementCommand(str, new ManagementCommand(SERVICE_WATCHFACE,
                    (byte) 1, watchfaceParamsTlv(), false, false), true);
            Log.i(TAG, "watchface params 27/01 sent on ready address=" + safeAddress(str));
        }
        requestWatchfaceListRepeated(str);
        requestNativeStatus(str);
        resumeRecoveryAfterReconnect(str);
    }

    private void handlePermissionCheck(String str, HuaweiProtocol.Tlv tlv) {
        int firstShort = tlv.firstShort(1, 0);
        Log.i(TAG, "permission check address=" + safeAddress(str) + " permission=" + firstShort);
        writeHuaweiPacket(str, (byte) 1, CMD_PERMISSION_CHECK, new HuaweiProtocol.Tlv().put(1, shortBytes(firstShort)).put(2, shortBytes(0)));
    }

    private void handleLinkParams(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null) {
            return;
        }
        huaweiGattSession.protocolVersion = tlv.firstByte(1, huaweiGattSession.protocolVersion);
        huaweiGattSession.sliceSize = tlv.firstShort(2, huaweiGattSession.sliceSize);
        huaweiGattSession.mtu = tlv.firstShort(3, huaweiGattSession.mtu);
        huaweiGattSession.requestIntervalMs = tlv.firstShort(4, huaweiGattSession.requestIntervalMs);
        byte[] first = tlv.first(5);
        huaweiGattSession.authVersion = parseAuthVersion(first, huaweiGattSession.authVersion);
        huaweiGattSession.serverNonce = parseServerNonce(first);
        huaweiGattSession.deviceSupportType = tlv.firstByte(7, huaweiGattSession.deviceSupportType);
        huaweiGattSession.authAlgorithm = tlv.firstByte(8, huaweiGattSession.authAlgorithm);
        huaweiGattSession.bondState = tlv.firstByte(9, huaweiGattSession.bondState);
        huaweiGattSession.encryptMethod = tlv.firstByte(12, huaweiGattSession.encryptMethod);
        StringBuilder sb = new StringBuilder("LinkParams address=");
        sb.append(safeAddress(str));
        sb.append(" protocolVersion=");
        sb.append(huaweiGattSession.protocolVersion);
        sb.append(" sliceSize=");
        sb.append(huaweiGattSession.sliceSize);
        sb.append(" mtu=");
        sb.append(huaweiGattSession.mtu);
        sb.append(" intervalMs=");
        sb.append(huaweiGattSession.requestIntervalMs);
        sb.append(" authVersion=");
        sb.append(huaweiGattSession.authVersion);
        sb.append(" deviceSupportType=");
        sb.append(huaweiGattSession.deviceSupportType);
        sb.append(" authAlgorithm=");
        sb.append(huaweiGattSession.authAlgorithm);
        sb.append(" bondState=");
        sb.append(huaweiGattSession.bondState);
        sb.append(" encryptMethod=");
        sb.append(huaweiGattSession.encryptMethod);
        sb.append(" hasServerNonce=");
        sb.append(huaweiGattSession.serverNonce != null);
        Log.i(TAG, sb.toString());
    }

    private void requestDeviceStatusBeforeAuthentication(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || huaweiGattSession.deviceStatusRequested) {
            return;
        }
        huaweiGattSession.deviceStatusRequested = true;
        Log.i(TAG, "send pre-auth device status address=" + safeAddress(str));
        if (writeHuaweiPacket(str, (byte) 1, CMD_DEVICE_STATUS, new HuaweiProtocol.Tlv().put(1, new byte[0]))) {
            return;
        }
        huaweiGattSession.deviceStatusRequested = false;
        sendSecurityNegotiationIfNeeded(str);
    }

    private void handleDeviceStatusBeforeAuthentication(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null) {
            return;
        }
        huaweiGattSession.deviceStatusReceived = true;
        Log.i(TAG, "pre-auth device status address=" + safeAddress(str) + " status=" + tlv.firstByte(1, -1) + " tlv=" + tlvSummary(tlv));
        sendSecurityNegotiationIfNeeded(str);
    }

    private void sendSecurityNegotiationIfNeeded(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || huaweiGattSession.securityNegotiationSent) {
            return;
        }
        huaweiGattSession.authMode = HuaweiAuthProtocol.preferredAuthMode(huaweiGattSession.deviceSupportType);
        if (huaweiGattSession.authMode == 0) {
            Log.i(TAG, "security negotiation skipped address=" + safeAddress(str) + " authMode=none");
            sendAuthIfPossible(str);
            return;
        }
        huaweiGattSession.securityNegotiationSent = true;
        Log.i(TAG, "send security negotiation address=" + safeAddress(str) + " authMode=" + huaweiGattSession.authMode + " encryptMethod=" + huaweiGattSession.encryptMethod);
        writeHuaweiPacket(str, (byte) 1, CMD_SECURITY_NEGOTIATION, HuaweiAuthProtocol.securityNegotiation(huaweiGattSession.authMode, localAuthId(str), huaweiGattSession.encryptMethod));
    }

    private byte[] localAuthId(String str) {
        return this.authStore.getOrCreateHiChainId(str).getBytes(StandardCharsets.UTF_8);
    }

    private void handleSecurityNegotiation(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null) {
            return;
        }
        huaweiGattSession.securityAuthType = HuaweiAuthProtocol.parseSecurityAuthType(tlv);
        Log.i(TAG, "security negotiation response address=" + safeAddress(str) + " authMode=" + huaweiGattSession.authMode + " securityAuthType=" + huaweiGattSession.securityAuthType);
        if (HuaweiAuthProtocol.isFullHiChainAuthType(huaweiGattSession.securityAuthType)) {
            Log.i(TAG, "security negotiation selected full HiChain address=" + safeAddress(str));
            if (huaweiGattSession.secretKey == null && huaweiGattSession.pinCode == null) {
                requestPinCode(str);
                return;
            } else {
                startHiChain(str, huaweiGattSession.secretKey == null);
                return;
            }
        }
        if (HuaweiAuthProtocol.isHiChainLiteAuthType(huaweiGattSession.securityAuthType)) {
            Log.i(TAG, "security negotiation selected HiChain Lite address=" + safeAddress(str));
            sendAuthIfPossible(str);
            return;
        }
        Log.w(TAG, "unsupported security auth type address=" + safeAddress(str) + " authType=" + huaweiGattSession.securityAuthType + " tlv=" + tlvSummary(tlv));
        markAuthenticationRequired(str);
    }

    private void requestPinCode(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || huaweiGattSession.pinCodeRequestSent) {
            return;
        }
        huaweiGattSession.pinCodeRequestSent = true;
        Log.i(TAG, "send pincode request address=" + safeAddress(str));
        writeHuaweiPacket(str, (byte) 1, CMD_PIN_CODE, new HuaweiProtocol.Tlv().put(1, new byte[0]));
    }

    private void handlePinCode(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null) {
            return;
        }
        byte[] first = tlv.first(1);
        byte[] first2 = tlv.first(2);
        if (first == null || first2 == null) {
            StringBuilder sb = new StringBuilder("pincode response missing fields address=");
            sb.append(safeAddress(str));
            sb.append(" message=");
            sb.append(first != null);
            sb.append(" iv=");
            sb.append(first2 != null);
            Log.w(TAG, sb.toString());
            markAuthenticationRequired(str);
            return;
        }
        byte[] decryptPinCode = HuaweiCryptoSupport.decryptPinCode(huaweiGattSession.authVersion, huaweiGattSession.encryptMethod, huaweiGattSession.deviceSupportType, first, first2);
        if (decryptPinCode == null || decryptPinCode.length == 0) {
            Log.w(TAG, "pincode decrypt failed address=" + safeAddress(str));
            markAuthenticationRequired(str);
            return;
        }
        huaweiGattSession.pinCode = decryptPinCode;
        Log.i(TAG, "pincode accepted address=" + safeAddress(str) + " length=" + decryptPinCode.length);
        startHiChain(str, true);
    }

    private void startHiChain(String str, boolean z) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null) {
            return;
        }
        huaweiGattSession.hiChainSession = HuaweiAuthProtocol.newHiChainSession(z, localAuthId(str), huaweiGattSession.secretKey, huaweiGattSession.pinCode);
        StringBuilder sb = new StringBuilder("start HiChain address=");
        sb.append(safeAddress(str));
        sb.append(" firstConnection=");
        sb.append(z);
        sb.append(" hasStoredKey=");
        sb.append(huaweiGattSession.secretKey != null);
        sb.append(" secretKeyId=");
        sb.append(keyFingerprint(huaweiGattSession.secretKey));
        sb.append(" hasPin=");
        sb.append(huaweiGattSession.pinCode != null);
        Log.i(TAG, sb.toString());
        HuaweiProtocol.Tlv hiChainStepOne = HuaweiAuthProtocol.hiChainStepOne(huaweiGattSession.hiChainSession);
        if (hiChainStepOne == null) {
            Log.w(TAG, "HiChain step1 request unavailable address=" + safeAddress(str));
            markAuthenticationRequired(str);
            return;
        }
        writeHuaweiPacket(str, (byte) 1, CMD_HICHAIN, hiChainStepOne);
    }

    private void handleHiChain(final String str, HuaweiProtocol.Tlv tlv) {
        NativeHuaweiWatchBackend nativeHuaweiWatchBackend;
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession != null && huaweiGattSession.hiChainSession != null) {
            HuaweiAuthProtocol.HiChainResponse parseHiChainResponse = HuaweiAuthProtocol.parseHiChainResponse(tlv);
            HuaweiProtocol.Tlv tlv2 = null;
            if (parseHiChainResponse == null || parseHiChainResponse.errorCode != 0) {
                byte[] first = tlv.first(4);
                String firstString = tlv.firstString(1);
                StringBuilder sb = new StringBuilder("HiChain response error address=");
                sb.append(safeAddress(str));
                sb.append(" responsePresent=");
                sb.append(parseHiChainResponse != null);
                sb.append(" errorCode=");
                sb.append(parseHiChainResponse != null ? parseHiChainResponse.errorCode : -1);
                sb.append(" type=");
                sb.append((first == null || first.length <= 0) ? "missing" : String.format(Locale.US, "0x%02X", Byte.valueOf(first[0])));
                sb.append(" dataLength=");
                sb.append(firstString != null ? firstString.length() : -1);
                sb.append(" dataPrefix=");
                sb.append(prefix(firstString, 96));
                Log.w(TAG, sb.toString());
                if (huaweiGattSession.hiChainSession != null && huaweiGattSession.hiChainSession.operationCode == 2 && huaweiGattSession.secretKey != null) {
                    BluetoothDevice resolveBluetoothDevice = resolveBluetoothDevice(str);
                    Log.w(TAG, "stored HiChain key rejected; retrying protocol pairing address=" + safeAddress(str) + " androidBondState=" + (resolveBluetoothDevice != null ? resolveBluetoothDevice.getBondState() : 10));
                    this.authStore.clearPairingCredentials(str);
                    this.pendingPinsByAddress.remove(str);
                    huaweiGattSession.secretKey = null;
                    huaweiGattSession.pinCode = null;
                    huaweiGattSession.hiChainSession = null;
                    huaweiGattSession.pinCodeRequestSent = false;
                    WatchDevice findByAddress = this.store.findByAddress(str);
                    closeNativeTransports(str);
                    if (findByAddress != null) {
                        nativeHuaweiWatchBackend = this;
                        this.store.upsert(nativeHuaweiWatchBackend.copyWithConnectionState(findByAddress, "Rebinding authentication", false, false, true, false));
                        sendDeviceChanged(str);
                    } else {
                        nativeHuaweiWatchBackend = this;
                    }
                    nativeHuaweiWatchBackend.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda14
                        @Override // java.lang.Runnable
                        public final void run() {
                            NativeHuaweiWatchBackend.this.lambda$handleHiChain$15(str);
                        }
                    }, RECOVERY_RETRY_DELAY_MS);
                    return;
                }
                markAuthenticationRequired(str);
                return;
            }
            Log.i(TAG, "HiChain response address=" + safeAddress(str) + " step=" + ((int) parseHiChainResponse.step) + " errorCode=" + parseHiChainResponse.errorCode + " operationCode=" + ((int) huaweiGattSession.hiChainSession.operationCode));
            if (parseHiChainResponse.step == 1) {
                if (!HuaweiAuthProtocol.handleHiChainStepOne(huaweiGattSession.hiChainSession, parseHiChainResponse)) {
                    Log.w(TAG, "HiChain step1 validation failed address=" + safeAddress(str));
                    markAuthenticationRequired(str);
                    return;
                }
                tlv2 = HuaweiAuthProtocol.hiChainStepTwo(huaweiGattSession.hiChainSession);
            } else if (parseHiChainResponse.step == 2) {
                if (!HuaweiAuthProtocol.handleHiChainStepTwo(huaweiGattSession.hiChainSession, parseHiChainResponse)) {
                    Log.w(TAG, "HiChain step2 validation failed address=" + safeAddress(str));
                    markAuthenticationRequired(str);
                    return;
                }
                tlv2 = HuaweiAuthProtocol.hiChainStepThree(huaweiGattSession.hiChainSession);
            } else if (parseHiChainResponse.step == 3) {
                if (!HuaweiAuthProtocol.handleHiChainStepThree(huaweiGattSession.hiChainSession, parseHiChainResponse)) {
                    Log.w(TAG, "HiChain step3 validation failed address=" + safeAddress(str));
                    markAuthenticationRequired(str);
                    return;
                }
                huaweiGattSession.secretKey = huaweiGattSession.hiChainSession.savedSecretKey;
                if (huaweiGattSession.secretKey != null) {
                    this.authStore.putAuthKey(str, huaweiGattSession.secretKey);
                }
                byte[] authKey = this.authStore.getAuthKey(str);
                StringBuilder sb2 = new StringBuilder("HiChain step3 accepted address=");
                sb2.append(safeAddress(str));
                sb2.append(" savedSecretKey=");
                sb2.append(huaweiGattSession.secretKey != null);
                sb2.append(" savedKeyId=");
                sb2.append(keyFingerprint(huaweiGattSession.secretKey));
                sb2.append(" persisted=");
                sb2.append(Arrays.equals(huaweiGattSession.secretKey, authKey));
                Log.i(TAG, sb2.toString());
                tlv2 = HuaweiAuthProtocol.hiChainStepFour(huaweiGattSession.hiChainSession);
            } else if (parseHiChainResponse.step == 4) {
                if (huaweiGattSession.hiChainSession.operationCode == 1) {
                    Log.i(TAG, "HiChain bind phase requested address=" + safeAddress(str));
                    startHiChain(str, false);
                    return;
                }
                byte[] finalHiChainReturnKey = HuaweiAuthProtocol.finalHiChainReturnKey(huaweiGattSession.hiChainSession);
                if (finalHiChainReturnKey != null) {
                    huaweiGattSession.secretKey = finalHiChainReturnKey;
                    Log.i(TAG, "HiChain bind return key activated for current session address=" + safeAddress(str) + " sessionKeyId=" + keyFingerprint(finalHiChainReturnKey) + " persistedKeyId=" + keyFingerprint(this.authStore.getAuthKey(str)));
                }
                if (!huaweiGattSession.authenticated && huaweiGattSession.secretKey != null) {
                    huaweiGattSession.authenticated = true;
                }
                if (huaweiGattSession.authenticated) {
                    huaweiGattSession.transactionCrypted = true;
                    Log.i(TAG, "native authenticated address=" + safeAddress(str) + " transactionCrypted=" + huaweiGattSession.transactionCrypted);
                    completePostAuthentication(str);
                    return;
                }
            }
            if (tlv2 != null) {
                writeHuaweiPacket(str, (byte) 1, CMD_HICHAIN, tlv2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$handleHiChain$15(String str) {
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress == null) {
            return;
        }
        try {
            connect(findByAddress);
        } catch (RuntimeException e) {
            Log.w(TAG, "protocol pairing retry could not start address=" + safeAddress(str), e);
        }
    }

    private int parseAuthVersion(byte[] bArr, int i) {
        return (bArr == null || bArr.length <= 1) ? i : bArr[1] & 0xFF;
    }

    private byte[] parseServerNonce(byte[] bArr) {
        if (bArr == null || bArr.length < 18) {
            return null;
        }
        byte[] bArr2 = new byte[16];
        System.arraycopy(bArr, 2, bArr2, 0, 16);
        return bArr2;
    }

    private void sendAuthIfPossible(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || huaweiGattSession.serverNonce == null || huaweiGattSession.authRequestSent) {
            return;
        }
        StringBuilder sb = new StringBuilder("send auth request address=");
        sb.append(safeAddress(str));
        sb.append(" authVersion=");
        sb.append(huaweiGattSession.authVersion);
        sb.append(" authAlgorithm=");
        sb.append(huaweiGattSession.authAlgorithm);
        sb.append(" authMode=");
        sb.append(huaweiGattSession.authMode);
        sb.append(" hasAuthKey=");
        sb.append(authKeyForDigest(huaweiGattSession) != null);
        Log.i(TAG, sb.toString());
        byte[] nonce = HuaweiCryptoSupport.nonce();
        HuaweiCryptoSupport.AuthDigest createAuthDigest = HuaweiCryptoSupport.createAuthDigest(huaweiGattSession.authVersion, huaweiGattSession.authAlgorithm, huaweiGattSession.authMode, authKeyForDigest(huaweiGattSession), huaweiGattSession.serverNonce, nonce);
        if (createAuthDigest == null) {
            Log.w(TAG, "auth digest unavailable address=" + safeAddress(str));
            markAuthenticationRequired(str);
            return;
        }
        huaweiGattSession.authRequestSent = true;
        huaweiGattSession.firstKey = createAuthDigest.firstKey;
        huaweiGattSession.expectedAuthResponse = createAuthDigest.expectedResponse;
        byte[] bArr = new byte[18];
        bArr[0] = (byte) ((huaweiGattSession.authVersion >> 8) & 255);
        bArr[1] = (byte) (huaweiGattSession.authVersion & 255);
        System.arraycopy(nonce, 0, bArr, 2, nonce.length);
        HuaweiProtocol.Tlv put = new HuaweiProtocol.Tlv().put(1, createAuthDigest.challenge).put(2, bArr);
        if (huaweiGattSession.authMode == 2) {
            put.put(3, (byte) huaweiGattSession.authAlgorithm);
        }
        writeHuaweiPacket(str, (byte) 1, CMD_AUTH, put);
    }

    private void markAuthenticationRequired(String str) {
        Log.i(TAG, "authentication required address=" + safeAddress(str));
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress != null) {
            this.store.upsert(copyWithConnectionState(findByAddress, "Authentication required", true, false, false, true));
            sendDeviceChanged(str);
        }
    }

    private void handleAuthResponse(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || huaweiGattSession.expectedAuthResponse == null) {
            return;
        }
        byte[] first = tlv.first(1);
        if (first != null && Arrays.equals(first, huaweiGattSession.expectedAuthResponse)) {
            huaweiGattSession.authenticated = true;
            huaweiGattSession.secretKey = huaweiGattSession.firstKey;
            huaweiGattSession.transactionCrypted = huaweiGattSession.secretKey != null;
            Log.i(TAG, "auth response accepted address=" + safeAddress(str) + " transactionCrypted=" + huaweiGattSession.transactionCrypted);
            sendBondParamsIfPossible(str);
            completePostAuthentication(str);
            return;
        }
        StringBuilder sb = new StringBuilder("auth response mismatch address=");
        sb.append(safeAddress(str));
        sb.append(" actualPresent=");
        sb.append(first != null);
        Log.w(TAG, sb.toString());
    }

    private void sendBondParamsIfPossible(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || huaweiGattSession.bondParamsSent) {
            return;
        }
        huaweiGattSession.bondParamsSent = true;
        Log.i(TAG, "send bond params address=" + safeAddress(str));
        writeHuaweiPacket(str, (byte) 1, CMD_BOND_PARAMS, HuaweiAuthProtocol.bondParams(localAuthId(str), str.getBytes(StandardCharsets.UTF_8)));
    }

    private void handleBondParams(String str, HuaweiProtocol.Tlv tlv) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null) {
            return;
        }
        huaweiGattSession.bondStatus = HuaweiAuthProtocol.parseBondParamsStatus(tlv);
        huaweiGattSession.encryptionCounter = HuaweiAuthProtocol.parseBondParamsEncryptionCounter(tlv);
        Log.i(TAG, "bond params response address=" + safeAddress(str) + " bondStatus=" + huaweiGattSession.bondStatus + " encryptionCounter=" + huaweiGattSession.encryptionCounter);
        sendBondIfPossible(str);
    }

    private void sendBondIfPossible(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || huaweiGattSession.bondSent || huaweiGattSession.secretKey == null) {
            return;
        }
        HuaweiProtocol.Tlv bond = HuaweiAuthProtocol.bond(huaweiGattSession.authVersion, huaweiGattSession.authMode, huaweiGattSession.encryptMethod, str, localAuthId(str), huaweiGattSession.secretKey, huaweiGattSession.firstKey, huaweiGattSession.encryptionCounter);
        if (bond == null) {
            Log.w(TAG, "bond request unavailable address=" + safeAddress(str));
        } else {
            huaweiGattSession.bondSent = true;
            Log.i(TAG, "send bond request address=" + safeAddress(str));
            writeHuaweiPacket(str, (byte) 1, CMD_BOND, bond);
        }
    }

    private void handleBondResponse(String str) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession != null) {
            huaweiGattSession.bonded = true;
            if (huaweiGattSession.secretKey != null) {
                this.authStore.putAuthKey(str, huaweiGattSession.secretKey);
            }
            Log.i(TAG, "bond response accepted address=" + safeAddress(str));
        }
    }

    private byte[] authKeyForDigest(HuaweiGattSession huaweiGattSession) {
        if (huaweiGattSession.authMode != 2) {
            return huaweiGattSession.secretKey;
        }
        if (huaweiGattSession.authVersion == 2) {
            return huaweiGattSession.secretKey;
        }
        return HuaweiCryptoSupport.pinDigest(huaweiGattSession.pinCode);
    }

    private void completePostAuthentication(String str) {
        String str2;
        int i;
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated || huaweiGattSession.postAuthInitializationSent) {
            return;
        }
        huaweiGattSession.postAuthInitializationSent = true;
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress != null && !TextUtils.isEmpty(findByAddress.getDisplayName())) {
            str2 = findByAddress.getDisplayName();
        } else {
            str2 = "HUAWEI Watch";
        }
        int offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
        if (offset < 0) {
            i = ((-offset) / 3600) + 128;
        } else {
            i = offset / 3600;
        }
        Log.i(TAG, "post-auth initialization address=" + safeAddress(str) + " productInfo=" + writeHuaweiPacket(str, (byte) 1, CMD_PRODUCT_INFO, productInfoRequestTlv()) + " time=" + writeHuaweiPacket(str, (byte) 1, (byte) 5, new HuaweiProtocol.Tlv().put(1, intBytes((int) (System.currentTimeMillis() / 1000))).put(2, new byte[]{(byte) i, (byte) ((offset / 60) % 60)})) + " battery=" + writeHuaweiPacket(str, (byte) 1, CMD_BATTERY_LEVEL, new HuaweiProtocol.Tlv().put(1, new byte[0])) + " supportedServices=" + writeHuaweiPacket(str, (byte) 1, (byte) 2, new HuaweiProtocol.Tlv().put(1, KNOWN_SUPPORTED_SERVICES)) + " pairingFinalization=deferred deviceName=" + str2);
        if (findByAddress != null) {
            this.store.upsert(copyWithConnectionState(findByAddress, "Finishing native initialization", true, false, false, false));
            sendDeviceChanged(str);
        }
    }

    private HuaweiProtocol.Tlv productInfoRequestTlv() {
        int[] iArr = {1, 2, 7, 9, 10, 17, 18, 22, 26, 29, 30, 31, 32, 33, 34, 35};
        HuaweiProtocol.Tlv tlv = new HuaweiProtocol.Tlv();
        for (int i = 0; i < 16; i++) {
            tlv.put(iArr[i], new byte[0]);
        }
        return tlv;
    }

    private void handleProductInfo(String str, HuaweiProtocol.Tlv tlv) {
        String displayName;
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress == null) {
            return;
        }
        String firstString = tlv.firstString(9);
        String serialNumber = !TextUtils.isEmpty(firstString) ? firstString : findByAddress.getSerialNumber();
        String firstString2 = tlv.firstString(10);
        String firstString3 = tlv.firstString(17);
        if (TextUtils.isEmpty(firstString3)) {
            displayName = !TextUtils.isEmpty(firstString2) ? firstString2 : findByAddress.getDisplayName();
        } else {
            displayName = firstString3;
        }
        if (shouldKeepCurrentDisplayName(findByAddress.getDisplayName(), str)) {
            displayName = findByAddress.getDisplayName();
        }
        String str2 = displayName;
        String buildUid = WatchDeviceIdentity.buildUid(serialNumber, str);
        if (TextUtils.isEmpty(buildUid)) {
            buildUid = findByAddress.getUid();
        }
        String str3 = buildUid;
        StringBuilder sb = new StringBuilder("product info address=");
        sb.append(safeAddress(str));
        sb.append(" model=");
        if (TextUtils.isEmpty(firstString2)) {
            firstString2 = "unknown";
        }
        sb.append(firstString2);
        sb.append(" deviceName=");
        if (TextUtils.isEmpty(firstString3)) {
            firstString3 = "unknown";
        }
        sb.append(firstString3);
        sb.append(" hasSerial=");
        sb.append(!TextUtils.isEmpty(firstString));
        sb.append(" storedSerial=");
        sb.append(!TextUtils.isEmpty(serialNumber));
        Log.i(TAG, sb.toString());
        this.store.upsert(new WatchDevice(findByAddress.getNativeDevice(), findByAddress.getAddress(), str2, str3, serialNumber, findByAddress.getStateLabel(), findByAddress.getBatteryLevel(), findByAddress.getAvailableSpaceMb(), findByAddress.isConnected(), findByAddress.isInitialized(), findByAddress.isConnecting(), findByAddress.isAuthenticationRequired(), findByAddress.supportsApps(), findByAddress.supportsWatchfaces(), findByAddress.supportsAppListFetching(), findByAddress.getInstalledAppsCount(), findByAddress.getInstalledWatchfacesCount(), findByAddress.getFingerprints()));
        sendDeviceChanged(str);
    }

    private boolean shouldKeepCurrentDisplayName(String str, String str2) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        return !TextUtils.equals(str, str2);
    }

    private void handleBatteryLevel(String str, HuaweiProtocol.Tlv tlv) {
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress == null) {
            return;
        }
        int firstByte = tlv.firstByte(1, findByAddress.getBatteryLevel());
        if (firstByte < 0 || firstByte > 100) {
            Log.w(TAG, "battery level ignored address=" + safeAddress(str) + " level=" + firstByte + " tlv=" + tlvSummary(tlv));
            return;
        }
        Log.i(TAG, "battery level address=" + safeAddress(str) + " level=" + firstByte);
        this.store.upsert(new WatchDevice(findByAddress.getNativeDevice(), findByAddress.getAddress(), findByAddress.getDisplayName(), findByAddress.getUid(), findByAddress.getSerialNumber(), findByAddress.getStateLabel(), firstByte, findByAddress.getAvailableSpaceMb(), findByAddress.isConnected(), findByAddress.isInitialized(), findByAddress.isConnecting(), findByAddress.isAuthenticationRequired(), findByAddress.supportsApps(), findByAddress.supportsWatchfaces(), findByAddress.supportsAppListFetching(), findByAddress.getInstalledAppsCount(), findByAddress.getInstalledWatchfacesCount(), findByAddress.getFingerprints()));
        sendDeviceChanged(str);
    }

    private void handleMusicInfoParams(String str, byte b, HuaweiProtocol.Tlv tlv) {
        int firstAsInteger;
        if (this.store.findByAddress(str) == null) {
            return;
        }
        if (b == 13) {
            firstAsInteger = tlv.firstAsInteger(1, -1);
        } else {
            firstAsInteger = tlv.firstAsInteger(2, -1);
        }
        if (firstAsInteger < 0) {
            return;
        }
        int normalizeAvailableSpaceMb = normalizeAvailableSpaceMb(firstAsInteger);
        Log.i(TAG, "music storage address=" + safeAddress(str) + " raw=" + firstAsInteger + " availableMb=" + normalizeAvailableSpaceMb + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(b & 0xFF)));
        this.store.updateAvailableSpace(str, normalizeAvailableSpaceMb);
        sendDeviceChanged(str);
    }

    private int normalizeAvailableSpaceMb(int i) {
        if (i <= 0) {
            return -1;
        }
        return i > 1048576 ? Math.max(1, i / 1048576) : i;
    }

    private void handleAppDeleteResponse(String str, HuaweiProtocol.Tlv tlv) {
        int firstInt = tlv.firstAsInteger(127, 0);
        String currentAppDeletePackage = currentAppDeletePackage(str);
        StringBuilder sb = new StringBuilder("app delete result address=");
        sb.append(safeAddress(str));
        sb.append(" package=");
        sb.append(!TextUtils.isEmpty(currentAppDeletePackage) ? currentAppDeletePackage : "unknown");
        sb.append(" result=");
        sb.append(firstInt);
        Log.i(TAG, sb.toString());
        if (firstInt == 0 || firstInt == 100000) {
            this.recoveryActionsByAddress.remove(str);
            if (!TextUtils.isEmpty(currentAppDeletePackage)) {
                markAppDeletedLocally(str, currentAppDeletePackage);
            }
            requestAppListRepeated(str);
            return;
        }
        if (isRecoverableWatchBusyResult(firstInt) && !TextUtils.isEmpty(currentAppDeletePackage)) {
            recoverAfter100004(str, PendingRecoveryAction.appDelete(currentAppDeletePackage));
            return;
        }
        requestAppListRepeated(str);
        Toast.makeText(this.appContext, "删除应用失败: " + firstInt, 0).show();
    }

    private void handleAppList(String str, HuaweiProtocol.Tlv tlv) {
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress == null) {
            return;
        }
        List<WatchInstalledItem> parseInstalledApps = parseInstalledApps(tlv);
        if (parseInstalledApps.isEmpty() && !tlv.contains(129) && findByAddress.getInstalledAppsCount() >= 0) {
            Log.d(TAG, "app list empty response ignored address=" + safeAddress(str));
            return;
        }
        Log.i(TAG, "app list address=" + safeAddress(str) + " apps=" + parseInstalledApps.size());
        this.store.updateInstalledItems(str, 1, parseInstalledApps);
        sendDeviceChanged(str);
    }

    private void handleAppStatus(String str, HuaweiProtocol.Tlv tlv) {
        int firstByte = tlv.firstByte(1, -1);
        Log.i(TAG, "app status response address=" + safeAddress(str) + " status=0x" + String.format(Locale.US, "%02X", Integer.valueOf(firstByte & 255)) + " tlv=" + tlvSummary(tlv));
        if (firstByte == 102 || firstByte == APP_STATUS_REFRESH_DELETED) {
            requestAppListRepeated(str);
            return;
        }
        if (firstByte == 104) {
            requestNativeStatusDelayed(str);
        } else if (firstByte >= 0) {
            requestNativeStatusRepeated(str);
        } else {
            requestNativeStatusDelayed(str);
        }
    }

    private void handleWatchfaceList(String str, HuaweiProtocol.Tlv tlv) {
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress == null) {
            return;
        }
        List<WatchInstalledItem> parseInstalledWatchfaces = parseInstalledWatchfaces(tlv);
        if (parseInstalledWatchfaces.isEmpty() && !tlv.contains(129) && findByAddress.getInstalledWatchfacesCount() >= 0) {
            Log.d(TAG, "watchface list empty response ignored address=" + safeAddress(str));
            // 前置列表被当成空回包忽略时，替换事务不能一直挂着（原版同样会走 list 超时）
            WatchfaceReplacement pendingReplace = watchfaceReplacementByAddress.get(str);
            if (pendingReplace != null && pendingReplace.waitingPreList) {
                pendingReplace.waitingPreList = false;
                failWatchfaceReplacement(str, pendingReplace, "手表返回的表盘列表为空，无法确认替换槽，请刷新后重试");
            }
            return;
        }
        Log.i(TAG, "watchface list address=" + safeAddress(str) + " watchfaces=" + parseInstalledWatchfaces.size());
        for (WatchInstalledItem wfi : parseInstalledWatchfaces) {
            Log.i(TAG, "watchface item id=" + wfi.getIdentifier() + " ver=" + wfi.getVersion()
                    + " rawType=0x" + Integer.toHexString(wfi.getRawType() & 0xFF)
                    + " expand=" + wfi.getRawExpandType()
                    + " replaceable=" + WatchfaceReplace.isReplaceableLenient(wfi)
                    + "/strict=" + WatchfaceReplace.isReplaceable(wfi)
                    + " (" + WatchfaceReplace.describe(wfi) + ")");
        }
        this.store.updateInstalledItems(str, 2, parseInstalledWatchfaces);
        sendDeviceChanged(str);

        // ── 替换法：刷新列表后核对同一 ID 的新版本是否真的出现了 ──
        WatchfaceReplacement repl = watchfaceReplacementByAddress.get(str);
        if (repl != null && !repl.state.terminal()) {
            // 前置阶段：这次列表就是「新鲜列表」，通过原版 validate 的复核后才发 0x27/0x03
            if (repl.waitingPreList) {
                repl.waitingPreList = false;
                String problem = checkPreListForReplacement(repl, parseInstalledWatchfaces);
                com.wlaz.Bridge.log("REPLACE step5 prelist result=" + (problem == null ? "OK" : problem));
                if (problem != null) {
                    failWatchfaceReplacement(str, repl, problem);
                    return;
                }
                sendWatchfaceReplaceOperation(str, repl);
                return;
            }
            repl.state.onList(parseInstalledWatchfaces, 1, repl.state.phase() == WatchfaceReplace.Phase.WAIT_LIST);
            Log.i(TAG, "watchface replace list check address=" + safeAddress(str) + " " + repl.state.summary());
            if (repl.state.phase() == WatchfaceReplace.Phase.LISTED) {
                succeedWatchfaceReplacement(str, repl);
            }
        }

        requestWatchfaceNamesDelayed(str, parseInstalledWatchfaces);
    }

    /**
     * 对应原版 WatchfaceManager.validate(face, epoch, REPLACEMENT_LIST_AGE)：
     * 目标必须在**这份刚到的列表**里恰好出现一次、版本等于 baseline、仍然 eligible，
     * 否则原版直接抛 FRESH_DEVICE_LIST_REQUIRED / SELECTED_FACE_CHANGED，不发 0x27/0x03。
     * 返回 null 表示通过，否则返回给用户看的原因。
     */
    private String checkPreListForReplacement(WatchfaceReplacement repl, List<WatchInstalledItem> faces) {
        String id = repl.state.id();
        String baseline = repl.state.baseline();
        int count = 0;
        WatchInstalledItem found = null;
        for (WatchInstalledItem item : faces) {
            if (item == null) continue;
            if (id.equals(WatchfaceReplace.resourceIdOf(item.getIdentifier()))) {
                count++;
                found = item;
            }
        }
        Log.i(TAG, "watchface replace pre-list check id=" + id + " baseline=" + baseline
                + " matches=" + count + " entries=" + faces.size());
        if (count == 0) return "手表当前列表里找不到该表盘（可能已被更换或删除），请刷新后重试";
        if (count > 1) return "手表列表里该表盘 ID 重复，无法确定替换目标，请刷新后重试";
        if (!baseline.equals(found.getVersion())) {
            return "表盘版本已变化（" + baseline + " → " + found.getVersion() + "），请刷新后重试";
        }
        if (!WatchfaceReplace.isReplaceable(found)) {
            return "该表盘当前状态不允许替换：" + WatchfaceReplace.describe(found);
        }
        return null;
    }

    private void handleWatchfaceOperationResponse(String str, HuaweiProtocol.Tlv tlv) {
        int firstInt = tlv.firstAsInteger(127, 0);
        Log.i(TAG, "watchface operation response address=" + safeAddress(str) + " result=" + firstInt + " tlv=" + tlvSummary(tlv));

        // ── 替换法优先：这个事务是我们自己发的，回包里 tag4 才是「要不要文件」 ──
        WatchfaceReplacement repl = watchfaceReplacementByAddress.get(str);
        if (repl != null && !repl.state.terminal()) {
            // 原版要求 tag1/tag2 与本次事务的 id/version 精确匹配，不匹配一律算 late 包丢弃，
            // 否则上一个事务的迟到回包会把本次状态机带歪（例如把 need=0 当成本次结论）。
            String echoId = tlv.firstString(1);
            String echoVer = tlv.firstString(2);
            boolean echoMatches = TextUtils.isEmpty(echoId)
                    || (repl.state.id().equals(echoId) && repl.state.version().equals(echoVer));
            Log.i(TAG, "watchface replace operation response address=" + safeAddress(str)
                    + " echo=" + echoId + "/" + echoVer + " owned=" + echoMatches);
            if (!echoMatches) {
                return;
            }
            boolean hasError = tlv.contains(127);
            boolean hasNeed = tlv.contains(4);
            // 注意：need 在回包里只有 1 字节（0x01），必须用 firstAsInteger。
            // 用 firstInt 会因「长度<4 直接返回默认值 0」把 need=1 读成 need=0，
            // 导致手表明确要求传文件时被判成「手表不要文件，替换失败」。
            Long err = hasError ? Long.valueOf(tlv.firstAsInteger(127, 0)) : null;
            Long need = hasNeed ? Long.valueOf(tlv.firstAsInteger(4, 0)) : null;
            com.wlaz.Bridge.log("REPLACE step7 RX 27/03 error=" + err + " need=" + need
                    + " echo=" + echoId + "/" + echoVer + " owned=" + echoMatches
                    + " needLen=" + (hasNeed ? tlv.first(4).length : -1)
                    + " rawTags=" + tlv.tags());
            Log.i(TAG, "watchface replace operation response address=" + safeAddress(str)
                    + " error=" + err + " need=" + need + " " + repl.state.summary());
            WatchfaceReplace.Action action = repl.state.onOperationResponse(err, need);
            if (repl.state.phase() == WatchfaceReplace.Phase.NO_TRANSFER) {
                // 设备说这条不需要新文件：按原版语义只报结果，不重试、不硬传
                failWatchfaceReplacement(str, repl, "手表未要求新文件（need=0），该表盘槽不支持替换");
                return;
            }
            if (repl.state.phase() == WatchfaceReplace.Phase.FAILED) {
                failWatchfaceReplacement(str, repl, "替换被拒绝：" + repl.state.reason());
                return;
            }
            if (action == WatchfaceReplace.Action.UPLOAD) {
                startReplacementUpload(str, repl);
                return;
            }
            if (action == WatchfaceReplace.Action.QUERY) {
                requestWatchfaceListRepeated(str, new long[]{POST_WATCHFACE_OPERATION_REFRESH_DELAY_MS});
                return;
            }
            return;
        }

        PendingWatchfaceOperation currentWatchfaceOperation = currentWatchfaceOperation(str);
        if (firstInt == 0 || firstInt == 100000) {
            this.recoveryActionsByAddress.remove(str);
            this.watchfaceOperationRejectsByAddress.remove(str);
            clearPendingWatchfaceOperation(str, currentWatchfaceOperation);
            if (currentWatchfaceOperation != null) {
                if (currentWatchfaceOperation.operation == 1) {
                    markWatchfaceActiveLocally(str, currentWatchfaceOperation.fileName);
                } else if (currentWatchfaceOperation.operation == 2) {
                    markWatchfaceDeletedLocally(str, currentWatchfaceOperation.fileName);
                }
                if (currentWatchfaceOperation.uploadActivation) {
                    this.awaitingWatchfaceInstallsByAddress.remove(str);
                    quietManagementRequests(str, 30000L);
                    pauseUploads(str, 1500L);
                    sendInstallProgress(100, false);
                    sendInstallMessage("Watchface install complete.");
                    sendInstallFinished(str);
                }
            }
            if (currentWatchfaceOperation == null || !currentWatchfaceOperation.uploadActivation) {
                requestWatchfaceListRepeated(str, new long[]{POST_WATCHFACE_OPERATION_REFRESH_DELAY_MS});
                return;
            }
            return;
        }
        if (currentWatchfaceOperation != null) {
            clearPendingWatchfaceOperation(str, currentWatchfaceOperation);
            if (currentWatchfaceOperation.uploadActivation) {
                this.awaitingWatchfaceInstallsByAddress.remove(str);
                if (isRecoverableWatchBusyResult(firstInt) && recoverAfter100004(str, PendingRecoveryAction.watchfaceOperation(currentWatchfaceOperation))) {
                    return;
                }
                failRecoveredAction(str, PendingRecoveryAction.watchfaceOperation(currentWatchfaceOperation), uploadErrorMessage(firstInt));
                return;
            }
            bumpManagementGeneration(str);
            pauseManagementRequests(str, 8000L);
            pauseUploads(str, 1500L);
            clearPendingManagementCommands(str);
            Log.w(TAG, "watchface operation rejected address=" + safeAddress(str) + " file=" + currentWatchfaceOperation.fileName + " operation=" + ((int) currentWatchfaceOperation.operation) + " result=" + firstInt);
            if (isRecoverableWatchBusyResult(firstInt)) {
                recoverAfter100004(str, PendingRecoveryAction.watchfaceOperation(currentWatchfaceOperation));
            }
        }
    }

    private List<WatchInstalledItem> parseInstalledApps(HuaweiProtocol.Tlv tlv) {
        ArrayList arrayList = new ArrayList();
        for (HuaweiProtocol.Tlv tlv2 : tlv.objects(129, 130)) {
            String firstString = tlv2.firstString(3);
            String firstString2 = tlv2.firstString(4);
            int firstAsInteger = tlv2.firstAsInteger(5, 0);
            String firstString3 = tlv2.firstString(6);
            int firstAsInteger2 = tlv2.firstAsInteger(9, 0);
            int firstByte = tlv2.firstByte(13, -1);
            String str = !TextUtils.isEmpty(firstString) ? firstString : firstString3;
            if (!TextUtils.isEmpty(str)) {
                String str2 = !TextUtils.isEmpty(firstString3) ? firstString3 : str;
                ArrayList arrayList2 = new ArrayList();
                if (!TextUtils.equals(str2, firstString) && !TextUtils.isEmpty(firstString)) {
                    arrayList2.add(firstString);
                }
                if (firstAsInteger2 > 0) {
                    arrayList2.add("versionCode " + firstAsInteger2);
                }
                if (firstByte >= 0) {
                    arrayList2.add("type " + firstByte);
                }
                arrayList.add(new WatchInstalledItem(1, str, str2, firstString2, firstAsInteger, joinDetails(arrayList2), null));
            }
        }
        return arrayList;
    }

    private List<WatchInstalledItem> parseInstalledWatchfaces(HuaweiProtocol.Tlv tlv) {
        ArrayList arrayList = new ArrayList();
        for (HuaweiProtocol.Tlv tlv2 : tlv.objects(129, 130)) {
            String firstString = tlv2.firstString(3);
            if (!TextUtils.isEmpty(firstString)) {
                String firstString2 = tlv2.firstString(4);
                String watchfaceFullFileName = watchfaceFullFileName(firstString, firstString2);
                int firstByte = tlv2.firstByte(5, 0);
                int firstPresentInteger = firstPresentInteger(tlv2, 6, 8, 9);
                int firstByte2 = tlv2.firstByte(7, 0);
                boolean z = (firstByte & 1) != 0;
                boolean z2 = ((firstByte >> 1) & 1) != 0;
                ArrayList arrayList2 = new ArrayList();
                if (z) {
                    arrayList2.add("当前表盘");
                }
                if (z2) {
                    arrayList2.add("预置");
                }
                if (((firstByte >> 3) & 1) != 0) {
                    arrayList2.add("可编辑");
                }
                if (((firstByte >> 4) & 1) != 0) {
                    arrayList2.add("视频");
                }
                if (((firstByte >> 5) & 1) != 0) {
                    arrayList2.add("照片");
                }
                if (((firstByte >> 6) & 1) != 0) {
                    arrayList2.add("试用");
                }
                if (((firstByte >> 7) & 1) != 0) {
                    arrayList2.add("万花筒");
                }
                ArrayList arrayList3 = new ArrayList();
                arrayList3.add(firstString);
                if (firstByte2 > 0) {
                    arrayList3.add("expandedType " + firstByte2);
                }
                arrayList.add(new WatchInstalledItem(2, watchfaceFullFileName, firstString, firstString2, firstPresentInteger, joinDetails(arrayList3), joinDetails(arrayList2), z, z2, firstByte, firstByte2));
            }
        }
        return arrayList;
    }

    private void requestWatchfaceNames(String str, List<WatchInstalledItem> list) {
        if (list.isEmpty()) {
            return;
        }
        HuaweiProtocol.Tlv tlv = new HuaweiProtocol.Tlv();
        Iterator<WatchInstalledItem> it = list.iterator();
        while (it.hasNext()) {
            tlv.put(131, new HuaweiProtocol.Tlv().put(4, stringBytes(watchfaceFileName(it.next().getIdentifier()))).serialize());
        }
        // tag1 是语言码：WristMirror WireNames 里明确 LANGUAGE_OTHER=1、LANGUAGE_CHINESE=2。
        // 之前写死 1，手表就按「其它语言」返回英文名；中文系统下必须发 2 才拿到中文表盘名。
        byte language = isChineseLocale() ? (byte) 2 : (byte) 1;
        enqueueManagementCommand(str, new ManagementCommand(SERVICE_WATCHFACE, CMD_WATCHFACE_NAME_INFO, new HuaweiProtocol.Tlv().put(1, language).put(130, tlv.serialize()), false, false), false);
    }

    private void handleWatchfaceNames(String str, HuaweiProtocol.Tlv tlv) {
        List<HuaweiProtocol.Tlv> objects = tlv.objects(130, 131);
        if (objects.isEmpty()) {
            return;
        }
        List<WatchInstalledItem> installedItems = this.store.getInstalledItems(str, 2);
        if (installedItems.isEmpty()) {
            return;
        }
        HashMap hashMap = new HashMap();
        for (HuaweiProtocol.Tlv tlv2 : objects) {
            String firstString = tlv2.firstString(4);
            String firstString2 = tlv2.firstString(5);
            if (!TextUtils.isEmpty(firstString) && !TextUtils.isEmpty(firstString2)) {
                hashMap.put(firstString, firstString2);
            }
        }
        if (hashMap.isEmpty()) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        for (WatchInstalledItem watchInstalledItem : installedItems) {
            String str2 = (String) hashMap.get(watchfaceFileName(watchInstalledItem.getIdentifier()));
            if (!TextUtils.isEmpty(str2)) {
                watchInstalledItem = new WatchInstalledItem(watchInstalledItem.getRequestType(), watchInstalledItem.getIdentifier(), str2, watchInstalledItem.getVersion(), watchInstalledItem.getSizeBytes(), watchInstalledItem.getDetail(), watchInstalledItem.getStatusLabel(), watchInstalledItem.isActive(), watchInstalledItem.isFactory(), watchInstalledItem.getRawType(), watchInstalledItem.getRawExpandType());
            }
            arrayList.add(watchInstalledItem);
        }
        this.store.updateInstalledItems(str, 2, arrayList);
        sendDeviceChanged(str);
    }

    private void handleWatchfaceConfirm(String str, HuaweiProtocol.Tlv tlv) {
        String firstString = tlv.firstString(1);
        String firstString2 = tlv.firstString(2);
        int firstAsInteger = tlv.firstAsInteger(3, 0);
        if (TextUtils.isEmpty(firstString)) {
            return;
        }
        if (!TextUtils.isEmpty(firstString2)) {
            firstString = firstString + "_" + firstString2;
        }
        Log.i(TAG, "watchface confirm address=" + safeAddress(str) + " file=" + firstString + " reportType=" + firstAsInteger);

        // ── 替换法：0x27/0x05 是表盘应用上报，交给替换状态机 ──
        // 原版这里是靠 awaitingWatchfaceInstallsByAddress 匹配「自动认领」，
        // 替换事务必须自己应答（先回 0x27/0x05 error=100000 表示收到），
        // 再由状态机决定要不要发后置 0x27/0x03。
        WatchfaceReplacement repl = watchfaceReplacementByAddress.get(str);
        if (repl != null && !repl.state.terminal()) {
            boolean hasError = tlv.contains(127);
            Long err = hasError ? Long.valueOf(tlv.firstAsInteger(127, 0)) : null;
            // 先按原版习惯回一个「已收到」，避免手表一直等
            if (err == null || err == 100000) {
                String fileName = WatchfaceReplace.replacementFileName(repl.state.id(), repl.state.version());
                writeHuaweiPacket(str, SERVICE_WATCHFACE, (byte) 5, watchfaceConfirmTlv(fileName), true);
            }
            onReplacementReport(str, repl, Integer.valueOf(firstAsInteger), err);
            return;
        }

        NativeUploadSession nativeUploadSession = this.awaitingWatchfaceInstallsByAddress.get(str);
        boolean z = nativeUploadSession != null && TextUtils.equals(nativeUploadSession.fileName, firstString);
        enqueueManagementCommand(str, new ManagementCommand(SERVICE_WATCHFACE, (byte) 5, watchfaceConfirmTlv(firstString), true, true, false, null, z), true);
        if (firstAsInteger == 2 && z) {
            enqueueWatchfaceOperation(str, new PendingWatchfaceOperation(firstString, (byte) 1, true), false);
            return;
        }
        if (z) {
            this.recoveryActionsByAddress.remove(str);
            this.awaitingWatchfaceInstallsByAddress.remove(str);
            quietManagementRequests(str, 30000L);
            pauseUploads(str, 1500L);
            sendInstallProgress(100, false);
            sendInstallMessage("Watchface install complete.");
            sendInstallFinished(str);
            return;
        }
        Log.d(TAG, "watchface confirm acknowledged without pending upload address=" + safeAddress(str) + " file=" + firstString + " reportType=" + firstAsInteger);
    }

    private void requestNativeStatusDelayed(final String str) {
        final int managementGeneration = managementGeneration(str);
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$requestNativeStatusDelayed$16(str, managementGeneration);
            }
        }, 1500L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$requestNativeStatusDelayed$16(String str, int i) {
        if (isManagementGenerationCurrent(str, i)) {
            requestNativeStatus(str);
        }
    }

    private void requestNativeStatusRepeated(final String str) {
        final int managementGeneration = managementGeneration(str);
        for (long j : SINGLE_MANAGEMENT_REFRESH_DELAY_MS) {
            this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda16
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$requestNativeStatusRepeated$17(str, managementGeneration);
                }
            }, j);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$requestNativeStatusRepeated$17(String str, int i) {
        if (isManagementGenerationCurrent(str, i)) {
            requestNativeStatus(str);
        }
    }

    private void requestAppListRepeated(String str) {
        requestAppListRepeated(str, SINGLE_MANAGEMENT_REFRESH_DELAY_MS);
    }

    private void requestAppListRepeated(final String str, long[] jArr) {
        final int managementGeneration = managementGeneration(str);
        for (long j : jArr) {
            this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$requestAppListRepeated$18(str, managementGeneration);
                }
            }, j);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$requestAppListRepeated$18(String str, int i) {
        if (isManagementGenerationCurrent(str, i)) {
            requestNativeAppList(str);
        }
    }

    private boolean requestNativeAppList(String str) {
        if (isNativeUploadOrInstallBusy(str)) {
            Log.d(TAG, "app list skipped while upload is active address=" + safeAddress(str));
            return false;
        }
        return enqueueManagementCommand(str, new ManagementCommand(SERVICE_APP, (byte) 3, new HuaweiProtocol.Tlv().put(129, new byte[0]), false, false), false);
    }

    private void requestWatchfaceListRepeated(String str) {
        requestWatchfaceListRepeated(str, SINGLE_MANAGEMENT_REFRESH_DELAY_MS);
    }

    private void requestWatchfaceListRepeated(final String str, long[] jArr) {
        final int managementGeneration = managementGeneration(str);
        for (long j : jArr) {
            this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda21
                @Override // java.lang.Runnable
                public final void run() {
                    NativeHuaweiWatchBackend.this.lambda$requestWatchfaceListRepeated$19(str, managementGeneration);
                }
            }, j);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$requestWatchfaceListRepeated$19(String str, int i) {
        if (isManagementGenerationCurrent(str, i)) {
            lambda$requestNativeManagementLists$5(str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: requestNativeWatchfaceList, reason: merged with bridge method [inline-methods] */
    public boolean lambda$requestNativeManagementLists$5(String str) {
        if (isNativeUploadOrInstallBusy(str)) {
            Log.d(TAG, "watchface list skipped while upload is active address=" + safeAddress(str));
            return false;
        }
        return enqueueManagementCommand(str, new ManagementCommand(SERVICE_WATCHFACE, (byte) 2, new HuaweiProtocol.Tlv().put(1, new byte[0]).put(6, (byte) 3), false, false), false);
    }

    private void requestWatchfaceNamesDelayed(final String str, List<WatchInstalledItem> list) {
        final int managementGeneration = managementGeneration(str);
        final ArrayList arrayList = new ArrayList(list);
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda19
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$requestWatchfaceNamesDelayed$20(str, managementGeneration, arrayList);
            }
        }, 150L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$requestWatchfaceNamesDelayed$20(String str, int i, ArrayList arrayList) {
        if (isManagementGenerationCurrent(str, i)) {
            requestWatchfaceNames(str, arrayList);
        }
    }

    private boolean enqueueWatchfaceOperation(String str, PendingWatchfaceOperation pendingWatchfaceOperation, boolean z) {
        this.pendingWatchfaceOperationsByAddress.put(str, pendingWatchfaceOperation);
        boolean enqueueManagementCommand = enqueueManagementCommand(str, new ManagementCommand(SERVICE_WATCHFACE, (byte) 3, watchfaceOperationTlv(pendingWatchfaceOperation.fileName, pendingWatchfaceOperation.operation), true, true, true, pendingWatchfaceOperation, pendingWatchfaceOperation.uploadActivation), z);
        if (!enqueueManagementCommand && this.pendingWatchfaceOperationsByAddress.get(str) == pendingWatchfaceOperation) {
            this.pendingWatchfaceOperationsByAddress.remove(str);
        }
        return enqueueManagementCommand;
    }

    private boolean enqueueAppDelete(String str, String str2, boolean z) {
        return enqueueManagementCommand(str, new ManagementCommand(SERVICE_APP, (byte) 1, new HuaweiProtocol.Tlv().put(1, (byte) 1).put(2, stringBytes(str2)), false, true, true, null, false, str2), z);
    }

    private PendingWatchfaceOperation currentWatchfaceOperation(String str) {
        ManagementQueue managementQueue = this.managementQueuesByAddress.get(str);
        if (managementQueue != null) {
            synchronized (managementQueue) {
                ManagementCommand managementCommand = managementQueue.inFlight;
                if (managementCommand != null && managementCommand.serviceId == 39 && managementCommand.commandId == 3 && managementCommand.watchfaceOperation != null) {
                    return managementCommand.watchfaceOperation;
                }
            }
        }
        return this.pendingWatchfaceOperationsByAddress.get(str);
    }

    private void clearPendingWatchfaceOperation(String str, PendingWatchfaceOperation pendingWatchfaceOperation) {
        if (pendingWatchfaceOperation == null) {
            this.pendingWatchfaceOperationsByAddress.remove(str);
        } else if (this.pendingWatchfaceOperationsByAddress.get(str) == pendingWatchfaceOperation) {
            this.pendingWatchfaceOperationsByAddress.remove(str);
        }
    }

    private boolean recoverAfter100004(String str, PendingRecoveryAction pendingRecoveryAction) {
        PendingRecoveryAction pendingRecoveryAction2 = this.recoveryActionsByAddress.get(str);
        if (pendingRecoveryAction2 != null && pendingRecoveryAction2.sameAction(pendingRecoveryAction)) {
            pendingRecoveryAction = pendingRecoveryAction2;
        }
        this.recoveryActionsByAddress.put(str, pendingRecoveryAction);
        pendingRecoveryAction.attempts++;
        Log.w(TAG, "watch service returned 100004; recovery attempt address=" + safeAddress(str) + " type=" + pendingRecoveryAction.type + " attempts=" + pendingRecoveryAction.attempts + " reconnected=" + pendingRecoveryAction.reconnected);
        clearRecoveryBusyState(str);
        if (!pendingRecoveryAction.reconnected && pendingRecoveryAction.attempts == 1) {
            sendRecoveryMessage(pendingRecoveryAction, "Watch service busy. Retrying...");
            scheduleRecoveryRetry(str, pendingRecoveryAction);
            return true;
        }
        if (!pendingRecoveryAction.reconnected && pendingRecoveryAction.attempts == 2) {
            sendRecoveryMessage(pendingRecoveryAction, "Watch service busy. Reconnecting...");
            scheduleRecoveryReconnect(str, pendingRecoveryAction);
            return true;
        }
        failRecoveredAction(str, pendingRecoveryAction, "Watch service rejected the operation after reconnect (100004).");
        return true;
    }

    private void clearRecoveryBusyState(String str) {
        this.uploadsByAddress.remove(str);
        this.pendingUploadsByAddress.remove(str);
        this.awaitingWatchfaceInstallsByAddress.remove(str);
        this.pendingWatchfaceOperationsByAddress.remove(str);
        bumpManagementGeneration(str);
        clearPendingManagementCommands(str);
        this.managementCooldownUntilByAddress.remove(str);
        this.managementQuietUntilByAddress.remove(str);
        this.uploadCooldownUntilByAddress.remove(str);
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession != null) {
            synchronized (huaweiGattSession.pendingWrites) {
                huaweiGattSession.pendingWrites.clear();
            }
        }
    }

    private void scheduleRecoveryRetry(final String str, final PendingRecoveryAction pendingRecoveryAction) {
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda11
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$scheduleRecoveryRetry$21(str, pendingRecoveryAction);
            }
        }, RECOVERY_RETRY_DELAY_MS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleRecoveryRetry$21(String str, PendingRecoveryAction pendingRecoveryAction) {
        if (this.recoveryActionsByAddress.get(str) == pendingRecoveryAction) {
            replayRecoveryAction(str, pendingRecoveryAction);
        }
    }

    private void scheduleRecoveryReconnect(final String str, final PendingRecoveryAction pendingRecoveryAction) {
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$scheduleRecoveryReconnect$22(str, pendingRecoveryAction);
            }
        }, WATCHFACE_OPERATION_RECONNECT_DELAY_MS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleRecoveryReconnect$22(String str, PendingRecoveryAction pendingRecoveryAction) {
        PendingRecoveryAction pendingRecoveryAction2 = this.recoveryActionsByAddress.get(str);
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (pendingRecoveryAction2 != pendingRecoveryAction || findByAddress == null) {
            return;
        }
        pendingRecoveryAction.reconnected = true;
        closeNativeTransports(str, false);
        this.store.upsert(copyWithConnectionState(findByAddress, "Reconnecting watch service", false, false, true, false));
        sendDeviceChanged(str);
        connect(findByAddress);
    }

    private void resumeRecoveryAfterReconnect(final String str) {
        final PendingRecoveryAction pendingRecoveryAction = this.recoveryActionsByAddress.get(str);
        if (pendingRecoveryAction == null || !pendingRecoveryAction.reconnected) {
            return;
        }
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda25
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$resumeRecoveryAfterReconnect$23(str, pendingRecoveryAction);
            }
        }, RECOVERY_RETRY_DELAY_MS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$resumeRecoveryAfterReconnect$23(String str, PendingRecoveryAction pendingRecoveryAction) {
        if (this.recoveryActionsByAddress.get(str) == pendingRecoveryAction) {
            replayRecoveryAction(str, pendingRecoveryAction);
        }
    }

    private void replayRecoveryAction(String str, PendingRecoveryAction pendingRecoveryAction) {
        String str2;
        Log.i(TAG, "replaying recovered watch action address=" + safeAddress(str) + " type=" + pendingRecoveryAction.type + " attempts=" + pendingRecoveryAction.attempts + " reconnected=" + pendingRecoveryAction.reconnected);
        int i = pendingRecoveryAction.type;
        if (i == 1) {
            if (pendingRecoveryAction.watchfaceOperation != null && enqueueWatchfaceOperation(str, pendingRecoveryAction.watchfaceOperation.copy(), true)) {
                if (pendingRecoveryAction.watchfaceOperation.uploadActivation) {
                    sendInstallProgress(99, true);
                    if (pendingRecoveryAction.reconnected) {
                        str2 = "Reconnected. Retrying watchface activation...";
                    } else {
                        str2 = "Retrying watchface activation...";
                    }
                    sendInstallMessage(str2);
                    return;
                }
                return;
            }
        } else if (i == 2 && !TextUtils.isEmpty(pendingRecoveryAction.appPackageName) && enqueueAppDelete(str, pendingRecoveryAction.appPackageName, true)) {
            return;
        }
        failRecoveredAction(str, pendingRecoveryAction, "Unable to retry watch operation.");
    }

    private void failRecoveredAction(String str, PendingRecoveryAction pendingRecoveryAction, String str2) {
        this.recoveryActionsByAddress.remove(str);
        clearRecoveryBusyState(str);
        if (isInstallRecovery(pendingRecoveryAction)) {
            sendInstallProgress(0, false);
            sendInstallMessage(str2);
            sendInstallFailed(str, str2);
        } else {
            Toast.makeText(this.appContext, str2, 0).show();
            requestNativeManagementLists(str);
        }
    }

    private void sendRecoveryMessage(PendingRecoveryAction pendingRecoveryAction, String str) {
        if (isInstallRecovery(pendingRecoveryAction)) {
            sendInstallMessage(str);
        }
    }

    private boolean isInstallRecovery(PendingRecoveryAction pendingRecoveryAction) {
        return pendingRecoveryAction.watchfaceOperation != null && pendingRecoveryAction.watchfaceOperation.uploadActivation;
    }

    private String currentAppDeletePackage(String str) {
        ManagementQueue managementQueue = this.managementQueuesByAddress.get(str);
        if (managementQueue == null) {
            return null;
        }
        synchronized (managementQueue) {
            ManagementCommand managementCommand = managementQueue.inFlight;
            if (managementCommand == null || managementCommand.serviceId != 42 || managementCommand.commandId != 1) {
                return null;
            }
            return managementCommand.appPackageName;
        }
    }

    private boolean enqueueManagementCommand(String str, ManagementCommand managementCommand, boolean z) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !huaweiGattSession.authenticated) {
            Log.d(TAG, "management command skipped before authentication address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.commandId & 0xFF)));
            return false;
        }
        if (!z && !managementCommand.allowWhileUploadBusy && isManagementCoolingDown(str)) {
            Log.d(TAG, "management command skipped during cooldown address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.commandId & 0xFF)));
            return false;
        }
        if (!z && !managementCommand.allowWhileUploadBusy && isManagementQuiet(str)) {
            Log.d(TAG, "management command skipped during quiet period address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.commandId & 0xFF)));
            return false;
        }
        if (!z && isNativeUploadOrInstallBusy(str) && !managementCommand.allowWhileUploadBusy) {
            Log.d(TAG, "management command skipped while upload is active address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.commandId & 0xFF)));
            return false;
        }
        ManagementQueue computeIfAbsent = this.managementQueuesByAddress.computeIfAbsent(str, new Function() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda6
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return NativeHuaweiWatchBackend.lambda$enqueueManagementCommand$24((String) obj);
            }
        });
        synchronized (computeIfAbsent) {
            if (z) {
                computeIfAbsent.pending.addFirst(managementCommand);
            } else {
                if (hasPendingManagementCommand(computeIfAbsent, managementCommand)) {
                    return true;
                }
                computeIfAbsent.pending.add(managementCommand);
            }
            drainManagementQueue(str);
            return true;
        }
    }

    static /* synthetic */ ManagementQueue lambda$enqueueManagementCommand$24(String str) {
        return new ManagementQueue();
    }

    private boolean hasPendingManagementCommand(ManagementQueue managementQueue, ManagementCommand managementCommand) {
        if (managementQueue.inFlight != null && managementQueue.inFlight.sameEndpoint(managementCommand)) {
            return true;
        }
        Iterator<ManagementCommand> it = managementQueue.pending.iterator();
        while (it.hasNext()) {
            if (it.next().sameEndpoint(managementCommand)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 管理命令队列泵：把 queued 的 ManagementCommand 逐条发出去。
     *
     * 原方法 jadx 反编译失败（RegionMakerVisitor: Can't find top splitter block），
     * 这里按 com.watch.assistant 3.4 的原始字节码逐条还原（mt ApkToolKit 读的 instruction 伪代码）：
     *   1. queue 不存在 -> 直接返回；
     *   2. 正在上传/安装时，只有 allowWhileUploadBusy 的命令能走，
     *      并且 inFlight 或队头那条必须允许 -> 否则直接返回（等上传结束再泵）；
     *   3. 已有 inFlight -> 返回，等它完成后再进来；
     *   4. 取队头置为 inFlight，writeHuaweiPacket 发送；
     *      发送失败 -> 清掉 inFlight 递归重泵；成功 ->
     *      awaitResponse ? postDelayed(8000ms 超时) : postDelayed(150ms 下一条)。
     */
    private void drainManagementQueue(final String str) {
        final ManagementQueue queue = this.managementQueuesByAddress.get(str);
        if (queue == null) {
            return;
        }
        if (isNativeUploadOrInstallBusy(str)) {
            ManagementCommand head;
            synchronized (queue) {
                head = queue.inFlight;
                if (head == null) {
                    head = queue.pending.peek();
                }
                if (head != null && head.allowWhileUploadBusy) {
                    // 允许在上传期间插入的命令，继续往下走
                } else {
                    return;
                }
            }
        }
        final ManagementCommand command;
        synchronized (queue) {
            if (queue.inFlight != null) {
                return;
            }
            command = queue.pending.poll();
            if (command == null) {
                return;
            }
            queue.inFlight = command;
        }

        boolean sent = writeHuaweiPacket(str, command.serviceId, command.commandId,
                command.tlv, command.requireEncryption);
        if (!sent) {
            synchronized (queue) {
                if (queue.inFlight == command) {
                    queue.inFlight = null;
                }
            }
            drainManagementQueue(str);
            return;
        }

        Log.d(TAG, "management command sent address=" + safeAddress(str)
                + " service=0x" + String.format(Locale.US, "%02X", (int) (command.serviceId & 0xFF))
                + " command=0x" + String.format(Locale.US, "%02X", (int) (command.commandId & 0xFF)));

        if (command.awaitResponse) {
            this.mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    lambda$drainManagementQueue$26(str, command);
                }
            }, MANAGEMENT_COMMAND_TIMEOUT_MS);
        } else {
            this.mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    lambda$drainManagementQueue$26(str, command);
                }
            }, MANAGEMENT_NEXT_COMMAND_DELAY_MS);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$drainManagementQueue$26(String str, ManagementCommand managementCommand) {
        completeManagementCommand(str, managementCommand.serviceId, managementCommand.commandId);
    }

    private void completeManagementCommand(String str, byte b, byte b2) {
        boolean z;
        ManagementQueue managementQueue = this.managementQueuesByAddress.get(str);
        if (managementQueue == null) {
            return;
        }
        synchronized (managementQueue) {
            ManagementCommand managementCommand = managementQueue.inFlight;
            if (managementCommand != null && managementCommand.serviceId == b && managementCommand.commandId == b2) {
                managementQueue.inFlight = null;
                z = true;
            } else {
                z = false;
            }
        }
        if (z) {
            scheduleManagementDrain(str);
        }
    }

    private void scheduleManagementDrain(final String str) {
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda17
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$scheduleManagementDrain$27(str);
            }
        }, 150L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleManagementDrain$27(String str) {
        drainManagementQueue(str);
        startPendingUploadIfIdle(str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: timeoutManagementCommand, reason: merged with bridge method [inline-methods] */
    public void lambda$drainManagementQueue$25(String str, ManagementCommand managementCommand) {
        boolean z;
        ManagementQueue managementQueue = this.managementQueuesByAddress.get(str);
        if (managementQueue == null) {
            return;
        }
        synchronized (managementQueue) {
            if (managementQueue.inFlight == managementCommand) {
                managementQueue.inFlight = null;
                z = true;
            } else {
                z = false;
            }
        }
        if (z) {
            Log.w(TAG, "management command timeout address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.serviceId & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(managementCommand.commandId & 0xFF)));
            drainManagementQueue(str);
            startPendingUploadIfIdle(str);
        }
    }

    private void clearManagementQueue(String str) {
        ManagementQueue remove = this.managementQueuesByAddress.remove(str);
        if (remove == null) {
            return;
        }
        synchronized (remove) {
            remove.pending.clear();
            remove.inFlight = null;
        }
    }

    private int bumpManagementGeneration(String str) {
        int managementGeneration = managementGeneration(str) + 1;
        this.managementGenerationsByAddress.put(str, Integer.valueOf(managementGeneration));
        return managementGeneration;
    }

    private int managementGeneration(String str) {
        Integer num = this.managementGenerationsByAddress.get(str);
        if (num != null) {
            return num.intValue();
        }
        return 0;
    }

    private boolean isManagementGenerationCurrent(String str, int i) {
        return managementGeneration(str) == i;
    }

    private void clearPendingManagementCommands(String str) {
        ManagementQueue managementQueue = this.managementQueuesByAddress.get(str);
        if (managementQueue == null) {
            return;
        }
        synchronized (managementQueue) {
            managementQueue.pending.clear();
        }
    }

    private boolean isNativeUploadOrInstallBusy(String str) {
        PendingRecoveryAction pendingRecoveryAction = this.recoveryActionsByAddress.get(str);
        if (this.uploadsByAddress.containsKey(str) || this.pendingUploadsByAddress.containsKey(str) || this.awaitingWatchfaceInstallsByAddress.containsKey(str)) {
            return true;
        }
        return pendingRecoveryAction != null && isInstallRecovery(pendingRecoveryAction);
    }

    private boolean isManagementBusy(String str) {
        boolean z;
        ManagementQueue managementQueue = this.managementQueuesByAddress.get(str);
        if (managementQueue == null) {
            return false;
        }
        synchronized (managementQueue) {
            z = (managementQueue.inFlight == null && managementQueue.pending.isEmpty()) ? false : true;
        }
        return z;
    }

    private void startPendingUploadIfIdle(String str) {
        NativeUploadSession nativeUploadSession = this.pendingUploadsByAddress.get(str);
        if (nativeUploadSession == null || this.uploadsByAddress.containsKey(str) || isManagementBusy(str) || startNativeUpload(str, nativeUploadSession)) {
            return;
        }
        this.pendingUploadsByAddress.remove(str);
        sendInstallProgress(0, false);
        sendInstallMessage("Unable to start native upload.");
        sendInstallFailed(str, "Unable to start native upload.");
    }

    private void markItemDeletedLocally(String str, WatchInstalledItem watchInstalledItem) {
        List<WatchInstalledItem> installedItems = this.store.getInstalledItems(str, watchInstalledItem.getRequestType());
        if (installedItems.isEmpty()) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        for (WatchInstalledItem watchInstalledItem2 : installedItems) {
            if (!TextUtils.equals(watchInstalledItem2.getIdentifier(), watchInstalledItem.getIdentifier())) {
                arrayList.add(watchInstalledItem2);
            }
        }
        this.store.updateInstalledItems(str, watchInstalledItem.getRequestType(), arrayList);
        sendDeviceChanged(str);
    }

    private void markAppDeletedLocally(String str, String str2) {
        List<WatchInstalledItem> installedItems = this.store.getInstalledItems(str, 1);
        if (installedItems.isEmpty()) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        for (WatchInstalledItem watchInstalledItem : installedItems) {
            if (!TextUtils.equals(watchInstalledItem.getIdentifier(), str2)) {
                arrayList.add(watchInstalledItem);
            }
        }
        this.store.updateInstalledItems(str, 1, arrayList);
        sendDeviceChanged(str);
    }

    private void markWatchfaceDeletedLocally(String str, String str2) {
        List<WatchInstalledItem> installedItems = this.store.getInstalledItems(str, 2);
        if (installedItems.isEmpty()) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        for (WatchInstalledItem watchInstalledItem : installedItems) {
            if (!TextUtils.equals(watchInstalledItem.getIdentifier(), str2)) {
                arrayList.add(watchInstalledItem);
            }
        }
        this.store.updateInstalledItems(str, 2, arrayList);
        sendDeviceChanged(str);
    }

    private void markWatchfaceActiveLocally(String str, String str2) {
        List<WatchInstalledItem> installedItems = this.store.getInstalledItems(str, 2);
        if (installedItems.isEmpty()) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        for (WatchInstalledItem watchInstalledItem : installedItems) {
            boolean equals = TextUtils.equals(watchInstalledItem.getIdentifier(), str2);
            arrayList.add(new WatchInstalledItem(watchInstalledItem.getRequestType(), watchInstalledItem.getIdentifier(), watchInstalledItem.getTitle(), watchInstalledItem.getVersion(), watchInstalledItem.getSizeBytes(), watchInstalledItem.getDetail(), equals ? "当前表盘" : withoutCurrentWatchfaceLabel(watchInstalledItem.getStatusLabel()), equals, watchInstalledItem.isFactory(), watchInstalledItem.getRawType(), watchInstalledItem.getRawExpandType()));
        }
        this.store.updateInstalledItems(str, 2, arrayList);
        sendDeviceChanged(str);
    }

    private void markWatchfaceActiveLocally(String str, WatchInstalledItem watchInstalledItem) {
        List<WatchInstalledItem> installedItems = this.store.getInstalledItems(str, 2);
        if (installedItems.isEmpty()) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        for (WatchInstalledItem watchInstalledItem2 : installedItems) {
            boolean equals = TextUtils.equals(watchInstalledItem2.getIdentifier(), watchInstalledItem.getIdentifier());
            arrayList.add(new WatchInstalledItem(watchInstalledItem2.getRequestType(), watchInstalledItem2.getIdentifier(), watchInstalledItem2.getTitle(), watchInstalledItem2.getVersion(), watchInstalledItem2.getSizeBytes(), watchInstalledItem2.getDetail(), equals ? "当前表盘" : withoutCurrentWatchfaceLabel(watchInstalledItem2.getStatusLabel()), equals, watchInstalledItem2.isFactory(), watchInstalledItem2.getRawType(), watchInstalledItem2.getRawExpandType()));
        }
        this.store.updateInstalledItems(str, 2, arrayList);
        sendDeviceChanged(str);
    }

    private String withoutCurrentWatchfaceLabel(String str) {
        if (TextUtils.isEmpty(str)) {
            return str;
        }
        ArrayList arrayList = new ArrayList();
        for (String str2 : str.split(" · ")) {
            if (!TextUtils.isEmpty(str2) && !TextUtils.equals(str2, "当前表盘")) {
                arrayList.add(str2);
            }
        }
        return joinDetails(arrayList);
    }

    private String requireReadyAddress(WatchDevice watchDevice) {
        if (TextUtils.isEmpty(watchDevice.getAddress())) {
            throw new UnsupportedOperationException("Missing device address");
        }
        if (sessionForReadyDevice(watchDevice) == null) {
            throw new UnsupportedOperationException("Native Huawei session is not ready");
        }
        return watchDevice.getAddress();
    }

    private HuaweiProtocol.Tlv watchfaceParamsTlv() {
        // 对齐 WristMirror Wire.params()：tag 1,2,3,4,5,0x14,0x15 全为空值，不编造 mode/sync
        return new HuaweiProtocol.Tlv()
                .put(1, new byte[0]).put(2, new byte[0]).put(3, new byte[0])
                .put(4, new byte[0]).put(5, new byte[0])
                .put(0x14, new byte[0]).put(0x15, new byte[0]);
    }

    private HuaweiProtocol.Tlv watchfaceOperationTlv(String str, byte b) {
        String[] splitWatchfaceFileName = splitWatchfaceFileName(str);
        Log.d(TAG, "watchface operation tlv file=" + str + " name=" + splitWatchfaceFileName[0] + " version=" + splitWatchfaceFileName[1] + " operation=" + ((int) b));
        return new HuaweiProtocol.Tlv().put(1, stringBytes(splitWatchfaceFileName[0])).put(2, stringBytes(splitWatchfaceFileName[1])).put(3, b);
    }

    private HuaweiProtocol.Tlv watchfaceConfirmTlv(String str) {
        String[] splitWatchfaceFileName = splitWatchfaceFileName(str);
        return new HuaweiProtocol.Tlv().put(1, stringBytes(splitWatchfaceFileName[0])).put(2, stringBytes(splitWatchfaceFileName[1])).put(127, intBytes(100000));
    }

    private String[] splitWatchfaceFileName(String str) {
        String[] split = str.split("_", 2);
        return split.length == 1 ? new String[]{split[0], ""} : split;
    }

    private String normalizedWatchfaceOperationFileName(WatchInstalledItem watchInstalledItem) {
        String identifier = watchInstalledItem.getIdentifier();
        if (identifier.contains("_") || TextUtils.isEmpty(watchInstalledItem.getVersion())) {
            return identifier;
        }
        return identifier + "_" + watchInstalledItem.getVersion();
    }

    private String watchfaceFileName(String str) {
        return splitWatchfaceFileName(str)[0];
    }

    /** 当前系统语言是不是中文（决定 0x27/0x06 的 tag1 语言码） */
    private boolean isChineseLocale() {
        try {
            String lang = Locale.getDefault().getLanguage();
            return "zh".equalsIgnoreCase(lang);
        } catch (Throwable ignore) {
            return true;
        }
    }

    private String watchfaceFullFileName(String str, String str2) {
        if (TextUtils.isEmpty(str2)) {
            return str;
        }
        return str + "_" + str2;
    }

    private byte[] stringBytes(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] intBytes(int i) {
        return ByteBuffer.allocate(4).putInt(i).array();
    }

    private byte[] shortBytes(int i) {
        return ByteBuffer.allocate(2).putShort((short) i).array();
    }

    private String keyFingerprint(byte[] bArr) {
        if (bArr == null || bArr.length == 0) {
            return "none";
        }
        byte[] sha256Digest = HuaweiCryptoSupport.sha256Digest(bArr);
        if (sha256Digest == null || sha256Digest.length < 4) {
            return "unavailable/" + bArr.length;
        }
        return String.format(Locale.US, "%02x%02x%02x%02x/%d", Integer.valueOf(sha256Digest[0] & 0xFF), Integer.valueOf(sha256Digest[1] & 0xFF), Integer.valueOf(sha256Digest[2] & 0xFF), Integer.valueOf(sha256Digest[3] & 0xFF), Integer.valueOf(bArr.length));
    }

    private String sha256Hex(byte[] bArr) {
        byte[] sha256Digest = HuaweiCryptoSupport.sha256Digest(bArr);
        StringBuilder sb = new StringBuilder(sha256Digest.length * 2);
        for (byte b : sha256Digest) {
            sb.append(String.format(Locale.US, "%02x", Integer.valueOf(b & 0xFF)));
        }
        return sb.toString();
    }

    private int firstPresentInteger(HuaweiProtocol.Tlv tlv, int... iArr) {
        for (int i : iArr) {
            if (tlv.contains(i)) {
                return tlv.firstAsInteger(i, 0);
            }
        }
        return 0;
    }

    private String joinDetails(List<String> list) {
        if (list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String str : list) {
            if (!TextUtils.isEmpty(str)) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(str);
            }
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        return null;
    }

    private String tlvSummary(HuaweiProtocol.Tlv tlv) {
        return tlv.summary();
    }

    private byte[] huaweiPayload(byte b, byte b2, byte[] bArr) {
        byte[] bArr2 = new byte[bArr.length + 2];
        bArr2[0] = b;
        bArr2[1] = b2;
        System.arraycopy(bArr, 0, bArr2, 2, bArr.length);
        return bArr2;
    }

    private void scheduleUploadContinueCheck(final String str, final NativeUploadSession nativeUploadSession) {
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda36
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$scheduleUploadContinueCheck$28(str, nativeUploadSession);
            }
        }, 12000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleUploadContinueCheck$28(String str, NativeUploadSession nativeUploadSession) {
        NativeUploadSession nativeUploadSession2 = this.uploadsByAddress.get(str);
        if (nativeUploadSession2 != nativeUploadSession || nativeUploadSession2.params != null || nativeUploadSession2.fileIdReceived || nativeUploadSession2.offset > 0) {
            return;
        }
        Log.w(TAG, "upload did not continue after metadata address=" + safeAddress(str) + " type=" + ((int) nativeUploadSession2.fileType) + " file=" + nativeUploadSession2.fileName);
        clearFailedUpload(str);
        sendInstallProgress(0, false);
        sendInstallMessage("Watch accepted metadata but did not request upload data.");
        sendInstallFailed(str, "Watch accepted metadata but did not request upload data.");
    }

    private void scheduleWatchfaceConfirmTimeout(final String str, final NativeUploadSession nativeUploadSession) {
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda18
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$scheduleWatchfaceConfirmTimeout$29(str, nativeUploadSession);
            }
        }, WATCHFACE_CONFIRM_TIMEOUT_MS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleWatchfaceConfirmTimeout$29(String str, NativeUploadSession nativeUploadSession) {
        if (this.awaitingWatchfaceInstallsByAddress.get(str) != nativeUploadSession) {
            return;
        }
        Log.w(TAG, "watchface install confirm timeout address=" + safeAddress(str) + " file=" + nativeUploadSession.fileName);
        this.awaitingWatchfaceInstallsByAddress.remove(str);
        bumpManagementGeneration(str);
        pauseManagementRequests(str, 8000L);
        pauseUploads(str, 1500L);
        sendInstallProgress(0, false);
        sendInstallMessage("Watchface transfer completed, but the watch did not confirm installation.");
        sendInstallFailed(str, "Watchface transfer completed, but the watch did not confirm installation.");
    }

    private void schedulePendingUploadStart(final String str, final NativeUploadSession nativeUploadSession) {
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$schedulePendingUploadStart$30(str, nativeUploadSession);
            }
        }, 500L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$schedulePendingUploadStart$30(String str, NativeUploadSession nativeUploadSession) {
        if (this.pendingUploadsByAddress.get(str) != nativeUploadSession || this.uploadsByAddress.containsKey(str)) {
            return;
        }
        if (isManagementBusy(str) || isUploadCoolingDown(str)) {
            Log.w(TAG, "pending upload waiting for watch service cooldown address=" + safeAddress(str) + " type=" + ((int) nativeUploadSession.fileType) + " file=" + nativeUploadSession.fileName);
            schedulePendingUploadStart(str, nativeUploadSession);
            return;
        }
        if (startNativeUpload(str, nativeUploadSession)) {
            return;
        }
        this.pendingUploadsByAddress.remove(str);
        sendInstallProgress(0, false);
        sendInstallMessage("Unable to start native upload.");
        sendInstallFailed(str, "Unable to start native upload.");
    }

    private boolean startNativeUpload(String str, NativeUploadSession nativeUploadSession) {
        this.pendingUploadsByAddress.remove(str);
        this.uploadsByAddress.put(str, nativeUploadSession);
        if (!writeHuaweiPacket(str, CMD_HICHAIN, (byte) 2, HuaweiFileUploadProtocol.fileInfo(nativeUploadSession.payload.length, nativeUploadSession.fileName, nativeUploadSession.fileType, null, null, null, null), nativeUploadSession.fileType == 1)) {
            this.uploadsByAddress.remove(str);
            return false;
        }
        Log.i(TAG, "native upload started address=" + safeAddress(str) + " type=" + ((int) nativeUploadSession.fileType) + " file=" + nativeUploadSession.fileName + " payloadBytes=" + nativeUploadSession.payload.length + " payloadSha256=" + sha256Hex(nativeUploadSession.payload));
        scheduleUploadInactivityCheck(str, nativeUploadSession, "waiting for upload metadata response");
        return true;
    }

    private void handleFileUploadPacket(String str, HuaweiProtocol.Packet packet) {
        List<byte[]> arrayList;
        NativeUploadSession nativeUploadSession = this.uploadsByAddress.get(str);
        if (nativeUploadSession == null) {
            Log.w(TAG, "upload packet without session address=" + safeAddress(str) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(packet.commandId & 0xFF)) + " tlv=" + tlvSummary(packet.tlv));
            return;
        }
        if (packet.commandId == 2) {
            int parseResult = HuaweiFileUploadProtocol.parseResult(packet.tlv);
            Log.i(TAG, "upload metadata response address=" + safeAddress(str) + " result=" + parseResult + " type=" + ((int) nativeUploadSession.fileType) + " file=" + nativeUploadSession.fileName + " tlv=" + tlvSummary(packet.tlv));
            if (parseResult != 0 && parseResult != 100000) {
                if ((parseResult == 140008 || parseResult == 100008) && nativeUploadSession.fileType == 1) {
                    activateExistingWatchfaceUpload(str, nativeUploadSession);
                    return;
                }
                clearFailedUpload(str);
                sendInstallProgress(0, false);
                String uploadErrorMessage = uploadErrorMessage(parseResult);
                sendInstallMessage(uploadErrorMessage);
                sendInstallFailed(str, uploadErrorMessage);
                return;
            }
            sendInstallMessage("Watch accepted upload metadata.");
            scheduleUploadContinueCheck(str, nativeUploadSession);
            return;
        }
        if (packet.commandId == 3) {
            nativeUploadSession.fileId = HuaweiFileUploadProtocol.parseFileId(packet.tlv);
            nativeUploadSession.fileIdReceived = true;
            Log.i(TAG, "upload hash request address=" + safeAddress(str) + " fileId=" + (nativeUploadSession.fileId & 0xFF) + " tlv=" + tlvSummary(packet.tlv));
            if (!writeHuaweiPacket(str, CMD_HICHAIN, (byte) 3, HuaweiFileUploadProtocol.fileHash(nativeUploadSession.fileId, nativeUploadSession.payload))) {
                failHuaweiTransport(str, "Unable to send Huawei upload hash.");
                return;
            } else {
                scheduleUploadInactivityCheck(str, nativeUploadSession, "waiting for upload parameters");
                return;
            }
        }
        if (packet.commandId == 4) {
            nativeUploadSession.params = HuaweiFileUploadProtocol.parseUploadParams(packet.tlv);
            nativeUploadSession.fileId = nativeUploadSession.params.fileId;
            Log.i(TAG, "upload params address=" + safeAddress(str) + " fileId=" + (nativeUploadSession.fileId & 0xFF) + " appWaitTimeMs=" + nativeUploadSession.params.appWaitTimeMs + " unitSize=" + nativeUploadSession.params.unitSize + " maxApplyDataSize=" + nativeUploadSession.params.maxApplyDataSize + " intervalMs=" + nativeUploadSession.params.intervalMs + " noEncryption=" + nativeUploadSession.params.noEncryption + " tlv=" + tlvSummary(packet.tlv));
            HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
            if (!nativeUploadSession.params.noEncryption && (huaweiGattSession == null || huaweiGattSession.secretKey == null)) {
                clearFailedUpload(str);
                sendInstallMessage("Watch requested encrypted upload, but native session key is not ready.");
                sendInstallFailed(str, "Watch requested encrypted upload, but native session key is not ready.");
                return;
            } else if (!writeHuaweiPacket(str, CMD_HICHAIN, CMD_MUSIC_INFO_PARAMS, HuaweiFileUploadProtocol.uploadConsultAck(nativeUploadSession.fileId, nativeUploadSession.params.noEncryption))) {
                failHuaweiTransport(str, "Unable to acknowledge Huawei upload parameters.");
                return;
            } else {
                sendUploadProgress(nativeUploadSession, 0, false);
                scheduleUploadInactivityCheck(str, nativeUploadSession, "waiting for the first upload chunk request");
                return;
            }
        }
        if (packet.commandId == 5) {
            HuaweiFileUploadProtocol.NextChunk parseNextChunk = HuaweiFileUploadProtocol.parseNextChunk(packet.tlv);
            nativeUploadSession.offset = Math.max(0, Math.min(nativeUploadSession.payload.length, parseNextChunk.bytesUploaded));
            nativeUploadSession.chunkSize = Math.max(0, Math.min(parseNextChunk.nextChunkSize, nativeUploadSession.payload.length - nativeUploadSession.offset));
            int round = nativeUploadSession.payload.length > 0 ? Math.round((nativeUploadSession.offset * 100.0f) / nativeUploadSession.payload.length) : 0;
            Log.d(TAG, "upload chunk request address=" + safeAddress(str) + " offset=" + nativeUploadSession.offset + " chunkSize=" + nativeUploadSession.chunkSize + " progress=" + round + " frameDelayMs=0 tlv=" + tlvSummary(packet.tlv));
            sendUploadProgress(nativeUploadSession, round, false);
            if (nativeUploadSession.chunkSize <= 0) {
                String str2 = "Watch requested an empty upload chunk at " + round + "%.";
                sendInstallMessage(str2);
                sendInstallFailed(str, str2);
                return;
            }
            if (nativeUploadSession.params == null || nativeUploadSession.params.unitSize <= 0) {
                clearFailedUpload(str);
                sendInstallMessage("Watch did not provide valid upload parameters.");
                sendInstallFailed(str, "Watch did not provide valid upload parameters.");
                return;
            }
            HuaweiGattSession huaweiGattSession2 = this.sessionsByAddress.get(str);
            int i = huaweiGattSession2 != null ? huaweiGattSession2.sliceSize : 244;
            if (nativeUploadSession.params.noEncryption) {
                arrayList = HuaweiFileUploadProtocol.unencryptedChunkFrames(nativeUploadSession.payload, nativeUploadSession.offset, nativeUploadSession.chunkSize, nativeUploadSession.params.unitSize, i, nativeUploadSession.fileId);
                Log.d(TAG, "Huawei upload transaction address=" + safeAddress(str) + " offset=" + nativeUploadSession.offset + " bytes=" + nativeUploadSession.chunkSize + " frames=" + arrayList.size());
            } else if (huaweiGattSession2 != null && huaweiGattSession2.secretKey != null) {
                HuaweiFileUploadProtocol.EncryptionCounter encryptionCounter = new HuaweiFileUploadProtocol.EncryptionCounter(huaweiGattSession2.encryptionCounter);
                List<byte[]> encryptedChunkFrames = HuaweiFileUploadProtocol.encryptedChunkFrames(nativeUploadSession.payload, nativeUploadSession.offset, nativeUploadSession.chunkSize, nativeUploadSession.params.unitSize, i, nativeUploadSession.fileId, huaweiGattSession2.secretKey, huaweiGattSession2.encryptMethod, huaweiGattSession2.deviceSupportType, encryptionCounter);
                if (huaweiGattSession2.deviceSupportType != 4) {
                    huaweiGattSession2.encryptionCounter = encryptionCounter.value;
                }
                arrayList = encryptedChunkFrames;
            } else {
                arrayList = new ArrayList<>();
            }
            if (arrayList.isEmpty() || !enqueueUploadTransaction(str, arrayList)) {
                clearFailedUpload(str);
                sendInstallMessage("Unable to send upload chunk.");
                sendInstallFailed(str, "Unable to send upload chunk.");
                return;
            } else {
                scheduleUploadInactivityCheck(str, nativeUploadSession, "waiting for the watch to acknowledge upload data");
                sendInstallMessage("Uploading package... " + round + "% confirmed");
                return;
            }
        }
        if (packet.commandId == 6) {
            int parseResult2 = HuaweiFileUploadProtocol.parseResult(packet.tlv);
            Log.v(TAG, "upload chunk ack address=" + safeAddress(str) + " result=" + parseResult2 + " tlv=" + tlvSummary(packet.tlv));
            if (parseResult2 != 0 && parseResult2 != 100000) {
                clearFailedUpload(str);
                String str3 = "Watch rejected Huawei upload data (" + parseResult2 + ").";
                sendInstallProgress(0, false);
                sendInstallMessage(str3);
                sendInstallFailed(str, str3);
                return;
            }
            scheduleUploadInactivityCheck(str, nativeUploadSession, "waiting for the next upload chunk request");
            return;
        }
        if (packet.commandId == 7) {
            Log.i(TAG, "upload result request address=" + safeAddress(str) + " fileId=" + (nativeUploadSession.fileId & 0xFF) + " type=" + ((int) nativeUploadSession.fileType) + " tlv=" + tlvSummary(packet.tlv));
            if (!writeHuaweiPacket(str, CMD_HICHAIN, CMD_PRODUCT_INFO, HuaweiFileUploadProtocol.uploadResult(nativeUploadSession.fileId))) {
                failHuaweiTransport(str, "Unable to acknowledge Huawei upload completion.");
                return;
            }
            this.recoveryActionsByAddress.remove(str);
            this.uploadsByAddress.remove(str);
            bumpManagementGeneration(str);

            // ── 替换法：这次上传属于替换事务，结果交给替换状态机 ──
            // 注意不能走下面「fileType==6 / 表盘等待确认」两条普通路径：
            // 普通表盘路径会往 awaitingWatchfaceInstallsByAddress 里塞，
            // 然后被 handleWatchfaceConfirm 的自动认领逻辑抢先收尾。
            if (nativeUploadSession.replacement != null) {
                WatchfaceReplacement repl = nativeUploadSession.replacement;
                nativeUploadSession.replacement = null;
                if (watchfaceReplacementByAddress.get(str) == repl) {
                    sendInstallProgress(99, true);
                    onReplacementFileResult(str, repl, 1);
                }
                return;
            }

            if (nativeUploadSession.fileType == 6) {
                sendInstallProgress(100, false);
                sendInstallMessage("Transfer complete. The watch is installing the package.");
                sendInstallFinished(str);
                requestAppListRepeated(str, POST_INSTALL_REFRESH_DELAYS_MS);
                return;
            }
            this.awaitingWatchfaceInstallsByAddress.put(str, nativeUploadSession);
            sendInstallProgress(99, true);
            sendInstallMessage("Transfer complete. Waiting for watch confirmation.");
            scheduleWatchfaceConfirmTimeout(str, nativeUploadSession);
            return;
        }
        if (packet.commandId == 8) {
            int parseResult3 = HuaweiFileUploadProtocol.parseResult(packet.tlv);
            Log.w(TAG, "upload device response address=" + safeAddress(str) + " code=" + parseResult3 + " tlv=" + tlvSummary(packet.tlv));
            clearFailedUpload(str);
            String uploadErrorMessage2 = uploadErrorMessage(parseResult3);
            sendInstallMessage(uploadErrorMessage2);
            sendInstallFailed(str, uploadErrorMessage2);
        }
    }

    private void activateExistingWatchfaceUpload(String str, NativeUploadSession nativeUploadSession) {
        this.uploadsByAddress.remove(str);
        this.pendingUploadsByAddress.remove(str);
        sendInstallProgress(99, true);
        sendInstallMessage("Watchface already exists. Activating it on the watch.");
        if (enqueueWatchfaceOperation(str, new PendingWatchfaceOperation(nativeUploadSession.fileName, (byte) 1, true), true)) {
            return;
        }
        this.pendingWatchfaceOperationsByAddress.remove(str);
        sendInstallProgress(0, false);
        sendInstallMessage("Unable to activate existing watchface.");
        sendInstallFailed(str, "Unable to activate existing watchface.");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearFailedUpload(String str) {
        this.uploadsByAddress.remove(str);
        this.pendingUploadsByAddress.remove(str);
        this.awaitingWatchfaceInstallsByAddress.remove(str);
        bumpManagementGeneration(str);
        pauseManagementRequests(str, 8000L);
        clearPendingManagementCommands(str);
    }

    private void scheduleUploadInactivityCheck(final String str, final NativeUploadSession nativeUploadSession, final String str2) {
        final int i = nativeUploadSession.watchdogGeneration + 1;
        nativeUploadSession.watchdogGeneration = i;
        this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda29
            @Override // java.lang.Runnable
            public final void run() {
                NativeHuaweiWatchBackend.this.lambda$scheduleUploadInactivityCheck$31(str, nativeUploadSession, i, str2);
            }
        }, 30000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleUploadInactivityCheck$31(String str, NativeUploadSession nativeUploadSession, int i, String str2) {
        NativeUploadSession nativeUploadSession2 = this.uploadsByAddress.get(str);
        if (nativeUploadSession2 == nativeUploadSession && nativeUploadSession2.watchdogGeneration == i) {
            int round = nativeUploadSession.payload.length > 0 ? Math.round((nativeUploadSession.offset * 100.0f) / nativeUploadSession.payload.length) : 0;
            Log.w(TAG, "upload inactivity timeout address=" + safeAddress(str) + " offset=" + nativeUploadSession.offset + " progress=" + round + " waitingFor=" + str2);
            StringBuilder sb = new StringBuilder("Huawei upload timed out at ");
            sb.append(round);
            sb.append("% while ");
            sb.append(str2);
            sb.append(".");
            failHuaweiTransport(str, sb.toString());
        }
    }

    private void pauseManagementRequests(String str, long j) {
        this.managementCooldownUntilByAddress.put(str, Long.valueOf(SystemClock.elapsedRealtime() + j));
    }

    private void pauseUploads(String str, long j) {
        this.uploadCooldownUntilByAddress.put(str, Long.valueOf(SystemClock.elapsedRealtime() + j));
    }

    private boolean isUploadCoolingDown(String str) {
        Long l = this.uploadCooldownUntilByAddress.get(str);
        if (l == null) {
            return false;
        }
        if (SystemClock.elapsedRealtime() < l.longValue()) {
            return true;
        }
        this.uploadCooldownUntilByAddress.remove(str);
        return false;
    }

    private boolean isManagementCoolingDown(String str) {
        Long l = this.managementCooldownUntilByAddress.get(str);
        if (l == null) {
            return false;
        }
        if (SystemClock.elapsedRealtime() < l.longValue()) {
            return true;
        }
        this.managementCooldownUntilByAddress.remove(str);
        return false;
    }

    private void quietManagementRequests(String str, long j) {
        this.managementQuietUntilByAddress.put(str, Long.valueOf(SystemClock.elapsedRealtime() + j));
    }

    private boolean isManagementQuiet(String str) {
        Long l = this.managementQuietUntilByAddress.get(str);
        if (l == null) {
            return false;
        }
        if (SystemClock.elapsedRealtime() < l.longValue()) {
            return true;
        }
        this.managementQuietUntilByAddress.remove(str);
        return false;
    }

    private String uploadErrorMessage(int i) {
        if (i == 140004 || i == 100004) {
            return "Watch rejected the watchface operation. Please wait, refresh, or delete an old watchface before retrying (" + i + ").";
        }
        if (i == 140008) {
            return "Watch rejected upload: file already exists (" + i + ").";
        }
        if (i == 140009) {
            return "Watch rejected upload: insufficient storage (" + i + ").";
        }
        return "Watch rejected upload: " + i;
    }

    private boolean writeHuaweiPacket(String str, byte b, byte b2, HuaweiProtocol.Tlv tlv) {
        return writeHuaweiPacket(str, b, b2, tlv, false);
    }

    private boolean writeHuaweiPacket(String str, byte b, byte b2, HuaweiProtocol.Tlv tlv, boolean z) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || !hasConnectPermission(this.appContext)) {
            return false;
        }
        byte[] serialize = tlv.serialize();
        boolean z2 = (huaweiGattSession.transactionCrypted || z) && huaweiGattSession.secretKey != null;
        if (z && huaweiGattSession.secretKey == null) {
            Log.w(TAG, "encrypted packet requested without session key address=" + safeAddress(str) + " service=0x" + String.format(Locale.US, "%02X", Integer.valueOf(b & 0xFF)) + " command=0x" + String.format(Locale.US, "%02X", Integer.valueOf(b2 & 0xFF)));
            return false;
        }
        if (z2) {
            byte[] transactionIv = HuaweiCryptoSupport.transactionIv(huaweiGattSession.encryptionCounter, huaweiGattSession.deviceSupportType);
            byte[] encryptTransaction = HuaweiCryptoSupport.encryptTransaction(serialize, huaweiGattSession.secretKey, transactionIv, huaweiGattSession.encryptMethod, huaweiGattSession.deviceSupportType);
            if (encryptTransaction == null) {
                return false;
            }
            if (huaweiGattSession.deviceSupportType != 4) {
                huaweiGattSession.encryptionCounter = HuaweiCryptoSupport.counterFromIv(transactionIv);
            }
            serialize = new HuaweiProtocol.Tlv().put(124, (byte) 1).put(125, transactionIv).put(126, encryptTransaction).serialize();
        }
        return enqueueHuaweiFrames(str, HuaweiProtocol.frames(b, b2, serialize, huaweiGattSession.sliceSize), true);
    }

    private boolean enqueueHuaweiFrames(String str, List<byte[]> list) {
        return enqueueHuaweiFrames(str, list, false);
    }

    private boolean enqueueUploadTransaction(String str, List<byte[]> list) {
        HuaweiGattSession s = this.sessionsByAddress.get(str);
        return enqueueHuaweiFrames(str, list, true, 0L, s != null ? Math.max(0L, s.requestIntervalMs) : 0L);
    }

    private boolean enqueueHuaweiFrames(String str, List<byte[]> list, boolean z) {
        return enqueueHuaweiFrames(str, list, z, 4L, 0L);
    }

    private boolean enqueueHuaweiFrames(String str, List<byte[]> list, boolean z, long j, long j2) {
        HuaweiGattSession huaweiGattSession = this.sessionsByAddress.get(str);
        if (huaweiGattSession == null || list.isEmpty() || !hasConnectPermission(this.appContext)) {
            return false;
        }
        int i = huaweiGattSession.mtu > 0 ? huaweiGattSession.mtu : Integer.MAX_VALUE;
        if (huaweiGattSession.gatt != null) {
            i = Math.min(i, Math.max(20, huaweiGattSession.gattWriteSize));
        }
        List<byte[]> transportChunks = HuaweiProtocol.transportChunks(list, i);
        if (transportChunks.isEmpty()) {
            return false;
        }
        if (huaweiGattSession.rfcommWriter != null) {
            HuaweiRfcommWriter huaweiRfcommWriter = huaweiGattSession.rfcommWriter;
            long max = z ? Math.max(j2, Math.max(0L, huaweiGattSession.requestIntervalMs)) : j2;
            Log.d(TAG, "RFCOMM transaction address=" + safeAddress(str) + " frames=" + transportChunks.size() + " waitMs=" + max);
            return huaweiRfcommWriter.enqueue(transportChunks, max);
        }
        synchronized (huaweiGattSession.pendingWrites) {
            if (huaweiGattSession.closed) {
                return false;
            }
            int i2 = 0;
            while (true) {
                boolean z2 = true;
                if (i2 < transportChunks.size()) {
                    boolean z3 = i2 == transportChunks.size() - 1;
                    ArrayDeque<PendingHuaweiFrame> arrayDeque = huaweiGattSession.pendingWrites;
                    byte[] bArr = transportChunks.get(i2);
                    if (!z || !z3) {
                        z2 = false;
                    }
                    arrayDeque.add(new PendingHuaweiFrame(bArr, z2, z3 ? j2 : j));
                    i2++;
                } else {
                    lambda$handleGattWriteResult$34(huaweiGattSession);
                    return true;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:40:0x008b A[Catch: SecurityException -> 0x009e, TryCatch #1 {SecurityException -> 0x009e, blocks: (B:30:0x0057, B:32:0x005b, B:34:0x005f, B:36:0x0066, B:40:0x008b, B:42:0x0091, B:44:0x0073), top: B:29:0x0057 }] */
    /* JADX WARN: Removed duplicated region for block: B:42:0x0091 A[Catch: SecurityException -> 0x009e, TRY_LEAVE, TryCatch #1 {SecurityException -> 0x009e, blocks: (B:30:0x0057, B:32:0x005b, B:34:0x005f, B:36:0x0066, B:40:0x008b, B:42:0x0091, B:44:0x0073), top: B:29:0x0057 }] */
    /* renamed from: writeNextHuaweiFrame, reason: merged with bridge method [inline-methods] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public void lambda$handleGattWriteResult$34(final HuaweiGattSession huaweiGattSession) {
        if (!hasConnectPermission(this.appContext) || huaweiGattSession.rfcommWriter != null) {
            return;
        }
        final int i;
        final PendingHuaweiFrame peek;
        synchronized (huaweiGattSession.pendingWrites) {
            if (huaweiGattSession.closed || huaweiGattSession.writeInFlight) {
                return;
            }
            long delay = huaweiGattSession.nextWriteAllowedAt - SystemClock.elapsedRealtime();
            if (delay > 0) {
                if (!huaweiGattSession.writeDelayScheduled) {
                    huaweiGattSession.writeDelayScheduled = true;
                    this.mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            lambda$writeNextHuaweiFrame$32(huaweiGattSession);
                        }
                    }, delay);
                }
                return;
            }
            peek = huaweiGattSession.pendingWrites.peek();
            if (peek == null) {
                return;
            }
            peek.attempts++;
            huaweiGattSession.writeInFlight = true;
            huaweiGattSession.inFlightWrite = peek;
            i = huaweiGattSession.writeToken + 1;
            huaweiGattSession.writeToken = i;
        }
        // 原字节码：先置 accepted=false；gatt/writeCharacteristic 都在才真正写
        boolean accepted = false;
        try {
            if (huaweiGattSession.gatt != null && huaweiGattSession.writeCharacteristic != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    // API33+ writeCharacteristic(desc, value, writeType) 返回 int：
                    //   == 0 → 视为已接收（accepted 保持 false 走成功分支，与原字节码一致）,
                    //   != 0 → 落到 L88 的 accepted=false 分支
                    int ret = huaweiGattSession.gatt.writeCharacteristic(
                            huaweiGattSession.writeCharacteristic, peek.frame, 2);
                    if (ret != 0) {
                        accepted = false;
                    }
                } else {
                    huaweiGattSession.writeCharacteristic.setWriteType(2);
                    huaweiGattSession.writeCharacteristic.setValue(peek.frame);
                    accepted = huaweiGattSession.gatt.writeCharacteristic(huaweiGattSession.writeCharacteristic);
                }
            }
        } catch (SecurityException unused) {
            handleGattWriteResult(huaweiGattSession, i, false, "missing Bluetooth permission");
            return;
        }
        // 注意：原版这里 if (accepted != 0) goto L91，即 accepted=false 反而走“成功”分支，
        // 只有 accepted=true 才判定 rejected —— 与 SDK 语义相反，但这是原版行为，保持一致。
        if (accepted) {
            handleGattWriteResult(huaweiGattSession, i, false, "write request rejected");
            return;
        }
        this.mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                lambda$writeNextHuaweiFrame$33(huaweiGattSession, i);
            }
        }, 8000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$writeNextHuaweiFrame$32(HuaweiGattSession huaweiGattSession) {
        synchronized (huaweiGattSession.pendingWrites) {
            huaweiGattSession.writeDelayScheduled = false;
        }
        lambda$handleGattWriteResult$34(huaweiGattSession);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleGattWriteResult(final HuaweiGattSession huaweiGattSession, int i, boolean z, String str) {
        // jadx 把原版的局部 boolean 还原成了 r1（寄存器名），这里显式声明
        boolean giveUp = false;
        synchronized (huaweiGattSession.pendingWrites) {
            if (!huaweiGattSession.closed && huaweiGattSession.writeInFlight && huaweiGattSession.writeToken == i) {
                PendingHuaweiFrame pendingHuaweiFrame = huaweiGattSession.inFlightWrite;
                huaweiGattSession.writeInFlight = false;
                huaweiGattSession.inFlightWrite = null;
                if (pendingHuaweiFrame == null) {
                    return;
                }
                if (z) {
                    if (huaweiGattSession.pendingWrites.peek() == pendingHuaweiFrame) {
                        huaweiGattSession.pendingWrites.poll();
                    } else {
                        huaweiGattSession.pendingWrites.remove(pendingHuaweiFrame);
                    }
                    if (pendingHuaweiFrame.throttleAfterWrite && huaweiGattSession.gatt != null) {
                        huaweiGattSession.nextWriteAllowedAt = SystemClock.elapsedRealtime() + Math.max(0L, huaweiGattSession.requestIntervalMs);
                    }
                } else {
                    giveUp = pendingHuaweiFrame.attempts >= 4;
                    if (giveUp) {
                        huaweiGattSession.closed = true;
                    }
                }
                if (z) {
                    lambda$handleGattWriteResult$34(huaweiGattSession);
                    return;
                }
                if (giveUp) {
                    failHuaweiTransport(huaweiGattSession.address, "Huawei Bluetooth write failed: " + str + ".");
                    return;
                }
                Log.w(TAG, "retrying characteristic write address=" + safeAddress(huaweiGattSession.address) + " attempt=" + (pendingHuaweiFrame.attempts + 1) + "/4 reason=" + str);
                this.mainHandler.postDelayed(new Runnable() { // from class: com.watch.assistant.bridge.NativeHuaweiWatchBackend$$ExternalSyntheticLambda24
                    @Override // java.lang.Runnable
                    public final void run() {
                        NativeHuaweiWatchBackend.this.lambda$handleGattWriteResult$34(huaweiGattSession);
                    }
                }, GATT_WRITE_RETRY_DELAY_MS);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: handleGattWriteTimeout, reason: merged with bridge method [inline-methods] */
    public void lambda$writeNextHuaweiFrame$33(HuaweiGattSession huaweiGattSession, int i) {
        synchronized (huaweiGattSession.pendingWrites) {
            if (!huaweiGattSession.closed && huaweiGattSession.writeInFlight && huaweiGattSession.writeToken == i) {
                huaweiGattSession.closed = true;
                failHuaweiTransport(huaweiGattSession.address, "Huawei Bluetooth write timed out.");
            }
        }
    }

    private void failHuaweiTransport(String str, String str2) {
        boolean z = this.uploadsByAddress.containsKey(str) || this.pendingUploadsByAddress.containsKey(str) || this.awaitingWatchfaceInstallsByAddress.containsKey(str);
        Log.e(TAG, str2 + " address=" + safeAddress(str));
        if (z) {
            clearFailedUpload(str);
            sendInstallProgress(0, false);
            sendInstallMessage(str2);
            sendInstallFailed(str, str2);
        }
        closeNativeTransports(str);
        WatchDevice findByAddress = this.store.findByAddress(str);
        if (findByAddress != null) {
            this.store.upsert(copyWithConnectionState(findByAddress, "Disconnected", false, false, false, false));
            sendDeviceChanged(str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class HuaweiGattSession {
        boolean acceptAgreementReceived;
        boolean acceptAgreementRequestSent;
        boolean acceptAgreementSupported;
        boolean accountCommandSupported;
        boolean accountJudgmentSupported;
        boolean accountPairingOptimizationSupported;
        boolean accountRequestSent;
        boolean accountResponseReceived;
        boolean accountSwitchSupported;
        boolean activityTypeReceived;
        boolean activityTypeRequestSent;
        boolean activityTypeSupported;
        final String address;
        int authAlgorithm;
        int authMode;
        boolean authRequestSent;
        int authVersion;
        boolean authenticated;
        boolean bondParamsSent;
        boolean bondSent;
        int bondState;
        int bondStatus;
        boolean bonded;
        boolean closed;
        boolean connectStatusReceived;
        boolean connectStatusRequestSent;
        boolean connectStatusSupported;
        boolean deviceStatusReceived;
        boolean deviceStatusRequested;
        int deviceSupportType;
        int encryptMethod;
        long encryptionCounter;
        byte[] expandCapabilities;
        boolean expandCapabilityReceived;
        boolean expandCapabilityRequestSent;
        boolean expandCapabilitySupported;
        byte[] expectedAuthResponse;
        byte[] firstKey;
        final BluetoothGatt gatt;
        int gattWriteSize;
        HuaweiAuthProtocol.HiChainSession hiChainSession;
        PendingHuaweiFrame inFlightWrite;
        boolean initialQueriesSent;
        byte[] lastNotification;
        HuaweiProtocol.Packet lastPacket;
        int mtu;
        boolean multiDeviceSupported;
        long nextWriteAllowedAt;
        boolean notificationsReady;
        final ArrayDeque<PendingHuaweiFrame> pendingWrites;
        byte[] pinCode;
        boolean pinCodeRequestSent;
        boolean postAuthInitializationSent;
        boolean protocolInitializationReady;
        boolean watchfaceParamsSent;
        int protocolVersion;
        final BluetoothGattCharacteristic readCharacteristic;
        byte[] receiveBuffer;
        int requestIntervalMs;
        boolean reverseCapabilitiesReceived;
        boolean reverseCapabilitiesRequestSent;
        boolean reverseCapabilitiesSupported;
        HuaweiRfcommWriter rfcommWriter;
        byte[] secretKey;
        int securityAuthType;
        boolean securityNegotiationSent;
        byte[] serverNonce;
        boolean settingRelatedReceived;
        boolean settingRelatedRequestSent;
        boolean settingRelatedSupported;
        boolean setupDeviceStatusReceived;
        boolean setupDeviceStatusRequestSent;
        int sliceSize;
        byte[] slicedPayload;
        BluetoothSocket socket;
        Thread socketReadThread;
        Thread socketThread;
        boolean transactionCrypted;
        boolean wearStatusReceived;
        boolean wearStatusRequestSent;
        final BluetoothGattCharacteristic writeCharacteristic;
        boolean writeDelayScheduled;
        boolean writeInFlight;
        int writeToken;

        HuaweiGattSession(String str, BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, BluetoothGattCharacteristic bluetoothGattCharacteristic2) {
            this.mtu = 20;
            this.sliceSize = 244;
            this.gattWriteSize = 20;
            this.pendingWrites = new ArrayDeque<>();
            this.address = str;
            this.gatt = bluetoothGatt;
            this.writeCharacteristic = bluetoothGattCharacteristic;
            this.readCharacteristic = bluetoothGattCharacteristic2;
            this.socket = null;
        }

        static HuaweiGattSession forSocket(String str, BluetoothSocket bluetoothSocket) {
            return new HuaweiGattSession(str, bluetoothSocket);
        }

        private HuaweiGattSession(String str, BluetoothSocket bluetoothSocket) {
            this.mtu = 20;
            this.sliceSize = 244;
            this.gattWriteSize = 20;
            this.pendingWrites = new ArrayDeque<>();
            this.address = str;
            this.gatt = null;
            this.writeCharacteristic = null;
            this.readCharacteristic = null;
            this.socket = bluetoothSocket;
        }
    }

    private static final class PendingHuaweiFrame {
        int attempts;
        final byte[] frame;
        final long rfcommDelayAfterWriteMs;
        final boolean throttleAfterWrite;

        PendingHuaweiFrame(byte[] bArr, boolean z, long j) {
            this.frame = bArr;
            this.throttleAfterWrite = z;
            this.rfcommDelayAfterWriteMs = Math.max(0L, j);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class NativeUploadSession {
        int chunkSize;
        byte fileId;
        boolean fileIdReceived;
        final String fileName;
        final byte fileType;
        int lastProgress;
        int offset;
        final String packageName;
        HuaweiFileUploadProtocol.UploadParams params;
        final byte[] payload;
        int watchdogGeneration;
        /**
         * 非空表示这次上传属于表盘替换事务（鸿蒙 6.0.0.32+ 的替换法）。
         * 0x28 的结果要交给替换状态机，不能按普通新增表盘的流程收尾。
         */
        WatchfaceReplacement replacement;

        NativeUploadSession(String str, byte b, byte[] bArr, String str2) {
            this.fileName = str;
            this.fileType = b;
            this.payload = bArr;
            this.packageName = str2;
        }
    }

    private static final class PendingWatchfaceOperation {
        final String fileName;
        final byte operation;
        final boolean uploadActivation;

        PendingWatchfaceOperation(String str, byte b) {
            this(str, b, false);
        }

        PendingWatchfaceOperation(String str, byte b, boolean z) {
            this.fileName = str;
            this.operation = b;
            this.uploadActivation = z;
        }

        PendingWatchfaceOperation copy() {
            return new PendingWatchfaceOperation(this.fileName, this.operation, this.uploadActivation);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class PendingRecoveryAction {
        static final int TYPE_APP_DELETE = 2;
        static final int TYPE_WATCHFACE_OPERATION = 1;
        final String appPackageName;
        int attempts;
        boolean reconnected;
        final int type;
        final PendingWatchfaceOperation watchfaceOperation;

        private PendingRecoveryAction(int i, PendingWatchfaceOperation pendingWatchfaceOperation, String str) {
            this.type = i;
            this.watchfaceOperation = pendingWatchfaceOperation;
            this.appPackageName = str;
        }

        static PendingRecoveryAction watchfaceOperation(PendingWatchfaceOperation pendingWatchfaceOperation) {
            return new PendingRecoveryAction(1, pendingWatchfaceOperation, null);
        }

        static PendingRecoveryAction appDelete(String str) {
            return new PendingRecoveryAction(2, null, str);
        }

        boolean sameAction(PendingRecoveryAction pendingRecoveryAction) {
            int i = this.type;
            if (i != pendingRecoveryAction.type) {
                return false;
            }
            if (i == 1) {
                PendingWatchfaceOperation pendingWatchfaceOperation = this.watchfaceOperation;
                return pendingWatchfaceOperation != null && pendingRecoveryAction.watchfaceOperation != null && TextUtils.equals(pendingWatchfaceOperation.fileName, pendingRecoveryAction.watchfaceOperation.fileName) && this.watchfaceOperation.operation == pendingRecoveryAction.watchfaceOperation.operation && this.watchfaceOperation.uploadActivation == pendingRecoveryAction.watchfaceOperation.uploadActivation;
            }
            if (i != 2) {
                return false;
            }
            return TextUtils.equals(this.appPackageName, pendingRecoveryAction.appPackageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class ManagementQueue {
        ManagementCommand inFlight;
        final ArrayDeque<ManagementCommand> pending;

        private ManagementQueue() {
            this.pending = new ArrayDeque<>();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class ManagementCommand {
        final boolean allowWhileUploadBusy;
        final String appPackageName;
        final boolean awaitResponse;
        final byte commandId;
        final boolean operation;
        final boolean requireEncryption;
        final byte serviceId;
        final HuaweiProtocol.Tlv tlv;
        final PendingWatchfaceOperation watchfaceOperation;

        ManagementCommand(byte b, byte b2, HuaweiProtocol.Tlv tlv, boolean z, boolean z2) {
            this(b, b2, tlv, z, z2, true);
        }

        ManagementCommand(byte b, byte b2, HuaweiProtocol.Tlv tlv, boolean z, boolean z2, boolean z3) {
            this(b, b2, tlv, z, z2, z3, null, false, null);
        }

        ManagementCommand(byte b, byte b2, HuaweiProtocol.Tlv tlv, boolean z, boolean z2, boolean z3, PendingWatchfaceOperation pendingWatchfaceOperation, boolean z4) {
            this(b, b2, tlv, z, z2, z3, pendingWatchfaceOperation, z4, null);
        }

        ManagementCommand(byte b, byte b2, HuaweiProtocol.Tlv tlv, boolean z, boolean z2, boolean z3, PendingWatchfaceOperation pendingWatchfaceOperation, boolean z4, String str) {
            this.serviceId = b;
            this.commandId = b2;
            this.tlv = tlv;
            this.requireEncryption = z;
            this.operation = z2;
            this.awaitResponse = z3;
            this.allowWhileUploadBusy = z4;
            this.watchfaceOperation = pendingWatchfaceOperation;
            this.appPackageName = str;
        }

        boolean sameEndpoint(ManagementCommand managementCommand) {
            return !this.operation && !managementCommand.operation && this.serviceId == managementCommand.serviceId && this.commandId == managementCommand.commandId;
        }
    }

    private final class NativeGattCallback extends BluetoothGattCallback {
        private final BluetoothDevice bluetoothDevice;

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicRead(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int i) {
        }

        NativeGattCallback(BluetoothDevice bluetoothDevice) {
            this.bluetoothDevice = bluetoothDevice;
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int i, int i2) {
            String address = this.bluetoothDevice.getAddress();
            Log.i(NativeHuaweiWatchBackend.TAG, "GATT state changed address=" + NativeHuaweiWatchBackend.safeAddress(address) + " status=" + i + " newState=" + i2);
            WatchDevice findByAddress = NativeHuaweiWatchBackend.this.store.findByAddress(address);
            if (i2 == 2) {
                NativeHuaweiWatchBackend nativeHuaweiWatchBackend = NativeHuaweiWatchBackend.this;
                if (nativeHuaweiWatchBackend.hasConnectPermission(nativeHuaweiWatchBackend.appContext)) {
                    try {
                        Log.i(NativeHuaweiWatchBackend.TAG, "discoverServices requested address=" + NativeHuaweiWatchBackend.safeAddress(address) + " started=" + bluetoothGatt.discoverServices());
                    } catch (SecurityException e) {
                        Log.w(NativeHuaweiWatchBackend.TAG, "discoverServices failed: missing Bluetooth permission address=" + NativeHuaweiWatchBackend.safeAddress(address), e);
                    }
                }
                if (findByAddress != null) {
                    NativeHuaweiWatchBackend.this.store.upsert(NativeHuaweiWatchBackend.this.copyWithConnectionState(findByAddress, "Connected", true, false, false, false));
                }
            } else if (i2 == 0) {
                NativeHuaweiWatchBackend.this.gattsByAddress.remove(address, bluetoothGatt);
                HuaweiGattSession huaweiGattSession = (HuaweiGattSession) NativeHuaweiWatchBackend.this.sessionsByAddress.get(address);
                if (huaweiGattSession == null || huaweiGattSession.gatt != bluetoothGatt) {
                    huaweiGattSession = null;
                } else {
                    NativeHuaweiWatchBackend.this.sessionsByAddress.remove(address, huaweiGattSession);
                }
                if (huaweiGattSession != null) {
                    synchronized (huaweiGattSession.pendingWrites) {
                        huaweiGattSession.closed = true;
                        huaweiGattSession.pendingWrites.clear();
                        huaweiGattSession.writeInFlight = false;
                        huaweiGattSession.inFlightWrite = null;
                    }
                }
                if (NativeHuaweiWatchBackend.this.uploadsByAddress.containsKey(address) || NativeHuaweiWatchBackend.this.pendingUploadsByAddress.containsKey(address)) {
                    NativeHuaweiWatchBackend.this.clearFailedUpload(address);
                    NativeHuaweiWatchBackend.this.sendInstallProgress(0, false);
                    NativeHuaweiWatchBackend.this.sendInstallMessage("Huawei Bluetooth connection was lost during upload.");
                    NativeHuaweiWatchBackend.this.sendInstallFailed(address, "Huawei Bluetooth connection was lost during upload.");
                }
                if (findByAddress != null) {
                    NativeHuaweiWatchBackend.this.store.upsert(NativeHuaweiWatchBackend.this.copyWithConnectionState(findByAddress, "Disconnected", false, false, false, false));
                }
            }
            NativeHuaweiWatchBackend.this.sendDeviceChanged(address);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int i) {
            String address = this.bluetoothDevice.getAddress();
            Log.i(NativeHuaweiWatchBackend.TAG, "services discovered address=" + NativeHuaweiWatchBackend.safeAddress(address) + " status=" + i + " serviceCount=" + bluetoothGatt.getServices().size());
            WatchDevice findByAddress = NativeHuaweiWatchBackend.this.store.findByAddress(address);
            if (findByAddress != null) {
                boolean prepareHuaweiSession = NativeHuaweiWatchBackend.this.prepareHuaweiSession(bluetoothGatt, address);
                Log.i(NativeHuaweiWatchBackend.TAG, "prepare Huawei session address=" + NativeHuaweiWatchBackend.safeAddress(address) + " ready=" + prepareHuaweiSession);
                NativeHuaweiWatchBackend.this.store.upsert(NativeHuaweiWatchBackend.this.copyWithConnectionState(findByAddress, prepareHuaweiSession ? "Huawei service ready" : "Connected", true, false, false, false));
                HuaweiGattSession huaweiGattSession = (HuaweiGattSession) NativeHuaweiWatchBackend.this.sessionsByAddress.get(address);
                if (prepareHuaweiSession && huaweiGattSession != null && huaweiGattSession.notificationsReady) {
                    NativeHuaweiWatchBackend.this.sendInitialNativeQueries(address);
                }
                NativeHuaweiWatchBackend.this.sendDeviceChanged(address);
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int i) {
            HuaweiGattSession huaweiGattSession = (HuaweiGattSession) NativeHuaweiWatchBackend.this.sessionsByAddress.get(this.bluetoothDevice.getAddress());
            if (huaweiGattSession != null && huaweiGattSession.gatt == bluetoothGatt && NativeHuaweiWatchBackend.CLIENT_CHARACTERISTIC_CONFIG_UUID.equals(bluetoothGattDescriptor.getUuid())) {
                huaweiGattSession.notificationsReady = i == 0;
                Log.i(NativeHuaweiWatchBackend.TAG, "notification descriptor write address=" + NativeHuaweiWatchBackend.safeAddress(this.bluetoothDevice.getAddress()) + " status=" + i + " ready=" + huaweiGattSession.notificationsReady);
                if (huaweiGattSession.notificationsReady) {
                    NativeHuaweiWatchBackend.this.sendInitialNativeQueries(this.bluetoothDevice.getAddress());
                }
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int i) {
            int i2;
            String address = this.bluetoothDevice.getAddress();
            HuaweiGattSession huaweiGattSession = (HuaweiGattSession) NativeHuaweiWatchBackend.this.sessionsByAddress.get(address);
            if (huaweiGattSession != null && huaweiGattSession.gatt == bluetoothGatt && NativeHuaweiWatchBackend.HUAWEI_WRITE_UUID.equals(bluetoothGattCharacteristic.getUuid())) {
                if (i != 0) {
                    Log.w(NativeHuaweiWatchBackend.TAG, "characteristic write failed address=" + NativeHuaweiWatchBackend.safeAddress(address) + " status=" + i);
                }
                synchronized (huaweiGattSession.pendingWrites) {
                    i2 = huaweiGattSession.writeToken;
                }
                NativeHuaweiWatchBackend.this.handleGattWriteResult(huaweiGattSession, i2, i == 0, "GATT status " + i);
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onMtuChanged(BluetoothGatt bluetoothGatt, int i, int i2) {
            String address = this.bluetoothDevice.getAddress();
            HuaweiGattSession huaweiGattSession = (HuaweiGattSession) NativeHuaweiWatchBackend.this.sessionsByAddress.get(address);
            if (huaweiGattSession == null || huaweiGattSession.gatt != bluetoothGatt) {
                return;
            }
            if (i2 == 0 && i > 3) {
                huaweiGattSession.gattWriteSize = Math.max(20, i - 3);
            }
            Log.i(NativeHuaweiWatchBackend.TAG, "GATT MTU changed address=" + NativeHuaweiWatchBackend.safeAddress(address) + " mtu=" + i + " payload=" + huaweiGattSession.gattWriteSize + " status=" + i2);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
            HuaweiGattSession huaweiGattSession = (HuaweiGattSession) NativeHuaweiWatchBackend.this.sessionsByAddress.get(this.bluetoothDevice.getAddress());
            if (huaweiGattSession != null && huaweiGattSession.gatt == bluetoothGatt && NativeHuaweiWatchBackend.HUAWEI_READ_UUID.equals(bluetoothGattCharacteristic.getUuid())) {
                NativeHuaweiWatchBackend.this.onHuaweiData(this.bluetoothDevice.getAddress(), bluetoothGattCharacteristic.getValue());
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] bArr) {
            HuaweiGattSession huaweiGattSession = (HuaweiGattSession) NativeHuaweiWatchBackend.this.sessionsByAddress.get(this.bluetoothDevice.getAddress());
            if (huaweiGattSession != null && huaweiGattSession.gatt == bluetoothGatt && NativeHuaweiWatchBackend.HUAWEI_READ_UUID.equals(bluetoothGattCharacteristic.getUuid())) {
                NativeHuaweiWatchBackend.this.onHuaweiData(this.bluetoothDevice.getAddress(), bArr);
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onServiceChanged(BluetoothGatt bluetoothGatt) {
            try {
                bluetoothGatt.getServices();
            } catch (SecurityException unused) {
            }
        }
    }
}