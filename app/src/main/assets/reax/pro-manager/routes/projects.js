const express = require('express');
const router = express.Router();
const projectService = require('../services/projectService');
const processService = require('../services/processService');
const configService = require('../configService');
const languageDetector = require('../services/languageDetector');

// GET /api/projects - 获取所有项目（按修改时间倒序）
router.get('/', async (req, res) => {
  try {
    const projects = await projectService.getAll();
    res.json({ success: true, data: projects });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/projects/:id - 获取单个项目
router.get('/:id', async (req, res) => {
  try {
    const project = await projectService.getByIndex(req.params.id);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects - 创建项目（项目框架）
router.post('/', async (req, res) => {
  try {
    const { name, scaffold } = req.body;
    if (!name) return res.status(400).json({ error: '项目名称不能为空' });
    const project = await projectService.create({ name, scaffold });
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/generic - 创建通用项目（新建）
router.post('/generic', async (req, res) => {
  try {
    const { name, dir } = req.body;
    if (!name) return res.status(400).json({ error: '项目名称不能为空' });
    const project = await projectService.createGeneric({ name, dir });
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/import - 从Git仓库导入项目
router.post('/import', async (req, res) => {
  try {
    const { url, username, password, name } = req.body;
    if (!url) return res.status(400).json({ error: 'Git仓库URL不能为空' });
    const project = await projectService.importFromGit({ url, username, password, name });
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/detect-single - 检测单个目录
router.post('/detect-single', (req, res) => {
  try {
    const { dir } = req.body;
    if (!dir) return res.status(400).json({ error: '目录路径不能为空' });
    const fs = require('fs');
    if (!fs.existsSync(dir)) {
      return res.status(400).json({ error: `目录不存在: ${dir}` });
    }
    const result = languageDetector.detectSingleDir(dir);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// PUT /api/projects/:id - 重命名项目
router.put('/:id', (req, res) => {
  try {
    const { name } = req.body;
    if (!name) return res.status(400).json({ error: '项目名称不能为空' });
    const project = projectService.rename(req.params.id, name);
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// PUT /api/projects/:id/visible - 设置是否显示在列表中
router.put('/:id/visible', (req, res) => {
  try {
    const { visible } = req.body;
    const project = projectService.updateVisible(req.params.id, visible);
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// PUT /api/projects/:id/git - 更新Git配置
router.put('/:id/git', (req, res) => {
  try {
    const { url, username, password } = req.body;
    const project = projectService.updateGitConfig(req.params.id, { url, username, password });
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/:id/pull - 拉取最新代码
router.post('/:id/pull', async (req, res) => {
  try {
    const result = await projectService.pullLatest(req.params.id);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// DELETE /api/projects/:id - 删除项目
router.delete('/:id', async (req, res) => {
  try {
    const result = await projectService.delete(req.params.id);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/:id/module - 添加模块
router.post('/:id/module', async (req, res) => {
  try {
    const { name, type, dir, startupScript, port } = req.body;
    if (!type) return res.status(400).json({ error: '请指定 type (frontend/server)' });
    if (!dir) return res.status(400).json({ error: '目录不能为空' });
    const project = await projectService.addModule(req.params.id, { name, type, dir, startupScript, port });
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// PUT /api/projects/:id/module/:moduleIndex - 更新模块
router.put('/:id/module/:moduleIndex', (req, res) => {
  try {
    const { name, type, dir, port, startupScript } = req.body;
    const patch = {};
    if (name !== undefined) patch.name = name;
    if (type !== undefined) patch.type = type;
    if (dir !== undefined) patch.dir = dir;
    if (port !== undefined) patch.port = port;
    if (startupScript !== undefined) patch.startupScript = startupScript;
    const project = projectService.updateModule(req.params.id, parseInt(req.params.moduleIndex), patch);
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// DELETE /api/projects/:id/module/:moduleIndex - 移除模块
router.delete('/:id/module/:moduleIndex', (req, res) => {
  try {
    const project = projectService.removeModule(req.params.id, parseInt(req.params.moduleIndex));
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/:id/start - 启动项目（target: "all" 或模块索引）
router.post('/:id/start', async (req, res) => {
  try {
    const { target } = req.body;
    const result = await projectService.start(req.params.id, target || 'all');
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/:id/stop - 停止项目（target: "all" 或模块索引）
router.post('/:id/stop', async (req, res) => {
  try {
    const { target } = req.body;
    const result = await projectService.stop(req.params.id, target || 'all');
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/:id/sync - 同步状态
router.post('/:id/sync', async (req, res) => {
  try {
    const project = await projectService.syncStatus(req.params.id);
    res.json({ success: true, data: project });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/projects/:id/log/:moduleIndex - 获取模块运行日志
router.get('/:id/log/:moduleIndex', (req, res) => {
  try {
    const output = processService.getOutput(req.params.id, parseInt(req.params.moduleIndex));
    res.json({ success: true, data: { output } });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/projects/:id/log/:moduleIndex/stream - 流式日志
router.get('/:id/log/:moduleIndex/stream', (req, res) => {
  const projectIndex = req.params.id;
  const moduleIndex = parseInt(req.params.moduleIndex);
  const project = configService.getByIndex(projectIndex);
  if (!project?.modules?.[moduleIndex]) return res.status(404).json({ error: '模块不存在' });

  const mod = project.modules[moduleIndex];
  const moduleDir = require('path').resolve(project.rootDir, mod.dir);
  const logFile = processService._getLogFilePath(moduleDir);

  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  res.write('\n');

  const fs = require('fs');
  let offset = 0;

  if (fs.existsSync(logFile)) {
    try {
      const stat = fs.statSync(logFile);
      offset = stat.size;
      if (offset > 0) {
        const tail = fs.readFileSync(logFile, 'utf-8').slice(-20000);
        res.write(`data: ${JSON.stringify({ type: 'init', content: tail })}\n\n`);
      }
    } catch {}
  }

  let closed = false;
  let watcher = null;

  const check = () => {
    if (closed) return;
    try {
      if (!fs.existsSync(logFile)) return;
      const stat = fs.statSync(logFile);
      if (stat.size > offset) {
        const fd = fs.openSync(logFile, 'r');
        const buf = Buffer.alloc(stat.size - offset);
        fs.readSync(fd, buf, 0, buf.length, offset);
        fs.closeSync(fd);
        offset = stat.size;
        const chunk = buf.toString('utf-8');
        if (chunk) res.write(`data: ${JSON.stringify({ type: 'chunk', content: chunk })}\n\n`);
      }
    } catch {}
  };

  if (fs.existsSync(logFile)) {
    try {
      watcher = fs.watch(logFile, check);
    } catch {}
  }

  const pollTimer = setInterval(check, 1000);

  req.on('close', () => {
    closed = true;
    clearInterval(pollTimer);
    if (watcher) try { watcher.close(); } catch {}
  });
});

// GET /api/projects/:id/module/:moduleIndex/script - 获取模块启动脚本内容
router.get('/:id/module/:moduleIndex/script', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.id);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const mod = project.modules?.[parseInt(req.params.moduleIndex)];
    if (!mod) return res.status(404).json({ error: '模块不存在' });
    const dir = path.resolve(project.rootDir, mod.dir);
    const content = processService.readStartupScript(dir) || '';
    res.json({ success: true, data: { content } });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/projects/:id/scan-modules - 扫描项目子目录
router.post('/:id/scan-modules', async (req, res) => {
  try {
    const index = req.params.id;
    const project = configService.getByIndex(index);
    if (!project) return res.status(404).json({ error: '项目不存在' });

    const subdirs = languageDetector.detectSubdirs(project.rootDir);
    const existingDirs = new Set();
    if (project.modules) {
      for (const mod of project.modules) {
        existingDirs.add(mod.dir.replace(/^\.\//, '').replace(/\/+$/, '') || '.');
      }
    }

    const modules = subdirs
      .filter(s => s.language !== 'unknown' && !existingDirs.has(s.name))
      .map(s => ({
        name: s.name === '.' ? '(根目录)' : s.name,
        dir: s.name === '.' ? './' : `./${s.name}`,
        language: s.language,
        startCommand: s.startCommand,
        port: s.port,
        type: s.isFrontend ? 'frontend' : 'server'
      }));

    res.json({ success: true, data: { modules } });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
