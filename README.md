# 鸿蒙手表助手 (Harmony Watch Assistant)

Android 端的第三方华为/鸿蒙手表管理工具。通过蓝牙 RFCOMM 通道与手表直接通信，实现应用与表盘的安装、卸载、读取，以及 `.hap` / `.app` 包的本地签名与推送安装。

> 本项目与华为、荣耀无任何关联，非官方产品。所有协议实现均基于自有设备上的公开通信行为独立编写。

---

## 功能

### 设备连接
- 蓝牙扫描与配对，支持 Huawei Watch GT / FIT / Ultimate / D / GT Cyber / GT Runner 等系列
- 多设备配对记忆，自动重连上次使用的设备
- 华为账号鉴权通道（AuthKey 落盘，断开后仍展示上次设备并提示重连）

### 应用管理
- 读取手表已安装应用列表（名称、版本、图标、类型）
- 应用图标内存 LRU + 磁盘缓存（`cacheDir/imgcache/`，上限 32 MB，按最后修改时间淘汰）
- 右上角「安装」提供两种模式：
  - **直接安装**：仅 `.hap` 且已签名，原样交给安装引擎
  - **签名后再安装**：`.app` 或未签名 `.hap`，走 DevEco 签名流程后推送
- 卸载、覆盖安装

### 表盘管理
- 读取表盘列表，中文/英文名称按系统 Locale 自动请求
- 两种安装方式：
  - **直接安装**：`.hwt`，适用于鸿蒙 6.0.0.32 以下
  - **替换法安装**：适用于鸿蒙 6.0.0.32 及以上，把目标表盘替换为自制表盘
- 替换目标选择按可替换性排序：普通表盘 > 带可删除位的预置表盘 > 其它；视频/相册/贴纸扩展表盘禁用

### 签名工具链
- 内置纯 Java 实现的 HAP 签名（`HapSigner`）：hapToBin → proBlock/signBlock → slicedSHA256 → CMS(SHA256/ECDSA) → signHead
- 手写 ZIP 容器输出：`extract_version=10`、DOS 系统、无 extra 字段、文件按 STORED 顺序 —— 这是手表安装校验的硬性要求，用 `ZipOutputStream` 会写出 `extract_version=20` 导致安装后图标闪一下就消失
- DevEco 通道申请调试证书与 Profile，与 AGC 账号打通
- Python 侧独立实现（`sign_app.py` / `sign_hap.py` / `sign_web.py`），与网页签名工具行为一致

### 应用库
- 在线应用库浏览、下载、按设备型号与分类筛选
- 悬赏应用提交：弹窗展示说明，一键跳转邮件客户端附上已签名 hap 与收款码

---

## 目录结构

```
.
├── AndroidManifest.xml          # 清单（versionCode/versionName 唯一有效来源）
├── build_wl.sh                  # 构建脚本：aapt → javac → d8 → zipalign → apksigner
├── src/
│   ├── androidx/localbroadcastmanager/content/
│   │   └── LocalBroadcastManager.java   # 内联的 AndroidX 组件
│   ├── com/watch/assistant/bridge/      # 手表协议层
│   │   ├── HuaweiProtocol.java          # TLV 编解码、命令字
│   │   ├── HuaweiAuthProtocol.java      # 鉴权握手
│   │   ├── HuaweiAuthStore.java         # 设备名/账号/AuthKey 持久化
│   │   ├── HuaweiFileUploadProtocol.java# 文件分块上传
│   │   ├── HuaweiRfcommWriter.java      # RFCOMM 写队列
│   │   ├── NativeHuaweiWatchBackend.java# 后端主体：连接、列表、安装、表盘操作
│   │   ├── WatchfaceReplace.java        # 表盘替换状态机
│   │   └── ...                          # WatchDevice / WatchInstalledItem 等模型
│   └── com/wlaz/                        # UI 与业务
│       ├── MainActivity.java            # 应用库主页
│       ├── AppManagerActivity.java      # 应用/表盘管理
│       ├── DevicesActivity.java         # 设备列表
│       ├── PairActivity.java            # 配对（独立 Activity）
│       ├── HapSigner.java               # HAP 签名核心
│       ├── AgcApi.java                  # AGC / DevEco 证书与 Profile
│       ├── DevEco.java                  # DevEco 授权流程
│       ├── Dl.java                      # 下载、签名安装、悬赏弹窗
│       ├── OneKeyInstall.java           # 一键安装编排
│       └── ...
├── res/                         # 布局、drawable、values
├── assets/
│   ├── wl_key.pem               # HAP 签名私钥（EC / PKCS#8）
│   └── hsu_ca.pem               # 华为签名 CA 证书链（公开）
├── sign_app.py                  # .app → .hap 提取 + 签名
├── sign_hap.py                  # 网页签名工具的 Python 等价实现
├── sign_any.py                  # 任意 hap 签名
├── sign_batch.py                # 批量签名
├── sign_web.py                  # 网页版签名逻辑复刻
├── verify_hap.py                # 签名与结构校验
├── SIGN_APP_说明.md             # 签名工具说明
└── 鸿蒙手表助手_1.0.apk          # 发行版 APK（Release 附件名为 HarmonyWatchAssistant_1.0.apk）
```

> GitHub 的 Release 附件名只接受 ASCII，因此发行版附件用 `HarmonyWatchAssistant_1.0.apk`，与仓库内的中文名文件是同一个包（SIZE 232040 字节，SHA-256 见 Release 页）。

---

## 构建

### 依赖
- JDK 8 或更高
- Android SDK：
  - `build-tools/34.0.0`（提供 `d8`、`zipalign`、`apksigner`）
  - `platforms/android-34/android.jar`
- `aapt` 在 PATH 中（Debian/Ubuntu 可装 `aapt` 包）

### 命令

```bash
# 默认读 /root/android-sdk
bash build_wl.sh

# 自定义 SDK 路径与输出名
SDK=~/Android/Sdk OUT=app-debug.apk bash build_wl.sh
```

脚本内可覆盖的环境变量：`SDK`、`BT`、`PLAT`、`OUT`。

产物用 `debug.keystore` 签名（口令 `android`，别名 `androiddebugkey`）；文件不存在时脚本会自动生成。

> **版本号注意**：`versionCode` / `versionName` 以 `AndroidManifest.xml` 为唯一有效来源。`aapt` 的 `--version-code` / `--version-name` 参数不会覆盖清单中的值。

### 安装

```bash
adb push 鸿蒙手表助手_1.0.apk /data/local/tmp/
adb shell pm install -r -d /data/local/tmp/鸿蒙手表助手_1.0.apk
```

部分设备不支持直接从 `/sdcard` 安装，先拷到 `/data/local/tmp/` 更稳妥。

---

## 签名说明

`assets/wl_key.pem` 是 EC（secp256r1）私钥，用于对手表侧 HAP 做 CMS 签名：

1. `HapSigner.buildCms` 用 `SHA256withECDSA` 对 `signedAttrsSet`（contentType + signingTime + messageDigest 的 DER SET）签名
2. AGC 侧申请的调试证书必须与该私钥的公钥点配对，否则签出的包无法通过手表校验
3. `AgcApi.ensureDebugCert` 会逐张下载账号下已有证书并比对公钥点，命中则复用，未命中才用内置 CSR 申请新证书

⚠️ **私钥即签名权**。任何拿到这把私钥的人都能签出被手表信任的包。公开此私钥是为了让本仓库开箱可用；如果你要发布自己的构建，请替换为你自己的密钥对并重新申请 AGC 证书与 Profile。

`assets/hsu_ca.pem` 是华为签名根证书链（公开信息），仅用于构造 CMS 的证书集合，不构成秘密。

---

## 协议实现要点

几个踩过的坑，供参考：

- **`27/03` 回包里的 `need` 只有 1 字节**。取值函数如果用「长度 ≥ 4 才解析」的实现会静默返回 0，把固件的 `need=1`（请求传文件）读成 `need=0`（不要文件），导致表盘替换永远判失败。必须用正确处理 1/2/≥4 字节的取值函数。
- **表盘替换必须由回包驱动，不能用固定延时**。正确序列是 `27/01`（连接初始化）→ `27/02`（拉列表）→ `27/03`（发起替换）→ 收到 `need=1` → 传文件 → `27/05`（进度回调）→ 后置 `27/03` → `27/02`。若在列表回包到达前就发 `27/03`，固件会因目标 ID 陈旧而回 `need=0`。
- **表盘名称请求的 tag1 是语言码**：`1` = 其它语言（英文名），`2` = 中文。写死 `1` 会让中文表盘全部显示英文名。
- **`27/02` 走管理队列会被排队**，超时上限较短。列表请求应走独立队列。

---

## 第三方

本项目表盘替换逻辑（`WatchfaceManager` / `Wire` / `ReplacementState` / `VersionPolicy` / `FacePolicy` / `FileTransfer` 等）移植并改写自：

- **[WristMirror](https://github.com/Escosis/WristMirror)** — Copyright 2026 Escosis，Apache License 2.0

蓝牙协议与表盘操作的通信流程参考华为运动健康、Gadgetbridge 的公开实现，并独立重写。本仓库不包含上述应用的实现、业务 APK 或 SDK。

详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 应用库站点

应用库与下载站在另一个仓库维护：**[fanyantao4110/watchapp](https://github.com/fanyantao4110/watchapp)**（GitHub Pages）。

---

## 许可

[Apache License 2.0](LICENSE)

## 免责声明

本项目仅供个人在自己的设备上管理自有手表使用。因使用本工具导致的设备异常、数据丢失、保修失效等后果，作者不承担责任。请勿用于商业分发或任何侵犯他人权益的用途。