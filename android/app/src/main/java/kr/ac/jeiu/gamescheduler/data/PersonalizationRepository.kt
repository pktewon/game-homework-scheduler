package kr.ac.jeiu.gamescheduler.data

import androidx.room.withTransaction
import java.util.UUID

val USER_TIME_OFFSETS = listOf("+09:00", "+08:00", "+00:00")

class PersonalizationRepository(private val db: SchedulerDatabase) {
    private val dao = db.dao()
    val games = dao.observeGames()
    suspend fun ensureDefaultGame() { dao.ensureGame(GameEntity(PROFILE)) }

    suspend fun saveGame(id: String?, name: String, serverLabel: String, offset: String): String {
        val title = name.trim()
        val server = serverLabel.trim()
        demand(title.isNotBlank() && title.length <= 60, "게임 이름은 1~60자로 입력해 주세요.")
        demand(server.isNotBlank() && server.length <= 80, "서버·지역은 1~80자로 입력해 주세요.")
        demand(offset in USER_TIME_OFFSETS, "지원하지 않는 시간 기준입니다.")
        return db.withTransaction {
            if (id == null) {
                val key = "user-game:" + UUID.randomUUID()
                dao.ensureGame(GameEntity(key, title, offset, serverLabel = server, isUserCreated = true))
                key
            } else {
                val old = dao.game(id) ?: throw CatalogException("수정할 게임이 없습니다.")
                demand(old.isUserCreated || (id == PROFILE && offset == "+08:00" && server == old.serverLabel),
                    "공식 엔필 일정의 서버와 시간 기준은 Asia / UTC+8로 유지합니다.")
                dao.editGame(id, title, server, offset)
                id
            }
        }
    }

    suspend fun saveTask(profile: String, taskId: String?, title: String, notes: String, enabled: Boolean): String {
        val cleanTitle = title.trim()
        demand(cleanTitle.isNotBlank() && cleanTitle.length <= 100, "숙제 이름은 1~100자로 입력해 주세요.")
        demand(notes.length <= 1000, "숙제 설명은 1,000자 이내로 입력해 주세요.")
        return db.withTransaction {
            demand(dao.game(profile) != null, "게임을 먼저 등록해 주세요.")
            if (taskId == null) {
                val key = "user-task:" + UUID.randomUUID()
                dao.createPersonalTask(PersonalTaskEntity(key, profile, cleanTitle, notes.trim(), enabled))
                key
            } else {
                val old = dao.personalTask(taskId) ?: throw CatalogException("직접 등록한 숙제가 없습니다.")
                demand(old.profileKey == profile, "다른 게임의 숙제를 수정할 수 없습니다.")
                dao.editPersonalTask(taskId, cleanTitle, notes.trim(), enabled)
                taskId
            }
        }
    }
}
