// 应用详情页（从「收集」页点任意应用进入）：两个区域——
//   信息收集：收集开关（与列表同步）、已发现会话、截图导入（导入只建会话名单，不代表读到消息）
//   提醒管理：「接管此应用提醒」默认关闭；开启走接管向导；启用后才有各会话「提醒/静默/隐藏」三档
// v0.5.0：会话三档只在接管启用后显示；接管关闭时原应用照常提醒，三档隐藏
package com.lvjian.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.GroupEntity
import com.lvjian.app.data.GroupMode
import com.lvjian.app.data.GroupSource
import com.lvjian.app.data.KnownApps
import com.lvjian.app.data.TakeoverState
import com.lvjian.app.notify.NotificationHelper
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import com.lvjian.app.util.AppNames
import com.lvjian.app.work.SystemHelper

/** 三档的展示名与说明文案（索引 = GroupMode 常量）；v0.5.0 起「监控」更名「提醒」 */
private val MODE_LABELS = listOf("提醒", "静默", "隐藏")
private val CONV_FOLD_SIZE_SPEC = tween<androidx.compose.ui.unit.IntSize>(durationMillis = 420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
private val CONV_FOLD_ARROW_SPEC = tween<Float>(durationMillis = 420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
private val MODE_EXPLAINS = listOf(
    // REMIND 提醒：撤掉原通知 + 有声重发
    "将消息收录至未整理，并正常提醒你：滤见撤掉原来的通知，用自己的渠道重发一条带铃声的通知，点它直接跳回这个会话。",
    // QUIET 静默：原通知不动，无声躺在通知栏
    "将消息收录至未整理；通知不响不震，安静地留在通知栏里，你下拉就能看到。",
    // HIDE 隐藏：撤掉原通知，不响不显示
    "通知栏里不再显示这个会话的通知（不响不震），但不影响收集——消息照常收录至未整理。"
)

@Composable
fun AppConvsScreen(
    vm: MainViewModel,
    pkg: String,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpenWizard: () -> Unit
) {
    val context = LocalContext.current
    val groups by vm.groups.collectAsStateWithLifecycle()
    val apps by vm.monitoredApps.collectAsStateWithLifecycle()
    val noRemindRemind by vm.noRemindMonitor.collectAsStateWithLifecycle() // key 沿用旧名，语义=提醒档
    val noRemindQuiet by vm.noRemindQuiet.collectAsStateWithLifecycle()
    val noRemindHide by vm.noRemindMute.collectAsStateWithLifecycle()       // key 沿用旧名，语义=隐藏档

    val app = apps.firstOrNull { it.packageName == pkg }
    val appName = app?.appName?.takeIf { it.isNotBlank() } ?: AppNames.label(context, pkg)
    // 未建档 = 默认收集中、未接管
    val collecting = KnownApps.isCollecting(pkg, app?.enabled)
    val takeover = app?.takeover ?: TakeoverState.OFF

    // 该应用的会话（按最近活跃排序）
    val convs = groups.filter { it.appPackage == pkg }.sortedByDescending { it.lastSeen }
    val totalMsgs = convs.sumOf { it.msgCount }

    // 档位说明弹层：值为 GroupMode 常量；null=不弹
    var explainMode by remember { mutableStateOf<Int?>(null) }
    // 「关闭接管」确认弹层
    var showDisableTakeover by remember { mutableStateOf(false) }
    // 「停止收集」确认弹层（接管仍有效时需说明联动）
    var showStopCollect by remember { mutableStateOf(false) }
    // v0.6.0 会话搜索（对齐预览 v7.3）：页头放大镜展开搜索框，按会话名过滤；收起即清词
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    // v0.6.0 会话行折叠账本（对齐预览 convFold）：key=会话名，默认全部收起
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // 搜索过滤：只匹配会话名（与预览一致：不看消息内容）
    val shown = if (query.isBlank()) convs
        else convs.filter { it.name.contains(query.trim(), ignoreCase = true) }

    /** 该档位的「下次不再提醒」是否已勾（未勾=切换到此档时弹说明，默认未勾=默认显示） */
    fun noRemindOf(mode: Int) = when (mode) {
        GroupMode.REMIND -> noRemindRemind
        GroupMode.QUIET -> noRemindQuiet
        else -> noRemindHide
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        // 子页头：返回 + 应用名 + 搜索 + 导入
        item {
            SubHeader(appName, onBack = onBack) {
                // v0.6.0：搜索入口在「导入」左边；展开或有关键词时点亮成墨黑，再点收起并清词
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                }) {
                    Icon(
                        LvIcons.Search, contentDescription = if (searchOpen) "收起搜索" else "搜索会话",
                        tint = if (searchOpen || query.isNotEmpty()) LvInk else LvMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
                TextButton(onClick = onImport) {
                    Text("导入", color = LvInk, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 搜索框：展开时出现并自动聚焦（对齐预览 .search-field 的浅底胶囊样式）
        item {
            AnimatedVisibility(
                visible = searchOpen,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索群聊或私信会话", color = LvMuted) },
                    leadingIcon = { Icon(LvIcons.Search, contentDescription = null, tint = LvMuted, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LvFill2,
                        unfocusedContainerColor = LvFill2,
                        // 聚焦不加深描边（用户反馈讨厌聚焦黑边），与未聚焦一致的浅描边
                        focusedBorderColor = LvFieldBorder,
                        unfocusedBorderColor = LvFieldBorder,
                        cursorColor = LvAccent
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                )
            }
            // 展开动画播完再要焦点，否则输入框还在 0 高时弹键盘会闪
            LaunchedEffect(searchOpen) {
                if (searchOpen) {
                    kotlinx.coroutines.delay(280)
                    searchFocus.requestFocus()
                }
            }
        }

        // ================= 信息收集 =================
        item { SectionLabel("信息收集", first = true) }
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("收集「$appName」的通知", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (collecting) "正在收集：之后发布的通知会整理进清单；${appName}照常提醒"
                            else "已停止收集：不再收录新通知，已有消息保留",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    LvSwitch(checked = collecting, onChange = { on ->
                        // 关闭收集且接管仍有效时，先说明联动影响再确认
                        if (!on && takeover != TakeoverState.OFF) showStopCollect = true
                        else vm.setAppEnabled(pkg, appName, on)
                    })
                }
                if (convs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已发现 ${convs.size} 个会话 · 共 $totalMsgs 条",
                        style = MaterialTheme.typography.bodySmall, color = LvMuted
                    )
                }
            }
        }

        item { SectionLabel("会话") }
        if (convs.isEmpty()) {
            item {
                LvCard(Modifier.fillMaxWidth()) {
                    EmptyHint(
                        "暂未发现会话",
                        "收到「$appName」的通知后，对应会话会自动出现在这里；也可以点右上角「导入」截图添加名单（导入不代表读到了消息）。"
                    )
                }
            }
        } else if (shown.isEmpty()) {
            // 有会话但搜索词没命中：与"从未发现会话"区分开，引导换词而不是去导入（同预览）
            item {
                LvCard(Modifier.fillMaxWidth()) {
                    EmptyHint("没有匹配的会话", "换个关键词试试；搜索只看会话名，不看消息内容。")
                }
            }
        } else {
            item {
                LvListCard(Modifier.fillMaxWidth()) {
                    shown.forEachIndexed { i, g ->
                        if (i > 0) LvDivider()
                        ConvRow(
                            group = g,
                            // 只有接管已启用才有三档可展开；否则只是会话名单（无箭头、不可点）
                            showModes = takeover == TakeoverState.ACTIVE,
                            expanded = expanded[g.name] == true,
                            onToggle = { expanded[g.name] = !(expanded[g.name] == true) },
                            onSelectMode = { mode ->
                                vm.setGroupMode(g.appPackage, g.name, mode)
                                // 每次切换档位都弹说明，除非用户勾过该档的「下次不再提醒」
                                if (mode != g.mode && !noRemindOf(mode)) explainMode = mode
                            }
                        )
                    }
                }
            }
        }

        // ================= 提醒管理 =================
        item { SectionLabel("提醒管理") }
        item {
            LvCard(Modifier.fillMaxWidth()) {
                when {
                    // 收集已停止：接管必须先恢复收集，避免出现「显示已排除但后台仍处理」
                    !collecting -> {
                        Text("接管此应用提醒", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "「$appName」已停止收集。接管提醒需要在收集的基础上工作，请先在上方恢复收集。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    takeover == TakeoverState.OFF -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("接管此应用提醒", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "默认关闭：${appName}照常提醒，滤见只负责整理。开启后由滤见按会话决定怎么响。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = onOpenWizard) {
                                Text("去配置", color = LvInk, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    takeover == TakeoverState.CONFIGURING -> {
                        Text("接管配置待完成", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "向导还没走完，滤见不会撤销或重发「$appName」的通知。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LvSecondaryButton("关闭接管", onClick = { vm.disableTakeover(pkg, appName) })
                            LvPrimaryButton("继续配置", onClick = onOpenWizard)
                        }
                    }
                    takeover == TakeoverState.SUSPENDED -> {
                        Text("接管已暂停", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "滤见发通知的许可或提醒渠道不可用，为避免「原通知撤了却提醒不了」，接管已自动暂停；期间只收集不动原通知。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LvSecondaryButton("检查权限", onClick = {
                                if (!NotificationHelper.canPostNotifications(context)) {
                                    SystemHelper.openAppNotificationSettings(context, context.packageName)
                                } else {
                                    NotificationHelper.openAlertChannelSettings(context)
                                }
                            })
                            val canRepost = NotificationHelper.canPostNotifications(context) &&
                                !NotificationHelper.alertChannelBlocked(context)
                            LvPrimaryButton("恢复接管", onClick = { vm.resumeTakeover(pkg, appName) },
                                enabled = canRepost)
                        }
                    }
                    else -> { // ACTIVE
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("已接管「$appName」的提醒", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "按上方各会话的「提醒/静默/隐藏」处理原通知。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { showDisableTakeover = true }) {
                                Text("关闭接管", color = LvDanger, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 档位说明弹层 ----
    explainMode?.let { mode ->
        LvSheet(
            title = MODE_LABELS[mode],
            onClose = { explainMode = null },
            actions = {
                LvPrimaryButton("知道了", onClick = { explainMode = null }, modifier = Modifier.fillMaxWidth())
            }
        ) {
            Text(MODE_EXPLAINS[mode], style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下次不再提醒", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LvSwitch(checked = noRemindOf(mode), onChange = { vm.setNoRemind(mode, it) })
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // ---- 关闭接管确认 ----
    if (showDisableTakeover) {
        LvSheet(
            title = "关闭对「$appName」的接管？",
            onClose = { showDisableTakeover = false },
            actions = {
                Spacer(Modifier.weight(1f))
                LvSecondaryButton("取消", onClick = { showDisableTakeover = false })
                LvPrimaryButton("关闭接管", onClick = {
                    vm.disableTakeover(pkg, appName)
                    showDisableTakeover = false
                })
            }
        ) {
            Text(
                "关闭后滤见立即停止撤销和重发「$appName」的通知，各会话的提醒方式偏好会保留（下次开启接管时直接生效）。\n\n" +
                    "注意：如果你之前把它的通知渠道设为静默，它不会自动恢复响铃——请到系统设置里改回。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { SystemHelper.openAppNotificationSettings(context, pkg) }) {
                Text("打开「$appName」的通知设置", color = LvInk, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    // ---- 停止收集确认（接管仍有效时的联动说明） ----
    if (showStopCollect) {
        LvSheet(
            title = "停止收集「$appName」？",
            onClose = { showStopCollect = false },
            actions = {
                Spacer(Modifier.weight(1f))
                LvSecondaryButton("取消", onClick = { showStopCollect = false })
                LvPrimaryButton("停止收集", onClick = {
                    vm.confirmStopCollecting(pkg, appName)
                    showStopCollect = false
                })
            }
        ) {
            Text(
                "停止收集会同时退出接管：之后不再收录它的新通知（已有消息保留），滤见也不再撤销或重发它的通知。\n\n" +
                    "如果你之前把它的通知渠道设为静默，请到系统设置里改回有声，否则它不会再响。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { SystemHelper.openAppNotificationSettings(context, pkg) }) {
                Text("打开「$appName」的通知设置", color = LvInk, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 会话行（v0.6.0，对齐预览 v7.3）：名称+条数默认收起，点行展开三档胶囊（提醒/静默/隐藏）。
 *  箭头沿用全局约定：收起=转 -90° 指向右，展开=回正向下；展开/收起走 expandVertically 高度动画。
 *  showModes=false（未接管）时无箭头不可点，只是名单行。 */
@Composable
private fun ConvRow(
    group: GroupEntity,
    showModes: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectMode: (Int) -> Unit
) {
    val sourceTag = if (group.source == GroupSource.IMPORTED) "导入 · " else ""
    // 导入只建立名单：没有消息时如实显示「未收到消息」
    val stats = if (group.msgCount > 0) "${group.msgCount} 条 · 最近 ${formatTime(group.lastSeen)}" else "未收到消息"

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (showModes) Modifier.clickable(
                        onClickLabel = if (expanded) "收起提醒方式" else "展开提醒方式",
                        role = androidx.compose.ui.semantics.Role.Button
                    ) { onToggle() }
                    else Modifier
                )
                .padding(vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("$sourceTag$stats", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showModes) {
                val chevAngle by animateFloatAsState(
                    targetValue = if (expanded) 0f else -90f,
                    animationSpec = CONV_FOLD_ARROW_SPEC,
                    label = "chev"
                )
                Icon(
                    LvIcons.ChevronDown,
                    contentDescription = null,   // 状态由整行的 onClickLabel 表达，图标不重复播报
                    tint = LvMuted,
                    modifier = Modifier.size(22.dp).rotate(chevAngle)
                )
            }
        }
        if (showModes) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = CONV_FOLD_SIZE_SPEC),
                exit = shrinkVertically(animationSpec = CONV_FOLD_SIZE_SPEC)
            ) {
                Column(Modifier.padding(bottom = 10.dp)) {
                    SegmentedPill(options = MODE_LABELS, selected = group.mode, onSelect = onSelectMode)
                }
            }
        }
    }
}
