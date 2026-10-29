# spw-lyrics-bridge

Salt Player for Windows/Linux（SPW）插件：把当前歌曲的完整歌词时间轴导出为本地 JSON 文件，供外部进程读取（如 Noctalia v5 状态栏歌词插件）。

## 设计

- **不开端口**：播放状态、进度、`Seeked` 信号、封面已由 SPW 内置的 MPRIS D-Bus 接口（`org.mpris.MediaPlayer2.salt-player`）提供，本插件只补齐 MPRIS 缺失的歌词数据。
- 换曲/歌词重载时把歌词时间轴**原子写入** `~/.cache/salt-player-spw-bridge/lyrics.json`（`XDG_CACHE_HOME` 可覆盖）；读取方按文件修改时间发现更新。
- **Noctalia 推送**：写盘后同时把歌词转换为 [Lyrics 插件](https://noctalia.dev/plugins/community/lyrics)（`h465855hgg/lyrics`）external 源的行模型，经 `noctalia msg … push-state` 推送（换曲推完整 payload，暂停/恢复/seek 推状态，播放中每 5 秒校准一次 position）。Noctalia 未安装时静默忽略。
- 进度同步建议：读取方用 MPRIS 的 `Position` + `Seeked` + `PlaybackStatus` 本地插值，用 `title + artist` 与文件内元数据做指纹匹配。

## 搭配 Noctalia Lyrics 在状态栏显示歌词

本插件把歌词推送进 [Lyrics 插件](https://noctalia.dev/plugins/community/lyrics)（`h465855hgg/lyrics`）的 **external 歌词源**，由它在状态栏渲染卡拉OK歌词。Lyrics 负责 UI、动画与进度插值，本插件只提供歌词与事件。

### 1. 安装依赖与插件

```sh
# Lyrics 插件的运行时依赖
sudo pacman -S playerctl python3
```

1. 安装本插件：Salt Player → 创意工坊 → 模组管理 → 导入模组 → 选择 `.spmod`，启用时勾选「曲库读取」
2. 安装 Lyrics：Noctalia 设置 → 插件 → 社区源搜索 `lyrics` → 启用 `h465855hgg/lyrics`
3. 启动它的 `service` entry（模组详情里启用 service），否则收不到推送

### 2. 配置 Lyrics

| 设置项 | 值 | 说明 |
| --- | --- | --- |
| 歌词源（`lyrics_source`） | **`external`** | 必须显式选，默认 `auto` 会走在线匹配，不吃推送 |
| 玩家过滤 | `org.mpris.MediaPlayer2.salt-player` | 只响应 Salt Player |
| `hide_when_no_lyrics` | 按需 | 无歌词时隐藏 |
| `hide_when_paused` | 按需 | 暂停时隐藏 |
| `karaoke_enabled` | 按需 | 逐字着色（本插件已推送 `chars` 时间轴，支持） |

把 `lyrics` bar widget 加到状态栏后即可看到歌词。

### 3. 使用

- 播放本地歌曲 → 状态栏显示同步歌词，逐字高亮（若启用 karaoke）
- **交互**（Lyrics 插件默认绑定，可在 widget 的 Actions 里改）：
  - 左键：在歌词与曲目信息之间切换
  - 右键：暂停/恢复
  - 滚轮上/下：下一曲/上一曲
- 进度同步：换曲、暂停/恢复、拖动进度条时立即对齐；播放中每 5 秒校准一次，漂移窗口 ≤5 秒
- 关闭推送：Salt Player → 模组管理 → 歌词桥 → 配置 → 「推送歌词到 Noctalia Lyrics」关掉（歌词文件仍继续导出）

### 4. Steam 版 Salt Player 的容器环境（重要）

经 Steam 启动的 Salt Player 会运行在 **Steam Linux Runtime（pressure-vessel）容器**内：容器里看不到宿主 `/usr` 与 `/run`，插件**无法直接执行宿主 `noctalia` CLI**（找不到二进制或 glibc 不兼容）。此时插件会自动降级：把推送内容原子写入 `~/.cache/salt-player-spw-bridge/push.out`，由宿主侧 systemd 服务转发。

一次性安装转发服务（在宿主终端执行，非容器内）：

```sh
sh deploy/install.sh
```

安装内容：`~/.local/bin/spw-lyrics-push.sh` + systemd --user 的 `spw-lyrics-push.path`/`.service`。之后每次插件写 outbox，宿主都会调用 `noctalia msg` 推送。

验证与卸载：

```sh
systemctl --user status spw-lyrics-push.path
journalctl --user -u spw-lyrics-push -n 20
systemctl --user disable --now spw-lyrics-push.path   # 卸载（再删除 unit 与脚本）
```

非 Steam 渠道（直跑）无需安装此服务，插件会直接调用 `noctalia`。

### 5. 故障排查

| 现象 | 检查 |
| --- | --- |
| 状态栏无歌词 | Lyrics 的歌词源是否选了 `external`；其 `service` entry 是否启动；`noctalia msg plugins list` 确认两者都已启用 |
| 歌词停在前几句 | 确认本插件版本 ≥1.0.0 且已随修复重导；`position` 单位错误会导致该现象 |
| 换歌后几秒内显示旧歌词 | 属正常窗口：歌词加载完成即推送，等待即可 |
| 歌词不动 | 确认 Salt Player 正在播放（暂停时 widget 不插值） |

推送调试：`noctalia msg plugin h465855hgg/lyrics:service all push-state '<json>'` 可直接手工验证 Lyrics 端是否正常。

## 输出文件契约

```json
{
  "writtenAt": 1769000000000,
  "plugin": "spw-lyrics-bridge 1.0.0",
  "title": "One Last Kiss",
  "artist": "宇多田ヒカル",
  "durationMs": 252027,
  "hasTimeline": true,
  "lines": [
    {
      "start": 12000, "end": 18000,
      "text": "主歌词", "translation": null,
      "cells": [{ "start": 12000, "end": 14000, "text": "主" }]
    }
  ]
}
```

- 时间单位毫秒；`cells` 为逐字（卡拉 OK）单元，无逐字数据时为 `null`
- `hasTimeline=false` 表示当前无可用歌词（换曲加载中/无歌词/失败，宿主不区分三者）
- `title`/`artist` 在未授予曲库读取权限时为 `null`

## 构建与安装

需 JDK 25：

```sh
./gradlew plugin
```

产物：`build/libs/plugin-dev.icesheep.spw.lyricsbridge-1.0.0.spmod`。在 Salt Player 中本地导入并启用，授予「曲库读取」权限。

## 宿主要求

SPW 1.19.0+（完整歌词时间轴 API 自 0.1.0-dev21 / 宿主 1.19.0 起提供）。

## 许可证

[Apache-2.0](LICENSE)——与上游 [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) 一致。

## 声明

本项目为 **vibe coding** 产物：代码由 AI 辅助生成，作者负责需求设计、代码审核与真机测试。Salt Player 及 SPW Workshop API 版权归 [Moriafly](https://github.com/Moriafly) 所有，本项目与其无隶属关系。

