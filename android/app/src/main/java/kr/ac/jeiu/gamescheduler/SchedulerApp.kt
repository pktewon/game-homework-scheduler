package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.room.Room
import kr.ac.jeiu.gamescheduler.data.CatalogRepository
import kr.ac.jeiu.gamescheduler.data.SchedulerDatabase
import kr.ac.jeiu.gamescheduler.data.DailyRepository

class SchedulerApp : Application() {
    val database by lazy {
        Room.databaseBuilder(this, SchedulerDatabase::class.java, "game_scheduler_database")
            .addMigrations(SchedulerDatabase.MIGRATION_1_2).build()
    }
    val repository by lazy { CatalogRepository(database) }
    val dailyRepository by lazy { DailyRepository(database) }
}
