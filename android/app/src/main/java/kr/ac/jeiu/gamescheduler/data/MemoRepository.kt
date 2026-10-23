package kr.ac.jeiu.gamescheduler.data

import androidx.room.withTransaction
import java.time.Clock
import java.util.UUID

data class MemoDraft(val memoId: String? = null, val title: String = "", val body: String = "", val eventKey: String? = null)

class MemoRepository(private val db: SchedulerDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = db.dao()
    suspend fun load(profile: String, id: String): MemoDraft {
        val memo = dao.memo(id)
        demand(memo != null && memo.profileKey == profile, "이 게임의 메모를 찾을 수 없습니다.")
        return MemoDraft(memo!!.memoId, memo.title, memo.body, memo.eventKey)
    }
    suspend fun save(profile: String, draft: MemoDraft): String {
        val title = draft.title.trim(); val body = draft.body
        demand(title.isNotEmpty() && title.length <= 100, "메모 제목은 1~100자로 입력해 주세요.")
        demand(body.isNotBlank() && body.length <= 10000, "메모 내용은 1~10,000자로 입력해 주세요.")
        return db.withTransaction {
            demand(dao.game(profile) != null, "게임을 먼저 등록해 주세요.")
            val old = draft.memoId?.let { dao.memo(it) }
            if (draft.memoId != null) demand(old != null && old.profileKey == profile, "이 게임의 메모만 수정할 수 있습니다.")
            draft.eventKey?.let { key ->
                val event = dao.event(key)
                demand(event != null && event.profileKey == profile, "연결할 이벤트는 같은 게임에 속해야 합니다.")
            }
            val id = old?.memoId ?: "memo:${UUID.randomUUID()}"
            val now = clock.millis()
            // Imports never write memos. Linking/unlinking changes no event metadata or check record.
            dao.putMemo(MemoEntity(id, profile, draft.eventKey, title, body, old?.createdAt ?: now, maxOf(old?.updatedAt ?: now, now)))
            id
        }
    }
}
