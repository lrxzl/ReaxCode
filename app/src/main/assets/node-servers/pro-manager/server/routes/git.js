const express = require('express');
const router = express.Router();
const gitService = require('../services/gitService');
const configService = require('../configService');

// GET /api/git/:projectIndex/status - 获取 git 状态
router.get('/:projectIndex/status', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const status = gitService.status(project.rootDir);
    res.json({ success: true, data: status });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/history - 获取提交历史
router.get('/:projectIndex/history', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const limit = parseInt(req.query.limit) || 50;
    const history = gitService.getHistory(project.rootDir, limit);
    res.json({ success: true, data: history });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/commit - 提交（保存快照）
router.post('/:projectIndex/commit', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { message } = req.body;
    const result = gitService.commit(project.rootDir, message);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/commit-file - 提交单个文件
router.post('/:projectIndex/commit-file', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { file, message } = req.body;
    if (!file) return res.status(400).json({ error: '文件路径不能为空' });
    const result = gitService.commitFile(project.rootDir, file, message);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/restore/:hash - 恢复到指定提交
router.post('/:projectIndex/restore/:hash', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.restore(project.rootDir, req.params.hash);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/restore-file - 撤回单个文件的更改
router.post('/:projectIndex/restore-file', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { file } = req.body;
    if (!file) return res.status(400).json({ error: '文件路径不能为空' });
    const result = gitService.restoreFile(project.rootDir, file);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/show/:hash - 查看提交详情
router.get('/:projectIndex/show/:hash', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.show(project.rootDir, req.params.hash);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/diff - 查看差异
router.get('/:projectIndex/diff', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { hash1, hash2 } = req.query;
    const result = gitService.diff(project.rootDir, hash1 || 'HEAD~1', hash2);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/diff-working - 查看工作区未提交的差异
router.get('/:projectIndex/diff-working', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.diffWorkingTree(project.rootDir);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/diff-file?file=xxx - 查看单个文件的差异
router.get('/:projectIndex/diff-file', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { file } = req.query;
    if (!file) return res.status(400).json({ error: '缺少 file 参数' });
    const result = gitService.diffFile(project.rootDir, file);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/discard - 丢弃所有未提交更改
router.post('/:projectIndex/discard', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.discardAll(project.rootDir);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/branches - 获取所有分支
router.get('/:projectIndex/branches', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const branches = gitService.getBranches(project.rootDir);
    res.json({ success: true, data: branches });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/branch/current - 获取当前分支
router.get('/:projectIndex/branch/current', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const branch = gitService.getCurrentBranch(project.rootDir);
    res.json({ success: true, data: { branch } });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/checkout - 切换分支
router.post('/:projectIndex/checkout', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { branch } = req.body;
    if (!branch) return res.status(400).json({ error: '分支名不能为空' });
    const result = gitService.checkout(project.rootDir, branch);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/branch/create - 创建新分支
router.post('/:projectIndex/branch/create', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { name } = req.body;
    if (!name) return res.status(400).json({ error: '分支名不能为空' });
    const result = gitService.createBranch(project.rootDir, name);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/branch/track - 从远程分支创建本地跟踪分支
router.post('/:projectIndex/branch/track', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { remoteBranch } = req.body;
    if (!remoteBranch) return res.status(400).json({ error: '远程分支名不能为空' });
    const result = gitService.trackBranch(project.rootDir, remoteBranch);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/push - 推送到远程
router.post('/:projectIndex/push', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    if (!project.gitPassword) {
      return res.status(400).json({ error: '未配置认证令牌，请先在 Git 管理页面的远程配置中保存私人令牌' });
    }
    const { force, setUpstream } = req.body || {};
    const result = gitService.push(project.rootDir, {
      force, setUpstream,
      username: project.gitUsername,
      password: project.gitPassword
    });
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/fetch - 从远程获取最新分支信息
router.post('/:projectIndex/fetch', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    if (!project.gitPassword) {
      return res.status(400).json({ error: '未配置认证令牌，请先在 Git 管理页面的远程配置中保存私人令牌' });
    }
    const result = gitService.fetch(project.rootDir, {
      username: project.gitUsername,
      password: project.gitPassword
    });
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/pull - 从远程拉取更新
router.post('/:projectIndex/pull', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    if (!project.gitPassword) {
      return res.status(400).json({ error: '未配置认证令牌，请先在 Git 管理页面的远程配置中保存私人令牌' });
    }
    const { strategy } = req.body || {};
    const result = gitService.pull(project.rootDir, {
      strategy,
      username: project.gitUsername,
      password: project.gitPassword
    });
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/local-branches - 获取本地分支
router.get('/:projectIndex/local-branches', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.getLocalBranches(project.rootDir);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/remote-branches - 获取远程分支
router.get('/:projectIndex/remote-branches', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.getRemoteBranches(project.rootDir);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/merge - 合并分支
router.post('/:projectIndex/merge', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { branch } = req.body;
    if (!branch) return res.status(400).json({ error: '分支名不能为空' });
    const result = gitService.merge(project.rootDir, branch);
    if (result.conflict) {
      return res.status(200).json({ success: false, data: result });
    }
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/merge-abort - 中止合并
router.post('/:projectIndex/merge-abort', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.mergeAbort(project.rootDir);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/git/:projectIndex/remote - 获取远程仓库地址
router.get('/:projectIndex/remote', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const url = gitService.getRemoteUrl(project.rootDir);
    res.json({ success: true, data: { url } });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/remote - 设置远程仓库
router.post('/:projectIndex/remote', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { url } = req.body;
    if (!url) return res.status(400).json({ error: '远程仓库地址不能为空' });
    const result = gitService.setRemote(project.rootDir, url);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/git/:projectIndex/create-repo - 创建远程仓库
router.post('/:projectIndex/create-repo', async (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const { url, password } = req.body;
    if (!url) return res.status(400).json({ error: '仓库地址不能为空' });
    const result = await gitService.createRemoteRepo({ url, password });
    gitService.setRemote(project.rootDir, url);
    configService.update(req.params.projectIndex, { gitUrl: url, gitPassword: password });
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// DELETE /api/git/:projectIndex/branch/:name - 删除分支
router.delete('/:projectIndex/branch/:name', (req, res) => {
  try {
    const project = configService.getByIndex(req.params.projectIndex);
    if (!project) return res.status(404).json({ error: '项目不存在' });
    const result = gitService.deleteBranch(project.rootDir, req.params.name);
    res.json({ success: true, data: result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
