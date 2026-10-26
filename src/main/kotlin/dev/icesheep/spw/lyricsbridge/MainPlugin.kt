package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * 插件主类：只负责歌词导出器的启停。
 *
 * 宿主约定：插件可能被反复启停，start() 中重建资源，stop() 中全部释放。
 * 启用后 PlaybackExtensionPoint.onLyricsLinesUpdated 会异步重放当前歌词，
 * 因此无需在 start() 里主动拉取歌词。
 */
class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    override fun start() {
        LyricsExporter.ensureStarted()
        WorkshopApi.ui.toast("歌词桥已启用", WorkshopApi.Ui.ToastType.Success)
    }

    override fun stop() {
        // 关闭写盘线程；已写出的歌词文件保留在磁盘上，
        // 有效性由读取方（Noctalia）按 MPRIS 歌曲指纹自行判断
        LyricsExporter.shutdown()
    }
}
