#!/usr/bin/env python3
"""把旧 release 上「数据包去哪下」那段刷新成当前最新正式版。

sync-data-packages 只把数据包留在最新正式版，其余 release 的同名资产会被
逐个删掉。body 里那段如果写死版本号，下一次发版就成了死链（点进去只有
APK）。所以每次同步之后跑一遍这个脚本，把所有旧 release 的这段统一指到
当前的 latest。

只处理带 <!-- data-packages-section --> 标记的 release；预发布和最新版本身
不动。

用法：
  repoint-packages.py [--repo OWNER/NAME] [--latest TAG] [--dry-run]

Token 从 GH_TOKEN 或 GITHUB_TOKEN 读。
"""

import argparse
import json
import os
import sys
import urllib.request

MARKER = "<!-- data-packages-section -->"


def api(path, token, method="GET", payload=None):
    url = "https://api.github.com" + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "token " + token)
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def segment(repo, tag):
    return (
        f"{MARKER}\n"
        "### 离线数据包（Linux 运行时 / 桌面 / Proton）\n"
        "\n"
        "数据包只挂在最新正式版上，这个 release 已经不带了。需要离线安装 Linux\n"
        f"运行时 / 桌面 / Proton 的话，去 [{tag}](https://github.com/{repo}/releases/tag/{tag}) 的 release 页下载。\n"
        "\n"
        "- `droiddeck-bundle.zip`：linuxfs + desktop 合包，导入后依次安装运行时和桌面\n"
        "- `linuxfs.tar.zst`：单独装 Linux 运行时\n"
        "- `desktop.tar.zst`：单独装 Linux 桌面\n"
        "- `proton.tar.zst`：Proton 兼容层，在 Proton 版本页导入\n"
        "- `data-packages.json`：上面几个包的版本和 sha256，给脚本用\n"
    )


def repoint(body, repo, tag):
    """marker 之后的内容整段替换。marker 之前（版本说明、独家功能）原样保留。"""
    head = body.split(MARKER, 1)[0].rstrip()
    return head + "\n\n" + segment(repo, tag)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    ap.add_argument("--latest", help="默认取 API 的 latest release")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("缺 GH_TOKEN / GITHUB_TOKEN")
    if not args.repo or "/" not in args.repo:
        sys.exit("缺 --repo 或 GITHUB_REPOSITORY")

    latest = args.latest
    if not latest:
        latest = api(f"/repos/{args.repo}/releases/latest", token)["tag_name"]
    print(f"最新正式版：{latest}")

    releases = api(f"/repos/{args.repo}/releases?per_page=100", token)
    changed = 0
    for r in releases:
        tag = r["tag_name"]
        body = r.get("body") or ""
        if tag == latest or r.get("prerelease") or r.get("draft"):
            continue
        if MARKER not in body:
            continue
        new = repoint(body, args.repo, latest)
        if new.strip() == body.strip():
            continue
        changed += 1
        if args.dry_run:
            print(f"  会改 {tag}")
        else:
            api(f"/repos/{args.repo}/releases/{r['id']}", token, "PATCH", {"body": new})
            print(f"  已改 {tag}")

    print(f"共处理 {changed} 个")


if __name__ == "__main__":
    main()
