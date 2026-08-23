# ReaxCode App

ReaxCode 安卓主应用模块，基于 Termux app 模块定制。

## 主要改动（相对 Termux 原版）

- 应用名称改为 **ReaxCode**（`TERMUX_APP_NAME`）
- APK 输出命名为 `reaxcode-app_<variant>_<abi>.apk`
- `assets/index.html`：启动加载页，加载内置前端资源
- `assets/reax/pro-manager/`：内置项目管理服务（Node.js + Express，端口 3456），详见其 [README](./src/main/assets/reax/pro-manager/README.md)
- 集成 nanohttpd / jackson 等依赖用于内置服务

## 构建

```bash
# 在仓库根目录执行
./gradlew :app:assembleDebug
```

> Debug 构建使用仓库内置的 `testkey_untrusted.jks` 测试签名（Termux 官方公开测试密钥）。
> Release 构建产物未签名，请使用 `jarsigner` / `apksigner` 自行签名后发布。

## License

[GPL-3.0](../LICENSE.md)
