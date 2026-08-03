# {{PROJECT_TITLE}}

## 快速开始

```bash
npm run install:all
npm run dev
```

## 项目结构

```
{{PROJECT_NAME}}/
├── frontend/          # Vue 3 + Vite 前端
│   ├── src/
│   │   ├── views/     # 页面组件
│   │   ├── router/    # 路由配置
│   │   ├── App.vue    # 根组件
│   │   └── main.js    # 入口文件
│   └── package.json
└── server/            # Express.js 后端
    ├── routes/        # API 路由
    ├── db.js          # SQLite 数据库
    ├── index.js       # 入口文件
    └── package.json
```



## AI获取实时编译信息
1. 项目启动后，你可以curl http://localhost:<端口>/api/compile-info 这个接口获取实时的编译信息
2. 对应端口从project-list.json里面获取

## API 接口
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/health | 健康检查 |
| GET | /api/items | 获取列表 |
| POST | /api/items | 创建项目 |
| PUT | /api/items/:id | 更新项目 |
| DELETE | /api/items/:id | 删除项目 |
