<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
    <img alt="DroidDeck 中文优化版" src="artwork/droiddeck-banner-light.svg" width="100%">
  </picture>
</p>

# DroidDeck 中文优化版（DroidDeck-zh）

基于 [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) 源码编译的中文优化发行版，面向国内 Android 掌机 / 手机用户。在保留上游全部功能与签名一致的前提下，提供完整中文界面、Steam 客户端中文化、下载加速与国内网络适配。

> 本仓库为上游 DroidDeck 的独立 fork，与 Valve Corporation 无任何关联。Steam、Proton 均为 Valve 的商标与产品。

## 与原版的区别（独家功能）

| 功能 | 说明 |
|---|---|
| 全量中文精翻 | App 界面 1673 条词条全部中文化，术语按 Linux / Wine / Steam 中文圈习惯翻译 |
| Steam 客户端中文化 | 自动以 `-language schinese` 启动，写入 registry.vdf，Steam 界面显示中文 |
| 中文字体注入 | 内置文泉驿微米黑（WenQuanYi Micro Hei）兜底，解决 Steam 中文"口口口"乱码 |
| 竖屏适配 | 管理界面跟随系统旋转，游戏会话保持横屏，竖屏使用不损失功能 |
| 汉化声明弹窗 | 首次启动显示汉化作者与免责声明，点击任意区域关闭 |
| 退出 Steam 登录 | 主页一键退出登录，彻底清除凭证，无需进客户端操作 |
| MirrorHub 智能加速 | Linux 运行时下载自动测速选最快节点（直连 vs 镜像），失败自动切换 |
| Linux 运行时导入 | 支持本地导入 `linuxfs.tar.zst` 数据包，无需 App 内重复下载 |
| 国内 Steam 下载区域 | 首次启动自动预设下载区域，优化 Steam 客户端下载速度 |

## 版本渠道

| 渠道 | Tag 格式 | 说明 |
|---|---|---|
| **Stable 稳定版** | `0.3.1-zh1` | 确认稳定可用后手动发布，非预发布 |
| **Preview 预览版** | `0.3.1-zh1-<sha7>` | 每次 main 构建自动发布，预发布标签，保留最近 3 个 |

两个渠道均为**同一签名**（AOSP 公开 testkey，与原版一致），可互相覆盖安装。应用内更新读取 [catalog.json](https://raw.githubusercontent.com/mihsian77/DroidDeck-zh/catalog/catalog.json) 自动区分渠道。

## 下载与安装

1. **安装 APK**：从 [Releases](https://github.com/mihsian77/DroidDeck-zh/releases) 下载（默认取最新稳定版；新功能尝鲜用最新预览版）。
2. **安装 Linux 运行时**：首次启动后需安装约 750MB 的 Linux 运行时（`linuxfs.tar.zst`）。两种方式：
   - **App 内下载**：自动启用 MirrorHub 加速，选最快节点下载，支持断点续传；
   - **本地导入**：从 [data-packages Release](https://github.com/mihsian77/DroidDeck-zh/releases/tag/data-packages) 下载数据包，传到手机后在「配置 → Linux 运行时 → 导入」选择文件，自动校验 sha256 并安装。
3. **开发者选项**：关闭「停止设置子进程」（Restrict child processes）。Android 12/13 无法手动关闭时，Steam 首次启动会出现 "Fix it for me" 按钮自动处理。
4. 点击 **Play** 登录 Steam，客户端首次启动自动下载更新。

### 数据包 Release（置顶）

[data-packages](https://github.com/mihsian77/DroidDeck-zh/releases/tag/data-packages) 是一个**只存放 Linux 运行时数据包**的独立 Release，由 `sync-data-packages` workflow 每日自动同步上游 [winlator-contents](https://github.com/The412Banner/winlator-contents) 最新版本，同步更新 sha256 校验值。方便导入或用于其他 Linux 设备。

## 自动同步上游

- **sync-upstream**：每日 UTC 0:00 检测上游新提交，有更新自动创建合并分支并提 PR，CI 通过后**自动合并**；
- **sync-data-packages**：每日 UTC 6:00 检测 Linux 运行时新版本，自动下载并更新数据包 Release；
- **update-catalog**：每次 Release 变动自动重新生成 catalog.json（stable 通道已排除数据包 Release）。

## 本地构建

环境要求：Docker、Java 17、Android SDK/NDK、`zstd`。

```bash
tools/build_local.sh      # 构建 APK（app/build/outputs/apk/release/app-release.apk）
tools/deploy_local.sh     # 安装到已连接设备
```

设置 `DROIDDECK_PA13_SOURCE_DIR` 指向已有的 PulseAudio 13.0 源码目录可跳过其下载。

## 免责声明

- 本项目是开源社区作品，与 Valve Corporation 无关，不提供 Steam 账号、游戏、Proton 等任何官方服务；
- 汉化与优化基于上游 GPL-3.0 源码进行，所有修改均可审计；
- 使用本发行版产生的任何设备问题、账号风险或法律后果由使用者自行承担；
- 上游 DroidDeck 无独立官网，请勿点击任何冒充官方的下载链接。

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。运行时、shim、输入与手柄工作基于 WinNative 与 Bannerlator（maxjivi05）构建。LSFG 帧生成来自 Camille LaVey 与 [Eden](https://eden-emu.dev) 项目（[lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)），由 [@maxjivi05](https://github.com/maxjivi05) 移植到 WinNative 与 DroidDeck；需自行购买 [Lossless Scaling](https://store.steampowered.com/app/993090/)，项目不含其着色器。x86 AppImage 使用 [uruntime](https://github.com/VHSgunzo/uruntime)（MIT）解包，未修改原样分发。
