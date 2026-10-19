// 设置子页（从各页头部齿轮进入）：外观 + 通知访问 + 后台运行建议 + AI 接口配置 + 数据说明
// v0.5.0：拆除「基础配置四步」强制清单——通知访问是唯一必需项；
//         来源应用静默/会话免打扰的说明归属各应用的接管向导，不再全局强制
// 视觉对齐 v4 皮肤；设置不再是底部标签
package com.lvjian.app.ui

import android.Manifest
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.AiConfig
import com.lvjian.app.notify.NotificationHelper
import com.lvjian.app.notify.PlanReminder
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import com.lvjian.app.work.SystemHelper
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val aiBatchMode by vm.aiBatchMode.collectAsStateWithLifecycle()
    val noRemindRealtime by vm.noRemindRealtime.collectAsStateWithLifecycle()
    // 开启「实时整理」前的费用提醒浮窗（每次开启都弹，确认后才生效）
    var showRealtimeCost by remember { mutableStateOf(false) }

    // 权限状态：每次回到本屏重新读取
    var notifAccess by remember { mutableStateOf(SystemHelper.hasNotificationAccess(context)) }
    var batteryOk by remember { mutableStateOf(SystemHelper.isIgnoringBatteryOptimizations(context)) }
    // 计划提醒要用的两条系统权限（通知权限 / 精确闹钟），同样每次进屏重读
    var notifPermOk by remember { mutableStateOf(NotificationHelper.canPostNotifications(context)) }
    var exactAlarmOk by remember { mutableStateOf(SystemHelper.canScheduleExactAlarms(context)) }
    LaunchedEffect(Unit) {
        notifAccess = SystemHelper.hasNotificationAccess(context)
        batteryOk = SystemHelper.isIgnoringBatteryOptimizations(context)
        notifPermOk = NotificationHelper.canPostNotifications(context)
        exactAlarmOk = SystemHelper.canScheduleExactAlarms(context)
    }

    // ---- 计划提醒（全局，对所有计划生效）----
    val planReminderOn by vm.planReminderEnabled.collectAsStateWithLifecycle()
    val planReminderSilent by vm.planReminderSilent.collectAsStateWithLifecycle()
    val planLeadMin by vm.planReminderLeadMin.collectAsStateWithLifecycle()
    // 系统里当前档位的渠道被用户静音/关掉时，提醒发出来也不会响 —— 这个只有系统设置能改，得给引导。
    // 挂在 planReminderSilent 上：切档位要重新取另一条渠道的状态。
    val planChannelOk = remember(planReminderSilent) {
        !NotificationHelper.planChannelBlocked(context, planReminderSilent)
    }
    // Android 13+ 的通知运行时权限（与接管向导用同一套申请方式）
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifPermOk = granted }

    // AI 表单本地编辑态（跟随已保存值重置）；AI 整理默认开启，无总开关
    var baseUrl by remember(aiConfig) { mutableStateOf(aiConfig.baseUrl) }
    var apiKey by remember(aiConfig) { mutableStateOf(aiConfig.apiKey) }
    var model by remember(aiConfig) { mutableStateOf(aiConfig.model) }

    // 模型列表弹层：null=关；loading=加载中；否则为结果列表
    var modelSheet by remember { mutableStateOf<String?>(null) } // "loading" / "list" / "error"
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelError by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        item { SubHeader("设置", onBack = onBack) }

        // ---- 外观 ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Text("外观", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                        Text("默认跟随系统，手动切换后会记住选择", style = MaterialTheme.typography.bodySmall)
                    }
                    LvSwitch(
                        checked = themeMode == ThemeMode.DARK,
                        onChange = { vm.setThemeMode(if (it) ThemeMode.DARK else ThemeMode.LIGHT) }
                    )
                }
            }
        }

        // ---- 通知访问（唯一必需权限；接管所需的来源应用设置在对应应用的接管向导里） ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Text("通知访问", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "这是滤见收集通知所需的唯一权限。开启后默认收集微信、QQ、企业微信；到「收集」页可开启其他应用或一键收集全部。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (notifAccess) "已开启" else "未开启（收集已暂停）",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (notifAccess) {
                        Surface(shape = RoundedCornerShape(50), color = LvGreenSoft) {
                            Text("正常", style = MaterialTheme.typography.labelLarge, color = LvGreenInk,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                    } else {
                        LvSecondaryButton("去开启", { SystemHelper.openNotificationAccessSettings(context) })
                    }
                }
            }
        }

        item {
            LvCard(Modifier.fillMaxWidth()) {
                Text("清理后台后继续收集", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(SystemHelper.keepAliveHint(), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                LvSecondaryButton("去设置", { SystemHelper.openVendorAutoStart(context) })
            }
        }

        // ---- 计划提醒（全局，对所有计划生效）----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Text("计划提醒", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "每项计划开始前给你一条通知。对所有计划生效。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("开启提醒", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    LvSwitch(checked = planReminderOn, onChange = { vm.setPlanReminderEnabled(it) })
                }

                // 关掉开关时整块变淡（值仍保留，可以先设好再开）。用 alpha 而不是隐藏，
                // 让人看得见"这里还有两项设置"。
                val dim = if (planReminderOn) 1f else 0.45f
                Spacer(Modifier.height(12.dp))
                Column(Modifier.alpha(dim)) {
                    // 提醒方式两档。真机上靠**切换通知渠道**实现 ——
                    // 渠道重要性创建后 App 改不了，所以必须是两条渠道（见 NotificationHelper）
                    SegmentedPill(
                        options = listOf("响铃", "静音"),
                        selected = if (planReminderSilent) 1 else 0,
                        onSelect = { if (planReminderOn) vm.setPlanReminderSilent(it == 1) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (planReminderSilent) "静音：只在通知栏出现，不出声也不震动。"
                        else "响铃：到点在通知栏出现，并响铃或震动。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // v0.6.0：标签跟符号走 —— 负数在语义上是"计划开始之后再提醒"
                        Text(if (planLeadMin < 0) "延后" else "提前", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(10.dp))
                        PlanLeadWheel(
                            value = planLeadMin,
                            enabled = planReminderOn,
                            onChange = { vm.setPlanReminderLeadMin(it) }
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("分钟", style = MaterialTheme.typography.bodyLarge)
                    }
                    // 负数提示：只在选中负值时出现（与预览端 plan-lead-hint 同文案）
                    if (planLeadMin < 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "负数：这项计划开始 ${-planLeadMin} 分钟后，再给你发提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = LvMuted
                        )
                    }
                }

                // 三条"提醒可能发不出/不准时"的引导。都只是降级，不影响计划本身照常排。
                // 用清单驱动，免得每加一条就要重新算上下间距。
                if (planReminderOn) {
                    val fixes = buildList {
                        if (!notifPermOk) add("系统通知权限未开启，提醒发不出来" to {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        })
                        if (!planChannelOk) add("系统里这个提醒渠道被静音或关掉了" to {
                            NotificationHelper.openPlanChannelSettings(context, planReminderSilent)
                        })
                        if (!exactAlarmOk) add("精确闹钟未授权，提醒可能晚几分钟" to {
                            SystemHelper.openExactAlarmSettings(context)
                        })
                    }
                    fixes.forEach { (text, fix) ->
                        Spacer(Modifier.height(10.dp))
                        FixRow(text) { fix() }
                    }
                }
            }
        }

        // ---- 后台运行（稳定性建议，不是必需步骤） ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Text("后台运行", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "部分系统会回收后台应用，可能延迟收集。以下只是建议，不设置也能用。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("电池优化白名单", style = MaterialTheme.typography.bodyLarge)
                        Text(if (batteryOk) "已加入" else "未加入", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!batteryOk) {
                        LvSecondaryButton("去处理", { SystemHelper.requestIgnoreBatteryOptimizations(context) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                LvSecondaryButton(
                    "滤见应用详情",
                    { SystemHelper.openAppDetailSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "自启动、后台权限在系统的应用详情里设置。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                LvSecondaryButton(
                    "刷新状态",
                    {
                        notifAccess = SystemHelper.hasNotificationAccess(context)
                        batteryOk = SystemHelper.isIgnoringBatteryOptimizations(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ---- AI 接口配置 ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Text("API 设置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "OpenAI 兼容协议。仅「规则作用群内、未被本地规则过滤」的消息文本会发往该接口；失败的消息保留，不静默丢弃。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("Base URL")
                LvInput(baseUrl, { baseUrl = it }, "https://api.openai.com/v1")
                Spacer(Modifier.height(8.dp))
                FieldLabel("API Key")
                LvInput(apiKey, { apiKey = it }, "sk-...", password = true)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel("模型名")
                    Spacer(Modifier.weight(1f))
                    Text(
                        "获取模型列表",
                        style = MaterialTheme.typography.labelLarge,
                        color = LvInk,
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickableText {
                                // 拉取模型列表（GET {baseUrl}/models）
                                modelSheet = "loading"
                                vm.fetchModels(baseUrl, apiKey) { list, err ->
                                    if (list != null) {
                                        modelList = list
                                        modelSheet = "list"
                                    } else {
                                        modelError = err ?: "未知原因"
                                        modelSheet = "error"
                                    }
                                }
                            }
                    )
                }
                LvInput(model, { model = it }, "gpt-4o-mini / deepseek-chat")
                Spacer(Modifier.height(10.dp))
                // 实时整理开关（默认关=攒着批量，进「未整理」一键整理；开=每条立即判定）；
                // 行内不放说明文案（用户要求），模式语义靠切换时的 toast 与费用提醒弹层交代
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("实时整理", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    // 开启实时整理前先弹费用提醒（勾过「下次不再提醒」则直接生效）；关回批量模式直接生效
                    LvSwitch(checked = !aiBatchMode, onChange = { on ->
                        if (on && !noRemindRealtime) showRealtimeCost = true else vm.setAiBatchMode(!on)
                    })
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    LvPrimaryButton(
                        "保存",
                        onClick = { vm.saveAiConfig(AiConfig(baseUrl, apiKey, model, enabled = true)) },
                    )
                }
            }
        }

        // ---- 数据说明 ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Text("数据说明", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "· 图片/语音/文件在通知里只有「[图片]」类占位，无法取到附件本体。\n" +
                        "· 长消息可能被系统通知截断；只能收集启用之后到达的消息。\n" +
                        "· 发送人识别用的是显示名，同名/改名可能误伤，按人过滤请留意。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                // 本地数据声明（对齐 .notice）
                Surface(shape = RoundedCornerShape(12.dp), color = LvGreenSoft) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LvIcons.Info, contentDescription = null, tint = LvGreenInk, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "全部数据保存在本机，截图识别也在本机完成，不会上传。",
                            style = MaterialTheme.typography.bodySmall, color = LvGreenInk
                        )
                    }
                }
            }
        }
    }

    // ---- 「实时整理」费用提醒浮窗（每次开启都弹；确认后才真正打开） ----
    if (showRealtimeCost) {
        LvSheet(
            title = "开启实时整理",
            onClose = { showRealtimeCost = false },
            actions = {
                Spacer(Modifier.weight(1f))
                LvSecondaryButton("再想想", onClick = { showRealtimeCost = false })
                LvPrimaryButton(
                    "仍要开启",
                    onClick = {
                        vm.setAiBatchMode(false) // false = 实时模式
                        showRealtimeCost = false
                    },
                )
            }
        ) {
            // 费用说明拆三段、段距等大（12dp）；结尾的「通常够用」按用户要求去掉
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "实时整理会让每条未命中消息都立即调一次 AI 接口，接口费用随之增加；",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "· 消息量大（群多、活跃）时差距会明显放大。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "批量模式（默认）把消息攒在「未整理」，点一次「一键整理」统一判定，调用次数少得多。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下次不再提醒", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LvSwitch(checked = noRemindRealtime, onChange = { vm.setNoRemindRealtime(it) })
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // ---- 模型列表弹层 ----
    modelSheet?.let {
        LvSheet(title = "选择模型", onClose = { modelSheet = null }) {
            Column {
            if (it == "loading") {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = LvAccent)
                    Spacer(Modifier.width(10.dp))
                    Text("正在获取模型列表…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (it == "error") {
                Text(
                    "获取失败：$modelError",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                Text(
                    "用的是上面输入框里的地址和密钥，不必先点保存。地址要带 /v1，例如 https://api.openai.com/v1。也可以直接在下面手动填模型名。",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                modelList.forEach { m ->
                    Surface(
                        onClick = { model = m; modelSheet = null },
                        shape = RoundedCornerShape(12.dp),
                        color = if (model == m) LvFill else LvElev,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(m, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (model == m) Icon(LvIcons.Check, null, tint = LvAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** 文字版点击修饰符（设置页「获取模型列表」文字按钮用；无涟漪） */
private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )

/** 设置卡里的「状态说明 + 去开启」一行（计划提醒的两条系统权限引导用） */
@Composable
private fun FixRow(text: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        LvSecondaryButton("去开启", onFix)
    }
}

/**
 * 提前量滚轮（v0.6.0，与预览 v7.3b 一致）：
 *   · 默认收起只露一行（当前值），点一下展开 3 行才可滚动，再点正中当前值收起；
 *   · 区间 -999~999（负数 = 计划开始过后再提醒）；滚轮只产生合法值，没有输入框的越界报红。
 *
 * 实现对齐预览踩过的坑：
 *   ① 开合过渡期间吸附会追跑偏 —— Compose 这边不模拟 CSS scroll-snap，
 *      SnapFlingBehavior 只在手势结束时吸附，不存在"过渡中吸附追着动"的问题；
 *   ② 收起/展开同一滚动位置对位的行差一格 —— 展开时滚到 (当前值−1) 让它居中，
 *      收起时滚到当前值本身占满唯一一行，两处明确分开（见 LaunchedEffect(value, open)）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlanLeadWheel(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val values = remember { (PlanReminder.LEAD_MIN..PlanReminder.LEAD_MAX).toList() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }

    val idx = (value - PlanReminder.LEAD_MIN).coerceIn(0, values.lastIndex)

    // 收起 30dp 露一行；展开 90dp 露三行（上下各一行为邻居，中间行=当前值）
    val boxH by animateDpAsState(if (open) 90.dp else 30.dp, tween(250), label = "wheelH")
    val padV by animateDpAsState(if (open) 30.dp else 0.dp, tween(250), label = "wheelPad")

    // 对位：当前值始终放在第 idx 项。展开时上下各 30dp 内边距，
    // 第 idx 项正好落在 90dp 视口正中。不要再滚到 idx-1，否则中心变成 value-2
    // （真机：10 一展开就停在 8，停稳后会把提前量写成 8）。
    LaunchedEffect(value, open) {
        listState.scrollToItem(idx)
    }

    // 滚动停稳后提交：视口正中那一行就是新值（value 用 rememberUpdatedState，
    // 否则 collect 里闭包抓到的是启动那一刻的旧值）
    val currentValue by rememberUpdatedState(value)
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@collect
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val nearest = info.visibleItemsInfo.minBy { kotlin.math.abs(it.offset + it.size / 2 - center) }
            val v = values.getOrNull(nearest.index) ?: return@collect
            if (v != currentValue) onChange(v)
        }
    }

    // 高亮行 = 视口正中那一行（滚动中实时跟手）
    val centerIdx by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf idx
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minBy { kotlin.math.abs(it.offset + it.size / 2 - center) }.index
        }
    }

    Box(
        modifier = Modifier
            .width(68.dp)
            .height(boxH)
            .clip(RoundedCornerShape(12.dp))
            .background(LvFill2)
            .border(1.dp, LvFieldBorder, RoundedCornerShape(12.dp))
            // 收起态整框可点 = 展开；展开态交给里面的行（点行选中 / 点正中收起）
            .clickable(
                enabled = enabled && !open,
                onClickLabel = "展开提前分钟数滚轮"
            ) { open = true },
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = open && enabled,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(vertical = padV),
            modifier = Modifier.fillMaxSize()
        ) {
            items(values.size) { i ->
                val v = values[i]
                val selected = if (open) i == centerIdx else i == idx
                Text(
                    text = v.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) LvInk else LvMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .then(
                            // 收起时不要挂 clickable。enabled=false 的 clickable 仍占住整框，
                            // 外框的「点一下展开」收不到事件（真机：提醒开着，点 10 次都不展开）。
                            if (open && enabled) Modifier.clickable {
                                if (i == centerIdx) open = false
                                else scope.launch { listState.animateScrollToItem((i - 1).coerceAtLeast(0)) }
                            } else Modifier
                        )
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
        // 中央选中带（蛋黄微透）。不带任何指针修饰符，点击会穿透到下面的行。
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 5.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LvYellow.copy(alpha = 0.16f))
        )
    }
}
