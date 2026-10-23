package kr.ac.jeiu.gamescheduler

import kr.ac.jeiu.gamescheduler.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomePresentationTest {
    private val now = Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private val game = GameEntity("a", name = "명조", dailyResetMinute = 240, serverUtcOffset = "+09:00")
    private val cycle get() = DailyCycles.current(now, 240, "+09:00")
    private fun event(key: String = "e", profile: String = "a", start: Long = now - day, end: Long = now + 60_000) =
        EventEntity(key, profile, "group", "축제", key, 1, "PLAY", "WINDOW", null, start, end, "CONFIRMED", "", "{}", "")
    private fun details(e: EventEntity = event(), done: Boolean = false, missingCheck: Boolean = false) = EventDetails(e,
        listOf(CheckRow(e.sourceKey, "goal", "보상 수령", 0, missingCheck, done, if (done) now else null)), emptyList())
    private fun task(e: EventEntity = event()) = DailyTaskDetails(DailyTaskEntity(e.sourceKey, "daily", "일일 임무", "{}"), e)
    private fun record(e: String = "e", at: Long = cycle.startAt) = DailyTaskRecordEntity(e, "daily", at, now, 240)
    private fun summary(data: HomeDailyData) = summarizeDaily(listOf(game), data, now).single()

    @Test fun exactStartIsOngoingAndExactEndIsEnded() {
        val d = details(event(start = now, end = now + 1000))
        assertEquals(EventStatus.UPCOMING, eventStatus(d, now - 1))
        assertEquals(EventStatus.ONGOING, eventStatus(d, now))
        assertEquals(EventStatus.ENDED, eventStatus(d, now + 1000))
    }
    @Test fun completionRemainsInCompletedAfterDeadlineAndUncheckRestoresEnded() {
        val e = event(end = now)
        assertEquals(EventStatus.COMPLETED, eventStatus(details(e, true), now))
        assertEquals(EventStatus.ENDED, eventStatus(details(e), now))
    }
    @Test fun removedChecksDoNotContributeToCompletionAndNoChecksIsNotCompleted() {
        assertFalse(EventCard(details(done = true, missingCheck = true), now).allDone)
        assertEquals(EventStatus.ONGOING, eventStatus(details(done = true, missingCheck = true), now))
        assertEquals(EventStatus.ONGOING, eventStatus(EventDetails(event(), emptyList(), emptyList()), now))
    }
    @Test fun unknownAndInvalidWindowsNeverBecomeActiveOrImminentEvenIfCompleted() {
        listOf(event().copy(timeState = "NEEDS_REVIEW", startAt = null), event().copy(endAt = null), event(start = now, end = now)).forEach { e ->
            val card = EventCard(details(e, true), now)
            assertEquals(EventStatus.REVIEW, card.status); assertTrue(imminentCards(listOf(card)).isEmpty())
            assertTrue(eventTimeHint(e, now).contains("확인 필요"))
        }
    }
    @Test fun archiveAndInstantAreSeparateFromWindowFilters() {
        val archived = details(event().copy(missingFromLatest = true), true)
        val instant = details(event().copy(timeShape = "INSTANT", occursAt = now, startAt = null, endAt = null))
        assertEquals(EventStatus.ARCHIVED, eventStatus(archived, now)); assertEquals(EventStatus.INSTANT, eventStatus(instant, now))
        assertTrue(filteredCards(homeCards(listOf(archived, instant), now), EventFilter.ONGOING).isEmpty())
    }
    @Test fun imminentHasOnlyActiveIncompleteAndIncludesExact24HourBoundarySortedByDeadline() {
        val cards = homeCards(listOf(details(event("later", end = now + day)), details(event("first")),
            details(event("tooLate", end = now + day + 1)), details(event("done"), true),
            details(event("future", start = now + 1)), details(event("ended", end = now))), now)
        assertEquals(listOf("first", "later"), imminentCards(cards).map { it.details.event.sourceKey })
    }
    @Test fun filtersPartitionEverySavedEventWithoutDroppingReviewOrArchivedRows() {
        val rows = listOf(details(event()), details(event("future", start = now + 1)), details(event("done"), true),
            details(event("ended", end = now)), details(event("review").copy(timeState = "NEEDS_REVIEW")),
            details(event("instant").copy(timeShape = "INSTANT")), details(event("archive").copy(missingFromLatest = true)))
        val cards = homeCards(rows, now)
        assertEquals(7, filteredCards(cards, EventFilter.ALL).size)
        assertEquals(rows.map { it.event.sourceKey }.toSet(), EventFilter.entries.drop(1).flatMap { filteredCards(cards, it) }.map { it.details.event.sourceKey }.toSet())
        assertEquals(7, EventFilter.entries.drop(1).sumOf { filteredCards(cards, it).size })
    }
    @Test fun countdownRoundsUpPositiveMinutesAndDoesNotShowNegativeTime() {
        assertEquals("1분", remainingTime(1)); assertEquals("1분", remainingTime(60_000))
        assertEquals("2분", remainingTime(60_001)); assertEquals("1일 0시간", remainingTime(day))
        assertTrue(eventTimeHint(event(end = now), now).contains("기간 종료"))
        assertTrue(eventTimeHint(event().copy(phaseKind = "SHOP"), now).startsWith("보상 교환 마감"))
    }
    @Test fun summaryCombinesOfficialAndPersonalButOnlyThisCyclesRecords() {
        val data = HomeDailyData(listOf(task()), listOf(record(at = cycle.startAt - day)),
            listOf(PersonalTaskEntity("own", "a", "개인 숙제")), listOf(PersonalRecordEntity("own", cycle.startAt, now, 240, "+09:00")))
        assertEquals(2, summary(data).total); assertEquals(1, summary(data).done)
    }
    @Test fun unconfirmedUncheckedIsSeparateAndExplicitlyCheckedIsIncluded() {
        val d = task(event().copy(timeState = "NEEDS_REVIEW", startAt = null))
        assertEquals(1, summary(HomeDailyData(tasks = listOf(d))).needsReview)
        assertEquals(0, summary(HomeDailyData(tasks = listOf(d))).total)
        val s = summary(HomeDailyData(tasks = listOf(d), records = listOf(record().copy(periodConfirmedByUser = true))))
        assertEquals(1, s.total); assertEquals(1, s.done); assertEquals(0, s.needsReview)
    }
    @Test fun endedUpcomingRemovedAndPausedUncheckedTasksAreExcluded() {
        val tasks = listOf(task(event("ended", end = now)), task(event("future", start = now + 1)),
            task(event("removed").copy(missingFromLatest = true)), task().let { it.copy(task = it.task.copy(missingFromLatest = true)) })
        val s = summary(HomeDailyData(tasks = tasks, personal = listOf(PersonalTaskEntity("p", "a", "중지", enabled = false))))
        assertEquals(0, s.total); assertEquals(0, s.needsReview)
    }
    @Test fun completedCurrentRowsRemainCountedAfterExpiryOrPausingWithoutDeletingHistory() {
        val r = record()
        val data = HomeDailyData(tasks = listOf(task(event(end = now))), records = listOf(r),
            personal = listOf(PersonalTaskEntity("p", "a", "중지", enabled = false)),
            personalRecords = listOf(PersonalRecordEntity("p", cycle.startAt, now, 240, "+09:00")))
        assertEquals(2, summary(data).done); assertEquals(2, summary(data).total)
        assertEquals(listOf(r), data.records)
    }
    @Test fun resetUnsetNeverShowsFalseCompletionAndOtherGamesAreIsolated() {
        val b = game.copy(profileKey = "b", name = "소전2", dailyResetMinute = null)
        val rows = summarizeDaily(listOf(game, b), HomeDailyData(tasks = listOf(task()), records = listOf(record()),
            personal = listOf(PersonalTaskEntity("other", "b", "다른 게임"))), now)
        assertEquals(1, rows[0].done); assertEquals(1, rows[0].total)
        assertNull(rows[1].cycle); assertEquals(0, rows[1].total)
    }
    @Test fun summaryChangesAtEachGamesOwnResetAndKeepsPreviousRecordsUnmodified() {
        val at = Instant.parse("2026-10-23T19:00:00Z").toEpochMilli() // 04:00 UTC+9, 03:00 UTC+8
        val b = game.copy(profileKey = "b", serverUtcOffset = "+08:00")
        val oldA = DailyCycles.current(at - 1, 240, "+09:00").startAt
        val oldB = DailyCycles.current(at - 1, 240, "+08:00").startAt
        val records = listOf(PersonalRecordEntity("a-task", oldA, at - 1, 240, "+09:00"),
            PersonalRecordEntity("b-task", oldB, at - 1, 240, "+08:00"))
        val data = HomeDailyData(personal = listOf(PersonalTaskEntity("a-task", "a", "A"), PersonalTaskEntity("b-task", "b", "B")), personalRecords = records)
        assertEquals(listOf(1, 1), summarizeDaily(listOf(game, b), data, at - 1).map { it.done })
        assertEquals(listOf(0, 1), summarizeDaily(listOf(game, b), data, at).map { it.done })
        assertEquals(records, data.personalRecords)
    }
}
