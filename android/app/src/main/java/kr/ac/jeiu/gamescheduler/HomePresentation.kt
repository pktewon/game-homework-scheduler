package kr.ac.jeiu.gamescheduler

import kr.ac.jeiu.gamescheduler.data.*

enum class EventStatus(val label: String, val rank: Int) {
    ONGOING("진행 중", 0), UPCOMING("예정", 2), COMPLETED("완료", 3), ENDED("종료", 4),
    REVIEW("확인 필요", 1), INSTANT("업데이트", 5), ARCHIVED("보관", 6),
}

enum class EventFilter(val label: String, val status: EventStatus? = null) {
    ALL("전체"), ONGOING("진행 중", EventStatus.ONGOING), UPCOMING("예정", EventStatus.UPCOMING),
    COMPLETED("완료", EventStatus.COMPLETED), ENDED("종료", EventStatus.ENDED),
    REVIEW("확인 필요", EventStatus.REVIEW), INSTANT("업데이트", EventStatus.INSTANT), ARCHIVED("보관", EventStatus.ARCHIVED),
}

fun eventStatus(details: EventDetails, now: Long): EventStatus {
    val e = details.event
    val checks = details.checks.filterNot { it.missingFromLatest }
    return when {
        e.missingFromLatest -> EventStatus.ARCHIVED
        e.timeShape == "INSTANT" -> EventStatus.INSTANT
        e.timeState != "CONFIRMED" || e.startAt == null || e.endAt == null || e.startAt >= e.endAt -> EventStatus.REVIEW
        checks.isNotEmpty() && checks.all { it.isDone } -> EventStatus.COMPLETED
        now >= e.endAt -> EventStatus.ENDED
        now < e.startAt -> EventStatus.UPCOMING
        else -> EventStatus.ONGOING
    }
}

fun phaseLabel(e: EventEntity) = when (e.phaseKind) { "SHOP" -> "보상 교환"; "PLAY" -> "참여"; else -> "일정" }
fun remainingTime(milliseconds: Long): String {
    val minutes = (milliseconds.coerceAtLeast(1) - 1) / 60_000 + 1
    return when {
        minutes >= 1440 -> "${minutes / 1440}일 ${minutes % 1440 / 60}시간"
        minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
        else -> "${minutes}분"
    }
}
fun eventTimeHint(e: EventEntity, now: Long): String = when {
    e.missingFromLatest -> "보관된 일정 · 완료 기록 유지"
    e.timeShape == "INSTANT" -> "업데이트 시점: ${displayTime(e.occursAt)} (한국 시간)"
    e.timeState != "CONFIRMED" || e.startAt == null || e.endAt == null || e.startAt >= e.endAt -> "기간 확인 필요 · 남은 시간과 마감 알림 제외"
    now >= e.endAt -> "${phaseLabel(e)} 기간 종료"
    now < e.startAt -> "시작까지 ${remainingTime(e.startAt - now)}"
    else -> "${phaseLabel(e)} 마감까지 ${remainingTime(e.endAt - now)}"
}
fun eventProgress(details: EventDetails): String {
    val current = details.checks.filterNot { it.missingFromLatest }
    return if (current.isEmpty()) "체크 항목 없음" else "완료 ${current.count { it.isDone }}/${current.size}"
}
fun homeCards(events: List<EventDetails>, now: Long, names: Map<String, String> = emptyMap()): List<EventCard> =
    events.map { EventCard(it, now, names[it.event.profileKey].orEmpty()) }.sortedWith(
        compareBy<EventCard> { it.rank }.thenBy { if (it.status in setOf(EventStatus.ENDED, EventStatus.COMPLETED)) -it.sortTime else it.sortTime }
            .thenBy { it.details.event.sourceKey })
fun imminentCards(cards: List<EventCard>) = cards.filter {
    it.status == EventStatus.ONGOING && it.details.event.endAt!! - it.now <= 86_400_000L
}
fun filteredCards(cards: List<EventCard>, filter: EventFilter) = cards.filter { filter.status == null || it.status == filter.status }

data class HomeDailyData(
    val tasks: List<DailyTaskDetails> = emptyList(), val records: List<DailyTaskRecordEntity> = emptyList(),
    val personal: List<PersonalTaskEntity> = emptyList(), val personalRecords: List<PersonalRecordEntity> = emptyList(),
)
data class GameDailySummary(val game: GameEntity, val cycle: DailyCycle?, val done: Int, val total: Int, val needsReview: Int)

fun summarizeDaily(games: List<GameEntity>, data: HomeDailyData, now: Long): List<GameDailySummary> = games.map { game ->
    val cycle = game.dailyResetMinute?.let { DailyCycles.current(now, it, game.serverUtcOffset) }
    if (cycle == null) GameDailySummary(game, null, 0, 0, 0) else {
        val completed = data.records.filter { it.cycleStartAt == cycle.startAt }.map { it.eventKey to it.sourceTaskKey }.toSet()
        val ownCompleted = data.personalRecords.filter { it.cycleStartAt == cycle.startAt }.map { it.taskId }.toSet()
        var done = 0; var total = 0; var review = 0
        data.tasks.filter { it.event.profileKey == game.profileKey }.forEach { d ->
            val checked = (d.task.eventKey to d.task.sourceTaskKey) in completed
            val availability = dailyAvailability(d.task, d.event, true, now)
            // A completed row stays in this cycle's summary even after expiry/retirement.
            if (checked || availability == DailyAvailability.READY) { total++; if (checked) done++ }
            else if (availability == DailyAvailability.NEEDS_PERIOD_CONFIRMATION) review++
        }
        data.personal.filter { it.profileKey == game.profileKey }.forEach { task ->
            if (task.enabled || task.taskId in ownCompleted) { total++; if (task.taskId in ownCompleted) done++ }
        }
        GameDailySummary(game, cycle, done, total, review)
    }
}
