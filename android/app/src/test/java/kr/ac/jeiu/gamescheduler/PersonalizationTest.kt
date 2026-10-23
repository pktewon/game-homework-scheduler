package kr.ac.jeiu.gamescheduler

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import java.time.Clock
import java.time.Instant
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

private val CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PersonalizationTest {
    private lateinit var db: SchedulerDatabase
    private lateinit var editor: PersonalizationRepository
    private lateinit var feed: TestFeed
    private lateinit var catalog: CatalogRepository
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SchedulerDatabase::class.java).build()
        editor = PersonalizationRepository(db); feed = TestFeed()
        catalog = CatalogRepository(db, CatalogClient(feed), CLOCK)
        catalog.refresh()
        Unit
    }
    @After fun cleanup() { db.close() }
    private fun daily(profile: String) = DailyRepository(db, CLOCK, profile)
    private suspend fun cycle(profile: String): DailyCycle {
        val game = db.dao().game(profile)!!
        return DailyCycles.current(CLOCK.millis(), game.dailyResetMinute!!, game.serverUtcOffset)
    }

    @Test fun gamesAndSameNamedTasksUseIndependentStableKeys() = runBlocking {
        val ww = editor.saveGame(null, "명조", "Asia", "+09:00")
        val gf = editor.saveGame(null, "소녀전선 2", "HAOPLAY 한국판", "+09:00")
        val sameName = editor.saveGame(null, "명조", "다른 프로필", "+08:00")
        assertEquals(4, editor.games.first().size)
        assertNotEquals(ww, sameName)
        val a = editor.saveTask(ww, null, "일일 임무", "나만의 목표", true)
        val b = editor.saveTask(gf, null, "일일 임무", "", true)
        daily(ww).setResetMinute(240); daily(gf).setResetMinute(300)
        daily(ww).setPersonalChecked(a, cycle(ww), true)
        assertEquals(1, daily(ww).personalHistory.first().size)
        assertTrue(daily(gf).personalHistory.first().isEmpty())
        assertEquals(b, daily(gf).personalTasks.first().single().taskId)
        try { daily(gf).setPersonalChecked(a, cycle(gf), true); fail("wrong game") } catch (_: CatalogException) { }
    }

    @Test fun officialUpdateCannotAlterPersonalGamesTasksSettingsOrRecords() = runBlocking {
        val ww = editor.saveGame(null, "명조", "Asia", "+09:00")
        daily(ww).setResetMinute(240); daily(PROFILE).setResetMinute(300)
        val own = editor.saveTask(ww, null, "내 숙제", "별도 메모", true)
        val endfieldOwn = editor.saveTask(PROFILE, null, "엔필 개인 목표", "공지와 무관", true)
        daily(ww).setPersonalChecked(own, cycle(ww), true)
        daily(PROFILE).setPersonalChecked(endfieldOwn, cycle(PROFILE), true)
        editor.saveGame(PROFILE, "내 엔필", "Asia", "+08:00")
        val beforeGame = db.dao().game(ww)
        val beforeTasks = daily(ww).personalTasks.first()
        val beforeRecords = daily(ww).personalHistory.first()
        val beforeEndfieldRecords = daily(PROFILE).personalHistory.first()
        feed.publish(2) { root -> root.getAsJsonArray("groups").remove(0) }
        catalog.refresh(); catalog.refresh()
        assertEquals(beforeGame, db.dao().game(ww))
        assertEquals(beforeTasks, daily(ww).personalTasks.first())
        assertEquals(beforeRecords, daily(ww).personalHistory.first())
        assertEquals(beforeEndfieldRecords, daily(PROFILE).personalHistory.first())
        assertEquals("내 엔필", db.dao().game(PROFILE)!!.name)
        assertEquals(300, db.dao().game(PROFILE)!!.dailyResetMinute)
    }

    @Test fun editingTaskPreservesIdAndCompletionWhilePauseBlocksNewChecks() = runBlocking {
        val ww = editor.saveGame(null, "명조", "Asia", "+09:00")
        daily(ww).setResetMinute(240)
        val id = editor.saveTask(ww, null, "처음 이름", "", true)
        daily(ww).setPersonalChecked(id, cycle(ww), true)
        val before = daily(ww).personalHistory.first().single().record
        assertEquals(id, editor.saveTask(ww, id, "수정한 이름", "수정 설명", false))
        assertEquals(before, daily(ww).personalHistory.first().single().record)
        assertEquals("수정한 이름", daily(ww).personalHistory.first().single().taskTitle)
        try { daily(ww).setPersonalChecked(id, cycle(ww), true); fail("paused") } catch (_: CatalogException) { }
        daily(ww).setPersonalChecked(id, cycle(ww), false)
        assertTrue(daily(ww).personalHistory.first().isEmpty())
        editor.saveTask(ww, id, "다시 사용", "", true)
        daily(ww).setPersonalChecked(id, cycle(ww), true)
        assertEquals(1, daily(ww).personalHistory.first().size)
    }

    @Test fun changingGameNameTimezoneAndResetDoesNotMoveOldHistory() = runBlocking {
        val ww = editor.saveGame(null, "명조", "Asia", "+09:00")
        daily(ww).setResetMinute(240)
        val id = editor.saveTask(ww, null, "임무", "", true)
        val oldCycle = cycle(ww)
        daily(ww).setPersonalChecked(id, oldCycle, true)
        val before = daily(ww).personalHistory.first().single().record
        editor.saveGame(ww, "명조 노트", "내 Asia", "+08:00")
        assertNotEquals(oldCycle, cycle(ww))
        try { daily(ww).setPersonalChecked(id, oldCycle, false); fail("stale cycle") } catch (_: CatalogException) { }
        assertEquals(before, daily(ww).personalHistory.first().single().record)
        assertEquals("+09:00", before.serverUtcOffsetAtCompletion)
        assertEquals(240, db.dao().game(ww)!!.dailyResetMinute)
    }

    @Test fun newPersonalCycleAndUncheckKeepOlderCompletionAndOriginalTimestamp() = runBlocking {
        val ww = editor.saveGame(null, "명조", "Asia", "+09:00")
        daily(ww).setResetMinute(240)
        val id = editor.saveTask(ww, null, "임무", "", true)
        val oldCycle = cycle(ww)
        daily(ww).setPersonalChecked(id, oldCycle, true)
        val before = daily(ww).personalHistory.first().single().record
        daily(ww).setPersonalChecked(id, oldCycle, true)
        assertEquals(before, daily(ww).personalHistory.first().single().record)
        val tomorrow = Clock.fixed(Instant.ofEpochMilli(oldCycle.endAt), ZoneOffset.UTC)
        val repo = DailyRepository(db, tomorrow, ww)
        val current = DailyCycles.current(tomorrow.millis(), 240, "+09:00")
        val state = DailyState(repo.game.first(), now = tomorrow.millis(), personalTasks = repo.personalTasks.first(), personalHistory = repo.personalHistory.first())
        assertFalse(dailyRows(state).single().checked)
        repo.setPersonalChecked(id, current, true)
        assertEquals(2, repo.personalHistory.first().size)
        repo.setPersonalChecked(id, current, false)
        assertEquals(listOf(before), repo.personalHistory.first().map { it.record })
    }

    @Test fun registrationValidatesInputAndCannotEditOfficialOrAnotherGamesTask() = runBlocking {
        val ww = editor.saveGame(null, "명조", "Asia", "+09:00")
        for (name in listOf(" ", "a".repeat(61))) {
            try { editor.saveGame(null, name, "Asia", "+09:00"); fail("invalid name") } catch (_: CatalogException) { }
        }
        try { editor.saveGame(PROFILE, "엔필", "Asia", "+09:00"); fail("official timezone") } catch (_: CatalogException) { }
        try { editor.saveTask(ww, null, " ", "", true); fail("empty title") } catch (_: CatalogException) { }
        val id = editor.saveTask(ww, null, "임무", "", true)
        try { editor.saveTask(PROFILE, id, "다른 게임으로 이동", "", true); fail("wrong game") } catch (_: CatalogException) { }
        try { editor.saveTask(ww, "daily-mission", "공식 키로 수정", "", true); fail("not personal") } catch (_: CatalogException) { }
        assertEquals(ww, db.dao().personalTask(id)!!.profileKey)
    }

    @Test fun personalRegistrationAndHistoryPersistAcrossReopenAndFeedRefresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "personal-persistence.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
        try {
            val edit = PersonalizationRepository(disk)
            val gf = edit.saveGame(null, "소녀전선 2", "HAOPLAY 한국판", "+09:00")
            val id = edit.saveTask(gf, null, "일일 임무", "보상도 확인", true)
            val repo = DailyRepository(disk, CLOCK, gf)
            repo.setResetMinute(240)
            repo.setPersonalChecked(id, DailyCycles.current(CLOCK.millis(), 240, "+09:00"), true)
            disk.close()
            disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
            CatalogRepository(disk, CatalogClient(feed), CLOCK).refresh()
            val again = DailyRepository(disk, CLOCK, gf)
            assertEquals("소녀전선 2", again.game.first()!!.name)
            assertEquals("보상도 확인", again.personalTasks.first().single().notes)
            assertEquals(id, again.personalHistory.first().single().record.taskId)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun versionTwoUpgradePreservesExistingSettingsAndAllowsNewGameAndTask() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "personal-v2-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JsonParser.parseString(javaClass.classLoader!!.getResourceAsStream(
            "kr.ac.jeiu.gamescheduler.data.SchedulerDatabase/2.json")!!.reader().readText()).asJsonObject.getAsJsonObject("database")
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
            legacy.execSQL("INSERT INTO games VALUES (?, ?, ?, ?)", arrayOf<Any>(PROFILE, "내 엔필", "+08:00", 240))
            legacy.version = 2
        }
        val migrated = Room.databaseBuilder(context, SchedulerDatabase::class.java, name)
            .addMigrations(SchedulerDatabase.MIGRATION_2_3).build()
        try {
            assertEquals(240, migrated.dao().game(PROFILE)!!.dailyResetMinute)
            assertEquals("내 엔필", migrated.dao().game(PROFILE)!!.name)
            assertFalse(migrated.dao().game(PROFILE)!!.isUserCreated)
            val edit = PersonalizationRepository(migrated)
            val ww = edit.saveGame(null, "명조", "Asia", "+09:00")
            val id = edit.saveTask(ww, null, "임무", "", true)
            val repo = DailyRepository(migrated, CLOCK, ww)
            repo.setResetMinute(240)
            repo.setPersonalChecked(id, DailyCycles.current(CLOCK.millis(), 240, "+09:00"), true)
            assertEquals(1, repo.personalHistory.first().size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
