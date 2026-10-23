package kr.ac.jeiu.gamescheduler.data

import androidx.room.withTransaction
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

data class DailyCycle(val startAt: Long, val endAt: Long, val resetMinute: Int)

object DailyCycles {
    // Use the game's explicit fixed offset, never the device timezone or midnight.
    fun current(now: Long, minute: Int, serverOffset: String): DailyCycle {
        demand(minute in 0..1439, "초기화 시각 범위가 올바르지 않습니다.")
        val offset = ZoneOffset.of(serverOffset)
        val localNow = Instant.ofEpochMilli(now).atOffset(offset)
        var start = localNow.toLocalDate().atStartOfDay().plusMinutes(minute.toLong()).atOffset(offset)
        if (localNow.isBefore(start)) start = start.minusDays(1)
        return DailyCycle(start.toInstant().toEpochMilli(), start.plusDays(1).toInstant().toEpochMilli(), minute)
    }
}

enum class DailyAvailability(val label: String) {
    READY("이번 회차 체크 가능"), NEEDS_PERIOD_CONFIRMATION("기간 확인 필요 · 직접 확인 후 이번 회차 체크"),
    RESET_UNSET("초기화 시각을 먼저 설정해 주세요"), UPCOMING("아직 시작하지 않은 이벤트"),
    ENDED("이벤트 기간 종료"), RETIRED("이전 공지 항목 · 기록 보관"), INVALID("기간을 확인해 주세요"),
}

fun dailyAvailability(task: DailyTaskEntity, event: EventEntity, configured: Boolean, now: Long): DailyAvailability = when {
    !configured -> DailyAvailability.RESET_UNSET
    task.missingFromLatest || event.missingFromLatest -> DailyAvailability.RETIRED
    event.timeShape != "WINDOW" -> DailyAvailability.INVALID
    event.startAt != null && now < event.startAt -> DailyAvailability.UPCOMING
    event.endAt != null && now >= event.endAt -> DailyAvailability.ENDED
    event.timeState == "NEEDS_REVIEW" -> DailyAvailability.NEEDS_PERIOD_CONFIRMATION
    event.timeState == "CONFIRMED" && event.startAt != null && event.endAt != null -> DailyAvailability.READY
    else -> DailyAvailability.INVALID
}

class DailyRepository(private val db: SchedulerDatabase, private val clock: Clock = Clock.systemUTC(), private val profileKey: String = PROFILE) {
    private val dao = db.dao()
    val game = dao.observeGame(profileKey)
    val tasks = dao.observeDailyTasks(profileKey)
    val history = dao.observeDailyHistory(profileKey)
    val personalTasks = dao.observePersonalTasks(profileKey)
    val personalHistory = dao.observePersonalHistory(profileKey)

    suspend fun setResetMinute(minute: Int?) {
        demand(minute == null || minute in 0..1439, "초기화 시각은 00:00~23:59 사이로 설정해 주세요.")
        db.withTransaction {
            if (profileKey == PROFILE) dao.ensureGame(GameEntity(PROFILE))
            demand(dao.game(profileKey) != null, "게임을 먼저 등록해 주세요.")
            dao.setResetMinute(profileKey, minute)
            // Changing settings never moves or deletes previous cycle records.
        }
    }

    suspend fun setChecked(eventKey: String, taskKey: String, expectedCycle: DailyCycle,
                           checked: Boolean, confirmUnknownPeriod: Boolean = false) {
        db.withTransaction {
            val game = dao.game(profileKey) ?: throw CatalogException("초기화 시각을 설정해 주세요.")
            val reset = game.dailyResetMinute ?: throw CatalogException("초기화 시각을 설정해 주세요.")
            val now = clock.millis()
            val current = DailyCycles.current(now, reset, game.serverUtcOffset)
            demand(expectedCycle == current, "회차 또는 초기화 설정이 바뀌었습니다. 새 회차에서 다시 체크해 주세요.")
            val event = dao.event(eventKey) ?: throw CatalogException("일정이 없습니다.")
            val task = dao.task(eventKey, taskKey) ?: throw CatalogException("숙제가 없습니다.")
            demand(event.profileKey == profileKey, "이용 서버가 다른 숙제입니다.")
            if (!checked) {
                // A user may correct this cycle even after the event ended. History is read-only.
                dao.uncheckDaily(eventKey, taskKey, current.startAt)
                return@withTransaction
            }
            val availability = dailyAvailability(task, event, true, now)
            demand(availability == DailyAvailability.READY ||
                (availability == DailyAvailability.NEEDS_PERIOD_CONFIRMATION && confirmUnknownPeriod), availability.label)
            dao.completeDailyOnce(DailyTaskRecordEntity(eventKey, taskKey, current.startAt, now, reset,
                availability == DailyAvailability.NEEDS_PERIOD_CONFIRMATION))
        }
    }

    suspend fun setPersonalChecked(taskId: String, expectedCycle: DailyCycle, checked: Boolean) {
        db.withTransaction {
            val game = dao.game(profileKey) ?: throw CatalogException("게임이 없습니다.")
            val minute = game.dailyResetMinute ?: throw CatalogException("초기화 시각을 설정해 주세요.")
            val now = clock.millis()
            val cycle = DailyCycles.current(now, minute, game.serverUtcOffset)
            demand(cycle == expectedCycle, "회차 또는 초기화 설정이 바뀌었습니다. 새 회차에서 다시 체크해 주세요.")
            val task = dao.personalTask(taskId) ?: throw CatalogException("숙제가 없습니다.")
            demand(task.profileKey == profileKey, "다른 게임의 숙제입니다.")
            if (!checked) { dao.uncheckPersonal(taskId, cycle.startAt); return@withTransaction }
            demand(task.enabled, "사용을 중지한 숙제입니다.")
            dao.completePersonalOnce(PersonalRecordEntity(taskId, cycle.startAt, now, minute, game.serverUtcOffset))
        }
    }
}
