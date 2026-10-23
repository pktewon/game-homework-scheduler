package kr.ac.jeiu.gamescheduler.notifications

import kr.ac.jeiu.gamescheduler.data.*

data class DeliveryKey(val eventKey: String, val deadlineAt: Long) {
    val tag get() = "deadline:$eventKey:$deadlineAt"
}
data class DeadlineNotice(val details: EventDetails, val gameName: String, val triggerAt: Long) {
    val event get() = details.event
    val key get() = DeliveryKey(event.sourceKey, event.endAt!!)
    val isShop get() = event.phaseKind == "SHOP"
    val kindLabel get() = if (isShop) "보상 교환" else "참여"
}

object DeadlinePolicy {
    fun validate(settings: DeadlineSettingsEntity) {
        demand(settings.id == 1 && settings.playLeadMinutes in 1..10080 && settings.shopLeadMinutes in 1..10080,
            "알림 시점은 1~10,080분(7일) 사이로 입력해 주세요.")
    }
    fun notices(events: List<EventDetails>, games: Map<String, String>, settings: DeadlineSettingsEntity, now: Long): List<DeadlineNotice> {
        if (!settings.enabled) return emptyList()
        validate(settings)
        return events.mapNotNull { details ->
            val e = details.event
            val start = e.startAt; val end = e.endAt
            // Defend against inconsistent rows as well as NEEDS_REVIEW.
            if (e.missingFromLatest || e.timeShape != "WINDOW" || e.timeState != "CONFIRMED" ||
                start == null || end == null || start >= end || end <= now) return@mapNotNull null
            val lead = when (e.phaseKind) {
                "SHOP" -> if (settings.shopEnabled) settings.shopLeadMinutes else return@mapNotNull null
                "PLAY", "ATTENDANCE", "CHALLENGE", "WEB" -> if (settings.playEnabled) settings.playLeadMinutes else return@mapNotNull null
                else -> return@mapNotNull null
            }
            val checks = details.checks.filterNot { it.missingFromLatest }
            if (checks.isEmpty() || checks.all { it.isDone }) return@mapNotNull null
            // Do not tell the user to participate before the event has opened.
            DeadlineNotice(details, games[e.profileKey].orEmpty(), maxOf(start, end - lead * 60_000L))
        }.sortedWith(compareBy<DeadlineNotice> { it.triggerAt }.thenBy { it.event.sourceKey })
    }
}
