#!/usr/bin/env python3
# 从本仓库的 release 生成本 fork 自己用的 update catalog。
# 上游那套 catalog 由 GitHub App token 发到 DroidDeck-CI，我们用不上，这里只读自己的 release。

import base64
import json
import os
import re
import sys
import urllib.request
from datetime import datetime

REPO = os.environ.get("REPO", "mihsian77/DroidDeck-zh")
TOKEN = os.environ.get("GH_TOKEN", "")
PACKAGE = "com.droiddeck.launcher"
OUT = sys.argv[1] if len(sys.argv) > 1 else "catalog.json"
PREVIEW_HISTORY = int(os.environ.get("PREVIEW_HISTORY", "10"))
STABLE_TAG = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+-zh[0-9]+")


def api(path):
    url = f"https://api.github.com/repos/{REPO}/{path}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "droiddeck-zh-catalog",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def text_at(path, ref):
    data = api(f"contents/{path}?ref={ref}")
    return base64.b64decode(data["content"]).decode("utf-8")


def epoch_ms(iso):
    return int(datetime.strptime(iso, "%Y-%m-%dT%H:%M:%SZ").timestamp() * 1000)


def gradle_version(tag, gradle):
    version_name = re.search(r'^\s*versionName "(.*)"$', gradle, re.M)
    version_code = re.search(r'^\s*versionCode (\d+)$', gradle, re.M)
    if not version_name or not version_code:
        raise SystemExit(f"{tag}: app/build.gradle 里找不到 versionName/versionCode")
    return version_name.group(1), int(version_code.group(1))


# 测试包发布时文件名叫 DroidDeck-<版本>-test.<sha7>.apk，变体名也带这个尾巴，
# 所以比较前先把 test 后缀去掉，否则 -antutu-test.<sha>.apk 会被当基础包选中。
TEST_SUFFIX = re.compile(r"-test\.[0-9a-f]{7,40}\.apk$", re.I)


def plain_name(name):
    return TEST_SUFFIX.sub(".apk", name)


def pick_apk(assets, version):
    """取基础变体：文件名形如 DroidDeck-<version>.apk，不带 -ludashi/-pubg/-antutu 后缀。"""
    apks = [a for a in assets if a["name"].lower().endswith(".apk")]
    for a in apks:
        if plain_name(a["name"]) == f"DroidDeck-{version}.apk":
            return a
    for a in apks:
        name = plain_name(a["name"])
        if re.fullmatch(r"DroidDeck-[0-9][^/]*\.apk", name) and not re.search(
                r"-(standard|ludashi|pubg|antutu)\.apk$", name):
            return a
    return None


def asset_sha(assets, apk):
    digest = apk.get("digest") or ""
    if digest.startswith("sha256:"):
        return digest.split(":", 1)[1].lower()
    for a in assets:
        if a["name"].startswith("SHA256SUMS"):
            body = urllib.request.urlopen(
                urllib.request.Request(a["url"], headers={
                    "Authorization": f"Bearer {TOKEN}",
                    "Accept": "application/octet-stream",
                }), timeout=30).read().decode()
            for line in body.splitlines():
                parts = line.split()
                if len(parts) == 2 and parts[1].lstrip("./") == apk["name"]:
                    return parts[0].lower()
    raise SystemExit(f"{apk['name']}: 拿不到 sha256")


def entry(release, signer):
    tag = release["tag_name"]
    commit = api(f"commits/{tag}")["sha"]
    version, version_code = gradle_version(tag, text_at("app/build.gradle", tag))
    assets = release["assets"] if "assets" in release else api(f"releases/{release['id']}/assets")
    apk = pick_apk(assets, version)
    summary = (release.get("body") or "").split("SHA-256")[0].strip()
    if len(summary) > 400:
        summary = summary[:400].rstrip() + "…"
    published = release.get("published_at") or release["created_at"]
    out = {
        "tag": tag,
        "title": release.get("name") or tag,
        "summary": summary,
        "commit": commit,
        "pr": 0,
        "version": version,
        "versionCode": version_code,
        "publishedAt": epoch_ms(published),
        "url": release["html_url"],
    }
    if apk:
        sha = asset_sha(assets, apk)
        out["apks"] = {PACKAGE: {
            "name": apk["name"],
            "url": apk["browser_download_url"],
            "size": apk["size"],
            "sha256": sha,
            "packageName": PACKAGE,
            "versionCode": version_code,
            "signerSha256": signer,
        }}
    return out


def main():
    signer = open("keystore/release-signer.sha256", encoding="utf-8").read().strip()
    # pack-* 是附加包的内容寻址 release，不是 App 版本，别让它们混进 stable。
    releases = [r for r in api("releases?per_page=100")
                if not r["draft"] and not r["tag_name"].startswith("pack-")]
    # 只看 <版本>-zhN 这种正式版 tag，别让 pack-*（内容寻址的附加包）、数据包这类
    # 非预发布 release 顶掉 stable——它们同样不是 prerelease。
    stable = next((r for r in releases if not r["prerelease"] and STABLE_TAG.fullmatch(r["tag_name"])), None)
    previews = [r for r in releases if r["prerelease"] and r["tag_name"].startswith("test-")]
    previews.sort(key=lambda r: r.get("published_at") or r["created_at"], reverse=True)
    preview = previews[0] if previews else None

    # recentPreviews 就是预览构建的历史列表，[0] 是最新。App 靠它在更新页显示
    # “落后几个构建”，以及按 commit 判断当前装的是哪一版。
    history = [entry(r, signer) for r in previews[:PREVIEW_HISTORY]]

    catalog = {
        "schema": 1,
        "sourceRepo": REPO,
        "ciRepo": REPO,
        "stable": entry(stable, signer) if stable else None,
        "preview": entry(preview, signer) if preview else None,
        # 我们不发 PR 构建包，tests 保持空；App 的 TEST 渠道目前用不上。
        "tests": [],
        "recentPreviews": history,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")
    print(f"写出 {OUT}: stable={catalog['stable']['tag'] if catalog['stable'] else '-'} "
          f"preview={catalog['preview']['tag'] if catalog['preview'] else '-'} "
          f"history={len(history)}")


if __name__ == "__main__":
    main()
