// 收集屏（底部第二栏，v0.5.0 由「监控」更名）：回答「从哪里收集」
// 收集屏：推荐的三个应用默认开启，其他应用默认关闭，可逐个开启或一键收集全部；
// 顶部为通知访问状态卡与 v0.5.0 升级说明；营销内容过滤以整卡入口进子页（选择生效的应用）
// 视觉对齐 v4 皮肤（黄 blob 页头）
package com.lvjian.app.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.KnownApps
import com.lvjian.app.data.TakeoverState
import com.lvjian.app.rules.MarketingLibrary
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import com.lvjian.app.work.SystemHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 全部应用列表里的一行（本机已安装、有桌面入口的应用） */
private data class InstalledApp(
    val packageName: String,
    val label: String
)

@Composable
fun AppsScreen(
    vm: MainViewModel,
    notifAccess: Boolean,
    listenerBound: Boolean,
    onSettings: () -> Unit,
    onOpenConvs: (String) -> Unit,
    onOpenMarketing: () -> Unit
) {
    val context = LocalContext.current
    val monitored by vm.monitoredApps.collectAsStateWithLifecycle()
    val v5NoticeSeen by vm.v5NoticeSeen.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // 「停止收集」确认弹层：非空 = 待确认的包名/应用名（仅当该应用接管仍有效时才需要确认）
    var stopConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var cancelAllConfirm by remember { mutableStateOf(false) }

    val installed by produceState<List<InstalledApp>>(initialValue = emptyList(), monitored) {
        value = withContext(Dispatchers.IO) {
            loadLaunchableApps(context.packageManager, monitored.map { it.packageName })
        }
    }

    // 建档状态查找表：包名 → 记录（未建档时只默认开启三个推荐应用）
    val rowMap = monitored.associateBy { it.packageName }
    val installedPkgs = installed.map { it.packageName }.toSet()

    // 推荐应用（按 KnownApps.PRESET 固定顺序置顶；仅是便利入口，默认策略面向全部应用）
    val recommended = KnownApps.PRESET.map { (pkg, name) ->
        val label = installed.firstOrNull { it.packageName == pkg }?.label ?: name
        Triple(pkg, label, rowMap[pkg])
    }

    // 全部应用 = 已安装（含推荐项之外的），按名称排序，支持按名称/包名搜索；已排除的也能被搜到并恢复
    val presetPkgs = KnownApps.PRESET.map { it.first }.toSet()
    val others = installed
        .filter { it.packageName !in presetPkgs }
        .filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        .sortedBy { it.label }

    // 已建档但本机已卸载（或自定义导入目标）的应用：仍在列表里可查可恢复，开关禁用
    val goneApps = monitored.filter {
        it.packageName !in installedPkgs && !it.packageName.startsWith("custom:")
    }

    /** 切换收集开关；关闭时若接管仍有效，先弹联动确认 */
    fun toggleCollect(pkg: String, label: String, on: Boolean) {
        val takeover = rowMap[pkg]?.takeover ?: TakeoverState.OFF
        if (!on && takeover != TakeoverState.OFF) {
            stopConfirm = pkg to label
        } else {
            vm.setAppEnabled(pkg, label, on)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        item { PageHeader("收集", blobColor = LvHighlight, onSettings = onSettings) }

        // ---- 通知访问状态卡（未开启/被撤销时给修复入口）----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                if (notifAccess && listenerBound) {
                    Text("正在收集", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "已收集推荐应用的通知；其他应用默认关闭，可在此开启，或用上方按钮一次开启全部。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (notifAccess) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("通知监听未连接", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text("通知访问已开启，但收集服务尚未连接。点击重新连接以恢复收集。", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { SystemHelper.rebindNotificationListener(context) }) {
                            Text("重新连接", color = LvInk, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("收集已暂停", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "通知访问权未开启（或已被撤销）。重新开启后继续收集；已有消息和设置都保留。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { SystemHelper.openNotificationAccessSettings(context) }) {
                            Text("去开启", color = LvInk, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // ---- v0.5.0 升级说明（仅升级用户可见，可关闭）----
        if (!v5NoticeSeen) {
            item {
                LvCard(Modifier.fillMaxWidth()) {
                    Text("收集方式变了", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "现在授予通知访问权后即默认收集全部应用，不再需要「四步配置」。\n" +
                            "· 只想整理不想动提醒：什么都不用做，各应用照常提醒。\n" +
                            "· 旧版里你设为「监控」的会话不再自动重发；要由滤见管提醒，请进对应应用的详情页开启「接管提醒」。\n" +
                            "· 如果你按旧版指引把微信通知渠道设成了静默：想恢复原样响铃，可到系统设置里把微信通知改回有声；想继续免打扰，开启接管即可。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        LvSecondaryButton("知道了", onClick = { vm.dismissV5Notice() })
                    }
                }
            }
        }

        // ---- 营销内容过滤（整卡可点 → 应用选择子页）----
        item { MarketingShieldCard(vm, onOpen = onOpenMarketing) }

        // ---- 推荐应用置顶（便利入口；默认全收集不代表只有它们） ----
        item { SectionLabel("推荐", first = true) }
        item {
            LvListCard(Modifier.fillMaxWidth()) {
                recommended.forEachIndexed { i, (pkg, label, row) ->
                    if (i > 0) LvDivider()
                    AppRow(
                        packageName = pkg,
                        label = label,
                        collecting = KnownApps.isCollecting(pkg, row?.enabled),
                        takeover = row?.takeover ?: TakeoverState.OFF,
                        onToggle = { toggleCollect(pkg, label, it) },
                        onOpen = { onOpenConvs(pkg) }
                    )
                }
            }
        }

        // ---- 全部应用（可搜索；每个应用都可进详情管理） ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("全部应用", first = false)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LvSecondaryButton(
                        "收集所有应用",
                        onClick = { vm.setInstalledAppsCollection(installed.map { it.packageName to it.label }, true) },
                        modifier = Modifier.weight(1f)
                    )
                    LvSecondaryButton(
                        "取消所有应用",
                        onClick = {
                            val takeoverApps = installed.filter {
                                KnownApps.isCollecting(it.packageName, rowMap[it.packageName]?.enabled) &&
                                    (rowMap[it.packageName]?.takeover ?: TakeoverState.OFF) != TakeoverState.OFF
                            }
                            if (takeoverApps.isNotEmpty()) cancelAllConfirm = true
                            else vm.setInstalledAppsCollection(installed.map { it.packageName to it.label }, false)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item {
            // 搜索框：放大镜 + 浅底输入（对齐 .search-field）
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索应用名或包名", color = LvMuted) },
                leadingIcon = { Icon(LvIcons.Search, contentDescription = null, tint = LvMuted, modifier = Modifier.size(20.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LvFill2,
                    unfocusedContainerColor = LvFill2,
                    // 聚焦时不再换成墨黑粗描边（用户反馈讨厌那圈黑线），保持与未聚焦一致的浅描边
                    focusedBorderColor = LvFieldBorder,
                    unfocusedBorderColor = LvFieldBorder,
                    cursorColor = LvAccent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            LvListCard(Modifier.fillMaxWidth()) {
                when {
                    installed.isEmpty() ->
                        Text("正在读取应用列表…", style = MaterialTheme.typography.bodyMedium, color = LvMuted,
                            modifier = Modifier.padding(vertical = 12.dp))
                    others.isEmpty() -> EmptyHint("没有匹配的应用", "换个关键词试试。")
                    else -> others.forEachIndexed { i, app ->
                        if (i > 0) LvDivider()
                        val row = rowMap[app.packageName]
                        AppRow(
                            packageName = app.packageName,
                            label = app.label,
                            collecting = KnownApps.isCollecting(app.packageName, row?.enabled),
                            takeover = row?.takeover ?: TakeoverState.OFF,
                            onToggle = { toggleCollect(app.packageName, app.label, it) },
                            onOpen = { onOpenConvs(app.packageName) }
                        )
                    }
                }
            }
        }

        // ---- 已卸载的应用（仍建档的）：说明 + 可清理状态 ----
        if (goneApps.isNotEmpty()) {
            item { SectionLabel("已卸载") }
            item {
                LvListCard(Modifier.fillMaxWidth()) {
                    goneApps.forEachIndexed { i, app ->
                        if (i > 0) LvDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("已卸载，不再产生新通知；历史消息仍在清单里。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (cancelAllConfirm) {
        LvSheet(
            title = "取消所有应用收集？",
            onClose = { cancelAllConfirm = false },
            actions = {
                Spacer(Modifier.weight(1f))
                LvSecondaryButton("返回", onClick = { cancelAllConfirm = false })
                LvPrimaryButton(
                    "确认取消",
                    onClick = {
                        vm.setInstalledAppsCollection(installed.map { it.packageName to it.label }, false)
                        cancelAllConfirm = false
                    }
                )
            }
        ) {
            Text(
                "这会停止收集所有已安装应用的新通知，并退出这些应用的提醒接管；已有消息会保留。之前设为静音的来源通知渠道不会自动恢复，请到系统设置手动调整。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // ---- 「停止收集 + 退出接管」确认弹层 ----
    stopConfirm?.let { (pkg, label) ->
        LvSheet(
            title = "停止收集「$label」？",
            onClose = { stopConfirm = null },
            actions = {
                Spacer(Modifier.weight(1f))
                LvSecondaryButton("取消", onClick = { stopConfirm = null })
                LvPrimaryButton("停止收集", onClick = {
                    vm.confirmStopCollecting(pkg, label)
                    stopConfirm = null
                })
            }
        ) {
            Text(
                "这个应用正在由滤见接管提醒。停止收集会同时退出接管：\n" +
                    "· 之后不再收录它的新通知（已有消息保留）；\n" +
                    "· 滤见不再撤销或重发它的通知；\n" +
                    "· 如果你之前把它的通知渠道设为静默，请到系统设置里改回有声，否则它不会再响。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = {
                SystemHelper.openAppNotificationSettings(context, pkg)
            }) {
                Text("打开「$label」的通知设置", color = LvInk, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 区块小标题（对齐 .section-label） */
@Composable
fun SectionLabel(text: String, first: Boolean = false) {
    // 对齐 preview.css .section-label{font-size:14px;font-weight:550;line-height:22px;color:var(--muted)}
    // 以前用的是 titleMedium（17sp/SemiBold），比预览大 3sp 且更重
    Text(
        text,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight(550),
        color = LvMuted,
        // 预览 .section-label{margin:24px 0 10px}，首块 .first{margin-top:0}。
        // 各屏幕 LazyColumn 是 spacedBy(14.dp)，所以这里补 10dp 凑成 24dp 上间距。
        modifier = Modifier.padding(top = if (first) 0.dp else 10.dp)
    )
}

/**
 * 「营销内容过滤」卡：整卡可点，进入应用选择页。
 * v0.5.0 起不再是「总开关 + 就地展开」——过滤改成按应用勾选，
 * 卡上只显示当前生效范围，选择在子页里做。
 */
@Composable
private fun MarketingShieldCard(vm: MainViewModel, onOpen: () -> Unit) {
    val apps by vm.marketingApps.collectAsStateWithLifecycle()
    // 摘要只说个数不点名：点名（已开启：微信）在多选之后会撑破一行
    val summary = if (apps.isEmpty()) "尚未开启" else "已开启 ${apps.size} 个应用"

    LvCard(Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("营销内容过滤", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                LvIcons.Right,
                contentDescription = null,
                tint = LvMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 营销内容过滤 · 应用选择页（v0.5.0 新增）
 * 只列「已接管」的应用：过滤的「不显示也不响」是靠提醒档撤掉原通知不再补发实现的，
 * 没接管的应用即使勾上也什么都不会发生，所以干脆不列。
 * 一个都没有时给空状态 + 「去接管应用」（返回收集页，那里能进应用详情开启接管）。
 */
@Composable
fun MarketingAppsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val monitored by vm.monitoredApps.collectAsStateWithLifecycle()
    val mktApps by vm.marketingApps.collectAsStateWithLifecycle()
    var showLib by remember { mutableStateOf(false) }
    val active = monitored.filter { it.takeover == TakeoverState.ACTIVE }
    var wordCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        wordCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MarketingLibrary.size(context)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        item { SubHeader("营销内容过滤", onBack = onBack) }

        item {
            Text(
                "打开后，该应用里的营销消息不进清单、也不出现在通知栏。只能选已接管的应用。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }

        // 词库在上、应用列表在下（用户定的顺序）；不另起「词库」小标题——「查看词库」四个字已经说清楚了
        item {
            LvCard(Modifier.fillMaxWidth().clickable { showLib = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("查看词库", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "内置 ${wordCount ?: "…"} 个营销特征词",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(LvIcons.Right, contentDescription = null, tint = LvMuted, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (active.isEmpty()) {
            item {
                LvCard(Modifier.fillMaxWidth()) {
                    EmptyHint(
                        "还没有已接管的应用",
                        "营销内容过滤只在已接管的应用上生效。先接管一个应用，再回来选它。"
                    )
                }
            }
            item {
                LvPrimaryButton("去接管应用", onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
        } else {
            item {
                LvListCard(Modifier.fillMaxWidth()) {
                    active.forEachIndexed { i, app ->
                        if (i > 0) LvDivider()
                        val on = app.packageName in mktApps
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.toggleMarketingApp(app.packageName, !on) }
                                .padding(vertical = 10.dp)
                        ) {
                            AppIcon(app.packageName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LvMuted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            LvSwitch(checked = on, onChange = { vm.toggleMarketingApp(app.packageName, it) })
                        }
                    }
                }
            }
        }
    }

    if (showLib) MarketingLibOverlay(onClose = { showLib = false })
}

/** 单行应用：图标 + 名称/包名（点进应用详情）+ 接管状态徽标 + 收集开关 */
@Composable
private fun AppRow(
    packageName: String,
    label: String,
    collecting: Boolean,
    takeover: Int,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 10.dp)
    ) {
        AppIcon(packageName)
        Spacer(Modifier.width(12.dp))
        // 名称区域：点按进应用详情（右箭头提示），开关独立不触发
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(vertical = 2.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 接管状态徽标（只有主动开启过才显示，不干扰默认只收集的行）
                    if (takeover != TakeoverState.OFF) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (takeover == TakeoverState.ACTIVE) LvGreenSoft else LvFill2
                        ) {
                            Text(
                                when (takeover) {
                                    TakeoverState.ACTIVE -> "已接管提醒"
                                    TakeoverState.SUSPENDED -> "接管已暂停"
                                    else -> "接管待配置"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (takeover == TakeoverState.ACTIVE) LvGreenInk else LvMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(LvIcons.Right, contentDescription = "应用详情", tint = LvMuted, modifier = Modifier.size(18.dp))
        }
        LvSwitch(checked = collecting, onChange = onToggle)
    }
}

/** 枚举本机应用：有桌面入口的，加上已建档的（部分应用查不到启动入口，但仍在收通知）。 */
private fun loadLaunchableApps(pm: PackageManager, extraPackages: List<String> = emptyList()): List<InstalledApp> {
    val infos: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
    val byPkg = infos.associateBy { it.packageName }
    val launchable = infos.filter { pm.getLaunchIntentForPackage(it.packageName) != null }
    val extra = extraPackages.mapNotNull { pkg -> byPkg[pkg]?.takeIf { pm.getLaunchIntentForPackage(pkg) == null } }
    return (launchable + extra)
        .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
        .distinctBy { it.packageName }
}
