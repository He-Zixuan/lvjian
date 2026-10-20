// 共享 UI 组件：卡片、页头（齿轮 + blob 吉祥物）、分段控件、开关、toast、弹层骨架、
// 跟手滑动行（右滑划去/左滑删除）、时间格式化
// 视觉对齐 web-preview/preview.css v4「IP 趣味」皮肤（含深色翻转）
package com.lvjian.app.ui.components

import android.view.WindowManager
import android.view.Window
import android.view.ViewTreeObserver
import android.graphics.Rect
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lvjian.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** 统一卡片：22dp 大圆角、无描边无投影（IP 风平涂分层） */
@Composable
fun LvCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = LvSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        // 左右 17dp / 上下 18dp —— 对齐 preview.css .surface-pad{padding:18px 17px}

        Column(modifier = Modifier.padding(horizontal = 17.dp, vertical = 18.dp), content = content)
    }
}

/** 整卡列表容器：padding 收窄为纵向，行间用 LvDivider（对齐预览 .surface 发卡） */
@Composable
fun LvListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = LvSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        // 左右 17dp 对齐 .surface-pad；纵向收窄为 6dp，行间用 LvDivider
        Column(modifier = Modifier.padding(horizontal = 17.dp, vertical = 6.dp), content = content)
    }
}

/* ================= 蛋黄（blob 吉祥物） =================
 * 待机动作对齐 preview.css：
 *  - 未点醒前完全安静；点第一次 = 压扁回弹（blob-tap .5s）+ 快眨一次眼，之后进入「醒着」状态
 *  - 醒着后：眨眼（7s 周期，94%~96% 相位闭眼）+ 左右瞥视（11s 周期）
 *  - 冒泡说话：由 MainScaffold 调度（点醒后 4~6s 第一句，之后每 18~35s 一句），
 *    文本写进 BlobState.sayText，页头 blob 旁边浮出气泡；说话时换「>.<」兴奋眼
 */
object BlobState {
    /** 是否被点醒过（全应用共享：换页后蛋黄记得自己醒着） */
    val awake = MutableStateFlow(false)
    /** 当前冒泡文案；null = 不显示 */
    val sayText = MutableStateFlow<String?>(null)
}

/** 冒泡文案库（对齐 BLOB_MOODS 的 excited 组） */
val BLOB_SAY_LINES = listOf("见到你真好", "太好啦", "辛苦啦", "心情不错哦", "今天也要开心呀", "嗯～今天很舒服")

/**
 * blob 吉祥物：不规则圆团 + 豆豆眼 + 笑嘴。
 * 说话时不换眼睛，只把笑嘴放大（对齐预览端 blob 的 .fx-on）——
 * 与预览端同一条教训：换元素会剪断眼睛的眨眼动画链，动画播完会闪一下。
 * 纯装饰；点击唤醒 + 弹一下。颜色随页面分（清单绿/规则蓝/监控黄/计划粉/对话蛋黄）。
 */
@Composable
fun BlobMascot(color: Color, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val awake by BlobState.awake.collectAsState()
    val saying by BlobState.sayText.collectAsState()

    // 身体缩放（点按回弹用）；命名避开 GraphicsLayerScope 的同名属性
    val bodyScaleX = remember { Animatable(1f) }
    val bodyScaleY = remember { Animatable(1f) }
    // 眼睛纵向缩放（眨眼用）
    val eyeScaleY = remember { Animatable(1f) }
    // 脸部横向偏移（瞥视用，单位 px 由 draw 时按尺寸比例换算）
    val glanceX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 说话时笑嘴的放大进度 0→1（对齐预览端 .fx-on 的 width/height .18s 过渡）。
    // 必须放在 Canvas 外面：它是 @Composable，而 Canvas 的绘制 lambda 不是 @Composable 上下文
    val smile by animateFloatAsState(
        targetValue = if (saying != null) 1f else 0f,
        animationSpec = tween(180),
        label = "blob-smile"
    )

    // 点醒后的待机循环：眨眼 7s 一次、瞥视 11s 一次（错开周期，"时不时动一下"而非机械感）
    LaunchedEffect(awake) {
        if (!awake) return@LaunchedEffect
        launch {
            while (true) {
                // preview-v7: blink is a 7s phase, with a brief closed-eye plateau.
                delay(6580)
                eyeScaleY.animateTo(0.12f, tween(110))
                delay(140)
                eyeScaleY.animateTo(1f, tween(170))
                delay(0)
            }
        }
        launch {
            while (true) {
                // preview-v7: glance uses an 11s phase and holds each direction.
                delay(6380)
                glanceX.animateTo(1.6f, tween(240))
                delay(1210)
                glanceX.animateTo(-1.6f, tween(320))
                delay(1100)
                glanceX.animateTo(0f, tween(240))
                delay(1510)
            }
        }
    }

    Canvas(
        modifier
            .size(width = size, height = size * 42f / 46f)
            .graphicsLayer {
                rotationZ = -4f
                scaleX = bodyScaleX.value
                scaleY = bodyScaleY.value
            }
            .clip(RoundedCornerShape(52))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // 第一次点 = 点醒；每次点都播「压扁回弹 + 快眨」
                if (!BlobState.awake.value) BlobState.awake.value = true
                scope.launch {
                    // blob-tap：30% 压扁 → 60% 回弹过头 → 100% 复原（.5s）
                    launch { bodyScaleX.animateTo(0.82f, tween(150)); bodyScaleX.animateTo(1.09f, tween(150)); bodyScaleX.animateTo(1f, tween(200)) }
                    launch { bodyScaleY.animateTo(0.86f, tween(150)); bodyScaleY.animateTo(1.05f, tween(150)); bodyScaleY.animateTo(1f, tween(200)) }
                    launch { eyeScaleY.animateTo(0.1f, tween(200)); eyeScaleY.animateTo(1f, tween(300)) }
                }
            }
    ) {
        val w = size.toPx()
        val h = size.toPx()
        // 不规则圆团（四个方向半径略有差异，模拟手绘 blob）
        val blob = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            cubicTo(w * 0.86f, h * 0.02f, w * 0.99f, h * 0.32f, w * 0.96f, h * 0.58f)
            cubicTo(w * 0.93f, h * 0.86f, w * 0.72f, h * 0.99f, w * 0.44f, h * 0.96f)
            cubicTo(w * 0.16f, h * 0.93f, w * 0.02f, h * 0.72f, w * 0.05f, h * 0.44f)
            cubicTo(w * 0.08f, h * 0.18f, w * 0.20f, h * 0.06f, w * 0.5f, h * 0.04f)
            close()
        }
        drawPath(blob, color)

        val face = LvLightPalette.ink // 深色底上五官也保持墨黑（对齐 CSS）
        val gx = glanceX.value / 46f * w // 瞥视偏移（CSS 里是绝对 px，这里按比例换算）
        val eyeY = h * 0.42f
        // 豆豆眼（眨眼 = 纵向压扁）。说话时眼睛保持豆豆眼不动，
        // 和默认脸共用同一双眼睛——只让嘴变化（对齐预览端 .fx-on 的思路）
        val eyeRx = w * 0.045f
        val eyeRy = h * 0.075f * eyeScaleY.value
        drawOval(face, topLeft = Offset(w * 0.38f - eyeRx + gx, eyeY - eyeRy), size = Size(eyeRx * 2, eyeRy * 2))
        drawOval(face, topLeft = Offset(w * 0.62f - eyeRx + gx, eyeY - eyeRy), size = Size(eyeRx * 2, eyeRy * 2))

        // 说话时笑嘴放大：宽 0.24w→0.30w、高 0.22h→0.26h，由 smile 进度插值，
        // 让"笑得更开"是长出来的而不是瞬间跳大。
        // topLeft.x 随宽度左移同样的量（0.03w），保证嘴的中心点始终压在脸部中线上。
        drawArc(
            color = face,
            startAngle = 15f, sweepAngle = 150f, useCenter = false,
            topLeft = Offset(w * (0.38f - 0.03f * smile) + gx, h * 0.42f),
            size = Size(w * (0.24f + 0.06f * smile), h * (0.22f + 0.04f * smile)),
            style = Stroke(width = w * 0.035f, cap = StrokeCap.Round)
        )
    }
}

/** 蛋黄冒泡：气泡浮在 blob 左侧，3.2s 由调度方收起（对齐 #blob-say） */
@Composable
fun BlobSayBubble(text: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = LvElev,
        shadowElevation = 6.dp
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = LvInk,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** 页面头部：大标题 + 右侧齿轮（进设置）+ blob 吉祥物（含冒泡气泡） */
@Composable
fun PageHeader(
    title: String,
    blobColor: Color,
    onSettings: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    titleActions: (@Composable RowScope.() -> Unit)? = null
) {
    val sayText by BlobState.sayText.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            titleActions?.invoke(this)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions?.invoke(this)
            IconButton(onClick = onSettings) {
                Icon(LvIcons.Gear, contentDescription = "设置", tint = LvInk, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(2.dp))
            // 冒泡：说话时浮在 blob 左边
            androidx.compose.animation.AnimatedVisibility(visible = sayText != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BlobSayBubble(sayText ?: "")
                    Spacer(Modifier.width(8.dp))
                }
            }
            BlobMascot(blobColor)
        }
    }
}

/** 子页头部：返回键 + 大标题（对齐 .sub-heading） */
@Composable
fun SubHeader(title: String, onBack: () -> Unit, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(LvIcons.Back, contentDescription = "返回", tint = LvMuted, modifier = Modifier.size(22.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontSize = 24.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        action?.invoke(this)
    }
}

/**
 * 分段选择：浅轨道 + 一条墨色胶囊滑到选中项（0.42s）。
 * 选中项的文字是奶油色；文字末尾的数字单独用蛋黄色（深色主题下改回奶油色，否则浅底上看不清）。
 * 对齐预览 .segmented / .seg-ghost。
 */
@Composable
fun SegmentedPill(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val gap = 2.dp
    val inset = 4.dp
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(LvFill)
            .padding(inset)
    ) {
        val slot = (maxWidth - gap * (options.size - 1)) / options.size
        val x by animateDpAsState(
            targetValue = (slot + gap) * selected,
            animationSpec = tween(420, easing = FastOutSlowInEasing),
            label = "seg"
        )
        Box(
            Modifier
                .offset(x = x)
                .width(slot)
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(LvAccent)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val cut = label.indexOfLast { it == ' ' }.let { if (it > 0 && label.substring(it + 1).all(Char::isDigit)) it else -1 }
                val name = if (cut > 0) label.substring(0, cut) else label
                val count = if (cut > 0) label.substring(cut) else ""
                Box(
                    Modifier
                        .width(slot)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = if (on) LvOnAccent else LvMuted)) { append(name) }
                            if (count.isNotEmpty()) {
                                val countColor = if (!on) LvMuted else if (LvAccent == LvLightPalette.accent) LvHighlight else LvOnAccent
                                withStyle(SpanStyle(color = countColor)) { append(count) }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(100),
        label = "button-press"
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 主按钮：墨黑实心胶囊（深色翻转为奶油底） */
@Composable
fun LvPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = LvAccent,
            contentColor = LvOnAccent,
            disabledContainerColor = LvFill,
            disabledContentColor = LvMuted
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
        modifier = modifier.heightIn(min = 50.dp).pressScale(interactionSource)
    ) { Text(text, color = LvOnAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

/** 次按钮：浅填充底（对齐 .secondary-button） */
@Composable
fun LvSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(50),
        color = LvFill,
        modifier = modifier.pressScale(interactionSource)
    ) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = LvInk, maxLines = 1, softWrap = false)
        }
    }
}

/** 统一样式开关（墨黑轨/奶油钮；深色自动翻转） */
@Composable
fun LvSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = LvAccent,
            checkedThumbColor = LvOnAccent,
            uncheckedTrackColor = LvFill,
            uncheckedThumbColor = LvMuted,
            uncheckedBorderColor = LvFieldBorder
        )
    )
}

/** 卡内分隔线（极浅，对齐 --hairline） */
@Composable
fun LvDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = LvLine, thickness = 1.dp)
}

/* ================= 跟手滑动行 =================
 * 对齐 preview.js 滑动手势：
 *  - 跟手：内容随手指横移（detectHorizontalDragGestures，纵向滚动不冲突）
 *  - 过阈值（90px）松手 → 整行滑出屏幕（240ms）后执行动作；未过阈值回弹（240ms）
 *  - 方向锁定：清单只许右滑（划去），规则只许左滑（删除）
 */
enum class SwipeDir { RIGHT, LEFT, NONE }

@Composable
fun SwipeActionRow(
    direction: SwipeDir,
    thresholdDp: Dp = 90.dp,
    onTrigger: () -> Unit,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableStateOf(1f) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { thresholdDp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .onGloballyPositioned { widthPx = it.size.width.toFloat().coerceAtLeast(1f) }
    ) {
        // 底层动作区（滑动时露出）
        Box(Modifier.matchParentSize()) { background() }
        // 前景内容（跟手横移）
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .background(LvSurface)
                .pointerInput(direction) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val dx = offsetX.value
                            val passed = kotlin.math.abs(dx) > thresholdPx
                            scope.launch {
                                if (passed) {
                                    // 滑出屏幕再执行（对齐 .24s 滑出后重渲染）
                                    offsetX.animateTo(
                                        if (dx > 0) widthPx * 1.1f else -widthPx * 1.1f,
                                        tween(240)
                                    )
                                    onTrigger()
                                    // 行通常会随数据流消失；若还在（如撤销回来），归零备用
                                    offsetX.snapTo(0f)
                                } else {
                                    // 未过阈值：回弹
                                    offsetX.animateTo(0f, tween(240))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(240)) } }
                    ) { change, dragAmount ->
                        change.consume()
                        val cur = offsetX.value
                        // 方向锁定：反方向归零
                        val next = when (direction) {
                            SwipeDir.NONE -> 0f
                            SwipeDir.RIGHT -> (cur + dragAmount).coerceAtLeast(0f)
                            SwipeDir.LEFT -> (cur + dragAmount).coerceAtMost(0f)
                        }
                        scope.launch { offsetX.snapTo(next) }
                    }
                }
        ) { content() }
    }
}

/**
 * 轻提示 toast：墨黑胶囊 + 蛋黄动作按钮，4 秒自动消失。
 * 由 MainScaffold 统一挂在内容底部。
 */
@Composable
fun LvToastHost(toast: MainViewModelToast?, onDismiss: () -> Unit) {
    var retainedToast by remember { mutableStateOf(toast) }
    val visibility = remember { MutableTransitionState(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    SideEffect {
        if (toast != null) retainedToast = toast
        visibility.targetState = toast != null
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState, toast) {
        if (visibility.isIdle && !visibility.currentState && toast == null) retainedToast = null
    }
    // 每条 toast 4 秒自消（id 变化重置计时）
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            delay(4000)
            dismiss()
        }
    }
    val offsetPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedVisibility(
        visibleState = visibility,
        enter = slideInVertically(tween(250)) { offsetPx } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(180)) { offsetPx } + fadeOut(tween(180))
    ) {
        val displayedToast = toast ?: retainedToast ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(50),
            color = LvToastBg,
            shadowElevation = 8.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Text(displayedToast.text, style = MaterialTheme.typography.bodyMedium, color = LvToastInk)
                if (displayedToast.actionLabel != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        displayedToast.actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = LvToastAct,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = toast != null) {
                                dismiss()
                                displayedToast.onAction?.invoke()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** toast 数据（由 MainViewModel 产出，LvToastHost 消费） */
data class MainViewModelToast(
    val id: Long,
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

/**
 * 底部弹层：遮罩 + 白卡片从底部滑上来（0.3s，对齐预览 .sheet 的 sheet-in）。
 * 不用系统 ModalBottomSheet：那一层在这台手机上要等将近 4 秒才开始动。
 * 表单自己滚，操作区留在卡片底部，并避开系统栏和软键盘。
 */
@Composable
fun LvSheet(
    title: String,
    onClose: () -> Unit,
    note: String? = null,
    backIcon: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf(false) }
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(Unit) { visibility.targetState = true }
    LaunchedEffect(closing, visibility.isIdle, visibility.currentState) {
        if (closing && visibility.isIdle && !visibility.currentState) close()
    }
    val requestClose: () -> Unit = {
        closing = true
        visibility.targetState = false
    }
    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
        LaunchedEffect(dialogWindow) {
            // 系统主题默认为 adjustPan，底部操作区不会随键盘抬起。
            // 交由窗口缩小可用高度，滚动表单让出空间，保留操作区。
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val dialogPadding = rememberDialogPadding(dialogWindow)
        Box(Modifier.fillMaxSize().padding(dialogPadding)) {
            AnimatedVisibility(
                visible = visibility.targetState,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180))
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LvScrim)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = requestClose
                        )
                )
            }
            AnimatedVisibility(
                visibleState = visibility,
                enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp))
                        .background(LvElev)
                ) {
                    Box(
                        Modifier
                            .padding(top = 10.dp)
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(LvMuted.copy(alpha = 0.35f))
                    )
                    Column(Modifier.padding(horizontal = 22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = requestClose) {
                                Icon(
                                    if (backIcon) LvIcons.Back else LvIcons.Close,
                                    contentDescription = if (backIcon) "返回" else "关闭",
                                    tint = LvMuted, modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        if (note != null) {
                            Text(note, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp)
                            .padding(bottom = 4.dp),
                        content = content
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (actions != null) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                content = actions
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dialog 的系统栏/键盘尺寸直接从窗口读取，避免厂商窗口未向 Compose 转发 IME insets。 */
@Composable
private fun rememberDialogPadding(window: Window): PaddingValues {
    val density = LocalDensity.current
    var insets by remember(window) { mutableStateOf(Insets.NONE) }
    DisposableEffect(window) {
        val decor = window.decorView
        val visibleFrame = Rect()
        val location = IntArray(2)
        fun update(current: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(decor)) {
            val bars = current?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                ?: Insets.NONE
            val ime = current?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            decor.getWindowVisibleDisplayFrame(visibleFrame)
            decor.getLocationOnScreen(location)
            // 厂商可能把 systemBars 和 IME inset 都报告为 0；可视区域仍包含实际遮挡。
            val frameInsets = if (!visibleFrame.isEmpty) Insets.of(
                (visibleFrame.left - location[0]).coerceIn(0, decor.width),
                (visibleFrame.top - location[1]).coerceIn(0, decor.height),
                (location[0] + decor.width - visibleFrame.right).coerceIn(0, decor.width),
                (location[1] + decor.height - visibleFrame.bottom).coerceIn(0, decor.height)
            ) else Insets.NONE
            insets = Insets.of(
                maxOf(bars.left, frameInsets.left),
                maxOf(bars.top, frameInsets.top),
                maxOf(bars.right, frameInsets.right),
                maxOf(bars.bottom, ime, frameInsets.bottom)
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, current ->
            update(current)
            current
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        ViewCompat.requestApplyInsets(decor)
        onDispose {
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            ViewCompat.setOnApplyWindowInsetsListener(decor, null)
        }
    }
    return with(density) {
        PaddingValues(start = insets.left.toDp(), top = insets.top.toDp(), end = insets.right.toDp(), bottom = insets.bottom.toDp())
    }
}

/** 弹层内表单字段标签（对齐 .field-label） */
@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LvMuted)
    Spacer(Modifier.height(6.dp))
}

/** 弹层内输入框（浅填充底 + 圆角，对齐 .input） */
@Composable
fun LvInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        placeholder = { Text(placeholder, color = LvMuted) },
        singleLine = singleLine,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = LvFill2,
            unfocusedContainerColor = LvFill2,
            // 聚焦时不再换成墨黑粗描边，保持与未聚焦一致的浅描边
            focusedBorderColor = LvFieldBorder,
            unfocusedBorderColor = LvFieldBorder,
            cursorColor = LvAccent
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** 空状态占位（对齐 .empty） */
@Composable
fun EmptyHint(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(LvIcons.Empty, contentDescription = null, tint = LvMuted, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

/** 把毫秒时间戳格式化为“今天 HH:mm / 昨天 HH:mm / MM-dd HH:mm” */
fun formatTime(ts: Long): String {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    return when {
        now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) &&
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> "今天 $time"
        now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 1 &&
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> "昨天 $time"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/** 按天分组的组标题（清单用） */
fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) &&
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> "今天"
        now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 1 &&
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> "昨天"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    }
}
