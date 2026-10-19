package com.lvjian.app.data

/** 对话输入草稿：图片只加入草稿，必须由用户显式点击发送。 */
data class ChatDraft(
    val text: String = "",
    val images: List<String> = emptyList()
) {
    fun addImages(values: List<String>): ChatDraft = copy(images = (images + values).distinct())
    fun removeImage(uri: String): ChatDraft = copy(images = images - uri)
    fun canSend(sending: Boolean): Boolean = !sending && (text.isNotBlank() || images.isNotEmpty())
}
