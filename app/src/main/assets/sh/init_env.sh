#!/bin/bash
# ============================================
# Termux 环境初始化总入口（幂等）
#   - 同步：init_mirrors.sh + init_node.sh
#   - 异步：init_git.sh （后台）
# 所有输出写入 $HOME/init_env.log
# ============================================

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${HOME}/init_env.log"

exec > "$LOG_FILE" 2>&1
exec < /dev/null

# 禁用一切交互式提示
export DEBIAN_FRONTEND=noninteractive
export APT_LISTCHANGES_FRONTEND=none
export GIT_TERMINAL_PROMPT=0
export npm_config_yes=true

echo "=== init_env.sh 开始 $(date) ==="

# 1. 镜像源（幂等，快速）
bash "$SCRIPT_DIR/init_mirrors.sh"
echo "----- init_mirrors.sh 完成 -----"

# 2. Node.js —— 同步等待（后续服务必须依赖 node）
bash "$SCRIPT_DIR/init_node.sh"
NODE_EXIT=$?
if [ $NODE_EXIT -ne 0 ]; then
    echo "[init_env][错误] Node.js 初始化失败 (exit=$NODE_EXIT)"
    exit $NODE_EXIT
fi
echo "----- init_node.sh 完成 -----"

# 3. Git —— 异步后台（不阻塞主流程）
nohup bash "$SCRIPT_DIR/init_git.sh" < /dev/null >> "$LOG_FILE" 2>&1 &
echo "[init_env] Git 初始化已派发后台 (PID: $!)"

echo "=== init_env.sh 完成 $(date) ==="
