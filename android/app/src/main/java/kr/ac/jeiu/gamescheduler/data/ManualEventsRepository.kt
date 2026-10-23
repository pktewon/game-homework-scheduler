package kr.ac.jeiu.gamescheduler.data

import androidx.room.withTransaction
import java.util.UUID

data class EventDraft(
    val groupKey: String? = null, val title: String = "", val notes: String = "",
    val playStart: Long? = null, val playEnd: Long? = null,
    val includeShop: Boolean = false, val shopStart: Long? = null, val shopEnd: Long? = null,
)

class ManualEventsRepository(private val db: SchedulerDatabase) {
    private val dao = db.dao()
    suspend fun load(profile: String, group: String): EventDraft {
        val rows = dao.eventGroup(profile, group)
        demand(rows.isNotEmpty() && rows.all { it.isUserCreated }, "직접 등록한 이벤트만 수정할 수 있습니다.")
        val play = rows.singleOrNull { it.sourceKey == "$group:play" }
            ?: throw CatalogException("참여 일정을 찾을 수 없습니다.")
        val shop = rows.singleOrNull { it.sourceKey == "$group:shop" }
        return EventDraft(group, play.groupTitle, play.notes, play.startAt, play.endAt,
            shop != null && !shop.missingFromLatest, shop?.startAt, shop?.endAt)
    }

    suspend fun save(profile: String, draft: EventDraft): String {
        val title = draft.title.trim()
        demand(title.isNotBlank() && title.length <= 100, "이벤트 이름은 1~100자로 입력해 주세요.")
        demand(draft.notes.length <= 1000, "메모는 1,000자 이내로 입력해 주세요.")
        fun valid(start: Long?, end: Long?, label: String) {
            demand(start == null || end == null || start < end, "$label 종료는 시작보다 뒤여야 합니다.")
        }
        valid(draft.playStart, draft.playEnd, "참여 기간")
        if (draft.includeShop) valid(draft.shopStart, draft.shopEnd, "보상 교환 기간")
        return db.withTransaction {
            demand(dao.game(profile) != null, "게임을 먼저 등록해 주세요.")
            val group = draft.groupKey ?: ("user-event:" + UUID.randomUUID())
            val oldRows = dao.eventGroup(profile, group)
            if (draft.groupKey != null) demand(oldRows.isNotEmpty() && oldRows.all { it.isUserCreated }, "직접 등록한 이벤트만 수정할 수 있습니다.")
            fun phase(kind: String, suffix: String, start: Long?, end: Long?): EventEntity {
                val old = oldRows.find { it.sourceKey == "$group:$suffix" }
                return EventEntity("$group:$suffix", profile, group, title,
                    "$title · ${if (kind == "SHOP") "보상 교환" else "참여"}", (old?.revision ?: 0) + 1,
                    kind, "WINDOW", null, start, end,
                    if (start == null || end == null) "NEEDS_REVIEW" else "CONFIRMED",
                    "", "{}", draft.notes.trim(), isUserCreated = true)
            }
            // Keep the group/phase/item keys. Only metadata is upserted; records are untouched.
            val play = phase("PLAY", "play", draft.playStart, draft.playEnd)
            dao.putEvent(play)
            dao.putCheck(CheckTemplateEntity(play.sourceKey, "goal", "참여 목표 완료 확인", 0))
            dao.putCheck(CheckTemplateEntity(play.sourceKey, "rewards", "참여 보상 수령 확인", 1))
            val oldShop = oldRows.find { it.sourceKey == "$group:shop" }
            if (draft.includeShop) {
                val shop = phase("SHOP", "shop", draft.shopStart, draft.shopEnd)
                dao.putEvent(shop)
                dao.putCheck(CheckTemplateEntity(shop.sourceKey, "wanted-exchanges", "교환 보상 수령 확인", 0))
            } else if (oldShop != null) {
                // Hiding an exchange window must not delete its completion or its previous dates.
                dao.putEvent(oldShop.copy(groupTitle = title, title = "$title · 보상 교환", notes = draft.notes.trim(),
                    missingFromLatest = true, revision = oldShop.revision + 1))
            }
            group
        }
    }
}
