#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证 sign_hap.py 产出的 signed.bin：
   1) 按结构拆出 unsigned bin / p7b / cms
   2) 重算 slicedSHA256，比对 cms 内的 messageDigest 与 econtent
   3) 用 p7b 里的叶子证书公钥验证 ECDSA 签名
"""
import hashlib, json, sys, zipfile
from asn1crypto import cms, core
from cryptography import x509 as cx
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

MARKER = b"hw signed app   1000"


def split(bin_data):
    n = int.from_bytes(bin_data[1:5], "big")
    bundle = bin_data[5:5 + n].decode()
    sub = int.from_bytes(bin_data[-12:-8], "big")
    total_len = len(bin_data)
    # signHead 位于末尾 32 字节
    sign_head_start = total_len - 32
    body = bin_data[:sign_head_start]
    # 找 proBlock：subBlockSize 覆盖 [proBlock .. end]
    for pos in (total_len - sub, total_len - sub - 8, total_len - sub - 16):
        if pos < 10:
            continue
        if not (body[pos] == 0x02 or (body[pos] == 0x01 and body[pos + 1] == 0x00)):
            continue
        ln = int.from_bytes(body[pos + 2:pos + 4], "big")
        off = int.from_bytes(body[pos + 4:pos + 8], "big")
        if off != pos + 16:
            continue
        sign_off = int.from_bytes(body[pos + 8 + 4:pos + 8 + 8], "big")
        p7b = body[off:off + ln]
        cms_bytes = body[sign_off:]
        return bundle, body[:pos], p7b, cms_bytes
    raise SystemExit("找不到 proBlock")


def main(path):
    zf = zipfile.ZipFile(path)
    data = zf.read("signed.bin")
    print("signed.bin:", len(data), "tail marker ok:", data[-32:-12] == MARKER)
    bundle, ub, p7b, cms_bytes = split(data)
    print("bundle:", bundle, "unsigned:", len(ub), "p7b:", len(p7b), "cms:", len(cms_bytes))

    # 重算摘要：范围 = unsigned + proBlock(8) + signBlock(8) + p7b
    sign_area = data[:len(ub) + 16 + len(p7b)]
    h = bytearray()
    for off in range(0, len(sign_area), 4096):
        h += hashlib.sha256(sign_area[off:off + 4096]).digest()
    digest = hashlib.sha256(bytes(h)).digest()
    print("slicedSHA256:", digest.hex()[:32])

    ci = cms.ContentInfo.load(cms_bytes)
    sd = ci["content"]
    econtent = sd["encap_content_info"]["content"].native
    print("econtent len:", len(econtent), "head:", econtent[:16])
    inner = econtent[16:48]
    print("inner digest match:", inner == digest, inner.hex()[:32])

    si = sd["signer_infos"][0]
    md = None
    for attr in si["signed_attrs"]:
        if attr["type"].native == "message_digest":
            md = attr["values"][0].native
    print("messageDigest match:", md == hashlib.sha256(econtent).digest())

    prof = json.loads(econtent if False else json.loads(b"{}")) if False else None
    prof = json.loads(cms.ContentInfo.load(p7b)["content"]["encap_content_info"]["content"].native)
    leaf_pem = prof["bundle-info"]["development-certificate"].encode()
    cert = cx.load_pem_x509_certificate(leaf_pem)
    pub = cert.public_key()
    to_sign = si["signed_attrs"].dump()
    # SignedAttributes dump 已经是 [0]，验签时需换成 SET OF(0x31)
    to_sign_set = b"\x31" + to_sign[1:]
    sig = si["signature"].native
    try:
        pub.verify(sig, to_sign_set, ec.ECDSA(hashes.SHA256()))
        print("ECDSA verify: OK")
    except Exception as e:
        print("ECDSA verify FAILED:", e)
        try:
            pub.verify(sig, to_sign, ec.ECDSA(hashes.SHA256()))
            print("(以 [0] 原文验签 OK)")
        except Exception as e2:
            print("both failed:", e2)

    print("certs in cms:", len(sd["certificates"]))
    for c in sd["certificates"]:
        cc = c.chosen
        print("   -", str(cc.subject.native)[:80])
    print("profile type:", prof.get("type"), "bundle:", prof["bundle-info"]["bundle-name"])


if __name__ == "__main__":
    main(sys.argv[1])