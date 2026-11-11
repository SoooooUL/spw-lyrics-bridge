package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * 写 Media Lyrics（tranzem/media-lyrics）可直接读取的 LRC 文件。
 *
 * Media Lyrics 的歌词获取顺序中本地目录是第一优先级：
 *   <local_lyrics_dir>/<artist> - <title>.lrc
 *   <local_lyrics_dir>/<title>.lrc
 * 其 artist 取自 MPRIS xesam:artist 数组并以 ", " 拼接，而 SPW 的
 * MediaItem.artist 用 "/" 分隔，因此这里做等价转换后再命名。
 *
 * 文件名清洗复刻 Media Lyrics 的 sanitizePart：[%c/\\] -> "_" 并 trim，
 * 否则两边拼出的文件名不一致，本地文件不会被命中。
 *
 * 输出为行级同步的标准 LRC；逐字时间轴仅存在于 lyrics.json 契约中
 * （LRC 格式不支持逐字）。
 */
internal object LrcWriter {

    /** Media Lyrics 默认的 local_lyrics_dir：$XDG_DATA_HOME/media-lyrics */
    private val outputDir: Path by lazy {
        val base = System.getenv("XDG_DATA_HOME")
            ?: (System.getProperty("user.home") + "/.local/share")
        Path.of(base, "media-lyrics")
    }

    /**
     * 写出当前曲目的 LRC 文件；无歌词或缺少标题时跳过。
     * 艺术家版与标题版两个候选都写，命中任一即可。
     */
    fun write(title: String?, artist: String?, lines: List<LyricsLine>) {
        if (title.isNullOrBlank() || lines.isEmpty()) return
        val content = buildLrc(lines)
        if (content.isEmpty()) return
        val titlePart = sanitizePart(title)
        if (titlePart.isEmpty()) return
        // MPRIS 的 artist 数组以 ", " 拼接；SPW 的 MediaItem.artist 用 "/"
        val artistPart = sanitizePart(artist?.replace("/", ", ") ?: "")
        Files.createDirectories(outputDir)
        if (artistPart.isNotEmpty()) {
            writeAtomically(outputDir.resolve("$artistPart - $titlePart.lrc"), content)
        }
        writeAtomically(outputDir.resolve("$titlePart.lrc"), content)
    }

    /** 生成标准 LRC：每行 [mm:ss.cc]文本，空文本行跳过。 */
    private fun buildLrc(lines: List<LyricsLine>): String {
        val sb = StringBuilder(8 * 1024)
        for (line in lines) {
            val text = line.pureMainText.trim()
            if (text.isEmpty()) continue
            val ms = line.startTime.coerceAtLeast(0)
            val minutes = ms / 60_000
            val seconds = (ms % 60_000) / 1000
            val centis = (ms % 1000) / 10
            sb.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]", minutes, seconds, centis))
            sb.append(text).append('\n')
        }
        return sb.toString()
    }

    /** 与 Media Lyrics 的 sanitizePart 保持一致：控制字符与 / \ 替换为 _，再 trim。 */
    private fun sanitizePart(value: String): String =
        value.replace(Regex("[\\x00-\\x1F\\x7F/\\\\]"), "_").trim()

    private fun writeAtomically(target: Path, content: String) {
        val tmp = Files.createTempFile(target.parent, "lrc", ".tmp")
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
}
