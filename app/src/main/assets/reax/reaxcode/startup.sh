#!/bin/bash
log() {
  : #echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}
NOTIFY_LOG="${HOME}/reax/common.log"
mkdir -p "$(dirname "$NOTIFY_LOG")" 2>/dev/null
exec > >(tee -a "$NOTIFY_LOG") 2>&1
log "启动reaxcode..."


# 检查 8089 端口是否已被占用（reaxcode 默认端口）
if netstat -tln 2>/dev/null | grep -q ':8089 '; then
    log "reaxcode 已在运行（8089 端口已监听），跳过启动"
    exit 0
fi

# ---------- 自动更新reaxcode（每天只检查一次） ----------
UPDATE_INTERVAL=43200  # 12小时（秒）
UPDATE_FLAG="${HOME}/.reaxcode_last_update"
export PATH="${PATH}:/data/data/com.termux/files/usr/bin"

do_update() {
    log "执行更新检查..."
    npm install -g reaxcode@latest 2>&1 | tee -a "$NOTIFY_LOG"
    if [ $? -eq 0 ]; then
        log "更新成功"
    else
        log "更新失败"
    fi
    touch "$UPDATE_FLAG"  # 更新标记时间
}

if [ -f "$UPDATE_FLAG" ]; then
    last_update=$(stat -c %Y "$UPDATE_FLAG" 2>/dev/null || stat -f %m "$UPDATE_FLAG" 2>/dev/null)
    now=$(date +%s)
    if [ $((now - last_update)) -ge $UPDATE_INTERVAL ]; then
        do_update
    else
        log "距离上次更新不足24小时，跳过更新"
    fi
else
    do_update  # 第一次运行强制更新
fi
# ------------------------------------------------

REAXCODE_SERVER="/data/data/com.termux/files/usr/lib/node_modules/reaxcode/server.js"
if [ -f "$REAXCODE_SERVER" ]; then
    log "使用全局安装的 reaxcode: $REAXCODE_SERVER"
    nohup node "$REAXCODE_SERVER" < /dev/null >> "${HOME}/reax/reaxcode-server.log" 2>&1 &
    REAXCODE_PID=$!
    log "reaxcode 已后台启动 (PID: $REAXCODE_PID, 端口 8089)"
    exit 0
fi

log "全局安装缺失，尝试 npx..."
npx --prefer-online -y reaxcode@latest --yes >> "$NOTIFY_LOG" 2>&1 &
