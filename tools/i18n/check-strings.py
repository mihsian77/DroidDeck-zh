#!/usr/bin/env python3
"""合并上游之后跑一遍字符串资源自检。

aapt 只会报重复定义，其他几种坏法要等用户看到才发现：

1. 同一个 name 在 values/*.xml 里定义多次 —— aapt 直接报 duplicate，构建红。
   手工解冲突最容易出这个。
2. 一行里挤了多条定义 —— merge 时把整块塞进一行的产物，aapt 未必报，
   但后续 diff 全是噪音，review 看不见改了哪条。
3. 英文有、中文没有 —— 界面上那一句还是英文。
4. 中文有、英文没有 —— key 改名后只改了一边，中文资源变成孤儿。
5. 同一 key 两边占位符集合不一致 —— 运行时 String.format 抛异常。

用法：
    python3 tools/i18n/check-strings.py [--root .]

退出码 0 表示干净，1 表示有问题。CI 里接在 merge 之后跑。
"""

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

DEF_RE = re.compile(r'<(string|plurals)\s+name="([^"]+)"')
ENTRY_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*?(?:/>|>(.*?)</string>)', re.S)
PH_RE = re.compile(r'%(\d+)\$[sd]')

MARK = "::error::"


def fail(msg):
    print(f"{MARK}{msg}")
    return 1


def check_xml_parse(root):
    bad = 0
    for path in glob.glob(os.path.join(root, "app/src/main/res/values*/*.xml")):
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            bad |= fail(f"{os.path.relpath(path, root)} 无法解析: {exc}")
    return bad


def check_duplicates(root):
    bad = 0
    for path in glob.glob(os.path.join(root, "app/src/main/res/values*/*.xml")):
        text = open(path, encoding="utf-8").read()
        counts = {}
        for m in DEF_RE.finditer(text):
            counts[m.group(2)] = counts.get(m.group(2), 0) + 1
        for key, n in sorted(counts.items()):
            if n > 1:
                bad |= fail(f"{os.path.relpath(path, root)}: {key} 定义了 {n} 次")
    return bad


def check_flattened(root):
    bad = 0
    for path in glob.glob(os.path.join(root, "app/src/main/res/values*/*.xml")):
        for lineno, line in enumerate(open(path, encoding="utf-8"), 1):
            if len(DEF_RE.findall(line)) > 1:
                bad |= fail(
                    f"{os.path.relpath(path, root)}:{lineno} 一行里挤了多条定义，拆回逐行"
                )
    return bad


def parse_strings(path):
    text = open(path, encoding="utf-8").read()
    out = {}
    for m in ENTRY_RE.finditer(text):
        out[m.group(1)] = m.group(2) or ""
    return out


def check_i18n(root):
    bad = 0
    en_path = os.path.join(root, "app/src/main/res/values/strings.xml")
    zh_path = os.path.join(root, "app/src/main/res/values-zh-rCN/strings.xml")
    if not (os.path.exists(en_path) and os.path.exists(zh_path)):
        return fail("找不到 values/strings.xml 或 values-zh-rCN/strings.xml")
    en = parse_strings(en_path)
    zh = parse_strings(zh_path)
    for key in sorted(set(en) - set(zh)):
        bad |= fail(f"英文有、中文没有: {key}")
    for key in sorted(set(zh) - set(en)):
        bad |= fail(f"中文有、英文没有: {key}")
    for key in sorted(set(en) & set(zh)):
        if set(PH_RE.findall(en[key])) != set(PH_RE.findall(zh[key])):
            bad |= fail(f"占位符不一致: {key}")
    if not bad:
        print(f"字符串资源干净：en {len(en)} 条，zh {len(zh)} 条")
    return bad


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    args = parser.parse_args()

    total = 0
    total += check_xml_parse(args.root)
    total += check_duplicates(args.root)
    total += check_flattened(args.root)
    total += check_i18n(args.root)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
