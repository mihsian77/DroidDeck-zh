#!/usr/bin/env python3
"""中文覆盖检查。

三项：
1. 英文 values/*.xml 的每个 key 在 values-zh-rCN/*.xml 里都有
2. 同一 key 的格式化占位符（%1$s / %d / %% 这类）中英一致，占位符对不上会在运行时抛异常
3. 中文侧多出来的 key（上游删了我们还留着）只报数量，不算错

退出码 1 表示有硬伤（缺 key / 占位符不一致 / 重复定义）。
"""
import glob
import os
import re
import sys

from lxml import etree

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EN_DIR = os.path.join(ROOT, "app/src/main/res/values")
ZH_DIR = os.path.join(ROOT, "app/src/main/res/values-zh-rCN")

TAGS = ("string", "plurals", "string-array", "array")
PH = re.compile(r"%\d+\$[a-zA-Z]|%%|%[a-zA-Z]")

# 同一个 key 在多个文件里重复定义会直接编译失败，这里一起查
def collect(directory):
    items = {}
    dups = []
    broken = []
    for path in sorted(glob.glob(os.path.join(directory, "*.xml"))):
        try:
            tree = etree.parse(path)
        except etree.XMLSyntaxError as exc:
            broken.append((os.path.basename(path), str(exc)))
            continue
        for el in tree.iter():
            if not isinstance(el.tag, str) or el.tag not in TAGS:
                continue
            name = el.get("name")
            if not name:
                continue
            if name in items:
                dups.append(name)
                continue
            text = "".join(el.itertext())
            items[name] = (os.path.basename(path), text)
    return items, dups, broken


def main():
    en, en_dups, en_broken = collect(EN_DIR)
    zh, zh_dups, zh_broken = collect(ZH_DIR)

    missing = sorted(set(en) - set(zh))
    orphan = sorted(set(zh) - set(en))
    # 同一个参数在英文里出现两次（"%1$d items in %1$d folders"）而中文只引用一次，
    # 是合法的；真正出错的是两边去重后的参数集合不一致（少一个会抛
    # IllegalFormatException，多一个同样会）。
    bad_ph = sorted(
        name for name in set(en) & set(zh)
        if set(PH.findall(en[name][1])) != set(PH.findall(zh[name][1]))
    )

    print(f"en={len(en)} zh={len(zh)} missing={len(missing)} bad-placeholder={len(bad_ph)} "
          f"orphan={len(orphan)} dup={len(en_dups) + len(zh_dups)}")

    for name in missing[:20]:
        print(f"  缺中文: {name}  ({en[name][0]})")
    if len(missing) > 20:
        print(f"  ...另有 {len(missing) - 20} 条")
    for name in bad_ph[:10]:
        print(f"  占位符不一致: {name}  en={sorted(set(PH.findall(en[name][1])))} "
              f"zh={sorted(set(PH.findall(zh[name][1])))}")
    if orphan:
        print(f"  中文多出（上游已删，可清理）: {', '.join(orphan[:8])}"
              f"{' ...' if len(orphan) > 8 else ''}")
    for name, err in en_broken + zh_broken:
        print(f"  XML 解析失败: {name}: {err[:80]}")

    return 1 if (missing or bad_ph or en_dups or zh_dups or en_broken or zh_broken) else 0


if __name__ == "__main__":
    sys.exit(main())
