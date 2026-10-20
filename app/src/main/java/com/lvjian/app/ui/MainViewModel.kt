// 主 ViewModel：把仓储/偏好的 Flow 转成 UI 状态，暴露全部用户操作
// 含：清单（含撤销/清空）、规则、群（监控/免打扰）、监控应用、计划时间块、蛋黄对话、主题
package com.lvjian.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lvjian.app.LvJianApp
import com.lvjian.app.data.*
import com.lvjian.app.notify.PlanReminder
import com.lvjian.app.ui.theme.ThemeMode
import com.lvjian.app.util.PlanDeleteMatcher
import com.lvjian.app.util.ScheduleParser
import com.lvjian.app.util.TaskIntentParser
import com.lvjian.app.util.TaskScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAdjusters

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val lvApp = app as LvJianApp
    private val repo = lvApp.repo
    private val prefs = lvApp.prefs

    // ---- 清单 ----
    val pending: StateFlow<List<MessageEntity>> =
        repo.pendingMessages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val done: StateFlow<List<MessageEntity>> =
        repo.doneMessages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val filtered: StateFlow<List<MessageEntity>> =
        repo.filteredMessages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 规则与群 ----
    val rules: StateFlow<List<RuleEntity>> =
        repo.allRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups: StateFlow<List<GroupEntity>> =
        repo.allGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 监控应用 ----
    val monitoredApps: StateFlow<List<MonitoredAppEntity>> =
        repo.monitoredApps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 计划时间块 ----
    val events: StateFlow<List<PlanEventEntity>> =
        repo.allEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- AI 配置 ----
    val aiConfig: StateFlow<AiConfig> =
        repo.aiConfigFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiConfig())

    // ---- 未整理（批量模式攒下的条目） ----
    val pendingBatch: StateFlow<List<MessageEntity>> =
        repo.pendingBatchMessages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- AI 判定方式（实时 / 批量） ----
    val aiBatchMode: StateFlow<Boolean> =
        repo.aiBatchModeFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ---- 「启用 AI 整理」首次说明（只弹一次） ----
    // （AI 整理默认开启，设置页无总开关，原「首次启用说明」已随开关一起移除）

    fun setAiBatchMode(v: Boolean) = viewModelScope.launch {
        repo.saveAiBatchMode(v)
        showToast(if (v) "攒着批量：未命中消息进「未整理」，点一键整理统一判定" else "实时判定：每条消息立即调用 AI")
    }

    // ---- 一键整理（批量 AI 判定） ----
    /** 整理进度：null=未在整理；否则 (已完成条数, 总条数) */
    private val _organizing = MutableStateFlow<Pair<Int, Int>?>(null)
    val organizing: StateFlow<Pair<Int, Int>?> = _organizing.asStateFlow()
    data class OrganizeProgress(val done: Int, val total: Int, val runId: Long)
    private val _organizeProgress = MutableStateFlow<OrganizeProgress?>(null)
    val organizeProgress = _organizeProgress.asStateFlow()
    private var organizeRunId = 0L

    fun organizeBatch() {
        if (_organizing.value != null) return  // 防重复点击
        _organizing.value = 0 to 0
        _organizeProgress.value = null
        val runId = ++organizeRunId
        viewModelScope.launch {
            try {
                val result = repo.organizePendingBatch { done, total ->
                    _organizeProgress.value = OrganizeProgress(done, total, runId)
                    _organizing.value = done to total
                }
                if (result == null) {
                    showToast("先在「设置 → AI」里配好接口再整理")
                } else {
                    showToast("整理完成：滤掉 ${result.filtered} 条，保留 ${result.kept} 条" +
                        (if (result.failed > 0) "，失败 ${result.failed} 条（留在未整理可重试）" else ""))
                }
            } catch (e: Exception) {
                showToast("整理中断：${e.message ?: "未知错误"}，已整理的不会丢")
            } finally {
                _organizing.value = null
            }
        }
    }

    // ---- 全局开关与偏好 ----
    val keepFiltered: StateFlow<Boolean> =
        prefs.keepFiltered.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    // 营销过滤生效的应用包名集合（v0.5.0：按应用勾选，只能是已接管的应用）
    val marketingApps: StateFlow<Set<String>> =
        prefs.marketingApps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)
    val noRemindMonitor: StateFlow<Boolean> =
        prefs.noRemindMonitor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val noRemindMute: StateFlow<Boolean> =
        prefs.noRemindMute.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val noRemindQuiet: StateFlow<Boolean> =
        prefs.noRemindQuiet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val noRemindRealtime: StateFlow<Boolean> =
        prefs.noRemindRealtime.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ---- 计划提醒（全局，对所有计划生效）----
    // 默认「开 + 提前 10 分钟」——与预览端 state.planReminderOn / state.planLead 的默认值一致
    val planReminderEnabled: StateFlow<Boolean> =
        prefs.planReminderEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val planReminderLeadMin: StateFlow<Int> =
        prefs.planReminderLeadMin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanReminder.LEAD_DEFAULT)
    /** 提醒方式：false=响铃（默认）/ true=静音（只在通知栏显示） */
    val planReminderSilent: StateFlow<Boolean> =
        prefs.planReminderSilent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        // App 启动就重排一次：进程被杀、系统清理过闹钟、或期间改过计划，都能在这一刻归位
        reschedulePlanReminders()
        // 把今天的蛋黄对话读回来（昨天的直接不读，见 loadChatLog）
        loadChatLog()
    }

    /**
     * 按当前设置把所有计划的提醒闹钟重排一遍。
     * 触发时机：App 启动 / 计划增删改 / 设置变化。
     * **每次都全量重排**（先撤后建），不做增量 —— 分支少，不容易漏掉某种改动路径。
     */
    fun reschedulePlanReminders() = viewModelScope.launch {
        repo.reschedulePlanReminders()
    }

    fun setPlanReminderEnabled(v: Boolean) = viewModelScope.launch {
        prefs.setPlanReminderEnabled(v)
        reschedulePlanReminders()   // 打开就排上；关掉时 reschedule 内部会把已排的全部撤销
        showToast(if (v) "已开启计划提醒" else "已关闭计划提醒")
    }

    /** 提前量：范围 -999~999，负数表示计划开始后再提醒。 */
    fun setPlanReminderLeadMin(min: Int) = viewModelScope.launch {
        val v = PlanReminder.normalizeLead(min)
        prefs.setPlanReminderLeadMin(v)
        reschedulePlanReminders()
        showToast(if (v < 0) "已改为延后 ${-v} 分钟提醒" else "已改为提前 $v 分钟提醒")
    }

    /**
     * 提醒方式：响铃 / 静音。
     * **不需要重排闹钟** —— 它只决定触发那一刻挑哪条通知渠道，跟"什么时候响"无关。
     */
    fun setPlanReminderSilent(silent: Boolean) = viewModelScope.launch {
        prefs.setPlanReminderSilent(silent)
        showToast(if (silent) "已改为静音：只在通知栏显示" else "已改为响铃")
    }

    // ---- 轻提示（toast）：text + 可选动作；UI 层 4 秒后自动消失 ----
    // 类型定义在 components/Common.kt（LvToastHost 直接用，避免 VM→组件的循环依赖）
    private val _toast = MutableStateFlow<com.lvjian.app.ui.components.MainViewModelToast?>(null)
    val toast: StateFlow<com.lvjian.app.ui.components.MainViewModelToast?> = _toast.asStateFlow()
    private var toastSeq = 0L

    fun showToast(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        _toast.value = com.lvjian.app.ui.components.MainViewModelToast(++toastSeq, text, actionLabel, onAction)
    }
    fun dismissToast() { _toast.value = null }

    // ---------- 清单操作 ----------
    fun setStatus(id: Long, status: Int) = viewModelScope.launch { repo.setStatus(id, status) }

    /** 划去（完成/忽略）+ toast 撤销：撤销 = 状态拉回待处理 */
    fun dismissMessage(id: Long, ignored: Boolean) {
        viewModelScope.launch {
            repo.setStatus(id, if (ignored) MsgStatus.IGNORED else MsgStatus.DONE)
            showToast(if (ignored) "已忽略" else "已划去", "撤销") {
                setStatus(id, MsgStatus.PENDING)
            }
        }
    }

    /** 已过滤恢复 */
    fun unfilter(id: Long) {
        viewModelScope.launch {
            repo.unfilter(id)
            showToast("已恢复到清单")
        }
    }

    /** 已划去恢复（统一回到待处理） */
    fun restoreDone(id: Long) {
        viewModelScope.launch {
            repo.setStatus(id, MsgStatus.PENDING)
            showToast("已恢复到清单")
        }
    }

    /** 清空分段（已过滤/已划去）+ toast 撤销（整批插回） */
    fun clearSegment(filteredSeg: Boolean) {
        viewModelScope.launch {
            val removed = repo.clearSegment(filteredSeg)
            val name = if (filteredSeg) "已过滤" else "已划去"
            if (removed.isNotEmpty()) {
                showToast("已清空「$name」${removed.size} 条", "撤销") {
                    viewModelScope.launch { removed.forEach { repo.reinsertMessage(it) } }
                }
            }
        }
    }

    /** 手动添加一条到清单 */
    fun addManualMessage(title: String, note: String) {
        viewModelScope.launch {
            repo.addManual(title.trim(), note.trim())
            showToast("已加入清单")
        }
    }

    // ---------- 规则操作 ----------
    fun addRule(name: String, type: Int, pattern: String, scopeKeys: String, action: Int = RuleAction.FILTER) =
        viewModelScope.launch {
            repo.addRule(
                RuleEntity(
                    name = name.trim(),
                    type = type,
                    pattern = pattern.trim(),
                    scopeKeys = scopeKeys,
                    action = action
                )
            )
            showToast("规则「${name.trim()}」已保存")
        }

    fun updateRule(rule: RuleEntity) =
        viewModelScope.launch {
            val current = rules.value.firstOrNull { it.id == rule.id }
            if (current?.builtin == true) {
                repo.setRuleEnabled(rule.id, rule.enabled)
                showToast("内置规则只能开关")
                return@launch
            }
            repo.updateRule(rule)
            showToast("规则「${rule.name}」已更新")
        }

    fun setRuleEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setRuleEnabled(id, enabled) }

    /** 删除规则。内置规则只能开关，不能删。 */
    fun deleteRule(rule: RuleEntity) {
        if (rule.builtin) {
            showToast("「${rule.name}」是内置规则，只能开关")
            return
        }
        viewModelScope.launch {
            repo.deleteRule(rule)
            showToast("已删除「${rule.name}」", "撤销") {
                viewModelScope.launch { repo.reinsertRule(rule) }
            }
        }
    }

    /** 只改规则的作用范围（规则卡「作用于」行 → scope 弹层） */
    fun updateRuleScope(rule: RuleEntity, scopeKeys: String) {
        viewModelScope.launch {
            repo.updateRule(rule.copy(scopeKeys = scopeKeys))
            showToast("作用范围已更新")
        }
    }

    // ---------- 全局过滤开关 ----------
    fun setKeepFiltered(v: Boolean) = viewModelScope.launch {
        repo.saveKeepFiltered(v)
        showToast(if (v) "命中过滤规则的消息将进「已过滤」" else "命中过滤规则的消息将直接消失，不进已过滤")
    }

    /** 切换某应用的营销内容过滤（勾选=开启；调用方保证只对已接管的应用开启） */
    fun toggleMarketingApp(pkg: String, on: Boolean) = viewModelScope.launch {
        repo.saveMarketingApps(
            if (on) marketingApps.value + pkg else marketingApps.value - pkg
        )
        showToast(if (on) "已开启该应用的营销过滤" else "已关闭该应用的营销过滤")
    }

    // ---------- 群（通知方式三选一：监控/静默/隐藏）----------
    fun setGroupMode(appPackage: String, name: String, mode: Int) =
        viewModelScope.launch { repo.setGroupMode(appPackage, name, mode) }

    // ---------- 收集应用 ----------
    /** 恢复收集（开启）。关闭收集请走 UI 确认后的 [confirmStopCollecting] */
    fun setAppEnabled(packageName: String, appName: String, enabled: Boolean) =
        viewModelScope.launch {
            repo.setAppEnabled(packageName, appName, enabled)
            showToast(if (enabled) "已恢复收集「$appName」" else "已停止收集「$appName」：不再收录其新通知")
        }

    fun enableAllApps(packageNames: List<Pair<String, String>>) =
        viewModelScope.launch {
            repo.enableAllApps(packageNames)
            showToast("已开启全部 ${packageNames.size} 个应用的收集")
        }

    /** 一键收集/取消所有已安装应用，保留已过滤和未安装应用记录。 */
    fun setInstalledAppsCollection(packageNames: List<Pair<String, String>>, enabled: Boolean) =
        viewModelScope.launch {
            repo.setInstalledAppsCollection(packageNames, enabled)
            showToast(if (enabled) "已开启全部 ${packageNames.size} 个应用的收集" else "已取消全部应用的收集")
        }

    /** 确认「停止收集 + 退出接管」（UI 弹过说明后调用）：两者一起关闭 */
    fun confirmStopCollecting(packageName: String, appName: String) =
        viewModelScope.launch {
            repo.disableAppAndTakeover(packageName, appName)
            showToast("已停止收集「$appName」并退出接管；如之前静默过其通知渠道，记得去系统设置恢复")
        }

    // ---------- 应用级通知接管 ----------
    /** 向导中保存渠道确认（状态置为配置中） */
    fun saveTakeoverProgress(packageName: String, appName: String, channelOk: Boolean) =
        viewModelScope.launch { repo.setTakeoverConfiguring(packageName, appName, channelOk) }

    /** 向导完成，开启接管 */
    fun enableTakeover(packageName: String, appName: String, channelOk: Boolean) =
        viewModelScope.launch {
            repo.enableTakeover(packageName, appName, channelOk)
            showToast("已接管「$appName」的提醒：按各会话的提醒方式处理原通知")
        }

    /** 关闭接管：立即停止撤/重发；来源应用通知设置需用户自行恢复 */
    fun disableTakeover(packageName: String, appName: String) =
        viewModelScope.launch {
            repo.disableTakeover(packageName)
            showToast("已停止接管「$appName」；如之前把它的通知渠道设为静默，请去系统设置恢复")
        }

    /** 权限恢复后重新启用被暂停/未完成的接管 */
    fun resumeTakeover(packageName: String, appName: String) =
        viewModelScope.launch {
            repo.resumeTakeover(packageName)
            showToast("已恢复接管「$appName」")
        }

    // ---------- v0.5.0 升级说明 ----------
    val v5NoticeSeen: StateFlow<Boolean> =
        prefs.v5NoticeSeen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun dismissV5Notice() = viewModelScope.launch { prefs.setV5NoticeSeen() }

    /** 自定义导入目标建档，返回其伪包名 */
    suspend fun ensureCustomApp(name: String): String = repo.ensureCustomApp(name)

    // ---- 群导入（截图 OCR 确认后批量入库） ----
    /** @return 实际新增的群数（已存在的会跳过）；UI 拿到后给用户反馈 */
    fun importGroups(appPackage: String, names: List<String>, onDone: (Int) -> Unit) =
        viewModelScope.launch { onDone(repo.importGroups(appPackage, names)) }

    // ---------- 计划时间块 ----------
    fun addEvent(ev: PlanEventEntity, onSaved: ((Long) -> Unit)? = null) =
        viewModelScope.launch {
            val id = repo.addEvent(ev)
            onSaved?.invoke(id)
        }

    fun updateEvent(ev: PlanEventEntity) = viewModelScope.launch {
        repo.updateEvent(ev)
    }

    fun deleteEvent(ev: PlanEventEntity) {
        viewModelScope.launch {
            repo.deleteEvent(ev)
            showToast("已删除", "撤销") {
                viewModelScope.launch {
                    repo.reinsertEvent(ev)
                }
            }
        }
    }

    // ---------- AI ----------
    fun saveAiConfig(cfg: AiConfig) = viewModelScope.launch {
        repo.saveAiConfig(cfg)
        showToast(
            if (cfg.enabled && !cfg.usable) "已保存；注意三项配置不齐，AI 过滤不会生效"
            else "AI 配置已保存"
        )
    }

    /** 拉取模型列表。用设置页当前输入，不要求先点保存。 */
    fun fetchModels(baseUrl: String, apiKey: String, onResult: (List<String>?, String?) -> Unit) {
        val cfg = AiConfig(baseUrl.trim(), apiKey.trim(), model = "", enabled = true)
        viewModelScope.launch {
            try {
                onResult(repo.fetchModels(cfg), null)
            } catch (e: Exception) {
                onResult(null, e.message ?: "获取失败")
            }
        }
    }

    // ---------- 主题 ----------
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }

    // ---------- 通知方式说明「下次不再提醒」（按档位各自独立）----------
    fun setNoRemind(mode: Int, v: Boolean) = viewModelScope.launch {
        when (mode) {
            GroupMode.REMIND -> prefs.setNoRemindMonitor(v) // key 沿用旧名，语义=监控档
            GroupMode.QUIET -> prefs.setNoRemindQuiet(v)
            else -> prefs.setNoRemindMute(v)                // key 沿用旧名，语义=隐藏档
        }
    }

    /** 「开启实时整理」费用提醒的「下次不再提醒」 */
    fun setNoRemindRealtime(v: Boolean) = viewModelScope.launch { prefs.setNoRemindRealtime(v) }

    // ---------- 蛋黄对话 ----------
    data class ChatMsg(val who: String, val text: String, val imageUri: String? = null) // who: "me" | "blob"

    private val _chatMsgs = MutableStateFlow<List<ChatMsg>>(emptyList())
    val chatMsgs: StateFlow<List<ChatMsg>> = _chatMsgs.asStateFlow()

    private val _chatSending = MutableStateFlow(false)
    val chatSending: StateFlow<Boolean> = _chatSending.asStateFlow()

    // ---- 按天记忆 ----
    // 对话日志落盘（跨进程重启还在），但**过了本地自然日零点就整份丢掉**。
    // 关键设计：跨天判定只用内存里的 chatDay 跟"今天"比，**不需要读盘** ——
    // 磁盘上的日期戳只在冷启动决定"要不要信任那份日志"时才用得上。
    private var chatDay: LocalDate = LocalDate.now()

    /** 跨天（本地自然日）就清空。纯内存判定，任何时候都可以安全地调 */
    private fun rolloverIfNeeded() {
        val today = LocalDate.now()
        if (chatDay == today) return
        chatDay = today
        _chatMsgs.value = emptyList()
        viewModelScope.launch { prefs.saveChatLog(today, emptyList()) }
    }

    /** 供对话页进入时调用：App 一直开着跨过零点，也能一进页面就换成新的一天 */
    fun checkChatDay() = rolloverIfNeeded()

    /** 冷启动把今天的对话读回来；磁盘那份是昨天的就直接不读（跨天即弃） */
    private fun loadChatLog() = viewModelScope.launch {
        val (day, entries) = prefs.currentChatLog()
        val today = LocalDate.now()
        if (ChatLog.isStale(day, today)) return@launch
        val loaded = entries.map { ChatMsg(it.who, it.text) }
        // 读盘是异步的：万一这期间用户已经说了话，把历史接在前面，别把它盖掉
        _chatMsgs.value = if (_chatMsgs.value.isEmpty()) loaded else loaded + _chatMsgs.value
        chatDay = today
    }

    /** 追加一条：先处理跨天，再改内存，最后异步落盘（落盘失败不影响界面） */
    private fun appendChat(msg: ChatMsg) {
        rolloverIfNeeded()
        _chatMsgs.value = _chatMsgs.value + msg
        persistChat()
    }

    private fun persistChat() {
        val day = chatDay
        // 图片消息不落盘：uri 是临时的，重启后多半已经读不到，存了只会出现破图
        val entries = _chatMsgs.value
            .filter { it.imageUri == null }
            .map { ChatLogEntry(it.who, it.text) }
        viewModelScope.launch { prefs.saveChatLog(day, entries) }
    }

    /** 蛋黄在对话里回一句 */
    private fun blobReply(text: String) = appendChat(ChatMsg("blob", text))

    // ================= v7.1：蛋黄的任务执行（本地意图 → 直接操作 APP） =================
    // 分工：听懂在 TaskIntentParser，算和写在 TaskScheduler，这里只负责"串起来 + 汇报"。
    // 排期策略（用户拍板）：排不进只报告、撞车不覆盖，两种失败都不写库。

    /** 蛋黄最近一次排进去的那条，供"改到晚上""删掉"引用（进程内有效，不做持久化） */
    private var lastScheduled: PlanEventEntity? = null

    /** (周几 + 第几周) → 具体日期；基准是"今天所在周的周一" */
    private fun dateOf(dayOfWeek: Int, weekOffset: Int, today: LocalDate): LocalDate {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.plusDays((weekOffset * 7 + dayOfWeek).toLong())
    }

    /**
     * 本地意图统一入口：命中就地执行并返回 true（不调 API）。
     * 顺序即优先级：改期/删除 → 排期 → 加清单。
     * 排期排在加清单前面，因为句子里带"几点到几点/几小时"时，意图更明确是排计划。
     */
    private suspend fun runLocalIntent(text: String): Boolean {
        if (handleTaskEdit(text)) return true
        val today = LocalDate.now()
        val intent = TaskIntentParser.parse(text, today)
        if (intent != null) { doSchedule(intent, today); return true }
        if (handleAddToInbox(text)) return true
        return false
    }

    /** 执行排期并回话：成功落库；失败只报告（见文件头的排期策略） */
    private suspend fun doSchedule(intent: TaskIntentParser.TaskIntent, today: LocalDate) {
        val date = dateOf(intent.dayOfWeek, intent.weekOffset, today)
        val whenLabel = TaskIntentParser.dayLabel(intent.dayOfWeek, intent.weekOffset, today)
        val outcome = repo.scheduleTask(intent, date)
        when (val res = outcome.decision) {
            is TaskScheduler.Result.Ok -> {
                lastScheduled = outcome.saved
                // 只说时段/只给时刻、没说排多久 → 默认 1 小时，得讲明白
                val note = if (intent.durationExplicit || intent.range != null) "" else "（你没说排多久，我按 1 小时排的）"
                blobReply(
                    "好啦，$whenLabel ${TaskIntentParser.fmtMin(res.start)}–${TaskIntentParser.fmtMin(res.end)} " +
                        "帮你排上了「${intent.title}」$note。\n想改就说「改到晚上」，不要了就说「删掉」。"
                )
                showToast("已排入$whenLabel ${TaskIntentParser.fmtMin(res.start)}–${TaskIntentParser.fmtMin(res.end)}")
            }
            is TaskScheduler.Result.Conflict -> blobReply(
                "$whenLabel ${TaskIntentParser.fmtMin(res.wantStart)}–${TaskIntentParser.fmtMin(res.wantEnd)} 已经有" +
                    "「${res.conflict.title}」了，我没动你的计划。\n换个时间，或者直接说「挪到晚上」。"
            )
            is TaskScheduler.Result.NoSlot -> {
                val free = res.free.joinToString("、") { "${TaskIntentParser.fmtMin(it.first)}–${TaskIntentParser.fmtMin(it.second)}" }
                blobReply(
                    "$whenLabel ${TaskIntentParser.fmtMin(res.from)}–${TaskIntentParser.fmtMin(res.to)} 里没有连续的 " +
                        "${TaskIntentParser.fmtDur(res.dur)} 空档${if (free.isNotEmpty()) "（那段时间还有：$free）" else ""}。" +
                        "\n我没动你的计划 —— 你说个具体时间，或者换一天？"
                )
            }
            TaskScheduler.Result.OutOfRange -> blobReply("$whenLabel 那个时间超出我能排的范围（08:00–22:00），我没动你的计划。")
        }
    }

    /**
     * 改期 / 删除计划。
     * 以前只认「蛋黄刚排进去的那一条」（lastScheduled），AI 通过接口写入的条目没有这个指针，
     * 于是会出现"能写不能删/不能改"。现在：
     *  - 删除先按事项名/日期在整张计划表里找，找不到再用 lastScheduled；
     *  - 改期同样支持「把高数改到明天下午」这种点名说法。
     * @return 是否已处理（true = 不要再走后面的链路）
     */
    private suspend fun handleTaskEdit(text: String): Boolean {
        val t = text.trim()
        val today = LocalDate.now()

        // ---- 删掉 ----
        val delete = PlanDeleteMatcher.parseLocal(t, today)
        if (delete != null) {
            val targets = PlanDeleteMatcher.match(repo.eventsOnce(), delete, today, lastScheduled)
            if (targets.isNotEmpty()) {
                removeEvents(targets)
                return true
            }
            // 本地没找到：配了 AI 就交给蛋黄带着计划表再判断；没配就直接说明
            if (!aiConfig.value.chatReady) {
                val what = delete.title.ifBlank { "这条计划" }
                blobReply("我在计划里没找到「$what」。说具体点，比如「删掉周三的高数」。")
                return true
            }
            return false
        }

        // ---- 改到 XX ----
        val editVerb = Regex("^(帮我)?(改|挪|换|移|调|推|提前|推迟)").containsMatchIn(t) ||
            Regex("把.+?(改|挪|换|移|调|推|提前|推迟)").containsMatchIn(t)
        if (!editVerb) return false
        val named = PlanDeleteMatcher.parseEditTarget(t, today)
        // 点了名就按名字找，别拿"最后一次写的那条"顶替；没点名才用 lastScheduled
        val ev = if (named != null) {
            PlanDeleteMatcher.match(repo.eventsOnce(), named, today).firstOrNull() ?: return false
        } else {
            lastScheduled ?: return false
        }
        val period = TaskIntentParser.parsePeriod(t)
        val range = TaskIntentParser.parseRange(t, period?.label)
        val point = if (range != null) null else TaskIntentParser.parsePoint(t, period?.label)
        val dur = TaskIntentParser.parseDuration(t) ?: (ev.endMin - ev.startMin)   // 没提时长 → 沿用原来的
        // 说了新日期就换天；只说"改到晚上"就还是原来那天
        val hasDayWord = Regex("今天|明天|后天|周|星期|礼拜").containsMatchIn(t)
        val originalDate = LocalDate.ofEpochDay(ev.epochDay)
        val (dow, weekOffset) = if (hasDayWord) {
            TaskIntentParser.parseDate(t, today)
        } else {
            (LocalDate.ofEpochDay(ev.epochDay).dayOfWeek.value - 1) to 0
        }
        val date = if (hasDayWord) dateOf(dow, weekOffset, today) else originalDate
        val whenLabel = TaskIntentParser.dayLabel(dow, weekOffset, today)
        val intent = TaskIntentParser.TaskIntent(
            title = ev.title,
            dayOfWeek = dow, weekOffset = weekOffset,
            durationMin = dur, durationExplicit = true,
            range = range, point = point,
            period = if (range != null || point != null) null else period
        )
        // 把自己摘出去再算占用（否则"往前挪半小时"会跟自己撞）
        val outcome = repo.scheduleTask(intent, date, replacing = ev)
        when (val res = outcome.decision) {
            is TaskScheduler.Result.Ok -> {
                lastScheduled = outcome.saved
                blobReply("挪好了：$whenLabel ${TaskIntentParser.fmtMin(res.start)}–${TaskIntentParser.fmtMin(res.end)}，还是「${ev.title}」。")
                showToast("已改到 ${TaskIntentParser.fmtMin(res.start)}–${TaskIntentParser.fmtMin(res.end)}")
            }
            is TaskScheduler.Result.Conflict -> blobReply(
                "$whenLabel ${TaskIntentParser.fmtMin(res.wantStart)}–${TaskIntentParser.fmtMin(res.wantEnd)} 有" +
                    "「${res.conflict.title}」，这次没挪成。换个时间？"
            )
            is TaskScheduler.Result.NoSlot -> blobReply("$whenLabel 那段没有 ${TaskIntentParser.fmtDur(dur)} 的连续空档，这次没挪成。")
            TaskScheduler.Result.OutOfRange -> blobReply("那个时间超出可排范围（08:00–22:00），没挪成。")
        }
        return true
    }

    /** 删除若干条计划并给出可撤销提示；本地「删掉高数」和蛋黄返回的 delete 都走这里 */
    private suspend fun removeEvents(targets: List<PlanEventEntity>) {
        targets.forEach { repo.deleteEvent(it) }
        if (targets.any { it.id == lastScheduled?.id }) lastScheduled = null
        val detail = targets.joinToString("；") {
            val d = LocalDate.ofEpochDay(it.epochDay)
            "「${it.title}」${d.monthValue}月${d.dayOfMonth}日 ${TaskIntentParser.fmtMin(it.startMin)}–${TaskIntentParser.fmtMin(it.endMin)}"
        }
        blobReply("好，删掉了：$detail。计划表已经改回来了。")
        showToast(
            if (targets.size == 1) "已删除「${targets[0].title}」" else "已删除 ${targets.size} 条计划",
            "撤销"
        ) {
            viewModelScope.launch { targets.forEach { repo.reinsertEvent(it) } }
        }
    }

    /** 「记一下 / 加到清单」→ 手动加一条。放在排期判定之后，避免"记一下明天三点开会"被误判成清单。 */
    private fun handleAddToInbox(text: String): Boolean {
        val m = Regex("^(?:帮我)?(?:记一下|记一笔|记个|加一条|加个|添加到清单|加到清单|加入清单|记到清单)[：:，,\\s]*(.*)${'$'}")
            .find(text.trim()) ?: return false
        val body = m.groupValues[1].trim()
        if (body.length < 2) return false
        addManualMessage(body, "")
        blobReply("记下了：「$body」。已经放进清单的「待处理」里。")
        return true
    }

    fun sendChat(text: String, onNeedSetup: () -> Unit) = sendChat(text, emptyList(), onNeedSetup)

    /** 发送文字与已确认的图片草稿；图片先展示在当前对话，暂不伪称已上传给模型。 */
    fun sendChat(text: String, imageUris: List<String>, onNeedSetup: () -> Unit) {
        val t = text.trim()
        if ((t.isEmpty() && imageUris.isEmpty()) || _chatSending.value) return
        imageUris.forEach { appendChat(ChatMsg("me", "", imageUri = it)) }
        if (t.isNotEmpty()) appendChat(ChatMsg("me", t))   // 同时完成跨天检查与落盘

        _chatSending.value = true
        viewModelScope.launch {
            try {
                if (t.isNotEmpty() && imageUris.isEmpty() && runLocalIntent(t)) return@launch

                // 本地能力仍先于 API 配置检查。
                val blocks = ScheduleParser.parse(t)
                if (blocks.isNotEmpty()) {
                    val monday = LocalDate.now().with(ChronoField.DAY_OF_WEEK, 1)
                    val added = mutableListOf<String>()
                    val rejected = mutableListOf<String>()
                    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
                    for (b in blocks) {
                        val date = monday.plusDays(b.dayOfWeek.toLong())
                        val task = TaskIntentParser.TaskIntent(
                            title = b.title, dayOfWeek = b.dayOfWeek, weekOffset = 0,
                            durationMin = b.endMin - b.startMin, durationExplicit = true,
                            range = b.startMin to b.endMin, point = null, period = null
                        )
                        val outcome = repo.scheduleTask(task, date, repeatWeekly = true, color = "blue")
                        val label = "周${dayNames[b.dayOfWeek]} ${fmtMin(b.startMin)}–${fmtMin(b.endMin)} ${b.title}"
                        if (outcome.saved != null) added += label else rejected += label
                    }
                    blobReply(buildList {
                        if (added.isNotEmpty()) add("已帮你排进「计划」：\n${added.joinToString("\n")}")
                        if (rejected.isNotEmpty()) add("以下安排与现有计划冲突或超出范围，我没写入：\n${rejected.joinToString("\n")}")
                    }.joinToString("\n\n"))
                    showToast(if (added.isEmpty()) "未加入：请调整冲突的时间" else "已加入 ${added.size} 项计划")
                    return@launch
                }

                val cfg = aiConfig.value
                if (!cfg.chatReady) {
                    onNeedSetup()
                    return@launch
                }
                val history = mutableListOf<com.lvjian.app.ai.AiClient.ChatTurn>()
                _chatMsgs.value.forEach { msg ->
                    val role = if (msg.who == "me") "user" else "assistant"
                    if (msg.imageUri != null && history.lastOrNull()?.role == role && history.last().text.isBlank()) {
                        val last = history.removeAt(history.lastIndex)
                        history += last.copy(imageUris = last.imageUris + msg.imageUri)
                    } else if (msg.imageUri != null) {
                        history += com.lvjian.app.ai.AiClient.ChatTurn(role, "", listOf(msg.imageUri))
                    } else if (msg.text.isNotBlank() && history.lastOrNull()?.role == role && history.last().imageUris.isNotEmpty() && history.last().text.isBlank()) {
                        val last = history.removeAt(history.lastIndex)
                        history += last.copy(text = msg.text)
                    } else if (msg.text.isNotBlank()) {
                        history += com.lvjian.app.ai.AiClient.ChatTurn(role, msg.text)
                    }
                }
                val reply = repo.chatAsk(cfg, history, repo.eventsOnce())
                val saved = mutableListOf<String>()
                val deleted = mutableListOf<PlanEventEntity>()
                val missed = mutableListOf<String>()
                var workingPlans = repo.eventsOnce()
                // 先删后加：模型改时间时会同时给 delete 和 plan，反过来的话按标题匹配可能把新加的删掉
                reply.second.sortedBy { if (it.kind == "plan_del") 0 else 1 }.forEach { act ->
                    if (act.kind == "inbox") {
                        repo.addManual(act.title, "")
                        saved += "清单：${act.title}"
                    } else if (act.kind == "plan") {
                        val day = runCatching { java.time.LocalDate.parse(act.date.take(10)) }.getOrNull()
                        val start = parseHm(act.start)
                        val end = parseHm(act.end)
                        if (day != null && start != null && end != null && end > start) {
                            val event = com.lvjian.app.data.PlanEventEntity(
                                epochDay = day.toEpochDay(),
                                startMin = start,
                                endMin = end,
                                title = act.title,
                                place = act.place,
                                color = "blue",
                                repeatWeekly = false
                            )
                            val id = repo.addEvent(event)
                            // 记下刚写的那条：下一句「删掉/改到晚上」不用再猜
                            val written = event.copy(id = id)
                            lastScheduled = written
                            workingPlans = workingPlans + written
                            saved += "${act.date} ${act.start}–${act.end} ${act.title}"
                        }
                    } else if (act.kind == "plan_del") {
                        val request = PlanDeleteMatcher.Request(
                            id = act.id, title = act.title, date = act.date
                        )
                        val targets = PlanDeleteMatcher.match(
                            workingPlans.filter { e -> deleted.none { it.id == e.id } },
                            request, LocalDate.now(), lastScheduled
                        )
                        if (targets.isEmpty()) {
                            missed += act.title.ifBlank { act.date.ifBlank { "第 ${act.id} 条" } }
                        } else {
                            targets.forEach { repo.deleteEvent(it) }
                            deleted += targets
                            if (targets.any { it.id == lastScheduled?.id }) lastScheduled = null
                        }
                    }
                }
                if (deleted.isNotEmpty()) {
                    showToast(
                        if (deleted.size == 1) "已删除「${deleted[0].title}」" else "已删除 ${deleted.size} 条计划",
                        "撤销"
                    ) {
                        viewModelScope.launch { deleted.forEach { repo.reinsertEvent(it) } }
                    }
                }
                val spoken = buildString {
                    append(reply.first.ifBlank { "……我一时没想好怎么回。" })
                    if (saved.isNotEmpty()) append("\n已写入 ${saved.size} 条。")
                    if (deleted.isNotEmpty()) {
                        append("\n已从计划里删掉 ${deleted.size} 条：")
                        append(deleted.joinToString("、") { it.title })
                    }
                    if (missed.isNotEmpty()) {
                        append("\n没找到要删的：${missed.joinToString("、")}")
                    }
                }
                blobReply(spoken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                blobReply("刚刚没完成（${e.message ?: "请稍后重试"}）。消息我留着；如果是在聊天，请检查「设置 → AI」里的接口配置。")
            } finally {
                _chatSending.value = false
            }
        }
    }

    /** 蛋黄发图片（只展示，不上传；不落盘 —— uri 重启后多半失效） */
    fun sendChatImage(uri: String) {
        appendChat(ChatMsg("me", "", imageUri = uri))
    }

    private fun parseHm(raw: String): Int? {
        val m = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""").find(raw) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    companion object {
        /** 分钟数 → "HH:MM" */
        fun fmtMin(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
    }
}
