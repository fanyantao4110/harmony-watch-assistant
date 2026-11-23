#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键把任意 HarmonyOS HAP 重签成「本账号调试 Profile」可安装包
用法:
  python3 sign_any.py <输入.hap|目录> [输出目录]
特性:
  * 支持已签名 hap（signed.bin 结构）与未签名 hap（含 config.json/pack.info）
  * 自动改 bundleName = 调试 Profile 里的 bundle-name
  * 输出 <原名>_signed.hap，并自动做一次结构+签名自检
依赖: agc_api.py / sign_hap.py / verify_hap.py
"""
import json, os, subprocess, sys, zipfile
from asn1crypto import cms

W = os.path.dirname(os.path.abspath(__file__))
PROFILE = "/root/wl_debug.p7b"      # AGC 调试 Profile（绑定 gt6）
KEY = "/root/wl_key.pem"
CA = os.path.join(W, "hsu_ca.pem")


def profile_info(p7b_path=PROFILE):
    b = open(p7b_path, "rb").read()
    prof = json.loads(cms.ContentInfo.load(b)["content"]["encap_content_info"]["content"].native)
    return {
        "bundle": prof["bundle-info"]["bundle-name"],
        "type": prof.get("type"),
        "validity": prof.get("validity"),
        "devices": [d.get("device-name") or d.get("udid", "")[:12]
                    for d in (prof.get("debug-info", {}).get("device-ids") and [] or [])],
    }


def sign_one(src, out_dir):
    name = os.path.basename(src)
    base = name.rsplit(".", 1)[0]
    dst = os.path.join(out_dir, base + "_signed.hap")
    print("── 签名:", name)
    r = subprocess.run([sys.executable, os.path.join(W, "sign_hap.py"), src, dst, PROFILE],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("   失败:", (r.stderr or r.stdout).strip().splitlines()[-3:])
        return None
    for line in r.stdout.strip().splitlines():
        if any(k in line for k in ("profile type", "解出文件数", "signed.bin", "自检")):
            print("   ", line.strip())
    v = subprocess.run([sys.executable, os.path.join(W, "verify_hap.py"), dst],
                       capture_output=True, text=True)
    ok = "ECDSA verify: OK" in v.stdout and "messageDigest match: True" in v.stdout \
        and "inner digest match: True" in v.stdout
    print("    自检:", "通过 ✅" if ok else "失败 ❌")
    return dst if ok else None


def main():
    target = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "/storage/emulated/0/hap/out"
    os.makedirs(out_dir, exist_ok=True)

    info = profile_info()
    print("调试 Profile:", PROFILE)
    print("  bundle:", info["bundle"], "type:", info["type"])
    print("  有效期:", info["validity"])
    print()

    if os.path.isdir(target):
        haps = [os.path.join(target, f) for f in sorted(os.listdir(target))
                if f.lower().endswith(".hap")]
    else:
        haps = [target]

    done = []
    for h in haps:
        try:
            d = sign_one(h, out_dir)
            if d:
                done.append(d)
        except Exception as e:
            print("   异常:", e)

    print("\n完成 %d/%d，输出目录: %s" % (len(done), len(haps), out_dir))
    for d in done:
        print("  ", d, "%.1fMB" % (os.path.getsize(d) / 1048576))


if __name__ == "__main__":
    main()