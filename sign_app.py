#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hap-sign-utils(index.html) 的完整离版本地等价实现
—— 与网页行为 1:1 对齐，并且补上网页缺的一半：同时输出 .app 和 .hap

与网页对应的函数：
    detectFileType    -> sniff()
    buildAppFromHap   -> build_app()
    buildHapFromFiles -> build_hap()
    hapToBin          -> hap_to_bin()
    signBin           -> sign_bin()
    signHapBuffers    -> sign_app()
    handleDownloadApp -> 输出 <base>-unsigned.app

用法:
    python3 sign_app.py <输入.hap|.app|.bin>            # 默认用 release p7b
    python3 sign_app.py <输入> --p7b /path/x.p7b
    python3 sign_app.py <输入> --out /sdcard/hap/app --bundle com.x.y --vc 0
    python3 sign_app.py <输入> --unsign                 # 只出未签名 .app

输出（都在 --out 目录）：
    <base>-signed.app    ← 真正拿去装/上传的 App Pack
    <base>-signed.hap    ← signed.bin 单文件 hap（网页同款）
    <base>-unsigned.app  ← 仅在 --unsign 时
"""
import hashlib, io, json, os, re, sys, zipfile

from asn1crypto import cms, core, x509 as asn1x509

W = os.path.dirname(os.path.abspath(__file__))
MAGIC = 0xBE
MARKER = b"hw signed app   1000"
CA_PEM = os.path.join(W, "hsu_ca.pem")

DEFAULT_P7B = ["/root/wl_debug.p7b",
               os.path.join(W, "wl_debug.p7b"),
               os.path.join(W, "wl_gt6_debug.p7b"),
               os.path.join(W, "out", "wl_gt6_debug.p7b")]
# 注意：绝不把 release profile 放进默认列表。
# 发布 profile 没有 debug-info.device-ids，手表一律拒装。
DEFAULT_KEY = ["/root/wl_key.pem", "/root/agc_key.pem"]

CERT_KEYS = ("distribution-certificate", "development-certificate")


# ══════════════════════ 1. 文件类型嗅探（对应 detectFileType） ══════════════════════
def sniff(name, raw):
    ext = (name.rsplit(".", 1)[-1] or "").lower()
    if ext == "p7b":
        return "p7b"
    if raw[:1] == bytes([MAGIC]):
        return "bin"
    if raw[:2] == b"PK":
        try:
            zf = zipfile.ZipFile(io.BytesIO(raw))
            names = [n for n in zf.namelist() if not n.endswith("/")]
            if any(n.endswith(".hap") for n in names):
                return "app"
            if any(n == "config.json" or n.endswith("/config.json") for n in names) \
               or any(n == "pack.info" or n.endswith("/pack.info") for n in names):
                return "unsigned-hap"
            for n in names:
                if n.endswith(".bin"):
                    d = zf.read(n)
                    if len(d) >= 32 and d[:1] == bytes([MAGIC]) \
                       and d[-32:-12] == MARKER:
                        return "signed-hap"
            return "unknown"
        except Exception:
            return "unknown"
    return "unknown"


# ══════════════════════ 2. bin 解析 / 构建 ══════════════════════
def parse_bin(data):
    """返回 (bundle, files:dict, p7b_bytes|None)。已签名/未签名都能吃。"""
    assert data[0] == MAGIC, "不是有效的 .bin（魔数 0xBE 不匹配）"
    n = int.from_bytes(data[1:5], "big")
    bundle = data[5:5 + n].decode()
    files, p7b, unsigned_end = {}, None, len(data)

    if len(data) >= 32 and data[-32:-12] == MARKER:
        sub = int.from_bytes(data[-12:-8], "big")
        cands = [len(data) - sub, len(data) - sub - 8,
                 len(data) - sub - 16, len(data) - sub + 8]
        for pos in cands:
            if pos < 10 or pos + 8 > len(data) - 32:
                continue
            if not (data[pos] == 0x02 or (data[pos] == 0x01 and data[pos + 1] == 0x00)):
                continue
            ln = int.from_bytes(data[pos + 2:pos + 4], "big")
            off = int.from_bytes(data[pos + 4:pos + 8], "big")
            if off == pos + 16 and off + ln <= len(data) - 32:
                unsigned_end, p7b = pos, data[off:off + ln]
                break

    pos = 5 + n
    while pos + 4 <= unsigned_end:
        nl = int.from_bytes(data[pos:pos + 4], "big")
        if nl == 0 or nl > 4096 or pos + 4 + nl > unsigned_end:
            break
        fname = data[pos + 4:pos + 4 + nl].decode()
        pos += 4 + nl
        pl = int.from_bytes(data[pos:pos + 4], "big")
        if pos + 4 + pl > unsigned_end:
            break
        rel = data[pos + 4:pos + 4 + pl].decode()
        pos += 4 + pl
        cl = int.from_bytes(data[pos:pos + 8], "big")
        pos += 8
        full = (rel.lstrip("/") + "/" + fname) if rel.strip("/") else fname
        files[full] = data[pos:pos + cl]
        pos += cl
    return bundle, files, p7b


def hap_to_bin(files, bundle, sort=False):
    """拼 unsigned bin。

    ★ 必须保留【原始文件顺序】！
    实测基准可安装包（2048新版）的 bin 里，文件序是 hvigor 构建时的原生顺序，
    例如 base/media 下是 icon_small.bin -> icon_small.png -> icon.bin -> icon.png，
    完全不是字典序。我们之前用 sorted() 强制按字母重排，文件集合与内容都对、
    签名也验得过，但手表端按顺序校验资源表后拒装。
    所以默认 sort=False，按 dict 插入序（即原包解析出的顺序）原样输出。
    """
    out = bytearray([MAGIC])
    bb = bundle.encode()
    out += len(bb).to_bytes(4, "big") + bb
    keys = sorted(files, key=lambda p: (p.count("/"), p)) if sort else list(files)
    for full in keys:
        content = files[full]
        fn = full.split("/")[-1]
        rel = "/" + full[:full.rfind("/")] if "/" in full else ""
        fb, rb = fn.encode(), rel.encode()
        out += len(fb).to_bytes(4, "big") + fb
        out += len(rb).to_bytes(4, "big") + rb
        out += len(content).to_bytes(8, "big") + content
    return bytes(out)


def build_hap(files):
    """手工拼 zip（只有一个 STORED 条目 signed.bin），
    ★ 关键：与基准可安装包逐字段对齐
       LFH/CDH extract_version = 10、create_system = 0(DOS)、flags=0、extra=0、comment=0。
       Python zipfile 会强制写 20/3(Unix)，手表端对容器头敏感，所以这里手写。
    """
    import binascii
    import struct as _st
    dt = (2026 - 1980 << 9) | (11 << 5) | 18   # 2026-11-18
    tm = (12 << 11) | (35 << 5) | 14 // 2      # 12:35:14
    lfh = []
    cdh = []
    offset = 0
    for name, data in files.items():
        nb = name.encode()
        crc = binascii.crc32(data) & 0xFFFFFFFF
        sz = len(data)
        # Local File Header
        lfh.append(_st.pack("<IHHHHHIIIHH", 0x04034B50, 10, 0, 0,
                            tm, dt, crc, sz, sz, len(nb), 0))
        lfh.append(nb)
        lfh.append(data)
        # Central Directory Header
        cdh.append(_st.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 0x0000, 10,
                            0, 0, tm, dt, crc, sz, sz, len(nb), 0, 0, 0, 0, 0, offset))
        cdh.append(nb)
        offset += 30 + len(nb) + sz
    body = b"".join(lfh)
    cdir = b"".join(cdh)
    eocd = _st.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(files), len(files),
                    len(cdir), len(body), 0)
    return body + cdir + eocd


def build_app(hap_buf, hap_name="entry-release-lite.hap", extra=None):
    """对应网页 buildAppFromHap：.app 就是一个只装 hap 的 zip（STORE）"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zo:
        if extra:
            for p, d in extra.items():
                zo.writestr(p, d)
        zo.writestr(hap_name, hap_buf)
    return buf.getvalue()


# ══════════════════════ 3. 签名原语 ══════════════════════
def sliced_sha256(data, chunk=4096):
    h = bytearray()
    for off in range(0, len(data), chunk):
        h += hashlib.sha256(data[off:off + chunk]).digest()
    return hashlib.sha256(bytes(h)).digest()


def block_head(t, tag, length, offset):
    return bytes([t, tag,
                  (length >> 8) & 0xFF, length & 0xFF,
                  (offset >> 24) & 0xFF, (offset >> 16) & 0xFF,
                  (offset >> 8) & 0xFF, offset & 0xFF])


def sign_head(sub_size):
    return b"hw signed app   " + b"1000" + sub_size.to_bytes(4, "big") \
        + b"\x00\x00\x00\x02\x00\x00\x00\x00"


def byte_content(d):
    size = 8 + 8 + len(d)
    return b"1000" + size.to_bytes(2, "big") + b"\x00\x01\x00\x88\x00\x06" \
        + len(d).to_bytes(4, "big") + d


def order_chain(pems):
    from cryptography import x509 as cx
    infos = []
    for p in pems:
        c = cx.load_pem_x509_certificate(p)
        infos.append((p, c.subject.rfc4514_string(), c.issuer.rfc4514_string()))
    leaf = next((i for i in infos if not any(j[2] == i[1] for j in infos)), None)
    if not leaf:
        raise SystemExit("证书链里找不到叶子证书")
    out, cur, seen = [], leaf, set()
    while cur:
        out.append(cur[0])
        if cur[1] == cur[2] or cur[1] in seen:
            break
        seen.add(cur[1])
        cur = next((i for i in infos if i[1] == cur[2]), None)
    return out


def pem_certs(b):
    return re.findall(rb"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", b, re.S)


def get_time_str(ts):
    import datetime
    d = datetime.datetime.utcfromtimestamp(ts)
    return d.strftime("%Y%m%d%H%M%SZ")


def build_cms(econtent, leaf_pem, chain, key, sign_time):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    attrs = cms.CMSAttributes([
        cms.CMSAttribute({"type": "signing_time", "values": [core.UTCTime(sign_time)]}),
        cms.CMSAttribute({"type": "content_type", "values": ["data"]}),
        cms.CMSAttribute({"type": "message_digest",
                          "values": [core.OctetString(hashlib.sha256(econtent).digest())]}),
    ])
    sig = key.sign(attrs.dump(), ec.ECDSA(hashes.SHA256()))
    from cryptography import x509 as cx
    from cryptography.hazmat.primitives import serialization
    leaf = asn1x509.Certificate.load(
        cx.load_pem_x509_certificate(leaf_pem)
        .public_bytes(serialization.Encoding.DER))
    chain_certs = []
    for p in chain:
        chain_certs.append(asn1x509.Certificate.load(
            cx.load_pem_x509_certificate(p)
            .public_bytes(serialization.Encoding.DER)))
    si = cms.SignerInfo({
        "version": "v1",
        "sid": cms.SignerIdentifier({"issuer_and_serial_number": cms.IssuerAndSerialNumber({
            "issuer": leaf.issuer, "serial_number": leaf.serial_number})}),
        "digest_algorithm": {"algorithm": "sha256"},
        "signed_attrs": attrs,
        "signature_algorithm": {"algorithm": "sha256_ecdsa"},
        "signature": sig,
    })
    sd = cms.SignedData({
        "version": "v1",
        "digest_algorithms": [{"algorithm": "sha256"}],
        "encap_content_info": {"content_type": "data", "content": econtent},
        "certificates": [cms.CertificateChoices({"certificate": c}) for c in [leaf] + chain_certs],
        "signer_infos": [si],
    })
    return cms.ContentInfo({"content_type": "signed_data", "content": sd}).dump()


def sign_bin(files, bundle, p7b, leaf_pem, chain_pems, key, sign_time):
    """对应网页 signBin：unsigned bin + proBlock + signBlock + p7b + CMS + signHead"""
    ub = hap_to_bin(files, bundle)
    off_pro = len(ub) + 8 + 8
    off_sign = off_pro + len(p7b)
    blob = bytearray(ub)
    blob += block_head(0x02, 0x00, len(p7b), off_pro)
    blob += block_head(0x00, 0x00, 0, off_sign)
    blob += p7b
    assert len(blob) == off_sign

    econtent = byte_content(sliced_sha256(bytes(blob)))
    cms_der = build_cms(econtent, leaf_pem, chain_pems, key, sign_time)
    total = off_sign + len(cms_der)
    sub = total - len(ub) + 32
    return bytes(blob) + cms_der + sign_head(sub)


# ══════════════════════ 4. profile 读取 ══════════════════════
def load_profile(p7b_path):
    from cryptography import x509 as cx
    from cryptography.hazmat.primitives import serialization
    raw = open(p7b_path, "rb").read()
    prof = json.loads(cms.ContentInfo.load(raw)["content"]
                      ["encap_content_info"]["content"].native)
    bi = prof["bundle-info"]
    # ★★ 硬校验：手表只认调试 profile（带 device-ids 白名单）
    ptype = (prof.get("type") or "").strip()
    if ptype.lower() != "debug":
        raise SystemExit("!! 只接受调试 Profile（type=debug），当前 type=%r（%s）" % (ptype, p7b_path))
    devs = (prof.get("debug-info") or {}).get("device-ids") or []
    if not devs:
        raise SystemExit("!! 该调试 Profile 未绑定任何设备（debug-info.device-ids 为空）：%s" % p7b_path)
    ck = next((k for k in CERT_KEYS if k in bi), None)
    if not ck:
        raise SystemExit("profile 里既没有 distribution-certificate 也没有 development-certificate")
    leaf_pem = bi[ck].encode()
    leaf = cx.load_pem_x509_certificate(leaf_pem)

    key = None
    for kp in DEFAULT_KEY:
        if os.path.exists(kp):
            k = serialization.load_pem_private_key(open(kp, "rb").read(), None)
            if k.public_key().public_numbers() == leaf.public_key().public_numbers():
                key, key_path = k, kp
                break
    if key is None:
        raise SystemExit("找不到与 profile 证书匹配的私钥（试过 %s）" % DEFAULT_KEY)

    chain = order_chain(pem_certs(open(CA_PEM, "rb").read()) + pem_certs(leaf_pem))
    return {
        "raw": raw, "prof": prof, "type": prof.get("type"), "cert_kind": ck,
        "bundle": bi["bundle-name"], "leaf_pem": chain[0], "chain": chain[1:],
        "key": key, "key_path": key_path,
        "sign_time": get_time_str(prof["validity"]["not-before"]),
        "validity": prof.get("validity"),
        "apl": bi.get("apl"),
    }


# ══════════════════════ 5. 主流程 ══════════════════════
def load_input(path):
    """统一取出 files / bundle / 原始 p7b，对应网页 processMainFile"""
    raw = open(path, "rb").read()
    name = os.path.basename(path)
    t = sniff(name, raw)
    if t == "unknown":
        raise SystemExit("无法识别的文件格式（既不是 .app/.hap/.bin，也不是 .p7b）")

    files, bundle, p7b = {}, "", None
    if t == "app":
        zf = zipfile.ZipFile(io.BytesIO(raw))
        he = next((n for n in zf.namelist()
                   if not n.endswith("/") and n.endswith(".hap")), None)
        if not he:
            raise SystemExit(".app 中未找到 .hap 文件")
        hap_raw = zf.read(he)
        hz = zipfile.ZipFile(io.BytesIO(hap_raw))
        inner = [n for n in hz.namelist() if not n.endswith("/")]
        if any(n == "config.json" or n.endswith("/config.json") for n in inner):
            # 未签名 App Pack：hap 里是明文文件
            for n in inner:
                files[n] = hz.read(n)
        else:
            # 已签名 App Pack：hap 里只有一个 signed.bin
            be = next((n for n in inner if n.endswith(".bin")), None)
            if not be:
                raise SystemExit(".app 内 hap 既无 config.json 也无 .bin，格式不正确")
            bundle, files, p7b = parse_bin(hz.read(be))
    elif t == "unsigned-hap":
        zf = zipfile.ZipFile(io.BytesIO(raw))
        for n in zf.namelist():
            if not n.endswith("/"):
                files[n] = zf.read(n)
    elif t == "signed-hap":
        zf = zipfile.ZipFile(io.BytesIO(raw))
        be = next((n for n in zf.namelist()
                   if not n.endswith("/") and n.endswith(".bin")), None)
        if not be:
            raise SystemExit("已签名 hap 中未找到 .bin")
        bundle, files, p7b = parse_bin(zf.read(be))
    elif t == "bin":
        bundle, files, p7b = parse_bin(raw)
    else:
        raise SystemExit("%s 不支持" % t)
    return t, raw, files, bundle, p7b


def patch_meta(files, bundle, vc):
    if "config.json" not in files:
        raise SystemExit("hap 内没有 config.json，无法改包名")
    cfg = json.loads(files["config.json"].decode())
    cfg.setdefault("app", {})
    cfg["app"]["bundleName"] = bundle
    if vc is not None and "version" in cfg["app"]:
        cfg["app"]["version"]["code"] = vc
    files["config.json"] = json.dumps(cfg, separators=(",", ":"),
                                      ensure_ascii=False).encode()
    # ★★ pack.info 绝对不能动！
    # 实测：基准可安装包（2048新版）的 pack.info 里 bundleName 仍是原始包名，
    # 网页签名工具原样保留整段字节（连缩进换行都不改）。
    # 之前这里用 json.loads + json.dumps 重新序列化，导致 1151 字节被压成 478 字节，
    # 手表端校验 pack.info 后直接拒装 —— 这才是"签名都对但装不上"的真因。
    return cfg


def module_hap_name(files, cfg):
    """真实 .app 里 hap 名形如 entry-default.hap；拿不到就用网页同款默认名"""
    try:
        pi = json.loads(files["pack.info"].decode())
        m = (pi.get("summary", {}).get("modules") or [{}])[0]
        n, ty = m.get("name"), m.get("type")
        if n:
            return "%s-%s.hap" % (n, ty or "default")
    except Exception:
        pass
    m = cfg.get("module") or {}
    if m.get("name"):
        return "%s-%s.hap" % (m["name"], m.get("type") or "entry")
    return "entry-release-lite.hap"


def verify(signed_bin_blob, prof):
    """对应 verify_hap.py 的三项自检"""
    data = signed_bin_blob
    ok = {"marker": data[-32:-12] == MARKER}
    n = int.from_bytes(data[1:5], "big")
    sub = int.from_bytes(data[-12:-8], "big")
    total = len(data)
    body = data[:total - 32]
    pro = None
    for pos in (total - sub, total - sub - 8, total - sub - 16):
        if pos < 10:
            continue
        if not (body[pos] == 0x02 or (body[pos] == 0x01 and body[pos + 1] == 0x00)):
            continue
        ln = int.from_bytes(body[pos + 2:pos + 4], "big")
        off = int.from_bytes(body[pos + 4:pos + 8], "big")
        if off == pos + 16:
            pro = (pos, ln, off)
            break
    if not pro:
        ok["struct"] = False
        return ok
    pos, ln, off = pro
    sign_off = int.from_bytes(body[pos + 8 + 4:pos + 8 + 8], "big")
    ub_len = pos
    p7b = body[off:off + ln]
    cms_bytes = body[sign_off:]
    sign_area = data[:ub_len + 16 + len(p7b)]
    h = bytearray()
    for o in range(0, len(sign_area), 4096):
        h += hashlib.sha256(sign_area[o:o + 4096]).digest()
    digest = hashlib.sha256(bytes(h)).digest()

    ci = cms.ContentInfo.load(cms_bytes)
    sd = ci["content"]
    ec = sd["encap_content_info"]["content"].native
    ok["struct"] = True
    ok["inner_digest"] = ec[16:48] == digest
    si = sd["signer_infos"][0]
    md = None
    for a in si["signed_attrs"]:
        if a["type"].native == "message_digest":
            md = a["values"][0].native
    ok["msg_digest"] = md == hashlib.sha256(ec).digest()

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec as xec
    from cryptography import x509 as cx
    leaf = cx.load_pem_x509_certificate(prof["leaf_pem"])
    raw_attrs = si["signed_attrs"].dump()
    ok["ecdsa"] = False
    for cand in (b"\x31" + raw_attrs[1:], raw_attrs):
        try:
            leaf.public_key().verify(si["signature"].native, cand,
                                     xec.ECDSA(hashes.SHA256()))
            ok["ecdsa"] = True
            break
        except Exception:
            pass
    ok["p7b_same"] = p7b == prof["raw"]
    ok["all"] = all(ok.get(k) for k in
                    ("marker", "struct", "inner_digest", "msg_digest", "ecdsa"))
    return ok


def pick(argv, flag, default=None):
    if flag in argv:
        return argv[argv.index(flag) + 1]
    return default


def main():
    args = [a for a in sys.argv[1:]]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return
    src = args[0]
    out_dir = pick(args, "--out", "/sdcard/hap/app")
    p7b_path = pick(args, "--p7b")
    if not p7b_path:
        p7b_path = next((p for p in DEFAULT_P7B if os.path.exists(p)), None)
    if not p7b_path:
        raise SystemExit("找不到 p7b，请用 --p7b 指定")
    bundle_override = pick(args, "--bundle")
    vc = pick(args, "--vc", "0")
    vc = None if vc in ("keep", "none") else int(vc)
    unsign_only = "--unsign" in args
    os.makedirs(out_dir, exist_ok=True)

    prof = load_profile(p7b_path)
    bundle = bundle_override or prof["bundle"]
    print("profile :", p7b_path)
    print("  type  :", prof["type"], "| cert:", prof["cert_kind"])
    print("  bundle:", prof["bundle"], "-> 使用:", bundle)
    print("  有效期:", prof["validity"], "| signTime:", prof["sign_time"])
    print("  key   :", prof["key_path"])

    t, raw, files, in_bundle, in_p7b = load_input(src)
    base = os.path.basename(src).rsplit(".", 1)[0]
    print("\n输入    :", os.path.basename(src), "| 类型:", t, "| 文件数:", len(files))

    cfg = patch_meta(files, bundle, vc)
    hap_name = module_hap_name(files, cfg)
    unsigned_hap = build_hap(files)
    print("重建 hap:", len(unsigned_hap), "字节 | app 内名字:", hap_name)

    if unsign_only:
        dst = os.path.join(out_dir, base + "-unsigned.app")
        open(dst, "wb").write(build_app(unsigned_hap, hap_name))
        print("输出    :", dst, os.path.getsize(dst))
        return

    print("签名中 …")
    signed_bin = sign_bin(files, bundle, prof["raw"], prof["leaf_pem"],
                          prof["chain"], prof["key"], prof["sign_time"])

    # ① signed.hap = 只含 signed.bin（网页同款）
    dst_hap = os.path.join(out_dir, base + "-signed.hap")
    # ★ 用 build_hap 手写 zip 容器（extract_version=10 / create_system=0），
    # 不能直接用 zipfile，它会强制写 20/3(Unix)，与基准可安装包不一致。
    open(dst_hap, "wb").write(build_hap({"signed.bin": signed_bin}))

    # ② signed.app = App Pack，里面装这个 signed.hap
    dst_app = os.path.join(out_dir, base + "-signed.app")
    open(dst_app, "wb").write(build_app(zipfile_bytes(signed_bin), hap_name))

    print("\nsigned.bin :", len(signed_bin), "字节")
    print("输出 .hap  :", dst_hap, os.path.getsize(dst_hap))
    print("输出 .app  :", dst_app, os.path.getsize(dst_app))

    ok = verify(signed_bin, prof)
    print("\n自检:")
    for k in ("marker", "struct", "inner_digest", "msg_digest", "ecdsa", "p7b_same"):
        if k in ok:
            print("   %-13s %s" % (k, "OK" if ok[k] else "FAIL"))
    print("   =>", "全部通过 ✅" if ok.get("all") else "存在问题 ❌")


def zipfile_bytes(signed_bin):
    """把 signed.bin 打成 hap（zip），返回 hap 字节"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zo:
        zo.writestr("signed.bin", signed_bin)
    return buf.getvalue()


if __name__ == "__main__":
    main()