// 应用级接管向导（应用详情 → 接管说明与配置 → 用户确认 → 回到详情管理各会话）
// 步骤：说明影响 → 来源应用通知渠道设静默（手动确认）→ 可选：收集原本免打扰的会话 →
//       核查通知访问（自动）→ 核查滤见发通知许可与提醒渠道（自动，提醒档必需）→ 后台运行建议（可选）
// 原则：系统读不到的状态由用户手动确认并注明「按你的确认」，不伪装成自动检测；
//       取消向导保持接管关闭（已填的进度保留为「配置中」），未完成向导不执行任何撤/重发
package com.lvjian.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lvjian.app.data.TakeoverState
import com.lvjian.app.notify.NotificationHelper
import com.lvjian.app.notify.TakeoverPolicy
import com.lvjian.app.ui.components.*
import com.lvjian.app.ui.theme.*
import com.lvjian.app.util.AppNames
import com.lvjian.app.work.SystemHelper

@Composable
fun TakeoverWizardScreen(vm: MainViewModel, pkg: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val apps by vm.monitoredApps.collectAsStateWithLifecycle()
    val app = apps.firstOrNull { it.packageName == pkg }
    val appName = app?.appName?.takeIf { it.isNotBlank() } ?: AppNames.label(context, pkg)

    // 用户手动确认位（系统读不到来源应用的渠道配置）：初值取已保存的进度
    var channelOk by remember(app) { mutableStateOf(app?.takeoverChannelOk ?: false) }
    // 「想收集原本免打扰的会话？」按需展开说明
    var showMutedConvHelp by remember { mutableStateOf(false) }
    // 后台运行建议展开
    var showBatteryHelp by remember { mutableStateOf(false) }

    // 自动检测项：通知访问权 / 滤见发通知许可 / 提醒渠道可用；每次回到本页（ON_RESUME）重读
    var notifAccess by remember { mutableStateOf(SystemHelper.hasNotificationAccess(context)) }
    var canPost by remember { mutableStateOf(NotificationHelper.canPostNotifications(context)) }
    var channelBlocked by remember { mutableStateOf(NotificationHelper.alertChannelBlocked(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifAccess = SystemHelper.hasNotificationAccess(context)
                canPost = NotificationHelper.canPostNotifications(context)
                channelBlocked = NotificationHelper.alertChannelBlocked(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android 13+ 发通知运行时权限：只有接管要用到「提醒」档重发才需要，在这里按需申请
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> canPost = NotificationHelper.canPostNotifications(context) }

    // 完成条件：渠道已确认 + 通知访问在 + 能发通知且提醒渠道未被禁
    val canFinish = notifAccess &&
        TakeoverPolicy.canEnableTakeover(channelOk, canPost, channelBlocked)

    /** 退出向导：保持接管关闭；有进度则存为「配置中」，下次进入可继续 */
    fun exitWizard() {
        if (app?.takeover != TakeoverState.ACTIVE) {
            if (channelOk) vm.saveTakeoverProgress(pkg, appName, true)
            else vm.disableTakeover(pkg, appName)
        }
        onBack()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        item { SubHeader("接管「$appName」的提醒", onBack = { exitWizard() }) }

        // ---- 第 1 步：说明 ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                WizardStepBadge(1)
                Spacer(Modifier.height(8.dp))
                Text("接管后会发生什么", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "滤见将按每个会话的「提醒/静默/隐藏」撤销或重发「$appName」的通知。\n" +
                        "为避免原通知先响一次，需要把「$appName」的相关通知渠道设为静默——注意：同一个渠道可能覆盖多个会话，设静默的是渠道，不是单个会话。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ---- 第 2 步：来源应用通知渠道（手动确认） ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                WizardStepBadge(2)
                Spacer(Modifier.height(8.dp))
                Text("把「$appName」的通知渠道设为静默", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "在系统设置里找到「$appName」的通知渠道，把要接管的渠道改为静默（无铃声、无震动）。系统不允许滤见读取这项配置，完成后请手动确认。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LvSecondaryButton("打开通知设置", onClick = {
                        SystemHelper.openAppNotificationSettings(context, pkg)
                    })
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("我已把相关渠道设为静默", style = MaterialTheme.typography.bodyLarge)
                        Text("按你的确认记录，滤见无法自动检测", style = MaterialTheme.typography.bodySmall)
                    }
                    LvSwitch(checked = channelOk, onChange = { channelOk = it })
                }
            }
        }

        // ---- 第 3 步（可选）：收集原本免打扰的会话 ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showMutedConvHelp = !showMutedConvHelp }
                ) {
                    Text(
                        "想收集原本免打扰的会话？（可选）",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                    )
                    Icon(
                        LvIcons.ChevronDown,
                        contentDescription = if (showMutedConvHelp) "收起" else "展开",
                        tint = LvMuted,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(18.dp)
                            .graphicsLayer { rotationZ = if (showMutedConvHelp) 180f else 0f }
                    )
                }
                if (showMutedConvHelp) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "如果某个会话在「$appName」里开了免打扰，它根本不发布通知，滤见也就收不到。\n" +
                            "要收集这类会话：到「$appName」内关闭该会话的免打扰，并保持「通知显示消息详情」开启。不是每个会话都需要这一步。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---- 第 4 步：核查通知访问（自动检测） ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                WizardStepBadge(4)
                Spacer(Modifier.height(8.dp))
                CheckRow(
                    title = "滤见的通知访问权",
                    subtitle = if (notifAccess) "已开启（自动检测）" else "未开启——接管需要读取原通知才能接管它",
                    ok = notifAccess,
                    actionLabel = "去开启"
                ) { SystemHelper.openNotificationAccessSettings(context) }
            }
        }

        // ---- 第 5 步：核查滤见发通知许可与提醒渠道（自动检测；提醒档必需） ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                WizardStepBadge(5)
                Spacer(Modifier.height(8.dp))
                CheckRow(
                    title = "允许滤见发出提醒",
                    subtitle = if (canPost) "已允许（自动检测）" else "「提醒」档需要滤见重发一条有声通知；拒绝不影响只收集",
                    ok = canPost,
                    actionLabel = "去允许"
                ) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        SystemHelper.openAppNotificationSettings(context, context.packageName)
                    }
                }
                if (canPost && channelBlocked) {
                    Spacer(Modifier.height(10.dp))
                    LvDivider()
                    Spacer(Modifier.height(10.dp))
                    CheckRow(
                        title = "滤见的「消息提醒」渠道",
                        subtitle = "该渠道被设为无声或已禁用（自动检测），提醒档会发不出声",
                        ok = false,
                        actionLabel = "去恢复"
                    ) { NotificationHelper.openAlertChannelSettings(context) }
                }
            }
        }

        // ---- 第 6 步（可选）：后台运行建议 ----
        item {
            LvCard(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showBatteryHelp = !showBatteryHelp }
                ) {
                    Text(
                        "后台运行建议（可选）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        LvIcons.ChevronDown,
                        contentDescription = if (showBatteryHelp) "收起" else "展开",
                        tint = LvMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = if (showBatteryHelp) 180f else 0f }
                    )
                }
                if (showBatteryHelp) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "部分系统会回收后台应用，可能导致接管延迟。把滤见加入电池优化白名单、允许自启动会更稳，但不是所有设备都必须。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LvSecondaryButton("电池优化", onClick = { SystemHelper.requestIgnoreBatteryOptimizations(context) })
                        LvSecondaryButton("应用详情", onClick = { SystemHelper.openAppDetailSettings(context) })
                    }
                }
            }
        }

        // ---- 底部操作 ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                LvSecondaryButton("取消", onClick = { exitWizard() }, modifier = Modifier.weight(1f))
                LvPrimaryButton(
                    "完成并开启接管",
                    onClick = {
                        vm.enableTakeover(pkg, appName, channelOk)
                        onBack()
                    },
                    enabled = canFinish,
                    modifier = Modifier.weight(2f)
                )
            }
            if (!canFinish) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "完成上方第 2、4、5 步后才能开启；未开启前滤见不会动「$appName」的任何通知。",
                    style = MaterialTheme.typography.bodySmall, color = LvMuted
                )
            }
        }
    }
}

/** 向导步骤序号：蛋黄圆底 + 墨黑数字 */
@Composable
private fun WizardStepBadge(step: Int) {
    Surface(shape = CircleShape, color = LvHighlight, modifier = Modifier.size(24.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text("$step", style = MaterialTheme.typography.labelLarge, color = LvLightPalette.ink)
        }
    }
}

/** 自动检测核查行：标题 + 状态说明 + 右侧状态胶囊/动作按钮 */
@Composable
private fun CheckRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (ok) {
            Surface(shape = RoundedCornerShape(50), color = LvGreenSoft) {
                Text(
                    "正常",
                    style = MaterialTheme.typography.labelLarge,
                    color = LvGreenInk,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        } else {
            TextButton(onClick = onClick) {
                Text(actionLabel, color = LvInk, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
