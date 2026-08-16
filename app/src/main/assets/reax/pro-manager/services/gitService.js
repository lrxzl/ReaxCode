const { execFileSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const https = require('https');

// ─── 工具函数 ────────────────────────────────────────────

/** 隐藏 URL 中的密码 */
function maskUrl(url) {
  if (!url) return url;
  return url.replace(/\/\/([^:/]+):([^@]+)@/g, '//$1:***@');
}

/** 将凭证嵌入 URL（URL 对象会自动编码特殊字符） */
function buildAuthUrl(remoteUrl, username, password) {
  if (!password) return remoteUrl;
  try {
    const urlObj = new URL(remoteUrl);
    urlObj.username = username || urlObj.username || 'token';
    urlObj.password = password;
    return urlObj.toString();
  } catch {
    return remoteUrl;
  }
}

/** 校验 commit hash */
function isValidHash(hash) {
  return typeof hash === 'string' && /^[0-9a-f]{4,40}$/i.test(hash);
}

/** 校验分支名（拒绝 shell 特殊字符和路径穿越） */
function isValidBranchName(name) {
  if (!name || typeof name !== 'string') return false;
  if (name.startsWith('-') || name.length > 200) return false;
  return !/[~^:?*\[\]\\]/.test(name) && !/\.\./.test(name);
}

/** 反转义 git porcelain 输出中被引号包裹的路径 */
function unquotePath(s) {
  if (!s || s.length < 2 || s[0] !== '"' || s[s.length - 1] !== '"') return s;
  return s.slice(1, -1).replace(/\\([0-7]{3}|.)/g, (_, g) =>
      /^[0-7]{3}$/.test(g) ? String.fromCharCode(parseInt(g, 8)) : g
  );
}

// ─── gitService ──────────────────────────────────────────

const gitService = {
  // ─── 默认 .gitignore（去重） ──────────────────────────

  DEFAULT_GITIGNORE: `# Dependencies
node_modules/
.pnp
.pnp.js

# Build output
dist/
build/
out/

# Runtime
*.log
npm-debug.log*
yarn-debug.log*
yarn-error.log*
pnpm-debug.log*

# Environment
.env
.env.local
.env.*.local

# IDE
.vscode/
.idea/
*.swp
*.swo
*~

# OS
.DS_Store
Thumbs.db

# Python
__pycache__/
*.py[cod]
*.egg-info/
.venv/
venv/

# Java
*.class
*.jar
target/

# Customs
startup.sh
startup.bat.log
pro-info.json
**/pro-info.json
`,

  // ─── 底层执行 ──────────────────────────────────────────

  /**
   * 执行 git 命令（使用 execFileSync + 数组参数，杜绝 shell 注入）
   * @param {string[]} args - git 子命令及参数，如 ['status', '--porcelain']
   * @param {string} cwd - 工作目录
   * @param {{timeout?: number, maxBuffer?: number}} opts
   */
  _git(args, cwd, { timeout = 60000, maxBuffer = 50 * 1024 * 1024 } = {}) {
    const env = {
      ...process.env,
      GIT_AUTHOR_NAME: process.env.GIT_AUTHOR_NAME || 'Seeker Server',
      GIT_AUTHOR_EMAIL: process.env.GIT_AUTHOR_EMAIL || 'seeker@localhost',
      GIT_COMMITTER_NAME: process.env.GIT_COMMITTER_NAME || process.env.GIT_AUTHOR_NAME || 'Seeker Server',
      GIT_COMMITTER_EMAIL: process.env.GIT_COMMITTER_EMAIL || process.env.GIT_AUTHOR_EMAIL || 'seeker@localhost',
      // 禁止交互式凭证提示，避免进程挂起
      GIT_TERMINAL_PROMPT: '0',
    };
    try {
      return execFileSync('git', args, {
        cwd,
        encoding: 'utf-8',
        stdio: ['pipe', 'pipe', 'pipe'],
        env,
        timeout,
        maxBuffer,
      }).trim();
    } catch (err) {
      const stderr = (err.stderr || '').toString().trim();
      const stdout = (err.stdout || '').toString().trim();
      let msg = stderr || stdout || err.message || '未知错误';
      // 清除错误信息中可能泄露的凭证
      msg = maskUrl(msg);
      throw new Error(msg);
    }
  },

  /**
   * HTTPS 请求（用于 Git 平台 API）
   * 修复：跨域重定向时移除 Authorization 头；修复 message$description 拼写错误
   */
  _httpRequest(options, postData, maxRedirects = 5) {
    return new Promise((resolve, reject) => {
      const req = https.request(options, (res) => {
        // 处理 3xx 重定向
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && maxRedirects > 0) {
          const redirectUrl = new URL(res.headers.location, `https://${options.hostname}`);
          const headers = { ...options.headers };
          // 安全：跨域重定向时不携带 Authorization 头
          if (redirectUrl.hostname !== options.hostname) {
            delete headers.Authorization;
          }
          return this._httpRequest({
            hostname: redirectUrl.hostname,
            path: redirectUrl.pathname + redirectUrl.search,
            method: options.method,
            headers,
          }, postData, maxRedirects - 1).then(resolve, reject);
        }

        let body = '';
        res.on('data', chunk => { body += chunk; });
        res.on('end', () => {
          try {
            const json = JSON.parse(body);
            if (res.statusCode >= 200 && res.statusCode < 300) {
              resolve({ success: true, data: json, url: json.html_url || json.clone_url });
            } else {
              // 修复：原代码 json.message$description 是拼写错误
              const errMsg = json.message || json.description || json.error || JSON.stringify(json);
              reject(new Error(`(${res.statusCode}) ${errMsg}`));
            }
          } catch {
            reject(new Error(`API 响应解析失败 (${res.statusCode}): ${body.slice(0, 300)}`));
          }
        });
      });
      req.on('error', reject);
      if (postData) req.write(postData);
      req.end();
    });
  },

  // ─── 仓库初始化 ────────────────────────────────────────

  /** 确保目录是 git 仓库（轻量检查，不每次同步 gitignore） */
  _ensureRepo(projectDir) {
    if (!fs.existsSync(path.join(projectDir, '.git'))) {
      this._initRepo(projectDir);
      this._syncGitignore(projectDir);
      this._cleanIgnored(projectDir);
    }
  },

  _initRepo(projectDir) {
    const gitignorePath = path.join(projectDir, '.gitignore');
    if (!fs.existsSync(gitignorePath)) {
      fs.writeFileSync(gitignorePath, this.DEFAULT_GITIGNORE, 'utf-8');
    }
    this._git(['init'], projectDir);
    this._git(['add', '-A'], projectDir);
    this._git(['commit', '-m', 'Initial commit'], projectDir);
  },

  /** 公开 API：初始化并同步 gitignore 规则 */
  init(projectDir) {
    if (!fs.existsSync(path.join(projectDir, '.git'))) {
      this._initRepo(projectDir);
    }
    this._syncGitignore(projectDir);
    this._cleanIgnored(projectDir);
    return { success: true };
  },

  /**
   * 同步 .gitignore：将 DEFAULT_GITIGNORE 中缺失的规则补上
   * 修复：原代码用 includes 做子串匹配，会误判；改为逐行精确比对
   */
  _syncGitignore(projectDir) {
    const gitignorePath = path.join(projectDir, '.gitignore');
    const existing = fs.existsSync(gitignorePath) ? fs.readFileSync(gitignorePath, 'utf-8') : '';
    const existingLines = new Set(
        existing.split('\n').map(l => l.trim()).filter(Boolean)
    );
    const needAdd = this.DEFAULT_GITIGNORE.split('\n')
        .map(l => l.trim())
        .filter(l => l && !existingLines.has(l));
    if (needAdd.length) {
      const prefix = existing && !existing.endsWith('\n') ? '\n' : '';
      fs.appendFileSync(gitignorePath, prefix + needAdd.join('\n') + '\n', 'utf-8');
    }
  },

  /**
   * 移除 .gitignore 中应忽略但仍被跟踪的文件
   * 优化：批量处理避免参数过长；提交前检查是否有变更
   */
  _cleanIgnored(projectDir) {
    try {
      const output = this._git(['ls-files', '-i', '--exclude-standard'], projectDir);
      if (!output) return;
      const files = output.split('\n').filter(Boolean);
      if (!files.length) return;

      // 分批处理，避免参数列表过长
      const BATCH = 100;
      for (let i = 0; i < files.length; i += BATCH) {
        this._git(['rm', '--cached', '--', ...files.slice(i, i + BATCH)], projectDir);
      }

      // 仅在有暂存变更时提交
      const status = this._git(['status', '--porcelain'], projectDir);
      if (status) {
        this._git(['commit', '-m', 'chore: remove ignored files from tracking'], projectDir);
      }
    } catch { /* 忽略 */ }
  },

  // ─── 状态查询 ──────────────────────────────────────────

  isGitRepo(projectDir) {
    return fs.existsSync(path.join(projectDir, '.git'));
  },

  /** 解析 git status --porcelain 单行 */
  _parseStatusLine(line) {
    const x = line[0];
    const y = line[1];
    let rest = line.substring(3); // 跳过 "XY "
    let oldFile = null;

    // 处理 rename/copy: "old -> new"
    const arrowIdx = rest.indexOf(' -> ');
    if (arrowIdx !== -1) {
      oldFile = unquotePath(rest.substring(0, arrowIdx));
      rest = rest.substring(arrowIdx + 4);
    }

    const file = unquotePath(rest);
    const flag = (x + y).trim();

    let status = 'modified';
    if (flag === '??') status = 'new';
    else if (x === 'D' || y === 'D') status = 'deleted';
    else if (x === 'R' || y === 'R') status = 'renamed';
    else if (x === 'C' || y === 'C') status = 'copied';
    else if (x === 'A' || y === 'A') status = 'added';

    return { flag, file, status, oldFile };
  },

  status(projectDir) {
    this._ensureRepo(projectDir);
    const output = this._git(['status', '--porcelain'], projectDir);
    const files = output
        ? output.split('\n').filter(Boolean).map(l => this._parseStatusLine(l))
        : [];
    return { files, clean: files.length === 0 };
  },

  /**
   * 获取提交历史
   * 修复：原用 | 分隔，commit message 中的 | 会导致解析错误；
   * 改用 %x1f (Unit Separator) 作为字段分隔符
   */
  getHistory(projectDir, limit = 50) {
    this._ensureRepo(projectDir);
    try {
      const log = this._git(
          ['log', `-${limit}`, '--format=%H%x1f%s%x1f%ai'],
          projectDir
      );
      if (!log) return [];
      return log.split('\n').filter(Boolean).map(line => {
        const [hash, message, date] = line.split('\x1f');
        return { hash: hash || '', message: message || '', date: date || '' };
      });
    } catch {
      return [];
    }
  },

  getHead(projectDir) {
    this._ensureRepo(projectDir);
    try {
      return this._git(['rev-parse', 'HEAD'], projectDir);
    } catch {
      return null;
    }
  },

  getCurrentBranch(projectDir) {
    this._ensureRepo(projectDir);
    try {
      return this._git(['rev-parse', '--abbrev-ref', 'HEAD'], projectDir) || 'HEAD';
    } catch {
      return 'HEAD';
    }
  },

  getBranches(projectDir) {
    this._ensureRepo(projectDir);
    try {
      const result = this._git(['branch', '-a'], projectDir);
      const current = this.getCurrentBranch(projectDir);
      const branches = [];
      const seen = new Set();

      for (const line of result.split('\n').filter(Boolean)) {
        const clean = line.replace(/^\*?\s+/, '').trim();
        if (!clean || clean.includes('HEAD ->')) continue;

        if (clean.startsWith('remotes/origin/')) {
          const name = clean.replace('remotes/origin/', '');
          if (!seen.has(name)) {
            seen.add(name);
            branches.push({ name, remote: true, current: name === current });
          }
        } else {
          if (!seen.has(clean)) {
            seen.add(clean);
            branches.push({ name: clean, remote: false, current: clean === current });
          }
        }
      }
      return branches;
    } catch {
      return [{ name: 'main', remote: false, current: true }];
    }
  },

  getLocalBranches(projectDir) {
    this._ensureRepo(projectDir);
    try {
      const result = this._git(['branch'], projectDir);
      const current = this.getCurrentBranch(projectDir);
      return result.split('\n').filter(Boolean).map(line => {
        const name = line.replace(/^\*\s+/, '').trim();
        return { name, current: name === current };
      });
    } catch {
      return [{ name: 'main', current: true }];
    }
  },

  getRemoteBranches(projectDir) {
    this._ensureRepo(projectDir);
    try {
      const result = this._git(['branch', '-r'], projectDir);
      const current = this.getCurrentBranch(projectDir);
      return result.split('\n').filter(Boolean)
          .map(line => line.trim())
          .filter(name => name && !name.includes('HEAD'))
          .map(name => {
            const branchName = name.replace(/^origin\//, '');
            return { name: branchName, fullName: name, current: branchName === current };
          });
    } catch {
      return [];
    }
  },

  getRemoteUrl(projectDir) {
    this._ensureRepo(projectDir);
    try {
      return this._git(['remote', 'get-url', 'origin'], projectDir);
    } catch {
      return null;
    }
  },

  // ─── 提交操作 ──────────────────────────────────────────

  /** 提交所有变更 */
  commit(projectDir, message) {
    this._ensureRepo(projectDir);
    this._git(['add', '-A'], projectDir);

    const status = this._git(['status', '--porcelain'], projectDir);
    if (!status) {
      return { success: true, message: '没有变更需要提交' };
    }

    const msg = message || `快照 ${new Date().toLocaleString('zh-CN')}`;
    // 数组传参，无需转义 commit message
    this._git(['commit', '-m', msg], projectDir);

    const hash = this._git(['rev-parse', 'HEAD'], projectDir);
    return { success: true, hash, message: msg };
  },

  /** 提交单个文件 */
  commitFile(projectDir, filePath, message) {
    this._ensureRepo(projectDir);

    // 检查文件是否有变更
    const status = this._git(['status', '--porcelain'], projectDir);
    const lines = status ? status.split('\n') : [];
    const hasChange = lines.some(line => {
      const info = this._parseStatusLine(line);
      return info.file === filePath;
    });
    if (!hasChange) {
      return { success: true, message: '该文件没有变更需要提交' };
    }

    this._git(['add', '--', filePath], projectDir);

    const msg = message || `${filePath} ${new Date().toLocaleString('zh-CN')}`;
    this._git(['commit', '-m', msg], projectDir);

    const hash = this._git(['rev-parse', 'HEAD'], projectDir);
    return { success: true, hash, message: msg };
  },

  // ─── 恢复操作 ──────────────────────────────────────────

  /** 恢复到指定提交 */
  restore(projectDir, commitHash) {
    this._ensureRepo(projectDir);
    if (!isValidHash(commitHash)) {
      throw new Error('无效的 commit hash');
    }

    // 先保存当前未提交的状态
    const status = this._git(['status', '--porcelain'], projectDir);
    if (status) {
      this._git(['add', '-A'], projectDir);
      this._git(['commit', '-m', '自动保存：恢复前'], projectDir);
    }

    // 获取目标 commit 的文件列表
    const targetFiles = this._git(
        ['ls-tree', '-r', '--name-only', commitHash], projectDir
    );
    const targetSet = new Set(
        targetFiles ? targetFiles.split('\n').filter(Boolean) : []
    );

    // 恢复文件到目标 commit 的状态
    this._git(['checkout', commitHash, '--', '.'], projectDir);

    // 删除目标 commit 中不存在的文件（之后新增的文件）
    const currentFiles = this._git(['ls-files'], projectDir);
    if (currentFiles) {
      for (const f of currentFiles.split('\n').filter(Boolean)) {
        if (!targetSet.has(f)) {
          try { fs.unlinkSync(path.join(projectDir, f)); } catch {}
        }
      }
    }

    this._git(['add', '-A'], projectDir);
    this._git(['commit', '-m', `恢复到 ${commitHash.substring(0, 7)}`], projectDir);

    return { success: true, restoredTo: commitHash };
  },

  /** 撤回单个文件的更改 */
  restoreFile(projectDir, filePath) {
    this._ensureRepo(projectDir);

    const status = this._git(['status', '--porcelain'], projectDir);
    const lines = status ? status.split('\n') : [];
    const statusLine = lines.find(line => {
      const info = this._parseStatusLine(line);
      return info.file === filePath;
    });
    if (!statusLine) {
      return { success: true, message: '该文件没有变更' };
    }

    const info = this._parseStatusLine(statusLine);
    if (info.status === 'new') {
      // 未跟踪的新文件，直接删除
      const fullPath = path.join(projectDir, filePath);
      if (fs.existsSync(fullPath)) {
        fs.unlinkSync(fullPath);
      }
    } else {
      // 已跟踪文件，恢复到 HEAD 版本
      this._git(['checkout', 'HEAD', '--', filePath], projectDir);
    }

    return { success: true, message: `已撤回 ${filePath} 的更改` };
  },

  /** 丢弃所有未提交的更改 */
  discardAll(projectDir) {
    this._ensureRepo(projectDir);
    this._git(['checkout', '--', '.'], projectDir);
    this._git(['clean', '-fd'], projectDir);
    return { success: true };
  },

  // ─── 分支操作 ──────────────────────────────────────────

  checkout(projectDir, branchName) {
    this._ensureRepo(projectDir);
    if (!isValidBranchName(branchName)) {
      throw new Error('无效的分支名');
    }
    this._git(['checkout', branchName], projectDir);
    return { success: true, branch: branchName };
  },

  createBranch(projectDir, branchName) {
    this._ensureRepo(projectDir);
    if (!isValidBranchName(branchName)) {
      throw new Error('无效的分支名');
    }
    this._git(['checkout', '-b', branchName], projectDir);
    return { success: true, branch: branchName };
  },

  trackBranch(projectDir, remoteBranchName) {
    this._ensureRepo(projectDir);
    const localName = remoteBranchName.replace(/^origin\//, '');
    if (!isValidBranchName(localName)) {
      throw new Error('无效的分支名');
    }
    this._git(['checkout', '-b', localName, '--track', remoteBranchName], projectDir);
    return { success: true, branch: localName };
  },

  deleteBranch(projectDir, branchName) {
    this._ensureRepo(projectDir);
    if (!isValidBranchName(branchName)) {
      throw new Error('无效的分支名');
    }
    this._git(['branch', '-d', branchName], projectDir);
    return { success: true };
  },

  /** 合并分支 */
  merge(projectDir, branchName) {
    this._ensureRepo(projectDir);
    if (!isValidBranchName(branchName)) {
      throw new Error('无效的分支名');
    }
    try {
      this._git(['merge', branchName], projectDir);
      return { success: true, message: `已合并分支 ${branchName}` };
    } catch (err) {
      const msg = err.message || '';
      if (msg.includes('CONFLICT') || msg.includes('conflict') || msg.includes('Automatic merge failed')) {
        return {
          success: false,
          conflict: true,
          message: '合并冲突，需要手动解决',
          conflictFiles: this._getConflictFiles(projectDir),
          error: msg,
        };
      }
      throw err;
    }
  },

  mergeAbort(projectDir) {
    this._ensureRepo(projectDir);
    this._git(['merge', '--abort'], projectDir);
    return { success: true, message: '已中止合并' };
  },

  _getConflictFiles(projectDir) {
    try {
      const output = this._git(['diff', '--name-only', '--diff-filter=U'], projectDir);
      return output ? output.split('\n').filter(Boolean) : [];
    } catch {
      return [];
    }
  },

  // ─── 远程操作 ──────────────────────────────────────────

  setRemote(projectDir, url) {
    this._ensureRepo(projectDir);
    try {
      this._git(['remote', 'set-url', 'origin', url], projectDir);
    } catch {
      this._git(['remote', 'add', 'origin', url], projectDir);
    }
    return { success: true };
  },

  /**
   * 获取带凭证的远程 URL
   * 优化：不再临时修改 remote URL（原 _withAuth 方式有竞态风险），
   * 而是直接用带凭证的 URL 进行 push/pull/fetch
   */
  _getAuthUrl(projectDir, username, password) {
    const remoteUrl = this.getRemoteUrl(projectDir);
    if (!remoteUrl) throw new Error('未配置远程仓库');
    return buildAuthUrl(remoteUrl, username, password);
  },

  /** 统一处理认证错误 */
  _wrapAuthError(err) {
    const msg = err.message || '';
    if (msg.includes('Username') || msg.includes('401') || msg.includes('Authentication') || msg.includes('could not read Username')) {
      return new Error('认证失败，请在 Git 管理页面配置远程仓库的私人令牌');
    }
    return err;
  },

  /** 推送到远程 */
  push(projectDir, options = {}) {
    this._ensureRepo(projectDir);
    const branch = this.getCurrentBranch(projectDir);
    const pushUrl = this._getAuthUrl(projectDir, options.username, options.password);

    const args = ['push'];
    if (options.setUpstream) args.push('-u');
    if (options.force) args.push('--force');
    args.push(pushUrl, branch);

    try {
      this._git(args, projectDir, { timeout: 120000 });
    } catch (err) {
      throw this._wrapAuthError(err);
    }
    return { success: true, branch, message: `已推送到远程 ${branch} 分支` };
  },

  /** 从远程拉取更新 */
  pull(projectDir, options = {}) {
    this._ensureRepo(projectDir);
    const branch = this.getCurrentBranch(projectDir);
    const pullUrl = this._getAuthUrl(projectDir, options.username, options.password);

    const args = ['pull'];
    if (options.strategy === 'merge') args.push('--no-rebase');
    else if (options.strategy === 'rebase') args.push('--rebase');
    args.push(pullUrl, branch);

    try {
      this._git(args, projectDir, { timeout: 120000 });
    } catch (err) {
      throw this._wrapAuthError(err);
    }
    return { success: true, branch, message: `已从远程 ${branch} 分支拉取更新` };
  },

  /** 从远程获取最新信息 */
  fetch(projectDir, options = {}) {
    this._ensureRepo(projectDir);
    const fetchUrl = this._getAuthUrl(projectDir, options.username, options.password);

    try {
      this._git(['fetch', fetchUrl], projectDir, { timeout: 120000 });
    } catch (err) {
      throw this._wrapAuthError(err);
    }
    return { success: true, message: '已从远程获取最新信息' };
  },

  /** 克隆远程仓库 */
  clone(url, targetDir, options = {}) {
    if (fs.existsSync(targetDir)) {
      throw new Error(`目标目录已存在: ${targetDir}`);
    }

    const parentDir = path.dirname(targetDir);
    if (!fs.existsSync(parentDir)) {
      fs.mkdirSync(parentDir, { recursive: true });
    }

    const cloneUrl = buildAuthUrl(url, options.username, options.password);
    console.log(`[gitService] 克隆 ${maskUrl(cloneUrl)} 到 ${targetDir}`);

    this._git(['clone', cloneUrl, targetDir], parentDir, { timeout: 300000 });

    console.log(`[gitService] 克隆完成`);
    return { success: true, targetDir };
  },

  /** 验证远程仓库 URL 是否可访问 */
  async validateRemoteUrl(url, options = {}) {
    try {
      const validateUrl = buildAuthUrl(url, options.username, options.password);
      // 修复：原代码使用 require.main.filename，在模块场景下可能为 null
      this._git(['ls-remote', validateUrl, 'HEAD'], process.cwd(), { timeout: 30000 });
      return { valid: true };
    } catch (err) {
      return { valid: false, error: maskUrl(err.message) };
    }
  },

  // ─── Diff 操作 ─────────────────────────────────────────

  show(projectDir, commitHash) {
    this._ensureRepo(projectDir);
    if (!isValidHash(commitHash)) {
      throw new Error('无效的 commit hash');
    }
    const detail = this._git(['show', '--stat', commitHash], projectDir);
    return { hash: commitHash, detail };
  },

  diff(projectDir, ref1, ref2) {
    this._ensureRepo(projectDir);
    const args = ref2 ? ['diff', ref1, ref2] : ['diff', ref1];
    return { diff: this._git(args, projectDir) };
  },

  /**
   * 查看工作区未提交的差异
   * 修复：新文件 diff 的行数计算（末尾换行导致 off-by-one）
   */
  diffWorkingTree(projectDir) {
    this._ensureRepo(projectDir);
    const diff = this._git(['diff'], projectDir);
    const diffCached = this._git(['diff', '--cached'], projectDir);

    // 对未跟踪的新文件，手动构造 diff
    const status = this.status(projectDir);
    const newFiles = (status.files || []).filter(f => f.status === 'new');
    let untrackedDiff = '';

    for (const f of newFiles) {
      const fullPath = path.join(projectDir, f.file);
      if (!fs.existsSync(fullPath)) continue;
      try {
        const content = fs.readFileSync(fullPath, 'utf-8');
        const lines = content.split('\n');
        // 修复：如果内容以 \n 结尾，split 会多产生一个空元素
        if (content.endsWith('\n')) lines.pop();
        const lineCount = lines.length;

        untrackedDiff += `diff --git a/${f.file} b/${f.file}\n`;
        untrackedDiff += `new file mode 100644\n`;
        untrackedDiff += `--- /dev/null\n`;
        untrackedDiff += `+++ b/${f.file}\n`;
        untrackedDiff += `@@ -0,0 +1,${lineCount} @@\n`;
        for (const line of lines) {
          untrackedDiff += '+' + line + '\n';
        }
      } catch { /* 忽略读取失败 */ }
    }

    return { diff: (diffCached || '') + (diff || '') + untrackedDiff };
  },

  /** 查看单个文件的差异 */
  diffFile(projectDir, filePath) {
    this._ensureRepo(projectDir);

    const status = this.status(projectDir);
    const fileInfo = (status.files || []).find(f => f.file === filePath);

    if (fileInfo && fileInfo.status === 'new') {
      // 新文件：直接构造 diff
      const fullPath = path.join(projectDir, filePath);
      if (!fs.existsSync(fullPath)) return { diff: '' };
      try {
        const content = fs.readFileSync(fullPath, 'utf-8');
        const lines = content.split('\n');
        if (content.endsWith('\n')) lines.pop();
        const lineCount = lines.length;

        let diff = `diff --git a/${filePath} b/${filePath}\n`
            + `new file mode 100644\n`
            + `--- /dev/null\n`
            + `+++ b/${filePath}\n`
            + `@@ -0,0 +1,${lineCount} @@\n`;
        for (const line of lines) {
          diff += '+' + line + '\n';
        }
        return { diff };
      } catch {
        return { diff: '' };
      }
    }

    // 已跟踪文件：使用 git diff
    const diff = this._git(['diff', '--', filePath], projectDir);
    const diffCached = this._git(['diff', '--cached', '--', filePath], projectDir);
    return { diff: (diffCached || '') + (diff || '') };
  },

  // ─── 远程仓库管理 ──────────────────────────────────────

  /** 从 URL 提取仓库名 */
  getRepoNameFromUrl(url) {
    try {
      const urlObj = new URL(url);
      const parts = urlObj.pathname.split('/').filter(Boolean);
      if (parts.length >= 2) {
        let name = parts[parts.length - 1];
        if (name.endsWith('.git')) name = name.slice(0, -4);
        return name;
      }
    } catch { /* 忽略 */ }
    return null;
  },

  /** 检测 Git 平台类型 */
  detectPlatform(url) {
    if (!url) return 'unknown';
    const lower = url.toLowerCase();
    if (lower.includes('github.com')) return 'github';
    if (lower.includes('gitee.com')) return 'gitee';
    return 'other';
  },

  /**
   * 创建远程仓库（GitHub / Gitee）
   * 修复：Gitee 应在 body 中传 access_token，而非用 Authorization 头
   */
  async createRemoteRepo({ url, password }) {
    if (!password) {
      throw new Error('需要提供私人令牌');
    }

    const platform = this.detectPlatform(url);
    const repoName = this.getRepoNameFromUrl(url);
    if (!repoName) {
      throw new Error('无法从 URL 解析仓库名');
    }

    if (platform === 'github') {
      const postData = JSON.stringify({
        name: repoName,
        auto_init: false,
        private: true,
      });
      return this._httpRequest({
        hostname: 'api.github.com',
        path: '/user/repos',
        method: 'POST',
        headers: {
          'Authorization': `token ${password}`,
          'Accept': 'application/vnd.github.v3+json',
          'Content-Type': 'application/json',
          'User-Agent': 'pro-manager',
        },
      }, postData);
    }

    if (platform === 'gitee') {
      // Gitee API v5：access_token 放在请求体中
      const postData = JSON.stringify({
        access_token: password,
        name: repoName,
        private: true,
      });
      return this._httpRequest({
        hostname: 'gitee.com',
        path: '/api/v5/user/repos',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
      }, postData);
    }

    throw new Error('不支持的 Git 平台，仅支持 GitHub 和 Gitee');
  },
};

module.exports = gitService;