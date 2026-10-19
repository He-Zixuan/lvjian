// 内置营销特征库：打包在 assets/marketing/ 下的词表（每行一个词，# 开头为注释）
// 规则页的「过滤营销通知」就是用它做的前置过滤；命中任一关键词即视为营销消息
package com.lvjian.app.rules

import android.content.Context

object MarketingLibrary {

    /** 全部营销关键词（懒加载并缓存；assets 打包进 APK，读取不会失败） */
    @Volatile
    private var words: Set<String>? = null

    /** 加载词表（首次读 assets，之后走内存缓存） */
    fun load(context: Context): Set<String> {
        words?.let { return it }
        val set = mutableSetOf<String>()
        try {
            context.assets.list("marketing")?.forEach { file ->
                if (!file.endsWith(".txt")) return@forEach
                context.assets.open("marketing/$file").bufferedReader().forEachLine { line ->
                    val w = line.trim()
                    // 跳过空行与注释行
                    if (w.isNotEmpty() && !w.startsWith("#")) set.add(w)
                }
            }
        } catch (e: Exception) {
            // assets 理论上必然存在；真失败返回空集（规则不命中，不影响主流程）
        }
        words = set
        return set
    }

    /** 词表大小（词墙标题用） */
    fun size(context: Context): Int = load(context).size

    /** 全部词（词墙展示用） */
    fun all(context: Context): List<String> = load(context).toList()

    /** 正文是否命中任一营销关键词 */
    fun matches(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        return load(context).any { text.contains(it, ignoreCase = true) }
    }
}
