const puppeteer = require('puppeteer-core');
const net = require('net');
const fs = require('fs');
const path = require('path');

const SOCK_PATH = path.join(__dirname, 'bot.sock');
const CDP_URL = 'http://127.0.0.1:9222';

async function startServer() {
    if (fs.existsSync(SOCK_PATH)) {
        fs.unlinkSync(SOCK_PATH);
    }

    console.log('正在连接浏览器 CDP:', CDP_URL);
    const browser = await puppeteer.connect({ browserURL: CDP_URL, defaultViewport: null });
    const pages = await browser.pages();
    const page = pages.length > 0 ? pages[0] : await browser.newPage();

    console.log('连接成功！当前页面:', page.url());
    console.log('Bot 服务已启动，等待指令...');

    const server = net.createServer((socket) => {
        let buffer = '';
        socket.on('data', async (data) => {
            buffer += data.toString();
            let lines = buffer.split('\n');
            buffer = lines.pop(); // 保留最后不完整的一行

            for (const line of lines) {
                if (!line.trim()) continue;
                try {
                    const req = JSON.parse(line);
                    if (req.type === 'stop') {
                        socket.end(JSON.stringify({ success: true, data: 'Server stopping...' }) + '\n');
                        server.close();
                        await browser.disconnect();
                        process.exit(0);
                    } else if (req.type === 'exec') {
                        try {
                            // 注入上下文执行代码
                            let codeStr = req.code.trim();
                            if (!codeStr.includes(';') && !codeStr.startsWith('return') && !codeStr.endsWith('}')) {
                                codeStr = 'return ' + codeStr;
                            }
                            const func = new Function('browser', 'page', 'puppeteer', `return (async () => { ${codeStr} })()`);
                            const result = await func(browser, page, puppeteer);
                            let resStr;
                            if (result === undefined) resStr = 'undefined';
                            else if (result === null) resStr = 'null';
                            else if (typeof result === 'object') resStr = JSON.stringify(result, null, 2);
                            else resStr = String(result);

                            socket.write(JSON.stringify({ success: true, data: resStr }) + '\n');
                        } catch (e) {
                            socket.write(JSON.stringify({ success: false, error: e.message + '\n' + e.stack }) + '\n');
                        }
                    }
                } catch (e) {
                    socket.write(JSON.stringify({ success: false, error: 'Invalid request format' }) + '\n');
                }
            }
        });

        socket.on('error', (err) => {
            // 忽略客户端突然断开的错误
        });
    });

    server.listen(SOCK_PATH, () => {
        console.log('Socket 监听中:', SOCK_PATH);
    });
}

function sendCommand(req) {
    return new Promise((resolve, reject) => {
        const client = net.createConnection(SOCK_PATH, () => {
            client.write(JSON.stringify(req) + '\n');
        });

        let data = '';
        client.on('data', (chunk) => {
            data += chunk.toString();
            if (data.includes('\n')) {
                try {
                    const res = JSON.parse(data.trim());
                    resolve(res);
                    client.end();
                } catch (e) {
                    // 等待更多数据
                }
            }
        });

        client.on('error', (err) => {
            reject(new Error('无法连接到服务，请确保服务已启动 (node bot.js server)'));
        });

        client.on('timeout', () => {
            reject(new Error('执行超时'));
            client.end();
        });
    });
}

async function main() {
    const arg = process.argv[2];

    if (!arg || arg === 'help') {
        console.log('用法:');
        console.log('  启动服务: node bot.js server');
        console.log('  执行命令: node bot.js "await page.screenshot({path: \'./test.png\'})"');
        console.log('  停止服务: node bot.js stop');
        return;
    }

    if (arg === 'server') {
        startServer().catch(e => {
            console.error('❌ 服务启动失败:', e.message);
            process.exit(1);
        });
    } else if (arg === 'stop') {
        try {
            const res = await sendCommand({ type: 'stop' });
            console.log(res.success ? '✅ 服务已停止' : '❌ 停止失败: ' + res.error);
        } catch (e) {
            console.error(e.message);
        }
    } else {
        // 执行代码
        try {
            const res = await sendCommand({ type: 'exec', code: arg });
            if (res.success) {
                console.log(res.data);
            } else {
                console.error('❌ 执行出错:', res.error);
            }
        } catch (e) {
            console.error(e.message);
        }
    }
}

main();
