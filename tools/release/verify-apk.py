#!/usr/bin/env python3
"""发版前的静态校验。

这些东西编译阶段发现不了，出问题只有用户装上之后才知道，所以放在发版前跑：
源码层查中文覆盖和占位符，包内层查字体、清单标记，再对签名和 lineage 做确认。

用法：
  verify-apk.py <apk> [--res app/src/main/res] [--release-tag TAG]

退出码非 0 表示有硬性项目没过，发版流程会因此中断。
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import zipfile

FONT = "assets/linuxfs/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
STRING_RE = re.compile(r'(?s)<string\s+name="([^"]+)"(.*?)</string>')
ANY_RE = re.compile(r'<(?:string|plurals|string-array|array)\s+name="([^"]+)"')
PARAM_RE = re.compile(r"%(\d+)\$")

failures = []


def fail(msg):
    failures.append(msg)
    print(f"  FAIL {msg}")


def ok(msg):
    print(f"  ok   {msg}")


def warn(msg):
    print(f"  --   {msg}")


def strings_of(path):
    text = open(path, encoding="utf-8").read()
    return {m.group(1): m.group(2) for m in STRING_RE.finditer(text)}


def all_keys(path):
    return set(ANY_RE.findall(open(path, encoding="utf-8").read()))


def param_indexes(body):
    """格式串里引用到的参数序号集合。%s / %d 这类不带序号的算第 1 个。"""
    out = {int(m.group(1)) for m in PARAM_RE.finditer(body)}
    if re.search(r"%(?!\d+\$)[sdfxX]", body):
        out.add(1)
    return out


def check_strings(res):
    """中文覆盖按文件算，不能只看总数——分文件同步时漏一整个文件很容易看不出来。"""
    values = os.path.join(res, "values")
    zh_dir = os.path.join(res, "values-zh-rCN")
    if not os.path.isdir(values) or not os.path.isdir(zh_dir):
        fail(f"找不到资源目录：{values} / {zh_dir}")
        return

    print("strings:")
    missing_total = 0
    for name in sorted(os.listdir(values)):
        if not name.endswith(".xml"):
            continue
        en = all_keys(os.path.join(values, name))
        zh_path = os.path.join(zh_dir, name)
        zh = all_keys(zh_path) if os.path.exists(zh_path) else set()
        missing = en - zh
        if missing:
            missing_total += len(missing)
            fail(f"{name} 缺 {len(missing)} 条：{', '.join(sorted(missing)[:5])}")
    if missing_total == 0:
        ok("每个资源文件的中文覆盖都是完整的")

    # 占位符：中文引用了英文没有的参数序号，运行时才会炸，编译不报。
    en_strings = strings_of(os.path.join(values, "strings.xml"))
    zh_strings = {}
    for path in glob.glob(os.path.join(zh_dir, "*.xml")):
        zh_strings.update(strings_of(path))
    bad = []
    for key, body in zh_strings.items():
        if key not in en_strings:
            continue
        en_idx = param_indexes(en_strings[key])
        zh_idx = param_indexes(body)
        if zh_idx and (not en_idx or max(zh_idx) > max(en_idx)):
            bad.append((key, sorted(en_idx), sorted(zh_idx)))
    if bad:
        for key, a, b in bad[:10]:
            fail(f"{key} 中文引用了英文没有的参数：英{a} 中{b}")
    else:
        ok("中文没有引用越界的参数序号")


def check_apk(apk):
    print("apk:")
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        if FONT in names:
            ok(f"中文字体在包内（{z.getinfo(FONT).file_size / 1024 / 1024:.1f} MB）")
        else:
            fail(f"包里没有中文字体：{FONT}")

        abis = {n.split("/")[1] for n in names if n.startswith("lib/") and "/" in n[4:]}
        if abis:
            ok(f"ABI: {', '.join(sorted(abis))}")


def aapt_dump(apk):
    build_tools = os.environ.get("BUILD_TOOLS") or os.environ.get("ANDROID_HOME", "")
    if not build_tools:
        return None
    candidates = sorted(glob.glob(os.path.join(build_tools, "build-tools", "*", "aapt")))
    if not candidates:
        candidates = sorted(glob.glob(os.path.join(build_tools, "aapt")))
    if not candidates:
        return None
    return candidates[-1]


def check_manifest(apk):
    aapt = aapt_dump(apk)
    if not aapt:
        warn("没有 aapt，跳过清单检查")
        return
    print("manifest:")
    tree = subprocess.run([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"],
                          capture_output=True, text=True).stdout
    if re.search(r"debuggable.*0x[0-9a-f]+.*=true|android:debuggable.*true", tree):
        fail("manifest 里 debuggable 是 true")
    else:
        ok("debuggable 未开启")
    if re.search(r"allowBackup.*true", tree):
        warn("allowBackup 开着，容器数据可能被云备份搬走")


def check_signature(apk, expected):
    build_tools = os.environ.get("BUILD_TOOLS") or os.environ.get("ANDROID_HOME", "")
    apksigner = None
    for pattern in ("build-tools/*/apksigner", "apksigner"):
        found = sorted(glob.glob(os.path.join(build_tools, pattern)))
        if found:
            apksigner = found[-1]
            break
    if not apksigner:
        warn("没有 apksigner，跳过签名检查")
        return
    print("signature:")
    certs = subprocess.run([apksigner, "verify", "--min-sdk-version", "28", "--print-certs", apk],
                           capture_output=True, text=True)
    if certs.returncode != 0:
        fail("apksigner verify 没过")
        return
    signers = set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs.stdout))
    if expected:
        if signers == {expected}:
            ok("Android 9+ 只看到 DroidDeck 的签名")
        else:
            fail(f"Android 9+ 看到的签名者 {sorted(signers)}，不是只有 {expected}")

    lineage = subprocess.run([apksigner, "lineage", "--in", apk, "--print-certs"],
                             capture_output=True, text=True)
    sign_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sign-apk.sh")
    found = re.search(r"TESTKEY_SHA256=([0-9a-f]{64})", open(sign_script, encoding="utf-8").read())
    testkey = found.group(1) if found else None
    if not testkey:
        warn("sign-apk.sh 里没有 TESTKEY_SHA256，没法确认升级路径")
    elif "Signer #1 in lineage" in lineage.stdout and testkey in lineage.stdout:
        ok("lineage 从上游 testkey 起头，旧安装能覆盖升级")
    else:
        fail("lineage 的起点不是 testkey，从上游版本升上来的装不上")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--res", default="app/src/main/res")
    parser.add_argument("--signer-sha256", default="")
    args = parser.parse_args()

    if not os.path.exists(args.apk):
        print(f"找不到 apk：{args.apk}", file=sys.stderr)
        return 2

    check_strings(args.res)
    check_apk(args.apk)
    check_manifest(args.apk)

    expected = args.signer_sha256
    if not expected:
        key = os.path.join("keystore", "release-signer.sha256")
        if os.path.exists(key):
            expected = open(key, encoding="utf-8").read().strip()
    check_signature(args.apk, expected)

    print()
    if failures:
        print(f"校验没过：{len(failures)} 项")
        return 1
    print("校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
