# spw-lyrics-bridge

Salt Player for Windows/Linux（SPW）插件：把当前歌曲的完整歌词时间轴导出为本地 JSON 文件，供外部进程读取（如 Noctalia v5 状态栏歌词插件）。

## 设计

- **不开端口**：播放状态、进度、`Seeked` 信号、封面已由 SPW 内置的 MPRIS D-Bus 接口（`org.mpris.MediaPlayer2.salt-player`）提供，本插件只补齐 MPRIS 缺失的歌词数据。
- 换曲/歌词重载时把歌词时间轴**原子写入** `~/.cache/salt-player-spw-bridge/lyrics.json`（`XDG_CACHE_HOME` 可覆盖）；读取方按文件修改时间发现更新。
- 进度同步建议：读取方用 MPRIS 的 `Position` + `Seeked` + `PlaybackStatus` 本地插值，用 `title + artist` 与文件内元数据做指纹匹配。

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

