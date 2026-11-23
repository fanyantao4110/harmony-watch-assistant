# 第三方说明

本项目采用 [Apache License 2.0](LICENSE)。

## 移植来源

表盘替换逻辑（`27/01` 连接初始化、`27/02` 列表、`27/03` 替换、`27/05` 进度回调、`need` 回执驱动、`VersionPolicy` 版本推进、`FacePolicy` 目标筛选、`FileTransfer` 分块传输等）移植并改写自：

- **[WristMirror](https://github.com/Escosis/WristMirror)**
  - Copyright 2026 Escosis
  - Apache License 2.0
  - 涉及上游模块：`transport/WatchfaceManager`、`transport/WatchConnection`、`protocol/Wire`、`protocol/WireNames`、`protocol/ReplacementState`、`protocol/TlvView`、`protocol/VersionPolicy`、`protocol/FacePolicy`、`protocol/FileTransfer`

## 协议参考

蓝牙 RFCOMM 通信流程与 TLV 命令结构参考以下公开资料，并据此独立实现：

- 华为运动健康（Huawei Health）的公开通信行为
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)（GPL-3.0，**本项目未使用其代码**，仅参考公开协议文档与抓包分析结论）
- 腕连手表助手 3.4 的公开可见行为（**不包含其反编译代码或资源**）

本仓库不包含上述任何应用的源代码、二进制产物、业务 APK 或 SDK。

## 内联组件

- `src/androidx/localbroadcastmanager/content/LocalBroadcastManager.java`
  - 来自 [AndroidX](https://developer.android.com/jetpack/androidx)，Apache License 2.0
  - Copyright (C) 2014 The Android Open Source Project
  - 内联原因：本项目的构建脚本不依赖 Gradle / AndroidX 仓库，为避免引入完整依赖链而内联单个类

## 图标与素材

`res/` 下图标为本项目自行绘制或由 Android 开源项目矢量图标转换而来，后者保留 Apache 2.0 许可。
