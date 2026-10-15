#!/usr/bin/env python3
"""DroidDeck-zh catalog generator (single source of truth).

record  : build.yml         -- write THIS build's known-good values into the preview row.
promote : release-stable.yml -- write the promoted APK's real values into the stable row.

Every value is taken at build/promote time (no guessing, no urlopen of remote assets,
no branch-HEAD commit resolution). Release metadata (title/summary/publishedAt/apk
asset) is read from the Releases API for the exact tag being recorded.
"""
import argparse
import json
import re
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone

CATALOG_BRANCH = "catalog"
OUT = "/tmp/catalog/catalog.json"


def gh(args):
    return subprocess.run(["gh", "api", args], capture_output=True, text=True, check=True).stdout


def to_ms(iso_str):
    try:
        dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
        return int(dt.timestamp() * 1000)
    except Exception:
        return 0


def fetch_catalog(repo):
    url = "https://raw.githubusercontent.com/%s/%s/catalog.json" % (repo, CATALOG_BRANCH)
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return json.loads(r.read())
    except Exception:
        return None


def release_row(repo, tag, version, version_code, signer, commit, sha256):
    """Build one catalog row for the exact release `tag`; APK metadata from the API."""
    rel = json.loads(gh("repos/%s/releases/tags/%s" % (repo, tag)))
    apk = next((a for a in rel.get("assets", []) if a["name"].endswith(".apk")), None)
    apks = {}
    if apk:
        apks["com.droiddeck.launcher"] = {
            "name": apk["name"],
            "url": apk["browser_download_url"],
            "size": apk["size"],
            "sha256": sha256,
            "packageName": "com.droiddeck.launcher",
            "versionCode": version_code,
            "signerSha256": signer,
        }
    return {
        "tag": tag,
        "title": rel.get("name") or tag,
        "summary": (rel.get("body") or "")[:500],
        "commit": commit,
        "pr": 0,
        "version": version,
        "versionCode": version_code,
        "publishedAt": to_ms(rel.get("published_at")),
        "url": "https://github.com/%s/releases/tag/%s" % (repo, tag),
        "apks": apks,
    }


def main():
    p = argparse.ArgumentParser(description="DroidDeck-zh catalog generator")
    p.add_argument("mode", choices=["record", "promote"])
    p.add_argument("--repo", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--signer", required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--sha256", required=True)
    args = p.parse_args()

    old = fetch_catalog(args.repo) or {
        "schema": 1, "sourceRepo": args.repo, "ciRepo": args.repo,
        "stable": None, "preview": None, "tests": [], "recentPreviews": [],
    }
    row = release_row(args.repo, args.tag, args.version, args.version_code,
                      args.signer, args.commit, args.sha256)

    catalog = dict(old)
    catalog["stable"] = row if args.mode == "promote" else old.get("stable")
    catalog["preview"] = row if args.mode == "record" else old.get("preview")

    import os
    os.makedirs("/tmp/catalog", exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
    print("catalog: stable=%s preview=%s" %
          (catalog["stable"]["tag"] if catalog["stable"] else None,
           catalog["preview"]["tag"] if catalog["preview"] else None))


if __name__ == "__main__":
    main()
