// 主 Activity：四标签骨架（清单/收集/计划/对话）+ 子页栈（设置/应用详情/接管向导/导入/整理规则/已划去）+ toast
// 视觉对齐 v4「IP 趣味」皮肤：奶油底 + 黑色胶囊底部导航（蛋黄圈标选中）
// 交互对齐 preview.js：页面切换淡入上移（page-in .24s）、蛋黄点醒后随机冒泡说话
// （个人自用版：无登录门，打开即主界面）
// v0.5.0：默认全收集 + 应用级接管；子页改为栈式导航（列表→详情→向导逐级返回）；
//         不再在启动时强制申请 POST_NOTIFICATIONS（需要发提醒时由接管向导申请）
package com.lvjian.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lvjian.app.ui.*
import com.lvjian.app.ui.components.BLOB_SAY_LINES
import com.lvjian.app.ui.components.BlobState
import com.lvjian.app.ui.components.LvCard
import com.lvjian.app.ui.components.LvPrimaryButton
import com.lvjian.app.ui.components.LvToastHost
import com.lvjian.app.ui.theme.*
import com.lvjian.app.work.SystemHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动：从通知点进来时 intent 里带着跳转目标，先记下来，等 MainScaffold 起来再消费
        NavRequest.pendingTab.value = intent?.getStringExtra(EXTRA_JUMP_TAB)
        setContent {
            val vm: MainViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            LvJianTheme(themeMode) {
                MainScaffold(vm)
            }
        }
    }

    /** App 已在后台时点通知：配合 manifest 的 singleTop 走这里，不新建 Activity 实例 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NavRequest.pendingTab.value = intent.getStringExtra(EXTRA_JUMP_TAB)
    }

    companion object {
        /** 计划提醒的通知点进来时带的「去哪个标签」标记（当前只会是 "week"） */
        const val EXTRA_JUMP_TAB = "jump_tab"
    }
}

/**
 * 「点通知进来要跳到哪一屏」的跨层传递。
 * Intent 是在 Activity 层收到的，而 tab 状态活在 MainScaffold 的 Composable 作用域里（remember），
 * 所以中间放一个单例把请求递过去（沿用 BlobState 的做法）。
 */
object NavRequest {
    /** 待处理的跳转请求；MainScaffold 消费后置回 null */
    val pendingTab = MutableStateFlow<String?>(null)
}

/** 四个主导航标签（规则页并入清单页的「未整理 → 整理规则」子页）；v0.5.0「监控」更名「收集」 */
private enum class Tab(val label: String, val icon: ImageVector) {
    INBOX("清单", LvIcons.List),
    APPS("收集", LvIcons.Grid),
    WEEK("计划", LvIcons.Cal),
    CHAT("对话", LvIcons.Sparkle)
}

/** 子页（盖住标签页内容，底部导航仍在；栈式返回，逐级回到来源页） */
private sealed interface SubPage {
    data object Settings : SubPage
    data class AppConvs(val pkg: String) : SubPage          // 应用详情（信息收集 + 提醒管理）
    data class TakeoverWizard(val pkg: String) : SubPage    // 应用级接管向导
    data class Import(val targetPkg: String?) : SubPage
    data object Rules : SubPage      // 整理规则（从清单页「未整理」进入）
    data object DoneList : SubPage   // 已划去（从清单页头无盖垃圾桶进入）
    data object MarketingApps : SubPage // 营销内容过滤的应用选择（从收集页营销卡进入）
}

/** 设计稿的缓动曲线：cubic-bezier(.16,1,.3,1) */
private val LvEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScaffold(vm: MainViewModel) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.INBOX) }
    // 子页栈：支持 列表 → 应用详情 → 接管向导/导入 的嵌套路径，返回逐级弹出
    var subStack by remember { mutableStateOf<List<SubPage>>(emptyList()) }
    // 进子页前的标签（栈空时还原）
    var prevTab by remember { mutableStateOf(Tab.INBOX) }
    val sub = subStack.lastOrNull()
    val goSub: (SubPage) -> Unit = { if (subStack.isEmpty()) prevTab = tab; subStack = subStack + it }
    val goBack: () -> Unit = {
        subStack = subStack.dropLast(1)
        if (subStack.isEmpty()) tab = prevTab
    }

    // 系统返回键与页面返回行为一致：有子页先弹子页，不直接退出/跳回主标签
    BackHandler(enabled = subStack.isNotEmpty()) { goBack() }

    // 计划提醒的通知点进来 → 切到「计划」标签（并把子页栈清空，否则会盖在计划页上面）
    val jumpTab by NavRequest.pendingTab.collectAsStateWithLifecycle()
    LaunchedEffect(jumpTab) {
        val target = jumpTab ?: return@LaunchedEffect
        if (target == "week") {
            subStack = emptyList()
            tab = Tab.WEEK
        }
        NavRequest.pendingTab.value = null   // 消费掉，避免重组时反复跳
    }

    // 通知访问状态：进入应用、从系统设置返回（ON_RESUME）时都重新读取，不只在进子页时刷新
    var notifAccess by remember { mutableStateOf(SystemHelper.hasNotificationAccess(context)) }
    val listenerBound by com.lvjian.app.notify.LvJianListenerService.connected.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifAccess = SystemHelper.hasNotificationAccess(context)
                if (notifAccess && !SystemHelper.isNotificationListenerBound(context)) SystemHelper.rebindNotificationListener(context)
                if (!SystemHelper.isIgnoringBatteryOptimizations(context)) {
                    SystemHelper.requestIgnoreBatteryOptimizations(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toast by vm.toast.collectAsStateWithLifecycle()

    // ---- 蛋黄冒泡调度（对齐 preview.js blobSay/scheduleSay）----
    // 点醒后 4~6s 冒第一句，之后每 18~35s 随机一句，3.2s 后收起
    val blobAwake by BlobState.awake.collectAsStateWithLifecycle()
    LaunchedEffect(blobAwake) {
        if (!blobAwake) return@LaunchedEffect
        delay(4000 + Random.nextLong(2000)) // 第一句别让用户干等
        while (true) {
            BlobState.sayText.value = BLOB_SAY_LINES.random()
            delay(3200)
            BlobState.sayText.value = null
            delay(15000 + Random.nextLong(12000)) // 距下一次 18~35s（含展示的 3.2s）
        }
    }

    // ---- 页面切换过渡（page-in：淡入 + 上移 10dp，.24s）----
    // 视图标识：子页优先于标签；只有真的切页才重播
    val viewKey = sub?.let { "sub-${subStack.size}" } ?: tab.name
    val pageAlpha = remember { Animatable(1f) }
    val pageShift = remember { Animatable(0f) }
    var lastKey by remember { mutableStateOf(viewKey) }
    LaunchedEffect(viewKey) {
        if (viewKey != lastKey) {
            lastKey = viewKey
            pageAlpha.snapTo(0f); pageShift.snapTo(10f)
            launch {
                pageAlpha.animateTo(1f, tween(240, easing = LvEase))
            }
            pageShift.animateTo(0f, tween(240, easing = LvEase))
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = LvBg,
            bottomBar = {
                val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
                AnimatedVisibility(
                    visible = !imeVisible,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 }
                ) {
                    // 胶囊导航：浅色=奶油面（对齐 preview.css --nav-bg:#fffdf6）、深色=近黑；
                // 选中项蛋黄圆底 + 墨黑图标。1px hairline 描边对齐预览的 border:1px solid var(--hairline)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 预览 .bottom-nav{margin:0 15px 2px} —— 左右 15px 而非屏幕边距 22px
                        .padding(horizontal = 15.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(50),
                    color = LvNavBg,
                    border = BorderStroke(1.dp, LvHairline),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Tab.entries.forEachIndexed { index, t ->
                            val selected = tab == t && sub == null
                            val selectedOffset by animateDpAsState(
                                targetValue = if (selected) 0.dp else 4.dp,
                                animationSpec = tween(420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                                label = "navSelectedOffset$index"
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { tab = t; subStack = emptyList() }
                                    .padding(vertical = 4.dp)
                            ) {
                                // 图标：选中时蛋黄圆底
                                Surface(
                                    shape = CircleShape,
                                    color = if (selected) LvHighlight else Color.Transparent,
                                    modifier = Modifier.size(34.dp).offset(y = selectedOffset)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            t.icon,
                                            contentDescription = t.label,
                                            tint = if (selected) LvLightPalette.ink else LvNavItem,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    t.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    // 选中态用主题 ink（对齐 preview.css .nav-item.active{color:var(--ink)}）。
                                    // 注意：以前写的是 onAccent，那是「实心墨黑底上的奶油字」用的；
                                    // 浅色导航条改成奶油面之后，奶油字配奶油底会直接看不见。
                                    color = if (selected) LvInk else LvNavItem
                                )
                            }
                        }
                    }
                }
            }
        }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 页面切换过渡：淡入 + 上移
                    .graphicsLayer {
                        alpha = pageAlpha.value
                        translationY = with(density) { pageShift.value.dp.toPx() }
                    }
            ) {
                // 未授予通知访问时的引导卡：只在清单页显示（主操作「开启通知访问」），
                // 其余页面浏览不受影响；收集页顶部有自己的状态卡
                if (sub == null && tab == Tab.INBOX && (!notifAccess || !listenerBound)) {
                    LvCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp)
                            .padding(top = 4.dp, bottom = 10.dp)
                    ) {
                        Text(
                            if (!notifAccess) "已经停止收集" else "通知监听没有连上",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (!notifAccess)
                                "通知访问关着，新通知不会进滤见。已有的消息和设置都还在。"
                            else
                                "通知访问已开启，正在尝试恢复连接。仍未恢复时，点击重新连接。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        LvPrimaryButton(
                            if (notifAccess) "重新连接" else "去开启",
                            onClick = {
                                if (notifAccess) SystemHelper.rebindNotificationListener(context)
                                else SystemHelper.openNotificationAccessSettings(context)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                when (val s = sub) {
                    is SubPage.Settings -> SettingsScreen(vm, onBack = goBack)
                    is SubPage.Rules -> RulesScreen(vm, onBack = goBack)
                    is SubPage.DoneList -> DoneListScreen(vm, onBack = goBack)
                    is SubPage.MarketingApps -> MarketingAppsScreen(vm, onBack = goBack)
                    is SubPage.AppConvs -> AppConvsScreen(
                        vm = vm, pkg = s.pkg, onBack = goBack,
                        onImport = { goSub(SubPage.Import(s.pkg)) },
                        onOpenWizard = { goSub(SubPage.TakeoverWizard(s.pkg)) }
                    )
                    is SubPage.TakeoverWizard -> TakeoverWizardScreen(
                        vm = vm, pkg = s.pkg, onBack = goBack
                    )
                    is SubPage.Import -> ImportGroupsScreen(
                        vm = vm, initialTarget = s.targetPkg, onClose = goBack,
                        onImportedToApp = { pkg ->
                            // 导入完成回到该应用的详情页（替换栈顶 Import，保持返回路径干净）
                            subStack = subStack.dropLast(1) + SubPage.AppConvs(pkg)
                        }
                    )
                    null -> when (tab) {
                        Tab.INBOX -> InboxScreen(
                            vm,
                            notifAccess = notifAccess,
                            onSettings = { goSub(SubPage.Settings) },
                            onOpenRules = { goSub(SubPage.Rules) },
                            onOpenDone = { goSub(SubPage.DoneList) }
                        )
                        Tab.APPS -> AppsScreen(
                            vm,
                            notifAccess = notifAccess,
                            listenerBound = listenerBound,
                            onSettings = { goSub(SubPage.Settings) },
                            onOpenConvs = { pkg -> goSub(SubPage.AppConvs(pkg)) },
                            onOpenMarketing = { goSub(SubPage.MarketingApps) }
                        )
                        Tab.WEEK -> WeekScreen(vm, onSettings = { goSub(SubPage.Settings) })
                        Tab.CHAT -> ChatScreen(vm, onSettings = { goSub(SubPage.Settings) })
                    }
                }
            }
        }

        // 轻提示 toast：浮在底部导航上方居中
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 108.dp)
        ) {
            LvToastHost(toast = toast, onDismiss = { vm.dismissToast() })
        }
    }
}
