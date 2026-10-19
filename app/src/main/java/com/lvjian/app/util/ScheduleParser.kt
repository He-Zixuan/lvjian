// 「课程表 → 计划」本地解析器（与网页预览 web-preview/preview-v7.js 的 parseScheduleDemo 口径一致）
// 识别「周一三五 10:00-11:30 高等数学」「周二下午2点到4点 大学物理」这类句式，排成**每周重复**。
// AI 未配置时蛋黄对话用它在本地排课（AI 配好则由模型理解任意表达）。
//
// ⚠️ 与本项目另一条链路的分工（别混）：
//   · 本文件：带「周X + 起止时刻」的**课程表**句式 → repeatWeekly = true
//   · TaskIntentParser + TaskScheduler：「今天下午打两个小时羽毛球」这类**临时安排** → 一次性
package com.lvjian.app.util

object ScheduleParser {

    data class ParsedBlock(
        val dayOfWeek: Int,  // 0=周一 … 6=周日
        val startMin: Int,
        val endMin: Int,
        val title: String
    )

    private val CN_DAY = mapOf(
        '一' to 0, '二' to 1, '三' to 2, '四' to 3, '五' to 4, '六' to 5, '日' to 6, '天' to 6
    )

    /**
     * 时刻部分要同时吃下两种写法，缺一个就会"看起来支持、其实解析不出"：
     *   · "10:00" / "2:30" → 冒号后跟分钟
     *   · "2点" / "2点30"  → 单说"点"时分钟默认 0
     * （旧版只写了 [:：点](\d{2})，要求"点"后必须跟两位数字，
     *   于是「周二下午2点到4点 大学物理」这种最常见的说法整句匹配不上。）
     *
     * 分组：1=星期几 2=起始时段词 3=起始小时 4=起始分钟(冒号) 5=起始分钟(点)
     *      6=结束时段词 7=结束小时 8=结束分钟(冒号) 9=结束分钟(点) 10=标题
     */
    private val RE = Regex(
        """周([一二三四五六日天]+)\s*(下午|晚上)?(\d{1,2})\s*(?:[:：]\s*(\d{1,2})|点\s*(\d{1,2})?)?\s*[-–~到]\s*(下午|晚上)?(\d{1,2})\s*(?:[:：]\s*(\d{1,2})|点\s*(\d{1,2})?)?\s*([^\n，,。；;]*)"""
    )

    /** 解析文本里的全部排课块；没有命中返回空列表 */
    fun parse(text: String): List<ParsedBlock> {
        val out = mutableListOf<ParsedBlock>()
        for (m in RE.findAll(text)) {
            val g = m.groupValues
            val days = g[1].mapNotNull { CN_DAY[it] }
            var sh = g[3].toIntOrNull() ?: continue
            var eh = g[7].toIntOrNull() ?: continue
            // 结束时刻没带"下午/晚上"时**继承开头的时段词**：
            // "周二下午2点到4点" 应是 14:00–16:00，不是 14:00–04:00（后者 end < start 会被下面整条丢掉）
            val startPeriod = g[2]
            val endPeriod = g[6].ifEmpty { startPeriod }
            if ((startPeriod == "下午" || startPeriod == "晚上") && sh < 12) sh += 12
            if ((endPeriod == "下午" || endPeriod == "晚上") && eh < 12) eh += 12
            val smin = (g[4].ifEmpty { g[5] }).toIntOrNull() ?: 0
            val emin = (g[8].ifEmpty { g[9] }).toIntOrNull() ?: 0
            val start = sh * 60 + smin
            val end = eh * 60 + emin
            val title = g[10].trim().ifEmpty { "课程" }
            for (day in days) {
                if (end > start && start >= 0 && end <= 24 * 60) out.add(ParsedBlock(day, start, end, title))
            }
        }
        return out
    }
}
