// DataStore 偏好存储：AI 接口配置、主题、全局过滤开关与各种小偏好
package com.lvjian.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lvjian.app.notify.PlanReminder
import com.lvjian.app.ui.theme.ThemeMode
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 顶层扩展属性，全应用共用一份 DataStore
private val Context.dataStore by preferencesDataStore(name = "lvjian_prefs")

/** AI 接口配置（OpenAI 兼容协议） */
data class AiConfig(
    val baseUrl: String = "",     // 形如 https://api.openai.com/v1
    val apiKey: String = "",
    val model: String = "",       // 如 gpt-4o-mini / deepseek-chat
    val enabled: Boolean = false  // 总开关：关闭时不发任何网络请求
) {
    /** 三项齐全且开关打开才算可用 */
    val usable: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 蛋黄对话只看三项配置齐不齐（不看过滤总开关） */
    val chatReady: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

class Prefs(private val context: Context) {

    private object Keys {
        val API_BASE = stringPreferencesKey("api_base")
        val API_KEY = stringPreferencesKey("api_key")
        val API_MODEL = stringPreferencesKey("api_model")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        // 保活服务是否曾被用户关闭（默认开）
        val KEEPER_ENABLED = booleanPreferencesKey("keeper_enabled")
        // 命中过滤规则的消息去向：进「已过滤」（默认）/ 直接消失。保留规则不走这里。
        val KEEP_FILTERED = booleanPreferencesKey("keep_filtered")
        // 内置营销过滤生效的应用（包名集合，\n 分隔；默认空=不启用）
        // v0.5.0 改「按应用勾选」：只能勾已接管的应用（过滤靠提醒档撤原通知不补发才生效），
        // 旧的「总开关 + 白名单豁免」模型作废——邀请与豁免两套名单并存会互相打架。
        val MARKETING_APPS = stringPreferencesKey("marketing_apps")
        // AI 判定方式：true=批量（攒未整理一键整理，默认）/ false=实时（每条立即判定）
        // 对应设置页「实时整理」开关（默认关）
        val AI_BATCH_MODE = booleanPreferencesKey("ai_batch_mode")
        // 「启用 AI 整理」首次说明弹窗是否已展示过（只弹一次）
        val AI_EXPLAIN_SHOWN = booleanPreferencesKey("ai_explain_shown")
        // 主题模式：system / light / dark
        val THEME_MODE = stringPreferencesKey("theme_mode")
        // 会话通知方式说明弹层的「下次不再提醒」（监控/静默/隐藏三档各自独立，默认都显示）
        // key 字符串沿用旧名以兼容已有数据；语义上 monitor→监控(REMIND)、mute→隐藏(HIDE)
        val NOREMIND_MONITOR = booleanPreferencesKey("noremind_monitor")
        val NOREMIND_MUTE = booleanPreferencesKey("noremind_mute")
        val NOREMIND_QUIET = booleanPreferencesKey("noremind_quiet")
        // 「开启实时整理」费用提醒的「下次不再提醒」（默认 false=每次开启都弹）
        val NOREMIND_REALTIME = booleanPreferencesKey("noremind_realtime")
        // v0.5.0 升级说明（默认全收集/接管改为按需开启）是否已看过；仅升级用户会见到
        val V5_NOTICE_SEEN = booleanPreferencesKey("v5_notice_seen")
        // 计划提醒：全局设置，对所有计划生效
        // 默认「开 + 提前 10 分钟」——与预览端 state.planReminderOn / planLead 保持一致
        val PLAN_REMINDER_ENABLED = booleanPreferencesKey("plan_reminder_enabled")
        val PLAN_REMINDER_LEAD_MIN = intPreferencesKey("plan_reminder_lead_min")
        // 提醒方式：false=响铃（默认，通知栏 + 响铃）/ true=静音（只在通知栏显示）
        // 真机上靠切换通知渠道实现（渠道重要性 App 创建后改不了，见 NotificationHelper）
        val PLAN_REMINDER_SILENT = booleanPreferencesKey("plan_reminder_silent")
        // 已排上闹钟的事件 id（字符串集合）；用于下次全量重排时回收已删除事件残留的闹钟
        val PLAN_ALARM_IDS = stringSetPreferencesKey("plan_alarm_ids")
        // 蛋黄对话的「按天记忆」：整份日志 + 一个日期戳。跨天就整份丢弃（见 ChatLog.isStale）
        val CHAT_LOG = stringPreferencesKey("chat_log")
        val CHAT_LOG_DAY = longPreferencesKey("chat_log_day")
    }

    /** AI 配置流 */
    val aiConfig: Flow<AiConfig> = context.dataStore.data.map { p ->
        AiConfig(
            baseUrl = p[Keys.API_BASE] ?: "",
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.API_MODEL] ?: "",
            enabled = p[Keys.AI_ENABLED] ?: true, // AI 整理默认开启（设置页不再有总开关）
        )
    }

    /** 一次性读取当前 AI 配置（服务内使用） */
    suspend fun currentAiConfig(): AiConfig = aiConfig.first()

    suspend fun saveAiConfig(cfg: AiConfig) {
        context.dataStore.edit { p ->
            p[Keys.API_BASE] = cfg.baseUrl.trim()
            p[Keys.API_KEY] = cfg.apiKey.trim()
            p[Keys.API_MODEL] = cfg.model.trim()
            p[Keys.AI_ENABLED] = cfg.enabled
        }
    }

    // ---- 命中规则的消息去向 ----
    val keepFiltered: Flow<Boolean> = context.dataStore.data.map { it[Keys.KEEP_FILTERED] ?: true }
    suspend fun setKeepFiltered(v: Boolean) { context.dataStore.edit { it[Keys.KEEP_FILTERED] = v } }
    suspend fun currentKeepFiltered(): Boolean = keepFiltered.first()

    // ---- 内置营销过滤（v0.5.0：按应用勾选，不再是全局开关 + 白名单） ----
    /** 开启了营销过滤的应用包名集合（只能从「已接管」的应用里勾选） */
    val marketingApps: Flow<Set<String>> = context.dataStore.data.map { p ->
        (p[Keys.MARKETING_APPS] ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    suspend fun setMarketingApps(pkgs: Set<String>) {
        context.dataStore.edit { it[Keys.MARKETING_APPS] = pkgs.joinToString("\n") }
    }

    /** 一次性读取营销过滤的生效应用（采集管线用，每条通知都要查，避免挂起两次） */
    suspend fun currentMarketingApps(): Set<String> = marketingApps.first()

    // ---- AI 判定方式（实时 / 批量；默认批量） ----
    val aiBatchMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.AI_BATCH_MODE] ?: true }
    suspend fun setAiBatchMode(v: Boolean) { context.dataStore.edit { it[Keys.AI_BATCH_MODE] = v } }
    suspend fun currentAiBatchMode(): Boolean = aiBatchMode.first()

    // ---- 主题 ----
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        when (it[Keys.THEME_MODE]) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }
    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit {
            it[Keys.THEME_MODE] = when (mode) {
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
                ThemeMode.SYSTEM -> "system"
            }
        }
    }

    // ---- 群开关说明「下次不再提醒」----
    val noRemindMonitor: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOREMIND_MONITOR] ?: false }
    suspend fun setNoRemindMonitor(v: Boolean) { context.dataStore.edit { it[Keys.NOREMIND_MONITOR] = v } }

    val noRemindMute: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOREMIND_MUTE] ?: false }
    suspend fun setNoRemindMute(v: Boolean) { context.dataStore.edit { it[Keys.NOREMIND_MUTE] = v } }

    val noRemindQuiet: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOREMIND_QUIET] ?: false }
    suspend fun setNoRemindQuiet(v: Boolean) { context.dataStore.edit { it[Keys.NOREMIND_QUIET] = v } }

    val noRemindRealtime: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOREMIND_REALTIME] ?: false }
    suspend fun setNoRemindRealtime(v: Boolean) { context.dataStore.edit { it[Keys.NOREMIND_REALTIME] = v } }

    val keeperEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.KEEPER_ENABLED] ?: true }

    suspend fun setKeeperEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEPER_ENABLED] = enabled }
    }

    // ---- v0.5.0 升级说明 ----
    val v5NoticeSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.V5_NOTICE_SEEN] ?: false }
    suspend fun setV5NoticeSeen() { context.dataStore.edit { it[Keys.V5_NOTICE_SEEN] = true } }
    suspend fun currentV5NoticeSeen(): Boolean = v5NoticeSeen.first()

    // ---- 计划提醒（全局，对所有计划生效）----
    // 默认「开 + 提前 10 分钟」——与预览端 state.planReminderOn / state.planLead 的默认值保持一致
    val planReminderEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PLAN_REMINDER_ENABLED] ?: true }
    suspend fun setPlanReminderEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.PLAN_REMINDER_ENABLED] = v }
    }
    suspend fun currentPlanReminderEnabled(): Boolean = planReminderEnabled.first()

    val planReminderLeadMin: Flow<Int> =
        context.dataStore.data.map { it[Keys.PLAN_REMINDER_LEAD_MIN] ?: PlanReminder.LEAD_DEFAULT }
    /** 越界的值在这里就夹住，别让脏数据流到闹钟排程里去 */
    suspend fun setPlanReminderLeadMin(v: Int) {
        context.dataStore.edit { it[Keys.PLAN_REMINDER_LEAD_MIN] = PlanReminder.normalizeLead(v) }
    }
    suspend fun currentPlanReminderLeadMin(): Int = planReminderLeadMin.first()

    /** 提醒方式：false=响铃（默认）/ true=静音（只在通知栏显示，不出声不震动） */
    val planReminderSilent: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PLAN_REMINDER_SILENT] ?: false }
    suspend fun setPlanReminderSilent(v: Boolean) {
        context.dataStore.edit { it[Keys.PLAN_REMINDER_SILENT] = v }
    }
    suspend fun currentPlanReminderSilent(): Boolean = planReminderSilent.first()

    // ---- 蛋黄对话的「按天记忆」----
    // 整份日志以文本存一个 key，另存一个日期戳；跨天整份丢弃（判定见 ChatLog.isStale）。

    /** 读回日志：日期戳 + 条目。从没存过时日期戳给 -1（isStale 会判定为过期） */
    suspend fun currentChatLog(): Pair<Long, List<ChatLogEntry>> {
        val p = context.dataStore.data.first()
        return (p[Keys.CHAT_LOG_DAY] ?: -1L) to ChatLog.decode(p[Keys.CHAT_LOG])
    }

    /** 写回日志，同时把日期戳记成今天 —— 这两件事必须一起写，否则会出现"戳是今天但内容是昨天的" */
    suspend fun saveChatLog(today: LocalDate, entries: List<ChatLogEntry>) {
        context.dataStore.edit {
            it[Keys.CHAT_LOG_DAY] = today.toEpochDay()
            it[Keys.CHAT_LOG] = ChatLog.encode(entries)
        }
    }

    /**
     * 上一次真正排上闹钟的事件 id 集合。
     * 全量重排时需要它来回收「已被删除的事件」残留的闹钟 —— 那些 id 不在当前事件列表里，
     * 光靠遍历现有事件是撤不掉的。
     */
    suspend fun currentPlanAlarmIds(): Set<Long> =
        context.dataStore.data.map { p ->
            // DataStore 只存字符串集合，这里转回 Long（脏值直接丢掉）
            (p[Keys.PLAN_ALARM_IDS] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
        }.first()
    suspend fun setPlanAlarmIds(ids: Set<Long>) {
        context.dataStore.edit { it[Keys.PLAN_ALARM_IDS] = ids.map { id -> id.toString() }.toSet() }
    }

    companion object {
        /** 暴露同一份 DataStore 给其他需要小型键值存储的模块 */
        fun dataStoreOf(context: Context) = context.dataStore
    }
}
