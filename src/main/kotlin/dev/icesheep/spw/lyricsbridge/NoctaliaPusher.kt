package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine
import java.util.concurrent.TimeUnit

/**
 * Noctalia Lyrics 插件推送桥。
 *
 * Lyrics（h465855hgg/lyrics）的 external 歌词源是被动的：它不做 MPRIS 轮询，
 * 只监听 Noctalia 的 IPC 命令（noctalia msg）。本桥在歌词变化/播放事件时，
 * 用 noctalia msg 把数据推给它的 service entry；widget 端按 33ms 本地插值
 * 渲染，因此低频推送即可平滑显示。
 *
 * 推送时机（由 LyricsExportExtension 驱动，统一排队到写盘线程串行执行）：
 * - 换曲/歌词加载完成：完整 payload（track + lines + position + playing）
 * - 暂停/恢复、seek：小状态包（position + playing）
 * - 播放中每 5 秒一次 position 校准
 *
 * payload 单位约定（与 Lyrics 源码对齐）：
 * - track.duration 为微秒（Lyrics 的 trackKey 按 μs→s 折算）
 * - position 与歌词时间戳为毫秒
 * - lines[].chars 与 text 的 Unicode 字符一一对应（卡拉 OK 逐字着色）
 *
 * noctalia msg 失败（未安装/插件未启用）时静默忽略——歌词文件仍照常落盘。
 */
internal object NoctaliaPusher {

    private const val TARGET = "h465855hgg/lyrics:service"

    /**
     * 换曲/歌词加载完成后的完整推送。
     *
     * [positionMs] 当前播放位置（毫秒），[durationMs] 曲长（毫秒）。
     * [lyricsLines] 为宿主解析的歌词时间轴，转换成 Lyrics 的行模型后推送。
     *
     * 单位约定（Lyrics 文档与源码一致）：track.duration 与 position 为微秒，
     * 歌词时间戳为毫秒。传毫秒进 position 会被 widget 再除以 1000，
     * 表现为永远停在前几行。
     *
     * [playing] 为 null 时不带该字段，让 widget 保留原有播放状态
     * （插件启动初期可能还没收到过播放状态事件）。
     */
    fun pushFull(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        lyricsLines: List<LyricsLine>,
        positionMs: Long,
        playing: Boolean?
    ) {
        val sb = StringBuilder(8 * 1024)
        sb.append("{\"track\":{")
        sb.append("\"title\":").append(title?.let(LyricsExporter::jsonString) ?: "\"\"")
        sb.append(",\"artist\":").append(artist?.let(LyricsExporter::jsonString) ?: "\"\"")
        sb.append(",\"album\":").append(album?.let(LyricsExporter::jsonString) ?: "\"\"")
        // Lyrics 的 trackKey 将 duration 视为微秒
        sb.append(",\"duration\":").append(durationMs * 1000)
        sb.append(",\"playerInstance\":\"salt-player\"")
        sb.append("},\"position\":").append(positionMs * 1000)
        if (playing != null) sb.append(",\"playing\":").append(playing)
        sb.append(",\"lines\":[").append(buildLinesJson(lyricsLines)).append("]}")
        send(sb.toString())
    }

    /** 暂停/恢复/seek 的状态包（position 微秒；playing 为 null 时不带该字段）。 */
    fun pushState(positionMs: Long, playing: Boolean?) {
        val sb = StringBuilder(64)
        sb.append("{\"position\":").append(positionMs * 1000)
        if (playing != null) sb.append(",\"playing\":").append(playing)
        sb.append('}')
        send(sb.toString())
    }

    /** 播放中的周期位置校准：只推 position，不动 playing，避免污染 widget 状态。 */
    fun pushPosition(positionMs: Long) {
        send("{\"position\":${positionMs * 1000}}")
    }

    /**
     * 把宿主歌词时间轴转换为 Lyrics 行模型 JSON。
     *
     * Lyrics 行：{ time, duration, text, translation?, chars? }
     * chars 为每字开始时间数组，按 Unicode 码点与 text 一一对应；
     * cells 拼接与整行文本的码点数不一致时放弃 chars（退化为整行进度着色）。
     */
    private fun buildLinesJson(lyricsLines: List<LyricsLine>): String {
        val sb = StringBuilder(4 * 1024)
        lyricsLines.forEachIndexed { index, line ->
            if (index > 0) sb.append(',')
            sb.append("{\"time\":").append(line.startTime)
            sb.append(",\"duration\":").append((line.endTime - line.startTime).coerceAtLeast(0))
            sb.append(",\"text\":").append(LyricsExporter.jsonString(line.pureMainText))
            line.pureSubText?.let {
                sb.append(",\"translation\":").append(LyricsExporter.jsonString(it))
            }
            buildCharsJson(line)?.let {
                sb.append(",\"chars\":[").append(it).append(']')
            }
            sb.append('}')
        }
        return sb.toString()
    }

    /** 逐字时间数组；无逐字数据或码点数不匹配时返回 null。 */
    private fun buildCharsJson(line: LyricsLine): String? {
        val cells = line.lyricsCells
        if (cells.isEmpty()) return null
        val textCpCount = line.pureMainText.codePointCount(0, line.pureMainText.length)
        if (textCpCount < 2) return null
        val sb = StringBuilder(cells.size * 8)
        var count = 0
        for (cell in cells) {
            val cpCount = cell.text.codePointCount(0, cell.text.length)
            repeat(cpCount) {
                if (count > 0) sb.append(',')
                sb.append(cell.startTime)
                count++
            }
        }
        return if (count == textCpCount) sb.toString() else null
    }

    /**
     * 执行 noctalia msg。失败时把原因输出到 stderr（宿主日志可见），
     * 仅在失败状态变化时打印一次，避免每 5 秒刷屏。
     * 调用方需保证在后台线程（当前为写盘线程）串行执行，避免并发 fork。
     */
    private fun send(payload: String) {
        // Steam Linux Runtime 容器内：宿主 /usr 与 /run 均不可见，
        // 无法执行宿主 noctalia CLI，改由宿主侧 systemd 服务转发 outbox 文件
        if (inRuntimeContainer) {
            writeOutbox(payload)
            return
        }
        var process: Process? = null
        try {
            process = ProcessBuilder(
                noctaliaCommand, "msg", "plugin", TARGET, "all", "push-state", payload
            ).apply {
                // 保留 stderr 用于诊断，stdout 丢弃
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
            }.start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reportFailure("noctalia msg 超时未返回")
                writeOutbox(payload)
                return
            }
            val exit = process.exitValue()
            if (exit == 0) {
                if (lastFailure != null) {
                    lastFailure = null
                    System.err.println("[lyrics-bridge] Noctalia 推送恢复正常")
                }
            } else {
                val err = runCatching {
                    process.errorStream.bufferedReader().use { it.readText() }
                }.getOrDefault("").trim()
                reportFailure(
                    "noctalia msg 退出码 $exit" +
                        if (err.isEmpty()) "" else "：${err.take(300)}"
                )
                // 直连失败（如未装 Noctalia 或命令异常）时同样落 outbox，
                // 由宿主侧服务兜底
                writeOutbox(payload)
            }
        } catch (e: Exception) {
            process?.destroyForcibly()
            reportFailure(
                "无法执行 $noctaliaCommand：${e::class.java.simpleName}: ${e.message}"
            )
            writeOutbox(payload)
        }
    }

    /**
     * 是否运行在 Steam Linux Runtime（pressure-vessel）容器内。
     * 容器特征：/run/host 是宿主根的部分挂载，而 /run/host/run 不存在（宿主 /run 不可见）。
     */
    private val inRuntimeContainer: Boolean by lazy {
        java.nio.file.Files.exists(java.nio.file.Path.of("/run/host")) &&
            !java.nio.file.Files.exists(java.nio.file.Path.of("/run/host/run"))
    }

    /**
     * 把推送内容原子写入 outbox，供宿主侧 systemd 服务转发给 Noctalia。
     * 路径：<缓存目录>/push.out
     */
    private fun writeOutbox(payload: String) {
        try {
            val dir = LyricsExporter.outputFile().parent
            java.nio.file.Files.createDirectories(dir)
            val target = dir.resolve("push.out")
            val tmp = java.nio.file.Files.createTempFile(dir, "push", ".tmp")
            try {
                java.nio.file.Files.writeString(tmp, payload, java.nio.charset.StandardCharsets.UTF_8)
                java.nio.file.Files.move(
                    tmp, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                java.nio.file.Files.deleteIfExists(tmp)
            }
        } catch (e: Exception) {
            reportFailure("写入 outbox 失败：${e::class.java.simpleName}: ${e.message}")
        }
    }

    /** 只在失败原因变化时打印，恢复时打印一次恢复日志 */
    private fun reportFailure(reason: String) {
        if (lastFailure == reason) return
        lastFailure = reason
        System.err.println("[lyrics-bridge] Noctalia 推送失败：$reason")
    }

    /** 上次推送失败原因（null 表示正常），用于抑制重复日志 */
    private var lastFailure: String? = null

    /**
     * noctalia CLI 路径。
     *
     * Steam Linux Runtime 容器内宿主 /usr 不可见，宿主根挂载在 /run/host；
     * 优先使用容器可执行的绝对路径，再退回 PATH 查找。
     */
    private val noctaliaCommand: String by lazy {
        val containerPath = java.nio.file.Path.of("/run/host/usr/bin/noctalia")
        val hostPath = java.nio.file.Path.of("/usr/bin/noctalia")
        when {
            java.nio.file.Files.isExecutable(containerPath) -> containerPath.toString()
            java.nio.file.Files.isExecutable(hostPath) -> hostPath.toString()
            else -> "noctalia"
        }
    }
}
