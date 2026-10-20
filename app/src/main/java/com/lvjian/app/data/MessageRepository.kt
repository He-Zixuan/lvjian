// 消息仓储：采集入库管线（通知处置[撤掉/重发] → 去重 → 营销前置 → 本地规则 → AI 规则）
// 所有写操作在 IO 协程中由监听服务调用；读操作通过 Flow 供 UI 订阅
// 过滤规则按全局开关进「已过滤」或直接消失；保留规则命中后留在待处理，不再交给别的规则。
package com.lvjian.app.data

import android.content.Context
import com.lvjian.app.ai.AiClient
import com.lvjian.app.notify.LvJianListenerService
import com.lvjian.app.notify.NotificationHelper
import com.lvjian.app.notify.ParsedMessage
import com.lvjian.app.rules.BatchAiFilter
import com.lvjian.app.rules.MarketingLibrary
import com.lvjian.app.rules.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lvjian.app.notify.PlanReminderScheduler
import com.lvjian.app.util.TaskIntentParser
import java.time.LocalDate

class MessageRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiClient = AiClient(context)

    private val messages get() = db.messageDao()
    private val rules get() = db.ruleDao()
    private val groups get() = db.groupDao()
    private val apps get() = db.monitoredAppDao()
    private val events get() = db.planEventDao()

    private val planActions = PlanEventActions(object : PlanEventActions.Store {
        override suspend fun all() = events.all()
        override suspend fun insert(event: PlanEventEntity) = events.insert(event)
        override suspend fun update(event: PlanEventEntity) = events.update(event)
        override suspend fun delete(event: PlanEventEntity) = events.delete(event)
        override suspend fun restore(event: PlanEventEntity) { events.reinsert(event) }
    }) {
        withContext(Dispatchers.IO) {
            val enabled = prefs.currentPlanReminderEnabled()
            val lead = prefs.currentPlanReminderLeadMin()
            val previous = prefs.currentPlanAlarmIds()
            val scheduled = PlanReminderScheduler.reschedule(context, events.all(), enabled, lead, previous)
            prefs.setPlanAlarmIds(scheduled)
        }
    }

    init {
        // v0.5.0 升级说明的展示判定：monitored_apps 为空 = 全新安装（默认全收集，无需升级提示），
        // 直接标记已看；非空 = 从旧版升级（旧版会预置三应用），保留标记让 UI 弹一次可关闭的升级说明
        scope.launch {
            if (apps.count() == 0 && !prefs.currentV5NoticeSeen()) {
                prefs.setV5NoticeSeen()
            }
        }
    }

    // ---------- UI 订阅流 ----------
    fun pendingMessages(): Flow<List<MessageEntity>> = messages.pendingFlow()
    fun doneMessages(): Flow<List<MessageEntity>> = messages.doneFlow()
    fun filteredMessages(): Flow<List<MessageEntity>> = messages.filteredFlow()
    fun filteredCount(): Flow<Int> = messages.filteredCountFlow()
    fun allRules(): Flow<List<RuleEntity>> = rules.allFlow()
    fun allGroups(): Flow<List<GroupEntity>> = groups.allFlow()
    fun monitoredApps(): Flow<List<MonitoredAppEntity>> = apps.allFlow()
    fun allEvents(): Flow<List<PlanEventEntity>> = events.allFlow()

    /** 监听服务的启用包名集合：只处理推荐应用及用户显式开启的应用。 */
    fun enabledAppPackages(): Flow<Set<String>> =
        apps.allFlow().map { rows ->
            val states = rows.associate { it.packageName to it.enabled }
            val knownPackages = (KnownApps.PRESET.map { it.first } + states.keys).toSet()
            knownPackages.filterTo(mutableSetOf()) { packageName ->
                KnownApps.isCollecting(packageName, states[packageName])
            }
        }

    /** 监听服务的排除包名集合：未建档的非推荐应用默认排除，用户显式开启时移出集合。 */
    fun excludedAppPackages(): Flow<Set<String>> =
        apps.allFlow().map { rows ->
            val states = rows.associate { it.packageName to it.enabled }
            val knownPackages = (KnownApps.PRESET.map { it.first } + states.keys).toSet()
            knownPackages.filterTo(mutableSetOf()) { packageName ->
                KnownApps.isCollecting(packageName, states[packageName])
            }.let { enabled -> knownPackages - enabled }
        }

    // ---------- 清单操作 ----------
    suspend fun setStatus(id: Long, status: Int) = messages.setStatus(id, status)
    suspend fun unfilter(id: Long) = messages.unfilter(id)
    suspend fun messageById(id: Long) = messages.byId(id)
    suspend fun reinsertMessage(msg: MessageEntity) = messages.reinsert(msg)

    /** 清空某分段（撤销用：先取出再删，撤销时整批插回） */
    suspend fun clearSegment(filtered: Boolean): List<MessageEntity> {
        val removed = if (filtered) messages.allFiltered() else messages.allDone()
        if (filtered) messages.clearFiltered() else messages.clearDone()
        return removed
    }

    /** 手动添加一条到清单（清单页「+」；来源标记为手动） */
    suspend fun addManual(title: String, note: String) {
        val now = System.currentTimeMillis()
        // v7.1：标题放宽为选填（用户要求）。只写了备注时把备注当正文，
        // 两条都空由调用方拦掉 —— 这里兜个底，不留空条目。
        val body = when {
            title.isBlank() -> note.trim()
            note.isBlank() -> title.trim()
            else -> "${title.trim()}\n${note.trim()}"
        }
        messages.insert(
            MessageEntity(
                appPackage = MessageEntity.MANUAL_PACKAGE,
                groupName = "手动添加",
                isGroup = false,
                sender = "我",
                text = body,
                timestamp = now
            )
        )
    }

    // ---------- 规则操作 ----------
    suspend fun addRule(rule: RuleEntity) = rules.insert(rule)
    suspend fun updateRule(rule: RuleEntity) = rules.update(rule)
    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = rules.setEnabled(id, enabled)
    suspend fun deleteRule(rule: RuleEntity) = rules.delete(rule)
    suspend fun reinsertRule(rule: RuleEntity) = rules.reinsert(rule)

    // ---------- 群操作（通知方式三选一：监控/静默/隐藏，见 GroupMode）----------
    suspend fun setGroupMode(appPackage: String, name: String, mode: Int) =
        groups.setMode(appPackage, name, mode)

    // ---------- 收集应用操作（默认全收集 + 显式排除） ----------
    /**
     * 设置应用收集开关；该应用首次操作时顺带建档（记录展示名）。
     * enabled=false = 显式排除（跨重启保留）；true = 恢复收集。
     * 注意：本方法不处理接管联动——关闭收集前若接管仍有效，UI 须先确认，
     * 确认后调 [disableAppAndTakeover]；恢复收集后接管保持关闭，须重新主动开启。
     */
    suspend fun setAppEnabled(packageName: String, appName: String, enabled: Boolean) {
        val existing = apps.byPackage(packageName)
        if (existing == null) {
            apps.upsert(MonitoredAppEntity(packageName, appName, enabled, preset = false))
        } else {
            apps.upsert(existing.copy(appName = appName.ifBlank { existing.appName }, enabled = enabled))
        }
    }

    /** 一键收集全部应用：显式开启当前安装应用，同时保留接管及其它设置。 */
    suspend fun enableAllApps(packageNames: List<Pair<String, String>>) {
        val existing = apps.all().associateBy { it.packageName }
        val updates = packageNames.distinctBy { it.first }.mapNotNull { (packageName, appName) ->
            val row = existing[packageName]
            when {
                row == null -> MonitoredAppEntity(packageName, appName, enabled = true, preset = false)
                !row.enabled -> row.copy(enabled = true, appName = appName.ifBlank { row.appName })
                else -> null
            }
        }
        if (updates.isNotEmpty()) apps.upsertAll(updates)
    }

    suspend fun setInstalledAppsCollection(packageNames: List<Pair<String, String>>, enabled: Boolean) {
        val existing = apps.all().associateBy { it.packageName }
        val updates = AppCollectionPolicy.setInstalledApps(packageNames, existing.values.toList(), enabled)
        if (updates.isNotEmpty()) apps.upsertAll(updates)
    }

    /** 关闭收集 + 同时退出接管（用户已在 UI 确认过联动影响） */
    suspend fun disableAppAndTakeover(packageName: String, appName: String) {
        setAppEnabled(packageName, appName, false)
        apps.setTakeover(packageName, TakeoverState.OFF, channelOk = false)
    }

    /** 应用是否正在收集（未建档 = 默认收集） */
    suspend fun isAppCollecting(packageName: String): Boolean =
        KnownApps.isCollecting(packageName, apps.byPackage(packageName)?.enabled)

    // ---------- 应用级通知接管 ----------
    /** 进入/更新接管配置（向导中）：状态置为 CONFIGURING，记录渠道确认位 */
    suspend fun setTakeoverConfiguring(packageName: String, appName: String, channelOk: Boolean) {
        ensureAppRow(packageName, appName)
        apps.setTakeover(packageName, TakeoverState.CONFIGURING, channelOk)
    }

    /** 向导完成：开启接管（调用前 UI 已用 TakeoverPolicy.canEnableTakeover 校验） */
    suspend fun enableTakeover(packageName: String, appName: String, channelOk: Boolean) {
        ensureAppRow(packageName, appName)
        apps.setTakeover(packageName, TakeoverState.ACTIVE, channelOk)
    }

    /** 关闭接管：立即停止对后续原通知的撤销/重发；来源应用的设置只能由用户自行恢复 */
    suspend fun disableTakeover(packageName: String) {
        val existing = apps.byPackage(packageName) ?: return
        apps.setTakeover(packageName, TakeoverState.OFF, existing.takeoverChannelOk)
    }

    /** 权限失效时降级为暂停（采集管线内部调用；保留渠道确认位以便恢复后直接用） */
    suspend fun suspendTakeover(packageName: String) {
        val existing = apps.byPackage(packageName) ?: return
        if (existing.takeover == TakeoverState.ACTIVE) {
            apps.setTakeover(packageName, TakeoverState.SUSPENDED, existing.takeoverChannelOk)
        }
    }

    /** 暂停/配置中的接管在权限恢复后重新启用（详情页按钮） */
    suspend fun resumeTakeover(packageName: String) {
        val existing = apps.byPackage(packageName) ?: return
        if (existing.takeover == TakeoverState.SUSPENDED || existing.takeover == TakeoverState.CONFIGURING) {
            apps.setTakeover(packageName, TakeoverState.ACTIVE, existing.takeoverChannelOk)
        }
    }

    /** 应用接管状态的一次性读取（管线用）：未建档 = 未接管 */
    suspend fun takeoverOf(packageName: String): MonitoredAppEntity? = apps.byPackage(packageName)

    private suspend fun ensureAppRow(packageName: String, appName: String) {
        if (apps.byPackage(packageName) == null) {
            apps.upsert(
                MonitoredAppEntity(
                    packageName,
                    appName,
                    enabled = KnownApps.collectingByDefault(packageName),
                    preset = false
                )
            )
        }
    }

    /** 自定义导入目标（「其它」应用）：只记名字，包名用伪包名占位 */
    suspend fun ensureCustomApp(name: String): String {
        val pkg = "custom:${name.trim()}"
        if (apps.byPackage(pkg) == null) {
            apps.upsert(MonitoredAppEntity(pkg, name.trim(), enabled = KnownApps.collectingByDefault(pkg), preset = false))
        }
        return pkg
    }

    /**
     * 批量导入群（截图 OCR 确认后调用）：默认监控开、免打扰关，来源标记为导入。
     * 已存在的（应用 + 群名）不覆盖原有模式。
     */
    suspend fun importGroups(appPackage: String, names: List<String>): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            if (groups.byKey(appPackage, name) == null) {
                groups.upsert(
                    GroupEntity(
                        appPackage = appPackage,
                        name = name,
                        isGroup = true,
                        mode = GroupMode.REMIND,  // 导入即「监控」档：收集 + 有声提醒
                        source = GroupSource.IMPORTED,
                        firstSeen = now,
                        lastSeen = now,
                        msgCount = 0
                    )
                )
                added++
            }
        }
        return added
    }

    // ---------- 计划时间块 ----------
    suspend fun addEvent(ev: PlanEventEntity): Long = planActions.add(ev)
    suspend fun updateEvent(ev: PlanEventEntity) = planActions.update(ev)
    suspend fun deleteEvent(ev: PlanEventEntity) = planActions.delete(ev)
    suspend fun reinsertEvent(ev: PlanEventEntity) = planActions.restore(ev)
    /** 一次性读取全部计划：对话里按事项名找目标用 */
    suspend fun eventsOnce(): List<PlanEventEntity> = planActions.all()
    suspend fun reschedulePlanReminders() = planActions.refresh()
    suspend fun scheduleTask(intent: TaskIntentParser.TaskIntent, date: LocalDate,
        replacing: PlanEventEntity? = null, repeatWeekly: Boolean = false, color: String = "teal") =
        planActions.schedule(intent, date, replacing, repeatWeekly, color)

    // ---------- 全局开关（Prefs 透传，服务侧要一次性读）----------
    suspend fun saveKeepFiltered(v: Boolean) = prefs.setKeepFiltered(v)
    /** 保存开启了营销过滤的应用集合（v0.5.0：按应用勾选） */
    suspend fun saveMarketingApps(pkgs: Set<String>) = prefs.setMarketingApps(pkgs)

    // ---------- 采集入库管线 ----------

    /**
     * 处理一条通知解析出的全部消息（MessagingStyle 一条通知可能含多条）。
     * 通知处置（撤掉/重发）按会话只做一次；逐条消息的入库与过滤在循环里做。
     * @param msgs 解析结果（同一会话，按时间升序）
     * @param notificationKey 系统通知 key（用于撤掉原通知）
     * @param contentIntent 原通知的点击跳转（重发时转交，点按仍跳回来源应用会话）
     */
    suspend fun ingestAll(
        msgs: List<ParsedMessage>,
        notificationKey: String?,
        contentIntent: android.app.PendingIntent?
    ) {
        if (msgs.isEmpty()) return
        val first = msgs.first()

        // 1) 群注册（首次见到自动建档，默认 REMIND=监控：收集+有声重发），并查当前通知方式
        val existing = groups.byKey(first.appPackage, first.groupName)
        val group = existing ?: GroupEntity(
            appPackage = first.appPackage,
            name = first.groupName,
            isGroup = first.isGroup,
            mode = GroupMode.REMIND,
            source = GroupSource.AUTO,
            firstSeen = first.timestamp,
            lastSeen = first.timestamp,
            msgCount = 0
        ).also { groups.upsert(it) }

        // 2) 预判定每条消息是否命中过滤规则（营销词库/本地规则），
        //    通知处置与后续入库共用这份结果，避免重复跑词库
        val mktApps = prefs.currentMarketingApps()
        val enabledRules = rules.enabledRules()
        val hitOf = { m: ParsedMessage -> localVerdictOf(m, mktApps, enabledRules) }

        // 3) 通知处置（每条通知一次，与去重无关——来源应用更新通知时也要维持处置结果，
        //    否则被撤掉的原通知会以更新形态重新出现在通知栏）。
        //    v0.5.0 起加应用级前置条件：只有「接管已启用」的应用才会撤/重发原通知；
        //    只收集（默认）模式下一律不动原通知，过滤规则命中与否都不影响提醒（任务书 05 §5.2）。
        val appRow = apps.byPackage(first.appPackage)
        val takeover = appRow?.takeover ?: TakeoverState.OFF
        val canRepost = NotificationHelper.canPostNotifications(context) &&
            !NotificationHelper.alertChannelBlocked(context)
        val action = com.lvjian.app.notify.TakeoverPolicy.decide(
            com.lvjian.app.notify.PolicyInput(
                collecting = KnownApps.isCollecting(first.appPackage, appRow?.enabled),
                takeover = takeover,
                groupMode = group.mode,
                canPostNotifications = NotificationHelper.canPostNotifications(context),
                alertChannelBlocked = NotificationHelper.alertChannelBlocked(context)
            )
        )
        // 提醒档需要重发但发不出：先降级为「已暂停」，绝不先撤原通知再发现发不出去
        if (takeover == TakeoverState.ACTIVE && group.mode == GroupMode.REMIND && !canRepost) {
            suspendTakeover(first.appPackage)
        }
        val hidden = action == com.lvjian.app.notify.NotificationAction.CANCEL && notificationKey != null
        if (hidden && notificationKey != null) {
            LvJianListenerService.instance?.cancelNotification(notificationKey)
        } else if (action == com.lvjian.app.notify.NotificationAction.CANCEL_AND_REPOST && notificationKey != null) {
            LvJianListenerService.instance?.cancelNotification(notificationKey)
            val latestKept = msgs.lastOrNull { hitOf(it)?.keep != false }
            if (latestKept != null) {
                NotificationHelper.repost(context, latestKept, contentIntent)
            }
        }

        // 4) 逐条入库（去重、规则、AI 分流）
        for (msg in msgs) {
            // 去重：同应用同群同人同文同时间戳视为通知更新导致的重复
            val dup = messages.countDuplicate(msg.appPackage, msg.groupName, msg.sender, msg.text, msg.timestamp)
            if (dup > 0) continue
            ingestOne(msg, hidden, hitOf(msg), enabledRules)
        }
    }

    /**
     * 本地结论：营销词库命中视为过滤；否则看关键词/按人。
     * keep=true 表示保留规则命中，消息留在待处理，不再走 AI。
     * 未命中返回 null。
     */
    private fun localVerdictOf(
        msg: ParsedMessage,
        mktApps: Set<String>,
        enabledRules: List<RuleEntity>
    ): RuleEngine.LocalVerdict? {
        val marketingHit = msg.appPackage in mktApps &&
            MarketingLibrary.matches(context, msg.text)
        if (marketingHit) {
            return RuleEngine.LocalVerdict(
                RuleEntity(name = "过滤营销通知", type = RuleType.KEYWORD, pattern = ""),
                keep = false
            )
        }
        return RuleEngine.matchLocal(
            enabledRules, msg.appPackage, msg.groupName, msg.sender, msg.text
        )
    }

    /** 单条消息的入库管线：命中去向 → 入库 → AI 分流 */
    private suspend fun ingestOne(
        msg: ParsedMessage,
        hidden: Boolean,
        verdict: RuleEngine.LocalVerdict?,
        enabledRules: List<RuleEntity>
    ) {
        val filterName = if (verdict != null && !verdict.keep) verdict.rule.name else null
        // 群聊里 @ 了别人，不是 @我，也不是 @所有人：不进待办。
        val mentionOthers = msg.isGroup && mentionsSomeone(msg.text) && !mentionsMe(msg.text)

        // 过滤规则 +「直接消失」：不入库。保留规则不受这个开关影响。
        if ((filterName != null || mentionOthers) && !prefs.currentKeepFiltered()) {
            groups.touch(msg.appPackage, msg.groupName, msg.timestamp, 1)
            return
        }

        val id = messages.insert(
            MessageEntity(
                appPackage = msg.appPackage,
                groupName = msg.groupName,
                isGroup = msg.isGroup,
                sender = msg.sender,
                text = msg.text,
                timestamp = msg.timestamp,
                intercepted = hidden,
                filtered = filterName != null || mentionOthers,
                matchedRule = verdict?.rule?.name ?: if (mentionOthers) "只保留@我和@所有人" else null,
                aiState = AIState.NONE
            )
        )
        groups.touch(msg.appPackage, msg.groupName, msg.timestamp, 1)

        // 本地已有结论（过滤或保留）就不再交给 AI 过滤。
        // 保留命中后留在待处理。过滤命中走上面的去向。
        // 完全未命中、且有适用 AI 规则：实时模式下立刻判定；批量模式进未整理。
        // 摘要和计划预填跟这次判定一起生成，不等用户点「加入计划」。
        if (verdict == null && !mentionOthers && obviousWork(msg.text)) {
            // @所有人、地点变更、截止、考核这类，不交给模型。模型曾把工作通知判成无用信息。
            scope.launch { writeBriefOnly(id, msg) }
        } else if (verdict == null && !mentionOthers) {
            val aiRules = enabledRules.filter {
                RuleEngine.aiRuleApplies(it, msg.appPackage, msg.groupName)
            }
            if (aiRules.isNotEmpty()) {
                if (prefs.currentAiBatchMode()) {
                    messages.setAiState(id, AIState.PENDING_BATCH)
                } else {
                    scope.launch { runAiFilter(id, msg, aiRules) }
                }
            }
        } else if (verdict?.keep == true) {
            scope.launch { writeBriefOnly(id, msg) }
        }
    }

    // ---------- 一键整理（批量 AI 判定） ----------

    /** 未整理条目流/计数（清单页「未整理」段） */
    fun pendingBatchMessages(): Flow<List<MessageEntity>> = messages.pendingBatchFlow()
    fun pendingBatchCount(): Flow<Int> = messages.pendingBatchCountFlow()

    /** AI 判定方式流（设置页 segmented） */
    fun aiBatchModeFlow(): Flow<Boolean> = prefs.aiBatchMode
    suspend fun saveAiBatchMode(v: Boolean) = prefs.setAiBatchMode(v)

    /**
     * 一键整理：把全部未整理条目交给批量引擎判定。
     * @param onProgress 进度回调（已成功保存条数/本次总条数），UI 更新操作卡文案
     * @return 整理结果汇总；API 未配置返回 null
     */
    suspend fun organizePendingBatch(onProgress: (done: Int, total: Int) -> Unit): BatchAiFilter.BatchResult? {
        val config = prefs.currentAiConfig()
        if (!config.usable) return null
        val batch = messages.pendingBatchAll()
        if (batch.isEmpty()) return BatchAiFilter.BatchResult(0, 0, 0, 0)
        val enabledRules = rules.enabledRules()
        val keepFiltered = prefs.currentKeepFiltered()
        val engine = BatchAiFilter(aiClient, config)
        return engine.run(batch, enabledRules, onProgress) { msgId, filtered, ruleName, aiState, summary ->
            messages.setFilterResult(msgId, filtered, ruleName, aiState)
            // 批量判定同时产出摘要：保留的条目写进清单，列表不再回落成原文首行
            if (summary.isNotBlank()) messages.setSummary(msgId, summary)
            // 「已过滤直接消失」开关关闭时：条目保留在已过滤；开关开（默认进复查）时同理走 filtered 标记
            // keepFiltered=false 的语义是命中后不留痕，这里沿用：直接删除
            if (filtered && !keepFiltered) messages.deleteById(msgId)
        }
    }

    /**
     * 实时判定：一次调用同时得到滤不滤、摘要、计划预填。
     * 本地保留规则已经在入库前挡住，这里只看过滤规则和 AI 规则。
     * 模型若把保留规则判成过滤，这里再挡一次：保留优先。
     * 调用失败：消息留在待处理，不当作无用信息丢掉；摘要留空，列表显示原文。
     */
    private suspend fun runAiFilter(messageId: Long, msg: ParsedMessage, aiRules: List<RuleEntity>) {
        val config = prefs.currentAiConfig()
        if (!config.usable) return
        val keepNames = rules.enabledRules()
            .filter { it.action == RuleAction.KEEP && ScopeKeys.covers(it.scopeKeys, msg.appPackage, msg.groupName) }
            .map { it.name }
            .toSet()
        messages.setFilterResult(messageId, filtered = false, rule = null, aiState = AIState.QUEUED)
        try {
            val result = aiClient.judgeAndBrief(
                config,
                rulesBlock(aiRules),
                messageBlock(msg),
                LocalDate.now().toString()
            )
            val blockedByKeep = result.ruleName != null && result.ruleName in keepNames
            val filtered = result.filter && !blockedByKeep
            val ruleName = if (filtered) result.ruleName ?: aiRules.first().name else null
            messages.setFilterResult(
                messageId,
                filtered = filtered,
                rule = ruleName,
                aiState = AIState.DONE
            )
            if (filtered && !prefs.currentKeepFiltered()) {
                messages.deleteById(messageId)
                return
            }
            saveBrief(messageId, result)
        } catch (e: Exception) {
            messages.setFilterResult(messageId, filtered = false, rule = null, aiState = AIState.FAILED)
        }
    }

    /** 微信、QQ 在 @本人时会在通知里写「有人@我」。@所有人也留。 */
    private fun mentionsMe(text: String): Boolean =
        text.contains("有人@我") || text.contains("@所有人") || text.contains("@所有成员")

    /** 正文里有 @，但不是上面那两种。这种是 @别人。 */
    private fun mentionsSomeone(text: String): Boolean =
        text.contains("@") && !mentionsMe(text)

    /** 正文里已经写明要处理，就不要再问模型滤不滤。 */
    private fun obviousWork(text: String): Boolean {
        val marks = listOf(
            "@所有人", "@所有成员", "截止", "考核", "成绩", "问卷", "提交",
            "地点", "创意园", "到场", "签到", "请确认", "再次确认"
        )
        return marks.any { text.contains(it) }
    }

    /** 本地保留已经命中：不再过滤，但仍提前写摘要和计划预填。 */
    private suspend fun writeBriefOnly(messageId: Long, msg: ParsedMessage) {
        val config = prefs.currentAiConfig()
        if (!config.usable) return
        try {
            val result = aiClient.judgeAndBrief(
                config,
                "没有过滤规则。这条消息已经由保留规则留下，filter 必须是 false。",
                messageBlock(msg),
                LocalDate.now().toString()
            )
            saveBrief(messageId, result.copy(filter = false, ruleName = null))
        } catch (e: Exception) {
            // 摘要失败不影响这条已经留下的消息
        }
    }

    private fun rulesBlock(aiRules: List<RuleEntity>): String =
        aiRules.joinToString("\n") { r ->
            val verb = if (r.action == RuleAction.KEEP) "保留" else "过滤"
            "$verb｜「${r.name}」${r.pattern}"
        }

    private fun messageBlock(msg: ParsedMessage): String =
        "来源：${msg.groupName}\n发送人：${msg.sender}\n正文：${msg.text.take(800)}"

    private suspend fun saveBrief(messageId: Long, result: AiClient.MessageAiResult) {
        val guess = com.lvjian.app.util.PlanGuesser.guess(result.title.ifBlank { result.summary })
        val day = parsePlanDay(result.date) ?: if (result.title.isNotBlank()) guess.epochDay else 0L
        val start = parsePlanMin(result.start)
        val end = parsePlanMin(result.end)
        messages.setBrief(
            id = messageId,
            summary = result.summary,
            planTitle = result.title,
            planPlace = result.place,
            planEpochDay = day,
            planStartMin = start ?: if (result.title.isNotBlank()) guess.startMin else -1,
            planEndMin = end ?: if (start != null) minOf(start + 60, 24 * 60) else if (result.title.isNotBlank()) guess.endMin else -1
        )
    }

    private fun parsePlanDay(raw: String): Long? = try {
        if (raw.length < 8) null else java.time.LocalDate.parse(raw.take(10)).toEpochDay()
    } catch (e: Exception) {
        null
    }

    private fun parsePlanMin(raw: String): Int? {
        val m = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""").find(raw) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    // ---------- AI 配置 ----------
    suspend fun saveAiConfig(cfg: AiConfig) = prefs.saveAiConfig(cfg)
    fun aiConfigFlow(): Flow<AiConfig> = prefs.aiConfig

    /** 蛋黄对话：直接调一次模型（不做过滤判定）。plans 让模型知道计划表里有哪些条目可删。 */
    suspend fun chatAsk(
        cfg: AiConfig,
        history: List<com.lvjian.app.ai.AiClient.ChatTurn>,
        plans: List<PlanEventEntity> = emptyList()
    ) = aiClient.chat(cfg, history, plans)

    /** 拉取可用模型列表（设置页「获取模型列表」） */
    suspend fun fetchModels(cfg: AiConfig): List<String> = aiClient.listModels(cfg)
}
