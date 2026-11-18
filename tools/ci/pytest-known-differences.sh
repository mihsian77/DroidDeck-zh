#!/usr/bin/env bash
# 跑全部 Python 检查，只放过已知的 fork 差异，其余失败一律红。
#
# 下面是四条测 workflow 结构的用例：它们要求 source / components /
# components-runner / build-runner / checks / python-tests 这些 job 存在。
# 这条 fork 砍掉了组件自编整条链，那几个 job 不打算加回来，所以这几条
# 会一直红。除此之外的任何失败都该拦下来。
set -uo pipefail
cd "$(dirname "$0")/../.."

ALLOWED='test_ci_checks.CiChecksTest.test_independent_jobs_wait_only_for_the_pinned_source
test_ci_checks.CiChecksTest.test_required_status_rejects_failed_cancelled_or_skipped_test_jobs
test_esync_packs.BundleTest.test_builds_fetch_the_pinned_bundle
test_xbox_build_source.XboxBuildSourceTest.test_main_advancing_between_jobs_does_not_change_packaged_source'

log=$(mktemp)
trap 'rm -f "$log"' EXIT

set +e
bash tools/ci/run-python-tests.sh 2>&1 | tee "$log"
set -e

actual=$(grep -oE '(FAIL|ERROR): [A-Za-z0-9_]+ \(test_[A-Za-z0-9_.]+\)' "$log" \
  | sed 's/.*(\(.*\))/\1/' | sort -u)
unexpected=$(comm -23 \
  <(printf '%s\n' "$actual" | sed '/^$/d' | sort -u) \
  <(printf '%s\n' "$ALLOWED" | sed '/^$/d' | sort -u))

echo
if [ -n "$unexpected" ]; then
  echo "::error::跑出来的失败不在已知差异清单里："
  printf '%s\n' "$unexpected"
  exit 1
fi

echo "失败项都在已知差异清单内，没有新的。"
