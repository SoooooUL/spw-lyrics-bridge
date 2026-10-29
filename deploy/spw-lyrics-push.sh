#!/bin/sh
# 把 Salt Player 歌词桥写出的推送内容转发给 Noctalia Lyrics 插件。
#
# 触发方式：由 spw-lyrics-push.path 监听 outbox 文件变化后调用；
# 也可手动执行验证（插件直连失败时的保底通道）。
#
# 背景：Salt Player 经 Steam 启动时会运行在 Steam Linux Runtime
# （pressure-vessel）容器内，容器里看不到宿主 /usr 与 /run，插件无法
# 直接执行 noctalia CLI，只能写文件，由本脚本在宿主侧转发。
set -u

CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/salt-player-spw-bridge"
OUTBOX="$CACHE_DIR/push.out"

[ -s "$OUTBOX" ] || exit 0
payload="$(cat "$OUTBOX")"
[ -n "$payload" ] || exit 0

exec noctalia msg plugin h465855hgg/lyrics:service all push-state "$payload"
