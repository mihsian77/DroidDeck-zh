# 本地验证套件

没有真机的情况下，把能在沙箱里验的都验一遍，剩下验不了的明确标出来，不猜。

## 用法

```bash
bash tools/verify/check.sh              # 默认快检，几秒
bash tools/verify/check.sh --full       # 加跑 tools/tests 全套 python 单测（分钟级）
bash tools/verify/check.sh --apk 0.7.1-zh.8   # 加跑 APK 静态核验（按 tag 下载，或用本地路径）

# 数据包结构校验单独跑（要下几百 MB，不适合每次）
python3 tools/verify/pack.py linuxfs.tar.zst
```

需要查 GitHub 的部分读 `$GITHUB_TOKEN`，没设就从 `git remote get-url origin` 里的 token 拿。
拿不到就跳过那几段，标 SKIP，不报错。

退出码：有 FAIL 返回 1，只有 WARN 返回 0。

耗时实测：默认快检几秒；`--full` 在沙箱能跑（不缺依赖），但 150 秒跑不完，
CI 上整套约 20 分钟。日常用快检，改动碰了 `tools/` 或 `app/` 逻辑再上 `--full`。

## 覆盖范围

| 段 | 验什么 | 依据 |
|---|---|---|
| A 上游 | 最新 SHA、待合入数、预演冲突、白名单漂移、上游新增 session tool 映射 | git + merge-tree |
| B 版本 | build.gradle 的 vc/versionName 与 catalog stable、最新 release 是否一致 | git show + REST |
| C 中文 | key 对齐、占位符一致、孤儿 key、重复定义 | lxml 解析 values / values-zh-rCN |
| D 脚本 | overlay 下全部脚本语法、Steam 预设调用在不在、SessionFiles 映射齐不齐、中文字体在不在 | bash -n + grep |
| E 单测 | tools/tests 全套 python 单测 | tools/ci/run-python-tests.sh |
| F CI | 最近构建 conclusion、Gate conclusion、sync/release/packs 状态、开着 issue | REST |
| G APK | 版本号、签名 v1/v2/v3、证书、assets 里的 preset 脚本与调用、zh_CN locale 数据、中文字符串抽样命中 | androguard |
| H 数据包 | linuxfs.tar.zst 的规模、动态链接器、proot、iconv、Vulkan ICD、gamescope layer、zh locale（仅报告，locale 由 assets 提供） | zstandard 流式解包 |

数据包是上游 Bannerlator 的基础 rootfs，**不含**我们的 overlay（droiddeck-session、
中文字体、locale 数据、preset 脚本），那些在 APK assets 里由 G 段验。两边分工别搞混。

## 验不了的（别指望这套件）

- **真机启动与运行**：Steam 能否起、会话是否黑屏、手柄映射、悬浮窗。需要设备。
- **界面渲染**：中文字体是否真的不出方块（能验 arsc 里字符串在，不能验渲染结果）。
- **性能与功耗**：帧率、内存增长、温控。需要实机 profile。
- **GPU 驱动实际行为**：Turnip/PanVK 在具体 Adreno/Mali 型号上的表现。
- **数据包运行时行为**：结构能验（H 段），但「装好后 Steam 能不能起」验不了。
- **签名后的安装兼容**：能查证书与 versionCode，不能验「旧版设备升级是否被系统拒」。

这几项只能靠真机或用户反馈，套件里一律按「未验证」处理，不写「通过」。

## 已知的包内 WARN

`pack.py` 会报 zh locale 缺失：linuxfs.tar.zst 里只有 `usr/lib/locale/C.utf8`，
没有编译好的 `zh_CN.UTF-8`。这是基础 rootfs 的现状，不是本 fork 的回归。

现在 `zh_CN.UTF-8` 的数据放在 `tools/linuxfs/overlay/usr/lib/locale/zh_CN.utf8`，
随 APK assets 在会话启动时释放到 `/usr/lib/locale`，跟 linuxfs 数据包无关，
所以这条 WARN 不需要处理。`pack.py --strict-locale` 仍会把包内缺 locale 判失败，
那是拿来对照上游基础包用的。

`apk.py` 会查这件事：assets 里有 `LC_CTYPE` 就直接 PASS。`droiddeck-session`
检测到目录存在才设 `LANG=zh_CN.UTF-8`，缺数据时不设，免得 glibc 每次
`setlocale` 往 stderr 吐警告。
