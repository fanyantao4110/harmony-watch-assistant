# hap-sign-utils 网页 → 本地完整等价实现

对照对象：`/data/local/tmp/hsu2/index.html`（hap-sign-utils，网页版）
网页只做 **.hap / .bin → 已签名 .hap**，并且能 **已签名 hap → 未签名 .app**。
本目录 `sign_app.py` 与它的函数级一一对应，并补上网页缺的一半：**同时产出 .app**。

## 一、网页到底怎么识别文件（detectFileType，逐行确认）

| 判定 | 条件 | 网页给的操作 | 下载名 |
|---|---|---|---|
| `app` | zip 里有任意 `*.hap` | 开始签名 | `base-signed.hap` |
| `unsigned-hap` | zip 里有 `config.json` 或 `pack.info` | 打包为 app 并签名 | `base-signed.hap` |
| `signed-hap` | zip 里 `.bin` 首字节 0xBE 且尾 32B 含 `hw signed app   1000` | 📥 下载未签名 app | `base.app` |
| `bin` | 首字节 0xBE | 📥 下载未签名 app | `base.app` |

**关键点：`.app` 的构造只有一行**

```js
zip.file("entry-release-lite.hap", hapBuf, { binary: true, compression: "STORE" });
```

也就是说 `.app` **就是一个 zip，里面装着一个（或多个）签名后的 hap**，STORE 不压缩。
所以"网页会把 APP 变成 hap"是误读：它是 **.app ⇄ .hap 双向**，只是签名产物那边落成 `-signed.hap`，
而"下载未签名 app"按钮反过来把 hap 包成 .app。

## 二、签名流程（signBin，与网页完全一致）

```
unsigned bin（0xBE + bundleName + 逐文件[name][path][len][content]）
  + proBlock   8B  (type 0x02, len=p7b长度, offset=len(ub)+16)
  + signBlock  8B  (type 0x00, len=0, offset=上面+len(p7b))
  + p7b 原始字节
  ───────────── 到此为此 = 摘要覆盖范围
  slicedSHA256：每 4096 字节 sha256，拼起来再 sha256
  → byteContent： "1000" + size(2B) + 000100880006 + datalen(4B) + digest
  → CMS SignedData(sha256 / ECDSA，含 signingTime / contentType / messageDigest)
  + signHead 32B: "hw signed app   " + "1000" + subBlockSize(4B) + 00000002 00000000
```

## 三、本地用法

```bash
cd /storage/emulated/0/hap/腕连助手_项目全量导出

# 单个文件（自动嗅探 hap / app / bin）
python3 sign_app.py /sdcard/hap/斗地主_xxx.hap
#   → /sdcard/hap/app/斗地主_xxx-signed.app   ← App Pack，装表/上传用这个
#   → /sdcard/hap/app/斗地主_xxx-signed.hap   ← signed.bin 单文件 hap（网页同款）

# 只出未签名 .app（等价网页「📥 下载未签名 app」）
python3 sign_app.py xxx.hap --unsign

# 指定 profile / 输出目录 / 包名 / versionCode
python3 sign_app.py xxx.hap --p7b /root/wl_debug.p7b --out /sdcard/hap/app \
                   --bundle com.wlaz.hl6fym --vc keep

# 批量（目录里的 .hap/.app/.bin 全签）
python3 sign_batch.py /sdcard/hap --out /sdcard/hap/app
```

默认 profile 顺序：`/sdcard/hap/wl_release.p7b` → `/root/wl_debug.p7b` → `/root/wl_release.p7b`
默认私钥顺序：`/root/wl_key.pem` → `/root/agc_key.pem`（自动按公钥比对挑匹配的）

## 四、profile 类型差异（重要）

| p7b | type | 证书字段 | 是否绑 UDID |
|---|---|---|---|
| `wl_release.p7b` | release | `distribution-certificate` | **不绑**，可自由分发 |
| `wl_debug.p7b` | debug | `development-certificate` | **绑 UDID**，只能装登记过的手表 |

已核对：`wl_release.p7b` 的 distribution 证书公钥 == `/root/wl_key.pem` 公钥（匹配 True），
所以可以完全离线签 release，不需要联网、不需要 AGC。
因此 `development-certificate` / `distribution-certificate` **两种字段都自适应**（旧版 `sign_hap.py` 硬编码前者，喂 release 会 KeyError）。

## 五、自检（scripts 内置 verify，等价 verify_hap.py）

每次签名后自动跑 6 项：

- `marker`      尾部 32B 的 `hw signed app   1000`
- `struct`      proBlock/signBlock 偏移自洽
- `inner_digest` CMS econtent 内嵌 digest == 重算 slicedSHA256
- `msg_digest`  signedAttrs.messageDigest == sha256(econtent)
- `ecdsa`       叶子证书公钥验签通过
- `p7b_same`    内嵌 p7b 与输入 p7b 字节一致

实测 6 个包全部 `全部通过 ✅`，且 `.app → 再签` 往返一致。

## 六、已验证的往返链路

1. `signed-hap → .app`（拆 bin → 改 bundleName → 重签 → 包成 app）✅
2. `.app → 再签`（App Pack 内是已签名 hap 时能识别并解 bin）✅
3. `--unsign 出未签名 .app → 再签` ✅
4. 批量 6/6 ✅

## 七、和旧脚本的区别

| 项 | `sign_hap.py`（旧） | `sign_app.py`（新） |
|---|---|---|
| 输出 | 只有 `-signed.hap` | `-signed.app` + `-signed.hap` |
| profile 字段 | 硬编码 development | 两种自适应 |
| release 支持 | 直接 KeyError | ✅ |
| 输入 .app | 不支持 | ✅ |
| 可改 bundle/versionCode | 需手改参数 | `--bundle` / `--vc` |
| 自检 | 需另跑 verify_hap.py | 内置 6 项 |
| 批量 | `sign_any.py` 写死 debug | `sign_batch.py` 任意目录 |

## 八、注意

- `.app` 里那个 hap 的**文件名**：能从 `pack.info` 的 modules 拿到就按真实项目名（如 `entry-default.hap`），
  拿不到才回落到网页同款 `entry-release-lite.hap`。若手表/AGC 校名字，可手工改 `build_app(..., hap_name)`。
- 手表安装目前没有 `hdc` 可用，仍需走腕连助手的 Bridge 通道上传。
