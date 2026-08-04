const express = require('express');
const cors = require('cors');
const path = require('path');
const http = require('http');
const { WebSocketServer } = require('ws');
const { spawn } = require('child_process');
const { TERMUX_HOME } = require('./constants');


const app = express();
const PORT = 3456;

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

// const webDist = path.join(__dirname, '..', 'web', 'dist');
// 用直接编译好的
const webDist = path.join(__dirname, '.', 'html');
app.use(express.static(webDist));

app.use('/api/projects', require('./routes/projects'));
app.use('/api/scaffolds', require('./routes/scaffolds'));
app.use('/api/files', require('./routes/files'));
app.use('/api/git', require('./routes/git'));

app.get('/api/events', (req, res) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  res.write('\n');

  const listener = (data) => {
    res.write(`data: ${JSON.stringify(data)}\n\n`);
  };

  processService.statusBus.on('change', listener);

  req.on('close', () => {
    processService.statusBus.removeListener('change', listener);
  });
});

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

app.get('*', (req, res) => {
  if (req.path.startsWith('/api')) return res.status(404).json({ error: 'Not found' });
  const indexPath = path.join(webDist, 'index.html');
  const fs = require('fs');
  if (fs.existsSync(indexPath)) {
    res.sendFile(indexPath);
  } else {
    res.status(200).json({
      status: 'running',
      message: 'AIPM 后端已启动，前端未构建。请在项目目录执行: cd web && npm run build'
    });
  }
});

const server = http.createServer(app);

const wss = new WebSocketServer({ server, path: '/ws/terminal' });

wss.on('connection', (ws) => {
  let shell = null;

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'start') {
        const cwd = msg.cwd || process.cwd();
        const isWin = process.platform === 'win32';
        if (isWin) {
          shell = spawn('cmd', [], { cwd, env: process.env, stdio: ['pipe', 'pipe', 'pipe'] });
        } else {
        const homeDir = process.env.HOME || TERMUX_HOME;
          shell = spawn('bash', ['-i'], {
            cwd,
            env: { ...process.env, HOME: homeDir },
            stdio: ['pipe', 'pipe', 'pipe']
          });
        }

        shell.stdout.on('data', (d) => {
          ws.send(JSON.stringify({ type: 'stdout', data: d.toString() }));
        });
        shell.stderr.on('data', (d) => {
          ws.send(JSON.stringify({ type: 'stderr', data: d.toString() }));
        });
        shell.on('exit', (code) => {
          ws.send(JSON.stringify({ type: 'exit', code }));
          shell = null;
        });

        ws.send(JSON.stringify({ type: 'started', pid: shell.pid }));
      } else if (msg.type === 'input' && shell) {
        shell.stdin.write(msg.data);
      }
    } catch (err) {
      ws.send(JSON.stringify({ type: 'error', message: err.message }));
    }
  });

  ws.on('close', () => {
    if (shell) {
      try {
        shell.kill();
      } catch {}
      shell = null;
    }
  });
});

const processService = require('./services/processService');

(async () => {
  processService.clearAllLogs();
  server.listen(PORT, '0.0.0.0', () => {
    console.log('');
    console.log('AIPM 已启动!');
    console.log('');
    console.log(`   URL: http://localhost:${PORT}`);
    // console.log(`   前端页面: http://localhost:5173`);
    console.log('');
  });
})();

process.on('SIGINT', () => {
  console.log('\n正在关闭服务器...');
  processService.stopAllProcesses();
  server.close(() => {
    process.exit(0);
  });
});

process.on('SIGTERM', () => {
  processService.stopAllProcesses();
  server.close(() => {
    process.exit(0);
  });
});
