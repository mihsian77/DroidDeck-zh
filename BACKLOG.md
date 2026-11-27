# BACKLOG

## 进度

- 2026-11-27 zh.12（`0.7.11-zh.12` / vc35）已推送 `1d431628d`，CI run 45940539154 全绿（构建/单测/签名/catalog 都过），测试包 `test-1d43162` 已发布，catalog preview 已指向它。
- 2026-11-27 机制改造落地：写 main 的三个 workflow 统一 `concurrency: main-write`（catalog-zh 原 cancel-in-progress=true 已关）；sync 的 push 改三次重试并在失败后按白名单恢复 fork workflow、必要时重跑编译预检；白名单收敛到 `tools/ci/fork-whitelist.txt` 单一来源；冲突判定与漂移检查都读该文件，二进制文件单独标注；新增 `tools/ci/fork-assertions.txt` 19 条特性断言；新增 merge 后上游 workflow 漂移检查；`publish-packs` 由 `pull --rebase` 改为 `merge`。
- 2026-11-27 定时 sync 自行跑通（run 45955178883 schedule success），上游再进 9 个提交（#3203/#3237/#3176/#3239/#3251/#3285/#3051/#3311 等），远端 main 已到 `dba4dd57a`，本地需 merge。
- 2026-11-27 沙箱验证方式确定：`-Xmx2560m` + `kotlin.compiler.execution.strategy=in-process` + `--max-workers=1`，跑前清 `~/.gradle/caches/journal-1` 与 `*.lock`；收尾报 `Could not stop all services` / 缓存锁释放失败属环境噪音，看 `kotlin-classes/release` 产物与 `^e: ` 计数判定。

## 待决策

- [ ] 发不发 `0.7.11-zh.12` stable（APK 已验证，test 包同签名同包体）
- [ ] 上游新 9 提交：先发 zh.12 再做 zh.13，还是跳过 zh.12 直接 zh.13
- [ ] issue #1 关闭时机（今天 sync 已成功，根因修复有真实运行证据）
- [ ] 6 个孤儿 tag（`0.2.0`/`0.3.0`/`0.3.1`/`0.4.0-zh1`/`0.8.0`/`0.8.1`）清不清
- [ ] cron `0 0 * * *` 是否挪到非整点（今天实际延迟约 7 小时才执行）

## 规则

见本轮 AGENTS.md（2026-11-27 九条）。要点：先盘点→再分析→最后改；禁降 SDK／关测试／注释报错／放宽阈值；推、发、删、合必须用动作词并向用户确认；收尾四段（交付物／证据分实测文档推断／未核实／漏做）。
