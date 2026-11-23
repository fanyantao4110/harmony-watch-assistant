#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hap-sign-utils 的纯 Python 等价实现（结构完全照 index.html 复刻）
用法:
  python3 sign_hap.py <输入.hap> <输出.hap> <profile.p7b> [--bundle com.xxx]
流程: 已签名 hap -> 抠出 bin -> 拆出文件 -> 改 bundleName/versionCode -> 重建 hap
      -> hapToBin -> 追加 proBlock/signBlock -> slicedSHA256 -> CMS(sha256/ECDSA)
      -> 追加 signHead -> 打成 signed.bin 的 hap
"""
import hashlib, io, json, os, re, sys, zipfile
from asn1crypto import cms, core, x509 as asn1x509

W = "/storage/emulated/0/hap/腕连助手_项目全量导出"
KEY_PEM = "/root/wl_key.pem"          # 与 AGC 证书匹配的 EC 私钥
CA_PEM = os.path.join(W, "hsu_ca.pem")  # 华为根+中间 CA（工具内置）
MAGIC = 0xBE
MARKER = b"hw signed app   1000"


# ---------- bin 解析 ----------
def parse_bin(data):
    assert data[0] == MAGIC, "魔数不对"
    n = int.from_bytes(data[1:5], "big")
    bundle = data[5:5 + n].decode()
    files, p7b, unsigned_end = [], None, len(data)
    if len(data) >= 32 and data[-32:-12] == MARKER:
        sub = int.from_bytes(data[-12:-8], "big")
        for pos in (len(data) - sub, len(data) - sub - 8, len(data) - sub - 16, len(data) - sub + 8):
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
        name = data[pos + 4:pos + 4 + nl].decode()
        pos += 4 + nl
        pl = int.from_bytes(data[pos:pos + 4], "big")
        if pos + 4 + pl > unsigned_end:
            break
        path = data[pos + 4:pos + 4 + pl].decode()
        pos += 4 + pl
        cl = int.from_bytes(data[pos:pos + 8], "big")
        pos += 8
        files.append((path, name, data[pos:pos + cl]))
        pos += cl
    return bundle, files, p7b


def find_bin(zf):
    for i in zf.infolist():
        if i.filename.endswith(".bin"):
            d = zf.read(i.filename)
            if d[:1] == b"\xbe":
                return d
    return None


# ---------- bin 构建 ----------
def hap_to_bin(files: dict, bundle: str):
    out = bytearray()
    out += bytes([MAGIC])
    bb = bundle.encode()
    out += len(bb).to_bytes(4, "big") + bb
    items = sorted(files.items(), key=lambda kv: (kv[0].count("/"), kv[0]))
    for full, content in items:
        fn = full.split("/")[-1]
        rel = "/" + full[:full.rfind("/")] if "/" in full else ""
        fb, rb = fn.encode(), rel.encode()
        out += len(fb).to_bytes(4, "big") + fb
        out += len(rb).to_bytes(4, "big") + rb
        out += len(content).to_bytes(8, "big") + content
    return bytes(out)


def block_head(t, tag, length, offset):
    return bytes([t, tag, (length >> 8) & 0xff, length & 0xff,
                  (offset >> 24) & 0xff, (offset >> 16) & 0xff,
                  (offset >> 8) & 0xff, offset & 0xff])


def sign_head(sub_size):
    return b"hw signed app   " + b"1000" + sub_size.to_bytes(4, "big") + b"\x00\x00\x00\x02\x00\x00\x00\x00"


def sliced_sha256(data, chunk=4096):
    h = bytearray()
    for off in range(0, len(data), chunk):
        h += hashlib.sha256(data[off:off + chunk]).digest()
    return hashlib.sha256(bytes(h)).digest()


def byte_content(d: bytes):
    size = 8 + 8 + len(d)
    return b"1000" + size.to_bytes(2, "big") + b"\x00\x01\x00\x88\x00\x06" + \
        len(d).to_bytes(4, "big") + d


# 需要把 CMS 的 econtent / signedAttrs 构造出来
def build_cms(econtent: bytes, leaf_pem: bytes, chain_pems: list, key, sign_time: str):
    sign_attrs = cms.CMSAttributes([
        cms.CMSAttribute({"type": "signing_time", "values": [core.UTCTime(cms_time(sign_time))]}),
        cms.CMSAttribute({"type": "content_type", "values": ["data"]}),
        cms.CMSAttribute({"type": "message_digest",
                          "values": [core.OctetString(hashlib.sha256(econtent).digest())]}),
    ])
    to_sign = sign_attrs.dump()   # CMSAttributes 是 SET OF，正常 DER 为 0x31，这就是标准签名输入的 DER
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    sig = key.sign(to_sign, ec.ECDSA(hashes.SHA256()))

    leaf = asn1x509.Certificate.load(der_of(leaf_pem))
    certs = [leaf] + [asn1x509.Certificate.load(der_of(p)) for p in chain_pems]

    si = cms.SignerInfo({
        "version": "v1",
        "sid": cms.SignerIdentifier({"issuer_and_serial_number": cms.IssuerAndSerialNumber({
            "issuer": leaf.issuer, "serial_number": leaf.serial_number})}),
        "digest_algorithm": {"algorithm": "sha256"},
        "signed_attrs": sign_attrs,
        "signature_algorithm": {"algorithm": "sha256_ecdsa"},
        "signature": sig,
    })
    sd = cms.SignedData({
        "version": "v1",
        "digest_algorithms": [{"algorithm": "sha256"}],
        "encap_content_info": {"content_type": "data", "content": econtent},
        "certificates": [cms.CertificateChoices({"certificate": c}) for c in certs],
        "signer_infos": [si],
    })
    ci = cms.ContentInfo({"content_type": "signed_data", "content": sd})
    return ci.dump()


def cms_time(s):
    # 输入 "YYYYMMDDHHMMSSZ" -> UTCTime "YYMMDDHHMMSSZ"
    return s[2:] if len(s) == 15 else s


def der_of(pem):
    from cryptography import x509 as cx
    from cryptography.hazmat.primitives import serialization
    if b"BEGIN CERTIFICATE" in pem:
        return cx.load_pem_x509_certificate(pem).public_bytes(serialization.Encoding.DER)
    return pem


def pem_certs(pem_bytes):
    return re.findall(rb"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", pem_bytes, re.S)


def order_chain(pems):
    from cryptography import x509 as cx
    infos = []
    for p in pems:
        c = cx.load_pem_x509_certificate(p)
        infos.append((p, c.subject.rfc4514_string(), c.issuer.rfc4514_string()))
    leaf = next((i for i in infos if not any(j[2] == i[1] for j in infos)), None)
    out, cur = [], leaf
    while cur:
        out.append(cur[0])
        if cur[1] == cur[2]:
            break
        cur = next((i for i in infos if i[1] == cur[2]), None)
    return out


def get_time_str(ts):
    import datetime
    d = datetime.datetime.utcfromtimestamp(ts) + datetime.timedelta(hours=8)
    return d.strftime("%Y%m%d%H%M%SZ")


def main():
    src, dst, p7b_path = sys.argv[1], sys.argv[2], sys.argv[3]
    new_bundle = None
    if "--bundle" in sys.argv:
        new_bundle = sys.argv[sys.argv.index("--bundle") + 1]

    p7b = open(p7b_path, "rb").read()
    prof = json.loads(cms.ContentInfo.load(p7b)["content"]["encap_content_info"]["content"].native)
    # ★★ 硬校验：手表只认调试 profile
    _pt = (prof.get("type") or "").strip()
    if _pt.lower() != "debug":
        raise SystemExit("!! 只接受调试 Profile（type=debug），当前 type=%r" % _pt)
    if not ((prof.get("debug-info") or {}).get("device-ids") or []):
        raise SystemExit("!! 该调试 Profile 未绑定任何设备（debug-info.device-ids 为空）")
    bundle = new_bundle or prof["bundle-info"]["bundle-name"]
    leaf_pem = prof["bundle-info"]["development-certificate"].encode()
    if b"BEGIN" not in leaf_pem:
        leaf_pem = prof["bundle-info"]["development-certificate"].encode()
    sign_time = get_time_str(prof["validity"]["not-before"])
    print("profile type:", prof.get("type"), "bundle:", bundle, "signTime:", sign_time)

    # 1. 拆原包
    zf = zipfile.ZipFile(src)
    bin_data = find_bin(zf)
    if bin_data is None:
        raise SystemExit("源包里没找到 signed.bin")
    _, entries, _ = parse_bin(bin_data)
    files = {}
    for path, name, content in entries:
        full = (path.lstrip("/") + "/" + name) if path else name
        files[full] = content
    print("解出文件数:", len(files))

    # 2. 改 bundleName / versionCode
    cfg = json.loads(files["config.json"].decode())
    cfg["app"]["bundleName"] = bundle
    if "version" in cfg.get("app", {}):
        cfg["app"]["version"]["code"] = 0
    files["config.json"] = json.dumps(cfg, separators=(",", ":")).encode()
    if "pack.info" in files:
        try:
            pi = json.loads(files["pack.info"].decode())
            if "summary" in pi and "app" in pi["summary"]:
                pi["summary"]["app"]["bundleName"] = bundle
            files["pack.info"] = json.dumps(pi, separators=(",", ":")).encode()
        except Exception as e:
            print("pack.info 跳过:", e)

    # 3. 重建 hap（不压缩）—— 仅为拿回 zip 结构做校验；真正签名用 files 直接转 bin
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zo:
        for k, v in files.items():
            zo.writestr(k, v)
    hap_unsigned = buf.getvalue()
    print("重建 hap:", len(hap_unsigned), "字节")

    # 4. bin + 签名块
    ub = hap_to_bin(files, bundle)
    off_pro = len(ub) + 8 + 8
    off_sign = off_pro + len(p7b)
    blob = bytearray()
    blob += ub
    blob += block_head(0x02, 0x00, len(p7b), off_pro)
    blob += block_head(0x00, 0x00, 0, off_sign)
    blob += p7b
    assert len(blob) == off_sign, (len(blob), off_sign)

    digest = sliced_sha256(bytes(blob))
    econtent = byte_content(digest)

    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(open(KEY_PEM, "rb").read(), None)
    chain = order_chain(pem_certs(open(CA_PEM, "rb").read()) + pem_certs(leaf_pem))
    print("证书链:", len(chain), "张")
    cms_der = build_cms(econtent, chain[0], chain[1:], key, sign_time)

    total = off_sign + len(cms_der)
    sub = total - len(ub) + 32
    signed = bytes(blob) + cms_der + sign_head(sub)
    print("signed.bin:", len(signed), "bytes  proBlock@%d p7b=%d cms=%d" % (len(ub), len(p7b), len(cms_der)))

    with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as zo:
        zo.writestr("signed.bin", signed)
    print("输出:", dst, os.path.getsize(dst))

    # 5. 自检：回读解析
    back = parse_bin(signed)
    print("自检 bundle:", back[0], "文件数:", len(back[1]), "p7b:", len(back[2] or b""))


if __name__ == "__main__":
    main()