const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const { initDB } = require('./db');
const apiRoutes = require('./routes/api');


const app = express();
const PORT = process.env.PORT || 3000;

// 编译信息（由前端构建脚本写入 build-status.json，或由运行时错误更新）
let compileInfo = '编译无错误';

// 启动时读取构建状态文件
const buildStatusPath = path.join(__dirname, '..', 'frontend', 'dist', 'build-status.json');
try {
  if (fs.existsSync(buildStatusPath)) {
    const status = JSON.parse(fs.readFileSync(buildStatusPath, 'utf-8'));
    if (status.compileInfo) compileInfo = status.compileInfo;
  }
} catch (e) {
  // 文件不存在或不合法，保持默认
}

// 全局异常捕获 - 写入编译/运行时错误
process.on('uncaughtException', (err) => {
  const msg = `未捕获异常: ${err.message}`;
  console.error(msg);
  compileInfo = msg;
  // 追加到错误日志
  const logPath = path.join(__dirname, '..', 'error.log');
  fs.appendFileSync(logPath, `[${new Date().toISOString()}] ${msg}
`);
});
process.on('unhandledRejection', (reason) => {
  const msg = `未处理的 Promise 拒绝: ${reason?.message || reason}`;
  console.error(msg);
  compileInfo = msg;
  const logPath = path.join(__dirname, '..', 'error.log');
  fs.appendFileSync(logPath, `[${new Date().toISOString()}] ${msg}
`);
});

app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// 生产环境提供前端静态文件
const distPath = path.join(__dirname, '..', 'frontend', 'dist');
app.use(express.static(distPath));

// API 路由 - 自定义 /api/compile-info 必须在通用 /api 之前
app.get('/api/compile-info', (req, res) => {
  res.json({ compileInfo });
});

// 通用 API 路由（需要放在 /api/compile-info 之后，否则会被吞）
app.use('/api', apiRoutes);

// SPA 回退
app.get('*', (req, res) => {
  if (req.path.startsWith('/api')) return res.status(404).json({ error: 'Not found' });
  res.sendFile(path.join(distPath, 'index.html'));
});

// 初始化数据库并启动
initDB().then(() => {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
});
