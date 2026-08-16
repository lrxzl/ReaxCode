const express = require('express');
const router = express.Router();
const fileService = require('../services/fileService');
const path = require('path');
const os = require('os');
const multer = require('multer');
const fs = require('fs');

const upload = multer({ dest: path.join(os.tmpdir(), 'pro-manager-upload') });

// GET /api/files/roots - 获取可用的根目录（Windows 驱动器盘符等）
router.get('/roots', (req, res) => {
  try {
    const roots = [];
    if (process.platform === 'win32') {
      // Windows: 列出所有驱动器盘符
      for (let i = 65; i <= 90; i++) {
        const drive = String.fromCharCode(i) + ':\\';
        try {
          const fs = require('fs');
          fs.accessSync(drive);
          roots.push({ name: drive, path: drive });
        } catch {}
      }
    } else {
      roots.push({ name: '/', path: '/' });
    }
    res.json({ success: true, data: roots });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/files/home - 获取用户主目录
router.get('/home', (req, res) => {
  try {
    res.json({ success: true, data: os.homedir() });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/files/list?path=... - 列出目录
router.get('/list', (req, res) => {
  try {
    const dirPath = req.query.path;
    if (!dirPath) return res.status(400).json({ error: '缺少 path 参数' });
    const files = fileService.listDir(dirPath);
    res.json({ success: true, data: files });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/files/read?path=... - 读取文件
router.get('/read', (req, res) => {
  try {
    const filePath = req.query.path;
    if (!filePath) return res.status(400).json({ error: '缺少 path 参数' });
    const content = fileService.readFile(filePath);
    res.json({ success: true, data: { content, path: filePath } });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/files/write - 写入文件
router.post('/write', (req, res) => {
  try {
    const { path: filePath, content } = req.body;
    if (!filePath) return res.status(400).json({ error: '缺少 path' });
    if (content === undefined) return res.status(400).json({ error: '缺少 content' });
    fileService.writeFile(filePath, content);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/files/mkdir - 创建目录
router.post('/mkdir', (req, res) => {
  try {
    const { path: dirPath } = req.body;
    if (!dirPath) return res.status(400).json({ error: '缺少 path' });
    fileService.createDir(dirPath);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/files/delete - 删除文件/目录
router.post('/delete', (req, res) => {
  try {
    const { path: targetPath } = req.body;
    if (!targetPath) return res.status(400).json({ error: '缺少 path' });
    fileService.delete(targetPath);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/files/rename - 重命名
router.post('/rename', (req, res) => {
  try {
    const { path: oldPath, newName } = req.body;
    if (!oldPath || !newName) return res.status(400).json({ error: '缺少参数' });
    const newPath = fileService.rename(oldPath, newName);
    res.json({ success: true, data: { newPath } });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/files/copy - 复制
router.post('/copy', (req, res) => {
  try {
    const { src, dest } = req.body;
    if (!src || !dest) return res.status(400).json({ error: '缺少参数' });
    fileService.copy(src, dest);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/files/move - 移动
router.post('/move', (req, res) => {
  try {
    const { src, dest } = req.body;
    if (!src || !dest) return res.status(400).json({ error: '缺少参数' });
    fileService.move(src, dest);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/files/search?path=...&keyword=... - 搜索
router.get('/search', (req, res) => {
  try {
    const { path: dirPath, keyword } = req.query;
    if (!dirPath || !keyword) return res.status(400).json({ error: '缺少参数' });
    const results = fileService.search(dirPath, keyword);
    res.json({ success: true, data: results });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/files/download?path=... - 下载文件/目录（目录自动打包 zip）
router.get('/download', (req, res) => {
  const filePath = req.query.path;
  if (!filePath) return res.status(400).json({ error: '缺少 path 参数' });

  const fs = require('fs');
  if (!fs.existsSync(filePath)) return res.status(404).json({ error: '文件不存在' });

  const stat = fs.statSync(filePath);
  const fileName = path.basename(filePath);

  if (stat.isDirectory()) {
    const archiver = require('archiver');
    const archive = archiver('zip', { zlib: { level: 6 } });
    let aborted = false;

    res.setHeader('Content-Disposition', `attachment; filename*=UTF-8''${encodeURIComponent(fileName + '.zip')}`);
    res.setHeader('Content-Type', 'application/zip');
    archive.pipe(res);

    archive.on('error', (err) => {
      if (!aborted) {
        aborted = true;
        if (!res.headersSent) {
          res.status(500).json({ error: err.message });
        } else {
          res.end();
        }
      }
    });

    archive.directory(filePath, fileName);
    archive.finalize();

    req.on('close', () => {
      if (!aborted) {
        aborted = true;
        if (!archive.destroyed) {
          archive.abort();
        }
        if (!res.destroyed) {
          res.end();
        }
      }
    });
  } else {
    res.setHeader('Content-Disposition', `attachment; filename*=UTF-8''${encodeURIComponent(fileName)}`);
    res.setHeader('Content-Length', stat.size);
    res.setHeader('Content-Type', 'application/octet-stream');
    const stream = fs.createReadStream(filePath);
    stream.pipe(res);

    req.on('close', () => {
      if (!stream.destroyed) {
        stream.destroy();
      }
    });

    stream.on('error', (err) => {
      if (!res.headersSent) {
        res.status(500).json({ error: err.message });
      } else {
        res.end();
      }
    });
  }
});

// GET /api/files/ip - 获取局域网IP
router.get('/ip', (req, res) => {
  try {
    const interfaces = os.networkInterfaces();
    const ips = [];
    for (const name of Object.keys(interfaces)) {
      for (const iface of interfaces[name]) {
        if (iface.family === 'IPv4' && !iface.internal) {
          ips.push(iface.address);
        }
      }
    }
    res.json({ success: true, data: ips });
  } catch (err) {
    res.json({ success: true, data: [] });
  }
});

// POST /api/files/upload - 上传文件到指定目录
router.post('/upload', upload.array('files'), (req, res) => {
  try {
    const dirPath = req.body.dir;
    if (!dirPath) return res.status(400).json({ error: '缺少 dir 参数' });
    if (!req.files || !req.files.length) return res.status(400).json({ error: '没有文件' });

    for (const file of req.files) {
      const destPath = path.join(dirPath, file.originalname);
      fs.copyFileSync(file.path, destPath);
      fs.unlinkSync(file.path);
    }

    res.json({ success: true, count: req.files.length });
  } catch (err) {
    // cleanup temp files
    if (req.files) {
      for (const file of req.files) {
        try { fs.unlinkSync(file.path); } catch {}
      }
    }
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
