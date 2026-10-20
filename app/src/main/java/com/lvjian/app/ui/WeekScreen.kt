// 计划屏（底部第四栏）：周时间表 —— 周切换 / 横向缩放 / 点空白新建 / 点块编辑 / 分享导出
// 布局口径对齐 preview.js renderWeek：默认可视 08:00~22:00（块超界自动整小时扩展）、
// 每小时 64dp、同屏 >4 天收起时间轴、今天列浅底高亮（仅本周）
package com.lvjian.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.PlanEventEntity
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import java.time.LocalDate
import java.time.temporal.ChronoField

private val WEEKDAY_FULL = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private const val MAX_WEEK_OFFSET = 52        // 最多往后翻 52 周（约一年）
private val HOUR_DP = 64.dp                   // 每小时高度
private val GUTTER_DP = 40.dp                 // 左侧时间轴。七天都在屏里，列不再被裁成一条。
private val DAY_START = 8 * 60                // 默认可视起点 08:00
private val DAY_END = 22 * 60                 // 默认终点 22:00

@Composable
fun WeekScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val events by vm.events.collectAsStateWithLifecycle()
    var weekOffset by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf<EventDraft?>(null) }
    var showShare by remember { mutableStateOf(false) }

    // 本周一（offset 0 的基准）
    val monday = LocalDate.now().with(ChronoField.DAY_OF_WEEK, 1)
    var daysVisible by remember { mutableFloatStateOf(4f) }
    val weekMonday = monday.plusWeeks(weekOffset.toLong())
    val weekDates = (0..6).map { weekMonday.plusDays(it.toLong()) }
    val today = LocalDate.now()
    // 今天不加黑底。列上只留很浅的底，方便认出今天，不把星期两个字衬成白字。
    val todayIdx = if (weekOffset == 0) today.get(ChronoField.DAY_OF_WEEK) - 1 else -1

    // 当前周可见的块：每周重复的永远显示；单次块只在自己那一周
    val visible = events.filter { ev ->
        if (ev.repeatWeekly) true
        else {
            val d = LocalDate.ofEpochDay(ev.epochDay)
            !d.isBefore(weekMonday) && d.isBefore(weekMonday.plusDays(7))
        }
    }

    // 可视范围：默认 08:00~22:00，有块超出时向两端整小时扩展
    var vStart = DAY_START
    var vEnd = DAY_END
    visible.forEach {
        vStart = minOf(vStart, (it.startMin / 60) * 60)
        vEnd = maxOf(vEnd, ((it.endMin + 59) / 60) * 60)
    }
    vStart = vStart.coerceAtLeast(0)
    vEnd = vEnd.coerceAtMost(24 * 60)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 页头：标题 + 分享 + 齿轮 + 粉 blob
        PageHeader(
            if (weekOffset == 0) "本周" else "第${weekOffset + 1}周",
            blobColor = LvHighlight,
            onSettings = onSettings,
            titleActions = {
                IconButton(onClick = { weekOffset-- }, enabled = weekOffset > 0) {
                    Icon(LvIcons.Back, contentDescription = "上一周", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { weekOffset++ }, enabled = weekOffset < MAX_WEEK_OFFSET) {
                    Icon(LvIcons.Right, contentDescription = "下一周", modifier = Modifier.size(20.dp))
                }
            },
            actions = {
                IconButton(onClick = { showShare = true }) {
                    Icon(LvIcons.Share, contentDescription = "分享计划表", tint = LvInk, modifier = Modifier.size(22.dp))
                }
            }
        )

        // 横向滚动只查看本周各天；换周只通过标题旁的箭头。
        Box(Modifier.weight(1f).clip(RoundedCornerShape(0.dp))) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val availW = maxWidth
                val gutterW = GUTTER_DP
                val dayW = (availW - gutterW) / daysVisible
                val density = LocalDensity.current
                val lineColor = LvLine
                val hScroll = rememberScrollState()
                val timeScroll = rememberScrollState()
                Row(Modifier.fillMaxSize().verticalScroll(timeScroll)) {
                    Column(Modifier.width(gutterW)) {
                        Spacer(Modifier.height(52.dp))
                        (DAY_START until DAY_END step 60).forEach { h ->
                            Text(MainViewModel.fmtMin(h), fontSize = 12.sp, color = LvMuted, modifier = Modifier.height(HOUR_DP).padding(start = 4.dp, top = 2.dp))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                    Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) daysVisible = (daysVisible / zoom).coerceIn(1.5f, 7f)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                ) {
                    Row(Modifier.horizontalScroll(hScroll)) {
                        WeekPage(weekOffset, events, monday, gutterW, dayW, density, lineColor, onBlank = { day, start, epoch ->
                            draft = EventDraft(epochDay = epoch, startMin = start, endMin = minOf(start + 60, 24 * 60))
                        }, onBlock = { ev ->
                            draft = EventDraft(id = ev.id, epochDay = ev.epochDay, startMin = ev.startMin, endMin = ev.endMin, title = ev.title, place = ev.place, color = ev.color, repeatWeekly = ev.repeatWeekly)
                        })
                    }
                    }
                    }
                }
            }
        }

    // ---- 新建/编辑弹层 ----
    draft?.let { d ->
        EventSheet(
            draft = d,
            onSave = {
                if (d.id == null) {
                    vm.addEvent(
                        PlanEventEntity(
                            epochDay = d.epochDay, startMin = d.startMin, endMin = d.endMin,
                            title = d.title.trim(), place = d.place.trim(),
                            color = d.color, repeatWeekly = d.repeatWeekly
                        )
                    )
                } else {
                    vm.updateEvent(
                        PlanEventEntity(
                            id = d.id, epochDay = d.epochDay, startMin = d.startMin, endMin = d.endMin,
                            title = d.title.trim(), place = d.place.trim(),
                            color = d.color, repeatWeekly = d.repeatWeekly
                        )
                    )
                }
                vm.showToast("已保存")
                draft = null
            },
            onDelete = d.id?.let { id ->
                {
                    events.firstOrNull { it.id == id }?.let { vm.deleteEvent(it) }
                    Unit
                }
            },
            onClose = { draft = null },
            onInvalid = { vm.showToast(it) }
        )
    }

    // ---- 分享弹层 ----
    if (showShare) {
        ShareWeekSheet(
            vm = vm,
            weekOffset = weekOffset,
            weekMonday = weekMonday,
            events = visible,
            onClose = { showShare = false }
        )
    }
    }
}

@Composable
private fun WeekPage(
    offset: Int,
    events: List<PlanEventEntity>,
    monday: LocalDate,
    gutterW: Dp,
    dayW: Dp,
    density: androidx.compose.ui.unit.Density,
    lineColor: Color,
    modifier: Modifier = Modifier,
    onBlank: ((Int, Int, Long) -> Unit)? = null,
    onBlock: ((PlanEventEntity) -> Unit)? = null,
) {
    val weekMonday = monday.plusWeeks(offset.toLong())
    val dates = (0..6).map { weekMonday.plusDays(it.toLong()) }
    val todayIdx = if (offset == 0) LocalDate.now().get(ChronoField.DAY_OF_WEEK) - 1 else -1
    val visible = events.filter { ev ->
        if (ev.repeatWeekly) true
        else {
            val d = LocalDate.ofEpochDay(ev.epochDay)
            !d.isBefore(weekMonday) && d.isBefore(weekMonday.plusDays(7))
        }
    }
    var vStart = DAY_START
    var vEnd = DAY_END
    visible.forEach {
        vStart = minOf(vStart, (it.startMin / 60) * 60)
        vEnd = maxOf(vEnd, ((it.endMin + 59) / 60) * 60)
    }
    vStart = vStart.coerceAtLeast(0)
    vEnd = vEnd.coerceAtMost(24 * 60)
    val totalH = HOUR_DP * ((vEnd - vStart) / 60f)
    val vScroll = rememberScrollState()
    Column(modifier.width(dayW * 7)) {
        Row(Modifier.width(dayW * 7)) {
            dates.forEachIndexed { i, d ->
                Column(
                    Modifier.width(dayW).drawBehind {
                        if (i > 0) drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), 1.dp.toPx())
                    }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(WEEKDAY_FULL[i], style = MaterialTheme.typography.labelLarge, color = LvInk, maxLines = 1, softWrap = false)
                    Text("${d.monthValue}/${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = LvMuted, maxLines = 1, softWrap = false)
                }
            }
        }
        Box(Modifier.width(dayW * 7).height(totalH)) {
            (vStart..vEnd step 60).forEach { h ->
                val y = HOUR_DP * ((h - vStart) / 60f)
                LvDivider(Modifier.offset(y = y))
            }
            for (d in 0..6) {
                Box(
                    Modifier.offset(x = dayW * d).width(dayW).height(totalH)
                        .background(if (d == todayIdx) LvAccent.copy(alpha = 0.05f) else Color.Transparent)
                        .drawBehind { if (d > 0) drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), 1.dp.toPx()) }
                        .pointerInput(d, offset, vStart) {
                            detectTapGestures { off ->
                                val yMin = with(density) { (off.y / HOUR_DP.toPx()) * 60f }
                                var start = vStart + (yMin / 30).toInt() * 30
                                start = start.coerceIn(vStart, vEnd - 30)
                                onBlank?.invoke(d, start, dates[d].toEpochDay())
                            }
                        }
                )
            }
            visible.forEach { ev ->
                val evDate = LocalDate.ofEpochDay(ev.epochDay)
                val dayIdx = evDate.get(ChronoField.DAY_OF_WEEK) - 1
                val colors = com.lvjian.app.data.EventColors.light[ev.color] ?: com.lvjian.app.data.EventColors.light.getValue("gray")
                val top = HOUR_DP * ((ev.startMin - vStart) / 60f) + 1.dp
                val height = HOUR_DP * ((ev.endMin - ev.startMin) / 60f) - 2.dp
                Box(
                    Modifier.offset(x = dayW * dayIdx + 3.dp, y = top).width(dayW - 7.dp).height(height)
                        .clip(RoundedCornerShape(8.dp)).background(Color(colors.second))
                        .clickable { onBlock?.invoke(ev) }
                ) {
                    Box(Modifier.fillMaxHeight().width(3.dp).background(Color(colors.first)))
                    Column(Modifier.padding(start = 9.dp, top = 6.dp, end = 5.dp)) {
                        if (ev.endMin - ev.startMin >= 50) Text("${MainViewModel.fmtMin(ev.startMin)}-${MainViewModel.fmtMin(ev.endMin)}", fontSize = 11.sp, color = Color(colors.first), maxLines = 1)
                        Text(ev.title, fontSize = 12.sp, color = LvInk, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    }
                }
            }
        }
    }
}
