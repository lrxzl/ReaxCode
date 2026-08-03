/*file: ./server.js*/
const http = require('http');
const fs = require('fs');
const path = require('path');
const { NodeTools } = require('./nodeTools');

const PORT = 8089;
const DIST_DIR = path.join(__dirname, 'dist');

// 会话隔离管理：为每个 sessionId 创建独立的 NodeTools 实例
const sessions = new Map();
function getNodeTools(sessionId) {
    const key = sessionId || 'default';
    if (!sessions.has(key)) {
        sessions.set(key, new NodeTools());
    }
    return sessions.get(key);
}

// 允许前端调用的方法白名单
const ALLOWED_METHODS = new Set([
    'exec', 'sleep', 'listFiles', 'readLines',
    'replaceLines', 'insertLines', 'insertAfter',
    'createFile', 'appendLines', 'undoEdit'
]);

// 常见静态资源 MIME 映射
const MIME_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js':   'application/javascript; charset=utf-8',
    '.mjs':  'application/javascript; charset=utf-8',
    '.css':  'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png':  'image/png',
    '.jpg':  'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif':  'image/gif',
    '.svg':  'image/svg+xml',
    '.ico':  'image/x-icon',
    '.webp': 'image/webp',
    '.woff': 'font/woff',
    '.woff2':'font/woff2',
    '.ttf':  'font/ttf',
    '.otf':  'font/otf',
    '.map':  'application/json'
};

const server = http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'POST, GET, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, token');
    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    if (req.method === 'POST' && req.url === '/api/callNative') {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', async () => {
            try {
                const payload = JSON.parse(body);
                const { methodName, args, sessionId } = payload;
                console.log(`Received request [Session: ${sessionId || 'default'}]:`, methodName);

                if (!ALLOWED_METHODS.has(methodName)) {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: 'Unsupported or forbidden method: ' + methodName }));
                    return;
                }

                const nodeTools = getNodeTools(sessionId);

                if (typeof nodeTools[methodName] === 'function') {
                    try {
                        const result = await nodeTools[methodName](...(args || []));
                        console.log(`Sent response [Session: ${sessionId || 'default'}]:`, methodName, result);
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ data: result }));
                    } catch (e) {
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: e.message }));
                    }
                } else {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: 'Method not implemented: ' + methodName }));
                }
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'Invalid request: ' + e.message }));
            }
        });
        return;
    }

    // ====== 静态文件服务（dist 目录） ======
    if (req.method === 'GET') {
        let urlPath = req.url.split('?')[0];
        if (urlPath === '/') urlPath = '/index.html';

        const decoded = decodeURIComponent(urlPath);
        const filePath = path.normalize(path.join(DIST_DIR, decoded));
        if (!filePath.startsWith(DIST_DIR)) {
            res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('Forbidden');
            return;
        }

        fs.stat(filePath, (err, stat) => {
            if (err || !stat.isFile()) {
                const fallback = path.join(DIST_DIR, 'index.html');
                fs.readFile(fallback, (e2, data2) => {
                    if (e2) {
                        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
                        res.end('Not found: ' + urlPath);
                        return;
                    }
                    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                    res.end(data2);
                });
                return;
            }

            fs.readFile(filePath, (e, data) => {
                if (e) {
                    res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
                    res.end('Read error: ' + e.message);
                    return;
                }
                const ext = path.extname(filePath).toLowerCase();
                const mime = MIME_TYPES[ext] || 'application/octet-stream';
                res.writeHead(200, { 'Content-Type': mime });
                res.end(data);
            });
        });
        return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Not found' }));
});

server.listen(PORT, () => {
    const localUrl = `http://localhost:${PORT}/`;
    console.log('Seeker HTTP server running at ' + localUrl);
    console.log('  - Web UI (dist/index.html): ' + localUrl + 'index.html');
    console.log('  - API endpoint:             ' + localUrl + 'api/callNative');
});