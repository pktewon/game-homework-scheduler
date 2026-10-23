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
    @ColumnInfo(defaultValue = "'Asia'") val serverLabel: String = "Asia",
    @ColumnInfo(defaultValue = "0") val isUserCreated: Boolean = false,
)

// Personal routines have no foreign key to imported events or template identities.
@Entity(tableName = "personal_daily_tasks", foreignKeys = [ForeignKey(
    entity = GameEntity::class, parentColumns = ["profileKey"], childColumns = ["profileKey"], onDelete = ForeignKey.RESTRICT,
)], indices = [Index("profileKey")])
data class PersonalTaskEntity(
    @PrimaryKey val taskId: String, val profileKey: String, val title: String,
    val notes: String = "", val enabled: Boolean = true,
)

@Entity(tableName = "personal_daily_records", primaryKeys = ["taskId", "cycleStartAt"], foreignKeys = [ForeignKey(
    entity = PersonalTaskEntity::class, parentColumns = ["taskId"], childColumns = ["taskId"], onDelete = ForeignKey.RESTRICT,
)])
data class PersonalRecordEntity(
    val taskId: String, val cycleStartAt: Long, val completedAt: Long,
    val resetMinuteAtCompletion: Int, val serverUtcOffsetAtCompletion: String,
)

data class PersonalHistoryRow(@Embedded val record: PersonalRecordEntity, val taskTitle: String)

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
    @ColumnInfo(defaultValue = "0") val isUserCreated: Boolean = false,
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
    @Query("SELECT * FROM games ORDER BY isUserCreated, name, profileKey") fun observeGames(): Flow<List<GameEntity>>
    @Query("UPDATE games SET name = :name, serverLabel = :server, serverUtcOffset = :offset WHERE profileKey = :profile")
    suspend fun editGame(profile: String, name: String, server: String, offset: String)
    @Insert suspend fun createPersonalTask(task: PersonalTaskEntity)
    @Query("SELECT * FROM personal_daily_tasks WHERE taskId = :id") suspend fun personalTask(id: String): PersonalTaskEntity?
    @Query("UPDATE personal_daily_tasks SET title = :title, notes = :notes, enabled = :enabled WHERE taskId = :id")
    suspend fun editPersonalTask(id: String, title: String, notes: String, enabled: Boolean)
    @Query("SELECT * FROM personal_daily_tasks WHERE profileKey = :profile ORDER BY title, taskId")
    fun observePersonalTasks(profile: String): Flow<List<PersonalTaskEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun completePersonalOnce(record: PersonalRecordEntity): Long
    @Query("DELETE FROM personal_daily_records WHERE taskId = :id AND cycleStartAt = :cycle")
    suspend fun uncheckPersonal(id: String, cycle: Long)
    @Query("""SELECT r.*, t.title AS taskTitle FROM personal_daily_records r
        JOIN personal_daily_tasks t ON t.taskId = r.taskId WHERE t.profileKey = :profile
        ORDER BY r.cycleStartAt DESC, r.completedAt DESC, r.taskId""")
    fun observePersonalHistory(profile: String): Flow<List<PersonalHistoryRow>>
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
    @Query("SELECT * FROM events WHERE profileKey = :profile AND groupKey = :groupKey")
    suspend fun eventGroup(profile: String, groupKey: String): List<EventEntity>
    @Query("SELECT * FROM check_templates WHERE eventKey = :eventKey AND sourceItemKey = :itemKey")
    suspend fun check(eventKey: String, itemKey: String): CheckTemplateEntity?
    @Query("SELECT * FROM check_records WHERE eventKey = :eventKey AND sourceItemKey = :itemKey")
    suspend fun checkRecord(eventKey: String, itemKey: String): CheckRecordEntity?
    @Query("SELECT * FROM daily_task_records") suspend fun dailyRecords(): List<DailyTaskRecordEntity>
    @Query("UPDATE events SET missingFromLatest = 1 WHERE profileKey = :profile AND isUserCreated = 0") suspend fun markEventsMissing(profile: String)
    @Query("UPDATE check_templates SET missingFromLatest = 1 WHERE eventKey IN (SELECT sourceKey FROM events WHERE profileKey = :profile AND isUserCreated = 0)")
    suspend fun markChecksMissing(profile: String)
    @Query("UPDATE daily_tasks SET missingFromLatest = 1 WHERE eventKey IN (SELECT sourceKey FROM events WHERE profileKey = :profile AND isUserCreated = 0)")
    suspend fun markTasksMissing(profile: String)
    @Transaction @Query("SELECT * FROM events WHERE profileKey = :profile")
    fun observeEvents(profile: String): Flow<List<EventDetails>>
    @Transaction @Query("SELECT * FROM events WHERE profileKey = :profile")
    suspend fun events(profile: String): List<EventDetails>
    @Transaction @Query("SELECT * FROM events") fun observeAllEvents(): Flow<List<EventDetails>>
}

@Database(entities = [GameEntity::class, EventEntity::class, CheckTemplateEntity::class,
    CheckRecordEntity::class, DailyTaskEntity::class, DailyTaskRecordEntity::class, SyncStateEntity::class,
    PersonalTaskEntity::class, PersonalRecordEntity::class],
    views = [CheckRow::class], version = 4, exportSchema = true)
abstract class SchedulerDatabase : RoomDatabase() {
    abstract fun dao(): SchedulerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_task_records ADD COLUMN resetMinuteAtCompletion INTEGER")
                db.execSQL("ALTER TABLE daily_task_records ADD COLUMN periodConfirmedByUser INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN serverLabel TEXT NOT NULL DEFAULT 'Asia'")
                db.execSQL("ALTER TABLE games ADD COLUMN isUserCreated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS personal_daily_tasks (
                    taskId TEXT NOT NULL, profileKey TEXT NOT NULL, title TEXT NOT NULL,
                    notes TEXT NOT NULL, enabled INTEGER NOT NULL, PRIMARY KEY(taskId),
                    FOREIGN KEY(profileKey) REFERENCES games(profileKey) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_daily_tasks_profileKey ON personal_daily_tasks(profileKey)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS personal_daily_records (
                    taskId TEXT NOT NULL, cycleStartAt INTEGER NOT NULL, completedAt INTEGER NOT NULL,
                    resetMinuteAtCompletion INTEGER NOT NULL, serverUtcOffsetAtCompletion TEXT NOT NULL,
                    PRIMARY KEY(taskId, cycleStartAt), FOREIGN KEY(taskId) REFERENCES personal_daily_tasks(taskId)
                    ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN isUserCreated INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
