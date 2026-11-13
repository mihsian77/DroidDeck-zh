#!/usr/bin/env python3
"""把上游新增的条目补进 values/strings.xml。

同步时只有 values/strings.xml 走白名单（保留我们自己的版本），上游新增的 key
会被整块挡掉。代码里一旦引用到这些 key，构建就会 Unresolved reference 失败；
aapt 还会因为重复定义报 duplicate。这个脚本从上游那份 strings.xml 里把缺的
条目原样追加进来，string 和 plurals 都算，保证构建不会因为缺 key 挂掉；
中文翻译随后补。

values/ 下的 gestures、input_strings 这些分文件不在白名单，同步时上游改动直接
合进来，不需要这里兜底。反过来，判断某个 key 有没有中文要扫 values-zh-rCN 下
所有分文件，只看 strings.xml 会把已经翻好的 key 误报成待翻译。

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

# string 可能是 <string .../> 自闭合，也可能是成对的；plurals 一定成对且含 <item>。
ENTRY_RE = re.compile(
    r'<(string|plurals)\s+name="([^"]+)"[^>]*?(?:/>|>.*?</\1\s*>)', re.S
)
MARKER = "<!-- 同步时自动补入的上游新 key，未翻译，翻译后请挪进正文 -->"


def git_show(ref, path):
    return subprocess.check_output(["git", "show", f"{ref}:{path}"], text=True)


def local_keys(root):
    keys = set()
    for path in glob.glob(os.path.join(root, "app/src/main/res/values/*.xml")):
        keys |= set(re.findall(r'name="([^"]+)"', open(path, encoding="utf-8").read()))
    return keys


def zh_keys(root):
    keys = set()
    for path in glob.glob(os.path.join(root, "app/src/main/res/values-zh-rCN/*.xml")):
        keys |= set(re.findall(r'name="([^"]+)"', open(path, encoding="utf-8").read()))
    return keys


def render_entry(entry):
    """一条 entry 按 4 空格基准重排；plurals 的多行体保持可读。"""
    lines = [line.strip() for line in entry.strip().splitlines() if line.strip()]
    if len(lines) == 1:
        return "    " + lines[0]
    body = ["    " + lines[0]]
    body += ["        " + line for line in lines[1:-1]]
    body.append("    " + lines[-1])
    return "\n".join(body)


def append_entries(path, entries):
    text = open(path, encoding="utf-8").read()
    body = text.rstrip()
    if not body.endswith("</resources>"):
        raise SystemExit(f"{path} 结尾不是 </resources>，不敢动")
    head = body[: -len("</resources>")]
    block = "\n    " + MARKER + "\n"
    block += "".join(render_entry(e) + "\n" for e in entries)
    open(path, "w", encoding="utf-8").write(head + block + "</resources>\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", default="upstream/main")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()

    root = args.root
    upstream = git_show(args.ref, "app/src/main/res/values/strings.xml")

    entries = {}
    for m in ENTRY_RE.finditer(upstream):
        entries.setdefault(m.group(2), m.group(0))

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
