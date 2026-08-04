const fs = require('fs');
const path = require('path');
const os = require('os');

const PROJECTS_DIR = path.join(os.homedir(), 'projects');
const CONFIG_PATH = path.join(PROJECTS_DIR, 'project-list.json');

if (!fs.existsSync(PROJECTS_DIR)) fs.mkdirSync(PROJECTS_DIR, { recursive: true });

function generateId() {
  return Date.now().toString(36) + Math.random().toString(36).substring(2, 8);
}

function readConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    fs.writeFileSync(CONFIG_PATH, '[]', 'utf-8');
    return [];
  }
  try {
    const data = fs.readFileSync(CONFIG_PATH, 'utf-8');
    const projects = JSON.parse(data);
    let changed = false;
    for (const p of projects) {
      if (!p.id) {
        p.id = generateId();
        changed = true;
      }
    }
    if (changed) writeConfig(projects);
    return projects;
  } catch {
    return [];
  }
}

function writeConfig(projects) {
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(projects, null, 2), 'utf-8');
}

const configService = {
  getAll() {
    return readConfig();
  },

  getByIndex(key) {
    const projects = readConfig();
    if (typeof key === 'string') {
      const index = projects.findIndex(p => p.id === key);
      if (index === -1) return null;
      return projects[index];
    }
    if (key < 0 || key >= projects.length) return null;
    return projects[key];
  },

  getByName(name) {
    const projects = readConfig();
    return projects.find(p => p.projectName === name) || null;
  },

  add(project) {
    const projects = readConfig();
    if (projects.some(p => p.projectName === project.projectName)) {
      throw new Error(`项目名称 "${project.projectName}" 已存在`);
    }
    project.id = generateId();
    projects.push(project);
    writeConfig(projects);
    return project;
  },

  update(key, patch) {
    const projects = readConfig();
    let index;
    if (typeof key === 'string') {
      index = projects.findIndex(p => p.id === key);
    } else {
      index = key;
    }
    if (index < 0 || index >= projects.length) {
      throw new Error(`项目 ${key} 不存在`);
    }
    if (patch.projectName && patch.projectName !== projects[index].projectName) {
      if (projects.some(p => p.projectName === patch.projectName)) {
        throw new Error(`项目名称 "${patch.projectName}" 已存在`);
      }
    }
    for (const [field, value] of Object.entries(patch)) {
      const keys = field.split('.');
      let obj = projects[index];
      let skip = false;
      for (let i = 0; i < keys.length - 1; i++) {
        if (obj[keys[i]] === null || obj[keys[i]] === undefined) {
          skip = true;
          break;
        }
        if (typeof obj[keys[i]] !== 'object') {
          skip = true;
          break;
        }
        obj = obj[keys[i]];
      }
      if (!skip) {
        obj[keys[keys.length - 1]] = value;
      }
    }
    projects[index].lastModified = new Date().toISOString();
    writeConfig(projects);
    return projects[index];
  },

  remove(key) {
    const projects = readConfig();
    let index;
    if (typeof key === 'string') {
      index = projects.findIndex(p => p.id === key);
    } else {
      index = key;
    }
    if (index < 0 || index >= projects.length) {
      throw new Error(`项目 ${key} 不存在`);
    }
    const removed = projects.splice(index, 1)[0];
    writeConfig(projects);
    return removed;
  },

  count() {
    return readConfig().length;
  },

  getUsedPorts() {
    const projects = readConfig();
    const ports = [];
    for (const p of projects) {
      if (p.port > 0) ports.push(p.port);
    }
    return ports;
  }
};

configService.PROJECTS_DIR = PROJECTS_DIR;
configService.CONFIG_PATH = CONFIG_PATH;

module.exports = configService;
