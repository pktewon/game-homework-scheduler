package kr.ac.jeiu.gamescheduler

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
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

private const val EVENT = "$PROFILE:endfield-9560-event-0004:play"
private const val DAILY = "$PROFILE:endfield-9560-event-0005:play"
private val CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)

private class TestFeed : CatalogTransport {
    private val loader = javaClass.classLoader!!
    private val original = loader.getResourceAsStream("endfield-asia.json")!!.readBytes()
    var payload = original
    var manifest = loader.getResourceAsStream("manifest.json")!!.readBytes()
    var fail = false
    override fun get(url: String, limit: Int): ByteArray {
        if (fail) throw IOException("test offline")
        return if (url == MANIFEST_URL) manifest else payload
    }
    fun publish(revision: Long, mutate: (JsonObject) -> Unit = {}) {
        val root = JsonParser.parseString(original.toString(Charsets.UTF_8)).asJsonObject
        root.addProperty("releaseRevision", revision)
        mutate(root)
        val groups = root.getAsJsonArray("groups")
        val phases = groups.flatMap { it.asJsonObject.getAsJsonArray("phases").toList() }.map { it.asJsonObject }
        root.getAsJsonObject("summary").apply {
            addProperty("groups", groups.size()); addProperty("phases", phases.size)
            addProperty("dailyTasks", phases.sumOf { it.getAsJsonArray("dailyTaskTemplates").size() })
            addProperty("confirmedPeriods", phases.count { it["timeShape"].asString == "WINDOW" && it["timeState"].asString == "CONFIRMED" })
            addProperty("reviewPeriods", phases.count { it["timeState"].asString == "NEEDS_REVIEW" })
            addProperty("confirmedInstants", phases.count { it["timeShape"].asString == "INSTANT" })
        }
        payload = root.toString().toByteArray()
        val m = JsonParser.parseString(manifest.toString(Charsets.UTF_8)).asJsonObject
        m.getAsJsonArray("profiles")[0].asJsonObject.apply {
            addProperty("releaseRevision", revision)
            addProperty("url", RAW_BASE + "releases/endfield-asia-v$revision.json")
            addProperty("sha256", sha256(payload)); addProperty("bytes", payload.size)
        }
        manifest = m.toString().toByteArray()
    }
}

private fun JsonObject.phase(key: String): JsonObject = getAsJsonArray("groups").flatMap {
    it.asJsonObject.getAsJsonArray("phases").toList()
}.map { it.asJsonObject }.single { it["sourceKey"].asString == key }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CatalogSyncTest {
    private lateinit var db: SchedulerDatabase
    private lateinit var feed: TestFeed
    private lateinit var repository: CatalogRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SchedulerDatabase::class.java).build()
        feed = TestFeed()
        repository = CatalogRepository(db, CatalogClient(feed), CLOCK)
    }
    @After fun cleanup() { db.close() }

    @Test fun realPublishedFixtureImportsAllRowsAndKeepsUncertainTimes() = runBlocking {
        val result = repository.refresh()
        assertEquals(15, result.events)
        assertEquals(6, result.needsReview)
        val events = db.dao().events(PROFILE)
        assertEquals(4, events.sumOf { it.dailyTasks.size })
        assertTrue(db.dao().dailyRecords().isEmpty())
        events.filter { it.event.timeState == "NEEDS_REVIEW" }.forEach {
            assertTrue(it.event.startAt == null || it.event.endAt == null)
            assertEquals("기간 확인 필요", EventCard(it, CLOCK.millis()).label)
        }
        assertTrue(events.flatMap { it.checks }.none { it.isDone })
        assertNull(db.dao().checkRecord(EVENT, "goal"))
    }

    @Test fun metadataUpdateAndRepeatedImportPreserveEventAndDailyCompletion() = runBlocking {
        repository.refresh()
        repository.setChecked(EVENT, "goal", true)
        val originalRecord = db.dao().checkRecord(EVENT, "goal")
        val dailyRecord = DailyTaskRecordEntity(DAILY, "daily-mission", 1_792_800_000_000, CLOCK.millis())
        db.dao().putDailyRecord(dailyRecord)
        feed.publish(2) { root -> root.phase(EVENT).apply {
            addProperty("title", "수정된 이벤트 제목"); addProperty("revision", 3)
            addProperty("endAt", "2026-11-06T20:00:00Z")
            getAsJsonArray("checkTemplates")[0].asJsonObject.addProperty("title", "수정된 확인 항목")
        } }
        repository.refresh()
        assertEquals("수정된 이벤트 제목", db.dao().event(EVENT)!!.title)
        assertEquals(Instant.parse("2026-11-06T20:00:00Z").toEpochMilli(), db.dao().event(EVENT)!!.endAt)
        assertEquals(originalRecord, db.dao().checkRecord(EVENT, "goal"))
        assertEquals(listOf(dailyRecord), db.dao().dailyRecords())
        assertEquals("수정된 확인 항목", db.dao().check(EVENT, "goal")!!.title)
        assertTrue(repository.refresh().unchanged)
        assertEquals(originalRecord, db.dao().checkRecord(EVENT, "goal"))
        assertEquals(15, db.dao().events(PROFILE).size)
    }

    @Test fun missingAndReturningEventsKeepRecords() = runBlocking {
        repository.refresh()
        repository.setChecked(EVENT, "goal", true)
        val record = db.dao().checkRecord(EVENT, "goal")
        feed.publish(2) { root ->
            val groups = root.getAsJsonArray("groups")
            groups.remove(groups.single { it.asJsonObject["groupKey"].asString == "endfield-9560-event-0004" })
        }
        repository.refresh()
        assertTrue(db.dao().event(EVENT)!!.missingFromLatest)
        assertTrue(db.dao().check(EVENT, "goal")!!.missingFromLatest)
        assertEquals(record, db.dao().checkRecord(EVENT, "goal"))
        feed.publish(3)
        repository.refresh()
        assertFalse(db.dao().event(EVENT)!!.missingFromLatest)
        assertEquals(record, db.dao().checkRecord(EVENT, "goal"))
    }

    @Test fun removedCheckTemplateIsRetainedWithItsRecord() = runBlocking {
        repository.refresh()
        repository.setChecked(EVENT, "goal", true)
        feed.publish(2) { root -> root.phase(EVENT).getAsJsonArray("checkTemplates").remove(0) }
        repository.refresh()
        assertTrue(db.dao().check(EVENT, "goal")!!.missingFromLatest)
        assertTrue(db.dao().checkRecord(EVENT, "goal")!!.isDone)
    }

    @Test fun uncertainPeriodCanBecomeConfirmedWithoutResettingChecks() = runBlocking {
        val key = "$PROFILE:endfield-9560-event-0002:play"
        repository.refresh()
        repository.setChecked(key, "goal", true)
        val before = db.dao().checkRecord(key, "goal")
        feed.publish(2) { root -> root.phase(key).apply {
            addProperty("startAt", "2026-10-15T04:00:00Z")
            addProperty("timeState", "CONFIRMED")
            add("reviewReasons", com.google.gson.JsonArray())
            addProperty("revision", 3)
        } }
        repository.refresh()
        assertEquals("CONFIRMED", db.dao().event(key)!!.timeState)
        assertEquals(before, db.dao().checkRecord(key, "goal"))
    }

    @Test fun offlineAndHashFailureLeaveDatabaseUntouched() = runBlocking {
        repository.refresh()
        repository.setChecked(EVENT, "goal", true)
        val before = db.dao().events(PROFILE)
        val sync = db.dao().syncState(PROFILE)
        feed.fail = true
        try { repository.refresh(); fail("expected offline error") } catch (_: IOException) { }
        assertEquals(before, db.dao().events(PROFILE))
        feed.fail = false
        feed.publish(2)
        feed.payload += byteArrayOf(32)
        try { repository.refresh(); fail("expected hash error") } catch (_: CatalogException) { }
        assertEquals(before, db.dao().events(PROFILE))
        assertEquals(sync, db.dao().syncState(PROFILE))
    }

    @Test fun rollbackAndChangedBytesAtSameRevisionAreRejected() = runBlocking {
        feed.publish(2)
        repository.refresh()
        val before = db.dao().events(PROFILE)
        feed.publish(1)
        try { repository.refresh(); fail("expected rollback error") } catch (_: CatalogException) { }
        feed.publish(2) { it.phase(EVENT).addProperty("title", "동일 배포 번호의 다른 내용") }
        try { repository.refresh(); fail("expected same-revision error") } catch (_: CatalogException) { }
        assertEquals(before, db.dao().events(PROFILE))
    }

    @Test fun lateTransactionErrorRollsBackEarlierMetadataChanges() = runBlocking {
        repository.refresh()
        repository.setChecked(EVENT, "goal", true)
        val before = db.dao().events(PROFILE)
        feed.publish(2) { root ->
            root.phase(EVENT).addProperty("title", "트랜잭션 내 임시 변경")
            root.phase("$PROFILE:endfield-9560-event-0011:play").addProperty("revision", 1)
        }
        try { repository.refresh(); fail("expected lower event revision") } catch (_: CatalogException) { }
        assertEquals(before, db.dao().events(PROFILE))
        assertEquals(1, db.dao().syncState(PROFILE)!!.releaseRevision.toInt())
    }

    @Test fun uncheckOnlyChangesChosenUserRecordAndFlowReflectsIt() = runBlocking {
        repository.refresh()
        repository.setChecked(EVENT, "goal", true)
        repository.setChecked(EVENT, "rewards", true)
        repository.setChecked(EVENT, "goal", false)
        assertFalse(db.dao().checkRecord(EVENT, "goal")!!.isDone)
        assertNull(db.dao().checkRecord(EVENT, "goal")!!.completedAt)
        val event = repository.events.first().single { it.event.sourceKey == EVENT }
        assertFalse(event.checks.single { it.sourceItemKey == "goal" }.isDone)
        assertTrue(event.checks.single { it.sourceItemKey == "rewards" }.isDone)
    }

    @Test fun diskDatabaseSurvivesCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
        try {
            val repo = CatalogRepository(disk, CatalogClient(feed), CLOCK)
            repo.refresh(); repo.setChecked(EVENT, "goal", true)
            disk.close()
            disk = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).build()
            val again = CatalogRepository(disk, CatalogClient(feed), CLOCK)
            assertTrue(again.events.first().single { it.event.sourceKey == EVENT }.checks.single { it.sourceItemKey == "goal" }.isDone)
            assertTrue(again.refresh().unchanged)
            assertTrue(disk.dao().checkRecord(EVENT, "goal")!!.isDone)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun unknownKindWrongRegionDuplicateKeyAndCandidateAreRejectedBeforeWriting() = runBlocking {
        val changes: List<(JsonObject) -> Unit> = listOf(
            { it.phase(EVENT).addProperty("phaseKind", "UNKNOWN") },
            { it.getAsJsonObject("profile").addProperty("serverRegion", "AMERICAS") },
            { it.addProperty("publicationState", "CANDIDATE") },
            { it.phase(EVENT).getAsJsonArray("checkTemplates").add(it.phase(EVENT).getAsJsonArray("checkTemplates")[0].deepCopy()) },
            { it.phase(EVENT).addProperty("isDone", true) },
        )
        for (change in changes) {
            feed.publish(1, change)
            try { repository.refresh(); fail("expected invalid feed") } catch (_: CatalogException) { }
            assertTrue(db.dao().events(PROFILE).isEmpty())
            assertNull(db.dao().syncState(PROFILE))
        }
    }

    @Test fun activeUnfinishedEventsSortBeforeUncertainAndCompletedEvents() = runBlocking {
        repository.refresh()
        val events = db.dao().events(PROFILE)
        val current = Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()
        val active = EventCard(events.single { it.event.sourceKey == EVENT }, current)
        val uncertain = EventCard(events.first { it.event.timeState == "NEEDS_REVIEW" }, current)
        assertTrue(active.rank < uncertain.rank)
        repository.setChecked(EVENT, "goal", true); repository.setChecked(EVENT, "rewards", true)
        val complete = EventCard(db.dao().events(PROFILE).single { it.event.sourceKey == EVENT }, current)
        assertTrue(complete.rank > uncertain.rank)
    }
}
