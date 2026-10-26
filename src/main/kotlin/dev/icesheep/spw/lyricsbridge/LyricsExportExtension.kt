package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 播放扩展点：订阅歌词时间轴与播放事件。
 *
 * 数据分派原则——进度/状态的高频渲染由消费端（Noctalia Lyrics widget）
 * 本地插值完成，本插件只推送低频事件：
 * - 歌词时间轴变化 → 写歌词文件 + 完整推送
 * - 暂停/恢复、seek → 小状态包（position + playing）
 * - 每秒位置更新 → 仅缓存，播放中每 5 秒一次校准
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

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        LyricsExporter.onPlayingChanged(isPlaying)
    }

    override fun onSeekTo(position: Long) {
        LyricsExporter.onSeek(position)
    }

    override fun onPositionUpdated(position: Long) {
        LyricsExporter.onPositionUpdated(position)
    }
}
