package kr.ac.jeiu.gamescheduler

import android.app.Application
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

private class MemoClock(var now: Long = 2_000_000_000_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MemoTest {
    private lateinit var db: SchedulerDatabase
    private lateinit var repo: MemoRepository
    private lateinit var clock: MemoClock
    private lateinit var ww: String
    private lateinit var gf: String
    private lateinit var group: String
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SchedulerDatabase::class.java).build()
        clock = MemoClock(); repo = MemoRepository(db, clock)
        val edit = PersonalizationRepository(db)
        ww = edit.saveGame(null, "명조", "Asia", "+09:00"); gf = edit.saveGame(null, "소녀전선 2", "HAOPLAY 한국판", "+09:00")
        group = ManualEventsRepository(db).save(ww, EventDraft(title = "테스트 축제", playStart = clock.now - 1000,
            playEnd = clock.now + 100000, includeShop = true, shopStart = clock.now - 1000, shopEnd = clock.now + 200000)); Unit
    }
    @After fun close() { db.close() }
    private fun draft(title: String = "공략 메모", event: String? = null) = MemoDraft(title = title, body = "첫째 줄\n  둘째 줄 🕹️\n", eventKey = event)

    @Test fun gameMemoWithoutEventPreservesTextAndTimestamps() = runBlocking {
        val id = repo.save(ww, draft())
        val row = db.dao().observeMemos(ww).first().single()
        assertEquals(id, row.memo.memoId); assertNull(row.memo.eventKey); assertNull(row.eventTitle)
        assertEquals(draft().body, row.memo.body)
        assertEquals(clock.now, row.memo.createdAt); assertEquals(clock.now, row.memo.updatedAt)
    }
    @Test fun duplicateTitlesAndDifferentGamesHaveIndependentIdsAndLists() = runBlocking {
        val a = repo.save(ww, draft()); val b = repo.save(ww, draft()); val c = repo.save(gf, draft())
        assertEquals(3, setOf(a,b,c).size)
        assertEquals(setOf(a,b), db.dao().observeMemos(ww).first().map { it.memo.memoId }.toSet())
        assertEquals(c, db.dao().observeMemos(gf).first().single().memo.memoId)
    }
    @Test fun editRelinkAndUnlinkKeepIdentityCreationTimeAndCompletionRecords() = runBlocking {
        val catalog = CatalogRepository(db, clock = clock)
        catalog.setChecked("$group:play", "goal", true)
        val events = db.dao().events(ww)
        val id = repo.save(ww, draft(event = "$group:play")); val original = db.dao().memo(id)!!
        clock.now += 1000
        assertEquals(id, repo.save(ww, repo.load(ww, id).copy(title = "수정 제목", body = "수정 내용", eventKey = "$group:shop")))
        val edited = db.dao().memo(id)!!
        assertEquals(original.createdAt, edited.createdAt); assertEquals(clock.now, edited.updatedAt)
        assertEquals("$group:shop", edited.eventKey)
        repo.save(ww, repo.load(ww, id).copy(eventKey = null))
        assertNull(db.dao().memo(id)!!.eventKey)
        assertEquals(events, db.dao().events(ww))
        assertEquals(1, db.dao().observeMemos(ww).first().size)
    }
    @Test fun anotherGamesEventCannotBeLinkedAndFailedEditChangesNothing() = runBlocking {
        val other = ManualEventsRepository(db).save(gf, EventDraft(title = "다른 게임 이벤트"))
        val id = repo.save(ww, draft(event = "$group:play")); val before = db.dao().memo(id)
        for (key in listOf("$other:play", "missing:event")) {
            try { repo.save(ww, repo.load(ww, id).copy(title = "잘못된 수정", eventKey = key)); fail("wrong event") } catch (_: CatalogException) { }
            try { repo.save(ww, draft(event = key)); fail("wrong new link") } catch (_: CatalogException) { }
        }
        assertEquals(before, db.dao().memo(id)); assertEquals(1, db.dao().observeMemos(ww).first().size)
    }
    @Test fun anotherGamesMemoAndUnknownMemoOrGameCannotBeEdited() = runBlocking {
        val id = repo.save(ww, draft()); val before = db.dao().memo(id)
        try { repo.save(gf, draft().copy(memoId = id)); fail("wrong owner") } catch (_: CatalogException) { }
        try { repo.load(gf, id); fail("wrong reader") } catch (_: CatalogException) { }
        try { repo.save(ww, draft().copy(memoId = "missing")); fail("missing memo") } catch (_: CatalogException) { }
        try { repo.save("missing", draft()); fail("missing game") } catch (_: CatalogException) { }
        assertEquals(before, db.dao().memo(id)); assertTrue(db.dao().observeMemos(gf).first().isEmpty())
    }
    @Test fun inputBoundsAndBlankValidationAreAtomic() = runBlocking {
        for (bad in listOf(draft(" "), draft("a".repeat(101)), draft().copy(body = " \n "), draft().copy(body = "a".repeat(10001)))) {
            try { repo.save(ww, bad); fail("invalid input") } catch (_: CatalogException) { }
        }
        assertTrue(db.dao().observeMemos(ww).first().isEmpty())
        val id = repo.save(ww, draft("a".repeat(100)).copy(body = "b".repeat(10000)))
        assertEquals(10000, db.dao().memo(id)!!.body.length)
    }
    @Test fun eventRenameUnknownPeriodAndHiddenExchangeKeepLinkedMemo() = runBlocking {
        val id = repo.save(ww, draft(event = "$group:shop")); val before = db.dao().memo(id)
        val events = ManualEventsRepository(db)
        events.save(ww, events.load(ww, group).copy(title = "이름이 바뀐 축제", includeShop = false))
        val row = db.dao().observeMemos(ww).first().single()
        assertEquals(before, row.memo); assertTrue(row.eventTitle!!.contains("이름이 바뀐 축제")); assertEquals(true, row.eventMissing)
        events.save(ww, events.load(ww, group).copy(includeShop = true, shopEnd = null))
        assertEquals("NEEDS_REVIEW", db.dao().observeMemos(ww).first().single().eventTimeState)
        assertEquals(before, db.dao().memo(id))
    }
    @Test fun officialRefreshRemovalAndReappearanceNeverOverwriteMemoOrLink() = runBlocking {
        val feed = TestFeed(); val catalog = CatalogRepository(db, CatalogClient(feed), clock)
        catalog.refresh()
        val target = db.dao().events(PROFILE).first().event
        val id = repo.save(PROFILE, draft(event = target.sourceKey)); val before = db.dao().memo(id)
        feed.publish(2) { root ->
            val groups = root.getAsJsonArray("groups")
            for (index in groups.size()-1 downTo 0) if (groups[index].asJsonObject.getAsJsonArray("phases").any { it.asJsonObject["sourceKey"].asString == target.sourceKey }) groups.remove(index)
        }
        catalog.refresh()
        assertEquals(before, db.dao().memo(id)); assertTrue(db.dao().event(target.sourceKey)!!.missingFromLatest)
        assertEquals(true, db.dao().observeMemos(PROFILE).first().single().eventMissing)
        feed.publish(3); catalog.refresh()
        assertEquals(before, db.dao().memo(id)); assertEquals(false, db.dao().observeMemos(PROFILE).first().single().eventMissing)
    }
    @Test fun boardSortsRecentlyEditedFirstAndNeverMovesCreationTimeBackwards() = runBlocking {
        val a = repo.save(ww, draft("첫 메모")); clock.now += 1000
        val b = repo.save(ww, draft("둘째 메모")); clock.now += 1000
        repo.save(ww, repo.load(ww, a).copy(body = "수정"))
        assertEquals(listOf(a,b), db.dao().observeMemos(ww).first().map { it.memo.memoId })
        val before = db.dao().memo(a)!!; clock.now -= 10000
        repo.save(ww, repo.load(ww, a).copy(body = "시각 변경 후 수정"))
        assertEquals(before.createdAt, db.dao().memo(a)!!.createdAt); assertEquals(before.updatedAt, db.dao().memo(a)!!.updatedAt)
    }
    @Test fun linkedMemoAndSettingsPersistAcrossFileDatabaseReopenAndOfficialRefresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "memo-persistence.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
        try {
            val game = PersonalizationRepository(disk).saveGame(null, "명조", "Asia", "+09:00")
            val group = ManualEventsRepository(disk).save(game, EventDraft(title = "기억할 이벤트"))
            val id = MemoRepository(disk, clock).save(game, draft(event = "$group:play"))
            disk.dao().putDeadlineSettings(DeadlineSettingsEntity(enabled = true, playLeadMinutes = 123))
            val before = disk.dao().memo(id)
            disk.close(); disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
            CatalogRepository(disk, CatalogClient(TestFeed()), clock).refresh()
            assertEquals(before, disk.dao().memo(id)); assertEquals("기억할 이벤트 · 참여", disk.dao().observeMemos(game).first().single().eventTitle)
            assertEquals(123, disk.dao().deadlineSettings()!!.playLeadMinutes)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun foreignKeysProtectLinkedEventAndGameFromPhysicalDeletion() = runBlocking {
        // No check templates or other children: only the memo FK can prevent these deletes.
        val bareEvent = db.dao().event("$group:play")!!.copy(sourceKey = "memo-only-event")
        db.dao().putEvent(bareEvent)
        val id = repo.save(ww, draft(event = bareEvent.sourceKey))
        val bareGame = PersonalizationRepository(db).saveGame(null, "메모만 있는 게임", "내 서버", "+09:00")
        repo.save(bareGame, draft())
        try { db.openHelper.writableDatabase.execSQL("DELETE FROM events WHERE sourceKey = ?", arrayOf(bareEvent.sourceKey)); fail("event deleted") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        try { db.openHelper.writableDatabase.execSQL("DELETE FROM games WHERE profileKey = ?", arrayOf(bareGame)); fail("game deleted") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(bareEvent.sourceKey, db.dao().memo(id)!!.eventKey)
    }
    @Test fun schemaFiveUpgradePreservesChecksSettingsAndDeliveryHistoryAndAllowsMemoSave() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "memo-v5-migration.db"
        context.deleteDatabase(name); val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JsonParser.parseString(javaClass.classLoader!!.getResourceAsStream(
            "kr.ac.jeiu.gamescheduler.data.SchedulerDatabase/5.json")!!.reader().readText()).asJsonObject.getAsJsonObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            for (entry in schema.getAsJsonArray("entities")) {
                val e = entry.asJsonObject; legacy.execSQL(e["createSql"].asString.replace('$' + "{TABLE_NAME}", e["tableName"].asString))
            }
            for (entry in schema.getAsJsonArray("views")) {
                val v = entry.asJsonObject; legacy.execSQL(v["createSql"].asString.replace('$' + "{VIEW_NAME}", v["viewName"].asString))
            }
            schema.getAsJsonArray("setupQueries").forEach { legacy.execSQL(it.asString) }
            legacy.execSQL("INSERT INTO games VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(PROFILE, "내 엔필", "+08:00", 240, "Asia", 0))
            legacy.execSQL("INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("old:play", PROFILE, "old", "기존", "기존 참여", 1, "PLAY", "WINDOW", null,
                    clock.now - 100000, clock.now + 100000, "CONFIRMED", "", "{}", "기존 이벤트 메모", 0, 1))
            legacy.execSQL("INSERT INTO check_templates VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("old:play", "goal", "참여", 0, 0))
            legacy.execSQL("INSERT INTO check_records VALUES (?, ?, ?, ?)", arrayOf<Any>("old:play", "goal", 1, clock.now))
            legacy.execSQL("INSERT INTO deadline_settings VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(1, 1, 1, 1440, 1, 180))
            legacy.execSQL("INSERT INTO deadline_receipts VALUES (?, ?, ?)", arrayOf<Any>("old:play", clock.now + 100000, clock.now))
            legacy.version = 5
        }
        val migrated = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).addMigrations(SchedulerDatabase.MIGRATION_5_6).build()
        try {
            assertEquals(6, migrated.openHelper.readableDatabase.version)
            assertTrue(migrated.dao().checkRecord("old:play", "goal")!!.isDone)
            assertEquals(240, migrated.dao().game(PROFILE)!!.dailyResetMinute)
            assertTrue(migrated.dao().deadlineSettings()!!.enabled); assertEquals(1, migrated.dao().deadlineReceipts().size)
            assertTrue(migrated.dao().observeMemos(PROFILE).first().isEmpty())
            MemoRepository(migrated, clock).save(PROFILE, draft(event = "old:play"))
            assertEquals(1, migrated.dao().observeMemos(PROFILE).first().size)
            assertEquals("기존 이벤트 메모", migrated.dao().event("old:play")!!.notes)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
