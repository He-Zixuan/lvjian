// Room 实体定义：消息、过滤规则、已知群聊、监控应用、计划时间块 五张表
package com.lvjian.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一条采集到的消息（清单条目） */
@Entity(
    tableName = "messages",
    // 多应用后按（应用 + 群）定位会话，避免两个应用的同名群串数据
    indices = [Index("appPackage", "groupName"), Index("timestamp"), Index("status"), Index("filtered")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 来源应用包名（如 com.tencent.mm）；手动添加的条目用 MANUAL_PACKAGE */
    val appPackage: String,
    /** 群名；私聊时为联系人名 */
    val groupName: String,
    /** 是否群聊（由通知是否带会话标题推断） */
    val isGroup: Boolean,
    /** 发送者显示名（同名/改名风险：只是显示名，不是稳定身份） */
    val sender: String,
    /** 正文；图片/语音为占位文本，长文可能被通知截断 */
    val text: String,
    /** 消息时间（取自通知，毫秒） */
    val timestamp: Long,
    /** 本条通知是否已按“伪免打扰”被取消 */
    val intercepted: Boolean = false,
    /** 是否被过滤规则命中（命中的条目不进主清单，进“已过滤”） */
    val filtered: Boolean = false,
    /** 命中的规则名，便于解释“为什么被过滤” */
    val matchedRule: String? = null,
    /** AI 判定状态，见 AIState */
    val aiState: Int = AIState.NONE,
    /** 清单状态，见 MsgStatus */
    val status: Int = MsgStatus.PENDING,
    /** 入库时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * AI 生成的一句话摘要。空表示还没生成，或生成失败，列表这时显示原文第一行。
     * 入库后立刻生成，不等用户点「加入计划」。
     */
    val summary: String = "",
    /** 加入计划的预填：标题。空表示还没生成。 */
    val planTitle: String = "",
    /** 加入计划的预填：地点。 */
    val planPlace: String = "",
    /** 加入计划的预填：日期，epoch day。0 表示没有。 */
    val planEpochDay: Long = 0,
    /** 加入计划的预填：开始分钟。-1 表示没有。 */
    val planStartMin: Int = -1,
    /** 加入计划的预填：结束分钟。-1 表示没有。 */
    val planEndMin: Int = -1,
    /** 合并后指向代表事项；原文仍在本行，0 表示独立清单项。 */
    val mergedIntoId: Long = 0
) {
    companion object {
        /** 手动添加（清单页 +）条目的虚拟包名 */
        const val MANUAL_PACKAGE = "lvjian.manual"
    }
}

/** 清单状态常量 */
object MsgStatus {
    const val PENDING = 0   // 待处理（主清单）
    const val DONE = 1      // 已划去（完成）
    const val IGNORED = 2   // 已划去（忽略）——与 DONE 分开，UI 标签不同
}

/** AI 判定状态常量 */
object AIState {
    const val NONE = 0          // 不需要 AI
    const val QUEUED = 1        // 等待/正在进行 AI 判定（实时模式）
    const val DONE = 2          // AI 已判定
    const val FAILED = 3        // AI 失败（保留条目，不当作闲聊丢弃）
    const val PENDING_BATCH = 4 // 未整理（批量模式攒着，等「一键整理」）
}

/**
 * 过滤规则：可持久保存、反复开关。
 * 作用范围是多选集合（对齐设计稿的 scope 弹层），存在 scopeKeys 里：
 *   "ALL"            —— 全部群（不限应用）
 *   "app:<包名>"      —— 某应用的全部群
 *   "grp:<包名>|<群名>" —— 某应用的某个群
 * 多个 key 用 \n 分隔。
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 规则名，如“家教”“闲聊” */
    val name: String,
    /** 规则类型，见 RuleType */
    val type: Int,
    /** 规则内容：关键词 / 人名 / AI 提示词 */
    val pattern: String,
    /** 作用范围 key 集合（\n 分隔），见 ScopeKeys */
    val scopeKeys: String = ScopeKeys.ALL,
    /** 开关 */
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 命中后做什么。见 [RuleAction]。
     * 旧库没有这一列，迁移时默认 FILTER，已有规则行为不变。
     */
    val action: Int = RuleAction.FILTER,
    /**
     * 系统内置规则。为 true 时不能删除、不能改内容和范围，只能开关。
     * 目前只有一条：「滤掉无用信息」。
     */
    val builtin: Boolean = false
)

/** 作用范围 key 的编解码（统一入口，UI 与规则引擎共用） */
object ScopeKeys {
    const val ALL = "ALL"
    fun app(pkg: String) = "app:$pkg"
    fun group(pkg: String, name: String) = "grp:$pkg|$name"

    fun decode(s: String): List<String> =
        s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(ALL) }

    fun encode(keys: List<String>): String =
        keys.distinct().ifEmpty { listOf(ALL) }.joinToString("\n")

    /** 该 key 集合是否覆盖（应用 + 群） */
    fun covers(keys: String, pkg: String, group: String): Boolean {
        val list = decode(keys)
        return ALL in list || app(pkg) in list || group(pkg, group) in list
    }
}

/** 规则类型常量 */
object RuleType {
    const val KEYWORD = 0    // 关键词：正文包含即命中
    const val PERSON = 1     // 按人：发送者显示名匹配即命中
    const val AI_PROMPT = 2  // AI 语义：把提示词+消息发给模型判定是否命中
}

/**
 * 规则命中后的动作。和「类型」（怎么匹配）是两回事。
 * FILTER：按全局去向进「已过滤」或直接消失。
 * KEEP：留在待处理，并记下规则名，不再交给其他规则。
 */
object RuleAction {
    const val FILTER = 0
    const val KEEP = 1
}

/**
 * 一个已发现的群（或联系人会话），记录拦截模式。
 * 多应用后同名群会串，所以主键是（来源应用 + 群名）复合键。
 */
@Entity(tableName = "groups", primaryKeys = ["appPackage", "name"])
data class GroupEntity(
    /** 来源应用包名 */
    val appPackage: String,
    /** 群名（通知会话标题 / 导入时用户确认的名字） */
    val name: String,
    val isGroup: Boolean,
    /** 通知方式，见 GroupMode（三档都收集进清单，区别只在手机怎么反应） */
    val mode: Int = GroupMode.REMIND,
    /** 来源：自动发现 / 截图导入，见 GroupSource */
    val source: Int = GroupSource.AUTO,
    val firstSeen: Long,
    var lastSeen: Long,
    var msgCount: Long = 0
)

/**
 * 会话提醒方式常量（三选一，互斥）。v0.5.0 起展示名为「提醒/静默/隐藏」。
 * 前提：该应用的接管已启用（来源应用的通知渠道已设为静默，响不响由滤见接管）。
 * 接管未启用时这些档位不产生任何通知动作，应用照常提醒。
 *   REMIND 提醒：撤掉原通知，用滤见自己的有声渠道重发一条（响铃/震动 + 通知栏显示）
 *   QUIET  静默：原通知不动——渠道静默下天然无声地留在通知栏
 *   HIDE   隐藏：撤掉原通知——不响不震不显示，消息仍收进清单
 */
object GroupMode {
    const val REMIND = 0  // 收集 + 有声重发（接管下的默认档：所有通知都响，用户主动调的才不响）
    const val QUIET = 1   // 收集 + 无声显示
    const val HIDE = 2    // 收集 + 通知栏不显示
}

/** 群来源常量 */
object GroupSource {
    const val AUTO = 0       // 来消息时自动发现
    const val IMPORTED = 1   // 截图 OCR 导入（用户确认过）
}

/**
 * 一个已建档的应用（收集页展示/排除/接管状态用）。
 * 新安装时仅默认推荐的三个应用开启；其它应用需用户显式开启，显式关闭状态跨重启保留。
 */
@Entity(tableName = "monitored_apps")
data class MonitoredAppEntity(
    @PrimaryKey val packageName: String,
    /** 展示名（取自 PackageManager，可能随应用更新变化；自定义导入目标用用户填的名字） */
    val appName: String,
    /** 收集开关：关闭=显式排除，该应用的通知不再入库；默认（未建档）视为开启 */
    val enabled: Boolean = true,
    /** 是否内置推荐项（微信/QQ/企微置顶展示用） */
    val preset: Boolean = false,
    /** 应用级通知接管状态，见 TakeoverState；默认关闭（不撤原通知、不重发） */
    val takeover: Int = TakeoverState.OFF,
    /** 接管向导中用户手动确认「来源应用通知渠道已设为静默」（系统读不到，按用户确认） */
    val takeoverChannelOk: Boolean = false
)

/**
 * 应用级接管状态常量。
 * 语义区分「用户保存的接管意图」与「当前可执行接管」：
 *   OFF         未开启（默认）：只收集，不动原通知
 *   CONFIGURING 配置中/待完成：向导走过但未全部确认，不执行接管
 *   ACTIVE      已开启：按会话档位撤/重发原通知
 *   SUSPENDED   因权限失效暂停（发通知许可被关/重发渠道被禁）：保留配置，不执行接管
 */
object TakeoverState {
    const val OFF = 0
    const val CONFIGURING = 1
    const val ACTIVE = 2
    const val SUSPENDED = 3
}

/** 常见应用包名常量（预置推荐用） */
object KnownApps {
    const val WECHAT = "com.tencent.mm"            // 微信
    const val QQ = "com.tencent.mobileqq"          // QQ
    const val WEWORK = "com.tencent.wework"        // 企业微信

    /** 包名 → 中文名（预置与界面展示用） */
    val PRESET: List<Pair<String, String>> = listOf(
        WECHAT to "微信",
        QQ to "QQ",
        WEWORK to "企业微信"
    )

    fun collectingByDefault(packageName: String): Boolean =
        PRESET.any { it.first == packageName }

    fun isCollecting(packageName: String, explicitState: Boolean?): Boolean =
        explicitState ?: collectingByDefault(packageName)
}

/**
 * 计划页的一个时间块。
 * 日期用绝对日（epochDay），重复块 repeatWeekly=true 表示每周同一天都有。
 */
@Entity(tableName = "plan_events")
data class PlanEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 日期（LocalDate.toEpochDay()）；weekly 块存的是首次安排的那一天 */
    val epochDay: Long,
    /** 当天分钟数：0:00=0 … 23:55=1435 */
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val place: String = "",
    /** 颜色 key：teal / blue / purple / gray（色值见 WeekScreen 的 EVENT_COLORS） */
    val color: String = "teal",
    /** 每周重复 */
    val repeatWeekly: Boolean = false,
    /** 来源消息 id（从清单「加入计划」来的；手动建的为 null） */
    val fromMessageId: Long? = null
)

/** 时间块配色（对齐 preview.js EVENT_COLORS） */
object EventColors {
    // key -> (色条, 浅底)
    val light = mapOf(
        "teal" to (0xFF2E8B74 to 0x142E8B74),
        "blue" to (0xFF4A6FA5 to 0x144A6FA5),
        "purple" to (0xFF7B6BA8 to 0x1A7B6BA8),
        "gray" to (0xFF8B8E96 to 0x1A8B8E96)
    )
}
