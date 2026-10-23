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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kr.ac.jeiu.gamescheduler.data.*
import kr.ac.jeiu.gamescheduler.notifications.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class DeadlineClock(var now: Long = 2_000_000_000_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}
private class FakeNotifier : DeadlineNotifier {
    var play = true; var shop = true; var fail = false
    val posts = mutableListOf<DeadlineNotice>(); val visible = mutableSetOf<String>()
    override fun allowed(shop: Boolean) = if (shop) this.shop else play
    override fun retain(tags: Set<String>) { visible.retainAll(tags) }
    override fun post(notice: DeadlineNotice): Boolean {
        if (fail) return false
        posts.add(notice); visible.add(notice.key.tag); return true
    }
}
private class FakeAlarm : DeadlineAlarm {
    var next: Long? = null
    override fun schedule(at: Long?) { next = at }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeadlineTest {
    private lateinit var db: SchedulerDatabase
    private lateinit var edit: ManualEventsRepository
    private lateinit var catalog: CatalogRepository
    private lateinit var clock: DeadlineClock
    private lateinit var notifier: FakeNotifier
    private lateinit var alarm: FakeAlarm
    private lateinit var coordinator: DeadlineCoordinator
    private lateinit var group: String
    private val enabled = DeadlineSettingsEntity(enabled = true, playLeadMinutes = 60, shopLeadMinutes = 30)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SchedulerDatabase::class.java).build()
        clock = DeadlineClock(); notifier = FakeNotifier(); alarm = FakeAlarm()
        catalog = CatalogRepository(db, clock = clock)
        db.dao().ensureGame(GameEntity(PROFILE))
        edit = ManualEventsRepository(db)
        group = edit.save(PROFILE, EventDraft(title = "테스트 이벤트", playStart = clock.now - 3_600_000L,
            playEnd = clock.now + 7_200_000L, includeShop = true, shopStart = clock.now - 3_600_000L, shopEnd = clock.now + 10_800_000L))
        coordinator = DeadlineCoordinator(db, notifier, alarm, clock); Unit
    }
    @After fun close() { db.close() }

    @Test fun defaultIsDisabledAndSeparateLeadTimesScheduleTheEarliestPhase() = runBlocking {
        coordinator.reconcile(); assertNull(alarm.next); assertTrue(notifier.posts.isEmpty())
        coordinator.save(enabled)
        assertEquals(clock.now + 3_600_000L, alarm.next)
        assertEquals(2, coordinator.state.value.pending)
        val notices = DeadlinePolicy.notices(db.dao().allEvents(), emptyMap(), enabled, clock.now)
        assertEquals(clock.now + 9_000_000L, notices.single { it.isShop }.triggerAt)
    }
    @Test fun noEarlyDeliveryAndEachPhaseNotifiesOnlyOnceAtItsOwnThreshold() = runBlocking {
        coordinator.save(enabled)
        clock.now = alarm.next!! - 1; coordinator.reconcile(); assertTrue(notifier.posts.isEmpty())
        clock.now++; coordinator.reconcile(); assertEquals(listOf("PLAY"), notifier.posts.map { it.event.phaseKind })
        val playTime = db.dao().deadlineReceipts().single().deliveredAt
        coordinator.reconcile(); assertEquals(1, notifier.posts.size)
        clock.now = alarm.next!!; coordinator.reconcile()
        assertEquals(listOf("PLAY", "SHOP"), notifier.posts.map { it.event.phaseKind })
        assertEquals(2, db.dao().deadlineReceipts().size); assertNull(alarm.next)
        assertEquals(playTime, db.dao().deadlineReceipts().single { it.eventKey.endsWith(":play") }.deliveredAt)
    }
    @Test fun uncertainEvenWithKnownEndNeverSchedulesOrDelivers() = runBlocking {
        val draft = edit.load(PROFILE, group)
        edit.save(PROFILE, draft.copy(playStart = null, shopEnd = null))
        coordinator.save(enabled.copy(playLeadMinutes = 10080, shopLeadMinutes = 10080))
        assertNull(alarm.next); assertTrue(notifier.posts.isEmpty()); assertTrue(db.dao().deadlineReceipts().isEmpty())
        assertNull(edit.load(PROFILE, group).playStart)
    }
    @Test fun expiredHiddenInstantAndInvalidRowsAreExcluded() = runBlocking {
        val original = db.dao().allEvents().first()
        val e = original.event
        for (bad in listOf(e.copy(timeState = "NEEDS_REVIEW"), e.copy(endAt = null), e.copy(startAt = null),
            e.copy(endAt = clock.now), e.copy(missingFromLatest = true), e.copy(timeShape = "INSTANT"),
            e.copy(phaseKind = "CONTENT_UPDATE"), e.copy(startAt = e.endAt))) {
            assertTrue(DeadlinePolicy.notices(listOf(original.copy(event = bad)), emptyMap(), enabled, clock.now).isEmpty())
        }
        assertTrue(DeadlinePolicy.notices(listOf(original.copy(checks = emptyList())), emptyMap(), enabled, clock.now).isEmpty())
    }
    @Test fun allParticipationKindsUseParticipationSettingsAndStartClampsEarlyLead() = runBlocking {
        val base = db.dao().allEvents().first()
        for (kind in listOf("PLAY", "ATTENDANCE", "CHALLENGE", "WEB")) {
            val row = base.copy(event = base.event.copy(phaseKind = kind, startAt = clock.now + 60_000L))
            val notice = DeadlinePolicy.notices(listOf(row), emptyMap(), enabled.copy(playLeadMinutes = 10080), clock.now).single()
            assertFalse(notice.isShop); assertEquals(clock.now + 60_000L, notice.triggerAt)
        }
    }
    @Test fun partialChecksKeepParticipationAndAllChecksCancelOnlyThatPhase() = runBlocking {
        coordinator.save(enabled)
        catalog.setChecked("$group:play", "goal", true); coordinator.reconcile()
        assertEquals(2, coordinator.state.value.pending)
        catalog.setChecked("$group:play", "rewards", true); coordinator.reconcile()
        assertEquals(1, coordinator.state.value.pending)
        assertEquals(clock.now + 9_000_000L, alarm.next)
        assertTrue(db.dao().checkRecord("$group:play", "goal")!!.isDone)
    }
    @Test fun postedNotificationIsRemovedWhenCompletedAndUncheckDoesNotRepeatIt() = runBlocking {
        coordinator.save(enabled.copy(playLeadMinutes = 180))
        assertEquals(1, notifier.visible.size)
        catalog.setChecked("$group:play", "goal", true); catalog.setChecked("$group:play", "rewards", true)
        coordinator.reconcile(); assertTrue(notifier.visible.isEmpty())
        catalog.setChecked("$group:play", "goal", false); coordinator.reconcile()
        assertEquals(1, notifier.posts.size); assertEquals(1, db.dao().deadlineReceipts().size)
    }
    @Test fun editingDeadlineCancelsOldVisibleNoticeAndCanNotifyForNewDeadline() = runBlocking {
        coordinator.save(enabled.copy(playLeadMinutes = 180)); val oldTag = notifier.visible.single()
        val completionBefore = db.dao().checkRecord("$group:play", "goal")
        edit.save(PROFILE, edit.load(PROFILE, group).copy(title = "수정한 이벤트", playEnd = clock.now + 3_600_000L))
        coordinator.reconcile()
        assertEquals(2, notifier.posts.size); assertFalse(oldTag in notifier.visible)
        assertEquals(2, db.dao().deadlineReceipts().size)
        assertEquals(completionBefore, db.dao().checkRecord("$group:play", "goal"))
    }
    @Test fun movingDeadlineIntoFutureReplacesReservationWithoutChangingCompletion() = runBlocking {
        catalog.setChecked("$group:play", "goal", true)
        val record = db.dao().checkRecord("$group:play", "goal")
        coordinator.save(enabled)
        edit.save(PROFILE, edit.load(PROFILE, group).copy(playEnd = clock.now + 5_400_000L))
        coordinator.reconcile(); assertEquals(clock.now + 1_800_000L, alarm.next)
        assertEquals(record, db.dao().checkRecord("$group:play", "goal"))
    }
    @Test fun leadTimeEditsRescheduleButCannotResendAnAlreadyDeliveredDeadline() = runBlocking {
        coordinator.save(enabled); coordinator.save(enabled.copy(playLeadMinutes = 180))
        assertEquals(1, notifier.posts.size)
        coordinator.save(enabled.copy(playLeadMinutes = 240)); coordinator.reconcile()
        assertEquals(1, notifier.posts.size); assertEquals(240, db.dao().deadlineSettings()!!.playLeadMinutes)
    }
    @Test fun eachSwitchAndMasterDisableCancelReservationsAndVisibleNotices() = runBlocking {
        coordinator.save(enabled.copy(playLeadMinutes = 180, shopLeadMinutes = 240))
        assertEquals(2, notifier.visible.size)
        coordinator.save(enabled.copy(playEnabled = false, shopLeadMinutes = 240))
        assertEquals(1, notifier.visible.size); assertTrue(notifier.posts.last().isShop)
        coordinator.save(enabled.copy(enabled = false)); assertTrue(notifier.visible.isEmpty()); assertNull(alarm.next)
        assertEquals(2, db.dao().deadlineReceipts().size)
    }
    @Test fun blockedPermissionDoesNotMarkDeliveryAndGrantCanDeliverBeforeDeadline() = runBlocking {
        notifier.play = false; notifier.shop = false
        coordinator.save(enabled.copy(playLeadMinutes = 180, shopLeadMinutes = 240))
        assertTrue(db.dao().deadlineReceipts().isEmpty()); assertNull(alarm.next)
        notifier.play = true; coordinator.reconcile()
        assertEquals(1, notifier.posts.size); assertFalse(notifier.posts.single().isShop)
        notifier.shop = true; coordinator.reconcile(); assertEquals(2, db.dao().deadlineReceipts().size)
    }
    @Test fun delayedCallbackAfterDeadlineNeverSendsAnExpiredReminder() = runBlocking {
        coordinator.save(enabled)
        clock.now += 4 * 3_600_000L; coordinator.reconcile()
        assertTrue(notifier.posts.isEmpty()); assertNull(alarm.next)
    }
    @Test fun failedPostRetriesWithoutReceiptOrBusyLoop() = runBlocking {
        notifier.fail = true; coordinator.save(enabled.copy(playLeadMinutes = 180))
        assertTrue(db.dao().deadlineReceipts().isEmpty()); assertEquals(clock.now + 60_000L, alarm.next)
        notifier.fail = false; clock.now = alarm.next!!; coordinator.reconcile()
        assertEquals(1, notifier.posts.size); assertEquals(1, db.dao().deadlineReceipts().size)
    }
    @Test fun simultaneousReconcilesAndNewCoordinatorDoNotDuplicateDeliveredDeadlines() = runBlocking {
        coordinator.save(enabled.copy(playLeadMinutes = 180, shopLeadMinutes = 240))
        coroutineScope { (1..6).map { async { coordinator.reconcile() } }.awaitAll() }
        DeadlineCoordinator(db, notifier, alarm, clock).reconcile()
        assertEquals(2, notifier.posts.size); assertEquals(2, db.dao().deadlineReceipts().size)
    }
    @Test fun invalidSettingsLeaveSavedSettingsRecordsAndAlarmUnchanged() = runBlocking {
        coordinator.save(enabled); val before = alarm.next
        for (bad in listOf(enabled.copy(playLeadMinutes = 0), enabled.copy(shopLeadMinutes = 10081), enabled.copy(id = 2))) {
            try { coordinator.save(bad); fail("invalid") } catch (_: CatalogException) { }
        }
        assertEquals(enabled, db.dao().deadlineSettings()); assertEquals(before, alarm.next)
        assertTrue(db.dao().deadlineReceipts().isEmpty())
    }
    @Test fun officialRefreshPreservesSettingsReceiptsManualEventsAndChecks() = runBlocking {
        val feed = TestFeed(); val official = CatalogRepository(db, CatalogClient(feed), clock)
        coordinator.save(enabled.copy(playLeadMinutes = 180)); val receipts = db.dao().deadlineReceipts()
        catalog.setChecked("$group:play", "goal", true)
        val check = db.dao().checkRecord("$group:play", "goal")
        official.refresh(); feed.publish(2) { it.getAsJsonArray("groups").remove(0) }; official.refresh()
        assertEquals(receipts, db.dao().deadlineReceipts()); assertEquals(check, db.dao().checkRecord("$group:play", "goal"))
        assertEquals(180, db.dao().deadlineSettings()!!.playLeadMinutes)
        assertTrue(db.dao().event("$group:play")!!.isUserCreated)
    }
    @Test fun schemaFourUpgradePreservesManualEventsAndChecksAndDefaultsAlertsOff() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "deadline-v4-migration.db"; context.deleteDatabase(name)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JsonParser.parseString(javaClass.classLoader!!.getResourceAsStream(
            "kr.ac.jeiu.gamescheduler.data.SchedulerDatabase/4.json")!!.reader().readText()).asJsonObject.getAsJsonObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            for (entry in schema.getAsJsonArray("entities")) {
                val e = entry.asJsonObject; legacy.execSQL(e["createSql"].asString.replace('$' + "{TABLE_NAME}", e["tableName"].asString))
            }
            for (entry in schema.getAsJsonArray("views")) {
                val v = entry.asJsonObject; legacy.execSQL(v["createSql"].asString.replace('$' + "{VIEW_NAME}", v["viewName"].asString))
            }
            schema.getAsJsonArray("setupQueries").forEach { legacy.execSQL(it.asString) }
            legacy.execSQL("INSERT INTO games VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(PROFILE, "내 엔필", "+08:00", 240, "Asia", 0))
            legacy.execSQL("""INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>("user-event:old:play", PROFILE, "user-event:old", "기존", "기존 참여", 1, "PLAY", "WINDOW", null,
                    clock.now - 100000, clock.now + 100000, "CONFIRMED", "", "{}", "메모", 0, 1))
            legacy.execSQL("INSERT INTO check_templates VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("user-event:old:play", "goal", "참여", 0, 0))
            legacy.execSQL("INSERT INTO check_records VALUES (?, ?, ?, ?)", arrayOf<Any>("user-event:old:play", "goal", 1, clock.now))
            legacy.version = 4
        }
        val migrated = Room.databaseBuilder(context, SchedulerDatabase::class.java, name).addMigrations(SchedulerDatabase.MIGRATION_4_5, SchedulerDatabase.MIGRATION_5_6).build()
        try {
            assertEquals(6, migrated.openHelper.readableDatabase.version)
            assertTrue(migrated.dao().event("user-event:old:play")!!.isUserCreated)
            assertTrue(migrated.dao().checkRecord("user-event:old:play", "goal")!!.isDone)
            assertEquals(240, migrated.dao().game(PROFILE)!!.dailyResetMinute)
            assertNull(migrated.dao().deadlineSettings()); assertTrue(migrated.dao().deadlineReceipts().isEmpty())
            val c = DeadlineCoordinator(migrated, notifier, alarm, clock); c.reconcile()
            assertNull(alarm.next); assertTrue(notifier.posts.isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
