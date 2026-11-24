#!/usr/bin/env python3
"""虚拟机数据包内容校验（linuxfs.tar.zst）。

用法:
    pack.py <linuxfs.tar.zst> [--strict-locale]

流式解包，不整体落盘。验的是「基础 rootfs 里该有的东西在不在」——这一层
APK 静态检查覆盖不到：APK 里的 assets/linuxfs 只是 overlay，运行时再盖到
这个 rootfs 上。

注意：这个包是上游 Bannerlator 的基础 rootfs，不含我们 overlay 里的
droiddeck-session / 中文字体 / preset 脚本（那些在 APK assets 里，由 apk.py 验）。
"""
import os
import sys
import tarfile
from collections import Counter

import zstandard as zstd

# 必须存在：缺了对应功能直接不可用
REQUIRED = {
    "usr/lib/ld-linux-aarch64.so.1": "aarch64 动态链接器（native 模式依赖）",
    "opt/android-host/proot": "proot（非 native 模式的运行底座）",
    "usr/lib/gconv": "iconv 模块目录（非 UTF-8 编码转换）",
    "usr/lib/locale/C.utf8": "基础 locale",
}

fails = []
warns = []


def check(ok, label, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        fails.append(label)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    strict_locale = "--strict-locale" in sys.argv
    if not args:
        print(__doc__, file=sys.stderr)
        return 2
    path = args[0]
    if not os.path.isfile(path):
        print(f"找不到文件: {path}", file=sys.stderr)
        return 2

    print(f"{os.path.basename(path)}  {os.path.getsize(path):,} 字节")

    present = {}
    vulkan_icd = []
    gamescope_layer = []
    zh_locale = []
    i18n_src = []
    members = 0
    top2 = Counter()

    dctx = zstd.ZstdDecompressor()
    with open(path, "rb") as fh, dctx.stream_reader(fh) as reader:
        with tarfile.open(fileobj=reader, mode="r|") as tf:
            for m in tf:
                members += 1
                name = m.name[2:] if m.name.startswith("./") else m.name
                parts = name.split("/")
                if len(parts) > 1 and parts[0] in ("usr", "etc", "opt"):
                    top2["/".join(parts[:2])] += 1
                for want in list(REQUIRED):
                    if name == want:
                        present[want] = m.isdir() or m.size > 0
                if "vulkan/icd.d/" in name and name.endswith(".json") and len(vulkan_icd) < 6:
                    vulkan_icd.append(name)
                if "gamescope" in name.lower() and name.endswith(".so") and len(gamescope_layer) < 4:
                    gamescope_layer.append(name)
                if name.startswith("usr/lib/locale/") and ("zh" in name.lower()) and len(zh_locale) < 4:
                    zh_locale.append(name)
                if name.startswith("usr/share/i18n/locales/") and len(i18n_src) < 3:
                    i18n_src.append(name)

    print(f"  成员 {members} 个；主要目录: "
          + ", ".join(f"{d}({c})" for d, c in top2.most_common(4)))
    check(members > 50000, "包规模正常", f"{members} 个成员")

    for want, label in REQUIRED.items():
        check(present.get(want, False), label, "" if present.get(want) else f"缺 {want}")

    check(bool(vulkan_icd), "Vulkan ICD 在", ", ".join(os.path.basename(v) for v in vulkan_icd))
    check(bool(gamescope_layer), "gamescope 的 Vulkan layer 在",
          ", ".join(os.path.basename(g) for g in gamescope_layer))

    if zh_locale:
        check(True, "已编译的 zh locale 在", ", ".join(zh_locale[:2]))
    else:
        # 现在的包只有 C.utf8。native 模式下 glibc 找不到 zh_CN.UTF-8 会退回 C/POSIX，
        # 影响程序的日期格式、消息目录、排序，字体不走这里所以不会出方块。
        msg = "包内没有已编译的 zh_CN.UTF-8，只有 C.utf8；native 模式下中文 locale 会回落"
        if i18n_src:
            msg += "（usr/share/i18n/locales 在，能 localedef 生成）"
        if strict_locale:
            check(False, "zh locale", msg)
        else:
            print(f"  WARN  zh locale: {msg}")
            warns.append("zh locale")

    if fails:
        print(f"\nFAIL: {', '.join(fails)}")
        return 1
    if warns:
        print("\n通过（有 1 项 WARN：zh locale）")
    else:
        print("\n全部通过（包内结构项）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
