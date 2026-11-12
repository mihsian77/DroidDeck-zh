#!/usr/bin/env python3
"""把上游新增的 string key 补进 values/strings.xml。

同步时 values/strings.xml 按白名单保留我们自己的版本，上游新增的 key 会被
整块挡掉。代码里一旦引用到这些 key，构建就会 Unresolved reference 失败。
这个脚本从上游那份 strings.xml 里把缺的条目原样追加进来，保证构建不会因为
缺 key 挂掉；中文翻译随后补。

用法：
    python3 tools/i18n/pick-up-new-strings.py [--ref upstream/main] [--root .]

只读上游文件、只追加不删除，本地已有的 key 一律不动。
"""

import argparse
import glob
import os
import re
import subprocess
import sys

STRING_RE = re.compile(r'<string name="([^"]+)"[^>]*>.*?</string>', re.S)
MARKER = "<!-- 同步时自动补入的上游新 key，未翻译，翻译后请挪进正文 -->"


def git_show(ref, path):
    return subprocess.check_output(["git", "show", f"{ref}:{path}"], text=True)


def local_keys(root):
    keys = set()
    for path in glob.glob(os.path.join(root, "app/src/main/res/values/*.xml")):
        keys |= set(re.findall(r'name="([^"]+)"', open(path, encoding="utf-8").read()))
    return keys


def zh_keys(root):
    path = os.path.join(root, "app/src/main/res/values-zh-rCN/strings.xml")
    if not os.path.exists(path):
        return set()
    return set(re.findall(r'name="([^"]+)"', open(path, encoding="utf-8").read()))


def append_entries(path, entries):
    text = open(path, encoding="utf-8").read()
    body = text.rstrip()
    if not body.endswith("</resources>"):
        raise SystemExit(f"{path} 结尾不是 </resources>，不敢动")
    head = body[: -len("</resources>")]
    block = "\n    " + MARKER + "\n"
    block += "".join("    " + e + "\n" for e in entries)
    open(path, "w", encoding="utf-8").write(head + block + "</resources>\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", default="upstream/main")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()

    root = args.root
    upstream = git_show(args.ref, "app/src/main/res/values/strings.xml")

    entries = {}
    for m in STRING_RE.finditer(upstream):
        entries.setdefault(m.group(1), m.group(0))

    have = local_keys(root)
    missing = sorted(k for k in entries if k not in have)

    if not missing:
        print("上游没有新 key，不用补")
        return 0

    append_entries(os.path.join(root, "app/src/main/res/values/strings.xml"),
                   [entries[k] for k in missing])

    print(f"补入 {len(missing)} 个上游新 key（英文原文，未翻译）")
    untranslated = [k for k in missing if k not in zh_keys(root)]
    if untranslated:
        print(f"::warning::其中 {len(untranslated)} 个还没有中文翻译，需要补 values-zh-rCN")
        for k in untranslated:
            print(f"  待翻译: {k}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
