package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.util.function.Consumer

/**
 * 插件主类：启动/停止歌词导出器，并维护 Noctalia 推送开关的配置联动。
 *
 * 宿主约定：插件可能被反复启停，start() 中重建资源（注册监听、重建线程），
 * stop() 中全部释放；配置界面保存后由监听器实时生效。
 */
@OptIn(UnstableSpwWorkshopApi::class)
class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    private val configManager by lazy {
        WorkshopApi.manager.createConfigManager()
    }

    /** 配置变更监听：开关即时生效，无需重启插件 */
    private val configListener = Consumer<ConfigHelper> { config ->
        LyricsExporter.setNoctaliaPushEnabled(config.get("push.noctalia", true))
    }

    override fun start() {
        LyricsExporter.ensureStarted()
        // 界面保存会自动写文件；启动时读一次初值并监听后续变化
        LyricsExporter.setNoctaliaPushEnabled(
            configManager.getConfig().get("push.noctalia", true)
        )
        configManager.addConfigChangeListener(configListener)
        WorkshopApi.ui.toast("歌词桥已启用", WorkshopApi.Ui.ToastType.Success)
    }

    override fun stop() {
        // 关闭写盘线程与监听；已写出的歌词文件保留在磁盘上，
        // 有效性由读取方（Noctalia）按 MPRIS 歌曲指纹自行判断
        configManager.removeConfigChangeListener(configListener)
        LyricsExporter.shutdown()
    }
}
