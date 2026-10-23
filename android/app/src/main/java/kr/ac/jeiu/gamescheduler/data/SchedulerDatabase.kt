package kr.ac.jeiu.gamescheduler.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val profileKey: String,
    val name: String = "명일방주: 엔드필드",
    val serverUtcOffset: String = "+08:00",
    val dailyResetMinute: Int? = null,
)

@Entity(tableName = "events", foreignKeys = [ForeignKey(
    entity = GameEntity::class, parentColumns = ["profileKey"], childColumns = ["profileKey"], onDelete = ForeignKey.RESTRICT,
)], indices = [Index("profileKey")])
data class EventEntity(
    @PrimaryKey val sourceKey: String,
    val profileKey: String,
    val groupKey: String,
    val groupTitle: String,
    val title: String,
    val revision: Long,
    val phaseKind: String,
    val timeShape: String,
    val occursAt: Long?,
    val startAt: Long?,
    val endAt: Long?,
    val timeState: String,
    val sourceUrl: String,
    val evidenceJson: String,
    val notes: String,
    val missingFromLatest: Boolean = false,
)

@Entity(tableName = "check_templates", primaryKeys = ["eventKey", "sourceItemKey"], foreignKeys = [ForeignKey(
    entity = EventEntity::class, parentColumns = ["sourceKey"], childColumns = ["eventKey"], onDelete = ForeignKey.RESTRICT,
)])
data class CheckTemplateEntity(
    val eventKey: String, val sourceItemKey: String, val title: String,
    val sortOrder: Int, val missingFromLatest: Boolean = false,
)

// Only explicit user actions write this table. Catalog import never updates it.
@Entity(tableName = "check_records", primaryKeys = ["eventKey", "sourceItemKey"], foreignKeys = [ForeignKey(
    entity = CheckTemplateEntity::class, parentColumns = ["eventKey", "sourceItemKey"],
    childColumns = ["eventKey", "sourceItemKey"], onDelete = ForeignKey.RESTRICT,
)])
data class CheckRecordEntity(val eventKey: String, val sourceItemKey: String, val isDone: Boolean, val completedAt: Long?)

@Entity(tableName = "daily_tasks", primaryKeys = ["eventKey", "sourceTaskKey"], foreignKeys = [ForeignKey(
    entity = EventEntity::class, parentColumns = ["sourceKey"], childColumns = ["eventKey"], onDelete = ForeignKey.RESTRICT,
)])
data class DailyTaskEntity(
    val eventKey: String, val sourceTaskKey: String, val title: String,
    val templateJson: String, val missingFromLatest: Boolean = false,
)

@Entity(tableName = "daily_task_records", primaryKeys = ["eventKey", "sourceTaskKey", "cycleStartAt"], foreignKeys = [ForeignKey(
    entity = DailyTaskEntity::class, parentColumns = ["eventKey", "sourceTaskKey"],
    childColumns = ["eventKey", "sourceTaskKey"], onDelete = ForeignKey.RESTRICT,
)])
data class DailyTaskRecordEntity(
    val eventKey: String, val sourceTaskKey: String, val cycleStartAt: Long, val completedAt: Long,
    val resetMinuteAtCompletion: Int? = null,
    @ColumnInfo(defaultValue = "0") val periodConfirmedByUser: Boolean = false,
)

data class DailyTaskDetails(
    @Embedded val task: DailyTaskEntity,
    @Relation(parentColumn = "eventKey", entityColumn = "sourceKey") val event: EventEntity,
)

data class DailyHistoryRow(
    @Embedded val record: DailyTaskRecordEntity,
    val taskTitle: String, val eventTitle: String,
)

@Entity(tableName = "sync_state", foreignKeys = [ForeignKey(
    entity = GameEntity::class, parentColumns = ["profileKey"], childColumns = ["profileKey"], onDelete = ForeignKey.RESTRICT,
)])
data class SyncStateEntity(
    @PrimaryKey val profileKey: String, val releaseRevision: Long,
    val contentHash: String, val sourceCheckedAt: Long, val lastSuccessAt: Long,
)

@DatabaseView("""SELECT t.eventKey, t.sourceItemKey, t.title, t.sortOrder, t.missingFromLatest,
    COALESCE(r.isDone, 0) AS isDone, r.completedAt FROM check_templates t
    LEFT JOIN check_records r ON t.eventKey = r.eventKey AND t.sourceItemKey = r.sourceItemKey""")
data class CheckRow(
    val eventKey: String, val sourceItemKey: String, val title: String, val sortOrder: Int,
    val missingFromLatest: Boolean, val isDone: Boolean, val completedAt: Long?,
)

data class EventDetails(
    @Embedded val event: EventEntity,
    @Relation(parentColumn = "sourceKey", entityColumn = "eventKey", entity = CheckRow::class) val checks: List<CheckRow>,
    @Relation(parentColumn = "sourceKey", entityColumn = "eventKey") val dailyTasks: List<DailyTaskEntity>,
)

@Dao
interface SchedulerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureGame(game: GameEntity): Long
    @Upsert suspend fun putEvent(event: EventEntity)
    @Upsert suspend fun putCheck(template: CheckTemplateEntity)
    @Upsert suspend fun putTask(task: DailyTaskEntity)
    @Upsert suspend fun putCheckRecord(record: CheckRecordEntity)
    @Upsert suspend fun putDailyRecord(record: DailyTaskRecordEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun completeDailyOnce(record: DailyTaskRecordEntity): Long
    @Query("SELECT * FROM games WHERE profileKey = :profile") suspend fun game(profile: String): GameEntity?
    @Query("SELECT * FROM games WHERE profileKey = :profile") fun observeGame(profile: String): Flow<GameEntity?>
    @Query("UPDATE games SET dailyResetMinute = :minute WHERE profileKey = :profile") suspend fun setResetMinute(profile: String, minute: Int?)
    @Query("SELECT * FROM daily_tasks WHERE eventKey = :eventKey AND sourceTaskKey = :taskKey")
    suspend fun task(eventKey: String, taskKey: String): DailyTaskEntity?
    @Query("SELECT * FROM daily_task_records WHERE eventKey = :eventKey AND sourceTaskKey = :taskKey AND cycleStartAt = :cycle")
    suspend fun dailyRecord(eventKey: String, taskKey: String, cycle: Long): DailyTaskRecordEntity?
    @Query("DELETE FROM daily_task_records WHERE eventKey = :eventKey AND sourceTaskKey = :taskKey AND cycleStartAt = :cycle")
    suspend fun uncheckDaily(eventKey: String, taskKey: String, cycle: Long)
    @Transaction @Query("SELECT * FROM daily_tasks WHERE eventKey IN (SELECT sourceKey FROM events WHERE profileKey = :profile)")
    fun observeDailyTasks(profile: String): Flow<List<DailyTaskDetails>>
    @Query("""SELECT r.*, t.title AS taskTitle, e.title AS eventTitle FROM daily_task_records r
        JOIN daily_tasks t ON r.eventKey = t.eventKey AND r.sourceTaskKey = t.sourceTaskKey
        JOIN events e ON e.sourceKey = r.eventKey WHERE e.profileKey = :profile
        ORDER BY r.cycleStartAt DESC, r.completedAt DESC, r.eventKey, r.sourceTaskKey""")
    fun observeDailyHistory(profile: String): Flow<List<DailyHistoryRow>>
    @Upsert suspend fun putSyncState(state: SyncStateEntity)
    @Query("SELECT * FROM sync_state WHERE profileKey = :profile") suspend fun syncState(profile: String): SyncStateEntity?
    @Query("SELECT * FROM sync_state WHERE profileKey = :profile") fun observeSyncState(profile: String): Flow<SyncStateEntity?>
    @Query("SELECT * FROM events WHERE sourceKey = :key") suspend fun event(key: String): EventEntity?
    @Query("SELECT * FROM check_templates WHERE eventKey = :eventKey AND sourceItemKey = :itemKey")
    suspend fun check(eventKey: String, itemKey: String): CheckTemplateEntity?
    @Query("SELECT * FROM check_records WHERE eventKey = :eventKey AND sourceItemKey = :itemKey")
    suspend fun checkRecord(eventKey: String, itemKey: String): CheckRecordEntity?
    @Query("SELECT * FROM daily_task_records") suspend fun dailyRecords(): List<DailyTaskRecordEntity>
    @Query("UPDATE events SET missingFromLatest = 1 WHERE profileKey = :profile") suspend fun markEventsMissing(profile: String)
    @Query("UPDATE check_templates SET missingFromLatest = 1 WHERE eventKey IN (SELECT sourceKey FROM events WHERE profileKey = :profile)")
    suspend fun markChecksMissing(profile: String)
    @Query("UPDATE daily_tasks SET missingFromLatest = 1 WHERE eventKey IN (SELECT sourceKey FROM events WHERE profileKey = :profile)")
    suspend fun markTasksMissing(profile: String)
    @Transaction @Query("SELECT * FROM events WHERE profileKey = :profile")
    fun observeEvents(profile: String): Flow<List<EventDetails>>
    @Transaction @Query("SELECT * FROM events WHERE profileKey = :profile")
    suspend fun events(profile: String): List<EventDetails>
}

@Database(entities = [GameEntity::class, EventEntity::class, CheckTemplateEntity::class,
    CheckRecordEntity::class, DailyTaskEntity::class, DailyTaskRecordEntity::class, SyncStateEntity::class],
    views = [CheckRow::class], version = 2, exportSchema = true)
abstract class SchedulerDatabase : RoomDatabase() {
    abstract fun dao(): SchedulerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_task_records ADD COLUMN resetMinuteAtCompletion INTEGER")
                db.execSQL("ALTER TABLE daily_task_records ADD COLUMN periodConfirmedByUser INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
