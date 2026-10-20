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
        val pattern = com.lvjian.app.rules.TriageRules.PATTERN
        val existing = dao.builtin()
        if (existing == null) {
            dao.insert(
                com.lvjian.app.data.RuleEntity(
                    name = com.lvjian.app.rules.TriageRules.NAME,
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
         * 整理准则的正文在 TriageRules.PATTERN（唯一来源），这里只保留引用，
         * 免得代码里出现第二份会走样的规则。
         */
        val BUILTIN_FILTER_PATTERN: String get() = com.lvjian.app.rules.TriageRules.PATTERN
    }
}
