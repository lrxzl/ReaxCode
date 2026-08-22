## 四、启动 / 停止 / 日志（核心流程）

### 1. 启动项目
`POST /api/projects/:id/start`

**请求体**（target 必填）：
```json
{"target": "all"}
```
或指定模块索引（数字）：
```json
{"target": 1}
```

**执行逻辑**：
- 实际执行的是该模块目录下的 `startup.sh`（或 startup.bat）
- 若模块无 startup.sh，会尝试自动生成（基于语言检测的 startCommand）
- `npm install` 等安装命令包含在脚本中，首次启动可能耗时较长
- 返回的 pid 是 startup.sh 的进程 pid（即主进程），子进程由它派生

**返回示例**：
```json
{"success":true,"data":{"0":{"pid":5159,"port":3000,"ports":[],"output":"...npm install 输出..."}}}
```

### 2. 查看日志
`GET /api/projects/:id/log/:moduleIndex` → 返回完整日志文本
`GET /api/projects/:id/log/:moduleIndex/stream` → SSE 流式日志

日志文件位置：`<项目根>/startup.sh.log`（首次启动时生成）

### 3. 停止项目
`POST /api/projects/:id/stop`

**请求体**：
```json
{"target": "all"}
```
或 `{"target": 模块索引}`

底层使用 tree-kill 终止整个进程树，SIGTERM → 超时强杀降级。

### 4. 同步状态
`GET /api/projects/:id/sync` → 重新检测进程存活/端口占用，刷新 pro-info.json

### 5. 状态判断要点
- 启动成功后查看 `pro-info.json`：`status` 为 running，`pid` 非 -1，`ports`/`links` 延迟填充（需等 3~5 秒端口探测）
- 停止后：`pid` 变为 -1，`ports` 清空，`links` 清空
- 若启动失败，查看日志文件定位原因

---
