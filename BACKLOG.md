# BACKLOG

## 进度

- 2026-11-27 zh.12（`0.7.11-zh.12` / vc35）已推送 `1d431628d`，CI run 45940539154 全绿（构建/单测/签名/catalog 都过），测试包 `test-1d43162` 已发布，catalog preview 已指向它。
- 2026-11-27 机制改造落地：写 main 的三个 workflow 统一 `concurrency: main-write`（catalog-zh 原 cancel-in-progress=true 已关）；sync 的 push 改三次重试并在失败后按白名单恢复 fork workflow、必要时重跑编译预检；白名单收敛到 `tools/ci/fork-whitelist.txt` 单一来源；冲突判定与漂移检查都读该文件，二进制文件单独标注；新增 `tools/ci/fork-assertions.txt` 19 条特性断言；新增 merge 后上游 workflow 漂移检查；`publish-packs` 由 `pull --rebase` 改为 `merge`。
- 2026-11-27 定时 sync 自行跑通（run 45955178883 schedule success），上游再进 9 个提交（#3203/#3237/#3176/#3239/#3251/#3285/#3051/#3311 等），远端 main 已到 `dba4dd57a`，本地需 merge。
- 2026-11-28 合上游 13 提交（`ab99c1a3b`，merge `f253f058d`），其中 `#3322` 正是修 on-screen 布局的提交，11-27 那 3 条 `OnScreenLayoutTest` 红应随之中止；另有 `#3290` bottom 研究/指南面板、`#3021` Steam 启动 EA 游戏、`#3340`/`#3341` 性能。上游版本仍是 `0.7.11`/vc20，基线不用动。
- 2026-11-28 merge 后 aapt 报 `Found item String/theme_custom more than one time`：英文 14 条 `theme_custom*` 重复，中文 14 条 `stats_hud_*` 重复且被压成一整行（78 条挤在 L3715）。已修（`d60cd676d`），并补 `#3290` 12 条中文（`1e34442ed`）。沙箱 `compileReleaseKotlin` 24m21s 通过，产出 6098 个 class。
- 2026-11-27 沙箱验证方式确定：`-Xmx2560m` + `kotlin.compiler.execution.strategy=in-process` + `--max-workers=1`，跑前清 `~/.gradle/caches/journal-1` 与 `*.lock`；收尾报 `Could not stop all services` / 缓存锁释放失败属环境噪音，看 `kotlin-classes/release` 产物与 `^e: ` 计数判定。

## 待决策

- [ ] zh.12 是否直接废弃，发 `0.7.11-zh.13`（内含上游 13 提交，一次发完）
- [ ] issue #1 关闭时机（11-27 sync 已成功，根因修复有真实运行证据）
- [ ] 6 个孤儿 tag（`0.2.0`/`0.3.0`/`0.3.1`/`0.4.0-zh1`/`0.8.0`/`0.8.1`）清不清
- [ ] cron `0 0 * * *` 是否挪到非整点（今天实际延迟约 7 小时才执行）
- [ ] `0.7.1-zh.9` / `0.7.1-zh.10` 两个只有单 APK 的 release 清不清

## 已修

- `tools/i18n/check-strings.py`（新增）：合并后自检重复定义、一行多条、en/zh 缺失、占位符不一致、XML 可解析。`build.yml` 与 `sync-upstream.yml` 各接一步，本地也能直接跑。
- [已废]「`pick-up-new-strings.py` 压行、不查重」：核过代码，脚本逐条 `render_entry` 追加，且拿 `local_keys` 做过本地去重，本身没有压行逻辑。这次重复的真实来源是手工 merge 时先跑补词再 merge，两段并存；至于那一行 78 条挤在一起是谁造成的，未定位，记疑点。


## 规则

见本轮 AGENTS.md（2026-11-27 九条）。要点：先盘点→再分析→最后改；禁降 SDK／关测试／注释报错／放宽阈值；推、发、删、合必须用动作词并向用户确认；收尾四段（交付物／证据分实测文档推断／未核实／漏做）。
