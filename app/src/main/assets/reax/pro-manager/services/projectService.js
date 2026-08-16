const path = require('path');
const fs = require('fs');
const configService = require('../configService');
const scaffoldService = require('./scaffoldService');
const processService = require('./processService');
const gitService = require('./gitService');
const languageDetector = require('./languageDetector');

const PROJECTS_DIR = configService.PROJECTS_DIR;

const projectService = {
  // ========== 查询 ==========

  async getAll() {
    const projects = configService.getAll();
    const enriched = [];
    for (const p of projects) {
      enriched.push(await this._enrichProject(p));
    }
    enriched.sort((a, b) => {
      const tA = a.lastModified ? new Date(a.lastModified).getTime() : 0;
      const tB = b.lastModified ? new Date(b.lastModified).getTime() : 0;
      return tB - tA;
    });
    return enriched;
  },

  async getByIndex(index) {
    const project = configService.getByIndex(index);
    if (!project) return null;
    return this._enrichProject(project);
  },

  // ========== 创建 ==========

  async importFromGit({ url, username, password, name }) {
    if (!url || !url.trim()) {
      throw new Error('Git仓库URL不能为空');
    }

    const repoName = name || gitService.getRepoNameFromUrl(url.trim());
    if (!repoName) {
      throw new Error('无法从URL解析仓库名，请手动输入项目名称');
    }

    const safeName = repoName.trim().replace(/[^a-zA-Z0-9一-鿿㐀-䶿_\-\s]/g, '_').replace(/\s+/g, '_');
    const existing = configService.getByName(safeName);
    if (existing) {
      throw new Error(`项目名称 "${safeName}" 已存在`);
    }

    const rootDir = path.join(PROJECTS_DIR, safeName);

    try {
      await gitService.clone(url.trim(), rootDir, { username, password });
    } catch (err) {
      throw new Error(`克隆仓库失败: ${err.message}`);
    }

    const project = {
      projectName: safeName,
      rootDirName: safeName,
      rootDir,
      lastModified: new Date().toISOString(),
      visible: true,
      status: 'stopped',
      creationMethod: 'git',
      gitUrl: url.trim(),
      modules: [{ name: '默认', type: 'server', dir: '.' }]
    };

    if (username) project.gitUsername = username;
    if (password) project.gitPassword = password;

    return configService.add(project);
  },

  async createGeneric({ name, dir }) {
    if (!name || !name.trim()) {
      throw new Error('项目名称不能为空');
    }
    const safeName = name.trim().replace(/[^a-zA-Z0-9一-鿿㐀-䶿_\-\s]/g, '_').replace(/\s+/g, '_');
    const existing = configService.getByName(safeName);
    if (existing) {
      throw new Error(`项目名称 "${safeName}" 已存在`);
    }

    let resolvedDir;
    if (dir && dir.trim()) {
      resolvedDir = path.resolve(dir);
      if (!fs.existsSync(resolvedDir)) {
        throw new Error(`目录不存在: ${resolvedDir}`);
      }
    } else {
      const homeDir = require('os').homedir();
      resolvedDir = path.join(homeDir, 'projects', safeName);
      if (!fs.existsSync(resolvedDir)) {
        fs.mkdirSync(resolvedDir, { recursive: true });
      }
    }

    const project = {
      projectName: safeName,
      rootDirName: path.basename(resolvedDir),
      rootDir: resolvedDir,
      lastModified: new Date().toISOString(),
      visible: true,
      status: 'stopped',
      creationMethod: 'new',
      modules: [{ name: '默认', type: 'server', dir: '.' }]
    };

    return configService.add(project);
  },

  async create({ name, scaffold }) {
    if (!name || !name.trim()) {
      throw new Error('项目名称不能为空');
    }
    const safeName = name.trim().replace(/[^a-zA-Z0-9一-鿿㐀-䶿_\-\s]/g, '_').replace(/\s+/g, '_');
    const existing = configService.getByName(safeName);
    if (existing) {
      throw new Error(`项目名称 "${safeName}" 已存在`);
    }

    const rootDir = path.join(PROJECTS_DIR, safeName);
    if (fs.existsSync(rootDir)) {
      throw new Error(`项目目录已存在: ${rootDir}`);
    }

    const scaffolds = scaffoldService.getList();
    const scaffoldName = scaffold || (scaffolds.length > 0 ? scaffolds[0].name : null);
    if (!scaffoldName) {
      throw new Error('没有可用的手脚架');
    }

    scaffoldService.generate(rootDir, scaffoldName, safeName, name);
    gitService.init(rootDir);

    const detection = languageDetector.detectSingleDir(rootDir);
    const detectedCmd = detection.fullCommand || detection.startCommand || '';
    if (detectedCmd) {
      processService.writeStartupScript(rootDir, detectedCmd);
    }

    const project = {
      projectName: safeName,
      rootDirName: safeName,
      rootDir,
      lastModified: new Date().toISOString(),
      visible: true,
      status: 'stopped',
      creationMethod: 'scaffold',
      modules: [{
        name: '默认',
        type: detection.isFrontend ? 'frontend' : 'server',
        dir: '.',
        port: detection.port || 0
      }]
    };

    return configService.add(project);
  },

  // ========== 模块管理 ==========

  async addModule(index, { name, type, dir, startupScript, port }) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    if (!dir || !dir.trim()) throw new Error('目录不能为空');
    if (type !== 'frontend' && type !== 'server') throw new Error('type 必须是 frontend 或 server');

    let normalizedDir = dir.trim().replace(/\\/g, '/').replace(/\/+$/, '');
    if (normalizedDir === '.' || normalizedDir === './') {
      normalizedDir = './';
    } else if (!normalizedDir.startsWith('./') && !normalizedDir.startsWith('/')) {
      normalizedDir = './' + normalizedDir;
    }

    const resolvedDir = path.resolve(project.rootDir, normalizedDir);
    if (!fs.existsSync(resolvedDir)) {
      fs.mkdirSync(resolvedDir, { recursive: true });
    }

    if (startupScript) {
      processService.writeStartupScriptContent(resolvedDir, startupScript);
    } else {
      const detection = languageDetector.detectSingleDir(resolvedDir);
      const detectedCmd = detection.fullCommand || detection.startCommand || '';
      if (detectedCmd) {
        processService.writeStartupScript(resolvedDir, detectedCmd);
      }
    }

    const newModule = {
      name: name || `模块${(project.modules || []).length + 1}`,
      type,
      dir: normalizedDir,
      port: port || 0
    };

    const modules = [...(project.modules || []), newModule];
    return configService.update(index, { modules });
  },

  removeModule(index, moduleIndex) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    if (!project.modules || !project.modules[moduleIndex]) throw new Error('模块不存在');

    processService.stop(index, moduleIndex);

    const modules = [...project.modules];
    modules.splice(moduleIndex, 1);
    return configService.update(index, { modules });
  },

  updateModule(index, moduleIndex, patch) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    if (!project.modules || !project.modules[moduleIndex]) throw new Error('模块不存在');

    const modules = [...project.modules];
    modules[moduleIndex] = { ...modules[moduleIndex], ...patch };

    const result = configService.update(index, { modules });

    const updatedProject = configService.getByIndex(index);
    const mod = updatedProject.modules[moduleIndex];
    const moduleDir = path.resolve(updatedProject.rootDir, mod.dir);

    if (fs.existsSync(moduleDir) && patch.startupScript !== undefined) {
      processService.writeStartupScriptContent(moduleDir, patch.startupScript);
    }

    return result;
  },

  // ========== 启动/停止/同步 ==========

  async start(index, target) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');

    if (!target || target === 'all') {
      const result = await processService.start(index, project);
      return result.results || result;
    }

    const moduleIndex = parseInt(target);
    if (isNaN(moduleIndex)) throw new Error('target 必须是模块索引或 "all"');
    return await processService.startModule(index, project, moduleIndex);
  },

  async stop(index, target) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');

    if (!target || target === 'all') {
      await processService.stopAll(index);
    } else {
      const moduleIndex = parseInt(target);
      if (isNaN(moduleIndex)) throw new Error('target 必须是模块索引或 "all"');
      await processService.stop(index, moduleIndex);
    }
    return { success: true };
  },

  async syncStatus(index) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');

    await processService.syncAllStatus(index);
    return this._enrichProject(configService.getByIndex(index));
  },

  // ========== 其他操作 ==========

  updateGitConfig(index, { url, username, password }) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    const patch = {};
    if (url !== undefined) patch.gitUrl = url;
    if (username !== undefined) patch.gitUsername = username;
    if (password !== undefined) patch.gitPassword = password;
    return configService.update(index, patch);
  },

  async pullLatest(index) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    if (!project.gitUrl) throw new Error('该项目没有配置Git仓库地址');
    try {
      const result = gitService.pull(project.rootDir, {
        username: project.gitUsername,
        password: project.gitPassword
      });
      return { success: true, message: result.message };
    } catch (err) {
      throw new Error(`拉取代码失败: ${err.message}`);
    }
  },

  rename(index, newName) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    const safeName = newName.trim().replace(/[^a-zA-Z0-9一-鿿㐀-䶿_\-\s]/g, '_').replace(/\s+/g, '_');
    const newRootDir = path.join(PROJECTS_DIR, safeName);
    if (project.rootDir !== newRootDir && fs.existsSync(project.rootDir)) {
      fs.renameSync(project.rootDir, newRootDir);
    }
    return configService.update(index, {
      projectName: safeName,
      rootDirName: safeName,
      rootDir: newRootDir
    });
  },

  async delete(index) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    try {
      await processService.stopAll(index);
    } catch {}
    return configService.remove(index);
  },

  updateVisible(index, visible) {
    const project = configService.getByIndex(index);
    if (!project) throw new Error('项目不存在');
    return configService.update(index, { visible: !!visible });
  },

  // ========== 内部方法 ==========

  async _enrichProject(project) {
    if (!project.gitUrl && gitService.isGitRepo(project.rootDir)) {
      const remoteUrl = gitService.getRemoteUrl(project.rootDir);
      if (remoteUrl) {
        project.gitUrl = remoteUrl;
        configService.update(project.id, { gitUrl: remoteUrl });
      }
    }

    if (project.modules) {
      let anyRunning = false;
      for (let i = 0; i < project.modules.length; i++) {
        const mod = project.modules[i];
        const dir = path.resolve(project.rootDir, mod.dir);

        if (!fs.existsSync(dir)) {
          fs.mkdirSync(dir, { recursive: true });
        }

        let scriptContent = processService.readStartupScript(dir);

        const proInfo = processService.readProInfo(dir) || {};

        let realStatus = 'stopped';
        let realPid = proInfo.pid || -1;
        let realPorts = proInfo.ports || [];

        if (realPid > 0 && processService.isProcessAlive(realPid)) {
          realStatus = 'running';
          realPorts = await processService._getPortsByPid(realPid);
          if (realPorts.length === 0 && mod.port > 0 && await processService.isPortListening(mod.port)) {
            realPorts = [mod.port];
          }
          if (realPorts.length === 0) realPorts = proInfo.ports || [];
        } else {
          realPid = -1;
        }

        const logLinks = realStatus === 'running' ? processService._extractLinksFromLog(dir) : [];
        const existingLinks = proInfo.links || [];
        const mergedLinks = processService._dedupLinks([...existingLinks, ...logLinks]);

        if (realStatus !== (proInfo.status || 'stopped') || realPid !== (proInfo.pid || -1)) {
          processService.writeProInfo(dir, {
            status: realStatus,
            pid: realPid,
            ports: realPorts,
            port: realPorts.length > 0 ? realPorts[0] : (proInfo.port || 0),
            links: mergedLinks
          });
        }

        if (realStatus === 'running') anyRunning = true;

        project.modules[i] = {
          ...mod,
          ...proInfo,
          status: realStatus,
          pid: realPid,
          ports: realPorts,
          startupScript: scriptContent
        };
      }
      project.isRunning = anyRunning;
    } else {
      project.isRunning = false;
    }
    return project;
  }
};

module.exports = projectService;
