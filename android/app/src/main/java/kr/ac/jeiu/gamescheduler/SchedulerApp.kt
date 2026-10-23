package kr.ac.jeiu.gamescheduler

import android.app.Application
import androidx.room.Room
import kr.ac.jeiu.gamescheduler.data.CatalogRepository
import kr.ac.jeiu.gamescheduler.data.SchedulerDatabase

class SchedulerApp : Application() {
    val database by lazy {
        Room.databaseBuilder(this, SchedulerDatabase::class.java, "game_scheduler_database").build()
    }
    val repository by lazy { CatalogRepository(database) }
}
