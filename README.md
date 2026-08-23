# ReaxCode Android (deepseek-harness-android)

基于 [Termux](https://github.com/termux/termux-app) 源码构建的安卓终端应用，是 **ReaxCode** 移动端载体。
应用启动时自动加载内置 assets 前端资源，并连接默认的 `seeker-vue.xiangxiang.net.cn` 提供完整的项目管理体验。

[Release APK Download](https://github.com/lrxzl/reaxcode/releases/tag/first-release)

## 特性

- 基于 Termux 终端环境，支持完整的 Linux 命令行
- 内置 pro-manager 项目管理服务（Node.js + Express），随应用启动
- 内置前端静态资源（assets 加载），无需额外部署即可使用
- 默认连接云端服务与接口 `https://seeker-vue.xiangxiang.net.cn`
- 发布约 110MB 的 universal APK，覆盖 arm64-v8a / armeabi-v7a / x86 / x86_64

## 构建

```bash
# 环境要求: JDK 17+, Android SDK 36, NDK 29.0.14206865
./gradlew assembleDebug     # Debug 构建
./gradlew assembleRelease   # Release 构建（需自行签名）
```

> 首次构建会自动从 termux-packages releases 下载对应架构的 bootstrap 压缩包。
> 正式发布请使用自己的密钥签名（本地放置 `release-key.jks`，已被 .gitignore 忽略，切勿提交）。


## 目录结构

```
├── app/                    # 主应用模块（ReaxCode 入口）
│   └── src/main/assets/
│       ├── index.html      # 启动加载页
│       └── reax/pro-manager/  # 内置项目管理服务源码
├── termux-shared/          # Termux 共享库
├── terminal-emulator/      # 终端模拟器核心库
└── terminal-view/          # 终端视图库
```

## 相关开源项目

- [Termux](https://github.com/termux/termux-app) — 本项目的基础终端框架
- [termux-packages](https://github.com/termux/termux-packages) — bootstrap 构建包来源
- [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator) — terminal-emulator 库部分代码来源（Apache 2.0）
- [libsuperuser](https://github.com/Chainfire/libsuperuser) — StreamGobbler 代码来源（Apache 2.0）

## 相关模块

| 模块 | 说明 |
|------|------|
| [seeker-server](../seeker-server) | 后端服务（Spring Boot） |
| [seeker-front-vue](../seeker-front-vue) | 前端（Vue3） |
| [pro-manager](./app/src/main/assets/reax/pro-manager) | 内置项目管理服务 |

## License

[GPL-3.0](./LICENSE.md)
