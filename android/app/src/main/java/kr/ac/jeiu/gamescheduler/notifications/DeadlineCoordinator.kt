package kr.ac.jeiu.gamescheduler.notifications

import androidx.room.withTransaction
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.ac.jeiu.gamescheduler.data.*

interface DeadlineNotifier {
    fun allowed(shop: Boolean): Boolean
    fun retain(tags: Set<String>)
    fun post(notice: DeadlineNotice): Boolean
}
fun interface DeadlineAlarm { fun schedule(at: Long?) }
data class DeadlineStatus(val pending: Int = 0, val nextAt: Long? = null, val error: String = "")

class DeadlineCoordinator(private val db: SchedulerDatabase, private val notifier: DeadlineNotifier,
    private val alarm: DeadlineAlarm, private val clock: Clock = Clock.systemUTC()) {
    private val mutex = Mutex()
    private val status = MutableStateFlow(DeadlineStatus())
    val state = status.asStateFlow()

    suspend fun save(settings: DeadlineSettingsEntity) {
        DeadlinePolicy.validate(settings)
        db.dao().putDeadlineSettings(settings)
        reconcile()
    }

    // Always reload current metadata/checks. Alarm Intents contain no trusted event snapshot.
    suspend fun reconcile() = mutex.withLock {
        try {
            val result = db.withTransaction {
                val dao = db.dao(); val now = clock.millis()
                val settings = dao.deadlineSettings() ?: DeadlineSettingsEntity()
                val notices = DeadlinePolicy.notices(dao.allEvents(), dao.games().associate { it.profileKey to it.name }, settings, now)
                    .filter { notifier.allowed(it.isShop) }
                notifier.retain(notices.map { it.key.tag }.toSet())
                val delivered = dao.deadlineReceipts().map { DeliveryKey(it.eventKey, it.deadlineAt) }.toMutableSet()
                val pending = notices.filterNot { it.key in delivered }
                for (notice in pending.filter { it.triggerAt <= now }) {
                    if (notifier.post(notice)) {
                        dao.putDeadlineReceipt(DeadlineReceiptEntity(notice.event.sourceKey, notice.key.deadlineAt, now))
                        delivered.add(notice.key)
                    }
                }
                val waiting = pending.filterNot { it.key in delivered }
                DeadlineStatus(waiting.size, waiting.minOfOrNull { if (it.triggerAt > now) it.triggerAt else now + 60_000L })
            }
            alarm.schedule(result.nextAt)
            status.value = result
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // Retain data and retry transient failures without an unbounded immediate loop.
            runCatching { alarm.schedule(clock.millis() + 15 * 60_000L) }
            status.value = DeadlineStatus(error = "알림 예약을 갱신하지 못했습니다. 다시 시도해 주세요.")
        }
    }
}
