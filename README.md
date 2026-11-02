# DroidDeck-zh

DroidDeck 的中文优化 fork。把 SteamOS 体验带到安卓掌机/手机上。

## 下载

去 [Releases](https://github.com/mihsian77/DroidDeck-zh/releases) 页面下最新版 APK。

## 这个版本和官方版有什么区别

**中文优化：**
- 全量中文化，界面、Steam 客户端、Linux 桌面都是中文
- 中文字体兜底，Steam 中文不乱码

**国内加速：**
- MirrorHub 智能加速，Linux 运行时、Proton 下载自动选最快节点
- 国内用户下载不用等半天

**额外功能：**
- 退出 Steam 登录按钮（设置页里，只清凭证，保留设置）
- 一键安装：诊断 tab 里可以直接下载+安装 Linux 运行时，或者选本地文件夹导入
- 自动同步上游，永远跟着最新版走

## 注意

- 这个版本用的是自有签名，和官方版签名不一样，**不能覆盖安装官方版**，需要先卸载官方版再装这个
- 卸载会清除所有数据，注意备份
- 支持 Adreno 显卡，Mali 显卡暂时不支持（上游也不支持）

## 数据包

Linux 运行时、桌面环境、Proton 这些大文件，APP 里会自动下载。
也可以手动下载后，在诊断 tab 里用"一键安装"导入。

数据包 release 在这里：[data-packages](https://github.com/mihsian77/DroidDeck-zh/releases/tag/data-packages)

## 反馈

有问题去 [Issues](https://github.com/mihsian77/DroidDeck-zh/issues) 提。
