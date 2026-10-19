// Application 入口：持有全局单例（数据库、仓储、设置），并启动保活服务
package com.lvjian.app

import android.app.Application
import com.lvjian.app.data.AppDatabase
import com.lvjian.app.data.MessageRepository
import com.lvjian.app.data.Prefs
import com.lvjian.app.work.KeeperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LvJianApp : Application() {

    // 数据库与仓储按需创建，全进程共享一份
    val db by lazy { AppDatabase.get(this) }
    val prefs by lazy { Prefs(this) }
    val repo by lazy { MessageRepository(this, db, prefs) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        KeeperService.start(this)
        com.lvjian.app.work.SystemHelper.rebindNotificationListener(this)
        // 内置一条 AI 过滤规则。每次启动都保证它在，用户删不掉。
        // 以前预置的「家教 / 闲聊 / 屏蔽某人」只是示例，清掉，避免装成用户的规则。
        CoroutineScope(Dispatchers.IO).launch {
            ensureBuiltinRule()
        }
    }

    private suspend fun ensureBuiltinRule() {
        val dao = db.ruleDao()
        dao.deleteByNames(listOf("家教", "闲聊", "屏蔽某人"))
        val pattern = BUILTIN_FILTER_PATTERN
        val existing = dao.builtin()
        if (existing == null) {
            dao.insert(
                com.lvjian.app.data.RuleEntity(
                    name = "滤掉无用信息",
                    type = com.lvjian.app.data.RuleType.AI_PROMPT,
                    pattern = pattern,
                    scopeKeys = com.lvjian.app.data.ScopeKeys.ALL,
                    enabled = true,
                    action = com.lvjian.app.data.RuleAction.FILTER,
                    builtin = true
                )
            )
        } else if (existing.pattern != pattern) {
            // 内置规则用户改不了内容，但旧安装里还是上一版说明。启动时写回，否则改代码不生效。
            dao.updateBuiltinPattern(pattern)
        }
    }

    companion object {
        /** 全局访问点；仅限确实需要上下文单例处使用 */
        lateinit var instance: LvJianApp
            private set

        /**
         * 分类开始时消息已在未整理。AI 只决定进待办，还是进已过滤。
         * 验证码时效短，进已过滤，不进待办。闲扯滤掉。约饭是要做的事，留下。
         */
        const val BUILTIN_FILTER_PATTERN =
            "只滤掉明确无用的消息：系统状态（充电、USB、互传、调试、电量）、营销广告和促销、" +
                "验证码、账号安全播报、与工作无关的闲聊、表情包刷屏。约饭不要过滤，放进待办。" +
                "以下一律留下，不得过滤：通知里写了「有人@我」、@所有人、通知地点或时间变更、要求确认、截止、提交、" +
                "问卷、考核、成绩、到场、回复、约饭。群里只 @了别人、没有「有人@我」也没有 @所有人的，过滤掉。拿不准时留下。"
    }
}
