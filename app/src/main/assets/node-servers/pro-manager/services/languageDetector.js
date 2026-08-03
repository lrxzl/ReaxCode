const fs = require('fs');
const path = require('path');

const SKIP_DIRS = ['node_modules', '.git', 'dist', 'build', 'target', '.next', 'out', '__pycache__', '.venv', 'venv'];

// 语言检测规则：标志性文件 → 语言（去重，去掉 src/main/java 复合规则）
const LANGUAGE_RULES = [
  { files: ['pom.xml'], language: 'java', buildTool: 'maven' },
  { files: ['AndroidManifest.xml'], language: 'android' },
  { files: ['build.gradle', 'build.gradle.kts'], language: 'java', buildTool: 'gradle' },
  { files: ['go.mod'], language: 'go' },
  { files: ['Cargo.toml'], language: 'rust' },
  { files: ['Gemfile'], language: 'ruby' },
  { files: ['composer.json'], language: 'php' },
  { files: ['requirements.txt'], language: 'python' },
  { files: ['pyproject.toml'], language: 'python' },
  { files: ['setup.py'], language: 'python' },
  { files: ['Pipfile'], language: 'python' },
];

// 前端框架标识依赖
const FRONTEND_FRAMEWORKS = ['react', 'vue', 'angular', 'svelte', 'nuxt', 'next', '@vue/cli-service', 'vite', 'webpack'];

// 启动命令模板
const START_COMMANDS = {
  nodejs: {
    detect: (dir) => {
      const pkgPath = path.join(dir, 'package.json');
      if (!fs.existsSync(pkgPath)) return null;
      try {
        const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
        if (pkg.scripts) {
          if (pkg.scripts.dev) return 'npm run dev';
          if (pkg.scripts.start) return 'npm start';
          if (pkg.scripts.serve) return 'npm run serve';
        }
        if (pkg.main) return `node ${pkg.main}`;
        return 'node index.js';
      } catch { return 'npm run dev'; }
    },
    defaultPort: 3000,
    installCmd: 'npm install'
  },
  python: {
    detect: (dir) => {
      const candidates = ['main.py', 'app.py', 'manage.py', 'server.py', 'run.py', 'wsgi.py'];
      for (const f of candidates) {
        if (fs.existsSync(path.join(dir, f))) {
          if (f === 'manage.py') return 'python manage.py runserver';
          if (f === 'wsgi.py') return 'uvicorn wsgi:app';
          return `python ${f}`;
        }
      }
      const pyprojectPath = path.join(dir, 'pyproject.toml');
      if (fs.existsSync(pyprojectPath)) {
        try {
          const content = fs.readFileSync(pyprojectPath, 'utf-8');
          if (content.includes('flask') || content.includes('Flask')) return 'flask run';
          if (content.includes('uvicorn') || content.includes('fastapi') || content.includes('FastAPI')) return 'uvicorn main:app --reload';
        } catch {}
      }
      const reqPath = path.join(dir, 'requirements.txt');
      if (fs.existsSync(reqPath)) {
        try {
          const content = fs.readFileSync(reqPath, 'utf-8').toLowerCase();
          if (content.includes('django')) return 'python manage.py runserver';
          if (content.includes('flask')) return 'flask run';
          if (content.includes('uvicorn') || content.includes('fastapi')) return 'uvicorn main:app --reload';
        } catch {}
      }
      return 'python main.py';
    },
    defaultPort: 8000,
    installCmd: 'pip install -r requirements.txt'
  },
  java: {
    detect: (dir, meta) => {
      if (meta?.buildTool === 'gradle' || fs.existsSync(path.join(dir, 'gradlew'))) {
        return './gradlew bootRun';
      }
      if (fs.existsSync(path.join(dir, 'mvnw'))) return './mvnw spring-boot:run';
      return 'mvn spring-boot:run';
    },
    defaultPort: 8080,
    installCmd: 'mvn install'
  },
  go: {
    detect: (dir) => {
      if (fs.existsSync(path.join(dir, 'main.go'))) return 'go run .';
      const cmdDir = path.join(dir, 'cmd');
      if (fs.existsSync(cmdDir)) {
        const entries = fs.readdirSync(cmdDir, { withFileTypes: true });
        const mainDir = entries.find(e => e.isDirectory() && e.name === 'main');
        if (mainDir) return 'go run ./cmd/main';
        if (entries.length > 0) return `go run ./cmd/${entries[0].name}`;
      }
      return 'go run .';
    },
    defaultPort: 8080,
    installCmd: 'go mod download'
  },
  rust: {
    detect: () => 'cargo run',
    defaultPort: 8080,
    installCmd: null
  },
  ruby: {
    detect: (dir) => {
      if (fs.existsSync(path.join(dir, 'bin/rails'))) return 'bundle exec rails s';
      if (fs.existsSync(path.join(dir, 'Gemfile'))) return 'bundle exec ruby main.rb';
      return 'ruby main.rb';
    },
    defaultPort: 3000,
    installCmd: 'bundle install'
  },
  php: {
    detect: (dir) => {
      if (fs.existsSync(path.join(dir, 'artisan'))) return 'php artisan serve';
      return 'php -S localhost:8000';
    },
    defaultPort: 8000,
    installCmd: 'composer install'
  },
  android: {
    detect: (dir) => {
      if (fs.existsSync(path.join(dir, 'gradlew'))) return './gradlew installDebug';
      if (fs.existsSync(path.join(dir, 'gradlew.bat'))) return 'gradlew.bat installDebug';
      return './gradlew installDebug';
    },
    defaultPort: 0,
    installCmd: null
  }
};

// 端口检测正则（多模式，覆盖常见日志格式）
const PORT_PATTERNS = [
  /https?:\/\/(?:localhost|0\.0\.0\.0|127\.0\.0\.1|\[::\]|[\d.]+):(\d{4,5})/i,
  /(?:listening|running|started|available)\s+(?:on|at)?\s*(?:port\s+)?(\d{4,5})/i,
  /(?:port|PORT)\s*[:=]\s*(\d{4,5})/,
  /--port\s+(\d{4,5})/,
  /-p\s+(\d{4,5})\b/,
];

const languageDetector = {
  // 检测项目语言和配置
  detectProject(rootDir) {
    if (!fs.existsSync(rootDir)) {
      return { language: 'unknown', type: 'unknown' };
    }

    const result = {
      language: 'unknown',
      type: 'unknown',
      startCommand: null,
      port: 3000,
      installCmd: null,
      frontendDir: null,
      backendDir: null,
      frontendLanguage: null,
      frontendCommand: null,
      frontendPort: null,
      configFiles: []
    };

    const rootFiles = this._listFiles(rootDir);
    const detectedLanguages = new Set();

    for (const rule of LANGUAGE_RULES) {
      if (rule.files.every(f => {
        return rootFiles.includes(f) || fs.existsSync(path.join(rootDir, f));
      })) {
        detectedLanguages.add(rule.language);
        result.configFiles.push(...rule.files.filter(f => rootFiles.includes(f) || fs.existsSync(path.join(rootDir, f))));
        if (rule.buildTool) result._meta = { ...result._meta, buildTool: rule.buildTool };
      }
    }

    if (rootFiles.includes('package.json')) {
      detectedLanguages.add('nodejs');
      result.configFiles.push('package.json');
    }

    // 前后端目录检测 —— 不再把 src 当前端目录（Java/Go/Python 都有 src）
    const hasFrontendDir = fs.existsSync(path.join(rootDir, 'frontend')) ||
        fs.existsSync(path.join(rootDir, 'client')) ||
        fs.existsSync(path.join(rootDir, 'web'));
    const hasBackendDir = fs.existsSync(path.join(rootDir, 'server')) ||
        fs.existsSync(path.join(rootDir, 'backend')) ||
        fs.existsSync(path.join(rootDir, 'api'));

    if (hasFrontendDir || hasBackendDir) {
      if (hasFrontendDir) {
        const fDir = this._findFrontendDir(rootDir);
        if (fDir) {
          result.frontendDir = path.relative(rootDir, fDir);
          const fDetection = this._detectSingleDir(fDir);
          result.frontendLanguage = fDetection.language;
          result.frontendCommand = fDetection.startCommand;
          result.frontendFullCommand = fDetection.fullCommand;
          result.frontendPort = fDetection.port;
        }
      }
      if (hasBackendDir) {
        const bDir = this._findBackendDir(rootDir);
        if (bDir) {
          result.backendDir = path.relative(rootDir, bDir);
          const bDetection = this._detectSingleDir(bDir);
          result.language = bDetection.language;
          result.startCommand = bDetection.startCommand;
          result.fullCommand = bDetection.fullCommand;
          result.port = bDetection.port;
          result.installCmd = bDetection.installCmd;
        }
      }
      result.type = (hasFrontendDir && hasBackendDir) ? 'fullstack' :
          hasFrontendDir ? 'frontend' : 'backend';
    } else {
      const rootDetection = this._detectSingleDir(rootDir);
      result.language = rootDetection.language;
      result.startCommand = rootDetection.startCommand;
      result.fullCommand = rootDetection.fullCommand;
      result.port = rootDetection.port;
      result.installCmd = rootDetection.installCmd;

      if (rootDetection.language === 'nodejs') {
        result.type = this._isFrontendProject(rootDir, 'nodejs') ? 'frontend' : 'backend';
      } else {
        result.type = 'backend';
      }
    }

    if (detectedLanguages.size > 0 && result.language === 'unknown') {
      result.language = [...detectedLanguages][0];
    }

    return result;
  },

  // 从配置文件中扫描端口
  _detectPort(dir, language) {
    if (language === 'nodejs') {
      const pkg = this._readPackageJson(dir);
      if (pkg && pkg.scripts) {
        const allScripts = Object.values(pkg.scripts).join(' ');
        for (const pattern of PORT_PATTERNS) {
          const m = allScripts.match(pattern);
          if (m) return parseInt(m[1], 10);
        }
      }
    }

    // .env 文件中的 PORT
    const envFiles = ['.env', '.env.local', '.env.development', '.env.production'];
    for (const ef of envFiles) {
      try {
        const content = fs.readFileSync(path.join(dir, ef), 'utf-8');
        const m = content.match(/^PORT\s*=\s*(\d+)/m);
        if (m) return parseInt(m[1], 10);
      } catch {}
    }

    // Java: application.properties / application.yml
    if (language === 'java') {
      try {
        const props = fs.readFileSync(path.join(dir, 'src/main/resources/application.properties'), 'utf-8');
        const m = props.match(/server\.port\s*=\s*(\d+)/);
        if (m) return parseInt(m[1], 10);
      } catch {}
      try {
        const yml = fs.readFileSync(path.join(dir, 'src/main/resources/application.yml'), 'utf-8');
        const m = yml.match(/server:\s*\n\s+port:\s*(\d+)/);
        if (m) return parseInt(m[1], 10);
      } catch {}
    }

    return null;
  },

  // 检测单个目录的语言
  _detectSingleDir(dir) {
    const files = this._listFiles(dir);
    const result = { language: 'unknown', startCommand: null, port: 3000, installCmd: null, fullCommand: null };

    const langOrder = ['android', 'java', 'nodejs', 'python', 'go', 'rust', 'ruby', 'php'];
    let detected = false;

    for (const lang of langOrder) {
      if (lang === 'nodejs') {
        if (files.includes('package.json')) {
          result.language = 'nodejs';
          result.startCommand = START_COMMANDS.nodejs.detect(dir);
          result.port = START_COMMANDS.nodejs.defaultPort;
          result.installCmd = START_COMMANDS.nodejs.installCmd;
          detected = true;
          break;
        }
      } else {
        const rule = LANGUAGE_RULES.find(r => r.language === lang);
        if (rule && rule.files.every(f => files.includes(f) || fs.existsSync(path.join(dir, f)))) {
          result.language = lang;
          const meta = rule.buildTool ? { buildTool: rule.buildTool } : null;
          result.startCommand = START_COMMANDS[lang].detect(dir, meta);
          result.port = START_COMMANDS[lang].defaultPort;
          result.installCmd = START_COMMANDS[lang].installCmd;
          detected = true;
          break;
        }
      }
    }

    if (!detected) {
      const extLang = this._detectByExtensions(dir);
      if (extLang && START_COMMANDS[extLang]) {
        result.language = extLang;
        result.startCommand = START_COMMANDS[extLang].detect(dir);
        result.port = START_COMMANDS[extLang].defaultPort;
        result.installCmd = START_COMMANDS[extLang].installCmd;
      }
    }

    const scannedPort = this._detectPort(dir, result.language);
    if (scannedPort) result.port = scannedPort;

    result.fullCommand = result.installCmd && result.startCommand
        ? `${result.installCmd} && ${result.startCommand}`
        : result.startCommand;

    return result;
  },

  // 通过文件扩展名检测（支持一级子目录）
  _detectByExtensions(dir) {
    try {
      const extCount = {};
      const countExt = (d, depth = 0) => {
        if (depth > 1) return; // 只看根目录和一级子目录
        const entries = fs.readdirSync(d, { withFileTypes: true });
        for (const entry of entries) {
          if (SKIP_DIRS.includes(entry.name)) continue;
          if (entry.name.startsWith('.')) continue;
          const fullPath = path.join(d, entry.name);
          if (entry.isFile()) {
            const ext = path.extname(entry.name).toLowerCase();
            if (ext) extCount[ext] = (extCount[ext] || 0) + 1;
          } else if (entry.isDirectory() && depth < 1) {
            try { countExt(fullPath, depth + 1); } catch {}
          }
        }
      };
      countExt(dir);

      if (extCount['.py'] > 0) return 'python';
      if (extCount['.java'] > 0) return 'java';
      if (extCount['.go'] > 0) return 'go';
      if (extCount['.rs'] > 0) return 'rust';
      if (extCount['.rb'] > 0) return 'ruby';
      if (extCount['.php'] > 0) return 'php';
      if (extCount['.js'] > 0 || extCount['.ts'] > 0) return 'nodejs';
    } catch {}
    return null;
  },

  // 查找前端目录（不包含 src）
  _findFrontendDir(rootDir) {
    for (const name of ['frontend', 'client', 'web']) {
      const dir = path.join(rootDir, name);
      if (fs.existsSync(dir)) return dir;
    }
    return null;
  },

  // 查找后端目录
  _findBackendDir(rootDir) {
    for (const name of ['server', 'backend', 'api']) {
      const dir = path.join(rootDir, name);
      if (fs.existsSync(dir)) return dir;
    }
    return null;
  },

  // 列出目录下的文件和文件夹名
  _listFiles(dir) {
    try {
      return fs.readdirSync(dir, { withFileTypes: true })
          .filter(e => !SKIP_DIRS.includes(e.name))
          .map(e => e.name);
    } catch {
      return [];
    }
  },

  // 读取 package.json
  _readPackageJson(dir) {
    const pkgPath = path.join(dir, 'package.json');
    if (!fs.existsSync(pkgPath)) return null;
    try {
      return JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
    } catch {
      return null;
    }
  },

  // 扫描根目录下的所有子目录
  detectSubdirs(rootDir) {
    if (!fs.existsSync(rootDir)) return [];

    const results = [];
    try {
      const entries = fs.readdirSync(rootDir, { withFileTypes: true });
      for (const entry of entries) {
        if (!entry.isDirectory()) continue;
        if (SKIP_DIRS.includes(entry.name)) continue;
        if (entry.name.startsWith('.')) continue;

        const dirPath = path.join(rootDir, entry.name);
        const detection = this._detectSingleDir(dirPath);
        results.push({
          name: entry.name,
          language: detection.language,
          startCommand: detection.startCommand,
          fullCommand: detection.fullCommand,
          installCmd: detection.installCmd,
          port: detection.port,
          isFrontend: this._isFrontendProject(dirPath, detection.language),
          isBackend: detection.language !== 'unknown'
        });
      }
    } catch {}

    const rootDetection = this._detectSingleDir(rootDir);
    if (rootDetection.language !== 'unknown') {
      results.unshift({
        name: '.',
        language: rootDetection.language,
        startCommand: rootDetection.startCommand,
        fullCommand: rootDetection.fullCommand,
        installCmd: rootDetection.installCmd,
        port: rootDetection.port,
        isFrontend: this._isFrontendProject(rootDir, rootDetection.language),
        isBackend: rootDetection.language !== 'unknown'
      });
    }

    return results;
  },

  detectSingleDir(dir) {
    if (!fs.existsSync(dir)) {
      return { language: 'unknown', startCommand: null, port: 3000, installCmd: null };
    }
    return this._detectSingleDir(dir);
  },

  // 判断是否为前端项目
  _isFrontendProject(dir, language) {
    if (language === 'nodejs') {
      const pkg = this._readPackageJson(dir);
      if (pkg) {
        const deps = { ...pkg.dependencies, ...pkg.devDependencies };
        for (const fw of FRONTEND_FRAMEWORKS) {
          if (deps[fw]) return true;
        }
      }
    }
    return false;
  },

  getLanguageLabel(lang) {
    const labels = {
      nodejs: 'Node.js',
      python: 'Python',
      java: 'Java',
      go: 'Go',
      rust: 'Rust',
      ruby: 'Ruby',
      php: 'PHP',
      unknown: '未知'
    };
    return labels[lang] || lang;
  },

  getTypeLabel(type) {
    const labels = {
      frontend: '前端项目',
      backend: '后端项目',
      fullstack: '全栈项目',
      unknown: '未知类型'
    };
    return labels[type] || type;
  }
};

module.exports = languageDetector;