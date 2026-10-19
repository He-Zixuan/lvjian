// 时间块 新建/编辑/加入计划 弹层（对齐设计稿 sheet 'event'）
// 标题 / 地点 / 日期（自绘日历，从消息进入时可改）/ 开始结束（双排滚筒）/ 重复 / 颜色 / 删除（两段确认）
package com.lvjian.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lvjian.app.data.EventColors
import com.lvjian.app.data.PlanEventEntity
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import java.time.LocalDate
import java.time.temporal.ChronoField

/** 弹层草稿（外部构造好传入；id 非空 = 编辑已有块） */
class EventDraft(
    val id: Long? = null,
    epochDay: Long, startMin: Int, endMin: Int,
    title: String = "", place: String = "", color: String = "teal",
    repeatWeekly: Boolean = false,
    /** 从消息「加入计划」进入：日期可选，给提示文案 */
    val pickDay: Boolean = false,
    val aiPrefill: Boolean = false,
    val fromMessageId: Long? = null
) {
    var epochDay by mutableStateOf(epochDay)
    var startMin by mutableStateOf(startMin)
    var endMin by mutableStateOf(endMin)
    var title by mutableStateOf(title)
    var place by mutableStateOf(place)
    var color by mutableStateOf(color)
    var repeatWeekly by mutableStateOf(repeatWeekly)
}

private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun EventSheet(
    draft: EventDraft,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
    onInvalid: (String) -> Unit
) {
    val heading = when {
        draft.pickDay -> "加入计划"
        draft.id == null -> "新建安排"
        else -> "编辑安排"
    }
    var confirmDelete by remember { mutableStateOf(false) }
    // 时间滚筒展开状态：null=收起，"start"/"end"=展开那一列
    var timePick by remember { mutableStateOf<String?>(null) }
    // 日历展开状态（仅 pickDay）
    var datePick by remember { mutableStateOf(false) }
    var calYM by remember { mutableStateOf(LocalDate.ofEpochDay(draft.epochDay).let { it.year to it.monthValue }) }

    LvSheet(
        title = heading,
        onClose = onClose,
        note = if (draft.pickDay)
            (if (draft.aiPrefill) "AI 已根据通知内容预填，请确认或修改" else "未接入 AI，请手动安排到时间表")
        else null,
        actions = {
            if (draft.id != null && onDelete != null) {
                TextButton(onClick = {
                    if (!confirmDelete) confirmDelete = true else { onDelete(); onClose() }
                }) {
                    Text(
                        if (confirmDelete) "确认删除" else "删除",
                        color = LvDanger, style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            LvSecondaryButton("取消", onClose, modifier = Modifier.weight(1f))
            LvPrimaryButton(
                if (draft.pickDay) "加入时间表" else "保存",
                onClick = {
                    // 校验：标题必填；结束晚于开始
                    if (draft.title.isBlank()) { onInvalid("填个标题"); return@LvPrimaryButton }
                    if (draft.endMin <= draft.startMin) { onInvalid("结束时间要晚于开始时间"); return@LvPrimaryButton }
                    onSave()
                },
                modifier = Modifier.weight(1f)
            )
        }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FieldLabel("标题")
            LvInput(draft.title, { draft.title = it }, "如：高等数学 · 第一章测验")
            Spacer(Modifier.height(8.dp))
            FieldLabel("地点")
            LvInput(draft.place, { draft.place = it }, "如：汇文楼 H-05（选填）")
            Spacer(Modifier.height(8.dp))

            // 日期：pickDay 时给自绘日历；否则只读展示
            FieldLabel("日期")
            val date = LocalDate.ofEpochDay(draft.epochDay)
            val weekday = WEEKDAY_NAMES[date.get(ChronoField.DAY_OF_WEEK) - 1]
            if (draft.pickDay) {
                Surface(
                    onClick = { datePick = !datePick; timePick = null },
                    shape = RoundedCornerShape(14.dp),
                    color = LvFill2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, LvFieldBorder)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日", style = MaterialTheme.typography.bodyMedium)
                        Icon(LvIcons.ChevronDown, contentDescription = null, tint = LvMuted, modifier = Modifier.size(18.dp))
                    }
                }
                Text(weekday, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                EventPickerReveal(visible = datePick) {
                    Spacer(Modifier.height(6.dp))
                    CalendarPicker(
                        yearMonth = calYM,
                        selected = draft.epochDay,
                        minDay = LocalDate.now().with(ChronoField.DAY_OF_WEEK, 1).toEpochDay(),
                        onPickMonth = { calYM = it },
                        onPickDay = { draft.epochDay = it; datePick = false }
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp), color = LvFill
                ) {
                    Text(
                        "$weekday · ${date.monthValue}月${date.dayOfMonth}日",
                        style = MaterialTheme.typography.bodyMedium, color = LvMuted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 开始 / 结束：双排滚筒下拉
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("开始")
                    TimeField(
                        minutes = draft.startMin,
                        open = timePick == "start",
                        onToggle = { timePick = if (timePick == "start") null else "start"; datePick = false },
                        onPick = { draft.startMin = it }
                    )
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("结束")
                    TimeField(
                        minutes = draft.endMin,
                        open = timePick == "end",
                        onToggle = { timePick = if (timePick == "end") null else "end"; datePick = false },
                        onPick = { draft.endMin = it }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            FieldLabel("重复")
            SegmentedPill(
                options = listOf("仅此一次", "每周重复"),
                selected = if (draft.repeatWeekly) 1 else 0,
                onSelect = { draft.repeatWeekly = it == 1 }
            )
            Spacer(Modifier.height(8.dp))

            FieldLabel("颜色")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EventColors.light.forEach { (key, colors) ->
                    val on = draft.color == key
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(colors.first))
                            .clickable { draft.color = key },
                        contentAlignment = Alignment.Center
                    ) {
                        if (on) Icon(LvIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 时间字段：当前值 + 展开的双排滚筒（左小时 0~23，右分钟 5 分钟步进） */
@Composable
private fun TimeField(minutes: Int, open: Boolean, onToggle: () -> Unit, onPick: (Int) -> Unit) {
    Column {
        Surface(
            onClick = onToggle,
            shape = RoundedCornerShape(14.dp),
            color = LvFill2,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (open) LvAccent else LvFieldBorder)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(MainViewModel.fmtMin(minutes), style = MaterialTheme.typography.bodyMedium)
                Icon(LvIcons.ChevronDown, contentDescription = null, tint = LvMuted, modifier = Modifier.size(18.dp))
            }
        }
        EventPickerReveal(visible = open) {
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = LvElev,
                border = androidx.compose.foundation.BorderStroke(1.dp, LvFieldBorder)
            ) {
                Row(Modifier.height(180.dp).padding(6.dp)) {
                    val curH = minutes / 60
                    val curM = minutes % 60
                    TimeWheel(
                        values = (0 until 24).toList(),
                        selected = curH,
                        label = { "%02d".format(it) },
                        modifier = Modifier.weight(1f),
                        onPick = { onPick(it * 60 + curM) }
                    )
                    TimeWheel(
                        values = (0 until 12).map { it * 5 },
                        selected = curM,
                        label = { "%02d".format(it) },
                        modifier = Modifier.weight(1f),
                        onPick = { onPick(curH * 60 + it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EventPickerReveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val offset = with(LocalDensity.current) { (-4).dp.roundToPx() }
    val easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180, easing = easing)) +
            slideInVertically(animationSpec = tween(180, easing = easing), initialOffsetY = { offset }),
        exit = fadeOut(animationSpec = tween(180, easing = easing)) +
            slideOutVertically(animationSpec = tween(180, easing = easing), targetOffsetY = { offset })
    ) {
        Column(content = content)
    }
}

@Composable
private fun TimeWheel(
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    modifier: Modifier,
    onPick: (Int) -> Unit
) {
    val selectedIndex = values.indexOf(selected).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(selected) {
        val index = values.indexOf(selected)
        if (index >= 0 && !state.isScrollInProgress) state.animateScrollToItem(index)
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxHeight(),
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = 60.dp)
    ) {
        items(values.size) { index ->
            val value = values[index]
            TimeOption(label(value), value == selected) { onPick(value) }
        }
    }
}

@Composable
private fun TimeOption(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (active) LvAccent else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = if (active) LvOnAccent else LvInk)
        }
    }
}

/** 自绘日历弹层（周一开头；选中=墨黑底，今天=蛋黄描边；早于本周一的禁用） */
@Composable
private fun CalendarPicker(
    yearMonth: Pair<Int, Int>,
    selected: Long,
    minDay: Long,
    onPickMonth: (Pair<Int, Int>) -> Unit,
    onPickDay: (Long) -> Unit
) {
    val (y, m) = yearMonth
    val first = LocalDate.of(y, m, 1)
    val daysInMonth = first.lengthOfMonth()
    val lead = (first.dayOfWeek.value + 6) % 7 + 1 - 1 // 1 号前空几格（周一为首）
    val today = LocalDate.now().toEpochDay()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = LvElev,
        border = androidx.compose.foundation.BorderStroke(1.dp, LvFieldBorder)
    ) {
        Column(Modifier.padding(10.dp)) {
            // 翻月头
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val nm = if (m == 1) y - 1 to 12 else y to m - 1
                    onPickMonth(nm)
                }) { Icon(LvIcons.Back, contentDescription = "上个月", tint = LvMuted, modifier = Modifier.size(18.dp)) }
                Text(
                    "${y}年${m}月",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = {
                    val nm = if (m == 12) y + 1 to 1 else y to m + 1
                    onPickMonth(nm)
                }) { Icon(LvIcons.Right, contentDescription = "下个月", tint = LvMuted, modifier = Modifier.size(18.dp)) }
            }
            // 星期表头
            Row {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = LvMuted,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            // 日期网格
            val totalCells = lead + daysInMonth
            val rows = (totalCells + 6) / 7
            for (r in 0 until rows) {
                Row {
                    for (c in 0 until 7) {
                        val idx = r * 7 + c
                        val dayNum = idx - lead + 1
                        if (dayNum < 1 || dayNum > daysInMonth) {
                            Spacer(Modifier.weight(1f))
                            continue
                        }
                        val d = LocalDate.of(y, m, dayNum)
                        val epoch = d.toEpochDay()
                        val disabled = epoch < minDay
                        val isSel = epoch == selected
                        val isToday = epoch == today
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(if (isSel) LvAccent else Color.Transparent)
                                .then(
                                    if (isToday && !isSel) Modifier.background(Color.Transparent) else Modifier
                                )
                                .clickable(enabled = !disabled) { onPickDay(epoch) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$dayNum",
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    isSel -> LvOnAccent
                                    disabled -> LvMuted.copy(alpha = 0.4f)
                                    isToday -> LvAccent
                                    else -> LvInk
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
