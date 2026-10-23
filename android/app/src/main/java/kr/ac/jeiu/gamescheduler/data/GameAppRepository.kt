package kr.ac.jeiu.gamescheduler.data

import androidx.room.withTransaction

data class LaunchableApp(val packageName: String, val label: String)
fun validAppPackage(value: String) = value.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)

class GameAppRepository(private val db: SchedulerDatabase) {
    suspend fun connect(profile: String, target: LaunchableApp?) {
        target?.let {
            demand(validAppPackage(it.packageName), "실행 앱의 패키지명이 올바르지 않습니다.")
            demand(it.label.isNotBlank() && it.label.length <= 120, "실행 앱의 이름을 확인해 주세요.")
        }
        db.withTransaction {
            demand(db.dao().game(profile) != null, "게임을 먼저 등록해 주세요.")
            // This is the only write for a link change. Launch actions never write completion records.
            db.dao().setGameApp(profile, target?.packageName, target?.label)
        }
    }
}
