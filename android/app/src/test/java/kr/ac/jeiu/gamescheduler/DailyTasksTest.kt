package kr.ac.jeiu.gamescheduler

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kr.ac.jeiu.gamescheduler.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val EVENT = "$PROFILE:test-daily:play"
private const val TASK = "daily-mission"
private fun utc(text: String) = Instant.parse(text).toEpochMilli()

private class TestClock(var time: Long = utc("2026-10-23T19:59:59Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(time)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DailyTasksTest {
    private lateinit var db: SchedulerDatabase
    private lateinit var repo: DailyRepository
    private lateinit var clock: TestClock
    private val event = EventEntity(EVENT, PROFILE, "test-daily", "테스트 이벤트", "매일 임무", 1,
        "PLAY", "WINDOW", null, utc("2026-10-01T00:00:00Z"), utc("2026-11-01T00:00:00Z"),
        "CONFIRMED", "https://endfield.gryphline.com/ko-kr/news/9560", "{}", "")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SchedulerDatabase::class.java).build()
        clock = TestClock()
        repo = DailyRepository(db, clock)
        db.dao().ensureGame(GameEntity(PROFILE))
        db.dao().putEvent(event)
        db.dao().putTask(DailyTaskEntity(EVENT, TASK, "오늘 목표와 보상 확인", "{}"))
    }
    @After fun cleanup() { db.close() }
    private fun cycle(reset: Int = 240) = DailyCycles.current(clock.millis(), reset, "+08:00")

    @Test fun boundaryUsesServerTimeAndNotDeviceMidnight() {
        val before = cycle()
        assertEquals(utc("2026-10-22T20:00:00Z"), before.startAt)
        clock.time = utc("2026-10-23T20:00:00Z")
        assertEquals(utc("2026-10-23T20:00:00Z"), cycle().startAt)
        assertEquals(before.endAt, cycle().startAt)
        clock.time = utc("2026-10-23T16:00:00Z") // Server midnight is not the 04:00 reset.
        assertEquals(before.startAt, cycle().startAt)
    }

    @Test fun midnightAndLastMinuteResetCrossMonthAndYearCorrectly() {
        val midnight = DailyCycles.current(utc("2026-12-31T16:00:00Z"), 0, "+08:00")
        assertEquals(utc("2026-12-31T16:00:00Z"), midnight.startAt)
        val lastMinute = DailyCycles.current(utc("2026-11-01T15:58:59Z"), 1439, "+08:00")
        assertEquals(utc("2026-10-31T15:59:00Z"), lastMinute.startAt)
        assertEquals(86_400_000L, lastMinute.endAt - lastMinute.startAt)
    }

    @Test fun newCycleIsUncheckedWithoutDeletingPreviousCompletion() = runBlocking {
        repo.setResetMinute(240)
        val previous = cycle()
        repo.setChecked(EVENT, TASK, previous, true)
        val old = db.dao().dailyRecords().single()
        clock.time = previous.endAt
        val current = cycle()
        assertNull(db.dao().dailyRecord(EVENT, TASK, current.startAt))
        assertEquals(listOf(old), db.dao().dailyRecords())
        repo.setChecked(EVENT, TASK, current, true)
        assertEquals(2, repo.history.first().size)
        repo.setChecked(EVENT, TASK, current, false)
        assertEquals(listOf(old), db.dao().dailyRecords())
    }

    @Test fun duplicateCompletionKeepsOriginalTimeAndDoesNotGenerateEmptyRecords() = runBlocking {
        repo.setResetMinute(240)
        assertTrue(db.dao().dailyRecords().isEmpty())
        repo.setChecked(EVENT, TASK, cycle(), true)
        val first = db.dao().dailyRecords().single()
        clock.time += 500
        repo.setChecked(EVENT, TASK, cycle(), true)
        assertEquals(listOf(first), db.dao().dailyRecords())
        assertEquals(240, first.resetMinuteAtCompletion)
        assertFalse(first.periodConfirmedByUser)
    }

    @Test fun staleClickAtBoundaryCannotWriteToEitherCycle() = runBlocking {
        repo.setResetMinute(240)
        val oldCycle = cycle()
        clock.time = oldCycle.endAt
        try { repo.setChecked(EVENT, TASK, oldCycle, true); fail("stale click") } catch (_: CatalogException) { }
        assertTrue(db.dao().dailyRecords().isEmpty())
    }

    @Test fun staleUncheckCannotDeleteOldCompletionAfterReset() = runBlocking {
        repo.setResetMinute(240)
        val oldCycle = cycle()
        repo.setChecked(EVENT, TASK, oldCycle, true)
        clock.time = oldCycle.endAt
        try { repo.setChecked(EVENT, TASK, oldCycle, false); fail("stale uncheck") } catch (_: CatalogException) { }
        assertEquals(1, db.dao().dailyRecords().size)
    }

    @Test fun resetSettingChangesAndClearPreserveHistory() = runBlocking {
        repo.setResetMinute(240)
        val oldCycle = cycle()
        repo.setChecked(EVENT, TASK, oldCycle, true)
        val old = db.dao().dailyRecords().single()
        repo.setResetMinute(180)
        assertNotEquals(oldCycle.startAt, cycle(180).startAt)
        try { repo.setChecked(EVENT, TASK, oldCycle, true); fail("stale setting") } catch (_: CatalogException) { }
        repo.setChecked(EVENT, TASK, cycle(180), true)
        assertEquals(2, repo.history.first().size)
        repo.setResetMinute(null)
        assertNull(repo.game.first()!!.dailyResetMinute)
        assertEquals(2, repo.history.first().size)
        repo.setResetMinute(240)
        assertEquals(old, db.dao().dailyRecord(EVENT, TASK, oldCycle.startAt))
    }

    @Test fun invalidAndUnsetResetCannotCreateCompletion() = runBlocking {
        try { repo.setChecked(EVENT, TASK, cycle(), true); fail("unset reset") } catch (_: CatalogException) { }
        for (invalid in listOf(-1, 1440)) {
            try { repo.setResetMinute(invalid); fail("invalid reset") } catch (_: CatalogException) { }
        }
        assertNull(repo.game.first()!!.dailyResetMinute)
        assertTrue(db.dao().dailyRecords().isEmpty())
    }

    @Test fun uncertainPeriodNeedsExplicitConfirmationForEveryNewCycle() = runBlocking {
        repo.setResetMinute(240)
        db.dao().putEvent(event.copy(startAt = null, timeState = "NEEDS_REVIEW"))
        try { repo.setChecked(EVENT, TASK, cycle(), true); fail("missing confirmation") } catch (_: CatalogException) { }
        repo.setChecked(EVENT, TASK, cycle(), true, true)
        assertTrue(db.dao().dailyRecords().single().periodConfirmedByUser)
        assertNull(db.dao().event(EVENT)!!.startAt)
        assertEquals("NEEDS_REVIEW", db.dao().event(EVENT)!!.timeState)
        clock.time = cycle().endAt
        try { repo.setChecked(EVENT, TASK, cycle(), true); fail("must confirm new cycle") } catch (_: CatalogException) { }
        assertEquals(1, db.dao().dailyRecords().size)
    }

    @Test fun confirmationCannotBypassKnownStartEndOrMissingTemplate() = runBlocking {
        repo.setResetMinute(240)
        val now = clock.millis()
        for (blocked in listOf(event.copy(startAt = now + 1, timeState = "NEEDS_REVIEW"),
            event.copy(endAt = now, timeState = "NEEDS_REVIEW"), event.copy(missingFromLatest = true))) {
            db.dao().putEvent(blocked)
            try { repo.setChecked(EVENT, TASK, cycle(), true, true); fail("blocked event") } catch (_: CatalogException) { }
        }
        db.dao().putEvent(event)
        db.dao().putTask(DailyTaskEntity(EVENT, TASK, "이전 숙제", "{}", true))
        try { repo.setChecked(EVENT, TASK, cycle(), true, true); fail("retired template") } catch (_: CatalogException) { }
        assertTrue(db.dao().dailyRecords().isEmpty())
    }

    @Test fun currentCompletionCanBeCorrectedAfterEventEndsWithoutDeletingOlderCycles() = runBlocking {
        repo.setResetMinute(240)
        repo.setChecked(EVENT, TASK, cycle(), true)
        db.dao().putDailyRecord(DailyTaskRecordEntity(EVENT, TASK, cycle().startAt - 86_400_000, clock.millis() - 86_400_000))
        db.dao().putEvent(event.copy(endAt = clock.millis()))
        repo.setChecked(EVENT, TASK, cycle(), false)
        assertEquals(1, db.dao().dailyRecords().size)
        assertEquals(cycle().startAt - 86_400_000, db.dao().dailyRecords().single().cycleStartAt)
    }

    @Test fun settingsAndDailyChecksPersistAcrossDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "daily-persistence.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
        try {
            disk.dao().ensureGame(GameEntity(PROFILE)); disk.dao().putEvent(event)
            disk.dao().putTask(DailyTaskEntity(EVENT, TASK, "매일 임무", "{}"))
            val first = DailyRepository(disk, clock)
            first.setResetMinute(240); first.setChecked(EVENT, TASK, cycle(), true)
            disk.close()
            disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
            val again = DailyRepository(disk, clock)
            assertEquals(240, again.game.first()!!.dailyResetMinute)
            assertEquals(1, again.history.first().size)
            clock.time = cycle().endAt
            assertEquals(1, again.history.first().size)
            assertNull(disk.dao().dailyRecord(EVENT, TASK, cycle().startAt))
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun rowPresentationSeparatesCurrentCycleAndReadOnlyHistory() = runBlocking {
        repo.setResetMinute(240); repo.setChecked(EVENT, TASK, cycle(), true)
        val data = DailyState(repo.game.first(), repo.tasks.first(), repo.history.first(), clock.millis())
        assertTrue(dailyRows(data).single().checked)
        val next = data.copy(now = cycle().endAt)
        assertFalse(dailyRows(next).single().checked)
        val history = dailyRows(next.copy(showHistory = true)).single()
        assertTrue(history.checked); assertFalse(history.enabled)
        assertTrue(resetText(23 * 60).contains("다음 날"))
    }

    @Test fun migrationFromExportedV1PreservesSettingsEventChecksAndDailyHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "daily-v1-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JsonParser.parseString(javaClass.classLoader!!.getResourceAsStream(
            "kr.ac.jeiu.gamescheduler.data.SchedulerDatabase/1.json")!!.reader().readText()).asJsonObject.getAsJsonObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            for (entry in schema.getAsJsonArray("entities")) {
                val e = entry.asJsonObject
                legacy.execSQL(e["createSql"].asString.replace('$' + "{TABLE_NAME}", e["tableName"].asString))
            }
            for (entry in schema.getAsJsonArray("views")) {
                val v = entry.asJsonObject
                legacy.execSQL(v["createSql"].asString.replace('$' + "{VIEW_NAME}", v["viewName"].asString))
            }
            schema.getAsJsonArray("setupQueries").forEach { legacy.execSQL(it.asString) }
            legacy.execSQL("INSERT INTO games VALUES (?, ?, ?, ?)", arrayOf<Any>(PROFILE, "엔필", "+08:00", 240))
            legacy.insertOrThrow("events", null, ContentValues().apply {
                put("sourceKey", EVENT); put("profileKey", PROFILE); put("groupKey", "test-daily"); put("groupTitle", "테스트")
                put("title", event.title); put("revision", 1); put("phaseKind", "PLAY"); put("timeShape", "WINDOW")
                putNull("occursAt"); put("startAt", event.startAt); put("endAt", event.endAt); put("timeState", "CONFIRMED")
                put("sourceUrl", event.sourceUrl); put("evidenceJson", "{}"); put("notes", ""); put("missingFromLatest", 0)
            })
            legacy.execSQL("INSERT INTO daily_tasks VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(EVENT, TASK, "매일 임무", "{}", 0))
            legacy.execSQL("INSERT INTO daily_task_records VALUES (?, ?, ?, ?)", arrayOf<Any>(EVENT, TASK, cycle().startAt, clock.millis()))
            legacy.execSQL("INSERT INTO check_templates VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(EVENT, "goal", "참여", 0, 0))
            legacy.execSQL("INSERT INTO check_records VALUES (?, ?, ?, ?)", arrayOf<Any>(EVENT, "goal", 1, clock.millis()))
            legacy.version = 1
        }
        val migrated = Room.databaseBuilder(context, SchedulerDatabase::class.java, name)
            .addMigrations(SchedulerDatabase.MIGRATION_1_2, SchedulerDatabase.MIGRATION_2_3, SchedulerDatabase.MIGRATION_3_4, SchedulerDatabase.MIGRATION_4_5).build()
        try {
            assertEquals(240, migrated.dao().game(PROFILE)!!.dailyResetMinute)
            assertTrue(migrated.dao().checkRecord(EVENT, "goal")!!.isDone)
            val previous = migrated.dao().dailyRecords().single()
            assertEquals(cycle().startAt, previous.cycleStartAt)
            assertNull(previous.resetMinuteAtCompletion)
            assertFalse(previous.periodConfirmedByUser)
            val daily = DailyRepository(migrated, clock)
            clock.time = cycle().endAt
            daily.setChecked(EVENT, TASK, cycle(), true)
            assertEquals(2, daily.history.first().size)
            assertEquals(5, migrated.openHelper.readableDatabase.version)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
