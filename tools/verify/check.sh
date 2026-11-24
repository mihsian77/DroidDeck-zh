#!/usr/bin/env bash
# 本地巡检验证（无真机场景）。
#
# 用法:
#   bash tools/verify/check.sh                    # 快检，几秒
#   bash tools/verify/check.sh --full             # 加跑 python 单测
#   bash tools/verify/check.sh --apk 0.7.1-zh.8   # 加跑 APK 静态核验（tag 或本地路径）
#
# 退出码: 有 FAIL 为 1，只有 WARN 为 0。
set -uo pipefail
cd "$(dirname "$0")/../.."

FULL=0
APK_TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --full) FULL=1 ;;
    --apk) shift; APK_TARGET="${1:-}" ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

FAILS=0
WARNS=0
SKIPS=0
pass() { printf '  PASS  %s\n' "$1"; }
warn() { printf '  WARN  %s\n' "$1"; WARNS=$((WARNS + 1)); }
fail() { printf '  FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
skip() { printf '  SKIP  %s\n' "$1"; SKIPS=$((SKIPS + 1)); }
sec()  { printf '\n== %s ==\n' "$1"; }

TOKEN="${GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  # origin 形如 https://x-access-token:<token>@github.com/owner/repo.git
  TOKEN=$(git remote get-url origin 2>/dev/null \
          | sed -n 's#https://[^@]*:\([^@]*\)@.*#\1#p')
fi
if [ -z "$TOKEN" ] && [ -n "${GH_TOKEN:-}" ]; then
  TOKEN="$GH_TOKEN"
fi
API="https://api.github.com/repos/mihsian77/DroidDeck-zh"
gh_get() { curl -s -H "Authorization: token $TOKEN" "$@"; }

# ---------- A 上游 ----------
sec "A 上游"
git fetch origin main --quiet 2>/dev/null || warn "origin fetch 失败，用本地缓存"
if git fetch upstream main --quiet 2>/dev/null; then
  git update-ref refs/remotes/upstream/main "$(git rev-parse FETCH_HEAD)"
else
  warn "上游 fetch 失败，用本地缓存的 upstream/main"
fi

if git rev-parse --verify -q upstream/main >/dev/null; then
  BASE=$(git merge-base origin/main upstream/main)
  BEHIND=$(git rev-list --count upstream/main --not origin/main)
  CONFLICT=$(git merge-tree "$BASE" origin/main upstream/main 2>/dev/null | grep -c '^<<<<<<<')
  echo "  upstream/main=$(git rev-parse --short upstream/main)  origin/main=$(git rev-parse --short origin/main)"
  echo "  待合入=$BEHIND  预演冲突=$CONFLICT"
  [ "$BEHIND" -eq 0 ] && pass "上游已全部合入" || warn "$BEHIND 个上游提交待合入"
  [ "$CONFLICT" -eq 0 ] && pass "预演无冲突" || fail "预演有 $CONFLICT 处冲突"

  # 白名单漂移：上游这批动了名单里的文件，冲突时会整段被 ours 覆盖
  WL=$(sed -n '/WHITELIST_FILES: |/,/^    steps:/p' .github/workflows/sync-upstream.yml \
       | sed -n 's/^        \([^ ][^ ]*\)$/\1/p')
  DRIFT=0
  for f in $WL; do
    st=$(git diff --numstat "$BASE" upstream/main -- "$f" 2>/dev/null)
    if [ -n "$st" ]; then
      echo "    漂移: $f ($(echo "$st" | awk '{printf "+%d -%d", $1, $2}'))"
      DRIFT=$((DRIFT + 1))
    fi
  done
  [ "$DRIFT" -eq 0 ] && pass "白名单无漂移" || warn "白名单漂移 $DRIFT 个，冲突时需人工看要不要移植"

  # SessionFiles 映射：上游每次加内置工具都要挂一条，走 ours 会被整文件盖掉
  SKT=app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt
  MISS=""
  for t in $(git show upstream/main:"$SKT" 2>/dev/null \
             | grep -o '"usr/local/bin/droiddeck-[a-z0-9-]*"' | tr -d '"' | sort -u); do
    git show origin/main:"$SKT" 2>/dev/null | grep -qF "\"$t\"" || MISS="$MISS $t"
  done
  [ -z "$MISS" ] && pass "SessionFiles 映射齐全" || fail "缺上游映射:$MISS"
else
  skip "没有 upstream 引用，跳过上游比对"
fi

# ---------- B 版本 ----------
sec "B 版本一致性"
VC=$(git show origin/main:app/build.gradle | grep -oE 'versionCode [0-9]+' | awk '{print $2}')
VN=$(git show origin/main:app/build.gradle | grep -oE 'versionName "[^"]+"' | head -1 | cut -d'"' -f2)
echo "  build.gradle: $VN / vc$VC"
CAT=$(git show origin/main:catalog.json 2>/dev/null \
      | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['stable'].get('tag'),d['stable'].get('versionCode'))" 2>/dev/null)
if [ -n "$CAT" ]; then
  read -r CTAG CVC <<< "$CAT"
  echo "  catalog stable: $CTAG / vc$CVC"
  if [ "$CTAG" = "$VN" ] && [ "$CVC" = "$VC" ]; then
    pass "catalog 与 build.gradle 一致"
  else
    warn "catalog($CTAG/vc$CVC) 与 build.gradle($VN/vc$VC) 不一致，可能在准备新版本或 catalog 未更新"
  fi
fi

if [ -n "$TOKEN" ]; then
  REL=$(gh_get "$API/releases?per_page=8" | python3 -c "
import sys, json
rs = [r for r in json.load(sys.stdin) if not r['prerelease']]
if rs: print(rs[0]['tag_name'], len(rs[0]['assets']))
" 2>/dev/null)
  if [ -n "$REL" ]; then
    read -r RTAG RASSETS <<< "$REL"
    echo "  最新正式版: $RTAG（$RASSETS 个资产）"
    [ "$RASSETS" -ge 4 ] && pass "正式版资产数正常" || warn "$RTAG 只有 $RASSETS 个资产，可能数据包未同步"
  fi
else
  skip "没有 token，跳过 release 查询"
fi

# ---------- C 中文 ----------
sec "C 中文覆盖"
if python3 tools/verify/i18n.py; then
  pass "i18n 无硬伤"
else
  fail "i18n 有问题（见上）"
fi

# ---------- D 脚本与资源 ----------
sec "D 脚本与资源"
BAD=0
while IFS= read -r s; do
  bash -n "$s" 2>/dev/null || { echo "    语法错: $s"; BAD=$((BAD + 1)); }
done < <(find tools/linuxfs/overlay -type f -size -1M -exec grep -l '^#!' {} + 2>/dev/null)
[ "$BAD" -eq 0 ] && pass "overlay 脚本语法 OK" || fail "$BAD 个脚本语法错误"

if grep -q "droiddeck-zh-steam-preset" tools/linuxfs/overlay/usr/local/bin/droiddeck-session 2>/dev/null; then
  pass "Steam 下载区域预设调用在"
else
  fail "Steam 预设调用丢了（11-18 被 ours 覆盖过一次）"
fi

for f in tools/linuxfs/overlay/usr/share/fonts/truetype/wqy/wqy-microhei.ttc \
         tools/linuxfs/overlay/etc/fonts/conf.d/99-zh-fallback.conf; do
  [ -s "$f" ] && pass "资源在: $(basename "$f")" || fail "资源缺失: $f"
done

if git show origin/main:app/src/main/res/values-zh-rCN/strings.xml >/dev/null 2>&1; then
  pass "中文资源目录在 origin/main"
fi

# ---------- E 单测 ----------
if [ "$FULL" -eq 1 ]; then
  sec "E python 单测"
  if PYTHON_TEST_GROUPS=3 bash tools/ci/run-python-tests.sh > /tmp/verify-tests.log 2>&1; then
    pass "python 单测全过"
  else
    fail "python 单测有失败，日志 /tmp/verify-tests.log"
    grep -E "::error::|FAIL:|ERROR:" /tmp/verify-tests.log | head -5
  fi
else
  skip "python 单测（--full 才跑）"
fi

# ---------- F CI ----------
sec "F CI 状态"
if [ -n "$TOKEN" ]; then
  # 主动取消的 run 不算失败：builder 排队久、重复推送、手动作废都会 cancel
  RUN=$(gh_get "$API/actions/workflows/377208120/runs?per_page=8" | python3 -c "
import sys, json
rs = json.load(sys.stdin)['workflow_runs']
skipped = 0
for r in rs:
    if r['conclusion'] == 'cancelled':
        skipped += 1
        continue
    print(r['id'], r['status'], r['conclusion'], r['head_sha'][:9], skipped)
    break
" 2>/dev/null)
  if [ -n "$RUN" ]; then
    read -r RID RSTAT RCONC RSHA RSKIP <<< "$RUN"
    echo "  最近有效构建: $RID $RSHA $RSTAT/$RCONC"
    [ "${RSKIP:-0}" -gt 0 ] && echo "  （跳过 $RSKIP 个已取消的 run）"
    if [ "$RCONC" = "success" ]; then
      pass "最近构建成功"
    elif [ "$RSTAT" != "completed" ]; then
      warn "构建进行中（$RSTAT）"
    else
      fail "最近构建 $RCONC"
    fi
    GATE=$(gh_get "$API/actions/runs/$RID/jobs" | python3 -c "
import sys, json
for j in json.load(sys.stdin)['jobs']:
    if 'Gate' in j['name']: print(j['conclusion'])
" 2>/dev/null)
    if [ -n "$GATE" ]; then
      [ "$GATE" = "success" ] && pass "Gate=$GATE" || warn "Gate=$GATE（注意：两个测试步骤 continue-on-error，Gate 可能读不到真实结果）"
    fi
  fi

  OPEN=$(gh_get "$API/issues?state=open&per_page=100" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(len([i for i in d if 'pull_request' not in i]))
" 2>/dev/null)
  [ -n "$OPEN" ] && { [ "$OPEN" -eq 0 ] && pass "0 个开着的 issue" || warn "$OPEN 个开着的 issue"; }

  for pair in "377969444:sync-upstream" "378052876:release-stable" "377987754:sync-data-packages"; do
    id=${pair%%:*}; name=${pair##*:}
    st=$(gh_get "$API/actions/workflows/$id/runs?per_page=1" | python3 -c "
import sys, json
r = json.load(sys.stdin)['workflow_runs'][0]
print(r['conclusion'] or r['status'])
" 2>/dev/null)
    [ -n "$st" ] && echo "  $name: $st"
    [ "$st" = "failure" ] && fail "$name 最近一次失败"
  done
else
  skip "没有 token，跳过 CI 查询"
fi

# ---------- G APK ----------
if [ -n "$APK_TARGET" ]; then
  sec "G APK 核验"
  A=""
  case "$APK_TARGET" in
    */*)
      # 含斜杠当本地路径，不拿它去查 release
      if [ -f "$APK_TARGET" ]; then
        A="$APK_TARGET"
      else
        fail "本地文件不存在: $APK_TARGET"
      fi
      ;;
    *)
      if [ -z "$TOKEN" ]; then
        fail "没有 token，无法按 tag 下载"
      else
        AID=$(gh_get "$API/releases/tags/$APK_TARGET" | python3 -c "
import sys, json
for a in json.load(sys.stdin).get('assets', []):
    if a['name'].endswith('.apk'): print(a['id'])
" 2>/dev/null)
        if [ -z "$AID" ]; then
          fail "release $APK_TARGET 里找不到 APK"
        else
          A=/tmp/verify-$APK_TARGET.apk
          gh_get -L -H "Accept: application/octet-stream" "$API/releases/assets/$AID" -o "$A"
        fi
      fi
      ;;
  esac
  if [ -n "$A" ] && [ -f "$A" ]; then
    python3 tools/verify/apk.py "$A" && pass "APK 静态核验通过" || fail "APK 有 FAIL 项"
  fi
else
  skip "APK 核验（--apk <tag|路径> 才跑）"
fi

# ---------- 汇总 ----------
sec "汇总"
echo "  FAIL=$FAILS  WARN=$WARNS  SKIP=$SKIPS"
if [ "$FAILS" -eq 0 ]; then
  echo "  结论: 快检通过"
  exit 0
fi
echo "  结论: $FAILS 项失败，需要处理"
exit 1
