#!/bin/sh
# 安装宿主侧转发服务（systemd --user）。
#
# 适用场景：Salt Player 经 Steam 启动、运行在 Steam Linux Runtime 容器内，
# 插件无法直接执行宿主 noctalia CLI。安装后：
#   插件写 outbox 文件 -> path unit 触发 -> 宿主执行 noctalia msg
#
# 用法：sh deploy/install.sh
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
bin_dir="$HOME/.local/bin"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

mkdir -p "$bin_dir" "$unit_dir"
install -m 0755 "$here/spw-lyrics-push.sh" "$bin_dir/spw-lyrics-push.sh"
install -m 0644 "$here/spw-lyrics-push.service" "$unit_dir/spw-lyrics-push.service"
install -m 0644 "$here/spw-lyrics-push.path" "$unit_dir/spw-lyrics-push.path"

systemctl --user daemon-reload
systemctl --user enable --now spw-lyrics-push.path

echo "已安装并启用 spw-lyrics-push.path"
echo "验证：播放一首歌后执行 journalctl --user -u spw-lyrics-push -n 20"
