# BACKLOG

## 进度

- 2026-11-27 zh.12（`0.7.11-zh.12` / vc35）已推送 `1d431628d`，CI run 45940539154 全绿（构建/单测/签名/catalog 都过），测试包 `test-1d43162` 已发布，catalog preview 已指向它。
- 2026-11-27 机制改造落地：写 main 的三个 workflow 统一 `concurrency: main-write`（catalog-zh 原 cancel-in-progress=true 已关）；sync 的 push 改三次重试并在失败后按白名单恢复 fork workflow、必要时重跑编译预检；白名单收敛到 `tools/ci/fork-whitelist.txt` 单一来源；冲突判定与漂移检查都读该文件，二进制文件单独标注；新增 `tools/ci/fork-assertions.txt` 19 条特性断言；新增 merge 后上游 workflow 漂移检查；`publish-packs` 由 `pull --rebase` 改为 `merge`。
- 2026-11-27 定时 sync 自行跑通（run 45955178883 schedule success），上游再进 9 个提交（#3203/#3237/#3176/#3239/#3251/#3285/#3051/#3311 等），远端 main 已到 `dba4dd57a`，本地需 merge。
- 2026-11-28 合上游 13 提交（`ab99c1a3b`，merge `f253f058d`），其中 `#3322` 正是修 on-screen 布局的提交，11-27 那 3 条 `OnScreenLayoutTest` 红应随之中止；另有 `#3290` bottom 研究/指南面板、`#3021` Steam 启动 EA 游戏、`#3340`/`#3341` 性能。上游版本仍是 `0.7.11`/vc20，基线不用动。
- 2026-11-28 merge 后 aapt 报 `Found item String/theme_custom more than one time`：英文 14 条 `theme_custom*` 重复，中文 14 条 `stats_hud_*` 重复且被压成一整行（78 条挤在 L3715）。已修（`d60cd676d`），并补 `#3290` 12 条中文（`1e34442ed`）。沙箱 `compileReleaseKotlin` 24m21s 通过，产出 6098 个 class。
- 2026-11-28 zh.13（`0.7.11-zh.13` / vc36）已发布，7 个资产（APK、desktop、linuxfs、proton、bundle、两个 json），catalog `stable` 指向它。CI 46104172288 与 46110671248 全绿，`Gate the test results` 转 success，那 3 条 `OnScreenLayoutTest` 确认是上游 `#3322` 修掉的。
- 2026-11-28 Steam 会话改按应用语言设 `LANG`（`0c836e2a4`）：原先只有桌面会话设 locale，Steam/游戏压成 `C.UTF-8`，导致 winecfg、regedit 一直是英文。查 Wine 源码确认界面走 PE 资源 LANGID，只有 `LANG` 能驱动，`LANGUAGE` 无效。游戏会话仍保持 `C.UTF-8`。随 zh.14 发布。
- 2026-11-28 清理：删 `0.7.1-zh.9`、`0.7.1-zh.10` 两个单资产 release 与 8 个孤儿 tag（`0.2.0`/`0.3.0`/`0.3.1`/`0.4.0-zh1`/`0.8.0`/`0.8.1`/`0.7.1-zh.9`/`0.7.1-zh.10`），删前核过 catalog 与代码零引用；issue #1 结案。
- 2026-11-27 沙箱验证方式确定：`-Xmx2560m` + `kotlin.compiler.execution.strategy=in-process` + `--max-workers=1`，跑前清 `~/.gradle/caches/journal-1` 与 `*.lock`；收尾报 `Could not stop all services` / 缓存锁释放失败属环境噪音，看 `kotlin-classes/release` 产物与 `^e: ` 计数判定。

## 待决策

- [ ] cron `0 0 * * *` 是否挪到非整点（实际延迟约 7 小时才执行）
- [ ] GPU 兼容层真机验证（设备枚举/帧率/Ubisoft 启动均未测，无真机，靠社区日志）

## 已完成（2026-11-28）

- [x] zh.12 废弃，直接发 zh.13（含上游 13 提交）
- [x] issue #1 关闭
- [x] 6 个孤儿 tag + 2 个单资产 release 清理
- [x] zh.14 发布：Steam 会话按应用语言设 locale（`0c836e2a4`），CI `46129261811` 全绿，release 7 资产，catalog stable 指向 zh.14
- [x] zh.15 合入上游 20 个 Vulkan/framegen 提交（`db12b8a1a` #3355 为首，Vulkan 设备丢失后恢复合成器），版本 vc38，推 `744fff2cc`
- [x] 删 `0.7.11-zh.13`、`0.7.11-zh.11` 两个残缺 stable 与对应 tag；删后仅剩 zh.14 一个正式版
- [x] proton 中文资源验证（P1）：`proton.tar.zst` 内 `winecfg.exe`/`regedit.exe`/`notepad.exe` 均自带 LANGID 2052（zh-CN）完整 STRINGTABLE，`LANG=zh_CN.UTF-8` 即可驱动中文界面，不需要重编 Wine

## 已修（2026-11-28 审查后）

- 退出登录竞态（`13255f3fb`）：`SteamLogout.apply` 原来排在 `killStragglers()` 前面，旧 Steam 退出时回写 `config.vdf` 会盖掉刚清好的版本。现在先杀残留进程、等它们真的消失（上限 2 秒），再清凭证；补了 `SteamLogoutTest` 四个用例。
- MirrorHub（`889ef0a9c`）：非 2xx 分支补 `disconnect`、`getResponseCode` 只调一次；缓存判定改成「差值 >= 0 才认」，时钟回拨不再永久命中；删掉全仓没调用的 `selectFastestSource`/`ProbeTask`（那条路径内部是串行 `get`，最坏 40 秒）。
- DownloadSource（`e7bcdfa9e`）：去掉 `api.github.com` 已提前返回后恒假的 `api` 判断与镜像过滤；`quickest` 不再在排序比较器里读 SharedPreferences。
- 一键安装（`839d85549`）：目录扫描同时认 `desktop` 包，bundle 解出的两个包都会装。

## 中文输入：现状与路线（2026-11-28 核查）

- 现状（实锤）：字符走 evdev 键码。`KeyboardHost.type()` 用 `CharKeyEvent` 把 codepoint 挂在
  `getUnicodeChar()` 上，交给 `GuestKeys.send()` 去映射 `EvdevKeys.fromKeyCode`；汉字没有 evdev
  键码，`evdev <= 0 && scanCode <= 0` 直接 `return false`，字符被丢掉。键盘里的中文进不去 guest。
- 底座已存在（实锤）：`SessionClipboard.kt` 写 `filesDir/session/android-clipboard`；
  `droiddeck-session:636` 用 `DD_ANDROID_CLIPBOARD` 拉起 `droiddeck-clipboard`，后者（`tools/linuxfs/clipboard/clipboard.c`）
  inotify 监听该文件并把它发布成 X11 clipboard。剪贴板这条链是通的。
- 不走 `winlator-input-kit` 的 keysym 方案：它的前提是有 X 协议注入点（`0x01000000 | codepoint` 当 keysym 发给
  xserver），本仓库输入是写 evdev 环（`heldKeys.set` → fakeinput ring），没有 X 注入通道。接过来要先补一层
  X 协议客户端，比剪贴板路线重得多。
- 路线（待实施）：IME `commitText` 里出现非 ASCII 时，写剪贴板文件并按需发一次粘贴键（wine 下 `Ctrl+V`，
  纯 X 程序 `Shift+Insert`，做成设置项）。风险点：wine 的 X clipboard 是否取 UTF8_STRING 需要实测。
- 未验证：wine/Proton 下 UTF-8 粘贴是否完整、粘贴键在各游戏里是否被拦截——需真机。

## 已修

- `tools/i18n/check-strings.py`（新增）：合并后自检重复定义、一行多条、en/zh 缺失、占位符不一致、XML 可解析。`build.yml` 与 `sync-upstream.yml` 各接一步，本地也能直接跑。
- [已废]「`sync-data-packages` 搬走旧版数据包 = 缺陷」：核过 `sync-data-packages.yml:257-265` 与 `tools/release/repoint-packages.py`，删旧版同名资产是为避免每版堆 2.7 GB 重复文件，且脚本会把旧 release 的数据包说明段重写成「已迁到最新版」并指向最新 tag，不是死链。数据包与 App 版本无关，旧 APK 配最新包可用。之前判为 P1 缺陷是误判，不改。
- [已废]「`pick-up-new-strings.py` 压行、不查重」：核过代码，脚本逐条 `render_entry` 追加，且拿 `local_keys` 做过本地去重，本身没有压行逻辑。这次重复的真实来源是手工 merge 时先跑补词再 merge，两段并存；至于那一行 78 条挤在一起是谁造成的，未定位，记疑点。


## 规则

见本轮 AGENTS.md（2026-11-27 九条）。要点：先盘点→再分析→最后改；禁降 SDK／关测试／注释报错／放宽阈值；推、发、删、合必须用动作词并向用户确认；收尾四段（交付物／证据分实测文档推断／未核实／漏做）。
