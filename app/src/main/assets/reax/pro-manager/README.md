# Pro Manager（项目管理器）

DeepSeek Harness 安卓端内置的项目管理服务，运行于 Termux 环境，提供项目创建、文件管理、Git 操作等功能。

## 技术栈

- Node.js + Express + WebSocket (ws)
- multer（文件上传）/ archiver + unzipper（压缩解压）

## 功能模块

| API 路由 | 说明 |
|----------|------|
| `/api/projects` | 项目列表与生命周期管理 |
| `/api/scaffolds` | 项目脚手架模板 |
| `/api/files` | 文件读写与上传 |
| `/api/git` | Git 操作 |
| `/api/events` | SSE 实时事件流 |

## 运行方式

该服务随安卓 APK 内置发布，应用启动时由 `startup.sh` 自动拉起：

- 服务端口：`3456`
- 管理界面：托管内置 `html/` 目录静态文件

手机端访问：`http://localhost:3457` 或 `http://localhost:5173`

## 开发调试

```bash
npm install
npm run dev   # 即 node index.js
```

## 相关开源项目

- [Express](https://github.com/expressjs/express)
- [ws (WebSocket)](https://github.com/websockets/ws)
- [multer](https://github.com/expressjs/multer)
- [archiver](https://github.com/archiverjs/node-archiver)
- [unzipper](https://github.com/EvanOxfeld/node-unzipper)

## 相关模块

- [后端服务](../../../seeker-server/README.md)

## License

[GPL-3.0](LICENSE)
