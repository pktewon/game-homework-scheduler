package kr.ac.jeiu.gamescheduler

import android.app.Application
import android.content.Context
import android.content.ContentValues
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ManualEventsTest {
    private lateinit var db: SchedulerDatabase
    private lateinit var repo: ManualEventsRepository
    private lateinit var feed: TestFeed
    private lateinit var catalog: CatalogRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val sample = EventDraft(title = "가을 축제", notes = "보상도 확인", playStart = 100L, playEnd = 300L,
        includeShop = true, shopStart = 200L, shopEnd = 500L)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SchedulerDatabase::class.java).build()
        repo = ManualEventsRepository(db); feed = TestFeed()
        catalog = CatalogRepository(db, CatalogClient(feed), clock); catalog.refresh(); Unit
    }
    @After fun cleanup() { db.close() }

    @Test fun participationAndExchangeHaveSeparatePeriodsAndIndependentChecks() = runBlocking {
        val key = repo.save(PROFILE, sample)
        val play = db.dao().event("$key:play")!!; val shop = db.dao().event("$key:shop")!!
        assertEquals(100L, play.startAt); assertEquals(300L, play.endAt)
        assertEquals(200L, shop.startAt); assertEquals(500L, shop.endAt)
        assertEquals("PLAY", play.phaseKind); assertEquals("SHOP", shop.phaseKind)
        assertTrue(play.isUserCreated && shop.isUserCreated); assertEquals("", play.sourceUrl)
        catalog.setChecked(play.sourceKey, "goal", true)
        val cards = db.dao().events(PROFILE).filter { it.event.groupKey == key }
        assertEquals(3, cards.sumOf { it.checks.size })
        assertTrue(cards.single { it.event.phaseKind == "PLAY" }.checks.single { it.sourceItemKey == "goal" }.isDone)
        assertFalse(cards.single { it.event.phaseKind == "SHOP" }.checks.single().isDone)
    }

    @Test fun editingTitleNotesAndBothPeriodsPreservesIdsAndExactCompletionRecords() = runBlocking {
        val key = repo.save(PROFILE, sample)
        catalog.setChecked("$key:play", "goal", true); catalog.setChecked("$key:shop", "wanted-exchanges", true)
        val beforePlay = db.dao().checkRecord("$key:play", "goal")
        val beforeShop = db.dao().checkRecord("$key:shop", "wanted-exchanges")
        assertEquals(key, repo.save(PROFILE, repo.load(PROFILE, key).copy(title = "수정 축제", notes = "새 메모",
            playStart = 50, playEnd = 400, shopStart = 100, shopEnd = 600)))
        assertEquals(beforePlay, db.dao().checkRecord("$key:play", "goal"))
        assertEquals(beforeShop, db.dao().checkRecord("$key:shop", "wanted-exchanges"))
        assertEquals(2, db.dao().eventGroup(PROFILE, key).size)
        assertEquals("수정 축제", repo.load(PROFILE, key).title)
        assertEquals(600L, db.dao().event("$key:shop")!!.endAt)
        assertEquals(2L, db.dao().event("$key:play")!!.revision)
    }

    @Test fun hidingAndRestoringExchangeRetainsItsDatesAndCompletion() = runBlocking {
        val key = repo.save(PROFILE, sample)
        catalog.setChecked("$key:shop", "wanted-exchanges", true)
        val before = db.dao().checkRecord("$key:shop", "wanted-exchanges")
        repo.save(PROFILE, repo.load(PROFILE, key).copy(includeShop = false, shopStart = null, shopEnd = null))
        val hidden = repo.load(PROFILE, key)
        assertFalse(hidden.includeShop); assertEquals(sample.shopEnd, hidden.shopEnd)
        val card = EventCard(db.dao().events(PROFILE).single { it.event.sourceKey == "$key:shop" }, 250)
        assertEquals(6, card.rank); assertTrue(card.label.contains("기록 유지")); assertTrue(card.allDone)
        repo.save(PROFILE, hidden.copy(includeShop = true))
        assertFalse(db.dao().event("$key:shop")!!.missingFromLatest)
        assertEquals(before, db.dao().checkRecord("$key:shop", "wanted-exchanges"))
    }

    @Test fun exchangeCanBeAddedLaterWithoutResettingParticipation() = runBlocking {
        val key = repo.save(PROFILE, sample.copy(includeShop = false))
        assertNull(db.dao().event("$key:shop"))
        catalog.setChecked("$key:play", "rewards", true)
        val before = db.dao().checkRecord("$key:play", "rewards")
        repo.save(PROFILE, repo.load(PROFILE, key).copy(includeShop = true, shopStart = 200, shopEnd = 700))
        assertEquals(before, db.dao().checkRecord("$key:play", "rewards"))
        assertFalse(db.dao().events(PROFILE).single { it.event.sourceKey == "$key:shop" }.checks.single().isDone)
    }

    @Test fun invalidParticipationOrExchangeRangeRejectsTheWholeEdit() = runBlocking {
        val key = repo.save(PROFILE, sample)
        catalog.setChecked("$key:play", "goal", true)
        val before = db.dao().events(PROFILE)
        for (bad in listOf(sample.copy(groupKey = key, title = "bad", playEnd = 100),
            sample.copy(groupKey = key, title = "bad", shopEnd = 100), sample.copy(title = " "),
            sample.copy(notes = "a".repeat(1001)))) {
            try { repo.save(PROFILE, bad); fail("invalid edit") } catch (_: CatalogException) { }
        }
        assertEquals(before, db.dao().events(PROFILE))
    }

    @Test fun unknownBoundsStayNullAndNeverAppearAsActiveCountdowns() = runBlocking {
        val key = repo.save(PROFILE, sample.copy(playStart = null, shopEnd = null))
        val cards = db.dao().events(PROFILE).filter { it.event.groupKey == key }.map { EventCard(it, 250) }
        assertTrue(cards.all { it.rank == 1 && it.label == "기간 확인 필요" })
        assertNull(repo.load(PROFILE, key).playStart); assertNull(repo.load(PROFILE, key).shopEnd)
        catalog.setChecked("$key:play", "goal", true)
        repo.save(PROFILE, repo.load(PROFILE, key).copy(playStart = 100, shopEnd = 500))
        assertTrue(db.dao().checkRecord("$key:play", "goal")!!.isDone)
    }

    @Test fun officialRefreshAndRemovedOfficialGroupsDoNotTouchManualEventsOrChecks() = runBlocking {
        val ww = PersonalizationRepository(db).saveGame(null, "명조", "Asia", "+09:00")
        val a = repo.save(PROFILE, sample); val b = repo.save(ww, sample)
        catalog.setChecked("$a:play", "goal", true); catalog.setChecked("$b:shop", "wanted-exchanges", true)
        val beforeA = db.dao().events(PROFILE).filter { it.event.isUserCreated }
        val beforeB = db.dao().events(ww)
        feed.publish(2) { it.getAsJsonArray("groups").remove(0) }; catalog.refresh(); catalog.refresh()
        assertEquals(beforeA, db.dao().events(PROFILE).filter { it.event.isUserCreated })
        assertEquals(beforeB, db.dao().events(ww))
        assertTrue(db.dao().observeAllEvents().first().any { it.event.profileKey == ww })
        assertFalse(db.dao().events(PROFILE).any { it.event.profileKey == ww })
    }

    @Test fun officialOrAnotherGamesEventCannotBeEditedAndSameTitlesStayIndependent() = runBlocking {
        val official = db.dao().events(PROFILE).first().event
        try { repo.save(PROFILE, sample.copy(groupKey = official.groupKey)); fail("official") } catch (_: CatalogException) { }
        val key = repo.save(PROFILE, sample)
        val ww = PersonalizationRepository(db).saveGame(null, "명조", "Asia", "+09:00")
        try { repo.save(ww, sample.copy(groupKey = key)); fail("wrong game") } catch (_: CatalogException) { }
        try { repo.save("missing", sample); fail("missing game") } catch (_: CatalogException) { }
        val second = repo.save(PROFILE, sample)
        assertNotEquals(key, second)
        catalog.setChecked("$key:play", "goal", true)
        assertNull(db.dao().checkRecord("$second:play", "goal"))
        assertEquals(official, db.dao().event(official.sourceKey))
    }

    @Test fun datesAndChecksPersistAcrossReopenAndFeedRefresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "manual-persistence.db"; context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
        try {
            val ww = PersonalizationRepository(disk).saveGame(null, "명조", "Asia", "+09:00")
            val key = ManualEventsRepository(disk).save(ww, sample)
            CatalogRepository(disk, CatalogClient(feed), clock).setChecked("$key:shop", "wanted-exchanges", true)
            disk.close(); disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
            CatalogRepository(disk, CatalogClient(feed), clock).refresh()
            assertEquals(sample.copy(groupKey = key), ManualEventsRepository(disk).load(ww, key))
            assertTrue(disk.dao().checkRecord("$key:shop", "wanted-exchanges")!!.isDone)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun schemaThreeUpgradePreservesExistingEventPersonalTaskAndBothRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "manual-v3-migration.db"; context.deleteDatabase(name)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JsonParser.parseString(javaClass.classLoader!!.getResourceAsStream(
            "kr.ac.jeiu.gamescheduler.data.SchedulerDatabase/3.json")!!.reader().readText()).asJsonObject.getAsJsonObject("database")
        val old = db.dao().events(PROFILE).first().event
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            for (entry in schema.getAsJsonArray("entities")) {
                val e = entry.asJsonObject; legacy.execSQL(e["createSql"].asString.replace('$' + "{TABLE_NAME}", e["tableName"].asString))
            }
            for (entry in schema.getAsJsonArray("views")) {
                val v = entry.asJsonObject; legacy.execSQL(v["createSql"].asString.replace('$' + "{VIEW_NAME}", v["viewName"].asString))
            }
            schema.getAsJsonArray("setupQueries").forEach { legacy.execSQL(it.asString) }
            legacy.execSQL("INSERT INTO games VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(PROFILE, "내 엔필", "+08:00", 240, "Asia", 0))
            legacy.insertOrThrow("events", null, ContentValues().apply {
                put("sourceKey", old.sourceKey); put("profileKey", PROFILE); put("groupKey", old.groupKey); put("groupTitle", old.groupTitle)
                put("title", old.title); put("revision", old.revision); put("phaseKind", old.phaseKind); put("timeShape", old.timeShape)
                put("occursAt", old.occursAt); put("startAt", old.startAt); put("endAt", old.endAt); put("timeState", old.timeState)
                put("sourceUrl", old.sourceUrl); put("evidenceJson", "{}"); put("notes", ""); put("missingFromLatest", 0)
            })
            legacy.execSQL("INSERT INTO check_templates VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(old.sourceKey, "goal", "참여", 0, 0))
            legacy.execSQL("INSERT INTO check_records VALUES (?, ?, ?, ?)", arrayOf<Any>(old.sourceKey, "goal", 1, clock.millis()))
            legacy.execSQL("INSERT INTO personal_daily_tasks VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("my-task", PROFILE, "내 숙제", "", 1))
            legacy.execSQL("INSERT INTO personal_daily_records VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("my-task", 100, clock.millis(), 240, "+08:00"))
            legacy.version = 3
        }
        val migrated = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).addMigrations(SchedulerDatabase.MIGRATION_3_4, SchedulerDatabase.MIGRATION_4_5, SchedulerDatabase.MIGRATION_5_6).build()
        try {
            assertEquals(6, migrated.openHelper.readableDatabase.version)
            assertFalse(migrated.dao().event(old.sourceKey)!!.isUserCreated)
            assertTrue(migrated.dao().checkRecord(old.sourceKey, "goal")!!.isDone)
            assertEquals(240, migrated.dao().game(PROFILE)!!.dailyResetMinute)
            assertEquals(1, migrated.dao().observePersonalHistory(PROFILE).first().size)
            ManualEventsRepository(migrated).save(PROFILE, sample)
            assertEquals(3, migrated.dao().events(PROFILE).size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
