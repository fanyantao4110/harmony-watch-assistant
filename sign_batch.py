#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量把 hap / app / bin 重签成 App Pack（.app）
用法:
    python3 sign_batch.py <目录或文件...> [--out 输出目录] [--p7b xx.p7b] [--vc 0|keep]
每个产物同时给 -signed.app 与 -signed.hap
"""
import os, subprocess, sys

W = os.path.dirname(os.path.abspath(__file__))
SIGN = os.path.join(W, "sign_app.py")
EXTS = (".hap", ".app", ".bin")


def pick(argv, flag, default=None):
    return argv[argv.index(flag) + 1] if flag in argv else default


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    out_dir = pick(args, "--out", "/sdcard/hap/app")
    p7b = pick(args, "--p7b")
    vc = pick(args, "--vc", "0")

    skip = set()
    for f in ("--out", "--p7b", "--vc"):
        if f in args:
            i = args.index(f)
            skip.update({i, i + 1})
    targets = [a for i, a in enumerate(args) if i not in skip and not a.startswith("--")]

    files = []
    for t in targets:
        if os.path.isdir(t):
            for n in sorted(os.listdir(t)):
                if n.lower().endswith(EXTS):
                    files.append(os.path.join(t, n))
        elif os.path.isfile(t):
            files.append(t)
    if not files:
        raise SystemExit("没有找到 .hap/.app/.bin")

    ok, fail = [], []
    for i, f in enumerate(files, 1):
        print("\n[%d/%d] %s" % (i, len(files), os.path.basename(f)))
        cmd = [sys.executable, SIGN, f, "--out", out_dir, "--vc", vc]
        if p7b:
            cmd += ["--p7b", p7b]
        r = subprocess.run(cmd, capture_output=True, text=True)
        out = r.stdout + r.stderr
        good = "全部通过" in out
        for line in out.splitlines():
            if any(k in line for k in ("输出 .", "=>", "自检", "FAIL", "Error",
                                       "错误", "失败", "无法", "没有")):
                if "Deprecation" in line or "utcfromtimestamp" in line:
                    continue
                print("   ", line.strip())
        (ok if good else fail).append(f)
        if not good:
            for line in out.splitlines()[-6:]:
                print("    !", line.strip())

    print("\n" + "=" * 50)
    print("成功 %d / 共 %d   输出目录: %s" % (len(ok), len(files), out_dir))
    for f in ok:
        b = os.path.basename(f).rsplit(".", 1)[0]
        p = os.path.join(out_dir, b + "-signed.app")
        if os.path.exists(p):
            print("   ✅ %s  (%.1f MB)" % (p, os.path.getsize(p) / 1048576))
    for f in fail:
        print("   ❌", os.path.basename(f))


if __name__ == "__main__":
    main()