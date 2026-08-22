## 三、模块管理 API

### 添加模块
`POST /api/projects/:id/module`

**请求体**（type 为必填）：
```json
{
  "name": "web-server",
  "dir": "web-server",
  "type": "server",
  "startupScript": "npm install && node index.js",
  "port": 8080
}
```

**⚠️ 要点**：
- `type` 只接受 `frontend` 或 `server`，缺失会报「请指定 type」
- `dir` 不存在会自动创建
- `startupScript` 传入后会写入该模块目录下的 `startup.sh`

### 更新模块
`PUT /api/projects/:id/module/:moduleIndex`（index 从 0 开始）
body 可更新 name/dir/type/startupScript/port 等

### 删除模块
`DELETE /api/projects/:id/module/:moduleIndex`

### 自动扫描子目录生成模块
`POST /api/projects/:id/scan-modules`

---
