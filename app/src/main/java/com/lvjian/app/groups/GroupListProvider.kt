// 群聊列表来源抽象：统一“从某处拿到某应用的群名列表”的入口
//
// 背景（技术结论，勿忘）：通知监听路线下不存在“一键读取群列表”的接口——
// 应用沙箱决定我们无法主动枚举微信的群。真正的“一键读取”只有 Root + LSPosed
// 读微信数据库一条路（可行性见 docs/root-and-decryption-research.md，未通过）。
// 本轮实现截图 OCR 兜底（ScreenshotOcrProvider）；Root 路线未来实现 RootDbProvider。
package com.lvjian.app.groups

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * 群列表来源接口。
 * @return 候选群名列表（已做基础清洗）；识别失败应抛异常，由 UI 明示而非静默空列表
 */
interface GroupListProvider {
    suspend fun fetchGroups(appPackage: String): List<String>
}

/** OCR 过程可能出现的失败，UI 层按 message 展示原因与建议 */
class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 截图 OCR 导入：用户在微信「通讯录 → 群聊」截 1~N 屏，
 * 本地 ML Kit 中文模型识别文本行，清洗后作为候选群名。
 *
 * 使用方式：先 setImages() 塞入选好的截图，再调 fetchGroups()。
 * （接口签名按任务书固定为 fetchGroups(appPackage)，截图属于本次会话的输入，
 *   所以由 setImages 先行注入；RootDbProvider 未来则不需要这一步。）
 */
class ScreenshotOcrProvider(private val context: Context) : GroupListProvider {

    /** 待识别的截图（系统图片选择器返回的内容 URI） */
    private var images: List<Uri> = emptyList()

    /** 注入本批次截图；必须在 fetchGroups 之前调用 */
    fun setImages(uris: List<Uri>) {
        images = uris
    }

    // bundled 中文识别器：模型打包进 APK，国行无谷歌服务也能用
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun fetchGroups(appPackage: String): List<String> {
        if (images.isEmpty()) throw OcrException("还没有选择截图")

        val candidates = LinkedHashSet<String>() // 保序去重
        var anyText = false

        for (uri in images) {
            val image = try {
                InputImage.fromFilePath(context, uri)
            } catch (e: Exception) {
                // 单张图读不了（权限/格式）不拖垮整批：跳过并记录
                continue
            }
            val result = try {
                recognizer.process(image).await()
            } catch (e: Exception) {
                // 模型异常/下载未完成等：抛出带原因的异常，UI 提示重试
                throw OcrException("文字识别失败：${e.message ?: "未知原因"}，请重试", e)
            }
            if (result.text.isNotBlank()) anyText = true
            // 按文本行提取，逐行清洗
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    cleanLine(line.text)?.let { candidates.add(it) }
                }
            }
        }

        if (!anyText) {
            // 识别引擎正常返回但没有任何文字：多半是截图不对，明确告诉用户
            throw OcrException("截图里没有识别到文字。请确认截的是微信「通讯录 → 群聊」页面。")
        }
        return candidates.toList()
    }

    /**
     * 清洗一行 OCR 文本：
     * 去掉明显 UI 元素、右侧字母索引（A~Z、#）、过短/过长文本；无法作为群名的返回 null
     */
    private fun cleanLine(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 页面固定 UI 文案黑名单（微信“群聊”页常见元素）
        if (text in UI_NOISE) return null
        // 右侧字母索引导航：单个大写字母或 #
        if (text.length == 1 && (text[0] in 'A'..'Z' || text[0] == '#')) return null
        // 群名长度经验边界：1~30 字（过长的行多半是识别粘连）
        if (text.length > 30) return null
        return text
    }

    private companion object {
        /** 微信「通讯录 → 群聊」页的固定 UI 文案（识别出来也不是群名） */
        val UI_NOISE = setOf(
            "群聊", "搜索", "通讯录", "微信", "发现", "我",
            "没有找到群聊", "添加", "新的朋友", "仅聊天的朋友"
        )
    }
}
