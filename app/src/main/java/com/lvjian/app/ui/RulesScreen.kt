// 整理规则子页（从清单页「未整理 → 整理规则」进入；底部导航已无独立规则 tab）
// 内容：全局去向开关 + 用户规则（kv 行编辑 / 作用范围弹层 / 开关 / 左滑删除）+ 新建/编辑弹层
// 营销过滤已挪到收集页「营销内容过滤」卡 → 应用选择子页（按应用勾选）；词墙浮层 MarketingLibOverlay 留在本文件供两处复用
package com.lvjian.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.RuleAction
import com.lvjian.app.data.RuleEntity
import com.lvjian.app.data.RuleType
import com.lvjian.app.data.ScopeKeys
import com.lvjian.app.rules.MarketingLibrary
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val keepFiltered by vm.keepFiltered.collectAsStateWithLifecycle()

    // 弹层状态：rule = 新建/编辑规则；scope = 作用范围（target：rule id / "new"）
    var ruleSheet by remember { mutableStateOf<RuleEntity?>(null) }   // null=关；EMPTY_RULE=新建
    var showNewRule by remember { mutableStateOf(false) }
    var scopeTarget by remember { mutableStateOf<String?>(null) }     // null=关

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
        ) {
            item { SubHeader("整理规则", onBack = onBack) }

            // 全局开关：命中规则的消息去向
            item {
                LvCard(Modifier.fillMaxWidth()) {
                    Text("命中规则的消息", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    SegmentedPill(
                        options = listOf("进「已过滤」", "直接消失"),
                        selected = if (keepFiltered) 0 else 1,
                        onSelect = { vm.setKeepFiltered(it == 0) }
                    )
                }
            }

            // 规则整卡：用户规则（左滑删除）；营销词库在监控页配置
            item {
                LvListCard(Modifier.fillMaxWidth()) {
                    if (rules.isEmpty()) {
                        EmptyHint("还没有整理规则", "点下方「新建规则」，关键词 / 按人 / AI 语义三种。")
                    }
                    rules.forEachIndexed { i, rule ->
                        if (i > 0) LvDivider()
                        key(rule.id) {
                            RuleSwipeRow(
                                rule = rule,
                                onToggle = { vm.setRuleEnabled(rule.id, it) },
                                onDelete = { vm.deleteRule(rule) },
                                onEdit = { ruleSheet = rule },
                                onScope = { if (!rule.builtin) scopeTarget = "rule:${rule.id}" }
                            )
                        }
                    }
                }
            }

            // 新建按钮：墨黑实心胶囊
            item {
                LvPrimaryButton("＋ 新建规则", onClick = { showNewRule = true }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // ---- 新建 / 编辑规则弹层 ----
    if (showNewRule || ruleSheet != null) {
        RuleEditSheet(
            existing = ruleSheet,
            groups = groups,
            onOpenScope = { scopeTarget = "new" },
            // scopeDraft：新建模式下作用范围暂存在这里
            scopeDraft = remember { mutableStateOf(ScopeKeys.ALL) },
            onSave = { name, type, pattern, keys, action ->
                if (ruleSheet != null) {
                    vm.updateRule(ruleSheet!!.copy(name = name, type = type, pattern = pattern, scopeKeys = keys, action = action))
                } else {
                    vm.addRule(name, type, pattern, keys, action)
                }
                ruleSheet = null; showNewRule = false
            },
            onInvalid = { vm.showToast(it) },
            onClose = { ruleSheet = null; showNewRule = false }
        )
    }

    // ---- 作用范围弹层（新建 / 编辑规则共用）----
    scopeTarget?.let { target ->
        when {
            target.startsWith("rule:") -> {
                val id = target.removePrefix("rule:").toLong()
                val rule = rules.firstOrNull { it.id == id }
                if (rule != null) {
                    ScopeSheet(
                        currentKeys = rule.scopeKeys,
                        groups = groups,
                        onChange = { vm.updateRuleScope(rule, it) },
                        onDone = { scopeTarget = null }
                    )
                } else scopeTarget = null
            }
            // 新建规则草稿的作用范围：由 RuleEditSheet 自己持有（传 onOpenScope 时记录）
            else -> {
                // "new" 的情况在 RuleEditSheet 内处理（见下）
                scopeTarget = null
            }
        }
    }
}

/** 单条用户规则行：左滑删除（跟手横移，底层红垃圾桶；过阈值滑出执行），点「类型 · 内容」编辑，点「作用于」改范围 */
@Composable
private fun RuleSwipeRow(
    rule: RuleEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onScope: () -> Unit
) {
    val context = LocalContext.current
    SwipeActionRow(
        direction = if (rule.builtin) SwipeDir.NONE else SwipeDir.LEFT,
        onTrigger = onDelete,
        background = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    LvIcons.Trash, contentDescription = "删除", tint = LvDanger,
                    modifier = Modifier.padding(end = 14.dp).size(22.dp)
                )
            }
        }
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 类型 · 内容行（点按 = 编辑规则）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onEdit)
                            .padding(vertical = 3.dp)
                    ) {
                        Text(typeLabel(rule.type), style = MaterialTheme.typography.bodySmall)
                        if (rule.builtin) {
                            Spacer(Modifier.width(8.dp))
                            Text("内置", style = MaterialTheme.typography.bodySmall, color = LvMuted)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            actionLabel(rule.action),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rule.action == RuleAction.KEEP) LvGreenInk else LvMuted
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            rule.pattern,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(LvIcons.Right, contentDescription = null, tint = LvMuted, modifier = Modifier.size(16.dp))
                    }
                    // 作用于行（点按 = 作用范围弹层）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !rule.builtin, onClick = onScope)
                            .padding(vertical = 3.dp)
                    ) {
                        Text("作用于", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            scopeSetLabel(rule.scopeKeys, context),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(LvIcons.Right, contentDescription = null, tint = LvMuted, modifier = Modifier.size(16.dp))
                    }
                }
                LvSwitch(checked = rule.enabled, onChange = onToggle)
            }
        }
    }
}

fun typeLabel(type: Int): String = when (type) {
    RuleType.KEYWORD -> "关键词"
    RuleType.PERSON -> "按人"
    RuleType.AI_PROMPT -> "AI 语义"
    else -> "未知"
}

fun actionLabel(action: Int): String = if (action == RuleAction.KEEP) "保留" else "过滤"

/** 新建/编辑规则弹层（对齐 sheet 'new-rule'） */
@Composable
private fun RuleEditSheet(
    existing: RuleEntity?,
    groups: List<com.lvjian.app.data.GroupEntity>,
    onOpenScope: () -> Unit,
    scopeDraft: MutableState<String>,
    onSave: (name: String, type: Int, pattern: String, scopeKeys: String, action: Int) -> Unit,
    onInvalid: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var pattern by remember { mutableStateOf(existing?.pattern ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: RuleType.KEYWORD) }
    var action by remember { mutableStateOf(existing?.action ?: RuleAction.FILTER) }
    // 作用范围：编辑用规则原值；新建用草稿 state（scope 弹层由外层打开）
    var keys by remember { mutableStateOf(existing?.scopeKeys ?: scopeDraft.value) }
    // 新建模式下打开内嵌的 scope 弹层
    var showScope by remember { mutableStateOf(false) }

    val patternHint = when (type) {
        RuleType.KEYWORD -> if (action == RuleAction.KEEP) "如：作业（正文包含即保留）" else "如：家教（正文包含即过滤）"
        RuleType.PERSON -> if (action == RuleAction.KEEP) "如：王老师（该显示名发的都保留）" else "如：张三（该显示名发的都过滤）"
        else -> if (action == RuleAction.KEEP) "用自然语言描述要留下的，如：作业、截止、改期"
        else "用自然语言描述要滤掉的，如：闲聊、约饭、表情包刷屏"
    }

    val readOnly = existing?.builtin == true

    LvSheet(
        title = when {
            readOnly -> "查看规则"
            existing != null -> "编辑规则"
            else -> "新建规则"
        },
        onClose = onClose,
        actions = {
            if (readOnly) {
                LvPrimaryButton("关闭", onClose, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.weight(1f))
                LvSecondaryButton("取消", onClose)
                LvPrimaryButton(
                    "保存规则",
                    onClick = {
                        if (name.isBlank() || pattern.isBlank()) { onInvalid("规则名和内容都要填"); return@LvPrimaryButton }
                        onSave(name, type, pattern, keys, action)
                    },
                )
            }
        }
    ) {
        Column {
            if (readOnly) {
                Text("内置规则只能开关，不能改。", style = MaterialTheme.typography.bodySmall, color = LvMuted)
                Spacer(Modifier.height(8.dp))
            }
            FieldLabel("规则名")
            LvInput(name, { if (!readOnly) name = it }, "如：家教", enabled = !readOnly)
            Spacer(Modifier.height(8.dp))
            FieldLabel("命中后")
            if (readOnly) {
                Text(actionLabel(action), style = MaterialTheme.typography.bodyMedium)
            } else {
                SegmentedPill(
                    options = listOf("过滤", "保留"),
                    selected = if (action == RuleAction.KEEP) 1 else 0,
                    onSelect = { action = if (it == 1) RuleAction.KEEP else RuleAction.FILTER }
                )
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("类型")
            if (readOnly) {
                Text(typeLabel(type), style = MaterialTheme.typography.bodyMedium)
            } else {
                SegmentedPill(
                    options = listOf("关键词", "按人", "AI 语义"),
                    selected = when (type) {
                        RuleType.PERSON -> 1
                        RuleType.AI_PROMPT -> 2
                        else -> 0
                    },
                    onSelect = {
                        type = when (it) {
                            1 -> RuleType.PERSON
                            2 -> RuleType.AI_PROMPT
                            else -> RuleType.KEYWORD
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("规则内容")
            LvInput(pattern, { if (!readOnly) pattern = it }, patternHint, singleLine = false, enabled = !readOnly)
            Spacer(Modifier.height(8.dp))
            FieldLabel("作用范围")
            Text(scopeSetLabel(keys, context), style = MaterialTheme.typography.bodyMedium)
            if (!readOnly) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    onClick = { showScope = true },
                    shape = RoundedCornerShape(14.dp),
                    color = LvFill2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, LvFieldBorder)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("修改范围", style = MaterialTheme.typography.bodyMedium)
                        Icon(LvIcons.Right, contentDescription = null, tint = LvMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 作用范围弹层（叠在规则弹层之上）
        if (showScope) {
            ScopeSheet(
                currentKeys = keys,
                groups = groups,
                onChange = { keys = it; scopeDraft.value = it },
                onDone = { showScope = false }
            )
        }
    }
}

/**
 * 内置营销特征库，对齐预览：词从下往上飘，大小和速度不一样。
 * 先打开窗口，词在后台读。不要在点击那一帧读完，否则又会卡住。
 */
@Composable
fun MarketingLibOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    var words by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        words = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MarketingLibrary.all(context).shuffled()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(LvBg.copy(alpha = 0.62f))) {
            val list = words
            Text(
                if (list == null) "正在读取词库" else "内置营销特征库 · ${list.size} 词",
                fontSize = 14.sp,
                color = LvMuted,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 26.dp).zIndex(2f)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).zIndex(2f)
            ) {
                Icon(LvIcons.Close, contentDescription = "关闭", tint = LvInk)
            }
            if (list == null) {
                Text("稍等", color = LvMuted, modifier = Modifier.align(Alignment.Center))
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
                    val h = maxHeight
                    val w = maxWidth
                    val rnd = kotlin.random.Random(list.size)
                    list.shuffled(rnd).take(100).forEachIndexed { i, word ->
                        val size = 14 + rnd.nextInt(13)
                        val left = 0.04f + rnd.nextFloat() * (0.84f - (size - 14) * 0.022f)
                        val dur = 9000 + rnd.nextInt(9000)
                        val delay = -(i * dur / 100)
                        val alpha = 0.35f + rnd.nextFloat() * 0.5f
                        DriftWord(word, size, left, dur, delay, h, w, alpha)
                    }
                }
            }
        }
    }
}

@Composable
private fun DriftWord(
    word: String,
    sizeSp: Int,
    left: Float,
    dur: Int,
    delay: Int,
    height: androidx.compose.ui.unit.Dp,
    width: androidx.compose.ui.unit.Dp,
    alpha: Float
) {
    val transition = rememberInfiniteTransition(label = word)
    val y by transition.animateFloat(
        initialValue = 1.05f,
        targetValue = -0.08f,
        animationSpec = infiniteRepeatable(tween(dur, delayMillis = delay, easing = LinearEasing)),
        label = "y"
    )
    Text(
        word,
        color = LvInk.copy(alpha = alpha),
        fontSize = sizeSp.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.graphicsLayer {
            translationX = left * width.toPx()
            translationY = y * height.toPx()
        }
    )
}
