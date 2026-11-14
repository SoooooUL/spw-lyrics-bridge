package dev.icesheep.spw.lyricsbridge

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine

/**
 * 版权 / 制作信息行过滤。
 *
 * 规则移植自 Noctalia Lyrics 插件（h465855hgg/lyrics，MIT）的 lyric_sources.py：
 * - METADATA_LINE：整行以制作信息标签开头（作词/作曲/编曲/出品/发行/版权等，
 *   以及 Latin、日文、韩文标签和 "Produced by"/"© 2024"/"courtesy of" 等形式）
 * - INSTRUMENTAL_LINE：纯音乐占位文本（"纯音乐，请欣赏"、"instrumental"）
 * - DECORATIVE_LINE：只含装饰符号（♪♫~-…）的行
 *
 * 只过滤本插件导出的数据（lyrics.json / LRC / Noctalia 推送），
 * 不改变宿主 Salt Player 自身的歌词显示。
 *
 * 防御：若过滤后一行不剩（纯正文被判为制作信息，或歌词本身只有一行制作信息），
 * 保留原列表，避免歌词整体消失。
 */
internal object CreditFilter {

    /** 中文（含繁体）制作信息标签 */
    private val cjkLabels = listOf(
        "作词", "作詞", "作曲", "编曲", "編曲", "制作人", "製作人", "监制", "監製",
        "出品人", "出品方", "出品", "发行方", "發行方", "发行", "發行", "词曲", "詞曲",
        "和声", "和聲", "合声", "合音", "吉他", "贝斯", "貝斯", "鼓手", "鼓", "键盘", "鍵盤",
        "弦乐", "弦樂", "钢琴", "鋼琴", "录音师", "錄音師", "录音室", "錄音室",
        "录音", "錄音", "混音师", "混音師", "混音", "母带", "母帶", "制作", "製作",
        "配唱", "人声", "人聲", "乐器", "樂器", "演奏", "演唱", "监唱", "監唱",
        "统筹", "統籌", "企划", "企劃", "策划", "策劃", "文案", "设计", "設計",
        "封面", "摄影", "攝影", "导演", "導演", "后期", "後期", "字幕", "校对", "校對",
        "版权", "版權", "版权管理方", "版權管理方", "词版权管理方", "詞版權管理方",
        "曲版权管理方", "曲版權管理方", "录音版权", "錄音版權", "母带版权", "母帶版權",
        "词曲版权", "詞曲版權", "录音作品", "錄音作品", "录音作品及MV版权", "錄音作品及MV版權",
        "联合出品", "聯合出品",
        "制作公司", "製作公司", "唱片公司", "出版公司", "出版", "批准文号", "批准文號",
        "音乐出品", "音樂出品", "版权所有", "版權所有", "未经许可", "未經許可",
        "出品监制", "出品監製", "总监制", "總監製", "总策划", "總策劃", "总导演", "總導演",
        "制作统筹", "製作統籌", "音乐总监", "音樂總監", "艺术总监", "藝術總監",
        "出品公司", "发行公司", "發行公司", "联合发行", "聯合發行",
        "母带工程师", "母帶工程師", "混音工程师", "混音工程師", "录音工程师", "錄音工程師",
        "制作助理", "製作助理", "录音助理", "錄音助理", "混音助理",
        "词作者", "詞作者", "曲作者", "编曲人", "編曲人", "作词作曲", "作詞作曲",
        "歌词制作", "歌詞製作", "歌词提供", "歌詞提供", "歌词翻译", "歌詞翻譯",
        "翻译", "翻譯", "上传", "上傳", "贡献", "貢獻",
        // 日文
        "作詞", "作曲", "編曲", "プロデュース", "プロデューサー", "ミックス", "マスタリング",
        "レコーディング", "コーラス", "演奏", "ギター", "ベース", "ドラム", "ピアノ", "ストリングス",
        // 韩文
        "작사", "작곡", "편곡", "프로듀서", "믹싱", "마스터링", "레코딩", "보컬",
        "기타", "베이스", "드럼", "피아노"
    )

    /** 拉丁字母（英/法/德/西/葡）制作信息标签 */
    private val latinLabels = listOf(
        "lyrics", "lyric", "words", "music", "composed", "composer", "written", "writer",
        "produced", "producer", "arranged", "arranger", "mixed", "mixer", "mastered",
        "mastering", "recorded", "recording", "engineered", "vocals", "vocal", "guitars",
        "guitar", "bass", "drums", "drum", "piano", "keys", "keyboards", "strings",
        "synthesizer", "synth", "programming", "executive producer", "co-producer",
        "label", "publisher", "published", "copyright", "performed", "sung", "artwork",
        "design", "photography", "director", "video director", "recording engineer",
        "mixing engineer", "mastering engineer", "vocal producer", "background vocals",
        "string arrangement", "orchestration", "op", "sp",
        "lyricist", "lyricists", "songwriter", "songwriters", "songwriting",
        "composers", "arrangers", "producers",
        "chorus", "orchestra", "management", "booking", "legal", "art direction",
        "illustration", "stylist", "subtitles", "translated", "romanized", "romanization",
        "paroles", "musique", "composé", "composée", "écrit", "écrite", "produit",
        "producteur", "arrangé", "mixé", "masterisé", "interprète", "éditeur",
        "text", "musik", "komponiert", "geschrieben", "produziert", "gemixt",
        "gemastert", "gesang", "gitarre", "schlagzeug", "klavier", "verlag",
        "letra", "música", "compuesto", "escrito", "producido", "arreglado", "mezclado",
        "masterizado", "voz", "guitarra", "bajo", "batería", "editorial", "composto",
        "produzido", "arranjado", "mixado", "violão", "baixo"
    )

    /** 按长度降序拼接，短标签不会抢占长标签 */
    private fun labelPattern(labels: List<String>): String =
        labels.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }

    private val cjkPattern = labelPattern(cjkLabels)
    private val latinPattern = labelPattern(latinLabels)

    private val metadataRegex = Regex(
        "^\\s*(?:" +
            "(?:" + cjkPattern + ")\\s*(?:[:：]|\\s+by\\s+|\$)" +
            "|(?:" + cjkPattern + ")(?:工程师|工程師|工程|编写|編寫|助理|老师|老師|团队|團隊|公司|工作室|厂牌|廠牌|制作|製作)\\s*[:：]" +
            "|(?:" + latinPattern + ")\\b\\s*(?:[:：]|\\s+by\\s+|\$)" +
            "|(?:词|曲|歌)\\s*[:：]" +
            "|(?:©|℗|\\(c\\)|\\(p\\))\\s*\\d{0,4}" +
            "|(?:courtesy of|under license(?: from)?|all rights reserved)\\b" +
            "|(?:lyrics?|words?|music)\\s*(?:&|and)\\s*(?:lyrics?|words?|music)\\s+by\\b" +
            "|(?:written|composed|produced|arranged)\\b(?:\\s*(?:&|and|,)\\s*(?:written|composed|produced|arranged))*\\s+by\\b" +
            "|(?:provided to youtube by|auto-generated by youtube|released by|distributed by|licensed to|under exclusive license to)\\b" +
            // "Lyrics by: X"、"Produced by: X" 等 by 后直接跟冒号的写法
            "|(?:lyrics?|words?|music|written|composed|produced|arranged)\\s+by\\s*[:：]" +
            // 双语制作信息行：CJK 标签 + 空格 + 英文短语 + 冒号
            // 例："作曲 Composer：…"、"出品 Produced by：…"、"口琴 Harmonica：…"
            // 正常歌词不会以「CJK 词 + 空格 + 英文 + 冒号」开头，误杀风险极低
            "|[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u30ff\\uac00-\\ud7af]{1,16}[\\s\\u3000]+[A-Za-z][A-Za-z0-9 ()\\/&.,'’\\-]*[:：]" +
            ")",
        RegexOption.IGNORE_CASE
    )

    private val instrumentalRegex = Regex(
        "^(?:(?:纯音乐|純音樂)(?:[，,、\\s]*(?:请欣赏|請欣賞))?|" +
            "请欣赏|請欣賞|暂无歌词|暫無歌詞|无歌词|無歌詞|" +
            "instrumental(?:\\s+music)?|this (?:song|track) is (?:an )?instrumental|" +
            "此歌曲为没有填词的纯音乐|此歌曲為沒有填詞的純音樂)" +
            "[\\s，,。.!！~～·…]*\$",
        RegexOption.IGNORE_CASE
    )

    private val decorativeRegex = Regex(
        "^[\\s♪♫♬~～\\-—…·.。,，、!！?？;；:：'\"“”‘’()（）\\[\\]【】]*\$"
    )

    /** 单行是否为版权/制作信息（或纯音乐、装饰行） */
    fun isMetadataLine(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (decorativeRegex.matches(value)) return true
        if (instrumentalRegex.matches(value)) return true
        return metadataRegex.containsMatchIn(value)
    }

    /**
     * 过滤歌词行；过滤后为空时保留原列表，避免歌词整体消失。
     */
    fun filter(lines: List<LyricsLine>): List<LyricsLine> {
        if (lines.isEmpty()) return lines
        val kept = lines.filterNot { isMetadataLine(it.pureMainText) }
        return if (kept.isEmpty()) lines else kept
    }
}
