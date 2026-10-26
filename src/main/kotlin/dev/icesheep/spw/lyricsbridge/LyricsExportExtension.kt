package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 播放扩展点：只订阅歌词时间轴变化，其余回调（位置/状态/控制）一律不碰——
 * 进度同步由 MPRIS 负责，本插件不做高频数据。
 *
 * 宿主约定：插件启用后会异步重放当前歌词（可能为空列表）；
 * 换曲、歌词重载时先发布空列表，加载完成后发布完整解析行；
 * 空列表统一表示"当前没有可用时间轴"，不区分加载中/无歌词/加载失败。
 */
@Extension
class LyricsExportExtension : PlaybackExtensionPoint {

    override fun onLyricsLinesUpdated(
        lyricsLines: List<PlaybackExtensionPoint.LyricsLine>
    ) {
        LyricsExporter.export(lyricsLines)
    }
}
