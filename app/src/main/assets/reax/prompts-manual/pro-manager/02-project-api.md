## 二、项目管理 API

### 1. 获取项目列表
`GET /api/projects`

### 2. 获取项目详情
`GET /api/projects/:id`

### 3. 创建项目（方式一：脚手架创建）⭐推荐
`POST /api/projects`

**请求体**：
```json
{"name": "my-app", "scaffold": "fullstack-vue3-express"}
```

**⚠️ 关键：scaffold 参数必须是模板目录名，不是显示名！**
可用模板（见 `pro-manager/pro-scaffold/scaffold.json`）：

| 模板目录名 | 显示名 | 内容 |
|---|---|---|
| `fullstack-vue3-express` | Vue3 + Express + SQLite | 全栈（server/ + frontend/） |
| `frontend-only-vue3` | Vue3 纯前端 | 仅 frontend/ |

**前端模板均已修复 Android 兼容性**：
- Vite 端口使用 `VITE_PORT` 环境变量（默认 5173），避免与 AIPM 注入的 PORT 冲突
- 已预置 `@rollup/rollup-linux-x64-musl` 解决 Android x64 平台 Rollup 加载问题（需 `npm install --force` 跳过 EBADPLATFORM 校验）
- 已配置 `/api` 代理到 `http://localhost:3000`
- dev 脚本已内置 `npm install --force`，首次启动自动装依赖

**前置条件**：需已安装 git（`pkg install -y git`），否则报 `spawnSync git ENOENT`。

**返回示例**：
```json
{"success":true,"data":{"id":"mt1zac7dq2adab","projectName":"test-fullstack","rootDir":".../projects/test-fullstack","creationMethod":"scaffold","modules":[{"name":"默认","dir":".","type":"server","port":3000}]}}
```

### 4. 创建项目（方式二：通用空项目）
`POST /api/projects/generic`

**请求体**：
```json
{"name": "my-empty-project"}
```

**规则**：
- `dir` 可省略 → 自动在 `~/projects/` 下创建同名目录
- `dir` 若传，**必须是绝对路径**（相对路径报「目录不存在」错误）
- 创建后默认含一个模块（type=server, dir=`.`），但没有任何源代码，需手动添加模块


### 6. 已有项目导入（未通过 AIPM 新建的目录）
步骤：
1. 先调用 `POST /api/projects/generic` 创建空壳项目（`name` 为项目名）
2. 再调用 `POST /:id/module` 添加模块（详见下方「添加模块」）
3. 或在项目详情中调用 `POST /:id/scan-modules` 自动扫描子目录生成模块

### 7. 删除项目
`DELETE /api/projects/:id` → 先停止所有模块再删除

### 8. 其他
- 重命名：`PUT /api/projects/:id` body: `{"name":"新名字"}`
- 可见性：`PUT /api/projects/:id/visible` body: `{"visible":true|false}`
- Git 配置：`PUT /api/projects/:id/git` body: `{"gitUrl":"..."}`
- Git 拉取：`POST /api/projects/:id/pull`

---
