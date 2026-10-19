// Room 数据库单例
package com.lvjian.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, RuleEntity::class, GroupEntity::class, MonitoredAppEntity::class, PlanEventEntity::class],
    // v3：规则作用范围改多选集合（scopeKeys）、新增计划时间块表 plan_events
    // v4：群模式从「监控/免打扰双开关」改为「通知方式三选一」（REMIND/QUIET/HIDE，含义见 GroupMode）
    // v5：monitored_apps 增加应用级接管状态 takeover（默认 OFF）与渠道确认位 takeoverChannelOk；
    //    旧会话 mode=0（REMIND）不代表用户开过接管，新状态一律默认关闭（任务书 05 第 6 节）
    // v6：rules 增加 action（0=过滤，1=保留）。旧规则没有这一列，默认过滤，行为与升级前相同。
    // v7：rules 增加 builtin（内置规则不可删）；messages 增加摘要和加入计划预填。
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun ruleDao(): RuleDao
    abstract fun groupDao(): GroupDao
    abstract fun monitoredAppDao(): MonitoredAppDao
    abstract fun planEventDao(): PlanEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v4→v5：monitored_apps 增加 takeover / takeoverChannelOk 两列（默认关闭接管） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monitored_apps ADD COLUMN takeover INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE monitored_apps ADD COLUMN takeoverChannelOk INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5→v6：rules 增加 action，默认 0（过滤）。已有规则继续只过滤。 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN action INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6→v7：内置规则标记；消息摘要和加入计划预填。旧行用默认空值，不丢数据。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN builtin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN planTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN planPlace TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN planEpochDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN planStartMin INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE messages ADD COLUMN planEndMin INTEGER NOT NULL DEFAULT -1")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lvjian.db"
                )
                    // v4→v5 显式迁移：新增两列默认值，已有消息/规则/群/计划/应用记录全部保留
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // 仅作为更老版本的兜底（v1~v3 无迁移路径时才触发）；正常升级不经过这里
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
