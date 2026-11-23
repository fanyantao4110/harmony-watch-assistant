#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按网页签名工具（hap-sign-utils）的逻辑签名 .app，并做完整 CMS 验签。
等价于 Java 侧 HapSigner.sign(collectFromApp -> binBuild -> CMS -> signHead)。

用法:
  python3 sign_web.py <src.app> <out.hap> <profile.p7b> [--bundleName com.xxx]
"""
import sys, os, io, json, struct, hashlib, zipfile, base64, re
from datetime import datetime, timezone

W = os.path.dirname(os.path.abspath(__file__))
KEY_PEM = "/root/wl_key.pem"
CA_PEM = os.path.join(W, "hsu_ca.pem")

import asn1crypto.cms as cms
import asn1crypto.x509 as asn1x509
from asn1crypto import core, algos
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from cryptography import x509 as cx


# ---------- bin 结构读写 ----------
def be32(n):
    return struct.pack(">I", n)


def be64(n):
    return struct.pack(">Q", n)


def collect_from_app(path):
    """从 .app 里取出内层 hap 的全部 zip 条目"""
    zf = zipfile.ZipFile(path)
    inner = None
    top = {}
    for n in zf.namelist():
        data = zf.read(n)
        if n.endswith(".hap"):
            inner = data
        else:
            top[n] = data
    zf.close()
    if inner:
        z2 = zipfile.ZipFile(io.BytesIO(inner))
        files = {}
        for n in z2.namelist():
            if n.endswith("/"):
                continue
            if n == "signed.bin" or n.startswith("META-INF/"):
                continue
            files[n] = z2.read(n)
        z2.close()
        if "config.json" in files:
            return files
    if "config.json" in top:
        return top
    return {}


def replace_json_string(text, key, value):
    """只替换 "key": "旧值" 的值，保留原有格式（网页工具也是正则替换）"""
    pat = re.compile(r'("%s"\s*:\s*)"[^"]*"' % re.escape(key))
    if pat.search(text):
        return pat.sub(lambda m: m.group(1) + '"' + value + '"', text, count=1)
    return text


def zero_version_code(text):
    pat = re.compile(r'("versionCode"\s*:\s*)-?\d+')
    return pat.sub(lambda m: m.group(1) + "0", text, count=1)


def bin_build(files, bundle):
    """拼 unsigned bin：0xBE + bundleLen + bundle + 文件表"""
    # 按层级数 -> 字典序 排序（与 Java binBuild 一致）
    names = sorted(files.keys(), key=lambda n: (n.count("/"), n))
    out = bytearray()
    out.append(0xBE)
    bb = bundle.encode()
    out += be32(len(bb))
    out += bb
    for n in names:
        content = files[n]
        nb = n.encode()
        # relPath：取最后一段的父目录，网页工具用完整路径名作 name、相对路径作 relPath
        rel = ""
        if "/" in n:
            rel = n.rsplit("/", 1)[0] + "/"
        rb = rel.encode()
        out += be32(len(nb))
        out += nb
        out += be32(len(rb))
        out += rb
        out += be64(len(content))
        out += content
    return bytes(out)


def block_head(kind, tag, length, offset):
    """8 字节：1B 类型 + 1B 标记 + 2B 长度 + 4B 偏移（与 Java blockHead 一致）"""
    return struct.pack(">BBHI", kind & 0xFF, tag & 0xFF, length & 0xFFFF, offset & 0xFFFFFFFF)


def sliced_sha256(data, chunk):
    """每 4MB 切片各算一次 SHA-256，输出 = 切片数 * 32 字节"""
    n = (len(data) + chunk - 1) // chunk
    if n == 0:
        n = 1
    out = bytearray()
    for i in range(n):
        seg = data[i * chunk:(i + 1) * chunk]
        out += hashlib.sha256(seg).digest()
    return bytes(out)


# ---------- 证书 ----------
def pem_certs(text):
    if isinstance(text, bytes):
        text = text.decode()
    return re.findall(r'-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----', text)


def der_of(pem):
    return cx.load_pem_x509_certificate(pem.encode()).public_bytes(serialization.Encoding.DER)


def order_chain(pems):
    """叶子在前，CA 在后（按 issuer/subject 链接排序）"""
    certs = [cx.load_pem_x509_certificate(p.encode()) for p in pems]
    leaf = None
    by_subject = {c.subject.rfc4514_string(): c for c in certs}
    for c in certs:
        if c.issuer.rfc4514_string() == c.subject.rfc4514_string():
            continue  # 自签名根
        # 找叶子：issuer 能在链里找到，且没有别的证书以它为 issuer
        used = [x for x in certs if x.issuer.rfc4514_string() == c.subject.rfc4514_string()
                and x.subject.rfc4514_string() != x.issuer.rfc4514_string()]
        if not used:
            leaf = c
            break
    ordered = []
    cur = leaf
    seen = set()
    while cur is not None:
        s = cur.subject.rfc4514_string()
        if s in seen:
            break
        seen.add(s)
        ordered.append(cur)
        nxt = by_subject.get(cur.issuer.rfc4514_string())
        if nxt is cur or nxt is None:
            break
        cur = nxt
    for c in certs:
        if c not in ordered:
            ordered.append(c)
    return ordered


def build_cms(econtent, chain, key, sign_time):
    leaf = chain[0]
    sd = cms.SignedData({
        "version": "v1",
        "digest_algorithms": [{"algorithm": "sha256"}],
        "encap_content_info": {"content_type": "data", "content": econtent},
        "certificates": [cms.CertificateChoices({"certificate": asn1x509.Certificate.load(
            c.public_bytes(serialization.Encoding.DER))}) for c in chain],
        "signer_infos": [{
            "version": "v1",
            "sid": cms.SignerIdentifier({"issuer_and_serial_number": cms.IssuerAndSerialNumber({
                "issuer": asn1x509.Name.load(leaf.issuer.public_bytes()),
                "serial_number": leaf.serial_number,
            })}),
            "digest_algorithm": {"algorithm": "sha256"},
            "signed_attrs": [],
            "signature_algorithm": {"algorithm": "sha256_ecdsa"},
            "signature": b"",
        }],
    })
    # signedAttrs
    signed_attrs = cms.CMSAttributes([
        cms.CMSAttribute({"type": "content_type", "values": ["data"]}),
        cms.CMSAttribute({"type": "message_digest", "values": [hashlib.sha256(econtent).digest()]}),
        cms.CMSAttribute({"type": "signing_time", "values": [cms.Time({"utc_time": sign_time})]}),
    ])
    si = sd["signer_infos"][0]
    si["signed_attrs"] = signed_attrs
    # 待签数据：SET OF 编码（0x31 替换 0xA0）
    to_sign = signed_attrs.dump()
    to_sign = b"\x31" + to_sign[1:]
    sig = key.sign(to_sign, ec.ECDSA(hashes.SHA256()))
    si["signature"] = sig
    return cms.ContentInfo({"content_type": "signed_data", "content": sd}).dump()


def utc_stamp(ms):
    d = datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)
    return d.strftime("%y%m%d%H%M%SZ")


def replace_version_code_zero(text):
    return zero_version_code(text)


def sign(src, dst, p7b_path, bundle_override=None):
    p7b = open(p7b_path, "rb").read()
    prof = json.loads(cms.ContentInfo.load(p7b)["content"]["encap_content_info"]["content"].native)
    # ★★ 硬校验：手表只认调试 profile
    _pt = (prof.get("type") or "").strip()
    if _pt.lower() != "debug":
        raise SystemExit("!! 只接受调试 Profile（type=debug），当前 type=%r" % _pt)
    if not ((prof.get("debug-info") or {}).get("device-ids") or []):
        raise SystemExit("!! 该调试 Profile 未绑定任何设备（debug-info.device-ids 为空）")
    bundle = bundle_override or prof["bundle-info"]["bundle-name"]
    leaf_pem = prof["bundle-info"]["development-certificate"]
    print("profile type:", prof.get("type"), "bundle:", bundle)
    print("device-ids:", prof.get("debug-info", {}).get("device-ids"))

    files = collect_from_app(src)
    if not files:
        raise SystemExit("不是有效的 .app/.hap（没有 config.json）")
    print("解出文件数:", len(files))

    cfg = files["config.json"].decode()
    newcfg = replace_json_string(cfg, "bundleName", bundle)
    newcfg = replace_version_code_zero(newcfg)
    if newcfg != cfg:
        files["config.json"] = newcfg.encode()
    if "pack.info" in files:
        pt = files["pack.info"].decode()
        npt = replace_json_string(pt, "bundleName", bundle)
        if npt != pt:
            files["pack.info"] = npt.encode()

    ub = bin_build(files, bundle)
    off_pro = len(ub) + 8 + 8
    off_sign = off_pro + len(p7b)
    blob = bytearray()
    blob += ub
    blob += block_head(0x02, 0x00, len(p7b), off_pro)
    blob += block_head(0x00, 0x00, 0, off_sign)
    blob += p7b
    head = bytes(blob)
    assert len(head) == off_sign, (len(head), off_sign)

    digest = sliced_sha256(head, 4096 * 1024)
    econtent = b"\x04" + bytes([len(digest)]) if len(digest) < 128 else None
    # econtent 用 DER OCTET STRING 封装
    econtent = core.OctetString(digest).dump()

    key = load_pem_private_key(open(KEY_PEM, "rb").read(), None)
    chain = order_chain(pem_certs(open(CA_PEM, "rb").read()) + pem_certs(leaf_pem))
    print("证书链:", [c.subject.rfc4514_string()[:34] for c in chain])
    leaf_pt = chain[0].public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    key_pt = key.public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    if leaf_pt != key_pt:
        raise SystemExit("!! p7b 的叶子证书与私钥不匹配，签出来必被拒")
    print("叶子证书与私钥匹配 ✓")

    sign_time = utc_stamp(prof.get("validity", {}).get("not-before", 0) * 1000)
    cms_der = build_cms(econtent, chain, key, sign_time)

    # signHead：32 字节
    sub = struct.pack(">II", 2, 0)
    sign_head = b"hw signed app   " + b"1000" + be32(len(sub)) + sub
    assert len(sign_head) == 32, len(sign_head)

    signed = head + cms_der + sign_head

    # 输出：只含 signed.bin 的 STORED zip
    out = io.BytesIO()
    zo = zipfile.ZipFile(out, "w", zipfile.ZIP_STORED)
    zo.writestr("signed.bin", signed)
    zo.close()
    open(dst, "wb").write(out.getvalue())
    print("输出:", dst, os.path.getsize(dst), "bytes  signed.bin:", len(signed))
    return signed, cms_der, chain, econtent


def verify(dst, cms_der, chain, econtent):
    """回读校验：bin 结构 + CMS 签名 + signHead"""
    zf = zipfile.ZipFile(dst)
    data = zf.read("signed.bin")
    assert data[0] == 0xBE
    off = 1
    bl = struct.unpack(">I", data[off:off + 4])[0]
    off += 4
    bundle = data[off:off + bl].decode()
    off += bl
    names = []
    while off + 4 <= len(data):
        nl = struct.unpack(">I", data[off:off + 4])[0]
        if nl <= 0 or nl > 4096 or off + 4 + nl > len(data):
            break
        off += 4
        name = data[off:off + nl].decode("utf-8", "ignore")
        off += nl
        pl = struct.unpack(">I", data[off:off + 4])[0]
        off += 4 + pl
        cl = struct.unpack(">Q", data[off:off + 8])[0]
        off += 8 + cl
        names.append(name)
    print("verify bin bundle =", bundle, "文件数 =", len(names))

    # signHead 在末尾 32 字节
    tail = data[-32:]
    assert tail[:16] == b"hw signed app   ", tail[:16]
    assert tail[16:20] == b"1000", tail[16:20]
    print("signHead OK ✓", tail.hex())

    # CMS 位于 proBlock 之后
    ci = cms.ContentInfo.load(cms_der)
    sd = ci["content"]
    si = sd["signer_infos"][0]
    attrs = si["signed_attrs"].dump()
    attrs = b"\x31" + attrs[1:]
    chain[0].public_key().verify(si["signature"].native, attrs, ec.ECDSA(hashes.SHA256()))
    md = None
    for a in si["signed_attrs"]:
        if a["type"].native == "message_digest":
            md = a["values"][0].native
    assert md == hashlib.sha256(econtent).digest(), "message-digest 不一致"
    print("CMS 验签 SIGNATURE OK ✓  message-digest OK ✓")


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    src, dst, p7b = sys.argv[1], sys.argv[2], sys.argv[3]
    bundle = None
    if "--bundleName" in sys.argv:
        bundle = sys.argv[sys.argv.index("--bundleName") + 1]
    signed, cms_der, chain, econtent = sign(src, dst, p7b, bundle)
    verify(dst, cms_der, chain, econtent)


if __name__ == "__main__":
    main()