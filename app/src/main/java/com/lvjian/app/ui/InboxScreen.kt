// 清单屏：分段（待处理/已过滤/未整理，带滚动计数）+ 按天分组整卡 + 右滑划去（可撤销）+ 点按展开详情
// 「已划去」不占分段位：页头齿轮左侧的无盖垃圾桶（BinOpen）图标进子页查看
// 「未整理」段：一键整理操作卡 + 整理规则入口 + 应用→群两层折叠分组（展开态仅当次会话记忆）
// 详情含：通知原文 / 来源行 / 加入计划 / 跳转到来源应用；已过滤、已划去支持恢复与两段确认清空
package com.lvjian.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.AIState
import com.lvjian.app.data.MessageEntity
import com.lvjian.app.data.MsgStatus
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import com.lvjian.app.util.AppNames
import com.lvjian.app.util.PlanGuesser
import kotlin.math.abs
import kotlin.math.roundToInt

/** 三个分段：待处理 / 已过滤 / 未整理（已划去移到页头 BinOpen 子页） */
private enum class InboxTab { PENDING, FILTERED, BATCH }

/** 滚动计数：数字不平跳，一个一个滚到目标值（差 1 条 ≈120ms，封顶 900ms） */
@Composable
private fun rollingCount(target: Int): Int {
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        if (anim.value.roundToInt() != target) {
            val dur = (120 * abs(target - anim.value.roundToInt())).coerceAtMost(900)
            anim.animateTo(target.toFloat(), tween(dur, easing = FastOutSlowInEasing))
        }
    }
    return anim.value.roundToInt()
}

/** 每轮从0开始，即使UI第一次收到的就是整批结果，也逐个展示已保存的数字。 */
@Composable
private fun savedProgressCount(target: Int, runId: Long?): Int {
    var displayed by remember(runId) { mutableIntStateOf(0) }
    LaunchedEffect(target, runId) {
        animateSavedCount(displayed, target,
            motionScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f,
            pause = { millis ->
                delay(millis)
                // 等一个实际屏幕帧，不能让所有数字在同一帧内更新后被合并。
                withFrameNanos { }
            }) { displayed = it }
    }
    return displayed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: MainViewModel,
    notifAccess: Boolean,
    onSettings: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenDone: () -> Unit
) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    val batch by vm.pendingBatch.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var section by remember { mutableStateOf(InboxTab.PENDING) }
    // 展开查看原文的行 id（一次只展开一条）
    var expandedId by remember { mutableStateOf<Long?>(null) }
    // 批量清空的两段确认（切分段复位）
    var bulkConfirm by remember { mutableStateOf(false) }
    // 「加入计划」弹层草稿（非空即打开）
    var planDraft by remember { mutableStateOf<EventDraft?>(null) }
    // 手动添加弹层
    var showAdd by remember { mutableStateOf(false) }
    // 未整理页「应用/群」折叠态（仅当次会话记忆，不持久化；key=app:/grp: 前缀分组键）
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    // 分段计数：滚动动画（未整理倒数归零、已过滤/待处理逐个加）
    val pendingCount = rollingCount(pending.size)
    val filteredCount = rollingCount(filtered.size)
    val batchCount = rollingCount(batch.size)

    // 跳转来源应用 / 加入计划的回调在未整理与待处理两段共用，提前备好
    val jumpToApp: (MessageEntity) -> Unit = { msg ->
        val launchIntent = context.packageManager.getLaunchIntentForPackage(msg.appPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent == null) {
            vm.showToast("找不到「${AppNames.label(context, msg.appPackage)}」，可能已卸载")
        } else {
            runCatching { context.startActivity(launchIntent) }
                .onFailure { vm.showToast("无法打开「${AppNames.label(context, msg.appPackage)}」") }
        }
    }
    val openPlanDraft: (MessageEntity) -> Unit = { msg ->
        val local = PlanGuesser.guess(msg.text)
        val hasAi = msg.planTitle.isNotBlank() || msg.planEpochDay > 0
        planDraft = EventDraft(
            epochDay = if (msg.planEpochDay > 0) msg.planEpochDay else local.epochDay,
            startMin = if (msg.planStartMin >= 0) msg.planStartMin else local.startMin,
            endMin = if (msg.planEndMin >= 0) msg.planEndMin else local.endMin,
            title = msg.planTitle.ifBlank {
                PlanGuesser.cleanTitle(msg.text.lines().first(), local.place).ifBlank { msg.text.take(30) }
            },
            place = msg.planPlace.ifBlank { local.place },
            pickDay = true,
            aiPrefill = hasAi,
            fromMessageId = msg.id
        )
    }

    val list = when (section) {
        InboxTab.PENDING -> pending
        InboxTab.FILTERED -> filtered
        InboxTab.BATCH -> batch
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        // 页头：大标题 + 无盖垃圾桶（已划去入口，与有盖删除桶区分）+ 齿轮 + 绿 blob
        item {
            PageHeader("待办清单", blobColor = LvHighlight, onSettings = onSettings, actions = {
                if (section == InboxTab.PENDING) {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(LvIcons.Plus, contentDescription = "自己添加一条待办", tint = LvInk, modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = onOpenDone) {
                    Icon(
                        LvIcons.BinOpen, contentDescription = "查看已划去",
                        tint = LvInk, modifier = Modifier.size(22.dp)
                    )
                }
            })
        }

        // 分段控件（带滚动计数）
        item {
            SegmentedPill(
                options = listOf(
                    "待处理 $pendingCount",
                    "已过滤 $filteredCount",
                    "未整理 $batchCount"
                ),
                selected = section.ordinal,
                onSelect = {
                    section = InboxTab.entries[it]
                    expandedId = null
                    bulkConfirm = false
                }
            )
        }

        // ================= 未整理段 =================
        if (section == InboxTab.BATCH) {
            // 一键整理操作卡 + 整理规则入口
            item { OrganizeCard(vm, batch.size, onOpenRules) }

            if (batch.isEmpty()) {
                item {
                    LvCard(Modifier.fillMaxWidth()) {
                        EmptyHint(
                            "没有未整理的消息",
                            "设置里「实时整理」关闭时，未被本地规则抓住的消息会攒到这里，点上方「一键整理」统一判定。"
                        )
                    }
                }
            } else {
                // 应用 → 群 两层折叠分组（默认全展开）
                val byApp = batch.groupBy { it.appPackage }
                for ((pkg, appMsgs) in byApp) {
                    val appKey = "app:$pkg"
                    val appCollapsed = collapsed[appKey] == true
                    // 应用组头
                    item(key = "bapp_$pkg") {
                        Column {
                        GroupHeader(
                            title = AppNames.label(LocalContext.current, pkg),
                            iconPkg = pkg,
                            collapsed = appCollapsed,
                            indent = 0,
                            onToggle = { collapsed[appKey] = !appCollapsed }
                        )
                        InboxReveal(visible = !appCollapsed, durationMillis = 420) {
                            val byGroup = appMsgs.groupBy { it.groupName }
                            for ((gname, gMsgs) in byGroup) {
                                key(gname) {
                                    val gKey = "grp:$pkg|$gname"
                                    val gCollapsed = collapsed[gKey] == true
                                    Spacer(Modifier.height(14.dp))
                                    GroupHeader(
                                        title = gname,
                                        collapsed = gCollapsed,
                                        indent = 1,
                                        onToggle = { collapsed[gKey] = !gCollapsed }
                                    )
                                    InboxReveal(visible = !gCollapsed, durationMillis = 420) {
                                        Spacer(Modifier.height(14.dp))
                                        LvListCard(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                                            gMsgs.forEachIndexed { i, msg ->
                                                if (i > 0) LvDivider()
                                                key(msg.id) {
                                                    BatchRow(
                                                        msg = msg,
                                                        expanded = expandedId == msg.id,
                                                        onToggle = { expandedId = if (expandedId == msg.id) null else msg.id },
                                                        onDone = { vm.dismissMessage(msg.id, ignored = false) },
                                                        onIgnore = { vm.dismissMessage(msg.id, ignored = true) },
                                                        onPlan = { openPlanDraft(msg) },
                                                        onJump = { jumpToApp(msg) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
            return@LazyColumn  // 未整理段渲染完毕，不走下方通用列表
        }

        // ================= 待处理 / 已过滤段 =================
        // 已过滤：批量清空（两段确认）
        if (section == InboxTab.FILTERED && list.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        if (bulkConfirm) "确认清空" else "清空",
                        style = MaterialTheme.typography.labelLarge,
                        color = LvDanger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                if (!bulkConfirm) bulkConfirm = true
                                else {
                                    bulkConfirm = false
                                    vm.clearSegment(filteredSeg = true)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (list.isEmpty()) {
            item {
                LvCard(Modifier.fillMaxWidth()) {
                    EmptyHint(
                        // 权限语义如实：未授权=收集尚未开始（权限撤销后同样走这里，消息与配置都保留）；
                        // 已授权但空 = 正常等待新通知，不判为权限失败
                        if (!notifAccess) "收集已暂停" else "这里空空的",
                        if (section == InboxTab.PENDING)
                            if (!notifAccess) "通知访问权未开启（或已被撤销），重新开启后即可继续收集；已有消息和设置都在。"
                            else "正在等待新通知。默认只收集微信、QQ、企业微信；可在「收集」页开启其他应用。原应用照常提醒。"
                        else "当前分段没有消息。"
                    )
                }
            }
        }

        // 按天分组
        val grouped = list.groupBy { dayLabel(it.timestamp) }
        for ((day, msgs) in grouped) {
            item(key = "day_${section}_$day") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = LvHighlight, modifier = Modifier.size(8.dp)) {}
                    Spacer(Modifier.width(6.dp))
                    Text(day, style = MaterialTheme.typography.bodyMedium, color = LvMuted)
                }
            }
            item(key = "card_${section}_$day") {
                LvListCard(modifier = Modifier.fillMaxWidth()) {
                    msgs.forEachIndexed { i, msg ->
                        if (i > 0) LvDivider()
                        key(msg.id) {
                            when (section) {
                                InboxTab.PENDING -> PendingRow(
                                    msg = msg,
                                    expanded = expandedId == msg.id,
                                    onToggle = { expandedId = if (expandedId == msg.id) null else msg.id },
                                    onDone = { vm.dismissMessage(msg.id, ignored = false) },
                                    onIgnore = { vm.dismissMessage(msg.id, ignored = true) },
                                    onPlan = { openPlanDraft(msg) },
                                    onJump = { jumpToApp(msg) }
                                )
                                InboxTab.FILTERED -> FilteredRow(msg, expandedId == msg.id,
                                    onToggle = { expandedId = if (expandedId == msg.id) null else msg.id },
                                    onRestore = { vm.unfilter(msg.id) })
                                InboxTab.BATCH -> {} // 上面已渲染
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 「加入计划」弹层 ----
    planDraft?.let { draft ->
        EventSheet(
            draft = draft,
            onSave = {
                vm.addEvent(
                    com.lvjian.app.data.PlanEventEntity(
                        epochDay = draft.epochDay, startMin = draft.startMin, endMin = draft.endMin,
                        title = draft.title.trim(), place = draft.place.trim(),
                        color = draft.color, repeatWeekly = draft.repeatWeekly,
                        fromMessageId = draft.fromMessageId
                    )
                ) {
                    val day = java.time.LocalDate.ofEpochDay(draft.epochDay)
                    val wd = WEEKDAY_NAMES_SHORT[day.get(java.time.temporal.ChronoField.DAY_OF_WEEK) - 1]
                    vm.showToast(
                        "已加入时间表：周$wd ${MainViewModel.fmtMin(draft.startMin)}"
                    )
                }
                planDraft = null
            },
            onDelete = null,
            onClose = { planDraft = null },
            onInvalid = { vm.showToast(it) }
        )
    }

    // ---- 手动添加弹层 ----
    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        LvSheet(
            title = "添加待办",
            onClose = { showAdd = false },
            actions = {
                Spacer(Modifier.weight(1f))
                LvSecondaryButton("取消", onClick = { showAdd = false })
                LvPrimaryButton(
                    "加入待办",
                    onClick = {
                        // v7.1 放宽：标题改选填 —— 标题、备注写一个就行，两个都空才拦
                        if (title.isBlank() && note.isBlank()) { vm.showToast("标题或备注写一个"); return@LvPrimaryButton }
                        vm.addManualMessage(title, note)
                        showAdd = false
                    },
                )
            }
        ) {
            FieldLabel("标题（选填）")
            LvInput(title, { title = it }, "如：周五前交实验报告")
            Spacer(Modifier.height(8.dp))
            FieldLabel("备注（选填）")
            LvInput(note, { note = it }, "补充细节，如地点、要求……（标题没写就写这里）", singleLine = false)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 已划去子页（页头无盖垃圾桶进入）：按天分组整卡 + 恢复 + 两段确认清空 */
@Composable
fun DoneListScreen(vm: MainViewModel, onBack: () -> Unit) {
    val done by vm.done.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var bulkConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        item { SubHeader("已划去", onBack = onBack) }

        if (done.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        if (bulkConfirm) "确认清空" else "清空",
                        style = MaterialTheme.typography.labelLarge,
                        color = LvDanger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                if (!bulkConfirm) bulkConfirm = true
                                else { bulkConfirm = false; vm.clearSegment(filteredSeg = false) }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (done.isEmpty()) {
            item { LvCard(Modifier.fillMaxWidth()) { EmptyHint("还没有划去任何消息", "待处理/未整理里右滑一条试试。") } }
        }

        val grouped = done.groupBy { dayLabel(it.timestamp) }
        for ((day, msgs) in grouped) {
            item(key = "dday_$day") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = LvHighlight, modifier = Modifier.size(8.dp)) {}
                    Spacer(Modifier.width(6.dp))
                    Text(day, style = MaterialTheme.typography.bodyMedium, color = LvMuted)
                }
            }
            item(key = "dcard_$day") {
                LvListCard(modifier = Modifier.fillMaxWidth()) {
                    msgs.forEachIndexed { i, msg ->
                        if (i > 0) LvDivider()
                        key(msg.id) {
                            DoneRow(msg, expandedId == msg.id,
                                onToggle = { expandedId = if (expandedId == msg.id) null else msg.id },
                                onRestore = { vm.restoreDone(msg.id) })
                        }
                    }
                }
            }
        }
    }
}

/** 未整理页顶部操作卡：计数 + 一键整理 + 整理规则入口 */
@Composable
private fun OrganizeCard(vm: MainViewModel, pendingCount: Int, onOpenRules: () -> Unit) {
    val organizing by vm.organizing.collectAsStateWithLifecycle()
    val progress by vm.organizeProgress.collectAsStateWithLifecycle()
    OrganizeCardContent(pendingCount, organizing != null, progress, { vm.organizeBatch() }, onOpenRules)
}

@Composable
internal fun OrganizeCardContent(
    pendingCount: Int,
    organizing: Boolean,
    progress: MainViewModel.OrganizeProgress?,
    onOrganize: () -> Unit,
    onOpenRules: () -> Unit
) {
    val displayedDone = savedProgressCount(progress?.done ?: 0, progress?.runId)
    val counting = progress != null && displayedDone != progress!!.done

    LvCard(Modifier.fillMaxWidth()) {
        Text("未整理 · $pendingCount 条", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        LvPrimaryButton(
            text = when {
                progress != null && (organizing || counting) -> "已整理 $displayedDone/${progress!!.total} 条"
                organizing -> "准备整理…"
                else -> "一键整理"
            },
            onClick = onOrganize,
            modifier = Modifier.fillMaxWidth(),
            enabled = !organizing && !counting
        )
        if (organizing || progress != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    organizing -> "正在分析消息，请稍候…"
                    counting -> "已整理 $displayedDone/${progress!!.total} 条"
                    progress!!.done < progress!!.total ->
                        "已整理 $displayedDone/${progress!!.total} 条，${progress!!.total - progress!!.done} 条未成功，可重试"
                    else -> "已整理 $displayedDone/${progress!!.total} 条"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            )
        }
        Spacer(Modifier.height(8.dp))
        // 整理规则入口：进规则管理子页（底部导航已无独立 tab）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenRules)
                .padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Icon(LvIcons.Filter, contentDescription = null, tint = LvMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("整理规则", style = MaterialTheme.typography.bodyLarge, color = LvInk)
            Spacer(Modifier.weight(1f))
            Icon(LvIcons.Right, contentDescription = null, tint = LvMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Keep the container composed until its exit animation finishes. */
@Composable
private fun InboxReveal(
    visible: Boolean,
    durationMillis: Int = 250,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top
        ),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        )
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun DetailChevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "detailChevron"
    )
    Icon(
        LvIcons.ChevronDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = LvMuted,
        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }
    )
}

/** 折叠组头：chevron + 名称（计数只保留在分段控件的「未整理 N」上，组头不重复显示） */
@Composable
private fun GroupHeader(
    title: String,
    collapsed: Boolean,
    indent: Int,
    onToggle: () -> Unit,
    iconPkg: String? = null
) {
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "groupChevron"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 16).dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Icon(
            LvIcons.ChevronDown, contentDescription = if (collapsed) "展开" else "收起",
            tint = LvMuted,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = rotation }
        )
        Spacer(Modifier.width(6.dp))
        if (iconPkg != null) {
            val context = LocalContext.current
            val bmp = remember(iconPkg) {
                runCatching {
                    context.packageManager.getApplicationIcon(iconPkg).toBitmap(64, 64).asImageBitmap()
                }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bmp, contentDescription = null,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            title,
            style = if (indent == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

private val WEEKDAY_NAMES_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

/** 展开详情块（待处理行与未整理行共用）：通知原文 + 来源行 + 加入计划/跳转。划去和忽略只走滑动。 */
@Composable
private fun MsgDetail(
    msg: MessageEntity,
    onPlan: () -> Unit,
    onJump: () -> Unit
) {
    val context = LocalContext.current
    val appName = AppNames.label(context, msg.appPackage)
    Spacer(Modifier.height(10.dp))
    Text("通知原文", style = MaterialTheme.typography.bodySmall, color = LvMuted)
    Spacer(Modifier.height(4.dp))
    val shown = BundledNotice.visible(msg.text.trim())
    val titleLine = msg.groupName.trim()
    if (shown.isNotBlank() && shown != titleLine) {
        Text(shown, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("通知里只有这一句，没有更多正文。", style = MaterialTheme.typography.bodyMedium, color = LvMuted)
    }
    Spacer(Modifier.height(6.dp))
    val who = msg.sender.trim().takeIf { it.isNotBlank() && it != "通知" && it != titleLine }
    val source = listOfNotNull(appName, msg.groupName, who, formatTime(msg.timestamp)).joinToString(" · ")
    Text(source, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LvSecondaryButton("加入计划", onPlan)
        LvSecondaryButton("跳转到$appName", onJump)
    }
}

/** 清单行滑动：右滑划去（完成），左滑忽略。两边都要过阈值才执行，否则回弹。 */
@Composable
private fun InboxSwipeRow(
    onDone: () -> Unit,
    onIgnore: () -> Unit,
    content: @Composable () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableStateOf(1f) }
    val thresholdPx = with(LocalDensity.current) { 90.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { widthPx = it.size.width.toFloat().coerceAtLeast(1f) }
    ) {
        Box(Modifier.matchParentSize()) {
            val right = offsetX.value >= 0f
            Box(
                Modifier.fillMaxSize().background(if (right) LvGreenSoft else LvFill),
                contentAlignment = if (right) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(
                    if (right) LvIcons.Check else LvIcons.Close,
                    contentDescription = null,
                    tint = if (right) LvGreenInk else LvMuted,
                    modifier = Modifier.padding(horizontal = 14.dp).size(22.dp)
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .background(LvSurface)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val dx = offsetX.value
                            val passed = kotlin.math.abs(dx) > thresholdPx
                            scope.launch {
                                if (passed) {
                                    offsetX.animateTo(if (dx > 0) widthPx * 1.1f else -widthPx * 1.1f, tween(240))
                                    if (dx > 0) onDone() else onIgnore()
                                    offsetX.snapTo(0f)
                                } else {
                                    offsetX.animateTo(0f, tween(240))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(240)) } }
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    }
                }
        ) { content() }
    }
}
@Composable
private fun PendingRow(
    msg: MessageEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDone: () -> Unit,
    onIgnore: () -> Unit,
    onPlan: () -> Unit,
    onJump: () -> Unit
) {
    InboxSwipeRow(onDone = onDone, onIgnore = onIgnore) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        BundledNotice.visible(msg.summary.ifBlank { msg.text.lines().first() }),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${msg.groupName} · ${formatTime(msg.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DetailChevron(expanded)
            }
            // AI 失败提示（明示，不静默）
            if (msg.aiState == AIState.FAILED) {
                Spacer(Modifier.height(4.dp))
                Text("AI 判定失败，已保留待人工处理", style = MaterialTheme.typography.bodySmall, color = LvDanger)
            }
            InboxReveal(visible = expanded) {
                MsgDetail(msg, onPlan, onJump)
            }
        }
    }
}

/** 未整理行：蛋黄小点 + 单行紧凑标题 + 时间；右滑划去、左滑忽略，点按展开详情 */
@Composable
private fun BatchRow(
    msg: MessageEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDone: () -> Unit,
    onIgnore: () -> Unit,
    onPlan: () -> Unit,
    onJump: () -> Unit
) {
    InboxSwipeRow(onDone = onDone, onIgnore = onIgnore) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
            ) {
                // 待整理标记：蛋黄小圆点
                Surface(shape = CircleShape, color = LvHighlight, modifier = Modifier.size(8.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    BundledNotice.visible(msg.summary.ifBlank { msg.text.lines().first() }),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime(msg.timestamp),
                    style = MaterialTheme.typography.bodySmall, color = LvMuted
                )
            }
            InboxReveal(visible = expanded) {
                MsgDetail(msg, onPlan, onJump)
            }
        }
    }
}

/** 已过滤行：命中规则说明 + 原文 + 恢复 */
@Composable
private fun FilteredRow(msg: MessageEntity, expanded: Boolean, onToggle: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    BundledNotice.visible(msg.summary.ifBlank { msg.text.lines().first() }),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = LvMuted,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${msg.groupName} · ${formatTime(msg.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            DetailChevron(expanded)
        }
        InboxReveal(visible = expanded) {
            Spacer(Modifier.height(8.dp))
            Text("命中规则：${msg.matchedRule ?: "—"}", style = MaterialTheme.typography.bodySmall, color = LvPlum)
            Spacer(Modifier.height(4.dp))
            Text(BundledNotice.visible(msg.text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LvSecondaryButton("恢复到清单", onRestore)
        }
    }
}

/** 已划去行：划线 + 状态标签 + 恢复 */
@Composable
private fun DoneRow(msg: MessageEntity, expanded: Boolean, onToggle: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    BundledNotice.visible(msg.summary.ifBlank { msg.text.lines().first() }),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = TextDecoration.LineThrough
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (msg.status == MsgStatus.DONE) LvGreenSoft else LvFill
                    ) {
                        Text(
                            if (msg.status == MsgStatus.DONE) "已完成" else "已忽略",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.status == MsgStatus.DONE) LvGreenInk else LvMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${msg.groupName} · ${formatTime(msg.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            DetailChevron(expanded)
        }
        InboxReveal(visible = expanded) {
            Spacer(Modifier.height(8.dp))
            Text(BundledNotice.visible(msg.text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LvSecondaryButton("↺ 恢复到清单", onRestore)
        }
    }
}

/** 微信合并通知以「[6条]」开头，后面才是真正收到的正文。展示时去掉这个前缀，不补没收到的条。 */
internal object BundledNotice {
    private val head = Regex("""^\[\d+条\]""")

    fun visible(text: String): String = text.replace(head, "").trim()
}
