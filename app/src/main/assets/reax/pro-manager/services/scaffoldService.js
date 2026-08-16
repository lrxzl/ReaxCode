const fs = require('fs');
const path = require('path');

const SCAFFOLD_DIR = path.join(__dirname, '..', 'pro-scaffold');
const SCAFFOLD_CONFIG = path.join(SCAFFOLD_DIR, 'scaffold.json');

const scaffoldService = {
  // 获取手脚架列表
  getList() {
    if (!fs.existsSync(SCAFFOLD_CONFIG)) {
      return [];
    }
    try {
      const data = fs.readFileSync(SCAFFOLD_CONFIG, 'utf-8');
      return JSON.parse(data);
    } catch {
      return [];
    }
  },

  // 生成项目（从手脚架复制）
  generate(targetDir, scaffoldName, projectName, projectTitle) {
    const scaffolds = this.getList();
    const scaffold = scaffolds.find(s => s.name === scaffoldName);
    if (!scaffold) {
      throw new Error(`手脚架不存在: ${scaffoldName}`);
    }

    const templateDir = path.join(SCAFFOLD_DIR, scaffold.templateDir);
    if (!fs.existsSync(templateDir)) {
      throw new Error(`手脚架模板目录不存在: ${templateDir}`);
    }

    const files = [];
    const errors = [];

    // 递归复制并替换变量
    this._copyWithReplace(templateDir, targetDir, {
      PROJECT_NAME: projectName || 'my-project',
      PROJECT_TITLE: projectTitle || projectName || 'My Project'
    }, files, errors);

    return {
      scaffold: scaffold.name,
      targetDir,
      files,
      errors
    };
  },

  // 内部方法：复制目录并替换变量
  _copyWithReplace(src, dest, vars, files, errors) {
    if (!fs.existsSync(dest)) {
      fs.mkdirSync(dest, { recursive: true });
    }

    const entries = fs.readdirSync(src, { withFileTypes: true });
    const skipDirs = ['node_modules', '.git', 'dist'];

    for (const entry of entries) {
      if (skipDirs.includes(entry.name)) continue;

      const srcPath = path.join(src, entry.name);
      const destPath = path.join(dest, entry.name);

      if (entry.isDirectory()) {
        this._copyWithReplace(srcPath, destPath, vars, files, errors);
      } else {
        try {
          // 检查是否是文本文件（需要替换变量）
          const shouldReplace = this._isTextFile(entry.name);
          if (shouldReplace) {
            let content = fs.readFileSync(srcPath, 'utf-8');
            // 替换所有变量
            for (const [key, value] of Object.entries(vars)) {
              content = content.replace(new RegExp(`\\{\\{${key}\\}\\}`, 'g'), value);
            }
            fs.writeFileSync(destPath, content, 'utf-8');
          } else {
            fs.copyFileSync(srcPath, destPath);
          }
          files.push(destPath);
        } catch (err) {
          errors.push({ file: destPath, error: err.message });
        }
      }
    }
  },

  // 判断是否是文本文件（需要变量替换）
  _isTextFile(filename) {
    const textExts = [
      '.js', '.ts', '.jsx', '.tsx', '.vue', '.json', '.md', '.txt',
      '.html', '.css', '.scss', '.less', '.yaml', '.yml', '.xml',
      '.sh', '.bat', '.py', '.java', '.sql', '.toml', '.ini',
      '.cfg', '.conf', '.env', '.gitignore', '.dockerignore'
    ];
    const ext = path.extname(filename).toLowerCase();
    // 无扩展名的文件（如 Makefile, Dockerfile）也视为文本
    if (!ext) return true;
    return textExts.includes(ext);
  }
};

module.exports = scaffoldService;
