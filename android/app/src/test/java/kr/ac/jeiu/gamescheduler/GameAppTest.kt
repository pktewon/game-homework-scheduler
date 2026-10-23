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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GameAppTest {
    private lateinit var db: SchedulerDatabase
    private lateinit var repo: GameAppRepository
    private lateinit var feed: TestFeed
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val target = LaunchableApp("com.example.testgame", "테스트 게임")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SchedulerDatabase::class.java).build()
        repo = GameAppRepository(db); feed = TestFeed()
        CatalogRepository(db, CatalogClient(feed), clock).refresh(); Unit
    }
    @After fun close() { db.close() }
    @Test fun connectReplaceAndDisconnectModifyOnlyLaunchFields() = runBlocking {
        val old = db.dao().game(PROFILE)!!
        assertNull(old.appPackageName); assertNull(old.appLabel)
        repo.connect(PROFILE, target)
        assertEquals(old.copy(appPackageName = target.packageName, appLabel = target.label), db.dao().game(PROFILE))
        val replacement = LaunchableApp("com.example.othergame", "다른 앱")
        repo.connect(PROFILE, replacement); assertEquals(replacement.packageName, db.dao().game(PROFILE)!!.appPackageName)
        repo.connect(PROFILE, null); assertEquals(old, db.dao().game(PROFILE))
    }
    @Test fun linksArePerGameAndTheSameAppCanBeUsedByDifferentProfiles() = runBlocking {
        val ww = PersonalizationRepository(db).saveGame(null, "명조", "Asia", "+09:00")
        repo.connect(PROFILE, target); repo.connect(ww, target)
        repo.connect(ww, null)
        assertEquals(target.packageName, db.dao().game(PROFILE)!!.appPackageName)
        assertNull(db.dao().game(ww)!!.appPackageName)
    }
    @Test fun linkingAndUnlinkingNeverMutateEventDailyMemoOrNotificationData() = runBlocking {
        val event = db.dao().events(PROFILE).first { it.checks.isNotEmpty() }
        CatalogRepository(db, clock = clock).setChecked(event.event.sourceKey, event.checks.first().sourceItemKey, true)
        val task = PersonalizationRepository(db).saveTask(PROFILE, null, "내 숙제", "", true)
        val daily = DailyRepository(db, clock); daily.setResetMinute(240)
        daily.setPersonalChecked(task, DailyCycles.current(clock.millis(), 240, "+08:00"), true)
        val note = MemoRepository(db, clock).save(PROFILE, MemoDraft(title = "메모", body = "플레이 후 직접 체크", eventKey = event.event.sourceKey))
        db.dao().putDeadlineSettings(DeadlineSettingsEntity(enabled = true, playLeadMinutes = 120))
        db.dao().putDeadlineReceipt(DeadlineReceiptEntity(event.event.sourceKey, event.event.endAt ?: clock.millis(), clock.millis()))
        val events = db.dao().allEvents(); val history = daily.personalHistory.first(); val memo = db.dao().memo(note)
        val settings = db.dao().deadlineSettings(); val receipts = db.dao().deadlineReceipts()
        repo.connect(PROFILE, target); repo.connect(PROFILE, null)
        assertEquals(events, db.dao().allEvents()); assertEquals(history, daily.personalHistory.first())
        assertEquals(memo, db.dao().memo(note)); assertEquals(settings, db.dao().deadlineSettings()); assertEquals(receipts, db.dao().deadlineReceipts())
    }
    @Test fun invalidPackageOrLabelAndMissingGameAreRejectedWithoutChanges() = runBlocking {
        val before = db.dao().game(PROFILE)
        for (bad in listOf("", "file.apk", "D:/game.apk", "https://game.test/app", "com..game", "com.example;am start", "a".repeat(256))) {
            // A filename-like string can be syntactically a package; file paths and URLs cannot.
            if (bad == "file.apk") continue
            try { repo.connect(PROFILE, LaunchableApp(bad, "게임")); fail("invalid package") } catch (_: CatalogException) { }
        }
        try { repo.connect(PROFILE, target.copy(label = " ")); fail("empty label") } catch (_: CatalogException) { }
        try { repo.connect("missing", target); fail("missing game") } catch (_: CatalogException) { }
        assertEquals(before, db.dao().game(PROFILE))
    }
    @Test fun editingGameNameAndOfficialCatalogRefreshPreserveLocalConnection() = runBlocking {
        repo.connect(PROFILE, target)
        PersonalizationRepository(db).saveGame(PROFILE, "내 엔필", "Asia", "+08:00")
        feed.publish(2) { it.getAsJsonArray("groups").remove(0) }
        CatalogRepository(db, CatalogClient(feed), clock).refresh()
        assertEquals(target.packageName, db.dao().game(PROFILE)!!.appPackageName)
        assertEquals(target.label, db.dao().game(PROFILE)!!.appLabel); assertEquals("내 엔필", db.dao().game(PROFILE)!!.name)
    }
    @Test fun connectionPersistsAcrossFileDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "launch-persistence.db"
        context.deleteDatabase(name); var disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
        try {
            disk.dao().ensureGame(GameEntity(PROFILE)); GameAppRepository(disk).connect(PROFILE, target)
            disk.close(); disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
            CatalogRepository(disk, CatalogClient(feed), clock).refresh()
            assertEquals(target.packageName, disk.dao().game(PROFILE)!!.appPackageName)
            assertEquals(target.label, disk.dao().game(PROFILE)!!.appLabel)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun schemaSixUpgradePreservesMemoChecksAndSettingsAndDefaultsToUnlinked() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "launch-v6-migration.db"
        context.deleteDatabase(name); val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JsonParser.parseString(javaClass.classLoader!!.getResourceAsStream(
            "kr.ac.jeiu.gamescheduler.data.SchedulerDatabase/6.json")!!.reader().readText()).asJsonObject.getAsJsonObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            for (entry in schema.getAsJsonArray("entities")) {
                val e = entry.asJsonObject
                legacy.execSQL(e["createSql"].asString.replace('$' + "{TABLE_NAME}", e["tableName"].asString))
                e.getAsJsonArray("indices")?.forEach { index -> legacy.execSQL(index.asJsonObject["createSql"].asString.replace('$' + "{TABLE_NAME}", e["tableName"].asString)) }
            }
            for (entry in schema.getAsJsonArray("views")) {
                val v = entry.asJsonObject; legacy.execSQL(v["createSql"].asString.replace('$' + "{VIEW_NAME}", v["viewName"].asString))
            }
            schema.getAsJsonArray("setupQueries").forEach { legacy.execSQL(it.asString) }
            legacy.execSQL("INSERT INTO games VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(PROFILE, "내 엔필", "+08:00", 240, "Asia", 0))
            legacy.execSQL("INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("old:play", PROFILE, "old", "기존", "기존 참여", 1, "PLAY", "WINDOW", null, 100L, 200L, "CONFIRMED", "", "{}", "", 0, 1))
            legacy.execSQL("INSERT INTO check_templates VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("old:play", "goal", "참여", 0, 0))
            legacy.execSQL("INSERT INTO check_records VALUES (?, ?, ?, ?)", arrayOf<Any>("old:play", "goal", 1, clock.millis()))
            legacy.execSQL("INSERT INTO memos VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>("my-memo", PROFILE, "old:play", "메모", "그대로 보관", 100L, 150L))
            legacy.execSQL("INSERT INTO deadline_settings VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(1, 1, 1, 120, 1, 180))
            legacy.version = 6
        }
        val migrated = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).addMigrations(SchedulerDatabase.MIGRATION_6_7).build()
        try {
            assertEquals(7, migrated.openHelper.readableDatabase.version)
            assertNull(migrated.dao().game(PROFILE)!!.appPackageName)
            assertTrue(migrated.dao().checkRecord("old:play", "goal")!!.isDone)
            assertEquals("그대로 보관", migrated.dao().memo("my-memo")!!.body)
            assertEquals(120, migrated.dao().deadlineSettings()!!.playLeadMinutes)
            assertEquals(240, migrated.dao().game(PROFILE)!!.dailyResetMinute)
            GameAppRepository(migrated).connect(PROFILE, target)
            assertEquals(target.packageName, migrated.dao().game(PROFILE)!!.appPackageName)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
