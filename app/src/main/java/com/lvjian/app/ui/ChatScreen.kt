// 对话屏（底部第五栏）：蛋黄 —— 应用内 AI 助手
// 能力：闲聊（走设置页的 OpenAI 兼容配置）；发课程表/安排 → 本地解析直接排进「计划」页；
// 未配置 API 时发送会弹出预设画面（ai-setup 弹层）；支持发图片（相册/拍照/文件）
package com.lvjian.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typingDots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "typingDot$index"
            )
            Box(
                Modifier
                    .size(5.dp)
                    .alpha(alpha)
                    .background(LvMuted, CircleShape)
            )
        }
    }
}
@Composable
fun ChatScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val msgs by vm.chatMsgs.collectAsStateWithLifecycle()
    val sending by vm.chatSending.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var pendingImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 收发消息后滚到底
    LaunchedEffect(msgs.size, sending) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size)
    }

    // 进对话页先过一次跨天检查：App 一直开着跨过零点的话，靠这里立刻换成新的一天
    LaunchedEffect(Unit) { vm.checkChatDay() }

    // 图片选择（相册）
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8)
    ) { uris: List<Uri> ->
        // 选图只进入草稿，不写入聊天记录，也不自动调用发送。
        pendingImages = (pendingImages + uris).distinct()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 页头
        Box(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
            PageHeader("蛋黄", blobColor = LvHighlight, onSettings = onSettings)
        }

        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            if (msgs.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BlobMascot(LvHighlight, size = 60.dp)
                        Spacer(Modifier.height(14.dp))
                        Text("我是蛋黄，可以陪你聊两句，也能帮你干活。", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            // v7.1：这几句是蛋黄**不配 API 也能干**的本地能力，写清楚别让人以为要先去配 key
                            "不用配 API 也能干这些：\n" +
                                "「今天下午打两个小时羽毛球」— 我找空档排进计划\n" +
                                "「周二下午 2 点到 4 点 大学物理」— 排成每周重复\n" +
                                "「记一下：周五前交实验报告」— 加进清单",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        // 把"记忆是怎么运作的"说清楚：省得以为它失忆，也省得以为它什么都记得
                        Text(
                            "今天的对话我会记住；过了午夜自动清空重来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LvMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            items(msgs) { m ->
                AnimatedVisibility(
                    visible = true,
                    enter = expandVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Top
                    ) + fadeIn(tween(220))
                ) {
                    ChatRow(m)
                }
            }
            // 正在输入：呼吸三点
            if (sending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BlobMascot(LvHighlight, size = 30.dp)
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(16.dp), color = LvSurface) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TypingDots()
                            }
                        }
                    }
                }
            }
        }

        // 图片草稿：先预览/删除，再编辑文字，最后由用户显式发送。
        if (pendingImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingImages.forEach { uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = "待发送图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                        )
                        Surface(
                            onClick = { pendingImages = pendingImages - uri },
                            shape = CircleShape,
                            color = LvInk,
                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("×", color = LvSurface, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // 输入条：无草稿时右侧是「＋」，有文字或图片时是「↑」发送
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("和蛋黄说点什么…", color = LvMuted) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LvFill2,
                    unfocusedContainerColor = LvFill2,
                    // 聚焦时不再换成墨黑粗描边，保持与未聚焦一致的浅描边
                    focusedBorderColor = LvFieldBorder,
                    unfocusedBorderColor = LvFieldBorder,
                    cursorColor = LvAccent
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = CircleShape,
                color = LvAccent,
                modifier = Modifier.size(44.dp),
                onClick = {
                    if (draft.isBlank() && pendingImages.isEmpty()) showAttach = true
                    else {
                        val t = draft
                        val images = pendingImages
                        draft = ""
                        pendingImages = emptyList()
                        vm.sendChat(t, images.map(Uri::toString)) { showSetup = true }
                        scope.launch { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size) }
                    }
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (draft.isBlank() && pendingImages.isEmpty()) LvIcons.Plus else LvIcons.Send,
                        contentDescription = if (draft.isBlank() && pendingImages.isEmpty()) "添加图片" else "发送",
                        tint = LvOnAccent, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // ---- AI 预设弹层（未配置时发送触发） ----
    if (showSetup) {
        AiSetupSheet(vm, onClose = { showSetup = false })
    }

    // ---- 附件弹层：相册 / 拍照 / 文件 ----
    if (showAttach) {
        LvSheet(title = "添加图片", onClose = { showAttach = false }) {
            AttachRow(LvIcons.Photo, "相册", "从相册里挑一张") {
                showAttach = false
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            AttachRow(LvIcons.Camera, "拍照", "调起相机拍一张发出去") {
                showAttach = false
                // 拍照走系统相机（TakePicture 需要预先准备 Uri，简化：用 Photo Picker 替代）
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            AttachRow(LvIcons.Doc, "文件", "从文件管理器里选") {
                showAttach = false
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 一条消息气泡（我=墨黑右对齐；蛋黄=白卡 + blob 头像） */
@Composable
private fun ChatRow(m: MainViewModel.ChatMsg) {
    val isMe = m.who == "me"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isMe) {
            BlobMascot(LvHighlight, modifier = Modifier.align(Alignment.Top).padding(top = 2.dp), size = 30.dp)
            Spacer(Modifier.width(8.dp))
        }
        if (m.imageUri != null) {
            AsyncImage(
                model = m.imageUri,
                contentDescription = "发送的图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        } else {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isMe) 16.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 16.dp
                ),
                color = if (isMe) LvAccent else LvSurface
            ) {
                Text(
                    m.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isMe) LvOnAccent else LvInk,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, meta: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = LvFill,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = LvInk, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(meta, style = MaterialTheme.typography.bodySmall)
            }
            Icon(LvIcons.Right, contentDescription = null, tint = LvMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** AI 预设弹层（与设置页同一组配置，保存后蛋黄即可上线） */
@Composable
private fun AiSetupSheet(vm: MainViewModel, onClose: () -> Unit) {
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    var baseUrl by remember { mutableStateOf(aiConfig.baseUrl) }
    var apiKey by remember { mutableStateOf(aiConfig.apiKey) }
    var model by remember { mutableStateOf(aiConfig.model) }

    LvSheet(
        title = "先给蛋黄预设 API",
        onClose = onClose,
        note = "OpenAI 兼容协议，与蛋黄对话的内容会发往该接口；不填也能随时在「设置 → API 设置」里补。",
        actions = {
            Spacer(Modifier.weight(1f))
            LvSecondaryButton("稍后再说", onClose)
            LvPrimaryButton(
                "保存",
                onClick = {
                    if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
                        vm.showToast("三项都要填，蛋黄才能开工")
                        return@LvPrimaryButton
                    }
                    vm.saveAiConfig(aiConfig.copy(baseUrl = baseUrl, apiKey = apiKey, model = model))
                    vm.showToast("已保存，蛋黄上线啦")
                    onClose()
                },
            )
        }
    ) {
        FieldLabel("Base URL")
        LvInput(baseUrl, { baseUrl = it }, "https://api.openai.com/v1")
        Spacer(Modifier.height(8.dp))
        FieldLabel("API Key")
        LvInput(apiKey, { apiKey = it }, "sk-...", password = true)
        Spacer(Modifier.height(8.dp))
        FieldLabel("模型名")
        LvInput(model, { model = it }, "gpt-4o-mini / deepseek-chat")
        Spacer(Modifier.height(14.dp))
    }
}
