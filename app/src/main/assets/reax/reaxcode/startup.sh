#!/bin/bash
NOTIFY_LOG="${HOME}/reax/common.log"
mkdir -p "$(dirname "$NOTIFY_LOG")" 2>/dev/null
exec > >(tee -a "$NOTIFY_LOG") 2>&1
echo "启动reaxcode..."

# 检查 8089 端口是否已被占用（reaxcode 默认端口）
if netstat -tln 2>/dev/null | grep -q ':8089 '; then
    echo "reaxcode 已在运行（8089 端口已监听），跳过启动"
    exit 0
fi

# 优先使用全局安装的 reaxcode（node 直接启动，绕开 /usr/bin/env 缺失问题）
REAXCODE_SERVER="/data/data/com.termux/files/usr/lib/node_modules/reaxcode/server.js"
if [ -f "$REAXCODE_SERVER" ]; then
    echo "使用全局安装的 reaxcode: $REAXCODE_SERVER"
    nohup node "$REAXCODE_SERVER" < /dev/null >> "${HOME}/reax/reaxcode-server.log" 2>&1 &
    REAXCODE_PID=$!
    echo "reaxcode 已后台启动 (PID: $REAXCODE_PID, 端口 8089)"
    exit 0
fi

# 全局不存在时自动安装
echo "未找到全局 reaxcode，正在安装..."
export PATH="${PATH}:/data/data/com.termux/files/usr/bin"
if npm install -g reaxcode@latest 2>&1; then
    if [ -f "$REAXCODE_SERVER" ]; then
        echo "安装成功，启动 reaxcode: $REAXCODE_SERVER"
        nohup node "$REAXCODE_SERVER" < /dev/null >> "${HOME}/reax/reaxcode-server.log" 2>&1 &
        REAXCODE_PID=$!
        echo "reaxcode 已后台启动 (PID: $REAXCODE_PID, 端口 8089)"
        exit 0
    fi
fi

# 兜底：使用 npx（可能因 /usr/bin/env 缺失而失败）
echo "安装失败，尝试 npx..."
npx --prefer-online -y reaxcode@latest --yes >> "$NOTIFY_LOG" 2>&1 &
