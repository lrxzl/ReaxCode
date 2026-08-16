const { spawn, exec } = require('child_process');
const net = require('net');
const treeKill = require('tree-kill');
const path = require('path');
const fs = require('fs');
const EventEmitter = require('events');
const { TERMUX_HOME } = require('../constants');


const processes = new Map();
const portScanners = new Map();
const isWindows = process.platform === 'win32';
const configService = require('../configService');
const PROJECTS_DIR = configService.PROJECTS_DIR;

const statusBus = new EventEmitter();
statusBus.setMaxListeners(50);

const processService = {
  // ========== pro-info.json 读写 ==========

  _getProInfoPath(projectDir) {
    return path.join(projectDir, 'pro-info.json');
  },

  readProInfo(projectDir) {
    const infoPath = this._getProInfoPath(projectDir);
    if (!fs.existsSync(infoPath)) return null;
    try {
      return JSON.parse(fs.readFileSync(infoPath, 'utf-8'));
    } catch {
      return null;
    }
  },

  writeProInfo(projectDir, info) {
    const infoPath = this._getProInfoPath(projectDir);
    const existing = this.readProInfo(projectDir) || {};
    const merged = { ...existing, ...info };
    fs.writeFileSync(infoPath, JSON.stringify(merged, null, 2), 'utf-8');
    return merged;
  },

  // ========== 启动脚本 ==========

  _getStartupScriptPath(projectDir) {
    return path.join(projectDir, isWindows ? 'startup.bat' : 'startup.sh');
  },

  _getLogFilePath(projectDir) {
    return path.join(projectDir, isWindows ? 'startup.bat.log' : 'startup.sh.log');
  },

  // 极简脚本生成：只负责 cd 目录和执行命令，不干预 PATH 和 NODE_PATH
  writeStartupScript(projectDir, devCommand) {
    const scriptPath = this._getStartupScriptPath(projectDir);
    if (isWindows) {
      const content = `@echo off\r\nchcp 65001 >nul\r\ncd /d "${projectDir}"\r\n${devCommand}\r\n`;
      fs.writeFileSync(scriptPath, content, 'utf-8');
    } else {
      const content = `#!/bin/bash\ncd "${projectDir}"\n${devCommand}\n`;
      fs.writeFileSync(scriptPath, content, 'utf-8');
    }
    return scriptPath;
  },

  readStartupScript(projectDir) {
    const scriptPath = this._getStartupScriptPath(projectDir);
    if (!fs.existsSync(scriptPath)) return null;
    try {
      return fs.readFileSync(scriptPath, 'utf-8');
    } catch {
      return null;
    }
  },

  getDevCommandFromScript(projectDir) {
    const scriptPath = this._getStartupScriptPath(projectDir);
    if (!fs.existsSync(scriptPath)) return null;
    try {
      const content = fs.readFileSync(scriptPath, 'utf-8');
      if (isWindows) {
        const lines = content.split(/\r?\n/).filter(l =>
            l.trim() && !l.startsWith('@') && !l.startsWith('chcp') && !l.startsWith('cd ')
        );
        return lines.length > 0 ? lines[lines.length - 1].trim() : null;
      } else {
        const lines = content.split('\n').filter(l =>
            l.trim() && !l.startsWith('#!') && !l.startsWith('cd ')
        );
        return lines.length > 0 ? lines[lines.length - 1].trim() : null;
      }
    } catch {
      return null;
    }
  },

  writeStartupScriptContent(projectDir, content) {
    const scriptPath = this._getStartupScriptPath(projectDir);
    fs.writeFileSync(scriptPath, content, 'utf-8');
    return scriptPath;
  },

  // ========== 启动 ==========

  async start(projectIndex, project) {
    const results = {};
    if (!project.modules || project.modules.length === 0) {
      return { results };
    }

    for (let i = 0; i < project.modules.length; i++) {
      const mod = project.modules[i];
      const dir = path.resolve(project.rootDir, mod.dir);
      try {
        results[i] = await this._startModule(projectIndex, i, dir, mod);
      } catch (err) {
        results[i] = { error: err.message, logOutput: this.getOutput(projectIndex, i) };
      }
    }

    return { results };
  },

  async startModule(projectIndex, project, moduleIndex) {
    const mod = project.modules[moduleIndex];
    if (!mod) throw new Error('模块不存在');
    const dir = path.resolve(project.rootDir, mod.dir);
    try {
      return await this._startModule(projectIndex, moduleIndex, dir, mod);
    } catch (err) {
      return { error: err.message, logOutput: this.getOutput(projectIndex, moduleIndex) };
    }
  },

  async _startModule(projectIndex, moduleIndex, moduleDir, moduleConfig) {
    const key = `${projectIndex}_${moduleIndex}`;
    if (processes.has(key)) {
      throw new Error('该模块已在运行中');
    }

    const existingScript = this.readStartupScript(moduleDir);
    if (!existingScript) {
      throw new Error('未配置启动脚本，请先在模块设置中配置启动命令');
    }

    if (!fs.existsSync(moduleDir)) {
      throw new Error(`目录不存在: ${moduleDir}`);
    }

    const configuredPort = moduleConfig.port || 0;

    this.writeProInfo(moduleDir, {
      status: 'starting',
      pid: -1,
      port: configuredPort,
      ports: []
    });

    const logFile = this._getLogFilePath(moduleDir);
    try { fs.writeFileSync(logFile, '', 'utf-8'); } catch {}
    const logStream = fs.createWriteStream(logFile, { flags: 'a' });

    const scriptPath = this._getStartupScriptPath(moduleDir);

    const env = { ...process.env };
    if (configuredPort > 0) {
      env.PORT = String(configuredPort);
    }

    let proc;
    if (isWindows) {
      proc = spawn('cmd', ['/c', scriptPath], {
        cwd: moduleDir,
        env,
        shell: true,
        stdio: ['pipe', 'pipe', 'pipe']
      });
    } else {
      // 通用环境加载：兼容 Termux 和标准 Linux
      const homeDir = env.HOME || '/root';
      const wrapCmd = `[ -f "${homeDir}/.bashrc" ] && source "${homeDir}/.bashrc" 2>/dev/null; [ -f "${homeDir}/.profile" ] && source "${homeDir}/.profile" 2>/dev/null; exec bash "${scriptPath}"`;
      proc = spawn('bash', ['-c', wrapCmd], {
        cwd: moduleDir,
        env,
        stdio: ['pipe', 'pipe', 'pipe']
      });
    }

    const entry = {
      proc,
      pid: proc.pid,
      startTime: Date.now(),
      output: '',
      logStream,
      moduleDir
    };
    processes.set(key, entry);

    let exited = false;
    let exitCode = null;

    proc.on('exit', (code) => {
      exited = true;
      exitCode = code;
      if (entry.logStream) entry.logStream.end();
      processes.delete(key);
      this._stopPortScanner(key);
      this.writeProInfo(moduleDir, { status: 'stopped', pid: -1, ports: [], links: [] });
      statusBus.emit('change', { projectIndex, moduleIndex, status: 'stopped' });
    });

    const stripAnsi = (s) => s.replace(/\x1b\[[0-9;]*m/g, '');

    proc.stdout.on('data', (data) => {
      const text = stripAnsi(data.toString());
      entry.output += text;
      logStream.write(text);
    });

    proc.stderr.on('data', (data) => {
      const text = stripAnsi(data.toString());
      entry.output += text;
      logStream.write(text);
    });

    proc.on('error', (err) => {
      entry.output += `\n[进程错误] ${err.message}\n`;
      logStream.write(`\n[进程错误] ${err.message}\n`);
    });

    await this._sleep(3000);

    if (exited) {
      const errorMsg = entry.output.slice(-500) || '进程已退出';
      throw new Error(`启动失败 (exit code: ${exitCode}): ${errorMsg}`);
    }

    let ports = await this._getPortsByPid(proc.pid);
    if (ports.length === 0 && configuredPort > 0 && await this.isPortListening(configuredPort)) {
      ports = [configuredPort];
    }
    const logLinks = this._extractLinksFromLog(moduleDir);
    this.writeProInfo(moduleDir, {
      status: 'running',
      pid: proc.pid,
      port: ports.length > 0 ? ports[0] : configuredPort,
      ports,
      links: this._dedupLinks(logLinks)
    });
    statusBus.emit('change', { projectIndex, moduleIndex, status: 'running' });

    this._startPortScanner(projectIndex, moduleIndex, moduleDir, proc.pid, configuredPort);

    return {
      pid: proc.pid,
      port: ports.length > 0 ? ports[0] : configuredPort,
      ports,
      output: entry.output.slice(-500)
    };
  },

  // ========== 停止 ==========

  async stop(projectIndex, moduleIndex) {
    const project = configService.getByIndex(projectIndex);
    const mod = project?.modules?.[moduleIndex];
    if (!mod) return { success: true, message: '模块不存在' };

    const key = `${projectIndex}_${moduleIndex}`;
    const moduleDir = path.resolve(project.rootDir, mod.dir);

    const cleanUp = () => {
      processes.delete(key);
      this._stopPortScanner(key);
      this.writeProInfo(moduleDir, { status: 'stopped', pid: -1, ports: [], links: [] });
      statusBus.emit('change', { projectIndex, moduleIndex, status: 'stopped' });
    };

    if (!processes.has(key)) {
      const proInfo = this.readProInfo(moduleDir);
      if (proInfo && proInfo.pid > 0) {
        await this._killProcessTree(proInfo.pid);
      }
      cleanUp();
      return { success: true, message: '已停止' };
    }

    const entry = processes.get(key);
    const pid = entry.pid;

    await this._killProcessTree(pid);
    cleanUp();
    return { success: true, message: '已停止' };
  },

  async stopAll(projectIndex) {
    const project = configService.getByIndex(projectIndex);
    const results = {};
    if (project?.modules) {
      for (let i = 0; i < project.modules.length; i++) {
        results[i] = await this.stop(projectIndex, i);
      }
    }
    return results;
  },

  stopAllProcesses() {
    for (const [key, entry] of processes) {
      try {
        treeKill(entry.pid, 'SIGTERM');
      } catch {}
    }
    processes.clear();
    for (const [key, timer] of portScanners) {
      clearTimeout(timer);
    }
    portScanners.clear();
  },

  async _killProcessTree(pid) {
    if (!pid || pid <= 0) return;

    await new Promise((resolve) => {
      treeKill(pid, 'SIGTERM', () => resolve());
    });

    for (let i = 0; i < 6; i++) {
      await this._sleep(500);
      if (!this.isProcessAlive(pid)) return;
    }

    await this._killByPid(pid);
    await this._sleep(500);
  },

  _killByPid(pid) {
    return new Promise((resolve) => {
      if (isWindows) {
        exec(`taskkill /PID ${pid} /T /F`, { timeout: 5000 }, () => {
          resolve({ success: true });
        });
      } else {
        exec(`pkill -9 -P ${pid} 2>/dev/null; kill -9 ${pid} 2>/dev/null`, { timeout: 5000 }, () => {
          resolve({ success: true });
        });
      }
    });
  },

  // ========== 端口检测 ==========

  async _getPortsByPid(pid) {
    if (!pid || pid <= 0) return [];
    if (isWindows) return this._getPortsByPidWindows(pid);
    return this._getPortsByPidLinux(pid);
  },

  _getPortsByPidWindows(pid) {
    return new Promise((resolve) => {
      const cmd = `netstat -ano | findstr "${pid}" | findstr "LISTENING"`;
      exec(cmd, { encoding: 'utf-8', timeout: 5000 }, (err, stdout) => {
        if (err || !stdout) return resolve([]);
        const ports = [];
        const lines = stdout.split('\n');
        for (const line of lines) {
          const m = line.match(/:(\d{2,5})\s+.*LISTENING\s+(\d+)/);
          if (m && parseInt(m[2]) === pid) ports.push(parseInt(m[1], 10));
        }
        return resolve([...new Set(ports)]);
      });
    });
  },

  async _getPortsByPidLinux(pid) {
    const procPorts = this._getPortsFromProcNet(pid);
    if (procPorts.length > 0) return procPorts;

    const tryCmd = (cmd) => new Promise((resolve) => {
      exec(cmd, { encoding: 'utf-8', timeout: 5000 }, (err, stdout) => {
        if (err || !stdout) return resolve([]);
        const ports = [];
        for (const line of stdout.split('\n')) {
          const m = line.match(/:(\d{2,5})\s/);
          if (m) ports.push(parseInt(m[1], 10));
        }
        return resolve([...new Set(ports)]);
      });
    });

    let ports = await tryCmd(`ss -tlnp 2>/dev/null | grep "pid=${pid}"`);
    if (ports.length === 0) {
      ports = await tryCmd(`netstat -tlnp 2>/dev/null | grep "${pid}"`);
    }
    return ports;
  },

  _getPortsFromProcNet(pid) {
    const ports = new Set();
    const socketInodes = new Set();
    try {
      const fdDir = `/proc/${pid}/fd`;
      if (fs.existsSync(fdDir)) {
        const fds = fs.readdirSync(fdDir);
        for (const fd of fds) {
          try {
            const link = fs.readlinkSync(`${fdDir}/${fd}`);
            const m = link.match(/socket:\[(\d+)\]/);
            if (m) socketInodes.add(m[1]);
          } catch {}
        }
      }
    } catch {}

    for (const tcpFile of ['/proc/net/tcp', '/proc/net/tcp6']) {
      try {
        const content = fs.readFileSync(tcpFile, 'utf-8');
        const lines = content.split('\n').slice(1);
        for (const line of lines) {
          const parts = line.trim().split(/\s+/);
          if (parts.length < 11) continue;
          const inode = parts[10];
          const isMatch = socketInodes.size > 0 ? socketInodes.has(inode) : parseInt(parts[7]) === pid;
          if (isMatch) {
            const localAddr = parts[1];
            const portHex = localAddr.split(':')[1];
            if (portHex) {
              const port = parseInt(portHex, 16);
              if (port > 0 && port < 65536) ports.add(port);
            }
          }
        }
      } catch {}
    }
    return [...ports];
  },

  async checkPortAlive(port) {
    return this.isPortListening(port);
  },

  isPortInUse(port) {
    return new Promise((resolve) => {
      const server = net.createServer();
      server.once('error', (err) => {
        resolve(err.code === 'EADDRINUSE');
      });
      server.once('listening', () => {
        server.close();
        resolve(false);
      });
      server.listen(port, '0.0.0.0');
    });
  },

  isPortListening(port) {
    return new Promise((resolve) => {
      if (!port || port <= 0) return resolve(false);
      const socket = new net.Socket();
      socket.setTimeout(2000);
      socket.once('connect', () => {
        socket.destroy();
        resolve(true);
      });
      socket.once('timeout', () => {
        socket.destroy();
        resolve(false);
      });
      socket.once('error', () => {
        socket.destroy();
        resolve(false);
      });
      socket.connect(port, '127.0.0.1');
    });
  },

  async findAvailablePort(startPort) {
    let port = startPort;
    for (let i = 0; i < 100; i++) {
      const inUse = await this.isPortInUse(port);
      if (!inUse) return port;
      port++;
    }
    throw new Error(`无法找到可用端口（从 ${startPort} 开始）`);
  },

  // ========== 状态同步 ==========

  async syncModuleStatus(projectIndex, moduleIndex) {
    const project = configService.getByIndex(projectIndex);
    if (!project?.modules?.[moduleIndex]) return null;

    const mod = project.modules[moduleIndex];
    const moduleDir = path.resolve(project.rootDir, mod.dir);
    const proInfo = this.readProInfo(moduleDir) || {};

    let pid = proInfo.pid || -1;
    let ports = [];
    let status = 'stopped';

    if (pid > 0 && this.isProcessAlive(pid)) {
      status = 'running';
      ports = await this._getPortsByPid(pid);
      if (ports.length === 0 && mod.port > 0 && await this.isPortListening(mod.port)) {
        ports = [mod.port];
      }
    } else {
      pid = -1;
    }

    this.writeProInfo(moduleDir, {
      status,
      pid,
      ports,
      port: ports.length > 0 ? ports[0] : (proInfo.port || 0)
    });

    return {
      status,
      pid,
      ports,
      port: ports.length > 0 ? ports[0] : (proInfo.port || 0)
    };
  },

  async syncAllStatus(projectIndex) {
    const project = configService.getByIndex(projectIndex);
    if (!project?.modules) return [];

    const results = [];
    for (let i = 0; i < project.modules.length; i++) {
      results[i] = await this.syncModuleStatus(projectIndex, i);
    }
    return results;
  },

  // ========== 日志 ==========

  getOutput(projectIndex, moduleIndex) {
    const key = `${projectIndex}_${moduleIndex}`;
    const entry = processes.get(key);
    if (entry) return entry.output;

    const project = configService.getByIndex(projectIndex);
    if (!project?.modules?.[moduleIndex]) return '';
    const moduleDir = path.resolve(project.rootDir, project.modules[moduleIndex].dir);
    const logFile = this._getLogFilePath(moduleDir);
    if (!fs.existsSync(logFile)) return '';
    try {
      return fs.readFileSync(logFile, 'utf-8');
    } catch {
      return '';
    }
  },

  clearAllLogs() {
    const projects = configService.getAll();
    for (const project of projects) {
      if (project.modules) {
        for (const mod of project.modules) {
          const logFile = this._getLogFilePath(path.resolve(project.rootDir, mod.dir));
          try { fs.writeFileSync(logFile, '', 'utf-8'); } catch {}
        }
      }
    }
  },

  // ========== 工具方法 ==========

  isProcessAlive(pid) {
    if (!pid || pid < 0) return false;
    try {
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  },

  _sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  },

  _extractLinksFromLog(moduleDir) {
    const logFile = this._getLogFilePath(moduleDir);
    if (!fs.existsSync(logFile)) return [];
    try {
      const content = fs.readFileSync(logFile, 'utf-8');
      const urlRe = /https?:\/\/[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+/g;
      const urls = content.match(urlRe) || [];
      return urls.map(u => u.replace(/[.,;:!?)\]}>]+$/, '')).filter(u => u.length < 500);
    } catch {
      return [];
    }
  },

  _dedupLinks(links) {
    const seen = new Set();
    const result = [];
    for (const link of links) {
      try {
        const u = new URL(link);
        const key = `${u.hostname}:${u.port || (u.protocol === 'https:' ? '443' : '80')}`;
        if (!seen.has(key)) {
          seen.add(key);
          result.push(link);
        }
      } catch {
        if (!seen.has(link)) {
          seen.add(link);
          result.push(link);
        }
      }
    }
    return result;
  },

  _startPortScanner(projectIndex, moduleIndex, moduleDir, pid, configuredPort) {
    const key = `${projectIndex}_${moduleIndex}`;
    this._stopPortScanner(key);

    let delay = 3000;

    const scan = async () => {
      if (!processes.has(key) || !this.isProcessAlive(pid)) {
        this._stopPortScanner(key);
        return;
      }
      try {
        let ports = await this._getPortsByPid(pid);
        if (ports.length === 0 && configuredPort > 0 && await this.isPortListening(configuredPort)) {
          ports = [configuredPort];
        }

        const logLinks = this._extractLinksFromLog(moduleDir);

        const proInfo = this.readProInfo(moduleDir) || {};
        const existingPorts = proInfo.ports || [];
        const existingLinks = proInfo.links || [];

        const mergedPorts = [...new Set([...existingPorts, ...ports])];
        const mergedLinks = this._dedupLinks([...existingLinks, ...logLinks]);

        if (mergedPorts.length > existingPorts.length || mergedLinks.length > existingLinks.length) {
          this.writeProInfo(moduleDir, {
            status: 'running',
            pid,
            port: mergedPorts[0] || configuredPort,
            ports: mergedPorts,
            links: mergedLinks
          });
          statusBus.emit('change', { projectIndex, moduleIndex, status: 'running' });
        }
      } catch {}
      delay += 100;
      const timer = setTimeout(scan, delay);
      portScanners.set(key, timer);
    };

    const timer = setTimeout(scan, delay);
    portScanners.set(key, timer);
  },

  _stopPortScanner(key) {
    if (portScanners.has(key)) {
      clearTimeout(portScanners.get(key));
      portScanners.delete(key);
    }
  },

  // ========== 依赖安装 ==========

  // 回归最朴素的本地安装，避免软链接导致的各种奇葩问题
  async install(dirPath) {
    const homeDir = process.env.HOME || TERMUX_HOME;
    const cmd = isWindows ? 'cmd' : 'bash';
    const args = isWindows
        ? ['/c', 'npm', 'install']
        : ['-c', `[ -f "${homeDir}/.bashrc" ] && source "${homeDir}/.bashrc" 2>/dev/null; [ -f "${homeDir}/.profile" ] && source "${homeDir}/.profile" 2>/dev/null; npm install`];

    return new Promise((resolve, reject) => {
      const proc = spawn(cmd, args, {
        cwd: dirPath,
        stdio: ['pipe', 'pipe', 'pipe']
      });
      let output = '';
      proc.stdout.on('data', data => { output += data.toString(); });
      proc.stderr.on('data', data => { output += data.toString(); });
      proc.on('exit', (code) => {
        if (code === 0) {
          resolve({ success: true, output: output.slice(-500) });
        } else {
          reject(new Error(`npm install 失败 (code ${code}): ${output.slice(-300)}`));
        }
      });
      proc.on('error', reject);
    });
  }
};

processService.statusBus = statusBus;

module.exports = processService;
