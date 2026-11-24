# 本地验证套件

没有真机的情况下，把能在沙箱里验的都验一遍，剩下验不了的明确标出来，不猜。

## 用法

```bash
bash tools/verify/check.sh              # 默认快检，几秒
bash tools/verify/check.sh --full       # 加跑 tools/tests 全套 python 单测（分钟级）
bash tools/verify/check.sh --apk 0.7.1-zh.8   # 加跑 APK 静态核验（按 tag 下载，或用本地路径）
```

需要查 GitHub 的部分读 `$GITHUB_TOKEN`，没设就从 `git remote get-url origin` 里的 token 拿。
拿不到就跳过那几段，标 SKIP，不报错。

退出码：有 FAIL 返回 1，只有 WARN 返回 0。

## 覆盖范围

| 段 | 验什么 | 依据 |
|---|---|---|
| A 上游 | 最新 SHA、待合入数、预演冲突、白名单漂移、上游新增 session tool 映射 | git + merge-tree |
| B 版本 | build.gradle 的 vc/versionName 与 catalog stable、最新 release 是否一致 | git show + REST |
| C 中文 | key 对齐、占位符一致、孤儿 key、重复定义 | lxml 解析 values / values-zh-rCN |
| D 脚本 | overlay 下全部脚本语法、Steam 预设调用在不在、SessionFiles 映射齐不齐、中文字体在不在 | bash -n + grep |
| E 单测 | tools/tests 全套 python 单测 | tools/ci/run-python-tests.sh |
| F CI | 最近构建 conclusion、Gate conclusion、sync/release/packs 状态、开着 issue | REST |
| G APK | 版本号、签名 v1/v2/v3、证书、assets 里的 preset 脚本与调用、中文字符串抽样命中 | androguard |

## 验不了的（别指望这套件）

- **真机启动与运行**：Steam 能否起、会话是否黑屏、手柄映射、悬浮窗。需要设备。
- **界面渲染**：中文字体是否真的不出方块（能验 arsc 里字符串在，不能验渲染结果）。
- **性能与功耗**：帧率、内存增长、温控。需要实机 profile。
- **GPU 驱动实际行为**：Turnip/PanVK 在具体 Adreno/Mali 型号上的表现。
- **数据包内部完整性**：能对比 sha 与上游发布值，但不能证明解包后文件都在；要验得下载 GB 级包并解包，代价高，按需再做。
- **签名后的安装兼容**：能查证书与 versionCode，不能验「旧版设备升级是否被系统拒」。

这几项只能靠真机或用户反馈，套件里一律按「未验证」处理，不写「通过」。
