## 八、AI 操作项目生命周期速查

```bash
# 一键完成：创建 → 添加模块 → 启动 → 看日志 → 停止

# 1. 创建（三选一）
curl -X POST localhost:3456/api/projects -H 'Content-Type: application/json' -d '{"name":"demo","scaffold":"fullstack-vue3-express"}'   # 全栈脚手架
curl -X POST localhost:3456/api/projects -H 'Content-Type: application/json' -d '{"name":"demo","scaffold":"frontend-only-vue3"}'      # 纯前端脚手架
curl -X POST localhost:3456/api/projects/generic -H 'Content-Type: application/json' -d '{"name":"demo"}'   # 空项目

# 2. 添加模块（空项目需要）
curl -X POST localhost:3456/api/projects/<ID>/module -H 'Content-Type: application/json' -d '{"name":"server","dir":"server","type":"server","startupScript":"npm install && node index.js","port":3000}'

# 3. 启动
curl -X POST localhost:3456/api/projects/<ID>/start -H 'Content-Type: application/json' -d '{"target":"all"}'

# 4. 日志（等待数秒后）
curl localhost:3456/api/projects/<ID>/log/0

# 5. 停止
curl -X POST localhost:3456/api/projects/<ID>/stop -H 'Content-Type: application/json' -d '{"target":"all"}'
```
