package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 歌词导出器：把当前歌曲的完整歌词时间轴写成 JSON 文件，供外部进程（Noctalia 插件）读取。
 *
 * 数据通路设计（不开端口、无常驻网络监听）：
 * - 播放状态、进度、Seeked 信号、封面已由 Salt Player 内置的 MPRIS D-Bus 接口提供，本插件不重复；
 * - 本插件只补齐 MPRIS 缺失的低频数据——换曲/歌词重载时把歌词时间轴
 *   原子写入 ~/.cache/salt-player-spw-bridge/lyrics.json（XDG_CACHE_HOME 可覆盖），
 *   读取方按文件修改时间发现更新。
 *
 * 线程模型：写盘在单个后台守护线程串行执行，不阻塞宿主回调线程；
 * [WorkshopApi.Playback.getCurrentMediaItem] 是阻塞数据库查询，同样安排在该线程调用。
 * 插件反复启停时线程随 stop() 销毁、start() 重建。
 */
internal object LyricsExporter {

    /** 与 build.gradle.kts 的 PluginVersion 保持一致 */
    private const val PLUGIN_VERSION = "1.0.0"

    private val lock = Any()

    @Volatile
    private var executor: ExecutorService? = null

    /** 最近一次已知的播放位置（毫秒）与播放状态（null=尚未收到状态事件） */
    @Volatile
    private var lastPositionMs = 0L

    @Volatile
    private var lastPlaying: Boolean? = null

    /** onPositionUpdated 计数，用于每 5 秒一次的位置校准 */
    private var positionTick = 0L

    /** Noctalia 推送开关（配置界面实时控制） */
    @Volatile
    private var noctaliaPushEnabled = true

    fun setNoctaliaPushEnabled(enabled: Boolean) {
        noctaliaPushEnabled = enabled
    }

    /** 缓存目录：遵循 XDG 规范，跨用户目录可写 */
    private val cacheDir: Path by lazy {
        val base = System.getenv("XDG_CACHE_HOME")
            ?: (System.getProperty("user.home") + "/.cache")
        Path.of(base, "salt-player-spw-bridge")
    }

    fun outputFile(): Path = cacheDir.resolve("lyrics.json")

    /**
     * 启动导出器并确保输出目录存在；幂等，start() 中调用。
     */
    fun ensureStarted() {
        synchronized(lock) {
            if (executor?.isShutdown == false) return
            Files.createDirectories(cacheDir)
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "spw-lyrics-bridge-writer").apply { isDaemon = true }
            }
        }
    }

    /**
     * 停止插件时释放线程；未完成的写盘任务被丢弃，
     * 重新启用后宿主会重放当前歌词，数据自动补上。
     */
    fun shutdown() {
        synchronized(lock) {
            executor?.shutdownNow()
            executor = null
        }
    }

    /**
     * 导出一次歌词时间轴（来自 onLyricsLinesUpdated）。
     * 任意线程可调用；实际写盘排队到后台线程。
     */
    fun export(lyricsLines: List<LyricsLine>) {
        ensureStarted()
        // 宿主给的列表不可修改且可能被复用，先复制快照再跨线程
        val snapshot = ArrayList(lyricsLines)
        submit {
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var durationMs = 0L
            try {
                // 需要 LIBRARY_READ；阻塞查询放在本后台线程执行
                WorkshopApi.playback.getCurrentMediaItem()?.let { item ->
                    title = item.title
                    artist = item.artist
                    album = item.album
                    durationMs = item.duration
                }
            } catch (denied: PluginPermissionDeniedException) {
                // 未声明或未授权曲库读取：仍导出歌词，元数据留空，
                // 由读取方从 MPRIS 元数据自行对齐
            } catch (e: Exception) {
                // 元数据查询的其他失败同样降级，不阻断歌词导出
            }
            try {
                writeAtomically(buildJson(snapshot, title, artist, durationMs))
            } catch (e: Exception) {
                // 写盘失败不能影响宿主：静默丢弃，换曲时会再次触发导出
            }
            // 歌词加载完成即推 Noctalia Lyrics（external 源在等这条推送）
            if (noctaliaPushEnabled) {
                NoctaliaPusher.pushFull(
                    title, artist, album, durationMs,
                    snapshot, lastPositionMs, lastPlaying
                )
            }
        }
    }

    /**
     * 通用提交：所有写盘与推送任务排队到写盘线程串行执行。
     * stop() 竞态导致的拒绝静默忽略。
     */
    private fun submit(task: () -> Unit) {
        try {
            executor?.execute(task)
        } catch (rejected: RejectedExecutionException) {
            // 线程已关闭：丢弃本次任务，重新启用后宿主会重放歌词补上
        }
    }

    /** 暂停/恢复（onIsPlayingChanged）：更新状态并立即推送修正 widget 基线 */
    fun onPlayingChanged(playing: Boolean) {
        lastPlaying = playing
        if (!noctaliaPushEnabled) return
        submit { NoctaliaPusher.pushState(lastPositionMs, playing) }
    }

    /** 进度跳转（onSeekTo）：更新位置并立即推送 */
    fun onSeek(position: Long) {
        lastPositionMs = position
        if (!noctaliaPushEnabled) return
        submit { NoctaliaPusher.pushState(position, lastPlaying) }
    }

    /**
     * 每秒位置更新（onPositionUpdated）：仅更新缓存；
     * 每 5 秒推送一次纯位置校准，修正 widget 本地插值的累计漂移。
     * 不依赖播放状态（暂停时 widget 自身不插值，多推一次无害）。
     */
    fun onPositionUpdated(position: Long) {
        lastPositionMs = position
        positionTick++
        if (positionTick % 5L != 0L || !noctaliaPushEnabled) return
        submit { NoctaliaPusher.pushPosition(position) }
    }

    /**
     * 组装 JSON。
     *
     * 字段契约：
     * - 时间单位毫秒；lines 为完整解析时间轴，start/end 为行起止；
     * - cells 为逐字（卡拉 OK）单元，无逐字数据时为 null；
     * - hasTimeline=false 表示当前没有可用时间轴（换曲加载中/无歌词/失败，宿主不区分）；
     * - title/artist 在无法读取曲库时为 null，读取方应改用 MPRIS 元数据对齐。
     */
    private fun buildJson(
        lyricsLines: List<LyricsLine>,
        title: String?,
        artist: String?,
        durationMs: Long
    ): String {
        val sb = StringBuilder(24 * 1024)
        sb.append("{\"writtenAt\":").append(System.currentTimeMillis())
        sb.append(",\"plugin\":\"spw-lyrics-bridge ").append(PLUGIN_VERSION).append('"')
        sb.append(",\"title\":").append(title?.let(::jsonString) ?: "null")
        sb.append(",\"artist\":").append(artist?.let(::jsonString) ?: "null")
        sb.append(",\"durationMs\":").append(durationMs)
        sb.append(",\"hasTimeline\":").append(lyricsLines.isNotEmpty())
        sb.append(",\"lines\":[")
        lyricsLines.forEachIndexed { index, line ->
            if (index > 0) sb.append(',')
            sb.append("{\"start\":").append(line.startTime)
            sb.append(",\"end\":").append(line.endTime)
            sb.append(",\"text\":").append(jsonString(line.pureMainText))
            sb.append(",\"translation\":").append(line.pureSubText?.let(::jsonString) ?: "null")
            if (line.lyricsCells.isEmpty()) {
                sb.append(",\"cells\":null")
            } else {
                sb.append(",\"cells\":[")
                line.lyricsCells.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) sb.append(',')
                    sb.append("{\"start\":").append(cell.startTime)
                    sb.append(",\"end\":").append(cell.endTime)
                    sb.append(",\"text\":").append(jsonString(cell.text))
                    sb.append('}')
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 原子写：先写同目录临时文件，再 rename 覆盖目标，
     * 保证外部读者永远读到完整文件而非半截 JSON。
     */
    private fun writeAtomically(content: String) {
        Files.createDirectories(cacheDir)
        val target = outputFile()
        val tmp = Files.createTempFile(cacheDir, "lyrics", ".tmp")
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8)
            try {
                Files.move(
                    tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** 手写 JSON 字符串转义，避免为几百字节引依赖第三方序列化库 */
    internal fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (ch < ' ') sb.append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
                    else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
