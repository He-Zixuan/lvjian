// Room DAO：五张表的增删改查（UI 用 Flow 订阅，服务用 suspend 写入）
package com.lvjian.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** 去重检查：同应用、同群、同人、同文、同时间戳视为同一条（通知更新会重复回调） */
    @Query(
        """SELECT COUNT(*) FROM messages
           WHERE appPackage = :app AND groupName = :group AND sender = :sender
             AND text = :text AND timestamp = :ts"""
    )
    suspend fun countDuplicate(app: String, group: String, sender: String, text: String, ts: Long): Int

    @Insert
    suspend fun insert(msg: MessageEntity): Long

    /** 撤销用：带原 id 整行插回 */
    @Insert
    suspend fun reinsert(msg: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int)

    @Query("UPDATE messages SET filtered = :filtered, matchedRule = :rule, aiState = :aiState WHERE id = :id")
    suspend fun setFilterResult(id: Long, filtered: Boolean, rule: String?, aiState: Int)

    /** 写入 AI 摘要和加入计划预填。日期 0、时间 -1 表示这项没生成出来。 */
    @Query(
        """UPDATE messages SET summary = :summary, planTitle = :planTitle, planPlace = :planPlace,
           planEpochDay = :planEpochDay, planStartMin = :planStartMin, planEndMin = :planEndMin
           WHERE id = :id"""
    )
    suspend fun setBrief(
        id: Long,
        summary: String,
        planTitle: String,
        planPlace: String,
        planEpochDay: Long,
        planStartMin: Int,
        planEndMin: Int
    )

    /** 主清单：未过滤 + 待处理，新的在前；未整理（aiState=4）单独成段，不进待处理 */
    @Query("SELECT * FROM messages WHERE filtered = 0 AND status = 0 AND aiState != 4 ORDER BY timestamp DESC")
    fun pendingFlow(): Flow<List<MessageEntity>>

    /** 未整理：批量模式攒下的待 AI 判定条目，新的在前 */
    @Query("SELECT * FROM messages WHERE filtered = 0 AND status = 0 AND aiState = 4 ORDER BY timestamp DESC")
    fun pendingBatchFlow(): Flow<List<MessageEntity>>

    /** 未整理计数（分段角标/操作卡） */
    @Query("SELECT COUNT(*) FROM messages WHERE filtered = 0 AND status = 0 AND aiState = 4")
    fun pendingBatchCountFlow(): Flow<Int>

    /** 一键整理取数：全部未整理，旧的在前（按时间序喂给模型更自然） */
    @Query("SELECT * FROM messages WHERE filtered = 0 AND status = 0 AND aiState = 4 ORDER BY timestamp ASC")
    suspend fun pendingBatchAll(): List<MessageEntity>

    /** 只更新 AI 状态（批量判定中 QUEUED 等） */
    @Query("UPDATE messages SET aiState = :aiState WHERE id = :id")
    suspend fun setAiState(id: Long, aiState: Int)

    /** 已划去（完成+忽略），新的在前 */
    @Query("SELECT * FROM messages WHERE filtered = 0 AND status != 0 ORDER BY timestamp DESC LIMIT 500")
    fun doneFlow(): Flow<List<MessageEntity>>

    /** 已过滤条目（可复查） */
    @Query("SELECT * FROM messages WHERE filtered = 1 ORDER BY timestamp DESC LIMIT 500")
    fun filteredFlow(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE filtered = 1")
    fun filteredCountFlow(): Flow<Int>

    /** 误判恢复：把条目拉回主清单 */
    @Query("UPDATE messages SET filtered = 0, matchedRule = NULL, status = 0 WHERE id = :id")
    suspend fun unfilter(id: Long)

    // ---- 清空分段（已过滤/已划去）与撤销 ----
    @Query("SELECT * FROM messages WHERE filtered = 1 ORDER BY timestamp DESC")
    suspend fun allFiltered(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE filtered = 0 AND status != 0 ORDER BY timestamp DESC")
    suspend fun allDone(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE filtered = 1")
    suspend fun clearFiltered()

    @Query("DELETE FROM messages WHERE filtered = 0 AND status != 0")
    suspend fun clearDone()

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun allFlow(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun enabledRules(): List<RuleEntity>

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int

    @Query("SELECT * FROM rules WHERE builtin = 1 LIMIT 1")
    suspend fun builtin(): RuleEntity?

    @Query("DELETE FROM rules WHERE name IN (:names) AND builtin = 0")
    suspend fun deleteByNames(names: List<String>)

    @Query("UPDATE rules SET pattern = :pattern WHERE builtin = 1")
    suspend fun updateBuiltinPattern(pattern: String)

    @Insert
    suspend fun insert(rule: RuleEntity): Long

    /** 撤销删除用：带原 id 整行插回 */
    @Insert
    suspend fun reinsert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun delete(rule: RuleEntity)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY lastSeen DESC")
    fun allFlow(): Flow<List<GroupEntity>>

    /** 复合键查找：同一应用下的同名群只有一条 */
    @Query("SELECT * FROM groups WHERE appPackage = :app AND name = :name")
    suspend fun byKey(app: String, name: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Query("UPDATE groups SET mode = :mode WHERE appPackage = :app AND name = :name")
    suspend fun setMode(app: String, name: String, mode: Int)

    @Query("UPDATE groups SET lastSeen = :ts, msgCount = msgCount + :delta WHERE appPackage = :app AND name = :name")
    suspend fun touch(app: String, name: String, ts: Long, delta: Long)
}

@Dao
interface MonitoredAppDao {
    @Query("SELECT * FROM monitored_apps ORDER BY preset DESC, appName ASC")
    fun allFlow(): Flow<List<MonitoredAppEntity>>

    /** 监听服务的启用包名集合（Flow 供内存缓存订阅）——v0.4.0 及以前的「只监听启用包」策略 */
    @Query("SELECT packageName FROM monitored_apps WHERE enabled = 1")
    fun enabledPackagesFlow(): Flow<List<String>>

    /** 监听服务的排除包名集合（v0.5.0 起「默认全收集 + 显式排除」策略，Flow 供内存缓存订阅） */
    @Query("SELECT packageName FROM monitored_apps WHERE enabled = 0")
    fun excludedPackagesFlow(): Flow<List<String>>

    /** 接管状态与确认位一并更新（向导/详情页用） */
    @Query("UPDATE monitored_apps SET takeover = :takeover, takeoverChannelOk = :channelOk WHERE packageName = :pkg")
    suspend fun setTakeover(pkg: String, takeover: Int, channelOk: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: MonitoredAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<MonitoredAppEntity>)

    @Query("SELECT * FROM monitored_apps")
    suspend fun all(): List<MonitoredAppEntity>

    @Query("SELECT COUNT(*) FROM monitored_apps")
    suspend fun count(): Int

    @Query("UPDATE monitored_apps SET enabled = :enabled WHERE packageName = :pkg")
    suspend fun setEnabled(pkg: String, enabled: Boolean)

    @Query("SELECT * FROM monitored_apps WHERE packageName = :pkg")
    suspend fun byPackage(pkg: String): MonitoredAppEntity?
}

@Dao
interface PlanEventDao {
    @Query("SELECT * FROM plan_events")
    fun allFlow(): Flow<List<PlanEventEntity>>

    /** 一次性读取（闹钟排程用：要给每条计划各排一个 alarm，不能挂 Flow） */
    @Query("SELECT * FROM plan_events")
    suspend fun all(): List<PlanEventEntity>

    @Insert
    suspend fun insert(ev: PlanEventEntity): Long

    /** 撤销删除用：带原 id 整行插回 */
    @Insert
    suspend fun reinsert(ev: PlanEventEntity): Long

    @Update
    suspend fun update(ev: PlanEventEntity)

    @Delete
    suspend fun delete(ev: PlanEventEntity)

    @Query("SELECT * FROM plan_events WHERE id = :id")
    suspend fun byId(id: Long): PlanEventEntity?
}
