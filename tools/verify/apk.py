#!/usr/bin/env python3
"""APK 静态核验。

用法: apk.py <apk 路径>

验的是「装上去之前能确定的东西」：版本号、签名方案、证书、随包的 linuxfs 脚本、
中文字符串是否真的进了 resources.arsc。验不了渲染和运行，见 README。
"""
import glob
import os
import re
import sys
import zipfile

from loguru import logger

logger.remove()  # androguard 默认刷 DEBUG 日志，几百 KB

from androguard.core.apk import APK  # noqa: E402
from lxml import etree  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ZH_DIR = os.path.join(ROOT, "app/src/main/res/values-zh-rCN")
sys.path.insert(0, ROOT)
from tools.verify.consts import FONT, FONTCONF, SESSION, ZH_LOCALE, ZH_PRESET  # noqa: E402

fails = []
warns = []


def check(ok, label, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        fails.append(label)


def has_cjk(text):
    # 不用正则的 \uXXXX：re 会把未知转义当字面处理，静默漏掉全部样本
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


def zh_samples(limit=8):
    """从中文资源里挑几条适合做字节检索的：短、无占位符。"""
    out = []
    for path in sorted(glob.glob(os.path.join(ZH_DIR, "*.xml"))):
        try:
            tree = etree.parse(path)
        except etree.XMLSyntaxError:
            continue
        for el in tree.iter():
            if not isinstance(el.tag, str) or el.tag != "string":
                continue
            text = "".join(el.itertext()).strip()
            text = text.replace("\\'", "'")
            if len(text) < 6 or "%" in text or "\n" in text or "\\n" in text:
                continue
            if not has_cjk(text):
                continue
            out.append(text)
            if len(out) >= limit:
                return out
    return out


def main():
    if len(sys.argv) < 2:
        print("用法: apk.py <apk 路径>", file=sys.stderr)
        return 2
    path = sys.argv[1]
    if not os.path.isfile(path):
        print(f"找不到文件: {path}", file=sys.stderr)
        return 2

    print(f"{os.path.basename(path)}  {os.path.getsize(path):,} 字节")

    a = APK(path)
    print(f"  package={a.get_package()}  versionCode={a.get_androidversion_code()}  "
          f"versionName={a.get_androidversion_name()}  targetSdk={a.get_target_sdk_version()}")

    check(a.is_signed_v1(), "v1 签名")
    check(a.is_signed_v2(), "v2 签名")
    check(a.is_signed_v3(), "v3 签名")
    certs = []
    for cert in a.get_certificates():
        fp = "".join(ch for ch in cert.sha256_fingerprint.lower() if ch in "0123456789abcdef")
        certs.append(fp)
        print(f"  证书 sha256: {fp[:16]}…")
    check(bool(certs), "证书存在")

    z = zipfile.ZipFile(path)
    names = set(z.namelist())

    # 随包的 linuxfs 脚本：这是中文补丁的落点，丢了等于白改
    session = SESSION
    preset = ZH_PRESET
    check(preset in names, "assets 内有 steam 预设脚本")
    if session in names:
        body = z.read(session)
        check(b"droiddeck-zh-steam-preset" in body, "session 脚本调用了预设脚本")
    else:
        check(False, "assets 内有 session 脚本")

    font = FONT
    conf = FONTCONF
    check(font in names, "assets 内有中文字体")
    check(conf in names, "assets 内有 fontconfig 回退配置")
    check(ZH_LOCALE in names, "assets 内有 zh_CN locale 数据")

    arsc = z.read("resources.arsc")
    samples = zh_samples()
    if len(samples) < 3:
        check(False, "中文样本抽取", f"只抽到 {len(samples)} 条，样本不足不能算通过")
    else:
        hit = [s for s in samples if s.encode("utf-8") in arsc]
        print(f"  中文抽样 {len(hit)}/{len(samples)} 命中 resources.arsc")
        for s in samples:
            if s not in hit:
                print(f"    未命中: {s}")
        check(len(hit) == len(samples), "中文资源已打进包")

    if fails:
        print(f"\nFAIL: {', '.join(fails)}")
        return 1
    print("\n全部通过（静态项）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
